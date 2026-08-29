package store

import (
	"bytes"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.etcd.io/bbolt"
)

var (
	ErrMailboxFull       = errors.New("mailbox full")
	ErrMessageIDConflict = errors.New("message id conflict")
	ErrDigestMismatch    = errors.New("digest mismatch")
)

const (
	bucketMailboxItems             = "mailbox_items"
	bucketMailboxOrder             = "mailbox_order"
	bucketMailboxStats             = "mailbox_stats"
	bucketMailboxStatus            = "mailbox_status"
	bucketMailboxStatusByRecipient = "mailbox_status_by_recipient"
	bucketMailboxExpiryPending     = "mailbox_expiry_pending"
	bucketMailboxExpiryCursor      = "mailbox_expiry_cursor"
	bucketMailboxMeta              = "mailbox_meta"
	bucketMailboxSequence          = "mailbox_sequence"
	bucketMailboxItemExpiry        = "mailbox_item_expiry"
	bucketMailboxStatusExpiry      = "mailbox_status_expiry"
	statusRetention                = 24 * time.Hour
	// Reserve the explicitly configured bbolt file-growth step in addition to
	// the logical mailbox write so a commit cannot consume the free-space floor.
	mailboxBoltGrowthAllowance uint64 = boltAllocSize
)

var statusIndexesVersionKey = []byte("status_indexes_v2")
var maintenanceExpiryIndexesVersionKey = []byte("maintenance_expiry_indexes_v1")

type MailboxLimits struct {
	MaxItems  int
	MaxBytes  uint64
	Retention time.Duration
}

func DefaultMailboxLimits() MailboxLimits {
	return MailboxLimits{MaxItems: 2000, MaxBytes: 128 << 20, Retention: 24 * time.Hour}
}

type MailboxRecord struct {
	RecipientDevice    string `json:"recipient_device"`
	SenderDevice       string `json:"sender_device"`
	MsgID              string `json:"msg_id"`
	EnvelopeSHA256     string `json:"envelope_sha256"`
	Envelope           []byte `json:"envelope"`
	ByteSize           uint64 `json:"byte_size"`
	AcceptedAt         int64  `json:"accepted_at"`
	ExpiresAt          int64  `json:"expires_at"`
	AcceptanceSequence uint64 `json:"acceptance_sequence,omitempty"`
}

type PutResult struct {
	AcceptedAt         int64
	AcceptanceSequence uint64
	Duplicate          bool
	Terminal           bool
}

// MailboxPutAdmission runs inside the same Bolt write transaction that will
// persist a new mailbox record. requiredBytes includes the JSON-encoded record,
// every mailbox/index mutation (including legacy order migration), and the
// pinned bbolt file-growth allowance. Returning an error aborts the transaction
// without mutation.
type MailboxPutAdmission func(requiredBytes uint64) error

type DeliveryStatus struct {
	SenderDevice     string `json:"sender_device"`
	RecipientDevice  string `json:"recipient_device"`
	MsgID            string `json:"msg_id"`
	Status           string `json:"status"`
	OccurredAt       int64  `json:"occurred_at"`
	ExpiresAt        int64  `json:"expires_at"`
	EnvelopeSHA256   string `json:"envelope_sha256,omitempty"`
	AcceptedAt       int64  `json:"accepted_at,omitempty"`
	MailboxExpiresAt int64  `json:"mailbox_expires_at,omitempty"`
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
	store, err := OpenMailboxStore(b, limits)
	if err != nil {
		panic(fmt.Sprintf("migrate mailbox expiry indexes: %v", err))
	}
	return store
}

func OpenMailboxStore(b *Bolt, limits MailboxLimits) (*MailboxStore, error) {
	if err := b.Update(func(tx *bbolt.Tx) error {
		if err := migrateMaintenanceExpiryIndexesTx(tx); err != nil {
			return err
		}
		return validateMaintenanceExpiryIndexesTx(tx)
	}); err != nil {
		return nil, fmt.Errorf("migrate mailbox expiry indexes: %w", err)
	}
	return &MailboxStore{bolt: b, limits: limits}, nil
}

func (s *MailboxStore) Put(rec MailboxRecord, now time.Time) (PutResult, error) {
	return s.putForPair("", rec, now, nil)
}

func (s *MailboxStore) PutForPair(pairID string, rec MailboxRecord, now time.Time) (PutResult, error) {
	return s.putForPair(pairID, rec, now, nil)
}

func (s *MailboxStore) PutForPairWithAdmission(pairID string, rec MailboxRecord, now time.Time, admit MailboxPutAdmission) (PutResult, error) {
	return s.putForPair(pairID, rec, now, admit)
}

