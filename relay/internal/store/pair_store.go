package store

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"go.etcd.io/bbolt"
)

var ErrNotFound = errors.New("not found")
var ErrInvalidProtocolFloor = errors.New("invalid protocol floor")
var ErrPairConflict = errors.New("pair transition conflict")
var ErrPendingPairLimit = errors.New("pending pair limit reached")
var ErrPairStoreCorrupt = errors.New("pair store index corruption")

type PendingPairState string

const (
	PairWaitingForPeer      PendingPairState = "waiting_for_peer"
	PairWaitingForSignature PendingPairState = "waiting_for_signature"
	PairReadyToComplete     PendingPairState = "ready_to_complete"
	PairCommitted           PendingPairState = "committed"
)

const (
	bucketPending             = "pair_pending"
	bucketConfirmed           = "pair_confirmed"
	bucketByDevice            = "device_to_pair"
	bucketCapabilities        = "device_capabilities"
	bucketProtocolFloor       = "pair_protocol_floor"
	bucketRetainedTokenByPair = "pair_retained_token"
	bucketPairMeta            = "pair_meta"
	bucketPendingExpiry       = "pair_pending_expiry"
)

var retainedTokenIndexVersionKey = []byte("retained_token_index_v1")
var pendingLimitIndexVersionKey = []byte("pending_limit_index_v1")
var pendingCountKey = []byte("pending_count")
var pairIndexSchemaVersionKey = []byte("pair_index_schema_v2")
var pairIndexConfigKey = []byte("pair_index_config_v2")

const pairIndexSchemaVersion = 2

type PendingPairLimits struct {
	MaxPending int
	TTL        time.Duration
	SweepBatch int
}

func DefaultPendingPairLimits() PendingPairLimits {
	return PendingPairLimits{MaxPending: 10_000, TTL: 5 * time.Minute, SweepBatch: 256}
}

type PendingPair struct {
	PairToken    string `json:"pair_token"`
	DeviceAID    string `json:"device_a_id"`
	AEncPubkey   []byte `json:"a_enc_pubkey"`
	ASignPubkey  []byte `json:"a_sign_pubkey"`
	ADisplayName string `json:"a_display_name,omitempty"`
	CreatedAt    int64  `json:"created_at"`

	// Populated by /pair/hello once Device B sends them:
	DeviceBID    string `json:"device_b_id,omitempty"`
	BEncPubkey   []byte `json:"b_enc_pubkey,omitempty"`
	BSignPubkey  []byte `json:"b_sign_pubkey,omitempty"`
	BDisplayName string `json:"b_display_name,omitempty"`

	// Populated by /pair/send_sig once Device A signs:
	ConfirmationSig []byte `json:"confirmation_sig,omitempty"`
	// Populated by /pair/complete once Device B signs the domain-separated
	// transcript and Device A's exact confirmation signature.
	ResponderConfirmationSig []byte `json:"responder_confirmation_sig,omitempty"`

	// Populated atomically with the confirmed pair and retained until the
	// original pair token expires so a lost completion response can be replayed.
	PairID string `json:"pair_id,omitempty"`
}

type ConfirmedPair struct {
	PairID       string `json:"pair_id"`
	DeviceA      string `json:"device_a"`
	DeviceB      string `json:"device_b"`
	AEncPubkey   []byte `json:"a_enc_pubkey"`
	ASignPubkey  []byte `json:"a_sign_pubkey"`
	ADisplayName string `json:"a_display_name,omitempty"`
	BEncPubkey   []byte `json:"b_enc_pubkey"`
	BSignPubkey  []byte `json:"b_sign_pubkey"`
	BDisplayName string `json:"b_display_name,omitempty"`
}

type DeviceCapabilities struct {
	Protocols  []int  `json:"protocols"`
	AppVersion string `json:"app_version"`
	UpdatedAt  int64  `json:"updated_at"`
}

type PairSession struct {
	PairID     string
	DeviceID   string
	PeerID     string
	SignPubkey []byte
}

type PairStore struct {
	bolt   *Bolt
	limits PendingPairLimits
}

func NewPairStore(b *Bolt) *PairStore {
	return NewPairStoreWithLimits(b, DefaultPendingPairLimits())
}

func NewPairStoreWithLimits(b *Bolt, limits PendingPairLimits) *PairStore {
	ps, err := OpenPairStoreWithLimits(b, limits)
	if err != nil {
		panic(fmt.Sprintf("initialize pair store: %v", err))
	}
	return ps
}

func OpenPairStore(b *Bolt) (*PairStore, error) {
	return OpenPairStoreWithLimits(b, DefaultPendingPairLimits())
}

// OpenPairStoreWithLimits initializes or validates every persisted pairing
// index before returning. NewPairStoreWithLimits remains as a compatibility
// wrapper for tests and older callers that intentionally treat init failure as
// fatal; production startup uses this error-returning path.
func OpenPairStoreWithLimits(b *Bolt, limits PendingPairLimits) (*PairStore, error) {
	if limits.MaxPending <= 0 || limits.TTL <= 0 || limits.SweepBatch <= 0 {
		return nil, errors.New("invalid pending pair limits")
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		return initializePairStoreIndexesTx(tx, limits)
	}); err != nil {
		return nil, fmt.Errorf("initialize pairing indexes: %w", err)
	}
	return &PairStore{bolt: b, limits: limits}, nil
}

