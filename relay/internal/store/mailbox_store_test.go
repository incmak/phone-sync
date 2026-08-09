package store

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"
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
