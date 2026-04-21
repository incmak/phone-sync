package store

import (
	"time"

	"go.etcd.io/bbolt"
)

// Bolt wraps a bbolt database with simple Put/Get/Delete operations.
type Bolt struct {
	db *bbolt.DB
}

// OpenBolt opens (or creates) a bbolt database at path.
func OpenBolt(path string) (*Bolt, error) {
	db, err := bbolt.Open(path, 0600, &bbolt.Options{Timeout: 2 * time.Second})
	if err != nil {
		return nil, err
	}
	return &Bolt{db: db}, nil
}

// Close releases the database file handle.
func (b *Bolt) Close() error {
	return b.db.Close()
}

// Put stores value under bucket/key, creating the bucket if absent.
func (b *Bolt) Put(bucket, key string, value []byte) error {
	return b.db.Update(func(tx *bbolt.Tx) error {
		bkt, err := tx.CreateBucketIfNotExists([]byte(bucket))
		if err != nil {
			return err
		}
		return bkt.Put([]byte(key), value)
	})
}

// Get retrieves the value for bucket/key. Returns nil (no error) when the
// bucket or key does not exist.
func (b *Bolt) Get(bucket, key string) ([]byte, error) {
	var out []byte
	err := b.db.View(func(tx *bbolt.Tx) error {
		bkt := tx.Bucket([]byte(bucket))
		if bkt == nil {
			return nil
		}
		v := bkt.Get([]byte(key))
		if v != nil {
			out = append([]byte{}, v...) // copy: v is only valid within the tx
		}
		return nil
	})
	return out, err
}

// Delete removes key from bucket. No-ops when bucket or key is absent.
func (b *Bolt) Delete(bucket, key string) error {
	return b.db.Update(func(tx *bbolt.Tx) error {
		bkt := tx.Bucket([]byte(bucket))
		if bkt == nil {
			return nil
		}
		return bkt.Delete([]byte(key))
	})
}