func (ps *PairStore) PutPending(p PendingPair) error {
	b, err := json.Marshal(p)
	if err != nil {
		return err
	}
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		pending, err := tx.CreateBucketIfNotExists([]byte(bucketPending))
		if err != nil {
			return err
		}
		raw := pending.Get([]byte(p.PairToken))
		if raw == nil {
			meta, err := tx.CreateBucketIfNotExists([]byte(bucketPairMeta))
			if err != nil {
				return err
			}
			count, err := decodePendingCount(meta.Get(pendingCountKey))
			if err != nil {
				return err
			}
			if count >= uint64(ps.limits.MaxPending) {
				return ErrPendingPairLimit
			}
			expiry, err := tx.CreateBucketIfNotExists([]byte(bucketPendingExpiry))
			if err != nil {
				return err
			}
			if err := pending.Put([]byte(p.PairToken), b); err != nil {
				return err
			}
			if err := expiry.Put(pendingExpiryKey(p.CreatedAt, ps.limits.TTL, p.PairToken), []byte(p.PairToken)); err != nil {
				return err
			}
			return meta.Put(pendingCountKey, encodePendingCount(count+1))
		}
		current, err := decodePendingPair(raw)
		if err != nil {
			return err
		}
		if samePairInitiator(current, p) {
			return nil
		}
		return ErrPairConflict
	})
}

func (ps *PairStore) GetPending(token string) (*PendingPair, error) {
	b, err := ps.bolt.Get(bucketPending, token)
	if err != nil {
		return nil, err
	}
	if b == nil {
		return nil, ErrNotFound
	}
	var p PendingPair
	if err := json.Unmarshal(b, &p); err != nil {
		return nil, fmt.Errorf("unmarshal pending: %w", err)
	}
	return &p, nil
}

func (ps *PairStore) DeletePending(token string) error {
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		return deletePendingPairTx(tx, token, ps.limits.TTL)
	})
}

func (ps *PairStore) PendingCount() (uint64, error) {
	var count uint64
	err := ps.bolt.View(func(tx *bbolt.Tx) error {
		meta := tx.Bucket([]byte(bucketPairMeta))
		if meta == nil {
			return nil
		}
		var err error
		count, err = decodePendingCount(meta.Get(pendingCountKey))
		return err
	})
	return count, err
}

// SweepExpired removes at most the configured batch from the expiry-ordered
// index. It never scans attacker-controlled live records on a request path.
func (ps *PairStore) SweepExpired(now time.Time) (int, error) {
	removed := 0
	err := ps.bolt.Update(func(tx *bbolt.Tx) error {
		expiry := tx.Bucket([]byte(bucketPendingExpiry))
		if expiry == nil {
			return fmt.Errorf("%w: missing pending expiry bucket", ErrPairStoreCorrupt)
		}
		for removed < ps.limits.SweepBatch {
			key, token := expiry.Cursor().First()
			if key == nil {
				break
			}
			if err := validatePendingExpiryEntryTx(tx, key, token, ps.limits.TTL); err != nil {
				return err
			}
			if pendingExpiryUnix(key) > now.Unix() {
				break
			}
			if err := deletePendingPairTx(tx, string(append([]byte(nil), token...)), ps.limits.TTL); err != nil {
				return err
			}
			removed++
		}
		return nil
	})
	return removed, err
}

// PendingState derives the current pairing transition solely from the durable
// token-indexed record. No in-memory notification state participates.
func (ps *PairStore) PendingState(pairToken string) (PendingPairState, error) {
	p, err := ps.GetPending(pairToken)
	if err != nil {
		return "", err
	}
	if p.PairID != "" {
		return PairCommitted, nil
	}
	if len(p.ConfirmationSig) != 0 {
		return PairReadyToComplete, nil
	}
	if p.DeviceBID != "" && len(p.BEncPubkey) != 0 && len(p.BSignPubkey) != 0 {
		return PairWaitingForSignature, nil
	}
	return PairWaitingForPeer, nil
}

// UpdatePendingB merges Device B's fields into the existing pending record.
// A's fields are preserved untouched.
func (ps *PairStore) UpdatePendingB(pairToken, deviceBID string, bEncPk, bSignPk []byte, bDisplayName string) error {
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		pending, p, err := pendingPairForUpdate(tx, pairToken)
		if err != nil {
			return err
		}
		if p.DeviceBID != "" || len(p.BEncPubkey) != 0 || len(p.BSignPubkey) != 0 || p.BDisplayName != "" {
			if p.DeviceBID == deviceBID && bytes.Equal(p.BEncPubkey, bEncPk) &&
				bytes.Equal(p.BSignPubkey, bSignPk) && p.BDisplayName == bDisplayName {
				return nil
			}
			return ErrPairConflict
		}
		p.DeviceBID = deviceBID
		p.BEncPubkey = append([]byte(nil), bEncPk...)
		p.BSignPubkey = append([]byte(nil), bSignPk...)
		p.BDisplayName = bDisplayName
		return putPendingPair(pending, p)
	})
}

// UpdatePendingSig merges the confirmation signature into the existing pending record.
// All other fields are preserved untouched.
func (ps *PairStore) UpdatePendingSig(pairToken string, sig []byte) error {
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		pending, p, err := pendingPairForUpdate(tx, pairToken)
		if err != nil {
			return err
		}
		if len(p.ConfirmationSig) != 0 {
			if bytes.Equal(p.ConfirmationSig, sig) {
				return nil
			}
			return ErrPairConflict
		}
		p.ConfirmationSig = append([]byte(nil), sig...)
		return putPendingPair(pending, p)
	})
}

