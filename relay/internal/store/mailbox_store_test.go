package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"go.etcd.io/bbolt"
)

func TestMailboxPutIsIdempotentAndSurvivesReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mailbox.db")
	b, err := OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: 24 * time.Hour})
	rec := MailboxRecord{RecipientDevice: "dev-b", SenderDevice: "dev-a", MsgID: "11111111-1111-4111-8111-111111111111", EnvelopeSHA256: strings.Repeat("a", 64), Envelope: []byte(`{"v":2}`)}
	first, err := s.Put(rec, time.UnixMilli(1000))
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.Put(rec, time.UnixMilli(2000))
	if err != nil {
		t.Fatal(err)
	}
	if first.AcceptedAt != second.AcceptedAt {
		t.Fatalf("acceptance time changed: %v != %v", first, second)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	b, err = OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	got, err := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: 24 * time.Hour}).Pending("dev-b", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].MsgID != rec.MsgID {
		t.Fatalf("pending = %#v", got)
	}
}

func TestPendingMetadataForPairPreservesAuthorizationOrderAndOmitsEnvelope(t *testing.T) {
	b := openTestBolt(t)
	pairs, err := OpenPairStore(b)
	if err != nil {
		t.Fatal(err)
	}
	if err := pairs.Confirm(ConfirmedPair{PairID: "pair-one", DeviceA: "dev-a", DeviceB: "dev-b"}); err != nil {
		t.Fatal(err)
	}
	store := NewMailboxStore(b, MailboxLimits{MaxItems: 4, MaxBytes: 1 << 20, Retention: time.Hour})
	wantOrder := []string{"22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111"}
	for index, id := range wantOrder {
		record := testMailboxRecord(id, fmt.Sprintf("%d", index))
		record.SenderDevice, record.RecipientDevice = "dev-a", "dev-b"
		if _, err := store.PutForPair("pair-one", record, time.UnixMilli(int64(1000+index))); err != nil {
			t.Fatal(err)
		}
	}
	metadata, err := store.PendingMetadataForPair("pair-one", "dev-b", 4)
	if err != nil || len(metadata) != 2 || metadata[0].MsgID != wantOrder[0] || metadata[1].MsgID != wantOrder[1] || metadata[0].SenderDevice != "dev-a" {
		t.Fatalf("ordered metadata = %#v, %v", metadata, err)
	}
	raw, err := json.Marshal(metadata)
	if err != nil || bytes.Contains(raw, []byte("envelope")) {
		t.Fatalf("metadata exposed envelope: %s, %v", raw, err)
	}
	if _, err := store.PendingMetadataForPair("wrong-pair", "dev-b", 4); !errors.Is(err, ErrNotFound) {
		t.Fatalf("wrong-pair metadata error = %v, want not found", err)
	}
}

func TestMailboxLiveByIDsCopiesAndOmitsExpiredRecords(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: time.Hour})
	acceptedAt := time.UnixMilli(1000)
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111112", "a")
	rec.Envelope = []byte("opaque-ciphertext")
	if _, err := s.Put(rec, acceptedAt); err != nil {
		t.Fatalf("put: %v", err)
	}

	live, err := s.LiveByIDs("dev-b", []string{rec.MsgID}, acceptedAt.Add(time.Minute))
	if err != nil || len(live) != 1 {
		t.Fatalf("live lookup = %#v, %v; want one record", live, err)
	}
	live[0].Envelope[0] = 'X'
	again, err := s.LiveByIDs("dev-b", []string{rec.MsgID}, acceptedAt.Add(time.Minute))
	if err != nil || len(again) != 1 || string(again[0].Envelope) != "opaque-ciphertext" {
		t.Fatalf("copied live lookup = %#v, %v", again, err)
	}
	expired, err := s.LiveByIDs("dev-b", []string{rec.MsgID}, acceptedAt.Add(2*time.Hour))
	if err != nil || len(expired) != 0 {
		t.Fatalf("expired live lookup = %#v, %v; want empty", expired, err)
	}
}

type atomicMailboxTransfer interface {
	TransferLiveByIDs(string, []string, time.Time, func([]MailboxRecord) error) error
}

func TestMailboxTransferLiveByIDsLinearizesAgainstTerminalMutations(t *testing.T) {
	mutations := []struct {
		name string
		run  func(*MailboxStore, MailboxRecord, time.Time) error
	}{
		{name: "ack", run: func(s *MailboxStore, rec MailboxRecord, now time.Time) error {
			return s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, now)
		}},
		{name: "expiry", run: func(s *MailboxStore, _ MailboxRecord, now time.Time) error {
			_, err := s.Expire(now.Add(2 * time.Hour))
			return err
		}},
		{name: "purge", run: func(s *MailboxStore, rec MailboxRecord, _ time.Time) error {
			return s.PurgePair(rec.SenderDevice, rec.RecipientDevice)
		}},
	}

	for mutationIndex, mutation := range mutations {
		t.Run(mutation.name+"_wins", func(t *testing.T) {
			s, rec, now := newTransferLinearizationStore(t, mutationIndex)
			transfer, ok := any(s).(atomicMailboxTransfer)
			if !ok {
				t.Fatal("MailboxStore lacks atomic live-transfer API")
			}
			if err := mutation.run(s, rec, now); err != nil {
				t.Fatalf("commit %s: %v", mutation.name, err)
			}
			transferred := 0
			if err := transfer.TransferLiveByIDs(rec.RecipientDevice, []string{rec.MsgID}, now, func(records []MailboxRecord) error {
				transferred += len(records)
				return nil
			}); err != nil {
				t.Fatalf("transfer after %s: %v", mutation.name, err)
			}
			if transferred != 0 {
				t.Fatalf("%s-winning transfer emitted %d records, want 0", mutation.name, transferred)
			}
		})

		t.Run("transfer_wins_before_"+mutation.name, func(t *testing.T) {
			s, rec, now := newTransferLinearizationStore(t, mutationIndex+10)
			transfer, ok := any(s).(atomicMailboxTransfer)
			if !ok {
				t.Fatal("MailboxStore lacks atomic live-transfer API")
			}
			entered := make(chan struct{})
			release := make(chan struct{})
			transferDone := make(chan error, 1)
			transferred := 0
			go func() {
				transferDone <- transfer.TransferLiveByIDs(rec.RecipientDevice, []string{rec.MsgID}, now, func(records []MailboxRecord) error {
					transferred = len(records)
					close(entered)
					<-release
					return nil
				})
			}()
			<-entered
			mutationDone := make(chan error, 1)
			go func() { mutationDone <- mutation.run(s, rec, now) }()
			select {
			case err := <-mutationDone:
				t.Fatalf("%s committed before active read transfer returned: %v", mutation.name, err)
			case <-time.After(50 * time.Millisecond):
			}
			close(release)
			if err := <-transferDone; err != nil {
				t.Fatalf("transfer before %s: %v", mutation.name, err)
			}
			if err := <-mutationDone; err != nil {
				t.Fatalf("commit %s after transfer: %v", mutation.name, err)
			}
			if transferred != 1 {
				t.Fatalf("transfer-winning %s race emitted %d records, want 1", mutation.name, transferred)
			}
		})
	}
}

