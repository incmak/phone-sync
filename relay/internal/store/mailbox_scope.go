package store

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"go.etcd.io/bbolt"
)

const bucketMailboxPairs = "mailbox_pairs_v3"

type bucketTransaction interface {
	Bucket([]byte) *bbolt.Bucket
	CreateBucketIfNotExists([]byte) (*bbolt.Bucket, error)
}

type pairMailboxTransaction struct {
	tx     *bbolt.Tx
	pairID string
}

func scopedMailboxBucket(name []byte) bool {
	return strings.HasPrefix(string(name), "mailbox_") && string(name) != bucketMailboxStats
}
func (s pairMailboxTransaction) Bucket(name []byte) *bbolt.Bucket {
	if !scopedMailboxBucket(name) {
		return s.tx.Bucket(name)
	}
	root := s.tx.Bucket([]byte(bucketMailboxPairs))
	if root == nil {
		return nil
	}
	pair := root.Bucket([]byte(s.pairID))
	if pair == nil {
		return nil
	}
	return pair.Bucket(name)
}
func (s pairMailboxTransaction) CreateBucketIfNotExists(name []byte) (*bbolt.Bucket, error) {
	if !scopedMailboxBucket(name) {
		return s.tx.CreateBucketIfNotExists(name)
	}
	root, err := s.tx.CreateBucketIfNotExists([]byte(bucketMailboxPairs))
	if err != nil {
		return nil, err
	}
	pair, err := root.CreateBucketIfNotExists([]byte(s.pairID))
	if err != nil {
		return nil, err
	}
	return pair.CreateBucketIfNotExists(name)
}
func mailboxScope(tx *bbolt.Tx, pairID string) bucketTransaction {
	if pairID == "" {
		return tx
	}
	return pairMailboxTransaction{tx, pairID}
}

// Legacy public helpers resolve a sole membership; ambiguity fails closed.
// An unpaired standalone store remains useful for storage tooling and tests.
func mailboxSessionScope(tx *bbolt.Tx, selected, device string) (bucketTransaction, error) {
	if selected == "" {
		ids, err := membershipsTx(tx, device)
		if err != nil {
			return nil, err
		}
		if len(ids) > 1 {
			return nil, ErrPairConflict
		}
		if len(ids) == 1 {
			selected = ids[0]
		}
	}
	if selected != "" {
		if _, _, err := confirmedPairForSessionTx(tx, device, selected); err != nil {
			return nil, err
		}
	}
	return mailboxScope(tx, selected), nil
}

var mailboxScopedBuckets = []string{bucketMailboxItems, bucketMailboxOrder, bucketMailboxStatus,
	bucketMailboxStatusByRecipient, bucketMailboxExpiryPending, bucketMailboxExpiryCursor,
	bucketMailboxMeta, bucketMailboxSequence, bucketMailboxItemExpiry, bucketMailboxStatusExpiry}