// UpdatePendingResponderSig retains Device B's verified confirmation proof so
// identical completion retries remain auditable and conflicting retries fail.
func (ps *PairStore) UpdatePendingResponderSig(pairToken string, sig []byte) error {
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		pending, p, err := pendingPairForUpdate(tx, pairToken)
		if err != nil {
			return err
		}
		if len(p.ResponderConfirmationSig) != 0 {
			if bytes.Equal(p.ResponderConfirmationSig, sig) {
				return nil
			}
			return ErrPairConflict
		}
		p.ResponderConfirmationSig = append([]byte(nil), sig...)
		return putPendingPair(pending, p)
	})
}

// ConfirmPending atomically commits a confirmed pair, both device indexes, and
// the token-to-pair completion marker. Identical retries return the original
// confirmed pair; conflicting values leave every bucket unchanged.
func (ps *PairStore) ConfirmPending(pairToken string, candidate ConfirmedPair, confirmationSig []byte) (*ConfirmedPair, error) {
	var result ConfirmedPair
	err := ps.bolt.Update(func(tx *bbolt.Tx) error {
		pending, p, err := pendingPairForUpdate(tx, pairToken)
		if err != nil {
			return err
		}
		if !samePendingInitiatorAndCandidate(p, candidate) {
			return ErrPairConflict
		}
		if p.DeviceBID != "" || len(p.BEncPubkey) != 0 || len(p.BSignPubkey) != 0 {
			if p.DeviceBID != candidate.DeviceB || !bytes.Equal(p.BEncPubkey, candidate.BEncPubkey) ||
				!bytes.Equal(p.BSignPubkey, candidate.BSignPubkey) {
				return ErrPairConflict
			}
		}
		if len(p.ConfirmationSig) != 0 && !bytes.Equal(p.ConfirmationSig, confirmationSig) {
			return ErrPairConflict
		}

		if p.PairID != "" {
			confirmed := tx.Bucket([]byte(bucketConfirmed))
			if confirmed == nil {
				return ErrNotFound
			}
			raw := confirmed.Get([]byte(p.PairID))
			if raw == nil {
				return ErrNotFound
			}
			stored, err := decodeConfirmedPair(raw)
			if err != nil {
				return err
			}
			if !sameConfirmedPair(stored, candidate) {
				return ErrPairConflict
			}
			retainedTokens, err := tx.CreateBucketIfNotExists([]byte(bucketRetainedTokenByPair))
			if err != nil {
				return err
			}
			indexedToken := retainedTokens.Get([]byte(p.PairID))
			if indexedToken != nil && !bytes.Equal(indexedToken, []byte(pairToken)) {
				return ErrPairConflict
			}
			if err := retainedTokens.Put([]byte(p.PairID), []byte(pairToken)); err != nil {
				return err
			}
			result = stored
			return nil
		}
		if candidate.PairID == "" {
			return errors.New("empty pair id")
		}
		confirmed := tx.Bucket([]byte(bucketConfirmed))
		if confirmed != nil && confirmed.Get([]byte(candidate.PairID)) != nil {
			return ErrPairConflict
		}
		byDevice := tx.Bucket([]byte(bucketByDevice))
		if byDevice != nil && (byDevice.Get([]byte(candidate.DeviceA)) != nil || byDevice.Get([]byte(candidate.DeviceB)) != nil) {
			return ErrPairConflict
		}

		encodedPair, err := json.Marshal(candidate)
		if err != nil {
			return err
		}
		if confirmed == nil {
			confirmed, err = tx.CreateBucket([]byte(bucketConfirmed))
			if err != nil {
				return err
			}
		}
		if err := confirmed.Put([]byte(candidate.PairID), encodedPair); err != nil {
			return err
		}
		if byDevice == nil {
			byDevice, err = tx.CreateBucket([]byte(bucketByDevice))
			if err != nil {
				return err
			}
		}
		if err := byDevice.Put([]byte(candidate.DeviceA), []byte(candidate.PairID)); err != nil {
			return err
		}
		if err := byDevice.Put([]byte(candidate.DeviceB), []byte(candidate.PairID)); err != nil {
			return err
		}

		if p.DeviceBID == "" {
			p.DeviceBID = candidate.DeviceB
			p.BEncPubkey = append([]byte(nil), candidate.BEncPubkey...)
			p.BSignPubkey = append([]byte(nil), candidate.BSignPubkey...)
			p.BDisplayName = candidate.BDisplayName
		}
		if len(p.ConfirmationSig) == 0 {
			p.ConfirmationSig = append([]byte(nil), confirmationSig...)
		}
		p.PairID = candidate.PairID
		if err := putPendingPair(pending, p); err != nil {
			return err
		}
		retainedTokens, err := tx.CreateBucketIfNotExists([]byte(bucketRetainedTokenByPair))
		if err != nil {
			return err
		}
		if indexedToken := retainedTokens.Get([]byte(candidate.PairID)); indexedToken != nil && !bytes.Equal(indexedToken, []byte(pairToken)) {
			return ErrPairConflict
		}
		if err := retainedTokens.Put([]byte(candidate.PairID), []byte(pairToken)); err != nil {
			return err
		}
		result = candidate
		return nil
	})
	if err != nil {
		return nil, err
	}
	return &result, nil
}