func newTransferLinearizationStore(t *testing.T, suffix int) (*MailboxStore, MailboxRecord, time.Time) {
	t.Helper()
	s := NewMailboxStore(openTestBolt(t), MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: time.Hour})
	now := time.UnixMilli(10_000)
	rec := testMailboxRecord(fmt.Sprintf("11eeeeee-eeee-4eee-8eee-%012d", suffix), "a")
	if _, err := s.Put(rec, now); err != nil {
		t.Fatalf("put transfer record: %v", err)
	}
	return s, rec, now.Add(time.Minute)
}

func TestMailboxRejectsCapacityWithoutEviction(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 1, MaxBytes: 1024, Retention: 24 * time.Hour})
	if _, err := s.Put(testMailboxRecord("11111111-1111-4111-8111-111111111111", "a"), time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(testMailboxRecord("22222222-2222-4222-8222-222222222222", "b"), time.UnixMilli(2000)); !errors.Is(err, ErrMailboxFull) {
		t.Fatalf("err = %v", err)
	}
	got, err := s.Pending("dev-b", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].MsgID != "11111111-1111-4111-8111-111111111111" {
		t.Fatalf("mailbox was evicted: %#v", got)
	}
}

func TestConcurrentMailboxPutsSerializeCapacityAdmissionThroughCommit(t *testing.T) {
	b := openTestBolt(t)
	pairs := NewPairStore(b)
	for _, pair := range []ConfirmedPair{
		{PairID: "capacity-pair-1", DeviceA: "capacity-sender-1", DeviceB: "capacity-recipient-1"},
		{PairID: "capacity-pair-2", DeviceA: "capacity-sender-2", DeviceB: "capacity-recipient-2"},
	} {
		if err := pairs.Confirm(pair); err != nil {
			t.Fatalf("confirm %s: %v", pair.PairID, err)
		}
	}

	mailbox := NewMailboxStore(b, DefaultMailboxLimits())
	records := []struct {
		pairID string
		record MailboxRecord
	}{
		{pairID: "capacity-pair-1", record: MailboxRecord{
			RecipientDevice: "capacity-recipient-1", SenderDevice: "capacity-sender-1",
			MsgID: "11111111-1111-4111-8111-111111111111", EnvelopeSHA256: strings.Repeat("a", 64),
			Envelope: bytes.Repeat([]byte("a"), 4096),
		}},
		{pairID: "capacity-pair-2", record: MailboxRecord{
			RecipientDevice: "capacity-recipient-2", SenderDevice: "capacity-sender-2",
			MsgID: "22222222-2222-4222-8222-222222222222", EnvelopeSHA256: strings.Repeat("b", 64),
			Envelope: bytes.Repeat([]byte("b"), 4096),
		}},
	}

	capacityErr := errors.New("capacity unavailable")
	firstAdmission := make(chan struct{})
	releaseFirstAdmission := make(chan struct{})
	secondAttempted := make(chan struct{})
	secondAdmission := make(chan struct{})
	var admissionCalls atomic.Int32
	admit := func(requiredBytes uint64) error {
		if requiredBytes == 0 {
			return errors.New("capacity reservation is empty")
		}
		switch admissionCalls.Add(1) {
		case 1:
			close(firstAdmission)
			<-releaseFirstAdmission
			return nil
		case 2:
			close(secondAdmission)
			return capacityErr
		default:
			return errors.New("unexpected capacity admission")
		}
	}

	results := make(chan error, len(records))
	go func() {
		_, err := mailbox.PutForPairWithAdmission(records[0].pairID, records[0].record, time.UnixMilli(1000), admit)
		results <- err
	}()
	select {
	case <-firstAdmission:
	case <-time.After(time.Second):
		t.Fatal("first capacity admission did not start")
	}
	go func() {
		close(secondAttempted)
		_, err := mailbox.PutForPairWithAdmission(records[1].pairID, records[1].record, time.UnixMilli(1000), admit)
		results <- err
	}()
	<-secondAttempted
	select {
	case <-secondAdmission:
		t.Fatal("second capacity admission ran before the first Bolt commit was released")
	case <-time.After(250 * time.Millisecond):
	}
	close(releaseFirstAdmission)

	accepted := 0
	rejected := 0
	for range records {
		switch err := <-results; {
		case err == nil:
			accepted++
		case errors.Is(err, capacityErr):
			rejected++
		default:
			t.Fatalf("put error = %v", err)
		}
	}
	if accepted != 1 || rejected != 1 {
		t.Fatalf("accepted/rejected = %d/%d, want 1/1", accepted, rejected)
	}
	persisted := 0
	for _, candidate := range records {
		pending, err := mailbox.PendingForPair(candidate.pairID, candidate.record.RecipientDevice, 10)
		if err != nil {
			t.Fatalf("pending %s: %v", candidate.record.RecipientDevice, err)
		}
		if len(pending) > 1 {
			t.Fatalf("recipient %s persisted %d records", candidate.record.RecipientDevice, len(pending))
		}
		persisted += len(pending)
	}
	if persisted != 1 {
		t.Fatalf("persisted records = %d, want exactly 1", persisted)
	}
}

func TestMailboxCapacityReservationIncludesLegacySequenceMigration(t *testing.T) {
	b := openTestBolt(t)
	mailbox := NewMailboxStore(b, MailboxLimits{MaxItems: 4, MaxBytes: 1 << 20, Retention: time.Hour})
	legacyRecords := []MailboxRecord{
		testMailboxRecord("33333333-3333-4333-8333-333333333333", "a"),
		testMailboxRecord("44444444-4444-4444-8444-444444444444", "b"),
	}
	var legacyEnvelopeBytes uint64
	for index := range legacyRecords {
		legacyRecords[index].Envelope = bytes.Repeat([]byte{byte('a' + index)}, 4096)
		legacyRecords[index].AcceptedAt = int64(index + 1)
		legacyRecords[index].ExpiresAt = int64(index+1) + int64(time.Hour/time.Millisecond)
		legacyRecords[index].ByteSize = uint64(len(legacyRecords[index].Envelope))
		legacyEnvelopeBytes += legacyRecords[index].ByteSize
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		items, order, stats, _, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		for _, record := range legacyRecords {
			encoded, err := json.Marshal(record)
			if err != nil {
				return err
			}
			if err := items.Put(itemKey(record.RecipientDevice, record.MsgID), encoded); err != nil {
				return err
			}
			if err := order.Put(orderKey(record.RecipientDevice, uint64(record.AcceptedAt), record.MsgID), nil); err != nil {
				return err
			}
		}
		return stats.Put([]byte(legacyRecords[0].RecipientDevice), encodeMailboxStats(uint64(len(legacyRecords)), legacyEnvelopeBytes))
	}); err != nil {
		t.Fatal(err)
	}
	before := snapshotMailboxBuckets(t, b)

	newRecord := testMailboxRecord("55555555-5555-4555-8555-555555555555", "c")
	capacityErr := errors.New("capacity unavailable")
	var requiredBytes uint64
	_, err := mailbox.putForPair("", newRecord, time.UnixMilli(3000), func(required uint64) error {
		requiredBytes = required
		return capacityErr
	})
	if !errors.Is(err, capacityErr) {
		t.Fatalf("put error = %v, want capacity unavailable", err)
	}
	minimumReservation := mailboxBoltGrowthAllowance + legacyEnvelopeBytes + uint64(len(newRecord.Envelope))
	if requiredBytes < minimumReservation {
		t.Fatalf("capacity reservation = %d, want at least %d including legacy rewrites", requiredBytes, minimumReservation)
	}
	if after := snapshotMailboxBuckets(t, b); !reflect.DeepEqual(after, before) {
		t.Fatalf("capacity-rejected legacy migration mutated mailbox\nbefore=%#v\nafter=%#v", before, after)
	}
}