func (s *MailboxStore) putForPair(expectedPairID string, rec MailboxRecord, now time.Time, admit MailboxPutAdmission) (PutResult, error) {
	if err := validateMailboxRecord(rec); err != nil {
		return PutResult{}, err
	}
	digest, _ := decodeDigest(rec.EnvelopeSHA256)
	rec.EnvelopeSHA256 = hex.EncodeToString(digest)
	rec.ByteSize = uint64(len(rec.Envelope))
	rec.AcceptedAt = now.UnixMilli()
	rec.ExpiresAt = now.Add(s.limits.Retention).UnixMilli()
	result := PutResult{AcceptedAt: rec.AcceptedAt}
	err := s.bolt.Update(func(tx *bbolt.Tx) error {
		if expectedPairID != "" {
			if _, err := authorizePairDevicesTx(tx, expectedPairID, rec.SenderDevice, rec.RecipientDevice); err != nil {
				return err
			}
		}
		items, order, stats, statuses, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		statusesByRecipient, expiryPending, expiryCursors, err := mailboxStatusIndexes(tx, statuses)
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
			if stored.SenderDevice != rec.SenderDevice || subtle.ConstantTimeCompare(storedDigest, digest) != 1 {
				return ErrMessageIDConflict
			}
			if err := requireMaintenanceExpiryEntry(tx.Bucket([]byte(bucketMailboxItemExpiry)), stored.ExpiresAt, key); err != nil {
				return err
			}
			result.AcceptedAt = stored.AcceptedAt
			result.AcceptanceSequence = stored.AcceptanceSequence
			result.Duplicate = true
			return nil
		}
		status, terminalKey, terminalFound, err := terminalStatusForPut(statuses, statusesByRecipient, rec.RecipientDevice, rec.MsgID)
		if err != nil {
			return err
		}
		if terminalFound {
			if status.ExpiresAt <= now.UnixMilli() {
				if err := statuses.Delete(terminalKey); err != nil {
					return err
				}
				statusExpiry := tx.Bucket([]byte(bucketMailboxStatusExpiry))
				if err := deleteMaintenanceExpiryEntry(statusExpiry, status.ExpiresAt, terminalKey); err != nil {
					return err
				}
				if err := statusesByRecipient.Delete(itemKey(rec.RecipientDevice, rec.MsgID)); err != nil {
					return err
				}
				if err := expiryPending.Delete(expiryStatusKey(status.SenderDevice, status.RecipientDevice, status.MsgID)); err != nil {
					return err
				}
				if err := deleteExpiryCursorIfMatches(expiryCursors, status.SenderDevice, status.RecipientDevice, status.MsgID); err != nil {
					return err
				}
			} else {
				if status.SenderDevice != rec.SenderDevice || status.RecipientDevice != rec.RecipientDevice || status.AcceptedAt == 0 || status.EnvelopeSHA256 == "" {
					return ErrMessageIDConflict
				}
				terminalDigest, err := decodeDigest(status.EnvelopeSHA256)
				if err != nil {
					return fmt.Errorf("decode terminal mailbox digest: %w", err)
				}
				if subtle.ConstantTimeCompare(terminalDigest, digest) != 1 {
					return ErrMessageIDConflict
				}
				result.AcceptedAt = status.AcceptedAt
				result.Duplicate = true
				result.Terminal = true
				return nil
			}
		}

		count, bytes := readMailboxStats(stats.Get([]byte(rec.RecipientDevice)))
		if s.limits.MaxItems <= 0 || count >= uint64(s.limits.MaxItems) || rec.ByteSize > s.limits.MaxBytes || bytes > s.limits.MaxBytes-rec.ByteSize {
			return ErrMailboxFull
		}
		sequence, sequenceMutationBytes, err := allocateAcceptanceSequence(tx, rec.RecipientDevice, items, order)
		if err != nil {
			return err
		}
		rec.AcceptanceSequence = sequence
		result.AcceptanceSequence = sequence
		encoded, err := json.Marshal(rec)
		if err != nil {
			return fmt.Errorf("marshal mailbox record: %w", err)
		}
		itemExpiryKey := maintenanceExpiryKey(rec.ExpiresAt, key)
		mailboxOrderKey := orderKey(rec.RecipientDevice, rec.AcceptanceSequence, rec.MsgID)
		if admit != nil {
			logicalBytes := sequenceMutationBytes
			for _, size := range []uint64{
				uint64(len(key)), uint64(len(encoded)),
				uint64(len(itemExpiryKey)), uint64(len(key)),
				uint64(len(mailboxOrderKey)), uint64(len(rec.RecipientDevice)), 16,
			} {
				if err := addMailboxReservation(&logicalBytes, size); err != nil {
					return err
				}
			}
			if err := addMailboxReservation(&logicalBytes, mailboxBoltGrowthAllowance); err != nil {
				return errors.New("mailbox capacity reservation overflow")
			}
			if err := admit(logicalBytes); err != nil {
				return err
			}
		}
		if err := items.Put(key, encoded); err != nil {
			return err
		}
		itemExpiry := tx.Bucket([]byte(bucketMailboxItemExpiry))
		if itemExpiry == nil {
			return errors.New("missing mailbox expiry index")
		}
		if err := itemExpiry.Put(itemExpiryKey, key); err != nil {
			return err
		}
		if err := order.Put(mailboxOrderKey, nil); err != nil {
			return err
		}
		return stats.Put([]byte(rec.RecipientDevice), encodeMailboxStats(count+1, bytes+rec.ByteSize))
	})
	return result, err
}

func (s *MailboxStore) Pending(recipient string, limit int) ([]MailboxRecord, error) {
	return s.pendingForPair("", recipient, limit)
}

func (s *MailboxStore) PendingForPair(pairID, recipient string, limit int) ([]MailboxRecord, error) {
	return s.pendingForPair(pairID, recipient, limit)
}

func (s *MailboxStore) pendingForPair(expectedPairID, recipient string, limit int) ([]MailboxRecord, error) {
	if recipient == "" || strings.ContainsRune(recipient, '\x00') {
		return nil, errors.New("invalid recipient device")
	}
	if limit <= 0 && expectedPairID == "" {
		return []MailboxRecord{}, nil
	}

	result := []MailboxRecord{}
	if limit > 0 {
		result = make([]MailboxRecord, 0, limit)
	}
	err := s.bolt.View(func(tx *bbolt.Tx) error {
		var expectedSender string
		if expectedPairID != "" {
			_, pair, err := confirmedPairForSessionTx(tx, recipient, expectedPairID)
			if err != nil {
				return err
			}
			expectedSender = pair.DeviceA
			if recipient == pair.DeviceA {
				expectedSender = pair.DeviceB
			}
		}
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
			if expectedSender != "" && rec.SenderDevice != expectedSender {
				continue
			}
			result = append(result, copyMailboxRecord(rec))
		}
		return nil
	})
	return result, err
}

