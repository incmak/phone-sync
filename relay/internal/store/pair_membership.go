package store

import (
	"bytes"
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"go.etcd.io/bbolt"
)

const bucketDeviceIdentity = "device_identity_v3"

type pinnedIdentity struct {
	Enc  []byte `json:"enc"`
	Sign []byte `json:"sign"`
}

func capabilityKey(pairID, deviceID string) []byte { return []byte(pairID + "\x00" + deviceID) }

func membershipsTx(tx bucketTransaction, device string) ([]string, error) {
	b := tx.Bucket([]byte(bucketByDevice))
	if b == nil || b.Get([]byte(device)) == nil {
		return nil, nil
	}
	var ids []string
	if err := json.Unmarshal(b.Get([]byte(device)), &ids); err != nil || len(ids) == 0 || len(ids) > 2 {
		return nil, ErrPairStoreCorrupt
	}
	for i, id := range ids {
		if id == "" || strings.ContainsRune(id, '\x00') || (i > 0 && ids[i-1] >= id) {
			return nil, ErrPairStoreCorrupt
		}
	}
	return ids, nil
}

func admitPairMembershipsTx(tx *bbolt.Tx, cp ConfirmedPair) error {
	for _, id := range []string{cp.PairID, cp.DeviceA, cp.DeviceB} {
		if id == "" || strings.ContainsRune(id, '\x00') {
			return ErrPairConflict
		}
	}
	if cp.DeviceA == cp.DeviceB {
		return ErrPairConflict
	}
	byDevice, err := tx.CreateBucketIfNotExists([]byte(bucketByDevice))
	if err != nil {
		return err
	}
	identities, err := tx.CreateBucketIfNotExists([]byte(bucketDeviceIdentity))
	if err != nil {
		return err
	}
	for _, entry := range []struct {
		device   string
		identity pinnedIdentity
	}{
		{cp.DeviceA, pinnedIdentity{cp.AEncPubkey, cp.ASignPubkey}}, {cp.DeviceB, pinnedIdentity{cp.BEncPubkey, cp.BSignPubkey}},
	} {
		ids, err := membershipsTx(tx, entry.device)
		if err != nil {
			return err
		}
		if raw := identities.Get([]byte(entry.device)); raw != nil {
			var old pinnedIdentity
			if json.Unmarshal(raw, &old) != nil {
				return ErrPairStoreCorrupt
			}
			if !bytes.Equal(old.Enc, entry.identity.Enc) || !bytes.Equal(old.Sign, entry.identity.Sign) {
				return ErrPairConflict
			}
		}
		if !slices.Contains(ids, cp.PairID) {
			if len(ids) >= 2 {
				return ErrPairConflict
			}
			// Re-pairing an existing peer requires revoking the old generation first.
			for _, id := range ids {
				raw := tx.Bucket([]byte(bucketConfirmed)).Get([]byte(id))
				pair, err := decodeConfirmedPair(raw)
				if err != nil {
					return ErrPairStoreCorrupt
				}
				if (pair.DeviceA == cp.DeviceA && pair.DeviceB == cp.DeviceB) || (pair.DeviceA == cp.DeviceB && pair.DeviceB == cp.DeviceA) {
					return ErrPairConflict
				}
			}
			ids = append(ids, cp.PairID)
			slices.Sort(ids)
		}
		encoded, err := json.Marshal(ids)
		if err != nil {
			return err
		}
		if err := byDevice.Put([]byte(entry.device), encoded); err != nil {
			return err
		}
		encoded, err = json.Marshal(entry.identity)
		if err != nil {
			return err
		}
		if err := identities.Put([]byte(entry.device), encoded); err != nil {
			return err
		}
	}
	return nil
}

func removeMembershipTx(tx *bbolt.Tx, device, pairID string) error {
	ids, err := membershipsTx(tx, device)
	if err != nil {
		return err
	}
	idx := slices.Index(ids, pairID)
	if idx < 0 {
		return ErrPairStoreCorrupt
	}
	ids = slices.Delete(ids, idx, idx+1)
	if len(ids) == 0 {
		// Registry lifetime follows membership; a completely removed device may regenerate.
		if err := tx.Bucket([]byte(bucketDeviceIdentity)).Delete([]byte(device)); err != nil {
			return err
		}
		return tx.Bucket([]byte(bucketByDevice)).Delete([]byte(device))
	}
	encoded, err := json.Marshal(ids)
	if err != nil {
		return err
	}
	return tx.Bucket([]byte(bucketByDevice)).Put([]byte(device), encoded)
}

