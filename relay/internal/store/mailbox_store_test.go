package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
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
		for _, name := range []string{bucketMailboxItems, bucketMailboxOrder, bucketMailboxStats, bucketMailboxStatus, bucketMailboxSequence} {
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
