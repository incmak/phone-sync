package store

import (
	"encoding/json"
	"errors"
	"fmt"
)

var ErrNotFound = errors.New("not found")

const (
	bucketPending   = "pair_pending"
	bucketConfirmed = "pair_confirmed"
	bucketByDevice  = "device_to_pair"
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