// LiveByIDs resolves opaque mailbox records in the requested order. Missing,
// acknowledged, purged, and expired records are omitted. Every returned value,
// including its ciphertext bytes, is copied before the Bolt view closes.
func (s *MailboxStore) LiveByIDs(recipient string, msgIDs []string, now time.Time) ([]MailboxRecord, error) {
	result := []MailboxRecord{}
	err := s.TransferLiveByIDs(recipient, msgIDs, now, func(records []MailboxRecord) error {
		result = append(result, records...)
		return nil
	})
	return result, err
}

// TransferLiveByIDs keeps the Bolt read transaction open through transfer.
// The callback must do only bounded copying/serialization and nonblocking queue
// insertion. It must never perform socket I/O or wait for queue capacity.
func (s *MailboxStore) TransferLiveByIDs(recipient string, msgIDs []string, now time.Time, transfer func([]MailboxRecord) error) error {
	return s.transferLiveByIDsForPair("", recipient, msgIDs, now, transfer)
}

func (s *MailboxStore) TransferLiveByIDsForPair(pairID, recipient string, msgIDs []string, now time.Time, transfer func([]MailboxRecord) error) error {
	return s.transferLiveByIDsForPair(pairID, recipient, msgIDs, now, transfer)
}

func (s *MailboxStore) transferLiveByIDsForPair(expectedPairID, recipient string, msgIDs []string, now time.Time, transfer func([]MailboxRecord) error) error {
	if recipient == "" || strings.ContainsRune(recipient, '\x00') {
		return errors.New("invalid recipient device")
	}
	for _, msgID := range msgIDs {
		if err := validateMailboxKey(recipient, msgID); err != nil {
			return err
		}
	}

	result := make([]MailboxRecord, 0, len(msgIDs))
	return s.bolt.View(func(tx *bbolt.Tx) error {
		var expectedSender string
		if expectedPairID != "" {
			_, pair, err := confirmedPairForSessionTx(tx, recipient, expectedPairID)
			if err != nil {
				return err
			}
			expectedSender = pair.DeviceA
			if recipient == pair.DeviceA {
				expectedSender = pair.DeviceB
			}
		}
		items := tx.Bucket([]byte(bucketMailboxItems))
		if items != nil {
			nowMillis := now.UnixMilli()
			for _, msgID := range msgIDs {
				raw := items.Get(itemKey(recipient, msgID))
				if raw == nil {
					continue
				}
				var rec MailboxRecord
				if err := json.Unmarshal(raw, &rec); err != nil {
					return fmt.Errorf("unmarshal mailbox item: %w", err)
				}
				if rec.ExpiresAt <= nowMillis {
					continue
				}
				if expectedSender != "" && rec.SenderDevice != expectedSender {
					continue
				}
				result = append(result, copyMailboxRecord(rec))
			}
		}
		return transfer(result)
	})
}

func (s *MailboxStore) Ack(recipient, msgID, digest string, now time.Time) error {
	return s.ackForPair("", recipient, msgID, digest, now)
}

func (s *MailboxStore) AckForPair(pairID, recipient, msgID, digest string, now time.Time) error {
	return s.ackForPair(pairID, recipient, msgID, digest, now)
}

func (s *MailboxStore) ackForPair(expectedPairID, recipient, msgID, digest string, now time.Time) error {
	if err := validateMailboxKey(recipient, msgID); err != nil {
		return err
	}
	wantDigest, err := decodeDigest(digest)
	if err != nil {
		return ErrDigestMismatch
	}

	return s.bolt.Update(func(tx *bbolt.Tx) error {
		var expectedSender string
		if expectedPairID != "" {
			peerID, err := authorizePairDeviceTx(tx, expectedPairID, recipient)
			if err != nil {
				return err
			}
			expectedSender = peerID
		}
		items, order, stats, statuses, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		statusesByRecipient, expiryPending, _, err := mailboxStatusIndexes(tx, statuses)
		if err != nil {
			return err
		}
		raw := items.Get(itemKey(recipient, msgID))
		if raw == nil {
			if expectedSender != "" {
				status, _, found, err := terminalStatusForPut(statuses, statusesByRecipient, recipient, msgID)
				if err != nil {
					return err
				}
				if !found || status.SenderDevice != expectedSender {
					return ErrNotFound
				}
			}
			return duplicateAckStatus(statuses, statusesByRecipient, recipient, msgID, wantDigest, now.UnixMilli())
		}
		var rec MailboxRecord
		if err := json.Unmarshal(raw, &rec); err != nil {
			return fmt.Errorf("unmarshal mailbox item: %w", err)
		}
		if expectedSender != "" && rec.SenderDevice != expectedSender {
			return ErrNotFound
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
			SenderDevice:     rec.SenderDevice,
			RecipientDevice:  rec.RecipientDevice,
			MsgID:            rec.MsgID,
			Status:           "acknowledged",
			OccurredAt:       now.UnixMilli(),
			ExpiresAt:        now.Add(statusRetention).UnixMilli(),
			EnvelopeSHA256:   rec.EnvelopeSHA256,
			AcceptedAt:       rec.AcceptedAt,
			MailboxExpiresAt: rec.ExpiresAt,
		}
		statusRaw, err := json.Marshal(status)
		if err != nil {
			return fmt.Errorf("marshal delivery status: %w", err)
		}
		if err := items.Delete(itemKey(recipient, msgID)); err != nil {
			return err
		}
		itemExpiry := tx.Bucket([]byte(bucketMailboxItemExpiry))
		statusExpiry := tx.Bucket([]byte(bucketMailboxStatusExpiry))
		if itemExpiry == nil || statusExpiry == nil {
			return errors.New("missing mailbox maintenance expiry index")
		}
		if err := deleteMaintenanceExpiryEntry(itemExpiry, rec.ExpiresAt, itemKey(recipient, msgID)); err != nil {
			return err
		}
		if err := order.Delete(orderKey(recipient, mailboxOrderValue(rec), msgID)); err != nil {
			return err
		}
		if err := stats.Put([]byte(recipient), encodeMailboxStats(count-1, bytes-rec.ByteSize)); err != nil {
			return err
		}
		canonicalKey := statusKey(rec.SenderDevice, rec.MsgID)
		if err := statuses.Put(canonicalKey, statusRaw); err != nil {
			return err
		}
		if err := statusExpiry.Put(maintenanceExpiryKey(status.ExpiresAt, canonicalKey), canonicalKey); err != nil {
			return err
		}
		if err := statusesByRecipient.Put(itemKey(rec.RecipientDevice, rec.MsgID), canonicalKey); err != nil {
			return err
		}
		if err := expiryPending.Delete(expiryStatusKey(rec.SenderDevice, rec.RecipientDevice, rec.MsgID)); err != nil {
			return err
		}
		return nil
	})
}

