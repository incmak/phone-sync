package server

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"time"

	"github.com/twinotify/relay/internal/store"
	"go.etcd.io/bbolt"
)

const (
	bucketAuthJTI       = "auth_jti_v1"
	bucketAuthJTIExpiry = "auth_jti_expiry_v1"
	bucketAuthJTIMeta   = "auth_jti_meta_v1"
)

var authJTICountKey = []byte("count")

type PersistentJTICache struct {
	bolt   *store.Bolt
	config JTICacheConfig
}

func OpenPersistentJTICache(bolt *store.Bolt, config JTICacheConfig) (*PersistentJTICache, error) {
	if bolt == nil || config.TTL <= 0 || config.MaxEntries <= 0 || config.CleanupBatch <= 0 {
		return nil, errors.New("invalid persistent JTI config")
	}
	if err := bolt.Update(func(tx *bbolt.Tx) error {
		entries, err := tx.CreateBucketIfNotExists([]byte(bucketAuthJTI))
		if err != nil {
			return err
		}
		expiry, err := tx.CreateBucketIfNotExists([]byte(bucketAuthJTIExpiry))
		if err != nil {
			return err
		}
		meta, err := tx.CreateBucketIfNotExists([]byte(bucketAuthJTIMeta))
		if err != nil {
			return err
		}
		count, err := validatePersistentJTIIndexes(entries, expiry)
		if err != nil {
			return err
		}
		if count > uint64(config.MaxEntries) {
			return fmt.Errorf("persistent JTI count %d exceeds configured maximum %d", count, config.MaxEntries)
		}
		return meta.Put(authJTICountKey, encodeJTICount(count))
	}); err != nil {
		return nil, fmt.Errorf("initialize persistent JTI store: %w", err)
	}
	return &PersistentJTICache{bolt: bolt, config: config}, nil
}

func (c *PersistentJTICache) CheckAndSet(jti string, now time.Time) error {
	digest := sha256.Sum256([]byte(jti))
	return c.bolt.Update(func(tx *bbolt.Tx) error {
		entries, expiry, meta, err := persistentJTIBuckets(tx)
		if err != nil {
			return err
		}
		count, err := decodeJTICount(meta.Get(authJTICountKey))
		if err != nil {
			return err
		}
		if raw := entries.Get(digest[:]); raw != nil {
			expiresAt, err := decodeJTIExpiry(raw)
			if err != nil {
				return err
			}
			indexKey := persistentJTIExpiryKey(expiresAt, digest[:])
			if !bytes.Equal(expiry.Get(indexKey), digest[:]) {
				return errors.New("persistent JTI expiry index mismatch")
			}
			if !now.After(time.Unix(0, expiresAt)) {
				return ErrJTIReplay
			}
			if count == 0 {
				return errors.New("invalid persistent JTI count")
			}
			if err := entries.Delete(digest[:]); err != nil {
				return err
			}
			if err := expiry.Delete(indexKey); err != nil {
				return err
			}
			count--
		}
		cursor := expiry.Cursor()
		for count >= uint64(c.config.MaxEntries) {
			indexKey, indexedDigest := cursor.First()
			if indexKey == nil {
				break
			}
			expiresAt, err := persistentJTIExpiryTime(indexKey, indexedDigest)
			if err != nil {
				return err
			}
			if !now.After(time.Unix(0, expiresAt)) {
				break
			}
			if count == 0 || !bytes.Equal(entries.Get(indexedDigest), encodeJTIExpiry(expiresAt)) {
				return errors.New("persistent JTI expiry entry is orphaned")
			}
			if err := entries.Delete(indexedDigest); err != nil {
				return err
			}
			if err := cursor.Delete(); err != nil {
				return err
			}
			count--
		}
		if count >= uint64(c.config.MaxEntries) {
			return ErrJTICapacity
		}
		expiresAt := now.Add(2 * c.config.TTL).UnixNano()
		if err := entries.Put(digest[:], encodeJTIExpiry(expiresAt)); err != nil {
			return err
		}
		if err := expiry.Put(persistentJTIExpiryKey(expiresAt, digest[:]), digest[:]); err != nil {
			return err
		}
		return meta.Put(authJTICountKey, encodeJTICount(count+1))
	})
}

