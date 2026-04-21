# Phase 2 — Crypto + Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Lock in the security foundation — Keystore-wrapped libsodium keys on Android, relay-side pair registry with JWT auth bound to stored `sign_pubkey`, two-sided QR pairing with fingerprint confirmation, and replay-protected envelopes. After Phase 2 the relay authenticates only paired devices and every message is E2EE ciphertext.

**Architecture:** Three components grow in tandem — relay gains `POST /pair/init`, `POST /pair/complete`, WebSocket JWT auth middleware, BoltDB pair registry, seen-JTI cache. Android gains libsodium (via Lazysodium-Android), Keystore-wrapped key persistence (DataStore + Tink), E2EE encrypt/decrypt helpers, pairing QR generator/scanner, JWT minter, replay-protection local table. Desktop Tauri deferred — this phase is Android ↔ relay only; Phase 3 starts using the crypto for real notification payloads.

**Tech Stack additions:**
- Android: `com.goterl:lazysodium-android:5.2.0@aar`, `net.java.dev.jna:jna:5.14.0@aar`, `androidx.datastore:datastore:1.1.1`, `com.google.crypto.tink:tink-android:1.14.0`, `androidx.security:security-crypto` (NOT used — spec rejects), `com.google.zxing:core:3.5.3` + `com.journeyapps:zxing-android-embedded:4.3.0` for QR, `com.auth0:java-jwt:4.4.0` or hand-rolled Ed25519 JWT, Room already in dep tree from Phase 1 (add now if absent).
- Relay: `github.com/golang-jwt/jwt/v5`, `golang.org/x/crypto/ed25519` (stdlib), reuse bbolt wrapper for pair registry + JTI cache.
- Desktop: nothing in this phase.

**Spec reference:** `docs/superpowers/specs/2026-04-20-phone-sync-design.md` (v9) — §4.4 RelayTransport (JWT + jti), §4.6 Crypto (Keystore-wrap + counter nonce), §4.7 Pairing (two-sided), §5.1 relay pair endpoints, §6.2 threat model.

**Out of scope (this phase):** NotificationListenerService, POST_NOTIFICATIONS runtime permission, NotificationManager.notify() posting, loop avoidance, FCM, LAN transport, reply bridge, icon cache, desktop client. Those ship in Phase 3+.

---

## File Structure Additions

```text
phone-sync/
├── relay/
│   ├── internal/server/
│   │   ├── pair.go              # POST /pair/init + /pair/complete
│   │   ├── pair_test.go
│   │   ├── jwt_auth.go          # JWT middleware, seen-jti cache
│   │   ├── jwt_auth_test.go
│   │   └── ws.go                # modify to require auth
│   ├── internal/store/
│   │   ├── pair_store.go        # BoltDB pair registry
│   │   └── pair_store_test.go
│   └── cmd/relay/main.go        # wire pair store + JWT middleware
├── mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/
│   ├── crypto/
│   │   ├── KeystoreMaster.kt    # AES-256-GCM master key in Keystore
│   │   ├── WrappedKeys.kt       # sealed libsodium X25519 + Ed25519 keypairs
│   │   ├── NonceSource.kt       # counter + random prefix
│   │   ├── Encrypter.kt         # crypto_box_easy wrapper
│   │   └── CryptoStore.kt       # DataStore + Tink persistence
│   ├── pairing/
│   │   ├── PairProtocol.kt      # POST /pair/init + complete client
│   │   ├── PairPayload.kt       # QR payload schema
│   │   └── Fingerprint.kt       # SHA-256(enc_pubkey || sign_pubkey) formatter
│   ├── auth/
│   │   └── JwtMinter.kt         # Ed25519-signed JWT with jti
│   ├── storage/
│   │   └── ReplayGuard.kt       # seen msg_id dedup (Room/DataStore)
│   └── PhoneSyncCoreModule.kt   # expose pair/encrypt/decrypt to JS
└── proto/
    ├── pair-init.schema.json    # pair_init request body
    ├── pair-complete.schema.json
    └── envelope-encrypted.schema.json  # ciphertext envelope wrapping
```

---

## Task 1: Relay BoltDB pair registry (store layer)

**Files:**

- Create: `relay/internal/store/pair_store.go`
- Create: `relay/internal/store/pair_store_test.go`

- [ ] **Step 1: Write failing test for pair registry roundtrip**

`pair_store_test.go`:

```go
package store

import (
	"path/filepath"
	"testing"
	"time"
)

func TestPairStore_InitAndComplete(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenBolt(filepath.Join(dir, "pair.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer s.Close()

	ps := NewPairStore(s)
	rec := PendingPair{
		PairToken:   "tok-abc",
		DeviceAID:   "devA",
		AEncPubkey:  []byte("A-enc"),
		ASignPubkey: []byte("A-sign"),
		CreatedAt:   time.Now().Unix(),
	}
	if err := ps.PutPending(rec); err != nil {
		t.Fatalf("put pending: %v", err)
	}
	got, err := ps.GetPending("tok-abc")
	if err != nil {
		t.Fatalf("get pending: %v", err)
	}
	if got.DeviceAID != "devA" {
		t.Fatalf("expected devA got %q", got.DeviceAID)
	}
	confirmed := ConfirmedPair{
		PairID:      "pair-1",
		DeviceA:     "devA",
		DeviceB:     "devB",
		AEncPubkey:  []byte("A-enc"),
		ASignPubkey: []byte("A-sign"),
		BEncPubkey:  []byte("B-enc"),
		BSignPubkey: []byte("B-sign"),
	}
	if err := ps.Confirm(confirmed); err != nil {
		t.Fatalf("confirm: %v", err)
	}
	cp, err := ps.Get(confirmed.PairID)
	if err != nil || cp.DeviceB != "devB" {
		t.Fatalf("get confirmed: %v got=%+v", err, cp)
	}
	if _, err := ps.GetPending("tok-abc"); err != ErrNotFound {
		t.Fatalf("pending should be gone after confirm, got %v", err)
	}
}

func TestPairStore_LookupSignPubkey(t *testing.T) {
	dir := t.TempDir()
	s, _ := OpenBolt(filepath.Join(dir, "p.db"))
	defer s.Close()
	ps := NewPairStore(s)
	_ = ps.Confirm(ConfirmedPair{
		PairID: "p1", DeviceA: "A", DeviceB: "B",
		AEncPubkey: []byte{1}, ASignPubkey: []byte{2},
		BEncPubkey: []byte{3}, BSignPubkey: []byte{4},
	})
	pk, err := ps.SignPubkeyFor("A")
	if err != nil || pk[0] != 2 {
		t.Fatalf("lookup A sign: %v %v", err, pk)
	}
	pk, err = ps.SignPubkeyFor("B")
	if err != nil || pk[0] != 4 {
		t.Fatalf("lookup B sign: %v %v", err, pk)
	}
}
```