func authorizePairDeviceTx(tx *bbolt.Tx, expectedPairID, deviceID string) (string, error) {
	_, pair, err := confirmedPairForSessionTx(tx, deviceID, expectedPairID)
	if err != nil {
		return "", err
	}
	if pair.DeviceA == deviceID {
		return pair.DeviceB, nil
	}
	return pair.DeviceA, nil
}

func authorizePairDevicesTx(tx *bbolt.Tx, expectedPairID, deviceID, peerID string) (ConfirmedPair, error) {
	_, pair, err := confirmedPairForSessionTx(tx, deviceID, expectedPairID)
	if err != nil {
		return ConfirmedPair{}, err
	}
	if (pair.DeviceA != deviceID || pair.DeviceB != peerID) && (pair.DeviceB != deviceID || pair.DeviceA != peerID) {
		return ConfirmedPair{}, ErrNotFound
	}
	return pair, nil
}

func (s *MailboxStore) Expire(now time.Time) ([]ExpiredRecord, error) {
	all := []ExpiredRecord{}
	for {
		batch, err := s.ExpireBatch(now, 1024)
		if err != nil {
			return nil, err
		}
		all = append(all, batch...)
		if len(batch) < 1024 {
			return all, nil
		}
	}
}

func (s *MailboxStore) ExpireForPair(pairID, deviceID string, now time.Time) ([]ExpiredRecord, error) {
	if pairID == "" || deviceID == "" || strings.ContainsRune(deviceID, '\x00') {
		return nil, errors.New("invalid pair session")
	}
	if err := s.bolt.View(func(tx *bbolt.Tx) error {
		_, err := authorizePairDeviceTx(tx, pairID, deviceID)
		return err
	}); err != nil {
		return nil, err
	}
	return s.ExpireBatch(now, 256)
}

func (s *MailboxStore) ExpireBatch(now time.Time, limit int) ([]ExpiredRecord, error) {
	if limit <= 0 {
		return []ExpiredRecord{}, nil
	}
	expired := make([]ExpiredRecord, 0, limit)
	err := s.bolt.Update(func(tx *bbolt.Tx) error {
		items, order, stats, statuses, err := mailboxBuckets(tx)
		if err != nil {
			return err
		}
		statusesByRecipient, expiryPending, _, err := mailboxStatusIndexes(tx, statuses)
		if err != nil {
			return err
		}
		itemExpiry := tx.Bucket([]byte(bucketMailboxItemExpiry))
		statusExpiry := tx.Bucket([]byte(bucketMailboxStatusExpiry))
		if itemExpiry == nil || statusExpiry == nil {
			return errors.New("missing mailbox maintenance expiry index")
		}
		for len(expired) < limit {
			indexKey, canonicalKey := itemExpiry.Cursor().First()
			if indexKey == nil {
				break
			}
			expiresAt, err := maintenanceExpiryTime(indexKey)
			if err != nil {
				return err
			}
			if expiresAt > now.UnixMilli() {
				break
			}
			canonicalKey = append([]byte(nil), canonicalKey...)
			raw := items.Get(canonicalKey)
			if raw == nil {
				return errors.New("mailbox expiry index points to missing item")
			}
			var rec MailboxRecord
			if err := json.Unmarshal(raw, &rec); err != nil {
				return fmt.Errorf("unmarshal mailbox item: %w", err)
			}
			if rec.ExpiresAt != expiresAt || !bytes.Equal(canonicalKey, itemKey(rec.RecipientDevice, rec.MsgID)) {
				return errors.New("mailbox expiry index identity mismatch")
			}
			count, byteCount := readMailboxStats(stats.Get([]byte(rec.RecipientDevice)))
			if count == 0 || byteCount < rec.ByteSize {
				return errors.New("invalid mailbox statistics")
			}
			status := DeliveryStatus{SenderDevice: rec.SenderDevice, RecipientDevice: rec.RecipientDevice, MsgID: rec.MsgID,
				Status: "expired", OccurredAt: rec.ExpiresAt, ExpiresAt: now.Add(statusRetention).UnixMilli(),
				EnvelopeSHA256: rec.EnvelopeSHA256, AcceptedAt: rec.AcceptedAt, MailboxExpiresAt: rec.ExpiresAt}
			statusRaw, err := json.Marshal(status)
			if err != nil {
				return err
			}
			if err := items.Delete(canonicalKey); err != nil {
				return err
			}
			if err := itemExpiry.Delete(indexKey); err != nil {
				return err
			}
			if err := order.Delete(orderKey(rec.RecipientDevice, mailboxOrderValue(rec), rec.MsgID)); err != nil {
				return err
			}
			if err := stats.Put([]byte(rec.RecipientDevice), encodeMailboxStats(count-1, byteCount-rec.ByteSize)); err != nil {
				return err
			}
			statusCanonicalKey := statusKey(rec.SenderDevice, rec.MsgID)
			if err := statuses.Put(statusCanonicalKey, statusRaw); err != nil {
				return err
			}
			if err := statusesByRecipient.Put(itemKey(rec.RecipientDevice, rec.MsgID), statusCanonicalKey); err != nil {
				return err
			}
			if err := expiryPending.Put(expiryStatusKey(rec.SenderDevice, rec.RecipientDevice, rec.MsgID), statusCanonicalKey); err != nil {
				return err
			}
			if err := statusExpiry.Put(maintenanceExpiryKey(status.ExpiresAt, statusCanonicalKey), statusCanonicalKey); err != nil {
				return err
			}
			expired = append(expired, ExpiredRecord{SenderDevice: rec.SenderDevice, RecipientDevice: rec.RecipientDevice, MsgID: rec.MsgID, ExpiredAt: rec.ExpiresAt})
		}
		return nil
	})
	return expired, err
}

