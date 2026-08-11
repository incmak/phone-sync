package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"go.etcd.io/bbolt"
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

func TestConfirmPendingRejectsPairAndDeviceBindingCollisionsWithoutMutation(t *testing.T) {
	tests := []struct {
		name      string
		existing  []ConfirmedPair
		candidate ConfirmedPair
	}{
		{
			name:      "pair ID",
			existing:  []ConfirmedPair{{PairID: "occupied-pair", DeviceA: "old-a", DeviceB: "old-b"}},
			candidate: ConfirmedPair{PairID: "occupied-pair", DeviceA: "new-a", DeviceB: "new-b"},
		},
		{
			name:      "Device A",
			existing:  []ConfirmedPair{{PairID: "existing-a", DeviceA: "shared-a", DeviceB: "old-b"}},
			candidate: ConfirmedPair{PairID: "candidate-a", DeviceA: "shared-a", DeviceB: "new-b"},
		},
		{
			name:      "Device B",
			existing:  []ConfirmedPair{{PairID: "existing-b", DeviceA: "old-a", DeviceB: "shared-b"}},
			candidate: ConfirmedPair{PairID: "candidate-b", DeviceA: "new-a", DeviceB: "shared-b"},
		},
		{
			name: "mixed",
			existing: []ConfirmedPair{
				{PairID: "occupied-mixed", DeviceA: "pair-id-a", DeviceB: "pair-id-b"},
				{PairID: "binding-a", DeviceA: "shared-mixed-a", DeviceB: "binding-a-peer"},
				{PairID: "binding-b", DeviceA: "binding-b-peer", DeviceB: "shared-mixed-b"},
			},
			candidate: ConfirmedPair{PairID: "occupied-mixed", DeviceA: "shared-mixed-a", DeviceB: "shared-mixed-b"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ps := newTestPairStore(t)
			candidate := tt.candidate
			candidate.AEncPubkey = []byte("candidate-a-enc")
			candidate.ASignPubkey = []byte("candidate-a-sign")
			candidate.BEncPubkey = []byte("candidate-b-enc")
			candidate.BSignPubkey = []byte("candidate-b-sign")
			pairIDs, deviceIDs := seedCollisionFixture(t, ps, tt.existing, candidate, "collision-token")
			before := snapshotPairBindings(t, ps, pairIDs, deviceIDs)

			if _, err := ps.ConfirmPending("collision-token", candidate, []byte("signature")); !errors.Is(err, ErrPairConflict) {
				t.Fatalf("ConfirmPending collision error = %v, want ErrPairConflict", err)
			}

			assertPairBindingsUnchanged(t, ps, before)
			pending, err := ps.GetPending("collision-token")
			if err != nil {
				t.Fatal(err)
			}
			if pending.PairID != "" {
				t.Fatalf("collision committed pending token to %q", pending.PairID)
			}
		})
	}
}