func TestMailboxRejectsDigestConflict(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	rec.EnvelopeSHA256 = strings.Repeat("b", 64)
	if _, err := s.Put(rec, time.UnixMilli(1001)); !errors.Is(err, ErrMessageIDConflict) {
		t.Fatalf("err = %v", err)
	}
}

func TestMailboxPendingUsesDurableSequenceWhenAcceptanceMillisCollide(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mailbox.db")
	b, err := OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	s := NewMailboxStore(b, DefaultMailboxLimits())
	now := time.UnixMilli(1000)
	first := testMailboxRecord("f1111111-1111-4111-8111-111111111111", "a")
	second := testMailboxRecord("11111111-1111-4111-8111-111111111111", "b")
	if _, err := s.Put(first, now); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(second, now); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}

	b, err = OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	s = NewMailboxStore(b, DefaultMailboxLimits())
	got, err := s.Pending(first.RecipientDevice, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].MsgID != first.MsgID || got[1].MsgID != second.MsgID {
		t.Fatalf("same-millisecond pending order = %#v, want %s then %s", got, first.MsgID, second.MsgID)
	}
	if got[0].AcceptanceSequence != 1 || got[1].AcceptanceSequence != 2 {
		t.Fatalf("acceptance sequences = %d, %d, want 1, 2", got[0].AcceptanceSequence, got[1].AcceptanceSequence)
	}
}

func TestMailboxSequenceMigratesLegacyOrderAndResetsOnPurge(t *testing.T) {
	b := openTestBolt(t)
	legacyFirst := testMailboxRecord("f2222222-2222-4222-8222-222222222222", "a")
	legacyFirst.AcceptedAt = 1000
	legacyFirst.ExpiresAt = 1000 + int64(time.Hour/time.Millisecond)
	legacyFirst.ByteSize = uint64(len(legacyFirst.Envelope))
	legacySecond := testMailboxRecord("12222222-2222-4222-8222-222222222222", "b")
	legacySecond.AcceptedAt = 2000
	legacySecond.ExpiresAt = 2000 + int64(time.Hour/time.Millisecond)
	legacySecond.ByteSize = uint64(len(legacySecond.Envelope))
	if err := b.Update(func(tx *bbolt.Tx) error {
		items, order, stats, _, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		for _, rec := range []MailboxRecord{legacyFirst, legacySecond} {
			raw, err := json.Marshal(rec)
			if err != nil {
				return err
			}
			if err := items.Put(itemKey(rec.RecipientDevice, rec.MsgID), raw); err != nil {
				return err
			}
			if err := order.Put(orderKey(rec.RecipientDevice, uint64(rec.AcceptedAt), rec.MsgID), nil); err != nil {
				return err
			}
		}
		return stats.Put([]byte(legacyFirst.RecipientDevice), encodeMailboxStats(2, legacyFirst.ByteSize+legacySecond.ByteSize))
	}); err != nil {
		t.Fatal(err)
	}

	s := NewMailboxStore(b, MailboxLimits{MaxItems: 4, MaxBytes: 4096, Retention: time.Hour})
	newRec := testMailboxRecord("02222222-2222-4222-8222-222222222222", "c")
	if _, err := s.Put(newRec, time.UnixMilli(3000)); err != nil {
		t.Fatal(err)
	}
	got, err := s.Pending(legacyFirst.RecipientDevice, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 || got[0].MsgID != legacyFirst.MsgID || got[1].MsgID != legacySecond.MsgID || got[2].MsgID != newRec.MsgID {
		t.Fatalf("migrated pending order = %#v", got)
	}
	if got[0].AcceptanceSequence != 1 || got[1].AcceptanceSequence != 2 || got[2].AcceptanceSequence != 3 {
		t.Fatalf("migrated sequences = %d, %d, %d", got[0].AcceptanceSequence, got[1].AcceptanceSequence, got[2].AcceptanceSequence)
	}

	if err := s.PurgePair(legacyFirst.SenderDevice, legacyFirst.RecipientDevice); err != nil {
		t.Fatal(err)
	}
	afterPurge := testMailboxRecord("32222222-2222-4222-8222-222222222222", "d")
	if _, err := s.Put(afterPurge, time.UnixMilli(4000)); err != nil {
		t.Fatal(err)
	}
	pending, err := s.Pending(afterPurge.RecipientDevice, 10)
	if err != nil || len(pending) != 1 || pending[0].AcceptanceSequence != 1 {
		t.Fatalf("post-purge sequence = %#v, %v, want reset to 1", pending, err)
	}
}

func TestMailboxRejectsInvalidMessageIDsBeforeMutation(t *testing.T) {
	cases := []struct {
		name  string
		msgID string
	}{
		{name: "empty", msgID: ""},
		{name: "plain text", msgID: "not-a-uuid"},
		{name: "invalid separators", msgID: "11111111_1111-4111-8111-111111111111"},
		{name: "invalid hex", msgID: "zzzzzzzz-1111-4111-8111-111111111111"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := openTestBolt(t)
			s := NewMailboxStore(b, DefaultMailboxLimits())
			valid := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
			if _, err := s.Put(valid, time.UnixMilli(1000)); err != nil {
				t.Fatal(err)
			}
			before := snapshotMailboxBuckets(t, b)

			if _, err := s.Put(testMailboxRecord(tc.msgID, "b"), time.UnixMilli(1001)); err == nil || !strings.Contains(err.Error(), "invalid message id") {
				t.Fatalf("Put error = %v, want invalid message id", err)
			}
			if got := snapshotMailboxBuckets(t, b); !reflect.DeepEqual(got, before) {
				t.Fatalf("Put mutated mailbox buckets: got %#v, want %#v", got, before)
			}

			if err := s.Ack(valid.RecipientDevice, tc.msgID, valid.EnvelopeSHA256, time.UnixMilli(1002)); err == nil || !strings.Contains(err.Error(), "invalid message id") {
				t.Fatalf("Ack error = %v, want invalid message id", err)
			}
			if got := snapshotMailboxBuckets(t, b); !reflect.DeepEqual(got, before) {
				t.Fatalf("Ack mutated mailbox buckets: got %#v, want %#v", got, before)
			}
		})
	}
}

func TestMailboxAckRequiresRecipientAndDigest(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack("other-device", rec.MsgID, rec.EnvelopeSHA256, time.UnixMilli(1001)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("wrong recipient err = %v", err)
	}
	if err := s.Ack(rec.RecipientDevice, rec.MsgID, strings.Repeat("b", 64), time.UnixMilli(1001)); !errors.Is(err, ErrDigestMismatch) {
		t.Fatalf("wrong digest err = %v", err)
	}
	got, err := s.Pending(rec.RecipientDevice, 10)
	if err != nil || len(got) != 1 {
		t.Fatalf("message removed after rejected ack: %#v, %v", got, err)
	}
}