func mailboxScopeIDs(tx *bbolt.Tx) ([]string, error) {
	ids := []string{""}
	if root := tx.Bucket([]byte(bucketMailboxPairs)); root != nil {
		err := root.ForEach(func(key, value []byte) error {
			if value != nil || len(key) == 0 {
				return errors.New("invalid mailbox scope")
			}
			ids = append(ids, string(key))
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	return ids, nil
}

func migrateMailboxScopesTx(tx *bbolt.Tx) error {
	if tx.Bucket([]byte(bucketMailboxPairs)) != nil {
		return errors.New("unexpected mailbox namespace before migration")
	}
	if err := migrateMaintenanceExpiryIndexesTx(tx); err != nil {
		return err
	}
	if err := validateMaintenanceExpiryIndexesTx(tx); err != nil {
		return err
	}
	legacy := tx.Bucket([]byte(bucketByDevice))
	owner := func(device string) (string, error) {
		if legacy == nil || legacy.Get([]byte(device)) == nil {
			return "", fmt.Errorf("mailbox migration: orphan device %q", device)
		}
		return string(legacy.Get([]byte(device))), nil
	}
	totals := map[string][2]uint64{}
	for _, name := range mailboxScopedBuckets {
		bucket := tx.Bucket([]byte(name))
		if bucket == nil || name == bucketMailboxMeta {
			continue
		}
		if err := bucket.ForEach(func(key, raw []byte) error {
			if bucket.Bucket(key) != nil {
				return errors.New("nested legacy mailbox bucket")
			}
			device := ""
			switch name {
			case bucketMailboxItems:
				var rec MailboxRecord
				if json.Unmarshal(raw, &rec) != nil || validateMailboxRecord(rec) != nil || !bytes.Equal(key, itemKey(rec.RecipientDevice, rec.MsgID)) || rec.ByteSize != uint64(len(rec.Envelope)) {
					return errors.New("invalid legacy mailbox record")
				}
				digest := sha256.Sum256(rec.Envelope)
				if rec.EnvelopeSHA256 != hex.EncodeToString(digest[:]) {
					return errors.New("legacy envelope digest mismatch")
				}
				p1, err := owner(rec.SenderDevice)
				if err != nil {
					return err
				}
				p2, err := owner(rec.RecipientDevice)
				if err != nil || p1 != p2 || rec.SenderDevice == rec.RecipientDevice {
					return errors.New("legacy mailbox ownership mismatch")
				}
				order := tx.Bucket([]byte(bucketMailboxOrder))
				if order == nil || order.Get(orderKey(rec.RecipientDevice, mailboxOrderValue(rec), rec.MsgID)) == nil {
					return errors.New("legacy mailbox order mismatch")
				}
				total := totals[rec.RecipientDevice]
				total[0]++
				total[1] += rec.ByteSize
				totals[rec.RecipientDevice] = total
				device = rec.RecipientDevice
			case bucketMailboxStatus:
				var status DeliveryStatus
				if json.Unmarshal(raw, &status) != nil || !bytes.Equal(key, statusKey(status.SenderDevice, status.MsgID)) {
					return errors.New("invalid legacy status")
				}
				a, err := owner(status.SenderDevice)
				if err != nil {
					return err
				}
				b, err := owner(status.RecipientDevice)
				if err != nil || a != b || status.SenderDevice == status.RecipientDevice {
					return errors.New("legacy status ownership mismatch")
				}
				device = status.SenderDevice
			case bucketMailboxSequence:
				if len(raw) != 8 {
					return errors.New("invalid legacy acceptance sequence")
				}
				device = string(key)
			case bucketMailboxItemExpiry, bucketMailboxStatusExpiry:
				device = strings.SplitN(string(raw), "\x00", 2)[0]
			case bucketMailboxOrder:
				recipient, msgID, ok := parseOrderKey(key)
				if !ok {
					return errors.New("invalid legacy order")
				}
				items := tx.Bucket([]byte(bucketMailboxItems))
				if items == nil || items.Get(itemKey(recipient, msgID)) == nil {
					return errors.New("orphan legacy order")
				}
				device = recipient
			default:
				device = strings.SplitN(string(key), "\x00", 2)[0]
			}
			pairID, err := owner(device)
			if err != nil {
				return err
			}
			dest, err := mailboxScope(tx, pairID).CreateBucketIfNotExists([]byte(name))
			if err != nil {
				return err
			}
			return dest.Put(key, raw)
		}); err != nil {
			return fmt.Errorf("migrate %s: %w", name, err)
		}
	}
	stats := tx.Bucket([]byte(bucketMailboxStats))
	if stats != nil {
		if err := stats.ForEach(func(device, raw []byte) error {
			count, size := readMailboxStats(raw)
			if len(raw) != 16 || totals[string(device)] != [2]uint64{count, size} {
				return errors.New("legacy aggregate mailbox quota mismatch")
			}
			delete(totals, string(device))
			return nil
		}); err != nil {
			return err
		}
	}
	if len(totals) != 0 {
		return errors.New("missing legacy mailbox quota")
	}
	// Preserve metadata markers in every migrated scope, then validate them.
	ids, err := mailboxScopeIDs(tx)
	if err != nil {
		return err
	}
	for _, id := range ids {
		if id == "" {
			continue
		}
		scope := mailboxScope(tx, id)
		if meta := tx.Bucket([]byte(bucketMailboxMeta)); meta != nil {
			dest, err := scope.CreateBucketIfNotExists([]byte(bucketMailboxMeta))
			if err != nil {
				return err
			}
			if err := meta.ForEach(func(k, v []byte) error { return dest.Put(k, v) }); err != nil {
				return err
			}
		}
		for _, name := range []string{bucketMailboxItemExpiry, bucketMailboxStatusExpiry} {
			if _, err := scope.CreateBucketIfNotExists([]byte(name)); err != nil {
				return err
			}
		}
		if err := validateMaintenanceExpiryIndexesTx(scope); err != nil {
			return err
		}
	}
	for _, name := range mailboxScopedBuckets {
		if tx.Bucket([]byte(name)) != nil {
			if err := tx.DeleteBucket([]byte(name)); err != nil {
				return err
			}
		}
	}
	return nil
}

// Maintenance walks at most 256 namespaces per transaction and persists a
// round-robin cursor. Record budgets remain enforced by the caller. A full
// record batch leaves the cursor before that scope so the next tick resumes it.
func walkMailboxMaintenanceTx(tx *bbolt.Tx, task string, visit func(bucketTransaction, string) (bool, error)) error {
	keepGoing, err := visit(tx, "")
	if err != nil || !keepGoing {
		return err
	}
	root := tx.Bucket([]byte(bucketMailboxPairs))
	if root == nil {
		return nil
	}
	meta, err := tx.CreateBucketIfNotExists([]byte("mailbox_maintenance_cursor_v3"))
	if err != nil {
		return err
	}
	cursor := root.Cursor()
	last := bytes.Clone(meta.Get([]byte(task)))
	key, _ := cursor.First()
	if len(last) > 0 {
		key, _ = cursor.Seek(last)
		if bytes.Equal(key, last) {
			key, _ = cursor.Next()
		}
		if key == nil {
			key, _ = cursor.First()
		}
	}
	first := bytes.Clone(key)
	for inspected := 0; key != nil && inspected < 256; inspected++ {
		current := bytes.Clone(key)
		if root.Bucket(current) == nil {
			return errors.New("invalid mailbox namespace")
		}
		keepGoing, err := visit(mailboxScope(tx, string(current)), string(current))
		if err != nil || !keepGoing {
			return err
		}
		if err := meta.Put([]byte(task), current); err != nil {
			return err
		}
		key, _ = cursor.Next()
		if key == nil {
			key, _ = cursor.First()
		}
		if bytes.Equal(key, first) {
			break
		}
	}
	return nil
}