func TestConfirmPendingConcurrentBindingCollisionsDoNotCreateCandidates(t *testing.T) {
	ps := newTestPairStore(t)
	existing := ConfirmedPair{PairID: "concurrent-existing", DeviceA: "concurrent-shared-a", DeviceB: "concurrent-old-b"}
	if err := ps.Confirm(existing); err != nil {
		t.Fatal(err)
	}

	const attempts = 16
	pairIDs := []string{existing.PairID}
	deviceIDs := []string{existing.DeviceA, existing.DeviceB}
	candidates := make([]ConfirmedPair, attempts)
	for index := range candidates {
		candidates[index] = ConfirmedPair{
			PairID:     fmt.Sprintf("concurrent-candidate-%02d", index),
			DeviceA:    existing.DeviceA,
			DeviceB:    fmt.Sprintf("concurrent-new-b-%02d", index),
			AEncPubkey: []byte("candidate-a-enc"), ASignPubkey: []byte("candidate-a-sign"),
			BEncPubkey: []byte("candidate-b-enc"), BSignPubkey: []byte("candidate-b-sign"),
		}
		token := fmt.Sprintf("concurrent-token-%02d", index)
		if err := ps.PutPending(PendingPair{
			PairToken: token, DeviceAID: candidates[index].DeviceA,
			AEncPubkey: candidates[index].AEncPubkey, ASignPubkey: candidates[index].ASignPubkey,
			CreatedAt: time.Now().Unix(),
		}); err != nil {
			t.Fatal(err)
		}
		pairIDs = append(pairIDs, candidates[index].PairID)
		deviceIDs = append(deviceIDs, candidates[index].DeviceB)
	}
	before := snapshotPairBindings(t, ps, pairIDs, deviceIDs)

	start := make(chan struct{})
	errorsByAttempt := make([]error, attempts)
	var wg sync.WaitGroup
	for index := range candidates {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			<-start
			token := fmt.Sprintf("concurrent-token-%02d", index)
			_, errorsByAttempt[index] = ps.ConfirmPending(token, candidates[index], []byte("signature"))
		}(index)
	}
	close(start)
	wg.Wait()

	for index, err := range errorsByAttempt {
		if !errors.Is(err, ErrPairConflict) {
			t.Fatalf("attempt %d error = %v, want ErrPairConflict", index, err)
		}
		pending, lookupErr := ps.GetPending(fmt.Sprintf("concurrent-token-%02d", index))
		if lookupErr != nil {
			t.Fatal(lookupErr)
		}
		if pending.PairID != "" {
			t.Fatalf("attempt %d committed pending token to %q", index, pending.PairID)
		}
	}
	assertPairBindingsUnchanged(t, ps, before)
}

type pairBindingSnapshot struct {
	pairs   map[string][]byte
	devices map[string][]byte
}

func seedCollisionFixture(t *testing.T, ps *PairStore, existing []ConfirmedPair, candidate ConfirmedPair, token string) ([]string, []string) {
	t.Helper()
	pairIDs := []string{candidate.PairID}
	deviceIDs := []string{candidate.DeviceA, candidate.DeviceB}
	for _, pair := range existing {
		if err := ps.Confirm(pair); err != nil {
			t.Fatal(err)
		}
		pairIDs = append(pairIDs, pair.PairID)
		deviceIDs = append(deviceIDs, pair.DeviceA, pair.DeviceB)
	}
	if err := ps.PutPending(PendingPair{
		PairToken: token, DeviceAID: candidate.DeviceA,
		AEncPubkey: candidate.AEncPubkey, ASignPubkey: candidate.ASignPubkey,
		CreatedAt: time.Now().Unix(),
	}); err != nil {
		t.Fatal(err)
	}
	return pairIDs, deviceIDs
}

func snapshotPairBindings(t *testing.T, ps *PairStore, pairIDs, deviceIDs []string) pairBindingSnapshot {
	t.Helper()
	snapshot := pairBindingSnapshot{pairs: make(map[string][]byte), devices: make(map[string][]byte)}
	for _, pairID := range pairIDs {
		raw, err := ps.bolt.Get(bucketConfirmed, pairID)
		if err != nil {
			t.Fatal(err)
		}
		snapshot.pairs[pairID] = raw
	}
	for _, deviceID := range deviceIDs {
		raw, err := ps.bolt.Get(bucketByDevice, deviceID)
		if err != nil {
			t.Fatal(err)
		}
		snapshot.devices[deviceID] = raw
	}
	return snapshot
}

func assertPairBindingsUnchanged(t *testing.T, ps *PairStore, before pairBindingSnapshot) {
	t.Helper()
	for pairID, want := range before.pairs {
		got, err := ps.bolt.Get(bucketConfirmed, pairID)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("confirmed pair %q changed: got %x, want %x", pairID, got, want)
		}
	}
	for deviceID, want := range before.devices {
		got, err := ps.bolt.Get(bucketByDevice, deviceID)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("device index %q changed: got %x, want %x", deviceID, got, want)
		}
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

