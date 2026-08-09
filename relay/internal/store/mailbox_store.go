package store

import (
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"go.etcd.io/bbolt"
)

var (
	ErrMailboxFull       = errors.New("mailbox full")
	ErrMessageIDConflict = errors.New("message id conflict")
	ErrDigestMismatch    = errors.New("digest mismatch")
)

const (
	bucketMailboxItems  = "mailbox_items"
	bucketMailboxOrder  = "mailbox_order"
	bucketMailboxStats  = "mailbox_stats"
	bucketMailboxStatus = "mailbox_status"
	statusRetention     = 24 * time.Hour
)

type MailboxLimits struct {
	MaxItems  int
	MaxBytes  uint64
	Retention time.Duration
}

func DefaultMailboxLimits() MailboxLimits {
	return MailboxLimits{MaxItems: 2000, MaxBytes: 128 << 20, Retention: 24 * time.Hour}
}

type MailboxRecord struct {
	RecipientDevice string `json:"recipient_device"`
	SenderDevice    string `json:"sender_device"`
	MsgID           string `json:"msg_id"`
	EnvelopeSHA256  string `json:"envelope_sha256"`
	Envelope        []byte `json:"envelope"`
	ByteSize        uint64 `json:"byte_size"`
	AcceptedAt      int64  `json:"accepted_at"`
	ExpiresAt       int64  `json:"expires_at"`
}

type PutResult struct {
	AcceptedAt int64
	Duplicate  bool
}

type DeliveryStatus struct {
	SenderDevice    string `json:"sender_device"`
	RecipientDevice string `json:"recipient_device"`
	MsgID           string `json:"msg_id"`
	Status          string `json:"status"`
	OccurredAt      int64  `json:"occurred_at"`
	ExpiresAt       int64  `json:"expires_at"`
}

// ExpiredRecord is the sender-addressed metadata emitted after a mailbox item
// expires. It deliberately contains no envelope bytes.
type ExpiredRecord struct {
	SenderDevice    string
	RecipientDevice string
	MsgID           string
	ExpiredAt       int64
}

type MailboxStore struct {
	bolt   *Bolt
	limits MailboxLimits
}

func NewMailboxStore(b *Bolt, limits MailboxLimits) *MailboxStore {
	return &MailboxStore{bolt: b, limits: limits}
}

func (s *MailboxStore) Put(rec MailboxRecord, now time.Time) (PutResult, error) {
	if err := validateMailboxRecord(rec); err != nil {
		return PutResult{}, err
	}
	digest, _ := decodeDigest(rec.EnvelopeSHA256)
	rec.EnvelopeSHA256 = hex.EncodeToString(digest)
	rec.ByteSize = uint64(len(rec.Envelope))
	rec.AcceptedAt = now.UnixMilli()
	rec.ExpiresAt = now.Add(s.limits.Retention).UnixMilli()
	encoded, err := json.Marshal(rec)
	if err != nil {
		return PutResult{}, fmt.Errorf("marshal mailbox record: %w", err)
	}

	result := PutResult{AcceptedAt: rec.AcceptedAt}
	err = s.bolt.Update(func(tx *bbolt.Tx) error {
		items, order, stats, _, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		key := itemKey(rec.RecipientDevice, rec.MsgID)
		if existing := items.Get(key); existing != nil {
			var stored MailboxRecord
			if err := json.Unmarshal(existing, &stored); err != nil {
				return fmt.Errorf("unmarshal mailbox item: %w", err)
			}
			storedDigest, err := decodeDigest(stored.EnvelopeSHA256)
			if err != nil {
				return fmt.Errorf("decode stored mailbox digest: %w", err)
			}
			if subtle.ConstantTimeCompare(storedDigest, digest) != 1 {
				return ErrMessageIDConflict
			}
			result.AcceptedAt = stored.AcceptedAt
			result.Duplicate = true
			return nil
		}

		count, bytes := readMailboxStats(stats.Get([]byte(rec.RecipientDevice)))
		if s.limits.MaxItems <= 0 || count >= uint64(s.limits.MaxItems) || rec.ByteSize > s.limits.MaxBytes || bytes > s.limits.MaxBytes-rec.ByteSize {
			return ErrMailboxFull
		}
		if err := items.Put(key, encoded); err != nil {
			return err
		}
		if err := order.Put(orderKey(rec.RecipientDevice, rec.AcceptedAt, rec.MsgID), nil); err != nil {
			return err
		}
		return stats.Put([]byte(rec.RecipientDevice), encodeMailboxStats(count+1, bytes+rec.ByteSize))
	})
	return result, err
}

