package store

import (
	"path/filepath"
	"testing"
	"time"
)

func TestPairStore_InitAndComplete(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenBolt(filepath.Join(dir, "pair.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer s.Close()

	ps := NewPairStore(s)

	rec := PendingPair{
		PairToken:   "tok-abc",
		DeviceAID:   "devA",
		AEncPubkey:  []byte("A-enc"),
		ASignPubkey: []byte("A-sign"),
		CreatedAt:   time.Now().Unix(),
	}
	if err := ps.PutPending(rec); err != nil {
		t.Fatalf("put pending: %v", err)
	}

	got, err := ps.GetPending("tok-abc")
	if err != nil {
		t.Fatalf("get pending: %v", err)
	}
	if got.DeviceAID != "devA" {
		t.Fatalf("expected devA got %q", got.DeviceAID)
	}

	confirmed := ConfirmedPair{
		PairID:      "pair-1",
		DeviceA:     "devA",
		DeviceB:     "devB",
		AEncPubkey:  []byte("A-enc"),
		ASignPubkey: []byte("A-sign"),
		BEncPubkey:  []byte("B-enc"),
		BSignPubkey: []byte("B-sign"),
	}
	if err := ps.Confirm(confirmed); err != nil {
		t.Fatalf("confirm: %v", err)
	}
	if err := ps.DeletePending("tok-abc"); err != nil {
		t.Fatalf("delete pending: %v", err)
	}

	cp, err := ps.Get(confirmed.PairID)
	if err != nil || cp.DeviceB != "devB" {
		t.Fatalf("get confirmed: %v got=%+v", err, cp)
	}
	if _, err := ps.GetPending("tok-abc"); err != ErrNotFound {
		t.Fatalf("pending should be gone after confirm, got %v", err)
	}
}

func TestPairStore_LookupSignPubkey(t *testing.T) {
	dir := t.TempDir()
	s, _ := OpenBolt(filepath.Join(dir, "p.db"))
	defer s.Close()
	ps := NewPairStore(s)
	_ = ps.Confirm(ConfirmedPair{
		PairID: "p1", DeviceA: "A", DeviceB: "B",
		AEncPubkey: []byte{1}, ASignPubkey: []byte{2},
		BEncPubkey: []byte{3}, BSignPubkey: []byte{4},
	})
	pk, err := ps.SignPubkeyFor("A")
	if err != nil || pk[0] != 2 {
		t.Fatalf("lookup A sign: %v %v", err, pk)
	}
	pk, err = ps.SignPubkeyFor("B")
	if err != nil || pk[0] != 4 {
		t.Fatalf("lookup B sign: %v %v", err, pk)
	}
}