func (ps *PairStore) Confirm(cp ConfirmedPair) error {
	b, err := json.Marshal(cp)
	if err != nil {
		return err
	}
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		confirmed, err := tx.CreateBucketIfNotExists([]byte(bucketConfirmed))
		if err != nil {
			return err
		}
		byDevice, err := tx.CreateBucketIfNotExists([]byte(bucketByDevice))
		if err != nil {
			return err
		}
		pairID := []byte(cp.PairID)
		if raw := confirmed.Get(pairID); raw != nil {
			stored, err := decodeConfirmedPair(raw)
			if err != nil {
				return err
			}
			if !sameConfirmedPair(stored, cp) {
				return ErrPairConflict
			}
		}
		for _, deviceID := range []string{cp.DeviceA, cp.DeviceB} {
			if existingPairID := byDevice.Get([]byte(deviceID)); existingPairID != nil && !bytes.Equal(existingPairID, pairID) {
				return ErrPairConflict
			}
		}
		if err := confirmed.Put(pairID, b); err != nil {
			return err
		}
		if err := byDevice.Put([]byte(cp.DeviceA), pairID); err != nil {
			return err
		}
		return byDevice.Put([]byte(cp.DeviceB), pairID)
	})
}

// RevokeByDevice atomically removes a confirmed pair's authorization,
// retained pairing state, capability negotiation, and durable mailboxes.
func (ps *PairStore) RevokeByDevice(deviceID string) (*ConfirmedPair, error) {
	return ps.revoke(deviceID, "")
}

func (ps *PairStore) RevokeBySession(deviceID, pairID string) (*ConfirmedPair, error) {
	return ps.revoke(deviceID, pairID)
}

func (ps *PairStore) revoke(deviceID, expectedPairID string) (*ConfirmedPair, error) {
	var revoked ConfirmedPair
	err := ps.bolt.Update(func(tx *bbolt.Tx) error {
		pairID, pair, err := confirmedPairForSessionTx(tx, deviceID, expectedPairID)
		if err != nil {
			return err
		}
		byDevice := tx.Bucket([]byte(bucketByDevice))
		for _, pairedDevice := range []string{pair.DeviceA, pair.DeviceB} {
			if indexedPairID := byDevice.Get([]byte(pairedDevice)); !bytes.Equal(indexedPairID, pairID) {
				return ErrPairConflict
			}
		}

		if err := purgePairTx(tx, pair.DeviceA, pair.DeviceB); err != nil {
			return err
		}
		confirmed := tx.Bucket([]byte(bucketConfirmed))
		if err := confirmed.Delete(pairID); err != nil {
			return err
		}
		if err := byDevice.Delete([]byte(pair.DeviceA)); err != nil {
			return err
		}
		if err := byDevice.Delete([]byte(pair.DeviceB)); err != nil {
			return err
		}
		if capabilities := tx.Bucket([]byte(bucketCapabilities)); capabilities != nil {
			if err := capabilities.Delete([]byte(pair.DeviceA)); err != nil {
				return err
			}
			if err := capabilities.Delete([]byte(pair.DeviceB)); err != nil {
				return err
			}
		}
		if floors := tx.Bucket([]byte(bucketProtocolFloor)); floors != nil {
			if err := floors.Delete(pairID); err != nil {
				return err
			}
		}
		if retainedTokens := tx.Bucket([]byte(bucketRetainedTokenByPair)); retainedTokens != nil {
			pairToken := retainedTokens.Get(pairID)
			if pairToken != nil {
				pending := tx.Bucket([]byte(bucketPending))
				if pending == nil {
					return ErrPairConflict
				}
				raw := pending.Get(pairToken)
				if raw == nil {
					return ErrPairConflict
				}
				retained, err := decodePendingPair(raw)
				if err != nil {
					return err
				}
				if retained.PairID != pair.PairID {
					return ErrPairConflict
				}
				if err := deletePendingPairTx(tx, string(pairToken), ps.limits.TTL); err != nil {
					return err
				}
			}
		}
		revoked = pair
		return nil
	})
	if err != nil {
		return nil, err
	}
	return &revoked, nil
}

func migrateRetainedTokenIndexTx(tx *bbolt.Tx) error {
	meta, err := tx.CreateBucketIfNotExists([]byte(bucketPairMeta))
	if err != nil {
		return err
	}
	if meta.Get(retainedTokenIndexVersionKey) != nil {
		return nil
	}
	index, err := tx.CreateBucketIfNotExists([]byte(bucketRetainedTokenByPair))
	if err != nil {
		return err
	}
	if pending := tx.Bucket([]byte(bucketPending)); pending != nil {
		cursor := pending.Cursor()
		for _, raw := cursor.First(); raw != nil; _, raw = cursor.Next() {
			record, err := decodePendingPair(raw)
			if err != nil {
				return err
			}
			if record.PairID == "" {
				continue
			}
			pairID := []byte(record.PairID)
			if existing := index.Get(pairID); existing != nil && !bytes.Equal(existing, []byte(record.PairToken)) {
				return ErrPairConflict
			}
			if err := index.Put(pairID, []byte(record.PairToken)); err != nil {
				return err
			}
		}
	}
	return meta.Put(retainedTokenIndexVersionKey, []byte{1})
}