func (s *MailboxStore) Pending(recipient string, limit int) ([]MailboxRecord, error) {
	if recipient == "" || strings.ContainsRune(recipient, '\x00') {
		return nil, errors.New("invalid recipient device")
	}
	if limit <= 0 {
		return []MailboxRecord{}, nil
	}

	result := make([]MailboxRecord, 0, limit)
	err := s.bolt.View(func(tx *bbolt.Tx) error {
		items := tx.Bucket([]byte(bucketMailboxItems))
		order := tx.Bucket([]byte(bucketMailboxOrder))
		if items == nil || order == nil {
			return nil
		}
		prefix := recipientPrefix(recipient)
		cursor := order.Cursor()
		for key, _ := cursor.Seek(prefix); key != nil && len(result) < limit && strings.HasPrefix(string(key), string(prefix)); key, _ = cursor.Next() {
			_, msgID, ok := parseOrderKey(key)
			if !ok {
				return fmt.Errorf("invalid mailbox order key")
			}
			raw := items.Get(itemKey(recipient, msgID))
			if raw == nil {
				return fmt.Errorf("mailbox item missing for order index")
			}
			var rec MailboxRecord
			if err := json.Unmarshal(raw, &rec); err != nil {
				return fmt.Errorf("unmarshal mailbox item: %w", err)
			}
			result = append(result, copyMailboxRecord(rec))
		}
		return nil
	})
	return result, err
}

func (s *MailboxStore) Ack(recipient, msgID, digest string, now time.Time) error {
	if err := validateMailboxKey(recipient, msgID); err != nil {
		return err
	}
	wantDigest, err := decodeDigest(digest)
	if err != nil {
		return ErrDigestMismatch
	}

	return s.bolt.Update(func(tx *bbolt.Tx) error {
		items, order, stats, statuses, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		raw := items.Get(itemKey(recipient, msgID))
		if raw == nil {
			return ErrNotFound
		}
		var rec MailboxRecord
		if err := json.Unmarshal(raw, &rec); err != nil {
			return fmt.Errorf("unmarshal mailbox item: %w", err)
		}
		storedDigest, err := decodeDigest(rec.EnvelopeSHA256)
		if err != nil {
			return fmt.Errorf("decode stored mailbox digest: %w", err)
		}
		if subtle.ConstantTimeCompare([]byte(rec.RecipientDevice), []byte(recipient)) != 1 || subtle.ConstantTimeCompare(storedDigest, wantDigest) != 1 {
			return ErrDigestMismatch
		}
		count, bytes := readMailboxStats(stats.Get([]byte(recipient)))
		if count == 0 || bytes < rec.ByteSize {
			return errors.New("invalid mailbox statistics")
		}
		status := DeliveryStatus{
			SenderDevice:    rec.SenderDevice,
			RecipientDevice: rec.RecipientDevice,
			MsgID:           rec.MsgID,
			Status:          "acknowledged",
			OccurredAt:      now.UnixMilli(),
			ExpiresAt:       now.Add(statusRetention).UnixMilli(),
		}
		statusRaw, err := json.Marshal(status)
		if err != nil {
			return fmt.Errorf("marshal delivery status: %w", err)
		}
		if err := items.Delete(itemKey(recipient, msgID)); err != nil {
			return err
		}
		if err := order.Delete(orderKey(recipient, rec.AcceptedAt, msgID)); err != nil {
			return err
		}
		if err := stats.Put([]byte(recipient), encodeMailboxStats(count-1, bytes-rec.ByteSize)); err != nil {
			return err
		}
		return statuses.Put(statusKey(rec.SenderDevice, rec.MsgID), statusRaw)
	})
}

func (s *MailboxStore) Expire(now time.Time) ([]ExpiredRecord, error) {
	expired := []ExpiredRecord{}
	nowMillis := now.UnixMilli()
	err := s.bolt.Update(func(tx *bbolt.Tx) error {
		items, order, stats, statuses, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		cursor := items.Cursor()
		for key, raw := cursor.First(); key != nil; {
			nextKey, nextRaw := cursor.Next()
			var rec MailboxRecord
			if err := json.Unmarshal(raw, &rec); err != nil {
				return fmt.Errorf("unmarshal mailbox item: %w", err)
			}
			if rec.ExpiresAt <= nowMillis {
				count, bytes := readMailboxStats(stats.Get([]byte(rec.RecipientDevice)))
				if count == 0 || bytes < rec.ByteSize {
					return errors.New("invalid mailbox statistics")
				}
				status := DeliveryStatus{
					SenderDevice:    rec.SenderDevice,
					RecipientDevice: rec.RecipientDevice,
					MsgID:           rec.MsgID,
					Status:          "expired",
					OccurredAt:      rec.ExpiresAt,
					ExpiresAt:       now.Add(statusRetention).UnixMilli(),
				}
				statusRaw, err := json.Marshal(status)
				if err != nil {
					return fmt.Errorf("marshal delivery status: %w", err)
				}
				if err := items.Delete(key); err != nil {
					return err
				}
				if err := order.Delete(orderKey(rec.RecipientDevice, rec.AcceptedAt, rec.MsgID)); err != nil {
					return err
				}
				if err := stats.Put([]byte(rec.RecipientDevice), encodeMailboxStats(count-1, bytes-rec.ByteSize)); err != nil {
					return err
				}
				if err := statuses.Put(statusKey(rec.SenderDevice, rec.MsgID), statusRaw); err != nil {
					return err
				}
				expired = append(expired, ExpiredRecord{
					SenderDevice:    rec.SenderDevice,
					RecipientDevice: rec.RecipientDevice,
					MsgID:           rec.MsgID,
					ExpiredAt:       rec.ExpiresAt,
				})
			}
			key, raw = nextKey, nextRaw
		}
		return nil
	})
	return expired, err
}