func TestRevokeByDevicePurgesPairAuthorizationCapabilitiesTokenAndMailboxState(t *testing.T) {
	b := openTestBolt(t)
	ps := NewPairStore(b)
	mailbox := NewMailboxStore(b, DefaultMailboxLimits())
	pending := PendingPair{
		PairToken: "revoke-token", DeviceAID: "dev-a",
		AEncPubkey: []byte("old-a-enc"), ASignPubkey: []byte("old-a-sign"),
		CreatedAt: time.Now().Unix(),
	}
	if err := ps.PutPending(pending); err != nil {
		t.Fatal(err)
	}
	pair := ConfirmedPair{
		PairID: "revoke-pair", DeviceA: "dev-a", DeviceB: "dev-b",
		AEncPubkey: pending.AEncPubkey, ASignPubkey: pending.ASignPubkey,
		BEncPubkey: []byte("old-b-enc"), BSignPubkey: []byte("old-b-sign"),
	}
	if _, err := ps.ConfirmPending(pending.PairToken, pair, []byte("confirmation")); err != nil {
		t.Fatal(err)
	}
	if err := ps.UpdateCapabilities(pair.DeviceA, []int{2, 1}, "old-a"); err != nil {
		t.Fatal(err)
	}
	if err := ps.UpdateCapabilities(pair.DeviceB, []int{2, 1}, "old-b"); err != nil {
		t.Fatal(err)
	}

	forward := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	reverse := testMailboxRecord("22222222-2222-4222-8222-222222222222", "b")
	reverse.RecipientDevice, reverse.SenderDevice = pair.DeviceA, pair.DeviceB
	tombstone := testMailboxRecord("33333333-3333-4333-8333-333333333333", "c")
	for _, rec := range []MailboxRecord{forward, reverse, tombstone} {
		if _, err := mailbox.Put(rec, time.UnixMilli(1000)); err != nil {
			t.Fatal(err)
		}
	}
	if err := mailbox.Ack(tombstone.RecipientDevice, tombstone.MsgID, tombstone.EnvelopeSHA256, time.UnixMilli(1001)); err != nil {
		t.Fatal(err)
	}

	revoked, err := ps.RevokeByDevice(pair.DeviceB)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(*revoked, pair) {
		t.Fatalf("revoked pair = %#v, want %#v", *revoked, pair)
	}
	if _, err := ps.Get(pair.PairID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("confirmed pair still present: %v", err)
	}
	for _, deviceID := range []string{pair.DeviceA, pair.DeviceB} {
		if _, err := ps.PeerFor(deviceID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("device %s still indexed: %v", deviceID, err)
		}
		pendingRecords, err := mailbox.Pending(deviceID, 10)
		if err != nil || len(pendingRecords) != 0 {
			t.Fatalf("pending mailbox for %s = %#v, %v", deviceID, pendingRecords, err)
		}
		statuses, err := mailbox.Statuses(deviceID, time.UnixMilli(0))
		if err != nil || len(statuses) != 0 {
			t.Fatalf("delivery statuses for %s = %#v, %v", deviceID, statuses, err)
		}
	}
	if _, err := ps.GetPending(pending.PairToken); !errors.Is(err, ErrNotFound) {
		t.Fatalf("retained pairing token still present: %v", err)
	}

	rebound := ConfirmedPair{
		PairID: "rebound-pair", DeviceA: pair.DeviceA, DeviceB: pair.DeviceB,
		AEncPubkey: []byte("new-a-enc"), ASignPubkey: []byte("new-a-sign"),
		BEncPubkey: []byte("new-b-enc"), BSignPubkey: []byte("new-b-sign"),
	}
	if err := ps.Confirm(rebound); err != nil {
		t.Fatalf("rebind after revocation: %v", err)
	}
	self, peer, floor, err := ps.CapabilitiesFor(rebound.DeviceA)
	if err != nil {
		t.Fatal(err)
	}
	if len(self.Protocols) != 0 || self.AppVersion != "" || len(peer.Protocols) != 0 || peer.AppVersion != "" || floor != 1 {
		t.Fatalf("rebound pair inherited capabilities: self=%#v peer=%#v floor=%d", self, peer, floor)
	}
	for index, rec := range []MailboxRecord{
		testMailboxRecord("44444444-4444-4444-8444-444444444444", "d"),
		func() MailboxRecord {
			rec := testMailboxRecord("55555555-5555-4555-8555-555555555555", "e")
			rec.RecipientDevice, rec.SenderDevice = rebound.DeviceA, rebound.DeviceB
			return rec
		}(),
	} {
		result, err := mailbox.Put(rec, time.UnixMilli(2000+int64(index)))
		if err != nil {
			t.Fatalf("put after revocation: %v", err)
		}
		if result.AcceptanceSequence != 1 {
			t.Fatalf("recipient sequence after revocation = %d, want 1", result.AcceptanceSequence)
		}
	}
}

