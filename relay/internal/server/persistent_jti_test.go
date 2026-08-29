package server

import (
	"bytes"
	"crypto/ed25519"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/twinotify/relay/internal/store"
	"go.etcd.io/bbolt"
)

func TestServerRejectsJWTReplayAfterBoltReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay.db")
	publicKey, privateKey, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	requestToken := "11111111-1111-4111-8111-111111111111"

	bolt, err := store.OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	firstServer, err := NewWithConfigChecked(bolt, DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	if err := firstServer.pairStore.Confirm(store.ConfirmedPair{
		PairID:      "pair-persistent-auth",
		DeviceA:     "persistent-device-a",
		DeviceB:     "persistent-device-b",
		AEncPubkey:  bytes.Repeat([]byte{1}, 32),
		ASignPubkey: publicKey,
		BEncPubkey:  bytes.Repeat([]byte{2}, 32),
		BSignPubkey: bytes.Repeat([]byte{3}, ed25519.PublicKeySize),
	}); err != nil {
		t.Fatal(err)
	}
	token := mintJWT(t, "persistent-device-a", privateKey, requestToken)

	firstStatus := authenticatedStatus(t, firstServer, token)
	if firstStatus != http.StatusNoContent {
		t.Fatalf("first request status = %d, want %d", firstStatus, http.StatusNoContent)
	}
	if err := bolt.Close(); err != nil {
		t.Fatal(err)
	}

	bolt, err = store.OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bolt.Close() })
	secondServer, err := NewWithConfigChecked(bolt, DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	if status := authenticatedStatus(t, secondServer, token); status != http.StatusUnauthorized {
		t.Fatalf("replay after server restart status = %d, want %d", status, http.StatusUnauthorized)
	}
}

func authenticatedStatus(t *testing.T, server *Server, token string) int {
	t.Helper()
	handler := server.authMiddleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	testServer := httptest.NewServer(handler)
	defer testServer.Close()
	req, err := http.NewRequest(http.MethodGet, testServer.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	return response.StatusCode
}

func TestPersistentJTIRejectsReplayAfterBoltReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay.db")
	config := JTICacheConfig{TTL: time.Minute, MaxEntries: 8, CleanupBatch: 2}
	now := time.Unix(10_000, 0)
	jti := "11111111-1111-4111-8111-111111111111"

	bolt, err := store.OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	cache, err := OpenPersistentJTICache(bolt, config)
	if err != nil {
		t.Fatal(err)
	}
	if err := cache.CheckAndSet(jti, now); err != nil {
		t.Fatalf("first admission: %v", err)
	}
	if err := bolt.Close(); err != nil {
		t.Fatal(err)
	}

	bolt, err = store.OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bolt.Close() })
	cache, err = OpenPersistentJTICache(bolt, config)
	if err != nil {
		t.Fatal(err)
	}
	if err := cache.CheckAndSet(jti, now.Add(time.Second)); !errors.Is(err, ErrJTIReplay) {
		t.Fatalf("replay after reopen = %v, want ErrJTIReplay", err)
	}
}

func TestPersistentJTICapacityCleanupIsBoundedAndStoresOnlyDigests(t *testing.T) {
	bolt, err := store.OpenBolt(filepath.Join(t.TempDir(), "relay.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bolt.Close() })
	cache, err := OpenPersistentJTICache(bolt, JTICacheConfig{
		TTL: time.Minute, MaxEntries: 2, CleanupBatch: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(20_000, 0)
	first := "11111111-1111-4111-8111-111111111111"
	second := "22222222-2222-4222-8222-222222222222"
	third := "33333333-3333-4333-8333-333333333333"
	if err := cache.CheckAndSet(first, now); err != nil {
		t.Fatal(err)
	}
	if err := cache.CheckAndSet(second, now.Add(time.Nanosecond)); err != nil {
		t.Fatal(err)
	}
	if err := cache.CheckAndSet(third, now); !errors.Is(err, ErrJTICapacity) {
		t.Fatalf("capacity error = %v, want ErrJTICapacity", err)
	}
	if inspected, err := cache.Cleanup(now.Add(2*time.Minute + time.Nanosecond)); err != nil || inspected != 1 {
		t.Fatalf("cleanup = %d, %v, want one bounded entry", inspected, err)
	}
	if err := cache.CheckAndSet(third, now.Add(2*time.Minute+2*time.Nanosecond)); err != nil {
		t.Fatalf("admit after cleanup: %v", err)
	}
	count, err := cache.EntryCount()
	if err != nil || count != 2 {
		t.Fatalf("entry count = %d, %v, want 2", count, err)
	}
	if err := bolt.View(func(tx *bbolt.Tx) error {
		entries := tx.Bucket([]byte(bucketAuthJTI))
		if entries == nil {
			t.Fatal("missing JTI entries bucket")
		}
		cursor := entries.Cursor()
		for key, _ := cursor.First(); key != nil; key, _ = cursor.Next() {
			if bytes.Contains(key, []byte(first)) || bytes.Contains(key, []byte(second)) || bytes.Contains(key, []byte(third)) {
				t.Fatal("persistent replay store exposed a raw JTI")
			}
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}