Run: `cd relay && go test ./internal/store/ -run TestPairStore`. Expect compile fail.

- [ ] **Step 2: Implement `pair_store.go`**

```go
package store

import (
	"encoding/json"
	"errors"
	"fmt"
)

var ErrNotFound = errors.New("not found")

const (
	bucketPending   = "pair_pending"
	bucketConfirmed = "pair_confirmed"
	bucketByDevice  = "device_to_pair" // device_id → pair_id
)

type PendingPair struct {
	PairToken   string `json:"pair_token"`
	DeviceAID   string `json:"device_a_id"`
	AEncPubkey  []byte `json:"a_enc_pubkey"`
	ASignPubkey []byte `json:"a_sign_pubkey"`
	CreatedAt   int64  `json:"created_at"`
}

type ConfirmedPair struct {
	PairID      string `json:"pair_id"`
	DeviceA     string `json:"device_a"`
	DeviceB     string `json:"device_b"`
	AEncPubkey  []byte `json:"a_enc_pubkey"`
	ASignPubkey []byte `json:"a_sign_pubkey"`
	BEncPubkey  []byte `json:"b_enc_pubkey"`
	BSignPubkey []byte `json:"b_sign_pubkey"`
}

type PairStore struct {
	bolt *Bolt
}

func NewPairStore(b *Bolt) *PairStore {
	return &PairStore{bolt: b}
}

func (ps *PairStore) PutPending(p PendingPair) error {
	b, err := json.Marshal(p)
	if err != nil {
		return err
	}
	return ps.bolt.Put(bucketPending, p.PairToken, b)
}

func (ps *PairStore) GetPending(token string) (*PendingPair, error) {
	b, err := ps.bolt.Get(bucketPending, token)
	if err != nil {
		return nil, err
	}
	if b == nil {
		return nil, ErrNotFound
	}
	var p PendingPair
	if err := json.Unmarshal(b, &p); err != nil {
		return nil, fmt.Errorf("unmarshal pending: %w", err)
	}
	return &p, nil
}

func (ps *PairStore) Confirm(cp ConfirmedPair) error {
	b, err := json.Marshal(cp)
	if err != nil {
		return err
	}
	if err := ps.bolt.Put(bucketConfirmed, cp.PairID, b); err != nil {
		return err
	}
	if err := ps.bolt.Put(bucketByDevice, cp.DeviceA, []byte(cp.PairID)); err != nil {
		return err
	}
	if err := ps.bolt.Put(bucketByDevice, cp.DeviceB, []byte(cp.PairID)); err != nil {
		return err
	}
	// Remove any pending by iterating is expensive; caller passes token to DeletePending.
	return nil
}

func (ps *PairStore) DeletePending(token string) error {
	return ps.bolt.Delete(bucketPending, token)
}

func (ps *PairStore) Get(pairID string) (*ConfirmedPair, error) {
	b, err := ps.bolt.Get(bucketConfirmed, pairID)
	if err != nil {
		return nil, err
	}
	if b == nil {
		return nil, ErrNotFound
	}
	var cp ConfirmedPair
	if err := json.Unmarshal(b, &cp); err != nil {
		return nil, err
	}
	return &cp, nil
}

func (ps *PairStore) SignPubkeyFor(deviceID string) ([]byte, error) {
	pairIDBytes, err := ps.bolt.Get(bucketByDevice, deviceID)
	if err != nil {
		return nil, err
	}
	if pairIDBytes == nil {
		return nil, ErrNotFound
	}
	cp, err := ps.Get(string(pairIDBytes))
	if err != nil {
		return nil, err
	}
	if cp.DeviceA == deviceID {
		return cp.ASignPubkey, nil
	}
	if cp.DeviceB == deviceID {
		return cp.BSignPubkey, nil
	}
	return nil, ErrNotFound
}
```

Adjust the test's `Confirm` flow to explicitly call `DeletePending("tok-abc")` after `Confirm` (since the spec above separates concerns). Update the test accordingly — the test should call:

```go
_ = ps.Confirm(confirmed)
_ = ps.DeletePending("tok-abc")
if _, err := ps.GetPending("tok-abc"); err != ErrNotFound { ... }
```

- [ ] **Step 3: Run tests**

```bash
cd relay && go test ./internal/store/ -race -count=1 -v
```

- [ ] **Step 4: Commit**

```bash
git add relay/internal/store/
git commit -m "feat(relay): BoltDB pair registry (pending, confirmed, device→pair index)"
```

---

## Task 2: Relay `/pair/init` and `/pair/complete` endpoints

**Files:**

- Create: `relay/internal/server/pair.go`
- Create: `relay/internal/server/pair_test.go`
- Modify: `relay/internal/server/server.go` (inject PairStore, register routes)
- Modify: `relay/cmd/relay/main.go` (open BoltDB, pass PairStore to server)
- Create: `proto/pair-init.schema.json`, `proto/pair-complete.schema.json`

- [ ] **Step 1: Proto schemas**

`proto/pair-init.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://phone-sync.local/schemas/pair-init.schema.json",
  "type": "object",
  "required": ["pair_token", "device_id", "enc_pubkey", "sign_pubkey"],
  "properties": {
    "pair_token": { "type": "string", "minLength": 16 },
    "device_id": { "type": "string", "minLength": 1 },
    "enc_pubkey": { "type": "string", "contentEncoding": "base64" },
    "sign_pubkey": { "type": "string", "contentEncoding": "base64" }
  }
}
```

`proto/pair-complete.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://phone-sync.local/schemas/pair-complete.schema.json",
  "type": "object",
  "required": ["pair_token", "device_id", "enc_pubkey", "sign_pubkey", "confirmation_sig"],
  "properties": {
    "pair_token": { "type": "string" },
    "device_id": { "type": "string" },
    "enc_pubkey": { "type": "string", "contentEncoding": "base64" },
    "sign_pubkey": { "type": "string", "contentEncoding": "base64" },
    "confirmation_sig": {
      "type": "string",
      "contentEncoding": "base64",
      "description": "Ed25519 sig by Device A over pair_token || A_enc || A_sign || B_enc || B_sign"
    }
  }
}
```

