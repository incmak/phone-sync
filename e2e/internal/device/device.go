// Package device keeps Android source controls separate from the receive-only Mac.
package device

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
)

type Canonical struct {
	FixtureTagHash string `json:"fixture_tag_hash,omitempty"`
	Hash           string `json:"canon_id_hash"`
	Sequence       int64  `json:"sequence"`
	State          string `json:"state"`
	Materialized   int64  `json:"materialized_sequence"`
	Delivered      bool   `json:"delivered"`
	BodyHash       string `json:"body_hash,omitempty"`
}

type Peer struct {
	LinkID     string `json:"peer_link_id"`
	DeviceHash string `json:"device_id_hash"`
	Lifecycle  string `json:"lifecycle"`
}

type Snapshot struct {
	SnapshotCommits int64       `json:"snapshot_commits,omitempty"`
	ProcessID       int         `json:"process_id,omitempty"`
	DeviceHash      string      `json:"device_id_hash"`
	Peers           []Peer      `json:"peer_links"`
	Canonical       []Canonical `json:"canonical"`
	Permission      string      `json:"permission,omitempty"`
	StorageOK       bool        `json:"storage_ok,omitempty"`
}

// Receiver deliberately has no source-post, reply, call, LAN or Bluetooth operation.
type Receiver interface {
	Snapshot(context.Context) (Snapshot, error)
	RemovePeer(context.Context, string) error
}

func requestID() (string, error) {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	bytes[6] = (bytes[6] & 15) | 64
	bytes[8] = (bytes[8] & 63) | 128
	raw := hex.EncodeToString(bytes[:])
	return fmt.Sprintf("%s-%s-%s-%s-%s", raw[:8], raw[8:12], raw[12:16], raw[16:20], raw[20:]), nil
}

func decodeSnapshot(raw json.RawMessage) (Snapshot, error) {
	var snapshot Snapshot
	err := json.Unmarshal(raw, &snapshot)
	if err == nil && (len(snapshot.DeviceHash) != 64 || len(snapshot.Peers) > 2 || len(snapshot.Canonical) > 8192) {
		err = fmt.Errorf("invalid device snapshot bounds")
	}
	return snapshot, err
}