func migratePendingLimitIndexTx(tx *bbolt.Tx, ttl time.Duration) error {
	meta, err := tx.CreateBucketIfNotExists([]byte(bucketPairMeta))
	if err != nil {
		return err
	}
	if meta.Get(pendingLimitIndexVersionKey) != nil {
		return nil
	}
	expiry, err := tx.CreateBucketIfNotExists([]byte(bucketPendingExpiry))
	if err != nil {
		return err
	}
	var count uint64
	if pending := tx.Bucket([]byte(bucketPending)); pending != nil {
		cursor := pending.Cursor()
		for token, raw := cursor.First(); token != nil; token, raw = cursor.Next() {
			record, err := decodePendingPair(raw)
			if err != nil {
				return err
			}
			if record.PairToken != string(token) {
				return ErrPairConflict
			}
			if err := expiry.Put(pendingExpiryKey(record.CreatedAt, ttl, record.PairToken), token); err != nil {
				return err
			}
			count++
		}
	}
	if err := meta.Put(pendingCountKey, encodePendingCount(count)); err != nil {
		return err
	}
	return meta.Put(pendingLimitIndexVersionKey, []byte{1})
}

type persistedPairIndexConfig struct {
	Schema     int   `json:"schema"`
	MaxPending int   `json:"max_pending"`
	TTLNanos   int64 `json:"ttl_nanos"`
	SweepBatch int   `json:"sweep_batch"`
}

func initializePairStoreIndexesTx(tx *bbolt.Tx, limits PendingPairLimits) error {
	meta := tx.Bucket([]byte(bucketPairMeta))
	if meta == nil || meta.Get(pairIndexSchemaVersionKey) == nil {
		if err := rebuildPairStoreIndexesTx(tx, limits); err != nil {
			return err
		}
	}
	return validatePairStoreIndexesTx(tx, limits)
}

func rebuildPairStoreIndexesTx(tx *bbolt.Tx, limits PendingPairLimits) error {
	meta, err := tx.CreateBucketIfNotExists([]byte(bucketPairMeta))
	if err != nil {
		return err
	}
	for _, name := range []string{bucketPendingExpiry, bucketRetainedTokenByPair} {
		if err := tx.DeleteBucket([]byte(name)); err != nil && !errors.Is(err, bbolt.ErrBucketNotFound) {
			return err
		}
	}
	expiry, err := tx.CreateBucket([]byte(bucketPendingExpiry))
	if err != nil {
		return err
	}
	retained, err := tx.CreateBucket([]byte(bucketRetainedTokenByPair))
	if err != nil {
		return err
	}
	var count uint64
	if pending := tx.Bucket([]byte(bucketPending)); pending != nil {
		cursor := pending.Cursor()
		for token, raw := cursor.First(); token != nil; token, raw = cursor.Next() {
			record, err := decodePendingPair(raw)
			if err != nil {
				return err
			}
			if record.PairToken == "" || record.PairToken != string(token) {
				return fmt.Errorf("%w: pending token mismatch", ErrPairStoreCorrupt)
			}
			if err := expiry.Put(pendingExpiryKey(record.CreatedAt, limits.TTL, record.PairToken), token); err != nil {
				return err
			}
			if record.PairID != "" {
				pairID := []byte(record.PairID)
				if prior := retained.Get(pairID); prior != nil && !bytes.Equal(prior, token) {
					return fmt.Errorf("%w: duplicate retained pair id", ErrPairStoreCorrupt)
				}
				if err := retained.Put(pairID, token); err != nil {
					return err
				}
			}
			count++
		}
	}
	config, err := encodePairIndexConfig(limits)
	if err != nil {
		return err
	}
	if err := meta.Put(pendingCountKey, encodePendingCount(count)); err != nil {
		return err
	}
	if err := meta.Put(retainedTokenIndexVersionKey, []byte{1}); err != nil {
		return err
	}
	if err := meta.Put(pendingLimitIndexVersionKey, []byte{1}); err != nil {
		return err
	}
	if err := meta.Put(pairIndexConfigKey, config); err != nil {
		return err
	}
	return meta.Put(pairIndexSchemaVersionKey, []byte{pairIndexSchemaVersion})
}

func encodePairIndexConfig(limits PendingPairLimits) ([]byte, error) {
	return json.Marshal(persistedPairIndexConfig{
		Schema: pairIndexSchemaVersion, MaxPending: limits.MaxPending,
		TTLNanos: int64(limits.TTL), SweepBatch: limits.SweepBatch,
	})
}