Run `make sync-proto` after creating.

- [ ] **Step 2: Write failing endpoint tests**

`relay/internal/server/pair_test.go`:

```go
package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPairInitAndComplete(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	initBody := map[string]any{
		"pair_token":  "tok-01234567890123456789",
		"device_id":   "devA",
		"enc_pubkey":  base64.StdEncoding.EncodeToString([]byte("Aenc-32bytes-placeholder00000000")),
		"sign_pubkey": base64.StdEncoding.EncodeToString([]byte("Asig-32bytes-placeholder00000000")),
	}
	body, _ := json.Marshal(initBody)
	resp, err := http.Post(ts.URL+"/pair/init", "application/json", bytes.NewReader(body))
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("init: %v status=%d", err, resp.StatusCode)
	}

	// Now /pair/complete. confirmation_sig validation is deferred to Step 4; send a placeholder.
	completeBody := map[string]any{
		"pair_token":       "tok-01234567890123456789",
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString([]byte("Benc-32bytes-placeholder00000000")),
		"sign_pubkey":      base64.StdEncoding.EncodeToString([]byte("Bsig-32bytes-placeholder00000000")),
		"confirmation_sig": base64.StdEncoding.EncodeToString([]byte("skip-for-test")),
	}
	cb, _ := json.Marshal(completeBody)
	resp2, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(cb))
	if err != nil || resp2.StatusCode != 200 {
		t.Fatalf("complete: %v status=%d", err, resp2.StatusCode)
	}

	// Confirm the pair exists
	ps := srv.pairStore
	pk, err := ps.SignPubkeyFor("devB")
	if err != nil || len(pk) == 0 {
		t.Fatalf("sign pubkey for devB: %v", err)
	}
}

func TestPairComplete_InvalidToken(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	completeBody := map[string]any{
		"pair_token":       "does-not-exist-token-padding123",
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString([]byte("Benc-32bytes-placeholder00000000")),
		"sign_pubkey":      base64.StdEncoding.EncodeToString([]byte("Bsig-32bytes-placeholder00000000")),
		"confirmation_sig": base64.StdEncoding.EncodeToString([]byte("nope")),
	}
	cb, _ := json.Marshal(completeBody)
	resp, _ := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(cb))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp.StatusCode)
	}
}
```

The test references `newTestServer(t)` and `srv.pairStore`. Add a helper at the top of pair_test.go that opens an in-temp-dir BoltDB and constructs a `*Server` with the PairStore injected:

```go
func newTestServer(t *testing.T) *Server {
	t.Helper()
	dir := t.TempDir()
	b, err := store.OpenBolt(dir + "/relay.db")
	if err != nil {
		t.Fatalf("open bolt: %v", err)
	}
	t.Cleanup(func() { _ = b.Close() })
	return NewWithStore(b)
}
```

Import `github.com/phonesync/relay/internal/store`.

- [ ] **Step 3: Implement pair handlers + refactor `Server` constructor to take a `*Bolt`**

Modify `server.go`:

```go
type Server struct {
	router    *chi.Mux
	validator *Validator
	pairStore *store.PairStore
	jtiCache  *JTICache // added in Task 3
}

func NewWithStore(b *store.Bolt) *Server {
	v, err := NewValidator()
	if err != nil {
		panic(err)
	}
	s := &Server{
		router:    chi.NewRouter(),
		validator: v,
		pairStore: store.NewPairStore(b),
		jtiCache:  NewJTICache(60 * time.Second),
	}
	s.routes()
	return s
}

// Keep New() as a convenience for Phase 1 code paths — but real main.go uses NewWithStore.
func New() *Server {
	return NewWithStore(mustOpenDefaultBolt())
}

func mustOpenDefaultBolt() *store.Bolt {
	path := os.Getenv("BOLT_PATH")
	if path == "" {
		path = "/tmp/phone-sync-relay.db"
	}
	b, err := store.OpenBolt(path)
	if err != nil {
		panic(err)
	}
	return b
}
```

Register in `routes()`:

```go
s.router.Post("/pair/init", s.handlePairInit)
s.router.Post("/pair/complete", s.handlePairComplete)
```

Create `pair.go`:

```go
package server

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/phonesync/relay/internal/store"
)

type pairInitReq struct {
	PairToken  string `json:"pair_token"`
	DeviceID   string `json:"device_id"`
	EncPubkey  string `json:"enc_pubkey"`
	SignPubkey string `json:"sign_pubkey"`
}

type pairCompleteReq struct {
	PairToken       string `json:"pair_token"`
	DeviceID        string `json:"device_id"`
	EncPubkey       string `json:"enc_pubkey"`
	SignPubkey      string `json:"sign_pubkey"`
	ConfirmationSig string `json:"confirmation_sig"`
}

func (s *Server) handlePairInit(w http.ResponseWriter, r *http.Request) {
	var req pairInitReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if req.PairToken == "" || req.DeviceID == "" || req.EncPubkey == "" || req.SignPubkey == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}
	encPk, err1 := base64.StdEncoding.DecodeString(req.EncPubkey)
	signPk, err2 := base64.StdEncoding.DecodeString(req.SignPubkey)
	if err1 != nil || err2 != nil {
		http.Error(w, "bad base64", http.StatusBadRequest)
		return
	}
	p := store.PendingPair{
		PairToken:   req.PairToken,
		DeviceAID:   req.DeviceID,
		AEncPubkey:  encPk,
		ASignPubkey: signPk,
		CreatedAt:   time.Now().Unix(),
	}
	if err := s.pairStore.PutPending(p); err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"pending"}`))
}