func (s *MailboxStore) ExpireStatuses(now time.Time) error {
	for {
		removed, err := s.ExpireStatusesBatch(now, 1024)
		if err != nil {
			return err
		}
		if removed < 1024 {
			return nil
		}
	}
}

func (s *MailboxStore) ExpireStatusesBatch(now time.Time, limit int) (int, error) {
	if limit <= 0 {
		return 0, nil
	}
	removed := 0
	err := s.bolt.Update(func(tx *bbolt.Tx) error {
		statuses := tx.Bucket([]byte(bucketMailboxStatus))
		if statuses == nil {
			return nil
		}
		statusesByRecipient, expiryPending, expiryCursors, err := mailboxStatusIndexes(tx, statuses)
		if err != nil {
			return err
		}
		statusExpiry := tx.Bucket([]byte(bucketMailboxStatusExpiry))
		if statusExpiry == nil {
			return errors.New("missing delivery status expiry index")
		}
		for removed < limit {
			indexKey, key := statusExpiry.Cursor().First()
			if indexKey == nil {
				break
			}
			expiresAt, err := maintenanceExpiryTime(indexKey)
			if err != nil {
				return err
			}
			if expiresAt > now.UnixMilli() {
				break
			}
			key = append([]byte(nil), key...)
			raw := statuses.Get(key)
			if raw == nil {
				return errors.New("status expiry index points to missing status")
			}
			var status DeliveryStatus
			if err := json.Unmarshal(raw, &status); err != nil {
				return fmt.Errorf("unmarshal delivery status: %w", err)
			}
			if status.ExpiresAt != expiresAt || !bytes.Equal(key, statusKey(status.SenderDevice, status.MsgID)) {
				return errors.New("status expiry index identity mismatch")
			}
			if err := statuses.Delete(key); err != nil {
				return err
			}
			if err := statusesByRecipient.Delete(itemKey(status.RecipientDevice, status.MsgID)); err != nil {
				return err
			}
			if err := expiryPending.Delete(expiryStatusKey(status.SenderDevice, status.RecipientDevice, status.MsgID)); err != nil {
				return err
			}
			if err := deleteExpiryCursorIfMatches(expiryCursors, status.SenderDevice, status.RecipientDevice, status.MsgID); err != nil {
				return err
			}
			if err := statusExpiry.Delete(indexKey); err != nil {
				return err
			}
			removed++
		}
		return nil
	})
	return removed, err
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

// ExpiryStatuses inspects at most limit expiry-index entries for one
// sender/recipient pair in rotating message-id order. It returns only live
// tombstones and removes logically expired entries from canonical and index
// state in the same bounded transaction. ACK tombstones are never visited.
func (s *MailboxStore) ExpiryStatuses(sender, recipient string, limit int, now time.Time) ([]DeliveryStatus, error) {
	return s.expiryStatusesForPair("", sender, recipient, limit, now)
}

func (s *MailboxStore) ExpiryStatusesForPair(pairID, sender, recipient string, limit int, now time.Time) ([]DeliveryStatus, error) {
	return s.expiryStatusesForPair(pairID, sender, recipient, limit, now)
}

func (s *MailboxStore) expiryStatusesForPair(expectedPairID, sender, recipient string, limit int, now time.Time) ([]DeliveryStatus, error) {
	for _, device := range []string{sender, recipient} {
		if device == "" || strings.ContainsRune(device, '\x00') {
			return nil, errors.New("invalid device")
		}
	}
	if limit <= 0 && expectedPairID == "" {
		return []DeliveryStatus{}, nil
	}
	result := []DeliveryStatus{}
	if limit > 0 {
		result = make([]DeliveryStatus, 0, limit)
	}
	err := s.bolt.Update(func(tx *bbolt.Tx) error {
		if expectedPairID != "" {
			if _, err := authorizePairDevicesTx(tx, expectedPairID, sender, recipient); err != nil {
				return err
			}
		}
		if limit <= 0 {
			return nil
		}
		statuses := tx.Bucket([]byte(bucketMailboxStatus))
		if statuses == nil {
			return nil
		}
		statusesByRecipient, pending, cursors, err := mailboxStatusIndexes(tx, statuses)
		if err != nil {
			return err
		}
		prefix := expiryStatusPrefix(sender, recipient)
		cursor := pending.Cursor()
		var key, canonicalKey []byte
		if msgID := cursors.Get(expiryCursorKey(sender, recipient)); msgID != nil {
			lastKey := expiryStatusKey(sender, recipient, string(msgID))
			key, canonicalKey = cursor.Seek(lastKey)
			if bytes.Equal(key, lastKey) {
				key, canonicalKey = cursor.Next()
			}
		}
		if key == nil {
			key, canonicalKey = cursor.Seek(prefix)
		}
		seen := make(map[string]struct{}, limit)
		wrapped := false
		for len(result) < limit && len(seen) < limit {
			if key == nil || !bytes.HasPrefix(key, prefix) {
				if wrapped {
					break
				}
				key, canonicalKey = cursor.Seek(prefix)
				wrapped = true
				continue
			}
			keyString := string(key)
			if _, exists := seen[keyString]; exists {
				break
			}
			seen[keyString] = struct{}{}
			nextKey, nextCanonicalKey := cursor.Next()
			raw := statuses.Get(canonicalKey)
			if raw == nil {
				return errors.New("pending expiry index points to missing status")
			}
			var status DeliveryStatus
			if err := json.Unmarshal(raw, &status); err != nil {
				return fmt.Errorf("unmarshal delivery status: %w", err)
			}
			if status.Status != "expired" || status.SenderDevice != sender || status.RecipientDevice != recipient {
				return errors.New("pending expiry index identity mismatch")
			}
			if status.ExpiresAt <= now.UnixMilli() {
				if err := statuses.Delete(canonicalKey); err != nil {
					return err
				}
				if err := statusesByRecipient.Delete(itemKey(status.RecipientDevice, status.MsgID)); err != nil {
					return err
				}
				if err := pending.Delete(key); err != nil {
					return err
				}
				statusExpiry := tx.Bucket([]byte(bucketMailboxStatusExpiry))
				if err := deleteMaintenanceExpiryEntry(statusExpiry, status.ExpiresAt, canonicalKey); err != nil {
					return err
				}
				if err := deleteExpiryCursorIfMatches(cursors, status.SenderDevice, status.RecipientDevice, status.MsgID); err != nil {
					return err
				}
			} else {
				result = append(result, status)
			}
			key, canonicalKey = nextKey, nextCanonicalKey
		}
		return nil
	})
	return result, err
}

// AdvanceExpiryStatusCursor rotates the bounded hello retry cursor without
// deleting retryability or modifying canonical 24-hour tombstones. The caller
// must invoke it only after the full returned page was written successfully.
func (s *MailboxStore) AdvanceExpiryStatusCursor(sender, recipient, msgID string) error {
	return s.advanceExpiryStatusCursorForPair("", sender, recipient, msgID)
}

func (s *MailboxStore) AdvanceExpiryStatusCursorForPair(pairID, sender, recipient, msgID string) error {
	return s.advanceExpiryStatusCursorForPair(pairID, sender, recipient, msgID)
}

func (s *MailboxStore) advanceExpiryStatusCursorForPair(expectedPairID, sender, recipient, msgID string) error {
	for _, device := range []string{sender, recipient} {
		if device == "" || strings.ContainsRune(device, '\x00') {
			return errors.New("invalid device")
		}
	}
	if err := validateMailboxKey(recipient, msgID); err != nil {
		return err
	}
	return s.bolt.Update(func(tx *bbolt.Tx) error {
		if expectedPairID != "" {
			if _, err := authorizePairDevicesTx(tx, expectedPairID, sender, recipient); err != nil {
				return err
			}
		}
		pending := tx.Bucket([]byte(bucketMailboxExpiryPending))
		if pending == nil {
			return nil
		}
		cursorKey := expiryCursorKey(sender, recipient)
		cursors, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxExpiryCursor))
		if err != nil {
			return err
		}
		if pending.Get(expiryStatusKey(sender, recipient, msgID)) == nil {
			return cursors.Delete(cursorKey)
		}
		return cursors.Put(cursorKey, []byte(msgID))
	})
}

