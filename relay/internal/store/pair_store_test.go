package store

import (
	"errors"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func newTestPairStore(t *testing.T) *PairStore {
	t.Helper()
	s, err := OpenBolt(filepath.Join(t.TempDir(), "pair.db"))
	if err != nil {
		t.Fatalf("open pair store: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return NewPairStore(s)
}

func confirmTestPair(t *testing.T, ps *PairStore, deviceA, deviceB string) {
	t.Helper()
	if err := ps.Confirm(ConfirmedPair{PairID: "pair-1", DeviceA: deviceA, DeviceB: deviceB}); err != nil {
		t.Fatalf("confirm pair: %v", err)
	}
}

func TestPairProtocolFloorAdvancesOnlyWhenBothSupportV2(t *testing.T) {
	ps := newTestPairStore(t)
	confirmTestPair(t, ps, "a", "b")
	if err := ps.UpdateCapabilities("a", []int{2, 1}, "0.8.0"); err != nil {
		t.Fatal(err)
	}
	_, _, floor, err := ps.CapabilitiesFor("a")
	if err != nil {
		t.Fatal(err)
	}
	if floor != 1 {
		t.Fatalf("floor after A only = %d", floor)
	}
	if err := ps.UpdateCapabilities("b", []int{2, 1}, "0.8.0"); err != nil {
		t.Fatal(err)
	}
	_, _, floor, err = ps.CapabilitiesFor("a")
	if err != nil {
		t.Fatal(err)
	}
	if floor != 2 {
		t.Fatalf("floor after both = %d", floor)
	}
}

func TestPairCapabilitiesPersistActualAdvertisementsAndMonotonicFloor(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "pair.db")
	s, err := OpenBolt(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	ps := NewPairStore(s)
	confirmTestPair(t, ps, "a", "b")
	aProtocols := []int{2, 1}
	if err := ps.UpdateCapabilities("a", aProtocols, "0.8.0-a"); err != nil {
		t.Fatal(err)
	}
	aProtocols[0] = 99
	if err := ps.UpdateCapabilities("b", []int{1, 2}, "0.8.0-b"); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}

	s, err = OpenBolt(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ps = NewPairStore(s)
	self, peer, floor, err := ps.CapabilitiesFor("a")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(self.Protocols, []int{2, 1}) || self.AppVersion != "0.8.0-a" || self.UpdatedAt == 0 {
		t.Fatalf("persisted self capabilities = %#v", self)
	}
	if !reflect.DeepEqual(peer.Protocols, []int{1, 2}) || peer.AppVersion != "0.8.0-b" || peer.UpdatedAt == 0 {
		t.Fatalf("persisted peer capabilities = %#v", peer)
	}
	if floor != 2 {
		t.Fatalf("persisted floor = %d, want 2", floor)
	}

	self.Protocols[0] = 88
	if err := ps.UpdateCapabilities("b", []int{1}, "0.7.0"); err != nil {
		t.Fatal(err)
	}
	storedSelf, storedPeer, floor, err := ps.CapabilitiesFor("a")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(storedSelf.Protocols, []int{2, 1}) {
		t.Fatalf("returned protocol slice aliased store: %v", storedSelf.Protocols)
	}
	if !reflect.DeepEqual(storedPeer.Protocols, []int{1}) || storedPeer.AppVersion != "0.7.0" {
		t.Fatalf("updated peer capabilities = %#v", storedPeer)
	}
	if floor != 2 {
		t.Fatalf("floor downgraded to %d", floor)
	}
}

func TestPairCapabilitiesRequireConfirmedPair(t *testing.T) {
	ps := newTestPairStore(t)
	if err := ps.UpdateCapabilities("unknown", []int{2, 1}, "0.8.0"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("update outside pair error = %v, want ErrNotFound", err)
	}
	if _, _, _, err := ps.CapabilitiesFor("unknown"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("lookup outside pair error = %v, want ErrNotFound", err)
	}
}

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