func (s *Server) handlePairComplete(w http.ResponseWriter, r *http.Request) {
	var req pairCompleteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	pending, err := s.pairStore.GetPending(req.PairToken)
	if err != nil {
		http.Error(w, "unknown pair_token", http.StatusBadRequest)
		return
	}
	if time.Now().Unix()-pending.CreatedAt > 300 {
		_ = s.pairStore.DeletePending(req.PairToken)
		http.Error(w, "token expired", http.StatusBadRequest)
		return
	}
	encPk, err1 := base64.StdEncoding.DecodeString(req.EncPubkey)
	signPk, err2 := base64.StdEncoding.DecodeString(req.SignPubkey)
	if err1 != nil || err2 != nil {
		http.Error(w, "bad base64", http.StatusBadRequest)
		return
	}
	// TODO(phase-2-hardening): verify req.ConfirmationSig is a valid Ed25519 signature
	// by pending.ASignPubkey over pair_token || A_enc || A_sign || B_enc || B_sign.
	// Deferred to Task 4 where Ed25519 verify helper lands.

	cp := store.ConfirmedPair{
		PairID:      uuid.NewString(),
		DeviceA:     pending.DeviceAID,
		DeviceB:     req.DeviceID,
		AEncPubkey:  pending.AEncPubkey,
		ASignPubkey: pending.ASignPubkey,
		BEncPubkey:  encPk,
		BSignPubkey: signPk,
	}
	if err := s.pairStore.Confirm(cp); err != nil {
		http.Error(w, "confirm", http.StatusInternalServerError)
		return
	}
	_ = s.pairStore.DeletePending(req.PairToken)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"pair_id":"` + cp.PairID + `"}`))
}
```

Add `github.com/google/uuid` via `go get github.com/google/uuid`.

Update `relay/cmd/relay/main.go` to use `NewWithStore`:

```go
func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	path := os.Getenv("BOLT_PATH")
	if path == "" {
		path = "/data/relay.db"
	}
	b, err := store.OpenBolt(path)
	if err != nil {
		log.Fatal(err)
	}
	defer b.Close()
	srv := &http.Server{Addr: addr, Handler: server.NewWithStore(b).Handler()}
	// graceful shutdown as in Phase 1 ...
}
```

- [ ] **Step 4: Run tests**

```bash
cd relay && go mod tidy && go vet ./... && go test -race ./... -count=1
```

- [ ] **Step 5: Commit**

```bash
git add relay/ proto/
git commit -m "feat(relay): POST /pair/init + /pair/complete with BoltDB persistence"
```

---

## Task 3: JWT auth middleware + JTI replay cache

**Files:**

- Create: `relay/internal/server/jwt_auth.go`
- Create: `relay/internal/server/jwt_auth_test.go`
- Modify: `relay/internal/server/server.go` (wire middleware on /ws)
- Modify: `relay/internal/server/ws.go` (no body change — middleware handles auth before upgrade)

- [ ] **Step 1: Add JWT library**

```bash
cd relay && go get github.com/golang-jwt/jwt/v5
```

- [ ] **Step 2: Failing test**

`jwt_auth_test.go`:

```go
package server

import (
	"crypto/ed25519"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/phonesync/relay/internal/store"
)

// helper: register a pair, return (deviceID, signKey)
func registerPair(t *testing.T, ps *store.PairStore) (string, ed25519.PrivateKey) {
	pub, priv, _ := ed25519.GenerateKey(nil)
	_ = ps.Confirm(store.ConfirmedPair{
		PairID: "p1", DeviceA: "devA", DeviceB: "devB",
		AEncPubkey: []byte{1}, ASignPubkey: pub, BEncPubkey: []byte{2}, BSignPubkey: []byte{3},
	})
	return "devA", priv
}

func mintJWT(t *testing.T, deviceID string, priv ed25519.PrivateKey) string {
	tok := jwt.NewWithClaims(jwt.SigningMethodEdDSA, jwt.MapClaims{
		"sub": deviceID,
		"jti": uuid.NewString(),
		"exp": time.Now().Add(60 * time.Second).Unix(),
	})
	s, err := tok.SignedString(priv)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

func TestWebSocketRequiresAuth(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got err=%v status=%d", err, resp.StatusCode)
	}
}

func TestWebSocketValidJWT(t *testing.T) {
	srv := newTestServer(t)
	deviceID, priv := registerPair(t, srv.pairStore)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, priv))
	c, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
}

func TestJTIReplayRejected(t *testing.T) {
	srv := newTestServer(t)
	deviceID, priv := registerPair(t, srv.pairStore)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"

	token := mintJWT(t, deviceID, priv)
	header := http.Header{}
	header.Set("Authorization", "Bearer "+token)

	c1, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatalf("first dial: %v", err)
	}
	c1.Close()

	// Replay exact same token
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected replay reject, got err=%v status=%v", err, resp)
	}
}

// silence unused imports
var _ = base64.StdEncoding
```

- [ ] **Step 3: Implement middleware + cache**

`jwt_auth.go`:

```go
package server

import (
	"crypto/ed25519"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/phonesync/relay/internal/store"
)

type JTICache struct {
	mu      sync.Mutex
	seen    map[string]time.Time
	ttl     time.Duration
	lastGC  time.Time
}

func NewJTICache(ttl time.Duration) *JTICache {
	return &JTICache{seen: map[string]time.Time{}, ttl: ttl}
}

func (c *JTICache) CheckAndSet(jti string, now time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if now.Sub(c.lastGC) > c.ttl {
		for k, t := range c.seen {
			if now.Sub(t) > c.ttl {
				delete(c.seen, k)
			}
		}
		c.lastGC = now
	}
	if _, ok := c.seen[jti]; ok {
		return errors.New("jti replay")
	}
	c.seen[jti] = now
	return nil
}

func (s *Server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if !strings.HasPrefix(authHeader, "Bearer ") {
			http.Error(w, "missing bearer", http.StatusUnauthorized)
			return
		}
		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")

		// Parse without verification first to extract sub (device_id)
		parser := jwt.NewParser()
		unverified, _, err := parser.ParseUnverified(tokenStr, jwt.MapClaims{})
		if err != nil {
			http.Error(w, "bad token", http.StatusUnauthorized)
			return
		}
		claims, ok := unverified.Claims.(jwt.MapClaims)
		if !ok {
			http.Error(w, "bad claims", http.StatusUnauthorized)
			return
		}
		sub, _ := claims["sub"].(string)
		if sub == "" {
			http.Error(w, "missing sub", http.StatusUnauthorized)
			return
		}

		signPk, err := s.pairStore.SignPubkeyFor(sub)
		if err != nil || len(signPk) != ed25519.PublicKeySize {
			http.Error(w, "unknown device", http.StatusUnauthorized)
			return
		}

		// Verify signature with stored pubkey
		_, err = jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
			if t.Method.Alg() != "EdDSA" {
				return nil, errors.New("bad alg")
			}
			return ed25519.PublicKey(signPk), nil
		})
		if err != nil {
			http.Error(w, "invalid signature or expired", http.StatusUnauthorized)
			return
		}

		jti, _ := claims["jti"].(string)
		if jti == "" {
			http.Error(w, "missing jti", http.StatusUnauthorized)
			return
		}
		if err := s.jtiCache.CheckAndSet(jti, time.Now()); err != nil {
			http.Error(w, "jti replay", http.StatusUnauthorized)
			return
		}

		// Inject device_id into context for handler use later
		r = r.WithContext(withDeviceID(r.Context(), sub))
		next.ServeHTTP(w, r)
	})
}

// placeholder — expand with actual context-key pattern
type ctxKey string

const ctxDeviceID ctxKey = "device_id"

func withDeviceID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, ctxDeviceID, id)
}

func DeviceIDFromContext(ctx context.Context) (string, bool) {
	id, ok := ctx.Value(ctxDeviceID).(string)
	return id, ok
}
```