func (c *PersistentJTICache) Cleanup(now time.Time) (int, error) {
	removed := 0
	err := c.bolt.Update(func(tx *bbolt.Tx) error {
		entries, expiry, meta, err := persistentJTIBuckets(tx)
		if err != nil {
			return err
		}
		count, err := decodeJTICount(meta.Get(authJTICountKey))
		if err != nil {
			return err
		}
		cursor := expiry.Cursor()
		for removed < c.config.CleanupBatch {
			indexKey, digest := cursor.First()
			if indexKey == nil {
				break
			}
			expiresAt, err := persistentJTIExpiryTime(indexKey, digest)
			if err != nil {
				return err
			}
			if !now.After(time.Unix(0, expiresAt)) {
				break
			}
			if count == 0 || !bytes.Equal(entries.Get(digest), encodeJTIExpiry(expiresAt)) {
				return errors.New("persistent JTI expiry entry is orphaned")
			}
			if err := entries.Delete(digest); err != nil {
				return err
			}
			if err := cursor.Delete(); err != nil {
				return err
			}
			count--
			removed++
		}
		return meta.Put(authJTICountKey, encodeJTICount(count))
	})
	return removed, err
}

func (c *PersistentJTICache) EntryCount() (int, error) {
	count := 0
	err := c.bolt.View(func(tx *bbolt.Tx) error {
		_, _, meta, err := persistentJTIBuckets(tx)
		if err != nil {
			return err
		}
		value, err := decodeJTICount(meta.Get(authJTICountKey))
		if err != nil {
			return err
		}
		count = int(value)
		return nil
	})
	return count, err
}

func persistentJTIBuckets(tx *bbolt.Tx) (*bbolt.Bucket, *bbolt.Bucket, *bbolt.Bucket, error) {
	entries := tx.Bucket([]byte(bucketAuthJTI))
	expiry := tx.Bucket([]byte(bucketAuthJTIExpiry))
	meta := tx.Bucket([]byte(bucketAuthJTIMeta))
	if entries == nil || expiry == nil || meta == nil {
		return nil, nil, nil, errors.New("missing persistent JTI bucket")
	}
	return entries, expiry, meta, nil
}

func validatePersistentJTIIndexes(entries, expiry *bbolt.Bucket) (uint64, error) {
	var count uint64
	entryCursor := entries.Cursor()
	for digest, raw := entryCursor.First(); digest != nil; digest, raw = entryCursor.Next() {
		if len(digest) != sha256.Size {
			return 0, errors.New("invalid persistent JTI digest")
		}
		expiresAt, err := decodeJTIExpiry(raw)
		if err != nil {
			return 0, err
		}
		if !bytes.Equal(expiry.Get(persistentJTIExpiryKey(expiresAt, digest)), digest) {
			return 0, errors.New("missing persistent JTI expiry entry")
		}
		count++
	}
	expiryCursor := expiry.Cursor()
	for indexKey, digest := expiryCursor.First(); indexKey != nil; indexKey, digest = expiryCursor.Next() {
		expiresAt, err := persistentJTIExpiryTime(indexKey, digest)
		if err != nil {
			return 0, err
		}
		if !bytes.Equal(entries.Get(digest), encodeJTIExpiry(expiresAt)) {
			return 0, errors.New("orphaned persistent JTI expiry entry")
		}
	}
	return count, nil
}

func persistentJTIExpiryKey(expiresAt int64, digest []byte) []byte {
	key := make([]byte, 8+len(digest))
	binary.BigEndian.PutUint64(key[:8], uint64(expiresAt))
	copy(key[8:], digest)
	return key
}

func persistentJTIExpiryTime(key, digest []byte) (int64, error) {
	if len(key) != 8+sha256.Size || len(digest) != sha256.Size || !bytes.Equal(key[8:], digest) {
		return 0, errors.New("invalid persistent JTI expiry key")
	}
	return int64(binary.BigEndian.Uint64(key[:8])), nil
}

func encodeJTIExpiry(expiresAt int64) []byte {
	raw := make([]byte, 8)
	binary.BigEndian.PutUint64(raw, uint64(expiresAt))
	return raw
}

func decodeJTIExpiry(raw []byte) (int64, error) {
	if len(raw) != 8 {
		return 0, errors.New("invalid persistent JTI expiry")
	}
	return int64(binary.BigEndian.Uint64(raw)), nil
}

func encodeJTICount(count uint64) []byte {
	raw := make([]byte, 8)
	binary.BigEndian.PutUint64(raw, count)
	return raw
}

func decodeJTICount(raw []byte) (uint64, error) {
	if len(raw) != 8 {
		return 0, errors.New("invalid persistent JTI count")
	}
	return binary.BigEndian.Uint64(raw), nil
}