func (s *MailboxStore) ExpireStatuses(now time.Time) error {
	return s.bolt.Update(func(tx *bbolt.Tx) error {
		statuses := tx.Bucket([]byte(bucketMailboxStatus))
		if statuses == nil {
			return nil
		}
		cursor := statuses.Cursor()
		for key, raw := cursor.First(); key != nil; {
			nextKey, nextRaw := cursor.Next()
			var status DeliveryStatus
			if err := json.Unmarshal(raw, &status); err != nil {
				return fmt.Errorf("unmarshal delivery status: %w", err)
			}
			if status.ExpiresAt <= now.UnixMilli() {
				if err := statuses.Delete(key); err != nil {
					return err
				}
			}
			key, raw = nextKey, nextRaw
		}
		return nil
	})
}

func (s *MailboxStore) Statuses(sender string, since time.Time) ([]DeliveryStatus, error) {
	if sender == "" || strings.ContainsRune(sender, '\x00') {
		return nil, errors.New("invalid sender device")
	}
	statuses := []DeliveryStatus{}
	prefix := recipientPrefix(sender)
	err := s.bolt.View(func(tx *bbolt.Tx) error {
		bucket := tx.Bucket([]byte(bucketMailboxStatus))
		if bucket == nil {
			return nil
		}
		cursor := bucket.Cursor()
		for key, raw := cursor.Seek(prefix); key != nil && strings.HasPrefix(string(key), string(prefix)); key, raw = cursor.Next() {
			var status DeliveryStatus
			if err := json.Unmarshal(raw, &status); err != nil {
				return fmt.Errorf("unmarshal delivery status: %w", err)
			}
			if status.OccurredAt >= since.UnixMilli() {
				statuses = append(statuses, status)
			}
		}
		return nil
	})
	return statuses, err
}

func (s *MailboxStore) PurgePair(deviceA, deviceB string) error {
	if err := validateMailboxKey(deviceA, deviceB); err != nil {
		return err
	}
	return s.bolt.Update(func(tx *bbolt.Tx) error {
		return purgePairTx(tx, deviceA, deviceB)
	})
}

// purgePairTx removes mailbox state that belongs to the pair in either
// direction. It is intentionally transaction-scoped so pair deletion can
// compose it with pair-index removal atomically.
func purgePairTx(tx *bbolt.Tx, deviceA, deviceB string) error {
	items := tx.Bucket([]byte(bucketMailboxItems))
	order := tx.Bucket([]byte(bucketMailboxOrder))
	stats := tx.Bucket([]byte(bucketMailboxStats))
	statuses := tx.Bucket([]byte(bucketMailboxStatus))
	if items != nil {
		for recipient, sender := range map[string]string{deviceA: deviceB, deviceB: deviceA} {
			var removedCount, removedBytes uint64
			prefix := recipientPrefix(recipient)
			cursor := items.Cursor()
			for key, raw := cursor.Seek(prefix); key != nil && strings.HasPrefix(string(key), string(prefix)); {
				nextKey, nextRaw := cursor.Next()
				var rec MailboxRecord
				if err := json.Unmarshal(raw, &rec); err != nil {
					return fmt.Errorf("unmarshal mailbox item: %w", err)
				}
				if rec.RecipientDevice == recipient && rec.SenderDevice == sender {
					if err := items.Delete(key); err != nil {
						return err
					}
					if order != nil {
						if err := order.Delete(orderKey(recipient, rec.AcceptedAt, rec.MsgID)); err != nil {
							return err
						}
					}
					removedCount++
					removedBytes += rec.ByteSize
				}
				key, raw = nextKey, nextRaw
			}
			if removedCount > 0 && stats != nil {
				count, bytes := readMailboxStats(stats.Get([]byte(recipient)))
				if count < removedCount || bytes < removedBytes {
					return errors.New("invalid mailbox statistics")
				}
				if count == removedCount {
					if err := stats.Delete([]byte(recipient)); err != nil {
						return err
					}
				} else if err := stats.Put([]byte(recipient), encodeMailboxStats(count-removedCount, bytes-removedBytes)); err != nil {
					return err
				}
			}
		}
	}
	if statuses != nil {
		for sender, recipient := range map[string]string{deviceA: deviceB, deviceB: deviceA} {
			prefix := recipientPrefix(sender)
			cursor := statuses.Cursor()
			for key, raw := cursor.Seek(prefix); key != nil && strings.HasPrefix(string(key), string(prefix)); {
				nextKey, nextRaw := cursor.Next()
				var status DeliveryStatus
				if err := json.Unmarshal(raw, &status); err != nil {
					return fmt.Errorf("unmarshal delivery status: %w", err)
				}
				if status.SenderDevice == sender && status.RecipientDevice == recipient {
					if err := statuses.Delete(key); err != nil {
						return err
					}
				}
				key, raw = nextKey, nextRaw
			}
		}
	}
	return nil
}