Add `import "context"` where needed.

- [ ] **Step 4: Wire middleware on /ws**

Modify `routes()` in server.go:

```go
s.router.With(s.authMiddleware).Get("/ws", s.handleWebSocket)
```

Update Phase 1 tests (`server_test.go` `TestWebSocketEcho` etc.) to mint a valid JWT before dialing. This WILL break those tests — update them to register a pair and mint a JWT like the new tests do. Extract a `dialWSAuthed(t, ts, srv)` helper.

- [ ] **Step 5: Run all tests**

```bash
cd relay && go vet ./... && go test -race ./... -count=1
```

Expected: all pass, including the updated Phase 1 WS tests.

- [ ] **Step 6: Commit**

```bash
git add relay/ proto/
git commit -m "feat(relay): JWT auth middleware on /ws with jti replay cache"
```

---

## Task 4: Verify pair-complete confirmation signature

**Files:**

- Modify: `relay/internal/server/pair.go` (enforce confirmation_sig)
- Modify: `relay/internal/server/pair_test.go` (update placeholder sig to real Ed25519)

- [ ] **Step 1: Failing test**

Update the `TestPairInitAndComplete` test to generate a real Ed25519 signing keypair for Device A, sign `pair_token || A_enc || A_sign || B_enc || B_sign`, and include that sig in the complete request. Also add a negative test with a wrong signature.

- [ ] **Step 2: Implement verification in `handlePairComplete`**

Replace the `// TODO(phase-2-hardening)` block with:

```go
msg := append([]byte(req.PairToken), pending.AEncPubkey...)
msg = append(msg, pending.ASignPubkey...)
msg = append(msg, encPk...)
msg = append(msg, signPk...)
sig, err := base64.StdEncoding.DecodeString(req.ConfirmationSig)
if err != nil || !ed25519.Verify(pending.ASignPubkey, msg, sig) {
	http.Error(w, "invalid confirmation signature", http.StatusBadRequest)
	return
}
```

- [ ] **Step 3: Run + commit**

```bash
cd relay && go test -race ./internal/server/ -run TestPair -count=1 -v
git add relay/
git commit -m "feat(relay): enforce confirmation_sig on /pair/complete"
```

---

## Task 5: Android — Keystore master key + wrapped libsodium keypairs

**Files (all in `mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/crypto/`):**

- `KeystoreMaster.kt`
- `WrappedKeys.kt`
- `CryptoStore.kt`
- Modify: `mobile/modules/phone-sync-core/android/build.gradle` (add lazysodium + datastore + tink)

- [ ] **Step 1: Add deps**

Edit `android/build.gradle` dependencies:

```gradle
implementation 'com.goterl:lazysodium-android:5.2.0@aar'
implementation 'net.java.dev.jna:jna:5.14.0@aar'
implementation 'androidx.datastore:datastore:1.1.1'
implementation 'com.google.crypto.tink:tink-android:1.14.0'
```

- [ ] **Step 2: `KeystoreMaster.kt`**

```kotlin
package expo.modules.phonesynccore.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.security.KeyStore

object KeystoreMaster {
    private const val KEYSTORE_NAME = "AndroidKeyStore"
    private const val ALIAS_MASTER = "phonesync.master"

    fun getOrCreate(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (ks.getKey(ALIAS_MASTER, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            ALIAS_MASTER,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .apply {
                try {
                    setIsStrongBoxBacked(true)
                } catch (_: NoSuchMethodError) { /* API level too low */ }
            }
            .build()

        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME).run {
                init(spec); generateKey()
            }
        } catch (_: StrongBoxUnavailableException) {
            // Retry without StrongBox
            val fallback = KeyGenParameterSpec.Builder(
                ALIAS_MASTER,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME).run {
                init(fallback); generateKey()
            }
        }
    }
}
```

- [ ] **Step 3: `WrappedKeys.kt` — libsodium keypair generation + Keystore AES-GCM seal/unseal**

```kotlin
package expo.modules.phonesynccore.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

data class BoxKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)
data class SignKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)
data class Sealed(val ciphertext: ByteArray, val iv: ByteArray)

object WrappedKeys {
    private val ls = LazySodiumAndroid(SodiumAndroid())

    fun generateBox(): BoxKeyPair {
        val kp = ls.cryptoBoxKeypair()
        return BoxKeyPair(kp.publicKey.asBytes, kp.secretKey.asBytes)
    }

    fun generateSign(): SignKeyPair {
        val kp = ls.cryptoSignKeypair()
        return SignKeyPair(kp.publicKey.asBytes, kp.secretKey.asBytes)
    }

    fun seal(plaintext: ByteArray): Sealed {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, KeystoreMaster.getOrCreate(), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)
        return Sealed(ct, iv)
    }

    fun unseal(sealed: Sealed): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, KeystoreMaster.getOrCreate(), GCMParameterSpec(128, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }
}
```

- [ ] **Step 4: `CryptoStore.kt` — DataStore persistence of wrapped secret keys + plain public keys**