func TestRevokeByDeviceRollsBackPairDeletionWhenMailboxPurgeFails(t *testing.T) {
	b := openTestBolt(t)
	ps := NewPairStore(b)
	pending := PendingPair{
		PairToken: "rollback-token", DeviceAID: "dev-a",
		AEncPubkey: []byte("a-enc"), ASignPubkey: []byte("a-sign"), CreatedAt: time.Now().Unix(),
	}
	if err := ps.PutPending(pending); err != nil {
		t.Fatal(err)
	}
	pair := ConfirmedPair{
		PairID: "rollback-pair", DeviceA: "dev-a", DeviceB: "dev-b",
		AEncPubkey: pending.AEncPubkey, ASignPubkey: pending.ASignPubkey,
		BEncPubkey: []byte("b-enc"), BSignPubkey: []byte("b-sign"),
	}
	if _, err := ps.ConfirmPending(pending.PairToken, pair, []byte("confirmation")); err != nil {
		t.Fatal(err)
	}
	if err := ps.UpdateCapabilities(pair.DeviceA, []int{2, 1}, "a"); err != nil {
		t.Fatal(err)
	}
	if err := ps.UpdateCapabilities(pair.DeviceB, []int{2, 1}, "b"); err != nil {
		t.Fatal(err)
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		items, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxItems))
		if err != nil {
			return err
		}
		return items.Put(itemKey(pair.DeviceB, "66666666-6666-4666-8666-666666666666"), []byte("{"))
	}); err != nil {
		t.Fatal(err)
	}

	if _, err := ps.RevokeByDevice(pair.DeviceA); err == nil {
		t.Fatal("RevokeByDevice succeeded despite corrupt mailbox state")
	}
	stored, err := ps.Get(pair.PairID)
	if err != nil || !reflect.DeepEqual(*stored, pair) {
		t.Fatalf("confirmed pair did not roll back: %#v, %v", stored, err)
	}
	for device, wantPeer := range map[string]string{pair.DeviceA: pair.DeviceB, pair.DeviceB: pair.DeviceA} {
		if got, err := ps.PeerFor(device); err != nil || got != wantPeer {
			t.Fatalf("device index %s did not roll back: peer=%q err=%v", device, got, err)
		}
	}
	if state, err := ps.PendingState(pending.PairToken); err != nil || state != PairCommitted {
		t.Fatalf("pending completion marker did not roll back: state=%q err=%v", state, err)
	}
	if _, _, floor, err := ps.CapabilitiesFor(pair.DeviceA); err != nil || floor != 2 {
		t.Fatalf("capability state did not roll back: floor=%d err=%v", floor, err)
	}
	indexedToken, err := b.Get(bucketRetainedTokenByPair, pair.PairID)
	if err != nil || string(indexedToken) != pending.PairToken {
		t.Fatalf("retained-token index did not roll back: token=%q err=%v", indexedToken, err)
	}
}

func TestRevokeRequiredBeforeConfirmCanRebindDevices(t *testing.T) {
	ps := newTestPairStore(t)
	original := ConfirmedPair{PairID: "original", DeviceA: "dev-a", DeviceB: "dev-b"}
	if err := ps.Confirm(original); err != nil {
		t.Fatal(err)
	}
	candidate := ConfirmedPair{PairID: "candidate", DeviceA: "dev-a", DeviceB: "dev-c"}
	if err := ps.Confirm(candidate); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("Confirm live device binding error = %v, want ErrPairConflict", err)
	}
	if _, err := ps.Get(candidate.PairID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("conflicting candidate was persisted: %v", err)
	}
	if peer, err := ps.PeerFor(original.DeviceA); err != nil || peer != original.DeviceB {
		t.Fatalf("original binding changed: peer=%q err=%v", peer, err)
	}
	if _, err := ps.RevokeByDevice(original.DeviceB); err != nil {
		t.Fatal(err)
	}
	if err := ps.Confirm(candidate); err != nil {
		t.Fatalf("Confirm after authenticated-state revocation: %v", err)
	}
	if peer, err := ps.PeerFor(candidate.DeviceA); err != nil || peer != candidate.DeviceB {
		t.Fatalf("rebound device index: peer=%q err=%v", peer, err)
	}
}