func validatePairStoreIndexesTx(tx *bbolt.Tx, limits PendingPairLimits) error {
	corrupt := func(format string, args ...any) error {
		return fmt.Errorf("%w: %s", ErrPairStoreCorrupt, fmt.Sprintf(format, args...))
	}
	meta := tx.Bucket([]byte(bucketPairMeta))
	if meta == nil || !bytes.Equal(meta.Get(pairIndexSchemaVersionKey), []byte{pairIndexSchemaVersion}) ||
		!bytes.Equal(meta.Get(retainedTokenIndexVersionKey), []byte{1}) ||
		!bytes.Equal(meta.Get(pendingLimitIndexVersionKey), []byte{1}) {
		return corrupt("missing or invalid schema markers")
	}
	wantConfig, err := encodePairIndexConfig(limits)
	if err != nil {
		return err
	}
	if !bytes.Equal(meta.Get(pairIndexConfigKey), wantConfig) {
		return corrupt("persisted limits do not match active configuration")
	}
	wantCount, err := decodePendingCount(meta.Get(pendingCountKey))
	if err != nil {
		return corrupt("invalid pending counter: %v", err)
	}
	expiry := tx.Bucket([]byte(bucketPendingExpiry))
	retained := tx.Bucket([]byte(bucketRetainedTokenByPair))
	if expiry == nil || retained == nil {
		return corrupt("missing pairing index bucket")
	}

	var pendingCount uint64
	if pending := tx.Bucket([]byte(bucketPending)); pending != nil {
		cursor := pending.Cursor()
		for token, raw := cursor.First(); token != nil; token, raw = cursor.Next() {
			record, decodeErr := decodePendingPair(raw)
			if decodeErr != nil {
				return corrupt("invalid pending record: %v", decodeErr)
			}
			if record.PairToken == "" || record.PairToken != string(token) {
				return corrupt("pending token mismatch")
			}
			expiryKey := pendingExpiryKey(record.CreatedAt, limits.TTL, record.PairToken)
			if !bytes.Equal(expiry.Get(expiryKey), token) {
				return corrupt("missing reciprocal expiry entry for %q", token)
			}
			if record.PairID != "" && !bytes.Equal(retained.Get([]byte(record.PairID)), token) {
				return corrupt("missing reciprocal retained token for %q", token)
			}
			pendingCount++
		}
	}
	if pendingCount != wantCount {
		return corrupt("pending counter is %d, records are %d", wantCount, pendingCount)
	}
	var expiryCount uint64
	expiryCursor := expiry.Cursor()
	for key, token := expiryCursor.First(); key != nil; key, token = expiryCursor.Next() {
		if err := validatePendingExpiryEntryTx(tx, key, token, limits.TTL); err != nil {
			return err
		}
		expiryCount++
	}
	if expiryCount != pendingCount {
		return corrupt("expiry entries are %d, records are %d", expiryCount, pendingCount)
	}
	retainedCursor := retained.Cursor()
	for pairID, token := retainedCursor.First(); pairID != nil; pairID, token = retainedCursor.Next() {
		if len(pairID) == 0 || len(token) == 0 {
			return corrupt("empty retained token mapping")
		}
		pending := tx.Bucket([]byte(bucketPending))
		if pending == nil {
			return corrupt("orphan retained token mapping")
		}
		record, decodeErr := decodePendingPair(pending.Get(token))
		if decodeErr != nil || record.PairID != string(pairID) || record.PairToken != string(token) {
			return corrupt("invalid reciprocal retained token mapping")
		}
	}
	return nil
}

func validatePendingExpiryEntryTx(tx *bbolt.Tx, key, token []byte, ttl time.Duration) error {
	if len(key) < 9 || key[8] != 0 || len(token) == 0 || !bytes.Equal(key[9:], token) {
		return fmt.Errorf("%w: malformed pending expiry entry", ErrPairStoreCorrupt)
	}
	pending := tx.Bucket([]byte(bucketPending))
	if pending == nil || pending.Get(token) == nil {
		return fmt.Errorf("%w: orphan pending expiry entry", ErrPairStoreCorrupt)
	}
	record, err := decodePendingPair(pending.Get(token))
	if err != nil {
		return fmt.Errorf("%w: invalid pending expiry record: %v", ErrPairStoreCorrupt, err)
	}
	if record.PairToken != string(token) || !bytes.Equal(key, pendingExpiryKey(record.CreatedAt, ttl, record.PairToken)) {
		return fmt.Errorf("%w: pending expiry entry mismatch", ErrPairStoreCorrupt)
	}
	return nil
}

func deletePendingPairTx(tx *bbolt.Tx, token string, ttl time.Duration) error {
	pending := tx.Bucket([]byte(bucketPending))
	if pending == nil {
		return nil
	}
	raw := pending.Get([]byte(token))
	if raw == nil {
		return nil
	}
	record, err := decodePendingPair(raw)
	if err != nil {
		return err
	}
	if record.PairID != "" {
		index := tx.Bucket([]byte(bucketRetainedTokenByPair))
		if index == nil || !bytes.Equal(index.Get([]byte(record.PairID)), []byte(token)) {
			return ErrPairConflict
		}
		if err := index.Delete([]byte(record.PairID)); err != nil {
			return err
		}
	}
	meta := tx.Bucket([]byte(bucketPairMeta))
	if meta == nil {
		return errors.New("missing pending pair metadata")
	}
	count, err := decodePendingCount(meta.Get(pendingCountKey))
	if err != nil {
		return err
	}
	if count == 0 {
		return errors.New("invalid pending pair count")
	}
	expiry := tx.Bucket([]byte(bucketPendingExpiry))
	expiryKey := pendingExpiryKey(record.CreatedAt, ttl, record.PairToken)
	if expiry == nil || !bytes.Equal(expiry.Get(expiryKey), []byte(token)) {
		return fmt.Errorf("%w: missing pending expiry entry", ErrPairStoreCorrupt)
	}
	if err := expiry.Delete(expiryKey); err != nil {
		return err
	}
	if err := pending.Delete([]byte(token)); err != nil {
		return err
	}
	return meta.Put(pendingCountKey, encodePendingCount(count-1))
}

func pendingExpiryKey(createdAt int64, ttl time.Duration, token string) []byte {
	key := make([]byte, 9+len(token))
	binary.BigEndian.PutUint64(key[:8], uint64(time.Unix(createdAt, 0).Add(ttl).Unix()))
	copy(key[9:], token)
	return key
}

func pendingExpiryUnix(key []byte) int64 {
	if len(key) < 9 {
		return 0
	}
	return int64(binary.BigEndian.Uint64(key[:8]))
}

func encodePendingCount(count uint64) []byte {
	raw := make([]byte, 8)
	binary.BigEndian.PutUint64(raw, count)
	return raw
}

