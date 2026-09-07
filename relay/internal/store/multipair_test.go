package store

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"go.etcd.io/bbolt"
)

func trianglePair(id, a, b string) ConfirmedPair {
	return ConfirmedPair{PairID: id, DeviceA: a, DeviceB: b, AEncPubkey: []byte(a + "-enc"), ASignPubkey: []byte(a + "-sign"), BEncPubkey: []byte(b + "-enc"), BSignPubkey: []byte(b + "-sign")}
}

func TestThreeDeviceMembershipIsolationAndAggregateQuota(t *testing.T) {
	b := openTestBolt(t)
	pairs := NewPairStore(b)
	for _, pair := range []ConfirmedPair{trianglePair("ab", "a", "b"), trianglePair("am", "a", "m"), trianglePair("bm", "b", "m")} {
		if err := pairs.Confirm(pair); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := pairs.SessionFor("a"); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("ambiguous selection = %v", err)
	}
	if _, err := pairs.SessionForPair("a", "bm"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("foreign selection = %v", err)
	}
	if err := pairs.Confirm(trianglePair("ax", "a", "x")); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("third link = %v", err)
	}
	limits := DefaultMailboxLimits()
	limits.MaxItems = 2
	mailbox := NewMailboxStore(b, limits)
	now := time.Now()
	record := func(sender, token string) MailboxRecord {
		r := testMailboxRecord("44444444-4444-4444-8444-444444444444", token)
		r.SenderDevice = sender
		r.RecipientDevice = "m"
		return r
	}
	a, brec := record("a", "a"), record("b", "b")
	// Deliberately reuse msg_id across links; ciphertext and receipt digests differ.
	for pair, rec := range map[string]MailboxRecord{"am": a, "bm": brec} {
		result, err := mailbox.PutForPair(pair, rec, now)
		if err != nil || result.AcceptanceSequence != 1 {
			t.Fatalf("put %s: %#v %v", pair, result, err)
		}
	}
	more := a
	more.MsgID = "55555555-5555-4555-8555-555555555555"
	if _, err := mailbox.PutForPair("am", more, now); !errors.Is(err, ErrMailboxFull) {
		t.Fatalf("aggregate cap = %v", err)
	}
	if err := mailbox.AckForPair("am", "m", a.MsgID, brec.EnvelopeSHA256, now); !errors.Is(err, ErrDigestMismatch) {
		t.Fatalf("foreign digest = %v", err)
	}
	if err := mailbox.AckForPair("am", "m", a.MsgID, a.EnvelopeSHA256, now); err != nil {
		t.Fatal(err)
	}
	if rows, err := mailbox.PendingForPair("bm", "m", 10); err != nil || len(rows) != 1 || !bytes.Equal(rows[0].Envelope, brec.Envelope) {
		t.Fatalf("other link lost: %#v %v", rows, err)
	}
	if err := pairs.UpdateCapabilitiesForPair("a", "am", []int{2}, "a"); err != nil {
		t.Fatal(err)
	}
	if err := pairs.UpdateCapabilitiesForPair("m", "am", []int{2}, "m"); err != nil {
		t.Fatal(err)
	}
	if _, _, floor, err := pairs.CapabilitiesForPair("m", "bm"); err != nil || floor != 1 {
		t.Fatalf("cross-link floor: %d %v", floor, err)
	}
	if _, err := pairs.RevokeBySession("a", "am"); err != nil {
		t.Fatal(err)
	}
	if rows, err := mailbox.PendingForPair("bm", "m", 10); err != nil || len(rows) != 1 {
		t.Fatalf("revoke cleared other link: %#v %v", rows, err)
	}
	if _, err := pairs.SessionForPair("a", "ab"); err != nil {
		t.Fatal(err)
	}
	changed := trianglePair("am2", "a", "m")
	changed.ASignPubkey = []byte("changed")
	if err := pairs.Confirm(changed); !errors.Is(err, ErrPairConflict) {
		t.Fatalf("key substitution = %v", err)
	}
	if err := pairs.Confirm(trianglePair("am2", "a", "m")); err != nil {
		t.Fatal(err)
	}
	if rows, err := mailbox.PendingForPair("am2", "m", 10); err != nil || len(rows) != 0 {
		t.Fatalf("new generation inherited work: %#v %v", rows, err)
	}
	if _, err := OpenPairStore(b); err != nil {
		t.Fatal(err)
	}
}