func (s *MailboxStore) PurgePair(deviceA, deviceB string) error {
	for _, device := range []string{deviceA, deviceB} {
		if device == "" || strings.ContainsRune(device, '\x00') {
			return errors.New("invalid device")
		}
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
	sequences := tx.Bucket([]byte(bucketMailboxSequence))
	expiryCursors := tx.Bucket([]byte(bucketMailboxExpiryCursor))
	itemExpiry := tx.Bucket([]byte(bucketMailboxItemExpiry))
	statusExpiry := tx.Bucket([]byte(bucketMailboxStatusExpiry))
	var statusesByRecipient, expiryPending *bbolt.Bucket
	if statuses != nil {
		var err error
		statusesByRecipient, expiryPending, expiryCursors, err = mailboxStatusIndexes(tx, statuses)
		if err != nil {
			return err
		}
	}
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
						if err := order.Delete(orderKey(recipient, mailboxOrderValue(rec), rec.MsgID)); err != nil {
							return err
						}
					}
					if err := deleteMaintenanceExpiryEntry(itemExpiry, rec.ExpiresAt, key); err != nil {
						return err
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
	if sequences != nil {
		if err := sequences.Delete([]byte(deviceA)); err != nil {
			return err
		}
		if err := sequences.Delete([]byte(deviceB)); err != nil {
			return err
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
					if err := statusesByRecipient.Delete(itemKey(status.RecipientDevice, status.MsgID)); err != nil {
						return err
					}
					if err := expiryPending.Delete(expiryStatusKey(status.SenderDevice, status.RecipientDevice, status.MsgID)); err != nil {
						return err
					}
					if err := deleteMaintenanceExpiryEntry(statusExpiry, status.ExpiresAt, key); err != nil {
						return err
					}
				}
				key, raw = nextKey, nextRaw
			}
		}
	}
	if expiryCursors != nil {
		if err := expiryCursors.Delete(expiryCursorKey(deviceA, deviceB)); err != nil {
			return err
		}
		if err := expiryCursors.Delete(expiryCursorKey(deviceB, deviceA)); err != nil {
			return err
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
	if err := uuid.Validate(rec.MsgID); err != nil {
		return fmt.Errorf("invalid message id: %w", err)
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
	if err := uuid.Validate(msgID); err != nil {
		return fmt.Errorf("invalid message id: %w", err)
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

func duplicateAckStatus(statuses, statusesByRecipient *bbolt.Bucket, recipient, msgID string, wantDigest []byte, nowMillis int64) error {
	status, _, found, err := terminalStatusForPut(statuses, statusesByRecipient, recipient, msgID)
	if err != nil {
		return err
	}
	if !found || status.Status != "acknowledged" || status.ExpiresAt <= nowMillis || status.EnvelopeSHA256 == "" {
		return ErrNotFound
	}
	storedDigest, err := decodeDigest(status.EnvelopeSHA256)
	if err != nil {
		return fmt.Errorf("decode terminal mailbox digest: %w", err)
	}
	if subtle.ConstantTimeCompare(storedDigest, wantDigest) != 1 {
		return ErrDigestMismatch
	}
	return nil
}

func terminalStatusForPut(statuses, statusesByRecipient *bbolt.Bucket, recipient, msgID string) (DeliveryStatus, []byte, bool, error) {
	canonicalKey := statusesByRecipient.Get(itemKey(recipient, msgID))
	if canonicalKey == nil {
		return DeliveryStatus{}, nil, false, nil
	}
	raw := statuses.Get(canonicalKey)
	if raw == nil {
		return DeliveryStatus{}, nil, false, errors.New("delivery status index points to missing record")
	}
	var status DeliveryStatus
	if err := json.Unmarshal(raw, &status); err != nil {
		return DeliveryStatus{}, nil, false, fmt.Errorf("unmarshal delivery status: %w", err)
	}
	if status.RecipientDevice != recipient || status.MsgID != msgID {
		return DeliveryStatus{}, nil, false, errors.New("delivery status index identity mismatch")
	}
	return status, append([]byte(nil), canonicalKey...), true, nil
}

func mailboxStatusIndexes(tx *bbolt.Tx, statuses *bbolt.Bucket) (statusesByRecipient, expiryPending, expiryCursors *bbolt.Bucket, err error) {
	if statusesByRecipient, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxStatusByRecipient)); err != nil {
		return nil, nil, nil, err
	}
	if expiryPending, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxExpiryPending)); err != nil {
		return nil, nil, nil, err
	}
	if expiryCursors, err = tx.CreateBucketIfNotExists([]byte(bucketMailboxExpiryCursor)); err != nil {
		return nil, nil, nil, err
	}
	meta, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxMeta))
	if err != nil {
		return nil, nil, nil, err
	}
	if meta.Get(statusIndexesVersionKey) != nil {
		return statusesByRecipient, expiryPending, expiryCursors, nil
	}
	cursor := statuses.Cursor()
	for key, raw := cursor.First(); key != nil; key, raw = cursor.Next() {
		var status DeliveryStatus
		if err := json.Unmarshal(raw, &status); err != nil {
			return nil, nil, nil, fmt.Errorf("migrate delivery status index: %w", err)
		}
		canonicalKey := append([]byte(nil), key...)
		if err := statusesByRecipient.Put(itemKey(status.RecipientDevice, status.MsgID), canonicalKey); err != nil {
			return nil, nil, nil, err
		}
		if status.Status == "expired" {
			if err := expiryPending.Put(expiryStatusKey(status.SenderDevice, status.RecipientDevice, status.MsgID), canonicalKey); err != nil {
				return nil, nil, nil, err
			}
		}
	}
	if err := meta.Put(statusIndexesVersionKey, []byte{1}); err != nil {
		return nil, nil, nil, err
	}
	return statusesByRecipient, expiryPending, expiryCursors, nil
}