```kotlin
package expo.modules.phonesynccore.crypto

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.ds by preferencesDataStore("phonesync_crypto")

object CryptoStore {
    private val KEY_BOX_SECRET_CT = byteArrayPreferencesKey("box_secret_ct")
    private val KEY_BOX_SECRET_IV = byteArrayPreferencesKey("box_secret_iv")
    private val KEY_BOX_PUBLIC    = byteArrayPreferencesKey("box_public")
    private val KEY_SIGN_SECRET_CT = byteArrayPreferencesKey("sign_secret_ct")
    private val KEY_SIGN_SECRET_IV = byteArrayPreferencesKey("sign_secret_iv")
    private val KEY_SIGN_PUBLIC    = byteArrayPreferencesKey("sign_public")

    suspend fun loadOrGenerate(context: Context): Pair<BoxKeyPair, SignKeyPair> {
        val prefs = context.ds.data.first()
        val existingBox = prefs[KEY_BOX_PUBLIC]?.let { pub ->
            val ct = prefs[KEY_BOX_SECRET_CT] ?: return@let null
            val iv = prefs[KEY_BOX_SECRET_IV] ?: return@let null
            BoxKeyPair(pub, WrappedKeys.unseal(Sealed(ct, iv)))
        }
        val existingSign = prefs[KEY_SIGN_PUBLIC]?.let { pub ->
            val ct = prefs[KEY_SIGN_SECRET_CT] ?: return@let null
            val iv = prefs[KEY_SIGN_SECRET_IV] ?: return@let null
            SignKeyPair(pub, WrappedKeys.unseal(Sealed(ct, iv)))
        }
        if (existingBox != null && existingSign != null) return existingBox to existingSign

        val newBox = WrappedKeys.generateBox()
        val newSign = WrappedKeys.generateSign()
        val boxSealed = WrappedKeys.seal(newBox.secretKey)
        val signSealed = WrappedKeys.seal(newSign.secretKey)
        context.ds.edit { e ->
            e[KEY_BOX_SECRET_CT] = boxSealed.ciphertext
            e[KEY_BOX_SECRET_IV] = boxSealed.iv
            e[KEY_BOX_PUBLIC] = newBox.publicKey
            e[KEY_SIGN_SECRET_CT] = signSealed.ciphertext
            e[KEY_SIGN_SECRET_IV] = signSealed.iv
            e[KEY_SIGN_PUBLIC] = newSign.publicKey
        }
        return newBox to newSign
    }

    suspend fun rotate(context: Context) {
        context.ds.edit { it.clear() }
        loadOrGenerate(context)
    }
}
```

- [ ] **Step 5: Expose a `getOrCreateKeys()` AsyncFunction in PhoneSyncCoreModule.kt** that returns the public keys (base64) to JS for debugging. Keep the interface small for now — Phase 3 will use it for pairing QR.

- [ ] **Step 6: Android instrumented test**

Create `mobile/modules/phone-sync-core/android/src/androidTest/java/expo/modules/phonesynccore/crypto/CryptoStoreTest.kt`:

```kotlin
package expo.modules.phonesynccore.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CryptoStoreTest {
    @Test fun roundtripKeys() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CryptoStore.rotate(ctx)
        val (box1, sign1) = CryptoStore.loadOrGenerate(ctx)
        val (box2, sign2) = CryptoStore.loadOrGenerate(ctx)
        assertTrue(box1.publicKey.contentEquals(box2.publicKey))
        assertTrue(box1.secretKey.contentEquals(box2.secretKey))
        assertTrue(sign1.publicKey.contentEquals(sign2.publicKey))
    }
}
```

- [ ] **Step 7: Build + run instrumented test on an emulator**

```bash
cd mobile && npx expo prebuild --platform android --clean
cd android && ./gradlew :phone-sync-core:connectedAndroidTest
```

If prebuild fails or there's no emulator, report as DONE_WITH_CONCERNS: "unit test compiles, needs emulator for instrumented run."

- [ ] **Step 8: Commit**

```bash
git add mobile/modules/phone-sync-core/
git commit -m "feat(mobile/crypto): Keystore master key + wrapped libsodium X25519/Ed25519 via DataStore"
```

---

## Task 6: Android — Encrypter + nonce source + Replay guard

**Files:**

- Create: `mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/crypto/NonceSource.kt`
- Create: `mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/crypto/Encrypter.kt`
- Create: `mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/storage/ReplayGuard.kt`
- Tests: androidTest for Encrypter roundtrip + ReplayGuard dedup

- [ ] **Step 1: NonceSource.kt — 16 random prefix + 8 counter, counter persisted in DataStore**

```kotlin
package expo.modules.phonesynccore.crypto

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.SecureRandom

private val Context.nonceDs by preferencesDataStore("phonesync_nonce")

object NonceSource {
    private val KEY_PREFIX = byteArrayPreferencesKey("prefix")
    private val KEY_COUNTER = longPreferencesKey("counter")

    suspend fun next(ctx: Context): ByteArray {
        val prefs = ctx.nonceDs.data.first()
        val prefix = prefs[KEY_PREFIX] ?: run {
            val p = ByteArray(16).also { SecureRandom().nextBytes(it) }
            ctx.nonceDs.edit { it[KEY_PREFIX] = p; it[KEY_COUNTER] = 0L }
            p
        }
        var counter = 0L
        ctx.nonceDs.edit { e ->
            counter = (e[KEY_COUNTER] ?: 0L) + 1
            e[KEY_COUNTER] = counter
        }
        val nonce = ByteArray(24)
        System.arraycopy(prefix, 0, nonce, 0, 16)
        ByteBuffer.wrap(nonce, 16, 8).putLong(counter)
        return nonce
    }

    suspend fun regenerate(ctx: Context) {
        ctx.nonceDs.edit { it.clear() }
    }
}
```

- [ ] **Step 2: Encrypter.kt — `crypto_box_easy` wrapper**

```kotlin
package expo.modules.phonesynccore.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box

object Encrypter {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium

    fun encrypt(plain: ByteArray, nonce: ByteArray, peerPubkey: ByteArray, ownSecret: ByteArray): ByteArray {
        require(nonce.size == 24)
        require(peerPubkey.size == 32)
        require(ownSecret.size == 32)
        val ct = ByteArray(plain.size + Box.MACBYTES)
        val rc = sodium.cryptoBoxEasy(ct, plain, plain.size.toLong(), nonce, peerPubkey, ownSecret)
        check(rc == 0) { "cryptoBoxEasy failed rc=$rc" }
        return ct
    }

    fun decrypt(ct: ByteArray, nonce: ByteArray, peerPubkey: ByteArray, ownSecret: ByteArray): ByteArray {
        val plain = ByteArray(ct.size - Box.MACBYTES)
        val rc = sodium.cryptoBoxOpenEasy(plain, ct, ct.size.toLong(), nonce, peerPubkey, ownSecret)
        check(rc == 0) { "cryptoBoxOpenEasy failed rc=$rc" }
        return plain
    }
}
```