func validateMailboxRecord(rec MailboxRecord) error {
	for _, field := range []struct {
		name  string
		value string
	}{
		{"recipient device", rec.RecipientDevice},
		{"sender device", rec.SenderDevice},
		{"message id", rec.MsgID},
		{"envelope digest", rec.EnvelopeSHA256},
	} {
		if field.value == "" || strings.ContainsRune(field.value, '\x00') {
			return fmt.Errorf("invalid %s", field.name)
		}
	}
	if _, err := decodeDigest(rec.EnvelopeSHA256); err != nil {
		return fmt.Errorf("invalid envelope digest: %w", err)
	}
	return nil
}

func validateMailboxKey(recipient, msgID string) error {
	for _, field := range []struct {
		name  string
		value string
	}{
		{"recipient device", recipient},
		{"message id", msgID},
	} {
		if field.value == "" || strings.ContainsRune(field.value, '\x00') {
			return fmt.Errorf("invalid %s", field.name)
		}
	}
	return nil
}

func decodeDigest(digest string) ([]byte, error) {
	decoded, err := hex.DecodeString(strings.ToLower(digest))
	if err != nil || len(decoded) != 32 {
		if err == nil {
			err = errors.New("must be a SHA-256 digest")
		}
		return nil, err
	}
	return decoded, nil
}

func mailboxBuckets(tx *bbolt.Tx) (items, order, stats, statuses *bbolt.Bucket, err error) {
	if items, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxItems)); err != nil {
		return nil, nil, nil, nil, err
	}
	if order, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxOrder)); err != nil {
		return nil, nil, nil, nil, err
	}
	if stats, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxStats)); err != nil {
		return nil, nil, nil, nil, err
	}
	if statuses, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxStatus)); err != nil {
		return nil, nil, nil, nil, err
	}
	return items, order, stats, statuses, nil
}

func itemKey(recipient, msgID string) []byte {
	return []byte(recipient + "\x00" + msgID)
}

func statusKey(sender, msgID string) []byte {
	return []byte(sender + "\x00" + msgID)
}

func recipientPrefix(recipient string) []byte {
	return []byte(recipient + "\x00")
}

func orderKey(recipient string, acceptedAt int64, msgID string) []byte {
	key := make([]byte, 0, len(recipient)+1+8+1+len(msgID))
	key = append(key, recipient...)
	key = append(key, 0)
	var timestamp [8]byte
	binary.BigEndian.PutUint64(timestamp[:], uint64(acceptedAt))
	key = append(key, timestamp[:]...)
	key = append(key, 0)
	key = append(key, msgID...)
	return key
}

func parseOrderKey(key []byte) (recipient, msgID string, ok bool) {
	firstSeparator := strings.IndexByte(string(key), 0)
	if firstSeparator < 0 || len(key) < firstSeparator+10 || key[firstSeparator+9] != 0 {
		return "", "", false
	}
	return string(key[:firstSeparator]), string(key[firstSeparator+10:]), true
}

func readMailboxStats(raw []byte) (count, bytes uint64) {
	if len(raw) != 16 {
		return 0, 0
	}
	return binary.BigEndian.Uint64(raw[:8]), binary.BigEndian.Uint64(raw[8:])
}

func encodeMailboxStats(count, bytes uint64) []byte {
	raw := make([]byte, 16)
	binary.BigEndian.PutUint64(raw[:8], count)
	binary.BigEndian.PutUint64(raw[8:], bytes)
	return raw
}

func copyMailboxRecord(rec MailboxRecord) MailboxRecord {
	rec.Envelope = append([]byte(nil), rec.Envelope...)
	return rec
}
