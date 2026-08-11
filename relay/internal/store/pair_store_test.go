package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestPairPendingStatePersistsTransitionsAndCommittedToken(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "pair.db")
	bolt, err := OpenBolt(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	ps := NewPairStore(bolt)
	pending := PendingPair{
		PairToken: "state-token", DeviceAID: "a", AEncPubkey: []byte("a-enc"),
		ASignPubkey: []byte("a-sign"), CreatedAt: time.Now().Unix(),
	}
	if err := ps.PutPending(pending); err != nil {
		t.Fatal(err)
	}
	assertPendingState(t, ps, pending.PairToken, PairWaitingForPeer)

	if err := ps.UpdatePendingB(pending.PairToken, "b", []byte("b-enc"), []byte("b-sign"), "Phone B"); err != nil {
		t.Fatal(err)
	}
	assertPendingState(t, ps, pending.PairToken, PairWaitingForSignature)

	signature := []byte("confirmation")
	if err := ps.UpdatePendingSig(pending.PairToken, signature); err != nil {
		t.Fatal(err)
	}
	assertPendingState(t, ps, pending.PairToken, PairReadyToComplete)

	confirmed, err := ps.ConfirmPending(pending.PairToken, ConfirmedPair{
		PairID: "pair-state", DeviceA: "a", DeviceB: "b",
		AEncPubkey: []byte("a-enc"), ASignPubkey: []byte("a-sign"),
		BEncPubkey: []byte("b-enc"), BSignPubkey: []byte("b-sign"), BDisplayName: "Phone B",
	}, signature)
	if err != nil {
		t.Fatal(err)
	}
	if confirmed.PairID != "pair-state" {
		t.Fatalf("confirmed pair ID = %q", confirmed.PairID)
	}
	assertPendingState(t, ps, pending.PairToken, PairCommitted)
	reinitialized := pending
	reinitialized.CreatedAt += 300
	if err := ps.PutPending(reinitialized); err != nil {
		t.Fatalf("identical init retry after completion: %v", err)
	}
	retained, err := ps.GetPending(pending.PairToken)
	if err != nil {
		t.Fatal(err)
	}
	if retained.PairID != confirmed.PairID || retained.CreatedAt != pending.CreatedAt {
		t.Fatalf("init retry replaced committed token state: %#v", retained)
	}

	if err := bolt.Close(); err != nil {
		t.Fatal(err)
	}
	bolt, err = OpenBolt(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer bolt.Close()
	ps = NewPairStore(bolt)
	assertPendingState(t, ps, pending.PairToken, PairCommitted)
	reopened, err := ps.GetPending(pending.PairToken)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.PairID != confirmed.PairID {
		t.Fatalf("reopened token pair ID = %q, want %q", reopened.PairID, confirmed.PairID)
	}
}

func TestPairPendingTransitionsAreIdempotentAndConflictsDoNotMutate(t *testing.T) {
	ps := newTestPairStore(t)
	pending := PendingPair{
		PairToken: "idempotent-token", DeviceAID: "a", AEncPubkey: []byte("a-enc"),
		ASignPubkey: []byte("a-sign"), ADisplayName: "Phone A", CreatedAt: time.Now().Unix(),
	}
	if err := ps.PutPending(pending); err != nil {
		t.Fatal(err)
	}

	hello := func(enc []byte) error {
		return ps.UpdatePendingB(pending.PairToken, "b", enc, []byte("b-sign"), "Phone B")
	}
	if err := hello([]byte("b-enc")); err != nil {
		t.Fatal(err)
	}
	if err := hello([]byte("b-enc")); err != nil {
		t.Fatalf("identical hello retry: %v", err)
	}
	if err := hello([]byte("other-enc")); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("conflicting hello error = %v, want ErrPairConflict", err)
	}
	stored, err := ps.GetPending(pending.PairToken)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(stored.BEncPubkey, []byte("b-enc")) {
		t.Fatalf("conflicting hello mutated key to %q", stored.BEncPubkey)
	}

	signature := []byte("signature")
	if err := ps.UpdatePendingSig(pending.PairToken, signature); err != nil {
		t.Fatal(err)
	}
	if err := ps.UpdatePendingSig(pending.PairToken, append([]byte(nil), signature...)); err != nil {
		t.Fatalf("identical signature retry: %v", err)
	}
	if err := ps.UpdatePendingSig(pending.PairToken, []byte("other-signature")); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("conflicting signature error = %v, want ErrPairConflict", err)
	}
	stored, err = ps.GetPending(pending.PairToken)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(stored.ConfirmationSig, signature) {
		t.Fatalf("conflicting signature mutated value to %q", stored.ConfirmationSig)
	}

	pair := ConfirmedPair{
		PairID: "first-pair", DeviceA: "a", DeviceB: "b",
		AEncPubkey: []byte("a-enc"), ASignPubkey: []byte("a-sign"), ADisplayName: "Phone A",
		BEncPubkey: []byte("b-enc"), BSignPubkey: []byte("b-sign"), BDisplayName: "Phone B",
	}
	first, err := ps.ConfirmPending(pending.PairToken, pair, signature)
	if err != nil {
		t.Fatal(err)
	}
	retry := pair
	retry.PairID = "discarded-retry-pair"
	second, err := ps.ConfirmPending(pending.PairToken, retry, append([]byte(nil), signature...))
	if err != nil {
		t.Fatalf("identical complete retry: %v", err)
	}
	if second.PairID != first.PairID {
		t.Fatalf("complete retry pair ID = %q, want original %q", second.PairID, first.PairID)
	}

	conflict := retry
	conflict.BEncPubkey = []byte("conflicting-b-enc")
	if _, err := ps.ConfirmPending(pending.PairToken, conflict, signature); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("conflicting complete error = %v, want ErrPairConflict", err)
	}
	storedPair, err := ps.Get(first.PairID)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(storedPair.BEncPubkey, pair.BEncPubkey) {
		t.Fatalf("conflicting complete mutated pair to %#v", storedPair)
	}
	if _, err := ps.Get(retry.PairID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("retry candidate pair ID was persisted: %v", err)
	}
	peer, err := ps.PeerFor(pair.DeviceA)
	if err != nil || peer != pair.DeviceB {
		t.Fatalf("device index after conflict = %q, %v", peer, err)
	}
}