func decodePendingCount(raw []byte) (uint64, error) {
	if len(raw) != 8 {
		return 0, errors.New("invalid pending pair count")
	}
	return binary.BigEndian.Uint64(raw), nil
}

func (ps *PairStore) Get(pairID string) (*ConfirmedPair, error) {
	b, err := ps.bolt.Get(bucketConfirmed, pairID)
	if err != nil {
		return nil, err
	}
	if b == nil {
		return nil, ErrNotFound
	}
	var cp ConfirmedPair
	if err := json.Unmarshal(b, &cp); err != nil {
		return nil, err
	}
	return &cp, nil
}

func (ps *PairStore) SignPubkeyFor(deviceID string) ([]byte, error) {
	session, err := ps.SessionFor(deviceID)
	if err != nil {
		return nil, err
	}
	return session.SignPubkey, nil
}

func (ps *PairStore) SessionFor(deviceID string) (PairSession, error) {
	var session PairSession
	err := ps.bolt.View(func(tx *bbolt.Tx) error {
		pairID, pair, err := confirmedPairForDeviceTx(tx, deviceID)
		if err != nil {
			return err
		}
		session = PairSession{PairID: string(pairID), DeviceID: deviceID}
		switch deviceID {
		case pair.DeviceA:
			session.PeerID = pair.DeviceB
			session.SignPubkey = append([]byte(nil), pair.ASignPubkey...)
		case pair.DeviceB:
			session.PeerID = pair.DeviceA
			session.SignPubkey = append([]byte(nil), pair.BSignPubkey...)
		default:
			return ErrNotFound
		}
		return nil
	})
	return session, err
}

func (ps *PairStore) ValidateSession(deviceID, pairID string) error {
	return ps.bolt.View(func(tx *bbolt.Tx) error {
		_, _, err := confirmedPairForSessionTx(tx, deviceID, pairID)
		return err
	})
}

// PeerFor returns the paired peer's device ID for deviceID, or ErrNotFound.
func (ps *PairStore) PeerFor(deviceID string) (string, error) {
	return ps.PeerForPair(deviceID, "")
}

func (ps *PairStore) PeerForPair(deviceID, pairID string) (string, error) {
	var peerID string
	err := ps.bolt.View(func(tx *bbolt.Tx) error {
		_, pair, err := confirmedPairForSessionTx(tx, deviceID, pairID)
		if err != nil {
			return err
		}
		if pair.DeviceA == deviceID {
			peerID = pair.DeviceB
		} else {
			peerID = pair.DeviceA
		}
		return nil
	})
	return peerID, err
}

// UpdateCapabilities records a confirmed device's advertised protocols and
// advances its pair's protocol floor atomically. A negotiated floor never
// decreases automatically.
func (ps *PairStore) UpdateCapabilities(deviceID string, protocols []int, appVersion string) error {
	return ps.UpdateCapabilitiesForPair(deviceID, "", protocols, appVersion)
}

func (ps *PairStore) UpdateCapabilitiesForPair(deviceID, expectedPairID string, protocols []int, appVersion string) error {
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		pairID, pair, err := confirmedPairForSessionTx(tx, deviceID, expectedPairID)
		if err != nil {
			return err
		}
		peerID := pair.DeviceA
		if deviceID == pair.DeviceA {
			peerID = pair.DeviceB
		}

		capabilities, err := tx.CreateBucketIfNotExists([]byte(bucketCapabilities))
		if err != nil {
			return err
		}
		encoded, err := json.Marshal(DeviceCapabilities{
			Protocols: append([]int(nil), protocols...), AppVersion: appVersion, UpdatedAt: time.Now().UnixMilli(),
		})
		if err != nil {
			return err
		}
		if err := capabilities.Put([]byte(deviceID), encoded); err != nil {
			return err
		}

		floors, err := tx.CreateBucketIfNotExists([]byte(bucketProtocolFloor))
		if err != nil {
			return err
		}
		floor, err := floorForPair(floors.Get(pairID))
		if err != nil {
			return err
		}
		if floor >= 2 {
			return nil
		}
		peerCapabilities, err := decodeCapabilities(capabilities.Get([]byte(peerID)))
		if err != nil {
			return err
		}
		if supportsStoredProtocol(protocols, 2) && supportsStoredProtocol(peerCapabilities.Protocols, 2) {
			return floors.Put(pairID, []byte{2})
		}
		return nil
	})
}

// CapabilitiesFor returns one consistent snapshot of a confirmed pair's
// advertised capabilities and monotonic negotiated floor.
func (ps *PairStore) CapabilitiesFor(deviceID string) (self DeviceCapabilities, peer DeviceCapabilities, floor int, err error) {
	return ps.CapabilitiesForPair(deviceID, "")
}

func (ps *PairStore) CapabilitiesForPair(deviceID, expectedPairID string) (self DeviceCapabilities, peer DeviceCapabilities, floor int, err error) {
	err = ps.bolt.View(func(tx *bbolt.Tx) error {
		pairID, pair, lookupErr := confirmedPairForSessionTx(tx, deviceID, expectedPairID)
		if lookupErr != nil {
			return lookupErr
		}
		peerID := pair.DeviceA
		if deviceID == pair.DeviceA {
			peerID = pair.DeviceB
		}
		capabilities := tx.Bucket([]byte(bucketCapabilities))
		if capabilities != nil {
			self, lookupErr = decodeCapabilities(capabilities.Get([]byte(deviceID)))
			if lookupErr != nil {
				return lookupErr
			}
			peer, lookupErr = decodeCapabilities(capabilities.Get([]byte(peerID)))
			if lookupErr != nil {
				return lookupErr
			}
		}
		floor = 1
		if floors := tx.Bucket([]byte(bucketProtocolFloor)); floors != nil {
			floor, lookupErr = floorForPair(floors.Get(pairID))
			if lookupErr != nil {
				return lookupErr
			}
		}
		return nil
	})
	return self, peer, floor, err
}