func TestMailboxAckDeletesCiphertextAndRetainsStatus(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(rec.RecipientDevice, rec.MsgID, strings.ToUpper(rec.EnvelopeSHA256), time.UnixMilli(2000)); err != nil {
		t.Fatal(err)
	}
	pending, err := s.Pending(rec.RecipientDevice, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("ciphertext still pending: %#v", pending)
	}
	statuses, err := s.Statuses(rec.SenderDevice, time.UnixMilli(0))
	if err != nil {
		t.Fatal(err)
	}
	if len(statuses) != 1 || statuses[0].Status != "acknowledged" || statuses[0].MsgID != rec.MsgID {
		t.Fatalf("statuses = %#v", statuses)
	}
}

func TestMailboxDuplicateAckRequiresTerminalDigest(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	rec := testMailboxRecord("11222222-2222-4222-8222-222222222222", "a")
	if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, time.UnixMilli(2000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(rec.RecipientDevice, rec.MsgID, strings.Repeat("b", 64), time.UnixMilli(3000)); !errors.Is(err, ErrDigestMismatch) {
		t.Fatalf("duplicate ACK with wrong digest err = %v, want digest mismatch", err)
	}
	if err := s.Ack(rec.RecipientDevice, rec.MsgID, strings.ToUpper(rec.EnvelopeSHA256), time.UnixMilli(3000)); err != nil {
		t.Fatalf("duplicate ACK with original digest: %v", err)
	}
}

func TestMailboxTerminalLookupIgnoresUnrelatedCorruptStatuses(t *testing.T) {
	t.Run("duplicate ack", func(t *testing.T) {
		b := openTestBolt(t)
		s := NewMailboxStore(b, DefaultMailboxLimits())
		rec := testMailboxRecord("11222222-2222-4222-8222-222222222223", "a")
		acceptedAt := time.UnixMilli(1000)
		ackedAt := time.UnixMilli(2000)
		if _, err := s.Put(rec, acceptedAt); err != nil {
			t.Fatal(err)
		}
		if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, ackedAt); err != nil {
			t.Fatal(err)
		}
		putCorruptUnrelatedStatus(t, b)
		if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, ackedAt.Add(time.Second)); err != nil {
			t.Fatalf("indexed duplicate ack consulted unrelated status: %v", err)
		}
		if err := s.Ack(rec.RecipientDevice, rec.MsgID, strings.Repeat("b", 64), ackedAt.Add(time.Second)); !errors.Is(err, ErrDigestMismatch) {
			t.Fatalf("indexed duplicate ack wrong digest = %v", err)
		}
	})

	t.Run("terminal put", func(t *testing.T) {
		b := openTestBolt(t)
		s := NewMailboxStore(b, DefaultMailboxLimits())
		rec := testMailboxRecord("11222222-2222-4222-8222-222222222224", "a")
		acceptedAt := time.UnixMilli(3000)
		if _, err := s.Put(rec, acceptedAt); err != nil {
			t.Fatal(err)
		}
		if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, time.UnixMilli(4000)); err != nil {
			t.Fatal(err)
		}
		putCorruptUnrelatedStatus(t, b)
		result, err := s.Put(rec, time.UnixMilli(5000))
		if err != nil {
			t.Fatalf("indexed terminal put consulted unrelated status: %v", err)
		}
		if !result.Duplicate || !result.Terminal || result.AcceptedAt != acceptedAt.UnixMilli() {
			t.Fatalf("terminal duplicate = %#v, want original acceptance identity", result)
		}
		conflict := rec
		conflict.EnvelopeSHA256 = strings.Repeat("b", 64)
		if _, err := s.Put(conflict, time.UnixMilli(5001)); !errors.Is(err, ErrMessageIDConflict) {
			t.Fatalf("indexed terminal digest conflict = %v", err)
		}
		pending, err := s.Pending(rec.RecipientDevice, 1)
		if err != nil || len(pending) != 0 {
			t.Fatalf("terminal duplicate resurrected ciphertext: %#v, %v", pending, err)
		}
	})
}

func TestMailboxStatusIndexesMigrateLegacyTombstonesAcrossReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mailbox.db")
	b, err := OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	acked := DeliveryStatus{
		SenderDevice: "dev-a", RecipientDevice: "dev-b",
		MsgID: "11444444-4444-4444-8444-444444444441", Status: "acknowledged",
		OccurredAt: 2000, ExpiresAt: time.UnixMilli(2000).Add(statusRetention).UnixMilli(),
		EnvelopeSHA256: strings.Repeat("a", 64), AcceptedAt: 1000, MailboxExpiresAt: 3601000,
	}
	expired := DeliveryStatus{
		SenderDevice: "dev-a", RecipientDevice: "dev-b",
		MsgID: "11444444-4444-4444-8444-444444444442", Status: "expired",
		OccurredAt: 3000, ExpiresAt: time.UnixMilli(3000).Add(statusRetention).UnixMilli(),
		EnvelopeSHA256: strings.Repeat("b", 64), AcceptedAt: 1100, MailboxExpiresAt: 3601100,
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		statuses, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxStatus))
		if err != nil {
			return err
		}
		for _, status := range []DeliveryStatus{acked, expired} {
			raw, err := json.Marshal(status)
			if err != nil {
				return err
			}
			if err := statuses.Put(statusKey(status.SenderDevice, status.MsgID), raw); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	b, err = OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	s := NewMailboxStore(b, DefaultMailboxLimits())
	if _, err := s.Expire(time.UnixMilli(4000)); err != nil {
		t.Fatalf("migrate legacy indexes: %v", err)
	}

	page, err := s.ExpiryStatuses(expired.SenderDevice, expired.RecipientDevice, 1, time.UnixMilli(4000))
	if err != nil || len(page) != 1 || page[0] != expired {
		t.Fatalf("migrated expiry page = %#v, %v", page, err)
	}
	rec := testMailboxRecord(acked.MsgID, "a")
	result, err := s.Put(rec, time.UnixMilli(5000))
	if err != nil {
		t.Fatalf("reopened indexed terminal put: %v", err)
	}
	if !result.Duplicate || !result.Terminal || result.AcceptedAt != acked.AcceptedAt {
		t.Fatalf("reopened terminal duplicate = %#v", result)
	}

	if err := s.AdvanceExpiryStatusCursor(expired.SenderDevice, expired.RecipientDevice, expired.MsgID); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	b, err = OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	s = NewMailboxStore(b, DefaultMailboxLimits())
	if page, err := s.ExpiryStatuses(expired.SenderDevice, expired.RecipientDevice, 1, time.UnixMilli(4000)); err != nil || len(page) != 1 || page[0] != expired {
		t.Fatalf("rotated expiry after reopen = %#v, %v; want retry", page, err)
	}
	if entries := mailboxBucketEntryCount(t, b, bucketMailboxExpiryCursor); entries != 1 {
		t.Fatalf("persistent pair cursor entries = %d, want 1", entries)
	}
	raw, err := b.Get(bucketMailboxStatus, string(statusKey(expired.SenderDevice, expired.MsgID)))
	if err != nil || raw == nil {
		t.Fatalf("rotation deleted canonical 24h tombstone: %q, %v", raw, err)
	}
	if err := s.ExpireStatuses(time.UnixMilli(expired.ExpiresAt)); err != nil {
		t.Fatal(err)
	}
	if page, err := s.ExpiryStatuses(expired.SenderDevice, expired.RecipientDevice, 1, time.UnixMilli(expired.ExpiresAt)); err != nil || len(page) != 0 {
		t.Fatalf("physically expired status was retried: %#v, %v", page, err)
	}
	if entries := mailboxBucketEntryCount(t, b, bucketMailboxExpiryCursor); entries != 0 {
		t.Fatalf("physical expiry left %d pair cursors", entries)
	}
}