func assertPendingState(t *testing.T, ps *PairStore, token string, want PendingPairState) {
	t.Helper()
	got, err := ps.PendingState(token)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("PendingState(%q) = %q, want %q", token, got, want)
	}
}

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

func TestPairCapabilitiesRejectPresentCorruptProtocolFloor(t *testing.T) {
	encodings := [][]byte{
		{},
		{1},
		{3},
		{2, 0},
		[]byte("2"),
	}
	for _, encoding := range encodings {
		t.Run(fmt.Sprintf("%x", encoding), func(t *testing.T) {
			ps := newTestPairStore(t)
			confirmTestPair(t, ps, "a", "b")
			if err := ps.bolt.Put(bucketProtocolFloor, "pair-1", encoding); err != nil {
				t.Fatal(err)
			}
			if _, _, _, err := ps.CapabilitiesFor("a"); err == nil {
				t.Fatalf("CapabilitiesFor accepted corrupt floor %x", encoding)
			}
		})
	}
}

func TestPairCapabilityUpdateRollsBackOnCorruptProtocolFloor(t *testing.T) {
	ps := newTestPairStore(t)
	confirmTestPair(t, ps, "a", "b")
	if err := ps.UpdateCapabilities("a", []int{1}, "before"); err != nil {
		t.Fatal(err)
	}
	if err := ps.bolt.Put(bucketProtocolFloor, "pair-1", []byte{3}); err != nil {
		t.Fatal(err)
	}
	if err := ps.UpdateCapabilities("a", []int{2, 1}, "after"); err == nil {
		t.Fatal("UpdateCapabilities accepted corrupt protocol floor")
	}
	raw, err := ps.bolt.Get(bucketCapabilities, "a")
	if err != nil {
		t.Fatal(err)
	}
	var stored DeviceCapabilities
	if err := json.Unmarshal(raw, &stored); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(stored.Protocols, []int{1}) || stored.AppVersion != "before" {
		t.Fatalf("capability update was not rolled back: %#v", stored)
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
