package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"go.etcd.io/bbolt"
)

var ErrNotFound = errors.New("not found")

const (
	bucketPending       = "pair_pending"
	bucketConfirmed     = "pair_confirmed"
	bucketByDevice      = "device_to_pair"
	bucketCapabilities  = "device_capabilities"
	bucketProtocolFloor = "pair_protocol_floor"
)

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

type PairStore struct {
	bolt *Bolt
}

func NewPairStore(b *Bolt) *PairStore {
	return &PairStore{bolt: b}
}

func (ps *PairStore) PutPending(p PendingPair) error {
	b, err := json.Marshal(p)
	if err != nil {
		return err
	}
	return ps.bolt.Put(bucketPending, p.PairToken, b)
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
	return ps.bolt.Delete(bucketPending, token)
}

// UpdatePendingB merges Device B's fields into the existing pending record.
// A's fields are preserved untouched.
func (ps *PairStore) UpdatePendingB(pairToken, deviceBID string, bEncPk, bSignPk []byte, bDisplayName string) error {
	p, err := ps.GetPending(pairToken)
	if err != nil {
		return err
	}
	p.DeviceBID = deviceBID
	p.BEncPubkey = bEncPk
	p.BSignPubkey = bSignPk
	p.BDisplayName = bDisplayName
	return ps.PutPending(*p)
}

// UpdatePendingSig merges the confirmation signature into the existing pending record.
// All other fields are preserved untouched.
func (ps *PairStore) UpdatePendingSig(pairToken string, sig []byte) error {
	p, err := ps.GetPending(pairToken)
	if err != nil {
		return err
	}
	p.ConfirmationSig = sig
	return ps.PutPending(*p)
}

func (ps *PairStore) Confirm(cp ConfirmedPair) error {
	b, err := json.Marshal(cp)
	if err != nil {
		return err
	}
	if err := ps.bolt.Put(bucketConfirmed, cp.PairID, b); err != nil {
		return err
	}
	if err := ps.bolt.Put(bucketByDevice, cp.DeviceA, []byte(cp.PairID)); err != nil {
		return err
	}
	return ps.bolt.Put(bucketByDevice, cp.DeviceB, []byte(cp.PairID))
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
	pairIDBytes, err := ps.bolt.Get(bucketByDevice, deviceID)
	if err != nil {
		return nil, err
	}
	if pairIDBytes == nil {
		return nil, ErrNotFound
	}
	cp, err := ps.Get(string(pairIDBytes))
	if err != nil {
		return nil, err
	}
	if cp.DeviceA == deviceID {
		return cp.ASignPubkey, nil
	}
	if cp.DeviceB == deviceID {
		return cp.BSignPubkey, nil
	}
	return nil, ErrNotFound
}

// PeerFor returns the paired peer's device ID for deviceID, or ErrNotFound.
func (ps *PairStore) PeerFor(deviceID string) (string, error) {
	pairIDBytes, err := ps.bolt.Get(bucketByDevice, deviceID)
	if err != nil {
		return "", err
	}
	if pairIDBytes == nil {
		return "", ErrNotFound
	}
	cp, err := ps.Get(string(pairIDBytes))
	if err != nil {
		return "", err
	}
	if cp.DeviceA == deviceID {
		return cp.DeviceB, nil
	}
	if cp.DeviceB == deviceID {
		return cp.DeviceA, nil
	}
	return "", ErrNotFound
}

// UpdateCapabilities records a confirmed device's advertised protocols and
// advances its pair's protocol floor atomically. A negotiated floor never
// decreases automatically.
func (ps *PairStore) UpdateCapabilities(deviceID string, protocols []int, appVersion string) error {
	return ps.bolt.Update(func(tx *bbolt.Tx) error {
		pairID, pair, err := confirmedPairForDeviceTx(tx, deviceID)
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
		if floorForPair(floors.Get(pairID)) >= 2 {
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
	err = ps.bolt.View(func(tx *bbolt.Tx) error {
		pairID, pair, lookupErr := confirmedPairForDeviceTx(tx, deviceID)
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
			floor = floorForPair(floors.Get(pairID))
		}
		return nil
	})
	return self, peer, floor, err
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

func floorForPair(raw []byte) int {
	if len(raw) == 1 && raw[0] >= 2 {
		return 2
	}
	return 1
}