func migrateMaintenanceExpiryIndexesTx(tx *bbolt.Tx) error {
	meta, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxMeta))
	if err != nil {
		return err
	}
	if meta.Get(maintenanceExpiryIndexesVersionKey) != nil {
		return nil
	}
	itemExpiry, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxItemExpiry))
	if err != nil {
		return err
	}
	statusExpiry, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxStatusExpiry))
	if err != nil {
		return err
	}
	if items := tx.Bucket([]byte(bucketMailboxItems)); items != nil {
		if err := items.ForEach(func(key, raw []byte) error {
			var rec MailboxRecord
			if err := json.Unmarshal(raw, &rec); err != nil {
				return fmt.Errorf("migrate mailbox expiry: %w", err)
			}
			return itemExpiry.Put(maintenanceExpiryKey(rec.ExpiresAt, key), key)
		}); err != nil {
			return err
		}
	}
	if statuses := tx.Bucket([]byte(bucketMailboxStatus)); statuses != nil {
		if err := statuses.ForEach(func(key, raw []byte) error {
			var status DeliveryStatus
			if err := json.Unmarshal(raw, &status); err != nil {
				return fmt.Errorf("migrate status expiry: %w", err)
			}
			return statusExpiry.Put(maintenanceExpiryKey(status.ExpiresAt, key), key)
		}); err != nil {
			return err
		}
	}
	return meta.Put(maintenanceExpiryIndexesVersionKey, []byte{1})
}