func TestExpiredMailboxSweepRejectsRevokedSessionAfterRebind(t *testing.T) {
	b := openTestBolt(t)
	ps := NewPairStore(b)
	mailbox := NewMailboxStore(b, DefaultMailboxLimits())
	original := ConfirmedPair{PairID: "expired-sweep-old", DeviceA: "dev-a", DeviceB: "dev-b"}
	if err := ps.Confirm(original); err != nil {
		t.Fatal(err)
	}
	if _, err := ps.RevokeBySession(original.DeviceA, original.PairID); err != nil {
		t.Fatal(err)
	}
	rebound := ConfirmedPair{PairID: "expired-sweep-new", DeviceA: original.DeviceA, DeviceB: original.DeviceB}
	if err := ps.Confirm(rebound); err != nil {
		t.Fatal(err)
	}

	acceptedAt := time.UnixMilli(1000)
	rec := testMailboxRecord("77777777-7777-4777-8777-777777777777", "7")
	if _, err := mailbox.PutForPair(rebound.PairID, rec, acceptedAt); err != nil {
		t.Fatal(err)
	}
	if _, err := mailbox.ExpireForPair(original.PairID, original.DeviceA, acceptedAt.Add(DefaultMailboxLimits().Retention+time.Millisecond)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked generation expiry sweep error = %v, want ErrNotFound", err)
	}
	pending, err := mailbox.PendingForPair(rebound.PairID, rec.RecipientDevice, 1)
	if err != nil || len(pending) != 1 || pending[0].MsgID != rec.MsgID {
		t.Fatalf("rebound mailbox after revoked sweep = %#v, %v", pending, err)
	}
}

func TestRevokeUsesPairScopedRetainedTokenIndexWithoutScanningUnrelatedPending(t *testing.T) {
	b := openTestBolt(t)
	ps := NewPairStore(b)
	for index := 0; index < 256; index++ {
		if err := ps.PutPending(PendingPair{
			PairToken: fmt.Sprintf("unrelated-%03d", index), DeviceAID: fmt.Sprintf("unrelated-device-%03d", index),
			AEncPubkey: []byte("enc"), ASignPubkey: []byte("sign"), CreatedAt: time.Now().Unix(),
		}); err != nil {
			t.Fatal(err)
		}
	}
	committed := PendingPair{
		PairToken: "indexed-token", DeviceAID: "indexed-a",
		AEncPubkey: []byte("a-enc"), ASignPubkey: []byte("a-sign"), CreatedAt: time.Now().Unix(),
	}
	if err := ps.PutPending(committed); err != nil {
		t.Fatal(err)
	}
	pair := ConfirmedPair{
		PairID: "indexed-pair", DeviceA: committed.DeviceAID, DeviceB: "indexed-b",
		AEncPubkey: committed.AEncPubkey, ASignPubkey: committed.ASignPubkey,
		BEncPubkey: []byte("b-enc"), BSignPubkey: []byte("b-sign"),
	}
	if _, err := ps.ConfirmPending(committed.PairToken, pair, []byte("confirmation")); err != nil {
		t.Fatal(err)
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		return tx.Bucket([]byte(bucketPending)).Put([]byte("unrelated-corrupt"), []byte("{"))
	}); err != nil {
		t.Fatal(err)
	}
	indexedToken, err := b.Get(bucketRetainedTokenByPair, pair.PairID)
	if err != nil || string(indexedToken) != committed.PairToken {
		t.Fatalf("retained token index=%q err=%v", indexedToken, err)
	}

	if _, err := ps.RevokeByDevice(pair.DeviceA); err != nil {
		t.Fatalf("indexed revoke consulted unrelated pending state: %v", err)
	}
	if _, err := ps.GetPending(committed.PairToken); !errors.Is(err, ErrNotFound) {
		t.Fatalf("committed token survived revoke: %v", err)
	}
	if _, err := ps.GetPending("unrelated-000"); err != nil {
		t.Fatalf("unrelated token was removed: %v", err)
	}
	rawCorrupt, err := b.Get(bucketPending, "unrelated-corrupt")
	if err != nil || string(rawCorrupt) != "{" {
		t.Fatalf("unrelated corrupt token changed: %q err=%v", rawCorrupt, err)
	}
	indexedToken, err = b.Get(bucketRetainedTokenByPair, pair.PairID)
	if err != nil || indexedToken != nil {
		t.Fatalf("retained token index survived revoke: %q err=%v", indexedToken, err)
	}
}