func TestMailboxStatusIndexUpgradeRestoresRetiredExpiryRetry(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	status := DeliveryStatus{
		SenderDevice: "dev-a", RecipientDevice: "dev-b",
		MsgID: "11555555-5555-4555-8555-555555555555", Status: "expired",
		OccurredAt: 3000, ExpiresAt: time.UnixMilli(3000).Add(statusRetention).UnixMilli(),
		EnvelopeSHA256: strings.Repeat("c", 64), AcceptedAt: 1000, MailboxExpiresAt: 2000,
	}
	raw, err := json.Marshal(status)
	if err != nil {
		t.Fatal(err)
	}
	if err := b.Update(func(tx *bbolt.Tx) error {
		statuses, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxStatus))
		if err != nil {
			return err
		}
		if err := statuses.Put(statusKey(status.SenderDevice, status.MsgID), raw); err != nil {
			return err
		}
		if _, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxExpiryPending)); err != nil {
			return err
		}
		meta, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxMeta))
		if err != nil {
			return err
		}
		return meta.Put([]byte("status_indexes_v1"), []byte{1})
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Expire(time.UnixMilli(4000)); err != nil {
		t.Fatal(err)
	}
	page, err := s.ExpiryStatuses(status.SenderDevice, status.RecipientDevice, 1, time.UnixMilli(4000))
	if err != nil || len(page) != 1 || page[0] != status {
		t.Fatalf("v1-retired expiry after index upgrade = %#v, %v; want retry", page, err)
	}
}

func TestMailboxExpiryStatusCursorIsPairScoped(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 10, MaxBytes: 1 << 20, Retention: time.Hour})
	now := time.UnixMilli(10_000_000)
	records := []MailboxRecord{
		testMailboxRecord("11666666-6666-4666-8666-666666666661", "a"),
		testMailboxRecord("11666666-6666-4666-8666-666666666662", "b"),
		testMailboxRecord("11777777-7777-4777-8777-777777777771", "c"),
		testMailboxRecord("11777777-7777-4777-8777-777777777772", "d"),
	}
	for i := 2; i < len(records); i++ {
		records[i].SenderDevice = "dev-c"
		records[i].RecipientDevice = "dev-d"
	}
	for _, rec := range records {
		if _, err := s.Put(rec, now.Add(-2*time.Hour)); err != nil {
			t.Fatal(err)
		}
	}
	if expired, err := s.Expire(now); err != nil || len(expired) != len(records) {
		t.Fatalf("expire pair-scoped statuses = %#v, %v", expired, err)
	}
	pageAB, err := s.ExpiryStatuses("dev-a", "dev-b", 1, now)
	if err != nil || len(pageAB) != 1 || pageAB[0].MsgID != records[0].MsgID {
		t.Fatalf("first AB page = %#v, %v", pageAB, err)
	}
	pageCD, err := s.ExpiryStatuses("dev-c", "dev-d", 1, now)
	if err != nil || len(pageCD) != 1 || pageCD[0].MsgID != records[2].MsgID {
		t.Fatalf("first CD page = %#v, %v", pageCD, err)
	}
	if err := s.AdvanceExpiryStatusCursor("dev-a", "dev-b", pageAB[0].MsgID); err != nil {
		t.Fatal(err)
	}
	churn := testMailboxRecord("11888888-8888-4888-8888-888888888888", "e")
	if _, err := s.Put(churn, now); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(churn.RecipientDevice, churn.MsgID, churn.EnvelopeSHA256, now.Add(time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	pageAB, err = s.ExpiryStatuses("dev-a", "dev-b", 1, now)
	if err != nil || len(pageAB) != 1 || pageAB[0].MsgID != records[1].MsgID {
		t.Fatalf("ACK churn reset rotated AB page = %#v, %v", pageAB, err)
	}
	pageCD, err = s.ExpiryStatuses("dev-c", "dev-d", 1, now)
	if err != nil || len(pageCD) != 1 || pageCD[0].MsgID != records[2].MsgID {
		t.Fatalf("AB cursor changed CD page = %#v, %v", pageCD, err)
	}
	if entries := mailboxBucketEntryCount(t, b, bucketMailboxExpiryCursor); entries != 1 {
		t.Fatalf("bounded cursor entries = %d, want one active pair", entries)
	}
}

func TestMailboxExpiryStatusRotationOmitsTombstoneAtLogicalExpiry(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 1, MaxBytes: 1 << 20, Retention: time.Hour})
	acceptedAt := time.UnixMilli(1000)
	rec := testMailboxRecord("11999999-9999-4999-8999-999999999999", "f")
	if _, err := s.Put(rec, acceptedAt); err != nil {
		t.Fatal(err)
	}
	terminalAt := acceptedAt.Add(time.Hour)
	if expired, err := s.Expire(terminalAt); err != nil || len(expired) != 1 {
		t.Fatalf("expire logical-expiry item = %#v, %v", expired, err)
	}
	page, err := s.ExpiryStatuses(rec.SenderDevice, rec.RecipientDevice, 1, terminalAt.Add(statusRetention))
	if err != nil || len(page) != 0 {
		t.Fatalf("logically expired tombstone rotated = %#v, %v", page, err)
	}
}

func TestMailboxExpiryStatusRotationBoundsStaleIndexProcessing(t *testing.T) {
	const pageSize = 64
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: pageSize + 1, MaxBytes: 1 << 20, Retention: time.Hour})
	now := time.UnixMilli(200_000_000)
	oldAcceptedAt := now.Add(-26 * time.Hour)
	for i := 0; i < pageSize; i++ {
		rec := testMailboxRecord(fmt.Sprintf("12000000-0000-4000-8000-%012x", i), "a")
		if _, err := s.Put(rec, oldAcceptedAt); err != nil {
			t.Fatal(err)
		}
	}
	if expired, err := s.Expire(oldAcceptedAt.Add(time.Hour)); err != nil || len(expired) != pageSize {
		t.Fatalf("expire stale index page = %d, %v", len(expired), err)
	}
	live := testMailboxRecord("12111111-1111-4111-8111-111111111111", "b")
	if _, err := s.Put(live, now.Add(-2*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if expired, err := s.Expire(now); err != nil || len(expired) != 1 {
		t.Fatalf("expire live rotation item = %#v, %v", expired, err)
	}

	first, err := s.ExpiryStatuses(live.SenderDevice, live.RecipientDevice, pageSize, now)
	if err != nil || len(first) != 0 {
		t.Fatalf("first bounded scan crossed stale page: %#v, %v", first, err)
	}
	second, err := s.ExpiryStatuses(live.SenderDevice, live.RecipientDevice, pageSize, now)
	if err != nil || len(second) != 1 || second[0].MsgID != live.MsgID {
		t.Fatalf("live status starved behind stale page: %#v, %v", second, err)
	}
}

func putCorruptUnrelatedStatus(t *testing.T, b *Bolt) {
	t.Helper()
	if err := b.Update(func(tx *bbolt.Tx) error {
		statuses, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxStatus))
		if err != nil {
			return err
		}
		return statuses.Put(statusKey("aaa-unrelated", "00000000-0000-4000-8000-000000000000"), []byte("{"))
	}); err != nil {
		t.Fatalf("insert corrupt unrelated status: %v", err)
	}
}