- [ ] **Step 3: ReplayGuard.kt — in-memory + DataStore-persisted seen set (48h TTL)**

```kotlin
package expo.modules.phonesynccore.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.replayDs by preferencesDataStore("phonesync_replay")
private const val TTL_MS = 48L * 60 * 60 * 1000

object ReplayGuard {
    suspend fun seenOrMark(ctx: Context, msgId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = ctx.replayDs.data.first()
        val key = stringPreferencesKey("m_$msgId")
        val ts = prefs[key]?.toLongOrNull()
        if (ts != null && nowMs - ts < TTL_MS) return true
        ctx.replayDs.edit { e ->
            e[key] = nowMs.toString()
            // sweep opportunistically
            val toRemove = e.asMap().keys.filter { k ->
                k.name.startsWith("m_") && (e[stringPreferencesKey(k.name)]?.toLongOrNull()?.let { nowMs - it > TTL_MS } ?: false)
            }
            toRemove.forEach { e.remove(it) }
        }
        return false
    }
}
```

- [ ] **Step 4: Instrumented tests for both**

One test for Encrypter symmetric round-trip (A encrypts to B, B decrypts; invalid nonce fails). One for ReplayGuard (first=false, second=true, expired=false).

- [ ] **Step 5: Run + commit**

```bash
git add mobile/modules/phone-sync-core/
git commit -m "feat(mobile/crypto): counter+prefix nonce, crypto_box_easy wrapper, replay guard"
```

---

## Task 7: Android — JWT minter + PairProtocol + fingerprint

**Files:**

- Create: `auth/JwtMinter.kt`
- Create: `pairing/PairPayload.kt`
- Create: `pairing/Fingerprint.kt`
- Create: `pairing/PairProtocol.kt`

- [ ] **Step 1: Dep: add `com.auth0:java-jwt:4.4.0` to build.gradle** or hand-roll Ed25519 JWT if dep resolution issues. Hand-rolled is simple: base64url(header) + "." + base64url(payload) + "." + base64url(Ed25519 sign(concat)). Prefer hand-roll — fewer deps.

- [ ] **Step 2: JwtMinter.kt (hand-rolled, no Auth0 dep)**

```kotlin
package expo.modules.phonesynccore.auth

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

object JwtMinter {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium

    fun mint(deviceId: String, signSecret: ByteArray, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val header = """{"alg":"EdDSA","typ":"JWT"}"""
        val payload = JSONObject(mapOf(
            "sub" to deviceId,
            "jti" to UUID.randomUUID().toString(),
            "iat" to nowSec,
            "exp" to nowSec + 60,
        )).toString()
        val b64 = { s: ByteArray -> Base64.getUrlEncoder().withoutPadding().encodeToString(s) }
        val signingInput = "${b64(header.toByteArray())}.${b64(payload.toByteArray())}"
        val sig = ByteArray(Sign.BYTES)
        sodium.cryptoSignDetached(sig, null, signingInput.toByteArray(), signingInput.length.toLong(), signSecret)
        return "$signingInput.${b64(sig)}"
    }
}
```

- [ ] **Step 3: PairPayload.kt — QR payload schema**

```kotlin
package expo.modules.phonesynccore.pairing

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

data class PairPayload(
    val relayUrl: String,
    val deviceId: String,
    val encPubkey: ByteArray,
    val signPubkey: ByteArray,
    val pairToken: String,
) {
    fun toJson(): String = JSONObject(mapOf(
        "relay_url" to relayUrl,
        "device_id" to deviceId,
        "enc_pubkey" to Base64.getEncoder().encodeToString(encPubkey),
        "sign_pubkey" to Base64.getEncoder().encodeToString(signPubkey),
        "pair_token" to pairToken,
    )).toString()

    companion object {
        fun newToken(): String = "pt-" + UUID.randomUUID().toString()
        fun fromJson(s: String): PairPayload {
            val j = JSONObject(s)
            return PairPayload(
                relayUrl = j.getString("relay_url"),
                deviceId = j.getString("device_id"),
                encPubkey = Base64.getDecoder().decode(j.getString("enc_pubkey")),
                signPubkey = Base64.getDecoder().decode(j.getString("sign_pubkey")),
                pairToken = j.getString("pair_token"),
            )
        }
    }
}
```

- [ ] **Step 4: Fingerprint.kt**

```kotlin
package expo.modules.phonesynccore.pairing

import java.security.MessageDigest

object Fingerprint {
    fun of(encPubkey: ByteArray, signPubkey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(encPubkey); md.update(signPubkey)
        val h = md.digest()
        // grouped hex, 8 groups of 4 chars, uppercase
        return (0 until 32 step 2).joinToString("-") { i ->
            "%02X%02X".format(h[i], h[i + 1])
        }
    }
}
```

- [ ] **Step 5: PairProtocol.kt — client of /pair/init + /pair/complete**

```kotlin
package expo.modules.phonesynccore.pairing

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

private val JSON = "application/json".toMediaType()
private val ls = LazySodiumAndroid(SodiumAndroid())
private val sodium = ls.sodium

object PairProtocol {
    private val http = OkHttpClient()

    fun init(relayUrl: String, token: String, deviceId: String, encPub: ByteArray, signPub: ByteArray) {
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(encPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(signPub),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("$relayUrl/pair/init").post(body).build()).execute()
        check(resp.isSuccessful) { "init HTTP ${resp.code}" }
    }

    fun complete(
        relayUrl: String, token: String, deviceId: String,
        ownEncPub: ByteArray, ownSignPub: ByteArray,
        peerEncPub: ByteArray, peerSignPub: ByteArray,
        aSignSecret: ByteArray,  // NOTE: this is invoked on Device A with A's sign secret to produce the confirmation_sig
    ) {
        val msg = token.toByteArray() + peerEncPub + peerSignPub + ownEncPub + ownSignPub
        // Wait — spec: sig_A(pair_token || A_enc || A_sign || B_enc || B_sign).
        // On Device A's side we already have A_enc, A_sign, and are proving consent to B's pubkeys.
        // Adjust order based on which device is completing. Phase 2 scope: Device B calls /pair/complete
        // but the confirmation_sig is created by Device A (sent to B over the relay out-of-band once A confirms).
        // This method signs from Device A's side and returns the sig; a separate RPC sends it to B,
        // which includes it in /pair/complete.
        // Simplified: Device B receives sig from A via relay and POSTs with it.
        val sig = ByteArray(Sign.BYTES)
        sodium.cryptoSignDetached(sig, null, msg, msg.size.toLong(), aSignSecret)
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(ownEncPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(ownSignPub),
            "confirmation_sig" to Base64.getEncoder().encodeToString(sig),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("$relayUrl/pair/complete").post(body).build()).execute()
        check(resp.isSuccessful) { "complete HTTP ${resp.code}" }
    }
}
```