func TestPairStoreMigratesLegacyRetainedTokenIndexOnce(t *testing.T) {
	b := openTestBolt(t)
	pair := ConfirmedPair{
		PairID: "legacy-retained-pair", DeviceA: "legacy-a", DeviceB: "legacy-b",
		AEncPubkey: []byte("a-enc"), ASignPubkey: []byte("a-sign"),
		BEncPubkey: []byte("b-enc"), BSignPubkey: []byte("b-sign"),
	}
	pending := PendingPair{
		PairToken: "legacy-retained-token", DeviceAID: pair.DeviceA,
		AEncPubkey: pair.AEncPubkey, ASignPubkey: pair.ASignPubkey,
		DeviceBID: pair.DeviceB, BEncPubkey: pair.BEncPubkey, BSignPubkey: pair.BSignPubkey,
		ConfirmationSig: []byte("confirmation"), PairID: pair.PairID, CreatedAt: time.Now().Unix(),
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		pendingBucket, err := tx.CreateBucketIfNotExists([]byte(bucketPending))
		if err != nil {
			return err
		}
		pendingRaw, err := json.Marshal(pending)
		if err != nil {
			return err
		}
		if err := pendingBucket.Put([]byte(pending.PairToken), pendingRaw); err != nil {
			return err
		}
		confirmed, err := tx.CreateBucketIfNotExists([]byte(bucketConfirmed))
		if err != nil {
			return err
		}
		pairRaw, err := json.Marshal(pair)
		if err != nil {
			return err
		}
		if err := confirmed.Put([]byte(pair.PairID), pairRaw); err != nil {
			return err
		}
		byDevice, err := tx.CreateBucketIfNotExists([]byte(bucketByDevice))
		if err != nil {
			return err
		}
		if err := byDevice.Put([]byte(pair.DeviceA), []byte(pair.PairID)); err != nil {
			return err
		}
		return byDevice.Put([]byte(pair.DeviceB), []byte(pair.PairID))
	}); err != nil {
		t.Fatal(err)
	}

	ps := NewPairStore(b)
	indexedToken, err := b.Get(bucketRetainedTokenByPair, pair.PairID)
	if err != nil || string(indexedToken) != pending.PairToken {
		t.Fatalf("legacy retained token was not migrated: token=%q err=%v", indexedToken, err)
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		return tx.Bucket([]byte(bucketPending)).Put([]byte("post-migration-corrupt"), []byte("{"))
	}); err != nil {
		t.Fatal(err)
	}
	ps = NewPairStore(b)
	if _, err := ps.RevokeByDevice(pair.DeviceB); err != nil {
		t.Fatal(err)
	}
	if _, err := ps.GetPending(pending.PairToken); !errors.Is(err, ErrNotFound) {
		t.Fatalf("migrated retained token survived revoke: %v", err)
	}
}