func TestMailboxTerminalTombstonePreventsResurrection(t *testing.T) {
	tests := []struct {
		name      string
		terminate func(*testing.T, *MailboxStore, MailboxRecord, time.Time)
	}{
		{
			name: "acknowledged",
			terminate: func(t *testing.T, s *MailboxStore, rec MailboxRecord, terminalAt time.Time) {
				t.Helper()
				if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, terminalAt); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name: "expired",
			terminate: func(t *testing.T, s *MailboxStore, _ MailboxRecord, terminalAt time.Time) {
				t.Helper()
				if expired, err := s.Expire(terminalAt); err != nil || len(expired) != 1 {
					t.Fatalf("Expire = %#v, %v", expired, err)
				}
			},
		},
	}

	for i, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			b := openTestBolt(t)
			s := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: time.Hour})
			rec := testMailboxRecord(fmt.Sprintf("11333333-3333-4333-8333-%012d", i+1), "a")
			acceptedAt := time.UnixMilli(1000)
			first, err := s.Put(rec, acceptedAt)
			if err != nil {
				t.Fatal(err)
			}
			terminalAt := acceptedAt.Add(time.Hour)
			tc.terminate(t, s, rec, terminalAt)

			duplicate, err := s.Put(rec, terminalAt.Add(time.Millisecond))
			if err != nil {
				t.Fatalf("same terminal put: %v", err)
			}
			if !duplicate.Duplicate || duplicate.AcceptedAt != first.AcceptedAt {
				t.Fatalf("terminal duplicate = %#v, want original acceptance %#v", duplicate, first)
			}
			if pending, err := s.Pending(rec.RecipientDevice, 10); err != nil || len(pending) != 0 {
				t.Fatalf("terminal duplicate resurrected mailbox: %#v, %v", pending, err)
			}
			wrongOwner := rec
			wrongOwner.SenderDevice = "dev-other"
			if _, err := s.Put(wrongOwner, terminalAt.Add(2*time.Millisecond)); !errors.Is(err, ErrMessageIDConflict) {
				t.Fatalf("terminal wrong-owner put err = %v, want id conflict", err)
			}

			conflict := rec
			conflict.EnvelopeSHA256 = strings.Repeat("b", 64)
			if _, err := s.Put(conflict, terminalAt.Add(3*time.Millisecond)); !errors.Is(err, ErrMessageIDConflict) {
				t.Fatalf("terminal conflicting put err = %v, want id conflict", err)
			}
		})
	}
}

func TestMailboxExpireUsesAcceptedAtAndReportsSender(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: time.Hour})
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	rec.ExpiresAt = time.UnixMilli(1).UnixMilli() // caller values must not affect retention.
	accepted := time.UnixMilli(1000)
	if _, err := s.Put(rec, accepted); err != nil {
		t.Fatal(err)
	}
	expired, err := s.Expire(accepted.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if len(expired) != 1 || expired[0].SenderDevice != rec.SenderDevice || expired[0].MsgID != rec.MsgID || expired[0].ExpiredAt != accepted.Add(time.Hour).UnixMilli() {
		t.Fatalf("expired = %#v", expired)
	}
	pending, err := s.Pending(rec.RecipientDevice, 10)
	if err != nil || len(pending) != 0 {
		t.Fatalf("expired message still pending: %#v, %v", pending, err)
	}
	statuses, err := s.Statuses(rec.SenderDevice, time.UnixMilli(0))
	if err != nil || len(statuses) != 1 || statuses[0].Status != "expired" {
		t.Fatalf("statuses = %#v, %v", statuses, err)
	}
}

func TestMailboxPurgePairDeletesBothDirections(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 1, MaxBytes: 1024, Retention: time.Hour})
	forward := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	if _, err := s.Put(forward, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(forward.RecipientDevice, forward.MsgID, forward.EnvelopeSHA256, time.UnixMilli(1001)); err != nil {
		t.Fatal(err)
	}
	reverse := testMailboxRecord("22222222-2222-4222-8222-222222222222", "b")
	reverse.RecipientDevice, reverse.SenderDevice = "dev-a", "dev-b"
	if _, err := s.Put(reverse, time.UnixMilli(1002)); err != nil {
		t.Fatal(err)
	}
	if expired, err := s.Expire(time.UnixMilli(1002).Add(time.Hour)); err != nil || len(expired) != 1 {
		t.Fatalf("expire reverse before purge = %#v, %v", expired, err)
	}
	page, err := s.ExpiryStatuses(reverse.SenderDevice, reverse.RecipientDevice, 1, time.UnixMilli(1002).Add(time.Hour))
	if err != nil || len(page) != 1 {
		t.Fatalf("expiry page before purge = %#v, %v", page, err)
	}
	if err := s.AdvanceExpiryStatusCursor(reverse.SenderDevice, reverse.RecipientDevice, reverse.MsgID); err != nil {
		t.Fatal(err)
	}
	if err := s.PurgePair("dev-a", "dev-b"); err != nil {
		t.Fatal(err)
	}
	for _, recipient := range []string{"dev-a", "dev-b"} {
		pending, err := s.Pending(recipient, 10)
		if err != nil || len(pending) != 0 {
			t.Fatalf("pending for %s = %#v, %v", recipient, pending, err)
		}
	}
	for _, sender := range []string{"dev-a", "dev-b"} {
		statuses, err := s.Statuses(sender, time.UnixMilli(0))
		if err != nil || len(statuses) != 0 {
			t.Fatalf("statuses for %s = %#v, %v", sender, statuses, err)
		}
	}
	for _, bucketName := range []string{bucketMailboxStatusByRecipient, bucketMailboxExpiryPending, bucketMailboxExpiryCursor} {
		if entries := mailboxBucketEntryCount(t, b, bucketName); entries != 0 {
			t.Fatalf("purge left %d entries in %s", entries, bucketName)
		}
	}
	if _, err := s.Put(testMailboxRecord("33333333-3333-4333-8333-333333333333", "c"), time.UnixMilli(1003)); err != nil {
		t.Fatalf("quota statistics were not cleared: %v", err)
	}
}