func pendingPairForUpdate(tx *bbolt.Tx, pairToken string) (*bbolt.Bucket, PendingPair, error) {
	pending := tx.Bucket([]byte(bucketPending))
	if pending == nil {
		return nil, PendingPair{}, ErrNotFound
	}
	raw := pending.Get([]byte(pairToken))
	if raw == nil {
		return nil, PendingPair{}, ErrNotFound
	}
	p, err := decodePendingPair(raw)
	return pending, p, err
}

func putPendingPair(bucket *bbolt.Bucket, p PendingPair) error {
	encoded, err := json.Marshal(p)
	if err != nil {
		return err
	}
	return bucket.Put([]byte(p.PairToken), encoded)
}

func decodePendingPair(raw []byte) (PendingPair, error) {
	var p PendingPair
	if err := json.Unmarshal(raw, &p); err != nil {
		return PendingPair{}, fmt.Errorf("unmarshal pending: %w", err)
	}
	return p, nil
}

func decodeConfirmedPair(raw []byte) (ConfirmedPair, error) {
	var pair ConfirmedPair
	if err := json.Unmarshal(raw, &pair); err != nil {
		return ConfirmedPair{}, err
	}
	return pair, nil
}

func samePairInitiator(a, b PendingPair) bool {
	return a.PairToken == b.PairToken && a.DeviceAID == b.DeviceAID &&
		bytes.Equal(a.AEncPubkey, b.AEncPubkey) && bytes.Equal(a.ASignPubkey, b.ASignPubkey) &&
		a.ADisplayName == b.ADisplayName
}

func samePendingInitiatorAndCandidate(p PendingPair, candidate ConfirmedPair) bool {
	return p.DeviceAID == candidate.DeviceA && bytes.Equal(p.AEncPubkey, candidate.AEncPubkey) &&
		bytes.Equal(p.ASignPubkey, candidate.ASignPubkey) && p.ADisplayName == candidate.ADisplayName
}

func sameConfirmedPair(stored, candidate ConfirmedPair) bool {
	return stored.DeviceA == candidate.DeviceA && stored.DeviceB == candidate.DeviceB &&
		bytes.Equal(stored.AEncPubkey, candidate.AEncPubkey) &&
		bytes.Equal(stored.ASignPubkey, candidate.ASignPubkey) && stored.ADisplayName == candidate.ADisplayName &&
		bytes.Equal(stored.BEncPubkey, candidate.BEncPubkey) &&
		bytes.Equal(stored.BSignPubkey, candidate.BSignPubkey) && stored.BDisplayName == candidate.BDisplayName
}

func confirmedPairForDeviceTx(tx *bbolt.Tx, deviceID string) ([]byte, ConfirmedPair, error) {
	byDevice := tx.Bucket([]byte(bucketByDevice))
	if byDevice == nil {
		return nil, ConfirmedPair{}, ErrNotFound
	}
	pairID := byDevice.Get([]byte(deviceID))
	if pairID == nil {
		return nil, ConfirmedPair{}, ErrNotFound
	}
	confirmed := tx.Bucket([]byte(bucketConfirmed))
	if confirmed == nil {
		return nil, ConfirmedPair{}, ErrNotFound
	}
	rawPair := confirmed.Get(pairID)
	if rawPair == nil {
		return nil, ConfirmedPair{}, ErrNotFound
	}
	var pair ConfirmedPair
	if err := json.Unmarshal(rawPair, &pair); err != nil {
		return nil, ConfirmedPair{}, err
	}
	if pair.DeviceA != deviceID && pair.DeviceB != deviceID {
		return nil, ConfirmedPair{}, ErrNotFound
	}
	return append([]byte(nil), pairID...), pair, nil
}

func confirmedPairForSessionTx(tx *bbolt.Tx, deviceID, expectedPairID string) ([]byte, ConfirmedPair, error) {
	pairID, pair, err := confirmedPairForDeviceTx(tx, deviceID)
	if err != nil {
		return nil, ConfirmedPair{}, err
	}
	if expectedPairID != "" && !bytes.Equal(pairID, []byte(expectedPairID)) {
		return nil, ConfirmedPair{}, ErrNotFound
	}
	return pairID, pair, nil
}

func decodeCapabilities(raw []byte) (DeviceCapabilities, error) {
	if raw == nil {
		return DeviceCapabilities{}, nil
	}
	var capabilities DeviceCapabilities
	if err := json.Unmarshal(raw, &capabilities); err != nil {
		return DeviceCapabilities{}, err
	}
	capabilities.Protocols = append([]int(nil), capabilities.Protocols...)
	return capabilities, nil
}

func supportsStoredProtocol(protocols []int, protocol int) bool {
	for _, advertised := range protocols {
		if advertised == protocol {
			return true
		}
	}
	return false
}

func floorForPair(raw []byte) (int, error) {
	if raw == nil {
		return 1, nil
	}
	if len(raw) == 1 && raw[0] == 2 {
		return 2, nil
	}
	return 0, ErrInvalidProtocolFloor
}