func TestPendingCapRejectsAtomicallyWithoutBoltGrowth(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pending-cap.db")
	b, err := OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	ps := NewPairStoreWithLimits(b, PendingPairLimits{MaxPending: 2, TTL: 5 * time.Minute, SweepBatch: 8})
	for index := 0; index < 2; index++ {
		if err := ps.PutPending(PendingPair{
			PairToken: fmt.Sprintf("pending-%d", index), DeviceAID: fmt.Sprintf("device-%d", index),
			AEncPubkey: bytes.Repeat([]byte{1}, 32), ASignPubkey: bytes.Repeat([]byte{2}, 32), CreatedAt: int64(index + 1),
		}); err != nil {
			t.Fatal(err)
		}
	}
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	err = ps.PutPending(PendingPair{
		PairToken: "rejected", DeviceAID: "rejected-device",
		AEncPubkey: bytes.Repeat([]byte{3}, 32), ASignPubkey: bytes.Repeat([]byte{4}, 32), CreatedAt: 3,
	})
	if !errors.Is(err, ErrPendingPairLimit) {
		t.Fatalf("cap error = %v, want ErrPendingPairLimit", err)
	}
	if _, err := ps.GetPending("rejected"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("rejected pending pair was persisted: %v", err)
	}
	count, err := ps.PendingCount()
	if err != nil || count != 2 {
		t.Fatalf("pending count = %d, %v; want 2", count, err)
	}
	after, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if after.Size() != before.Size() {
		t.Fatalf("Bolt grew after rejected insert: before=%d after=%d", before.Size(), after.Size())
	}
}

func TestPendingCapIsAtomicUnderConcurrentInserts(t *testing.T) {
	b := openTestBolt(t)
	ps := NewPairStoreWithLimits(b, PendingPairLimits{MaxPending: 4, TTL: 5 * time.Minute, SweepBatch: 8})
	var wg sync.WaitGroup
	var accepted atomic.Int64
	for index := 0; index < 32; index++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			err := ps.PutPending(PendingPair{
				PairToken: fmt.Sprintf("concurrent-%d", index), DeviceAID: fmt.Sprintf("device-%d", index),
				AEncPubkey: bytes.Repeat([]byte{1}, 32), ASignPubkey: bytes.Repeat([]byte{2}, 32), CreatedAt: 1,
			})
			if err == nil {
				accepted.Add(1)
				return
			}
			if !errors.Is(err, ErrPendingPairLimit) {
				t.Errorf("insert %d error = %v", index, err)
			}
		}(index)
	}
	wg.Wait()
	if got := accepted.Load(); got != 4 {
		t.Fatalf("accepted inserts = %d, want 4", got)
	}
	if count, err := ps.PendingCount(); err != nil || count != 4 {
		t.Fatalf("pending count = %d, %v; want 4", count, err)
	}
}

func TestPendingPairSweepIsBoundedAndReleasesCapacity(t *testing.T) {
	b := openTestBolt(t)
	ps := NewPairStoreWithLimits(b, PendingPairLimits{MaxPending: 3, TTL: 5 * time.Minute, SweepBatch: 2})
	for index := 0; index < 3; index++ {
		if err := ps.PutPending(PendingPair{
			PairToken: fmt.Sprintf("expired-%d", index), DeviceAID: fmt.Sprintf("device-%d", index),
			AEncPubkey: bytes.Repeat([]byte{1}, 32), ASignPubkey: bytes.Repeat([]byte{2}, 32), CreatedAt: int64(index + 1),
		}); err != nil {
			t.Fatal(err)
		}
	}
	removed, err := ps.SweepExpired(time.Unix(10*60, 0))
	if err != nil || removed != 2 {
		t.Fatalf("first bounded sweep = %d, %v; want 2", removed, err)
	}
	if count, err := ps.PendingCount(); err != nil || count != 1 {
		t.Fatalf("pending after first sweep = %d, %v; want 1", count, err)
	}
	if err := ps.PutPending(PendingPair{
		PairToken: "replacement", DeviceAID: "replacement-device",
		AEncPubkey: bytes.Repeat([]byte{3}, 32), ASignPubkey: bytes.Repeat([]byte{4}, 32), CreatedAt: 10 * 60,
	}); err != nil {
		t.Fatalf("released capacity was unavailable: %v", err)
	}
}