func TestMailboxQuotaCountersRemainCorrectAfterAckAndExpire(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 1, MaxBytes: 1024, Retention: time.Hour})
	first := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	if _, err := s.Put(first, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(first.RecipientDevice, first.MsgID, first.EnvelopeSHA256, time.UnixMilli(1001)); err != nil {
		t.Fatal(err)
	}
	second := testMailboxRecord("22222222-2222-4222-8222-222222222222", "b")
	if _, err := s.Put(second, time.UnixMilli(1002)); err != nil {
		t.Fatalf("quota did not recover after ack: %v", err)
	}
	if _, err := s.Expire(time.UnixMilli(1002).Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	third := testMailboxRecord("33333333-3333-4333-8333-333333333333", "c")
	if _, err := s.Put(third, time.UnixMilli(1003).Add(time.Hour)); err != nil {
		t.Fatalf("quota did not recover after expiry: %v", err)
	}
}

func TestMailboxExpireStatusesRemovesOnlyExpiredTombstones(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	ackedAt := time.UnixMilli(2000)
	if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, ackedAt); err != nil {
		t.Fatal(err)
	}
	if err := s.ExpireStatuses(ackedAt.Add(statusRetention - time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if got, err := s.Statuses(rec.SenderDevice, time.UnixMilli(0)); err != nil || len(got) != 1 {
		t.Fatalf("status removed early: %#v, %v", got, err)
	}
	if err := s.ExpireStatuses(ackedAt.Add(statusRetention)); err != nil {
		t.Fatal(err)
	}
	if got, err := s.Statuses(rec.SenderDevice, time.UnixMilli(0)); err != nil || len(got) != 0 {
		t.Fatalf("status not removed at expiry: %#v, %v", got, err)
	}
	for _, bucketName := range []string{bucketMailboxStatusByRecipient, bucketMailboxExpiryPending} {
		if entries := mailboxBucketEntryCount(t, b, bucketName); entries != 0 {
			t.Fatalf("status expiry left %d entries in %s", entries, bucketName)
		}
	}
}

func TestMailboxExpiryMaintenanceUsesDedicatedBoundedIndexes(t *testing.T) {
	b := openTestBolt(t)
	limits := DefaultMailboxLimits()
	limits.Retention = time.Hour
	s := NewMailboxStore(b, limits)
	now := time.UnixMilli(10_000)
	records := []MailboxRecord{
		testMailboxRecord("11111111-1111-4111-8111-111111111111", "1"),
		testMailboxRecord("22222222-2222-4222-8222-222222222222", "2"),
		testMailboxRecord("33333333-3333-4333-8333-333333333333", "3"),
	}
	for _, rec := range records {
		if _, err := s.Put(rec, now); err != nil {
			t.Fatal(err)
		}
	}
	if got := mailboxBucketEntryCount(t, b, bucketMailboxItemExpiry); got != 3 {
		t.Fatalf("live expiry index entries = %d, want 3", got)
	}
	expired, err := s.ExpireBatch(now.Add(time.Hour), 2)
	if err != nil || len(expired) != 2 {
		t.Fatalf("first live expiry batch = %#v, %v; want 2", expired, err)
	}
	if got := mailboxBucketEntryCount(t, b, bucketMailboxItemExpiry); got != 1 {
		t.Fatalf("live expiry index after batch = %d, want 1", got)
	}
	if got := mailboxBucketEntryCount(t, b, bucketMailboxStatusExpiry); got != 2 {
		t.Fatalf("status expiry index after live expiry = %d, want 2", got)
	}
	if err := s.Ack(records[2].RecipientDevice, records[2].MsgID, records[2].EnvelopeSHA256, now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if got := mailboxBucketEntryCount(t, b, bucketMailboxItemExpiry); got != 0 {
		t.Fatalf("ACK left live expiry index entries = %d", got)
	}
	if got := mailboxBucketEntryCount(t, b, bucketMailboxStatusExpiry); got != 3 {
		t.Fatalf("status expiry index after ACK = %d, want 3", got)
	}
	removed, err := s.ExpireStatusesBatch(now.Add(25*time.Hour), 2)
	if err != nil || removed != 2 {
		t.Fatalf("first status expiry batch = %d, %v; want 2", removed, err)
	}
	if got := mailboxBucketEntryCount(t, b, bucketMailboxStatusExpiry); got != 1 {
		t.Fatalf("status expiry index after batch = %d, want 1", got)
	}
	assertMaintenanceExpiryIndexesExact(t, b)
}

func TestMailboxMaintenanceExpiryIndexesSurviveMigrationReopenRollbackAndPurge(t *testing.T) {
	path := filepath.Join(t.TempDir(), "maintenance-index.db")
	b, err := OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	limits := DefaultMailboxLimits()
	s, err := OpenMailboxStore(b, limits)
	if err != nil {
		t.Fatal(err)
	}
	now := time.UnixMilli(1000)
	acked := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	live := testMailboxRecord("22222222-2222-4222-8222-222222222222", "b")
	for _, rec := range []MailboxRecord{acked, live} {
		if _, err := s.Put(rec, now); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Ack(acked.RecipientDevice, acked.MsgID, acked.EnvelopeSHA256, now.Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	assertMaintenanceExpiryIndexesExact(t, b)

	before := snapshotMailboxBuckets(t, b)
	if err := s.Ack(live.RecipientDevice, live.MsgID, strings.Repeat("f", 64), now.Add(2*time.Second)); !errors.Is(err, ErrDigestMismatch) {
		t.Fatalf("conflicting ACK = %v, want ErrDigestMismatch", err)
	}
	if got := snapshotMailboxBuckets(t, b); !reflect.DeepEqual(got, before) {
		t.Fatal("failed ACK changed mailbox or expiry indexes")
	}

	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	b, err = OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = b.Close() })
	s, err = OpenMailboxStore(b, limits)
	if err != nil {
		t.Fatal(err)
	}
	assertMaintenanceExpiryIndexesExact(t, b)

	if err := b.Update(func(tx *bbolt.Tx) error {
		if err := tx.DeleteBucket([]byte(bucketMailboxItemExpiry)); err != nil {
			return err
		}
		if err := tx.DeleteBucket([]byte(bucketMailboxStatusExpiry)); err != nil {
			return err
		}
		return tx.Bucket([]byte(bucketMailboxMeta)).Delete(maintenanceExpiryIndexesVersionKey)
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenMailboxStore(b, limits); err != nil {
		t.Fatalf("legacy expiry-index migration: %v", err)
	}
	assertMaintenanceExpiryIndexesExact(t, b)
	if err := s.PurgePair("dev-a", "dev-b"); err != nil {
		t.Fatal(err)
	}
	assertMaintenanceExpiryIndexesExact(t, b)
}

func TestMailboxStoreMarkerPresentExpiryIndexCorruptionFailsOpen(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*testing.T, *Bolt)
	}{
		{
			name: "missing live index bucket",
			mutate: func(t *testing.T, b *Bolt) {
				t.Helper()
				if err := b.Update(func(tx *bbolt.Tx) error { return tx.DeleteBucket([]byte(bucketMailboxItemExpiry)) }); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name: "missing status index bucket",
			mutate: func(t *testing.T, b *Bolt) {
				t.Helper()
				if err := b.Update(func(tx *bbolt.Tx) error { return tx.DeleteBucket([]byte(bucketMailboxStatusExpiry)) }); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name: "missing reciprocal live entry",
			mutate: func(t *testing.T, b *Bolt) {
				t.Helper()
				if err := b.Update(func(tx *bbolt.Tx) error {
					bucket := tx.Bucket([]byte(bucketMailboxItemExpiry))
					key, _ := bucket.Cursor().First()
					return bucket.Delete(key)
				}); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name: "orphan status entry",
			mutate: func(t *testing.T, b *Bolt) {
				t.Helper()
				if err := b.Update(func(tx *bbolt.Tx) error {
					return tx.Bucket([]byte(bucketMailboxStatusExpiry)).Put(maintenanceExpiryKey(1, []byte("orphan")), []byte("orphan"))
				}); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name: "malformed status entry",
			mutate: func(t *testing.T, b *Bolt) {
				t.Helper()
				if err := b.Update(func(tx *bbolt.Tx) error {
					return tx.Bucket([]byte(bucketMailboxStatusExpiry)).Put([]byte{1, 2, 3}, []byte("malformed"))
				}); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name: "mismatched live entry",
			mutate: func(t *testing.T, b *Bolt) {
				t.Helper()
				if err := b.Update(func(tx *bbolt.Tx) error {
					bucket := tx.Bucket([]byte(bucketMailboxItemExpiry))
					key, value := bucket.Cursor().First()
					if err := bucket.Delete(key); err != nil {
						return err
					}
					return bucket.Put(maintenanceExpiryKey(1, value), value)
				}); err != nil {
					t.Fatal(err)
				}
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			b := openTestBolt(t)
			s, err := OpenMailboxStore(b, DefaultMailboxLimits())
			if err != nil {
				t.Fatal(err)
			}
			rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
			if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil {
				t.Fatal(err)
			}
			if err := s.Ack(rec.RecipientDevice, rec.MsgID, rec.EnvelopeSHA256, time.UnixMilli(2000)); err != nil {
				t.Fatal(err)
			}
			live := testMailboxRecord("22222222-2222-4222-8222-222222222222", "b")
			if _, err := s.Put(live, time.UnixMilli(3000)); err != nil {
				t.Fatal(err)
			}
			tt.mutate(t, b)
			if _, err := OpenMailboxStore(b, DefaultMailboxLimits()); err == nil {
				t.Fatal("marker-present mailbox expiry corruption was silently trusted")
			}
		})
	}
}

func assertMaintenanceExpiryIndexesExact(t *testing.T, b *Bolt) {
	t.Helper()
	if err := b.View(func(tx *bbolt.Tx) error {
		for _, check := range []struct {
			recordBucket string
			indexBucket  string
			decode       func([]byte) (int64, []byte, error)
		}{
			{
				recordBucket: bucketMailboxItems,
				indexBucket:  bucketMailboxItemExpiry,
				decode: func(raw []byte) (int64, []byte, error) {
					var rec MailboxRecord
					if err := json.Unmarshal(raw, &rec); err != nil {
						return 0, nil, err
					}
					return rec.ExpiresAt, itemKey(rec.RecipientDevice, rec.MsgID), nil
				},
			},
			{
				recordBucket: bucketMailboxStatus,
				indexBucket:  bucketMailboxStatusExpiry,
				decode: func(raw []byte) (int64, []byte, error) {
					var status DeliveryStatus
					if err := json.Unmarshal(raw, &status); err != nil {
						return 0, nil, err
					}
					return status.ExpiresAt, statusKey(status.SenderDevice, status.MsgID), nil
				},
			},
		} {
			records := tx.Bucket([]byte(check.recordBucket))
			index := tx.Bucket([]byte(check.indexBucket))
			if index == nil {
				return fmt.Errorf("missing %s", check.indexBucket)
			}
			recordCount := 0
			if records != nil {
				if err := records.ForEach(func(key, raw []byte) error {
					expiresAt, canonicalKey, err := check.decode(raw)
					if err != nil {
						return err
					}
					if !bytes.Equal(key, canonicalKey) || !bytes.Equal(index.Get(maintenanceExpiryKey(expiresAt, key)), key) {
						return fmt.Errorf("missing reciprocal %s entry", check.indexBucket)
					}
					recordCount++
					return nil
				}); err != nil {
					return err
				}
			}
			indexCount := 0
			if err := index.ForEach(func(indexKey, canonicalKey []byte) error {
				if len(indexKey) < 9 || indexKey[8] != 0 || !bytes.Equal(indexKey[9:], canonicalKey) || records == nil {
					return fmt.Errorf("malformed or orphan %s entry", check.indexBucket)
				}
				raw := records.Get(canonicalKey)
				if raw == nil {
					return fmt.Errorf("orphan %s entry", check.indexBucket)
				}
				expiresAt, decodedKey, err := check.decode(raw)
				if err != nil {
					return err
				}
				if !bytes.Equal(decodedKey, canonicalKey) || !bytes.Equal(indexKey, maintenanceExpiryKey(expiresAt, canonicalKey)) {
					return fmt.Errorf("mismatched %s entry", check.indexBucket)
				}
				indexCount++
				return nil
			}); err != nil {
				return err
			}
			if indexCount != recordCount {
				return fmt.Errorf("%s count %d, records %d", check.indexBucket, indexCount, recordCount)
			}
		}
		return nil
	}); err != nil {
		t.Fatalf("maintenance expiry indexes are not exact: %v", err)
	}
}

func mailboxBucketEntryCount(t *testing.T, b *Bolt, bucketName string) int {
	t.Helper()
	count := 0
	if err := b.View(func(tx *bbolt.Tx) error {
		bucket := tx.Bucket([]byte(bucketName))
		if bucket == nil {
			return nil
		}
		return bucket.ForEach(func(_, _ []byte) error {
			count++
			return nil
		})
	}); err != nil {
		t.Fatal(err)
	}
	return count
}

func openTestBolt(t *testing.T) *Bolt {
	t.Helper()
	b, err := OpenBolt(filepath.Join(t.TempDir(), "mailbox.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = b.Close() })
	return b
}

func testMailboxRecord(msgID, digestSeed string) MailboxRecord {
	return MailboxRecord{
		RecipientDevice: "dev-b",
		SenderDevice:    "dev-a",
		MsgID:           msgID,
		EnvelopeSHA256:  strings.Repeat(digestSeed, 64),
		Envelope:        []byte(`{"v":2,"type":"enc"}`),
	}
}

func snapshotMailboxBuckets(t *testing.T, b *Bolt) map[string]map[string]string {
	t.Helper()
	snapshot := make(map[string]map[string]string)
	if err := b.View(func(tx *bbolt.Tx) error {
		for _, name := range []string{
			bucketMailboxItems, bucketMailboxOrder, bucketMailboxStats, bucketMailboxStatus,
			bucketMailboxStatusByRecipient, bucketMailboxExpiryPending, bucketMailboxExpiryCursor,
			bucketMailboxMeta, bucketMailboxSequence, bucketMailboxItemExpiry, bucketMailboxStatusExpiry,
		} {
			entries := make(map[string]string)
			bucket := tx.Bucket([]byte(name))
			if bucket != nil {
				if err := bucket.ForEach(func(key, value []byte) error {
					entries[string(key)] = string(value)
					return nil
				}); err != nil {
					return err
				}
			}
			snapshot[name] = entries
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	return snapshot
}