**Note on two-sided confirmation:** Phase 2 scope — Device B initiates `complete` with a sig produced by Device A. Device A→Device B transport of the sig is out-of-band for now (user types/scans it); Phase 3 will add the WebSocket-push flow from relay.

- [ ] **Step 6: Tests — unit tests for Fingerprint format + JwtMinter signature verification**

Add `androidTest` class that mints a JWT then verifies signature with stored pubkey via `sodium.cryptoSignVerifyDetached`.

- [ ] **Step 7: Commit**

```bash
git add mobile/modules/phone-sync-core/
git commit -m "feat(mobile): JWT minter + pair protocol client + fingerprint"
```

---

## Task 8: Android — PhoneSyncCoreModule exposes pair/encrypt/decrypt

**Files:**

- Modify: `PhoneSyncCoreModule.kt` — replace (or extend) the existing `ping()` with pair + encrypt AsyncFunctions.

Add methods:

- `getPublicKeys()` → returns base64 of enc_pubkey + sign_pubkey + device_id (UUID persisted in DataStore).
- `startPairInitiator(relayUrl)` → generates pair_token, calls relay /pair/init, returns QR JSON string.
- `completePairing(qrJson, peerConfirmationSig?)` → parses QR, calls relay /pair/complete.
- `fingerprintOf(peerEncPubkey, peerSignPubkey)` → returns formatted fingerprint string for UI.
- `mintAuthJwt()` → returns JWT for WebSocket auth.
- `encryptToPeer(plaintextBase64, peerEncPubkey)` → returns `{ciphertext, nonce}` base64.
- `decryptFromPeer(ciphertextBase64, nonceBase64, peerEncPubkey)` → returns plaintext base64.

Keep the existing `ping(url)` — now it should mint a JWT and include `Authorization: Bearer …` header on the OkHttp request (test that it still round-trips the echo).

TS surface update in `src/index.ts` to export all new methods.

- [ ] **Step 1: Implement + tests**

- [ ] **Step 2: Typecheck + commit**

```bash
cd mobile && npx tsc --noEmit
git add mobile/
git commit -m "feat(mobile/core): expose pair/encrypt/decrypt/jwt AsyncFunctions from native module"
```

---

## Task 9: End-to-end Phase 2 smoke test (manual)

**No code — document the procedure in `docs/test-scenarios.md`.**

- [ ] **Step 1: Add section "Phase 2 — Crypto + Pairing smoke":**

```markdown
## Phase 2 — Crypto + Pairing Smoke

**Setup:** Two Android emulators (or one emulator + one device). Relay running locally.

**Procedure:**
1. Launch app on Device A. Tap "Start pairing" (new UI button — added in Task 8's JS-side code). App generates keys, calls `/pair/init`, displays QR.
2. Launch app on Device B. Scan QR (or paste JSON).
3. Device B generates its keys, fetches pair_token, waits. Device A shows B's fingerprint. User confirms on A (button "Approve peer: <fingerprint>"). Device A produces confirmation_sig and sends it (for now, out-of-band copy-paste; Phase 3 adds WS-push).
4. Device B receives confirmation_sig, calls `/pair/complete`. Relay returns `pair_id`.
5. Both devices now have peer pubkeys stored.
6. Tap "Ping relay (authed)" on either device — uses minted JWT, succeeds. Without JWT fails with 401.
7. Tap "Encrypt test" on Device A — encrypts a string with Device B's pubkey, sends ciphertext over ws, Device B decrypts and displays.

**Expected:** All steps succeed; relay-side logs show only ciphertext (no plaintext); wrong JWT rejected; replayed JWT rejected.
```

- [ ] **Step 2: Commit**

```bash
git add docs/test-scenarios.md
git commit -m "docs: Phase 2 manual smoke test procedure"
```

---

## Completion Criteria

Phase 2 is done when:

- [ ] `cd relay && go test -race ./... -count=1` → green.
- [ ] All Android instrumented tests pass on a running emulator (or gradle says they would if docker-in-CI available).
- [ ] `cd mobile && npx tsc --noEmit` → clean.
- [ ] Manual smoke (Task 9) passes on two devices.
- [ ] WebSocket `/ws` rejects unauthenticated connections (401).
- [ ] Relay-side BoltDB contains pending → confirmed transitions; confirmation_sig verified.
- [ ] JTI replay rejected within 60s window.
- [ ] Relay NEVER sees plaintext of any `encryptToPeer` output.
- [ ] TODO(phase-2) comments from Phase 1 are RESOLVED: `CheckOrigin` still open (acceptable for personal dev; Phase 3 paired-origin check arrives), `origin_device` hardcoded "mobile" is replaced with persisted device UUID, jsonschema FormatAssert still deferred (it's cosmetic).

**Next:** Phase 3 plan — NotificationListenerService + first real notification mirror (loop-free), posting through the established crypto + relay. File: `docs/superpowers/plans/2026-04-21-phase-3-listener-first-mirror.md`. Do NOT start Phase 3 until Phase 2 manual smoke passes.

**UI design checkpoint flagged:** Phase 2 Task 8 adds "Start pairing / Scan QR / Approve peer / Encrypt test" buttons. These are functional debug UI. The full polished UI (onboarding, app allowlist, connection state card, reliability setup) ships in Phase 4+ and **controller will PAUSE and ask the user for design direction before any UI polish work begins.**