func validateMaintenanceExpiryIndexesTx(tx *bbolt.Tx) error {
	meta := tx.Bucket([]byte(bucketMailboxMeta))
	if meta == nil || !bytes.Equal(meta.Get(maintenanceExpiryIndexesVersionKey), []byte{1}) {
		return errors.New("invalid mailbox maintenance expiry marker")
	}
	checks := []struct {
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
	}
	for _, check := range checks {
		records := tx.Bucket([]byte(check.recordBucket))
		index := tx.Bucket([]byte(check.indexBucket))
		if index == nil {
			return fmt.Errorf("missing %s", check.indexBucket)
		}
		var recordCount, indexCount uint64
		if records != nil {
			if err := records.ForEach(func(key, raw []byte) error {
				expiresAt, canonicalKey, err := check.decode(raw)
				if err != nil {
					return fmt.Errorf("decode %s: %w", check.recordBucket, err)
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
				return fmt.Errorf("decode %s: %w", check.recordBucket, err)
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
}

func requireMaintenanceExpiryEntry(bucket *bbolt.Bucket, expiresAt int64, canonicalKey []byte) error {
	if bucket == nil || !bytes.Equal(bucket.Get(maintenanceExpiryKey(expiresAt, canonicalKey)), canonicalKey) {
		return errors.New("missing reciprocal mailbox maintenance expiry entry")
	}
	return nil
}

func deleteMaintenanceExpiryEntry(bucket *bbolt.Bucket, expiresAt int64, canonicalKey []byte) error {
	if err := requireMaintenanceExpiryEntry(bucket, expiresAt, canonicalKey); err != nil {
		return err
	}
	return bucket.Delete(maintenanceExpiryKey(expiresAt, canonicalKey))
}

func maintenanceExpiryKey(expiresAt int64, canonicalKey []byte) []byte {
	key := make([]byte, 9+len(canonicalKey))
	binary.BigEndian.PutUint64(key[:8], uint64(expiresAt))
	copy(key[9:], canonicalKey)
	return key
}

func maintenanceExpiryTime(key []byte) (int64, error) {
	if len(key) < 9 {
		return 0, errors.New("malformed maintenance expiry key")
	}
	return int64(binary.BigEndian.Uint64(key[:8])), nil
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

func expiryStatusKey(sender, recipient, msgID string) []byte {
	return []byte(sender + "\x00" + recipient + "\x00" + msgID)
}

func expiryStatusPrefix(sender, recipient string) []byte {
	return []byte(sender + "\x00" + recipient + "\x00")
}

func expiryCursorKey(sender, recipient string) []byte {
	return []byte(sender + "\x00" + recipient)
}

func deleteExpiryCursorIfMatches(cursors *bbolt.Bucket, sender, recipient, msgID string) error {
	key := expiryCursorKey(sender, recipient)
	if bytes.Equal(cursors.Get(key), []byte(msgID)) {
		return cursors.Delete(key)
	}
	return nil
}

func recipientPrefix(recipient string) []byte {
	return []byte(recipient + "\x00")
}

func allocateAcceptanceSequence(tx *bbolt.Tx, recipient string, items, order *bbolt.Bucket) (uint64, uint64, error) {
	sequences, err := tx.CreateBucketIfNotExists([]byte(bucketMailboxSequence))
	if err != nil {
		return 0, 0, err
	}
	rawCounter := sequences.Get([]byte(recipient))
	var current uint64
	var mutationBytes uint64
	if len(rawCounter) == 8 {
		current = binary.BigEndian.Uint64(rawCounter)
	} else if len(rawCounter) != 0 {
		return 0, 0, errors.New("invalid mailbox acceptance sequence")
	} else {
		type orderEntry struct {
			key   []byte
			msgID string
		}
		entries := []orderEntry{}
		prefix := recipientPrefix(recipient)
		cursor := order.Cursor()
		for key, _ := cursor.Seek(prefix); key != nil && strings.HasPrefix(string(key), string(prefix)); key, _ = cursor.Next() {
			_, msgID, ok := parseOrderKey(key)
			if !ok {
				return 0, 0, errors.New("invalid mailbox order key")
			}
			entries = append(entries, orderEntry{key: append([]byte(nil), key...), msgID: msgID})
		}
		for _, entry := range entries {
			if err := addMailboxReservation(&mutationBytes, uint64(len(entry.key))); err != nil {
				return 0, 0, err
			}
			if err := order.Delete(entry.key); err != nil {
				return 0, 0, err
			}
		}
		for index, entry := range entries {
			raw := items.Get(itemKey(recipient, entry.msgID))
			if raw == nil {
				return 0, 0, errors.New("mailbox item missing for order migration")
			}
			var rec MailboxRecord
			if err := json.Unmarshal(raw, &rec); err != nil {
				return 0, 0, fmt.Errorf("unmarshal mailbox item: %w", err)
			}
			rec.AcceptanceSequence = uint64(index + 1)
			encoded, err := json.Marshal(rec)
			if err != nil {
				return 0, 0, fmt.Errorf("marshal mailbox item: %w", err)
			}
			migratedItemKey := itemKey(recipient, rec.MsgID)
			migratedOrderKey := orderKey(recipient, rec.AcceptanceSequence, rec.MsgID)
			for _, size := range []uint64{uint64(len(migratedItemKey)), uint64(len(encoded)), uint64(len(migratedOrderKey))} {
				if err := addMailboxReservation(&mutationBytes, size); err != nil {
					return 0, 0, err
				}
			}
			if err := items.Put(migratedItemKey, encoded); err != nil {
				return 0, 0, err
			}
			if err := order.Put(migratedOrderKey, nil); err != nil {
				return 0, 0, err
			}
		}
		current = uint64(len(entries))
	}
	if current == ^uint64(0) {
		return 0, 0, errors.New("mailbox acceptance sequence exhausted")
	}
	next := current + 1
	var encoded [8]byte
	binary.BigEndian.PutUint64(encoded[:], next)
	if err := addMailboxReservation(&mutationBytes, uint64(len(recipient))); err != nil {
		return 0, 0, err
	}
	if err := addMailboxReservation(&mutationBytes, uint64(len(encoded))); err != nil {
		return 0, 0, err
	}
	if err := sequences.Put([]byte(recipient), encoded[:]); err != nil {
		return 0, 0, err
	}
	return next, mutationBytes, nil
}

func addMailboxReservation(total *uint64, size uint64) error {
	if total == nil || size > ^uint64(0)-*total {
		return errors.New("mailbox capacity reservation overflow")
	}
	*total += size
	return nil
}

func mailboxOrderValue(rec MailboxRecord) uint64 {
	if rec.AcceptanceSequence != 0 {
		return rec.AcceptanceSequence
	}
	return uint64(rec.AcceptedAt)
}

func orderKey(recipient string, sequence uint64, msgID string) []byte {
	key := make([]byte, 0, len(recipient)+1+8+1+len(msgID))
	key = append(key, recipient...)
	key = append(key, 0)
	var encodedSequence [8]byte
	binary.BigEndian.PutUint64(encodedSequence[:], sequence)
	key = append(key, encodedSequence[:]...)
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