func TestLegacyPairMailboxMigrationPreservesBytesAndRejectsCorruption(t *testing.T) {
	for _, corrupt := range []bool{false, true} {
		t.Run(fmt.Sprint(corrupt), func(t *testing.T) {
			b := openTestBolt(t)
			// Construct the supported v2 layout without going through the new pair store.
			cp := trianglePair("legacy", "a", "b")
			if err := b.Update(func(tx *bbolt.Tx) error {
				confirmed, err := tx.CreateBucketIfNotExists([]byte(bucketConfirmed))
				if err != nil {
					return err
				}
				raw, _ := json.Marshal(cp)
				if err := confirmed.Put([]byte(cp.PairID), raw); err != nil {
					return err
				}
				index, err := tx.CreateBucketIfNotExists([]byte(bucketByDevice))
				if err != nil {
					return err
				}
				for _, dev := range []string{"a", "b"} {
					if err := index.Put([]byte(dev), []byte(cp.PairID)); err != nil {
						return err
					}
				}
				if err := rebuildPairStoreIndexesTx(tx, DefaultPendingPairLimits()); err != nil {
					return err
				}
				meta := tx.Bucket([]byte(bucketPairMeta))
				config, _ := encodePairIndexConfigVersion(DefaultPendingPairLimits(), 2)
				if err := meta.Put(pairIndexConfigKey, config); err != nil {
					return err
				}
				return meta.Put(pairIndexSchemaVersionKey, []byte{2})
			}); err != nil {
				t.Fatal(err)
			}
			// Seed immutable records directly, as the old binary did.
			now := time.Now()
			rec := testMailboxRecord("44444444-4444-4444-8444-444444444444", "a")
			rec.SenderDevice = "a"
			rec.RecipientDevice = "b"
			rec.AcceptedAt = now.UnixMilli()
			rec.ExpiresAt = now.Add(time.Hour).UnixMilli()
			rec.ByteSize = uint64(len(rec.Envelope))
			rec.AcceptanceSequence = 41
			digest := sha256.Sum256(rec.Envelope)
			rec.EnvelopeSHA256 = hex.EncodeToString(digest[:])
			if err := b.Update(func(tx *bbolt.Tx) error {
				items, order, stats, statuses, err := mailboxBuckets(tx)
				if err != nil {
					return err
				}
				raw, _ := json.Marshal(rec)
				if err := items.Put(itemKey("b", rec.MsgID), raw); err != nil {
					return err
				}
				if err := order.Put(orderKey("b", 41, rec.MsgID), nil); err != nil {
					return err
				}
				if err := stats.Put([]byte("b"), encodeMailboxStats(1, rec.ByteSize)); err != nil {
					return err
				}
				seq, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxSequence))
				if err != nil {
					return err
				}
				if err := seq.Put([]byte("b"), encodePendingCount(41)); err != nil {
					return err
				}
				terminal := DeliveryStatus{SenderDevice: "a", RecipientDevice: "b",
					MsgID: "66666666-6666-4666-8666-666666666666", Status: "expired",
					OccurredAt: now.Add(-time.Minute).UnixMilli(), ExpiresAt: now.Add(time.Hour).UnixMilli(),
					EnvelopeSHA256: rec.EnvelopeSHA256, AcceptedAt: now.Add(-time.Hour).UnixMilli(),
					MailboxExpiresAt: now.Add(-time.Minute).UnixMilli()}
				terminalRaw, _ := json.Marshal(terminal)
				if err := statuses.Put(statusKey("a", terminal.MsgID), terminalRaw); err != nil {
					return err
				}
				if _, _, _, err := mailboxStatusIndexes(tx, statuses); err != nil {
					return err
				}
				caps, err := tx.CreateBucketIfNotExists([]byte(bucketCapabilities))
				if err != nil {
					return err
				}
				for _, device := range []string{"a", "b"} {
					raw, _ := json.Marshal(DeviceCapabilities{Protocols: []int{2, 1}, AppVersion: "legacy-" + device, UpdatedAt: now.UnixMilli()})
					if err := caps.Put([]byte(device), raw); err != nil {
						return err
					}
				}
				floors, err := tx.CreateBucketIfNotExists([]byte(bucketProtocolFloor))
				if err != nil {
					return err
				}
				if err := floors.Put([]byte("legacy"), []byte{2}); err != nil {
					return err
				}
				if err := migrateMaintenanceExpiryIndexesTx(tx); err != nil {
					return err
				}
				if corrupt {
					return tx.Bucket([]byte(bucketByDevice)).Delete([]byte("b"))
				}
				return nil
			}); err != nil {
				t.Fatal(err)
			}
			before := snapshotMailboxBuckets(t, b)
			ps, err := OpenPairStore(b)
			if corrupt {
				if err == nil {
					t.Fatal("orphan ownership migrated")
				}
				after := snapshotMailboxBuckets(t, b)
				beforeJSON, _ := json.Marshal(before)
				afterJSON, _ := json.Marshal(after)
				if !bytes.Equal(beforeJSON, afterJSON) {
					t.Fatal("failed migration mutated mailbox")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			mailbox, err := OpenMailboxStore(b, DefaultMailboxLimits())
			if err != nil {
				t.Fatal(err)
			}
			rows, err := mailbox.PendingForPair("legacy", "b", 10)
			if err != nil || len(rows) != 1 || !bytes.Equal(rows[0].Envelope, rec.Envelope) || rows[0].EnvelopeSHA256 != rec.EnvelopeSHA256 || rows[0].AcceptanceSequence != 41 {
				t.Fatalf("migration changed custody: %#v %v", rows, err)
			}
			self, peer, floor, err := ps.CapabilitiesForPair("a", "legacy")
			if err != nil || floor != 2 || self.AppVersion != "legacy-a" || peer.AppVersion != "legacy-b" {
				t.Fatalf("migration changed capabilities/floor: %#v %#v %d %v", self, peer, floor, err)
			}
			statuses, err := mailbox.ExpiryStatusesForPair("legacy", "a", "b", 10, now)
			if err != nil || len(statuses) != 1 || statuses[0].MsgID != "66666666-6666-4666-8666-666666666666" || statuses[0].EnvelopeSHA256 != rec.EnvelopeSHA256 {
				t.Fatalf("migration changed expiry status/indexes: %#v %v", statuses, err)
			}
			more := rec
			more.MsgID = "77777777-7777-4777-8777-777777777777"
			if result, err := mailbox.PutForPair("legacy", more, now); err != nil || result.AcceptanceSequence != 42 {
				t.Fatalf("migration lost acceptance sequence: %#v %v", result, err)
			}
			if err := ps.Confirm(trianglePair("am", "a", "m")); err != nil {
				t.Fatal(err)
			}
			if err := b.View(func(tx *bbolt.Tx) error { return validatePairStoreIndexesVersionTx(tx, DefaultPendingPairLimits(), 2) }); err == nil {
				t.Fatal("v2 validator accepted migrated storage")
			}
		})
	}
}
