package store

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"go.etcd.io/bbolt"
)

// Bolt wraps a bbolt database with simple Put/Get/Delete operations.
type Bolt struct {
	db *bbolt.DB
}

// Snapshot writes a transactionally consistent Bolt copy to path and installs
// it atomically only after the temporary copy passes a full Bolt check.
func (b *Bolt) Snapshot(path string) error {
	if b == nil || b.db == nil || path == "" {
		return errors.New("snapshot database and path are required")
	}
	directory := filepath.Dir(path)
	info, err := os.Stat(directory)
	if err != nil {
		return fmt.Errorf("snapshot directory unavailable: %w", err)
	}
	if !info.IsDir() {
		return errors.New("snapshot parent must be a directory")
	}
	if _, err := os.Lstat(path); err == nil {
		return errors.New("snapshot target already exists")
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	temporary, err := os.CreateTemp(directory, "."+filepath.Base(path)+".tmp-")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	if err := temporary.Close(); err != nil {
		_ = os.Remove(temporaryPath)
		return err
	}
	defer os.Remove(temporaryPath)
	if err := b.db.View(func(tx *bbolt.Tx) error {
		return tx.CopyFile(temporaryPath, 0600)
	}); err != nil {
		return fmt.Errorf("copy Bolt snapshot: %w", err)
	}
	if err := os.Chmod(temporaryPath, 0600); err != nil {
		return err
	}
	if err := ValidateBolt(temporaryPath); err != nil {
		return fmt.Errorf("validate Bolt snapshot: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("install Bolt snapshot: %w", err)
	}
	return syncDirectory(directory)
}

// ValidateBolt opens path read-only and checks every reachable page and bucket.
func ValidateBolt(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return errors.New("Bolt path must be a regular non-symlink file")
	}
	database, err := bbolt.Open(path, 0400, &bbolt.Options{ReadOnly: true, Timeout: time.Second})
	if err != nil {
		return fmt.Errorf("open Bolt read-only: %w", err)
	}
	defer database.Close()
	return database.View(func(tx *bbolt.Tx) error {
		var firstError error
		for checkErr := range tx.Check() {
			if checkErr != nil && firstError == nil {
				firstError = checkErr
			}
		}
		return firstError
	})
}

func syncDirectory(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
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

// Update runs fn in a writable Bolt transaction.
func (b *Bolt) Update(fn func(*bbolt.Tx) error) error {
	return b.db.Update(fn)
}

// View runs fn in a read-only Bolt transaction.
func (b *Bolt) View(fn func(*bbolt.Tx) error) error {
	return b.db.View(fn)
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