// Conversion is one transaction, including mailbox namespaces. Every legacy
// membership must be reciprocal; an orphan must never be assigned by guesswork.
func migratePairMembershipsTx(tx *bbolt.Tx) error {
	pairs := []ConfirmedPair{}
	confirmed := tx.Bucket([]byte(bucketConfirmed))
	legacy := tx.Bucket([]byte(bucketByDevice))
	if confirmed != nil {
		if err := confirmed.ForEach(func(key, raw []byte) error {
			cp, err := decodeConfirmedPair(raw)
			if err != nil || cp.PairID != string(key) || cp.DeviceA == cp.DeviceB || cp.DeviceA == "" || cp.DeviceB == "" {
				return ErrPairStoreCorrupt
			}
			if legacy == nil || !bytes.Equal(legacy.Get([]byte(cp.DeviceA)), key) || !bytes.Equal(legacy.Get([]byte(cp.DeviceB)), key) {
				return ErrPairStoreCorrupt
			}
			pairs = append(pairs, cp)
			return nil
		}); err != nil {
			return err
		}
	}
	if legacy != nil {
		if err := legacy.ForEach(func(device, pairID []byte) error {
			if confirmed == nil {
				return ErrPairStoreCorrupt
			}
			cp, err := decodeConfirmedPair(confirmed.Get(pairID))
			if err != nil || (cp.DeviceA != string(device) && cp.DeviceB != string(device)) {
				return ErrPairStoreCorrupt
			}
			return nil
		}); err != nil {
			return err
		}
	}
	// Mailbox ownership still resolves through the legacy one-pair index here.
	if err := migrateMailboxScopesTx(tx); err != nil {
		return err
	}
	caps := tx.Bucket([]byte(bucketCapabilities))
	type capEntry struct{ key, raw []byte }
	entries := []capEntry{}
	if caps != nil {
		if err := caps.ForEach(func(device, raw []byte) error {
			if legacy == nil || legacy.Get(device) == nil {
				return ErrPairStoreCorrupt
			}
			if _, err := decodeCapabilities(raw); err != nil {
				return err
			}
			entries = append(entries, capEntry{capabilityKey(string(legacy.Get(device)), string(device)), bytes.Clone(raw)})
			return nil
		}); err != nil {
			return err
		}
		if err := tx.DeleteBucket([]byte(bucketCapabilities)); err != nil {
			return err
		}
	}
	if legacy != nil {
		if err := tx.DeleteBucket([]byte(bucketByDevice)); err != nil {
			return err
		}
	}
	for _, cp := range pairs {
		if err := admitPairMembershipsTx(tx, cp); err != nil {
			return err
		}
	}
	if len(entries) > 0 {
		caps, err := tx.CreateBucketIfNotExists([]byte(bucketCapabilities))
		if err != nil {
			return err
		}
		for _, entry := range entries {
			if err := caps.Put(entry.key, entry.raw); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateMembershipsTx(tx *bbolt.Tx) error {
	confirmed := tx.Bucket([]byte(bucketConfirmed))
	if confirmed != nil {
		if err := confirmed.ForEach(func(key, raw []byte) error {
			cp, err := decodeConfirmedPair(raw)
			if err != nil || cp.PairID != string(key) || cp.DeviceA == cp.DeviceB {
				return ErrPairStoreCorrupt
			}
			for _, entry := range []struct {
				device    string
				enc, sign []byte
			}{{cp.DeviceA, cp.AEncPubkey, cp.ASignPubkey}, {cp.DeviceB, cp.BEncPubkey, cp.BSignPubkey}} {
				ids, err := membershipsTx(tx, entry.device)
				if err != nil || !slices.Contains(ids, cp.PairID) {
					return ErrPairStoreCorrupt
				}
				registry := tx.Bucket([]byte(bucketDeviceIdentity))
				if registry == nil {
					return ErrPairStoreCorrupt
				}
				var identity pinnedIdentity
				if json.Unmarshal(registry.Get([]byte(entry.device)), &identity) != nil || !bytes.Equal(identity.Enc, entry.enc) || !bytes.Equal(identity.Sign, entry.sign) {
					return ErrPairStoreCorrupt
				}
			}
			return nil
		}); err != nil {
			return err
		}
	}
	if byDevice := tx.Bucket([]byte(bucketByDevice)); byDevice != nil {
		return byDevice.ForEach(func(device, _ []byte) error {
			ids, err := membershipsTx(tx, string(device))
			if err != nil {
				return err
			}
			for _, id := range ids {
				if _, _, err := confirmedPairForSessionTx(tx, string(device), id); err != nil {
					return fmt.Errorf("%w: membership", ErrPairStoreCorrupt)
				}
			}
			return nil
		})
	}
	return nil
}
