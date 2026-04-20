# Phase 1 — Relay + Proto + Mobile Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the relay server (Go + BoltDB + WebSocket), lock the shared packet protocol as JSON Schema, and scaffold an Expo RN mobile app with a custom native module (Kotlin) that can ping the relay round-trip.

**Architecture:** Monorepo with `/relay` (Go), `/proto` (JSON Schema), `/mobile` (Expo app + custom native module). Relay is a single static Go binary in a Docker container; proto lives as JSON Schema files with codegen stubs for each client; mobile is an Expo app using EAS Build dev client with a custom Kotlin native module exposing a minimal `ping(relayUrl)` method.

**Tech Stack:** Go 1.22, gorilla/websocket, bbolt (maintained fork of BoltDB), chi router; JSON Schema 2020-12; Expo SDK 52+, TypeScript, Expo Modules API, Kotlin 1.9+, OkHttp 4.

**Spec reference:** `docs/superpowers/specs/2026-04-20-phone-sync-design.md`

**Out of scope for this plan:** Crypto, pairing, LAN transport, FCM, reply bridge, icons, loop avoidance, desktop client, battery tuning. Those ship in later phase plans.

---

## File Structure

```text
phone-sync/
├── relay/
│   ├── cmd/relay/main.go              # entrypoint
│   ├── internal/server/server.go      # HTTP + WS routing
│   ├── internal/server/ws.go          # WebSocket upgrade, echo handler (Phase 1)
│   ├── internal/server/health.go      # /health endpoint
│   ├── internal/store/bolt.go         # bbolt wrapper (Phase 1: simple KV, not yet used)
│   ├── internal/store/bolt_test.go
│   ├── internal/server/server_test.go
│   ├── Dockerfile
│   ├── go.mod
│   └── go.sum
├── proto/
│   ├── packet.schema.json             # top-level envelope
│   ├── notif-post.schema.json
│   ├── notif-cancel.schema.json
│   ├── ping.schema.json
│   └── README.md
├── mobile/
│   ├── app/
│   │   ├── _layout.tsx                # Expo Router root layout
│   │   └── index.tsx                  # single screen: ping button + status
│   ├── modules/
│   │   └── phone-sync-core/
│   │       ├── expo-module.config.json
│   │       ├── src/
│   │       │   ├── index.ts           # TS types
│   │       │   └── PhoneSyncCoreModule.ts
│   │       ├── android/
│   │       │   ├── build.gradle
│   │       │   └── src/main/java/expo/modules/phonesynccore/
│   │       │       └── PhoneSyncCoreModule.kt
│   │       └── ios/
│   │           └── PhoneSyncCoreModule.swift   # stub, no-op
│   ├── app.json
│   ├── package.json
│   ├── tsconfig.json
│   └── eas.json
├── deploy/
│   ├── docker-compose.yml
│   └── caddy/Caddyfile
└── .github/workflows/
    ├── relay.yml                      # go test + docker build
    └── mobile.yml                     # expo-doctor + tsc
```

---

## Task 1: Initialize Go module and relay skeleton

**Files:**

- Create: `relay/go.mod`
- Create: `relay/cmd/relay/main.go`

- [ ] **Step 1: Initialize Go module**

Run from repo root:

```bash
mkdir -p relay/cmd/relay relay/internal/server relay/internal/store
cd relay && go mod init github.com/phonesync/relay
```

Expected: `go.mod` created with Go version.

- [ ] **Step 2: Write minimal main.go**

Create `relay/cmd/relay/main.go`:

```go
package main

import (
	"log"
	"net/http"
	"os"
)

func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("relay listening on %s", addr)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatal(err)
	}
}
```

- [ ] **Step 3: Run it and curl /health**

```bash
cd relay && go run ./cmd/relay &
sleep 1
curl -sf http://localhost:8080/health
kill %1
```

Expected: `{"status":"ok"}`.

- [ ] **Step 4: Commit**

```bash
git add relay/
git commit -m "feat(relay): initialize Go module with /health endpoint"
```

---

## Task 2: Add chi router and /health unit test

**Files:**

- Modify: `relay/go.mod`
- Create: `relay/internal/server/server.go`
- Create: `relay/internal/server/health.go`
- Create: `relay/internal/server/server_test.go`
- Modify: `relay/cmd/relay/main.go`

- [ ] **Step 1: Add chi dependency**

```bash
cd relay && go get github.com/go-chi/chi/v5
```

- [ ] **Step 2: Write failing test for /health**

Create `relay/internal/server/server_test.go`:

```go
package server

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthEndpoint(t *testing.T) {
	srv := New()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	expected := `{"status":"ok"}`
	if rec.Body.String() != expected {
		t.Fatalf("expected body %q, got %q", expected, rec.Body.String())
	}
}
```

- [ ] **Step 3: Run test, verify it fails with compile error**

```bash
cd relay && go test ./internal/server/
```

Expected: FAIL — `undefined: New`.

- [ ] **Step 4: Implement server skeleton**

Create `relay/internal/server/server.go`:

```go
package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

type Server struct {
	router *chi.Mux
}

func New() *Server {
	s := &Server{router: chi.NewRouter()}
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler {
	return s.router
}

func (s *Server) routes() {
	s.router.Get("/health", s.handleHealth)
}
```

Create `relay/internal/server/health.go`:

```go
package server

import "net/http"

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}
```

- [ ] **Step 5: Update main.go to use the server**

Rewrite `relay/cmd/relay/main.go`:

```go
package main

import (
	"log"
	"net/http"
	"os"

	"github.com/phonesync/relay/internal/server"
)

func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	srv := server.New()
	log.Printf("relay listening on %s", addr)
	if err := http.ListenAndServe(addr, srv.Handler()); err != nil {
		log.Fatal(err)
	}
}
```

- [ ] **Step 6: Run tests, verify they pass**

```bash
cd relay && go test ./...
```

Expected: `PASS`.

- [ ] **Step 7: Commit**

```bash
git add relay/
git commit -m "feat(relay): chi router with /health unit test"
```

---

## Task 3: WebSocket echo endpoint (proof of upgrade path)

**Files:**

- Modify: `relay/go.mod`
- Create: `relay/internal/server/ws.go`
- Modify: `relay/internal/server/server.go`
- Modify: `relay/internal/server/server_test.go`

- [ ] **Step 1: Add gorilla/websocket**

```bash
cd relay && go get github.com/gorilla/websocket
```

- [ ] **Step 2: Write failing WS echo test**

Append to `relay/internal/server/server_test.go`:

```go
import (
	"net/http/httptest"
	"strings"

	"github.com/gorilla/websocket"
)

func TestWebSocketEcho(t *testing.T) {
	srv := New()
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	c, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()

	if err := c.WriteMessage(websocket.TextMessage, []byte(`{"type":"ping"}`)); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, msg, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(msg) != `{"type":"ping"}` {
		t.Fatalf("expected echo, got %q", string(msg))
	}
}
```

- [ ] **Step 3: Run test, verify it fails**

```bash
cd relay && go test ./internal/server/ -run TestWebSocketEcho
```

Expected: FAIL — 404 or connection refused on `/ws`.

- [ ] **Step 4: Implement WS handler**

Create `relay/internal/server/ws.go`:

```go
package server

import (
	"log"
	"net/http"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("upgrade: %v", err)
		return
	}
	defer conn.Close()
	for {
		mt, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if err := conn.WriteMessage(mt, msg); err != nil {
			return
		}
	}
}
```

- [ ] **Step 5: Wire the route**

Modify `relay/internal/server/server.go` — update `routes()`:

```go
func (s *Server) routes() {
	s.router.Get("/health", s.handleHealth)
	s.router.Get("/ws", s.handleWebSocket)
}
```

- [ ] **Step 6: Run tests**

```bash
cd relay && go test ./internal/server/ -v
```

Expected: both tests PASS.

- [ ] **Step 7: Commit**

```bash
git add relay/
git commit -m "feat(relay): WebSocket /ws echo endpoint with integration test"
```

---

## Task 4: bbolt KV store wrapper (foundational, not yet wired)

**Files:**

- Modify: `relay/go.mod`
- Create: `relay/internal/store/bolt.go`
- Create: `relay/internal/store/bolt_test.go`

- [ ] **Step 1: Add bbolt dependency**

```bash
cd relay && go get go.etcd.io/bbolt
```

- [ ] **Step 2: Write failing test for Put/Get/Delete**

Create `relay/internal/store/bolt_test.go`:

```go
package store

import (
	"path/filepath"
	"testing"
)

func TestBoltPutGetDelete(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenBolt(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer s.Close()

	if err := s.Put("bucket1", "key1", []byte("hello")); err != nil {
		t.Fatalf("put: %v", err)
	}
	got, err := s.Get("bucket1", "key1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if string(got) != "hello" {
		t.Fatalf("expected hello, got %q", string(got))
	}
	if err := s.Delete("bucket1", "key1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	got, err = s.Get("bucket1", "key1")
	if err != nil {
		t.Fatalf("get after delete: %v", err)
	}
	if got != nil {
		t.Fatalf("expected nil after delete, got %q", string(got))
	}
}
```

- [ ] **Step 3: Run test, verify compile fail**

```bash
cd relay && go test ./internal/store/
```

Expected: FAIL — `undefined: OpenBolt`.

- [ ] **Step 4: Implement bolt wrapper**

Create `relay/internal/store/bolt.go`:

```go
package store

import (
	"time"

	"go.etcd.io/bbolt"
)

type Bolt struct {
	db *bbolt.DB
}

func OpenBolt(path string) (*Bolt, error) {
	db, err := bbolt.Open(path, 0600, &bbolt.Options{Timeout: 2 * time.Second})
	if err != nil {
		return nil, err
	}
	return &Bolt{db: db}, nil
}

func (b *Bolt) Close() error {
	return b.db.Close()
}

func (b *Bolt) Put(bucket, key string, value []byte) error {
	return b.db.Update(func(tx *bbolt.Tx) error {
		bkt, err := tx.CreateBucketIfNotExists([]byte(bucket))
		if err != nil {
			return err
		}
		return bkt.Put([]byte(key), value)
	})
}

func (b *Bolt) Get(bucket, key string) ([]byte, error) {
	var out []byte
	err := b.db.View(func(tx *bbolt.Tx) error {
		bkt := tx.Bucket([]byte(bucket))
		if bkt == nil {
			return nil
		}
		v := bkt.Get([]byte(key))
		if v != nil {
			out = append([]byte{}, v...) // copy because v is only valid within tx
		}
		return nil
	})
	return out, err
}

func (b *Bolt) Delete(bucket, key string) error {
	return b.db.Update(func(tx *bbolt.Tx) error {
		bkt := tx.Bucket([]byte(bucket))
		if bkt == nil {
			return nil
		}
		return bkt.Delete([]byte(key))
	})
}
```

- [ ] **Step 5: Run tests**

```bash
cd relay && go test ./internal/store/ -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add relay/
git commit -m "feat(relay): bbolt KV wrapper with Put/Get/Delete"
```

---

## Task 5: Dockerfile + docker-compose

**Files:**

- Create: `relay/Dockerfile`
- Create: `relay/.dockerignore`
- Create: `deploy/docker-compose.yml`
- Create: `deploy/caddy/Caddyfile`

- [ ] **Step 1: Write multi-stage Dockerfile**

Create `relay/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1.6
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/relay ./cmd/relay

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/relay /relay
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/relay"]
```

Create `relay/.dockerignore`:

```text
Dockerfile
.dockerignore
*.md
.git
```

- [ ] **Step 2: Write docker-compose**

Create `deploy/docker-compose.yml`:

```yaml
services:
  relay:
    build: ../relay
    container_name: phone-sync-relay
    environment:
      LISTEN_ADDR: ":8080"
    volumes:
      - relay-data:/data
    restart: unless-stopped
    networks:
      - phone-sync-net

  caddy:
    image: caddy:2-alpine
    container_name: phone-sync-caddy
    ports:
      - "443:443"
      - "80:80"
    volumes:
      - ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
    depends_on:
      - relay
    restart: unless-stopped
    networks:
      - phone-sync-net

volumes:
  relay-data:
  caddy-data:
  caddy-config:

networks:
  phone-sync-net:
```

Create `deploy/caddy/Caddyfile`:

```text
# Replace "your-domain.example" with your DuckDNS hostname when deploying publicly.
# For local laptop-only dev, access relay directly on port 8080 and skip Caddy.

relay.local {
    tls internal
    reverse_proxy relay:8080
}
```

- [ ] **Step 3: Build and smoke test locally**

```bash
cd deploy && docker compose build relay
docker compose up -d relay
sleep 2
curl -sf http://localhost:8080/health || echo "FAIL — check docker compose logs relay"
docker compose down
```

Expected: `{"status":"ok"}`.

Note: `ports:` not exposed in compose — use `docker compose exec` or add a temp port mapping for local access:

```bash
cd deploy && docker run --rm -p 8080:8080 phone-sync-relay
# in another shell:
curl -sf http://localhost:8080/health
```

- [ ] **Step 4: Commit**

```bash
git add relay/Dockerfile relay/.dockerignore deploy/
git commit -m "feat(deploy): Dockerfile + docker-compose + Caddy"
```

---

## Task 6: Proto JSON Schema — envelope + notif.post + notif.cancel + ping

**Files:**

- Create: `proto/packet.schema.json`
- Create: `proto/notif-post.schema.json`
- Create: `proto/notif-cancel.schema.json`
- Create: `proto/ping.schema.json`
- Create: `proto/README.md`

- [ ] **Step 1: Write envelope schema**

Create `proto/packet.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://phone-sync.local/schemas/packet.schema.json",
  "title": "Packet",
  "description": "Top-level envelope for all messages. Contents are E2EE; this schema describes the inner cleartext.",
  "type": "object",
  "required": ["v", "type", "msg_id", "origin_device", "ts"],
  "properties": {
    "v": { "type": "integer", "const": 1 },
    "type": {
      "type": "string",
      "enum": ["notif.post", "notif.update", "notif.cancel", "notif.reply", "ping", "pong", "ack", "icon.request"]
    },
    "msg_id": { "type": "string", "format": "uuid" },
    "origin_device": { "type": "string", "minLength": 1 },
    "ts": { "type": "integer", "description": "Origin wall-clock millis; advisory only." },
    "payload": { "type": ["object", "null"] }
  }
}
```

- [ ] **Step 2: Write notif.post schema**

Create `proto/notif-post.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://phone-sync.local/schemas/notif-post.schema.json",
  "title": "NotifPostPayload",
  "type": "object",
  "required": ["canon_id", "package", "id", "title", "is_clearable"],
  "properties": {
    "canon_id": { "type": "string", "pattern": "^[^:]+:[^:]+:[0-9]+:.*$" },
    "app_name": { "type": "string" },
    "package": { "type": "string" },
    "id": { "type": "integer" },
    "tag": { "type": ["string", "null"] },
    "channel_id": { "type": ["string", "null"] },
    "channel_name": { "type": ["string", "null"] },
    "channel_importance": { "type": "integer", "minimum": 0, "maximum": 5 },
    "visibility": { "type": "string", "enum": ["public", "private", "secret"] },
    "is_group_summary": { "type": "boolean" },
    "group_key": { "type": ["string", "null"] },
    "title": { "type": "string" },
    "text": { "type": ["string", "null"] },
    "big_text": { "type": ["string", "null"] },
    "sub_text": { "type": ["string", "null"] },
    "template": { "type": ["string", "null"] },
    "messages": { "type": ["array", "null"] },
    "is_clearable": { "type": "boolean" },
    "is_ongoing": { "type": "boolean" },
    "small_icon_png": { "type": ["string", "null"], "contentEncoding": "base64" },
    "small_icon_hash": { "type": ["string", "null"] },
    "large_icon_png": { "type": ["string", "null"], "contentEncoding": "base64" },
    "large_icon_hash": { "type": ["string", "null"] },
    "actions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["title", "action_id"],
        "properties": {
          "title": { "type": "string" },
          "action_id": { "type": "string", "format": "uuid" },
          "remote_input_key": { "type": ["string", "null"] },
          "is_reply": { "type": "boolean" }
        }
      }
    }
  }
}
```

- [ ] **Step 3: Write notif.cancel schema**

Create `proto/notif-cancel.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://phone-sync.local/schemas/notif-cancel.schema.json",
  "title": "NotifCancelPayload",
  "type": "object",
  "required": ["canon_id", "reason"],
  "properties": {
    "canon_id": { "type": "string" },
    "reason": { "type": "string", "enum": ["user_swipe", "user_click", "app_cancel"] }
  }
}
```

- [ ] **Step 4: Write ping schema**

Create `proto/ping.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://phone-sync.local/schemas/ping.schema.json",
  "title": "PingPayload",
  "type": "object",
  "properties": {
    "nonce": { "type": ["string", "null"] }
  }
}
```

- [ ] **Step 5: Write proto README**

Create `proto/README.md`:

```markdown
# Phone-Sync Protocol (v1)

Single source of truth for packet schemas. Each client parses in its native language:
- Relay (Go) — hand-written structs validated against these schemas in tests
- Mobile (Kotlin) — Moshi/Gson-generated types, schema-checked in CI
- Desktop (Rust) — serde_json structs, schema-checked in CI

## Envelope

See `packet.schema.json`. Every message carries `{v, type, msg_id, origin_device, ts, payload}`. Payload shape varies by `type` — see per-type schemas.

## Types (Phase 1 subset)

- `ping` / `pong` — liveness
- `notif.post` / `notif.update` — mirror a notification (see `notif-post.schema.json`)
- `notif.cancel` — dismiss mirror (see `notif-cancel.schema.json`)

Later phases add `notif.reply`, `icon.request`, `ack`.

## Validation

Schemas are JSON Schema 2020-12. Use any compliant validator in language of choice.
```

- [ ] **Step 6: Commit**

```bash
git add proto/
git commit -m "feat(proto): v1 JSON Schema — envelope, notif.post, notif.cancel, ping"
```

---

## Task 7: Relay validates envelope on /ws inbound

**Files:**

- Modify: `relay/go.mod`
- Create: `relay/internal/server/validator.go`
- Create: `relay/internal/server/validator_test.go`
- Modify: `relay/internal/server/ws.go`

- [ ] **Step 1: Add JSON Schema validator**

```bash
cd relay && go get github.com/santhosh-tekuri/jsonschema/v5
```

- [ ] **Step 2: Copy proto into relay for embed**

```bash
mkdir -p relay/internal/server/schemas
cp ../proto/*.schema.json relay/internal/server/schemas/
```

Actually use `go:embed` from relative path. Adjust approach:

```bash
mkdir -p relay/proto
cp proto/*.schema.json relay/proto/
```

- [ ] **Step 3: Write failing validator test**

Create `relay/internal/server/validator_test.go`:

```go
package server

import "testing"

func TestValidateEnvelope_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	good := []byte(`{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000}`)
	if err := v.ValidateEnvelope(good); err != nil {
		t.Fatalf("expected valid, got %v", err)
	}
}

func TestValidateEnvelope_Invalid(t *testing.T) {
	v, _ := NewValidator()
	bad := []byte(`{"v":2,"type":"ping","msg_id":"x","origin_device":"","ts":"oops"}`)
	if err := v.ValidateEnvelope(bad); err == nil {
		t.Fatal("expected invalid, got nil")
	}
}
```

- [ ] **Step 4: Run test, verify compile fail**

```bash
cd relay && go test ./internal/server/ -run TestValidateEnvelope
```

Expected: FAIL — `undefined: NewValidator`.

- [ ] **Step 5: Implement validator with embed**

Create `relay/internal/server/validator.go`:

```go
package server

import (
	"embed"
	"fmt"
	"io/fs"

	"github.com/santhosh-tekuri/jsonschema/v5"
)

//go:embed schemas/*.schema.json
var schemaFS embed.FS

type Validator struct {
	envelope *jsonschema.Schema
}

func NewValidator() (*Validator, error) {
	compiler := jsonschema.NewCompiler()
	err := fs.WalkDir(schemaFS, "schemas", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, err := schemaFS.ReadFile(path)
		if err != nil {
			return err
		}
		return compiler.AddResource(path, bytesReader(b))
	})
	if err != nil {
		return nil, err
	}
	env, err := compiler.Compile("schemas/packet.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile envelope: %w", err)
	}
	return &Validator{envelope: env}, nil
}

func (v *Validator) ValidateEnvelope(raw []byte) error {
	var any interface{}
	if err := jsonUnmarshal(raw, &any); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return v.envelope.Validate(any)
}

// helpers kept tiny for readability
import_helpers_here := 0
_ = import_helpers_here
```

The tail lines are placeholders — replace with proper imports. Final `validator.go`:

```go
package server

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"

	"github.com/santhosh-tekuri/jsonschema/v5"
)

//go:embed schemas/*.schema.json
var schemaFS embed.FS

type Validator struct {
	envelope *jsonschema.Schema
}

func NewValidator() (*Validator, error) {
	compiler := jsonschema.NewCompiler()
	err := fs.WalkDir(schemaFS, "schemas", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, err := schemaFS.ReadFile(path)
		if err != nil {
			return err
		}
		return compiler.AddResource(path, bytes.NewReader(b))
	})
	if err != nil {
		return nil, err
	}
	env, err := compiler.Compile("schemas/packet.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile envelope: %w", err)
	}
	return &Validator{envelope: env}, nil
}

func (v *Validator) ValidateEnvelope(raw []byte) error {
	var doc interface{}
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return v.envelope.Validate(doc)
}

// keep io import used (silence unused) — can be removed once not needed:
var _ io.Reader = (*bytes.Reader)(nil)
```

Copy schemas into relay's embed path:

```bash
mkdir -p relay/internal/server/schemas
cp proto/*.schema.json relay/internal/server/schemas/
```

- [ ] **Step 6: Wire validator into /ws**

Modify `relay/internal/server/ws.go`:

```go
package server

import (
	"log"
	"net/http"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("upgrade: %v", err)
		return
	}
	defer conn.Close()
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if err := s.validator.ValidateEnvelope(msg); err != nil {
			_ = conn.WriteMessage(websocket.TextMessage, []byte(`{"error":"invalid envelope"}`))
			continue
		}
		_ = conn.WriteMessage(websocket.TextMessage, msg)
	}
}
```

Modify `relay/internal/server/server.go`:

```go
type Server struct {
	router    *chi.Mux
	validator *Validator
}

func New() *Server {
	v, err := NewValidator()
	if err != nil {
		panic(err)
	}
	s := &Server{router: chi.NewRouter(), validator: v}
	s.routes()
	return s
}
```

- [ ] **Step 7: Update existing WS echo test to use valid envelope**

Modify the test payload in `TestWebSocketEcho`:

```go
valid := `{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000}`
if err := c.WriteMessage(websocket.TextMessage, []byte(valid)); err != nil { ... }
_, msg, err := c.ReadMessage()
if string(msg) != valid {
    t.Fatalf("expected echo, got %q", string(msg))
}
```

- [ ] **Step 8: Add test for invalid-envelope rejection**

Append to `server_test.go`:

```go
func TestWebSocketRejectsInvalid(t *testing.T) {
	srv := New()
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	c, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	_ = c.WriteMessage(websocket.TextMessage, []byte(`{"garbage":true}`))
	_, msg, _ := c.ReadMessage()
	if !strings.Contains(string(msg), "invalid envelope") {
		t.Fatalf("expected error reply, got %q", string(msg))
	}
}
```

- [ ] **Step 9: Run all tests**

```bash
cd relay && go test ./... -v
```

Expected: all PASS.

- [ ] **Step 10: Commit**

```bash
git add relay/ proto/
git commit -m "feat(relay): validate envelopes against embedded JSON Schema on /ws"
```

---

## Task 8: Expo app scaffold

**Files:**

- Create: `mobile/package.json` (via `create-expo-app`)
- Create: `mobile/app/_layout.tsx`
- Create: `mobile/app/index.tsx`
- Create: `mobile/tsconfig.json`
- Create: `mobile/app.json`

- [ ] **Step 1: Scaffold Expo app**

Run from repo root:

```bash
cd /Users/moaieen.kirmani/Documents/projects/Learn/phone-sync
npx create-expo-app@latest mobile --template blank-typescript
```

Note: this creates a template. If the template differs from expectations, remove and re-scaffold:

```bash
rm -rf mobile/node_modules mobile/.expo
```

- [ ] **Step 2: Install Expo Router**

```bash
cd mobile && npx expo install expo-router react-native-safe-area-context react-native-screens expo-linking expo-constants expo-status-bar
```

- [ ] **Step 3: Configure Expo Router in app.json**

Overwrite `mobile/app.json`:

```json
{
  "expo": {
    "name": "phone-sync",
    "slug": "phone-sync",
    "version": "0.1.0",
    "orientation": "portrait",
    "icon": "./assets/icon.png",
    "userInterfaceStyle": "automatic",
    "scheme": "phonesync",
    "newArchEnabled": true,
    "ios": { "supportsTablet": false, "bundleIdentifier": "com.phonesync.app" },
    "android": {
      "package": "com.phonesync.app",
      "adaptiveIcon": { "foregroundImage": "./assets/adaptive-icon.png", "backgroundColor": "#000000" }
    },
    "plugins": ["expo-router"],
    "experiments": { "typedRoutes": true }
  }
}
```

- [ ] **Step 4: Set up router entry**

Remove `mobile/App.tsx` if present. Modify `mobile/package.json` `main`:

```json
{
  "main": "expo-router/entry"
}
```

Create `mobile/app/_layout.tsx`:

```tsx
import { Stack } from 'expo-router';

export default function RootLayout() {
  return <Stack />;
}
```

Create `mobile/app/index.tsx`:

```tsx
import { View, Text, Button, StyleSheet } from 'react-native';
import { useState } from 'react';

export default function Home() {
  const [status, setStatus] = useState<string>('idle');
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phone-Sync</Text>
      <Text>Status: {status}</Text>
      <Button title="Ping relay" onPress={() => setStatus('not implemented yet')} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24, gap: 12 },
  title: { fontSize: 24, fontWeight: '600' },
});
```

- [ ] **Step 5: Run typecheck**

```bash
cd mobile && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add mobile/
git commit -m "feat(mobile): Expo TS scaffold with Expo Router home screen"
```

---

## Task 9: Scaffold custom Expo Native Module (phone-sync-core)

**Files:**

- Create: `mobile/modules/phone-sync-core/expo-module.config.json`
- Create: `mobile/modules/phone-sync-core/package.json`
- Create: `mobile/modules/phone-sync-core/src/index.ts`
- Create: `mobile/modules/phone-sync-core/src/PhoneSyncCoreModule.ts`
- Create: `mobile/modules/phone-sync-core/android/build.gradle`
- Create: `mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/PhoneSyncCoreModule.kt`
- Create: `mobile/modules/phone-sync-core/ios/PhoneSyncCoreModule.swift`
- Modify: `mobile/package.json`
- Modify: `mobile/app.json`

- [ ] **Step 1: Generate module via CLI**

Run from `mobile/`:

```bash
cd mobile && npx create-expo-module@latest --local phone-sync-core
```

This creates `mobile/modules/phone-sync-core/` with standard scaffolding. Continue customizing only if the CLI output differs from below.

- [ ] **Step 2: Verify auto-generated structure**

Check files created:

```bash
ls mobile/modules/phone-sync-core/
ls mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/
```

Expected: `expo-module.config.json`, `package.json`, `src/`, `android/`, `ios/` directories present.

- [ ] **Step 3: Replace Kotlin module with a `ping` function**

Overwrite `mobile/modules/phone-sync-core/android/src/main/java/expo/modules/phonesynccore/PhoneSyncCoreModule.kt`:

```kotlin
package expo.modules.phonesynccore

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

class PhoneSyncCoreModule : Module() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun definition() = ModuleDefinition {
        Name("PhoneSyncCore")

        AsyncFunction("ping") { relayUrl: String, promise: expo.modules.kotlin.Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = Request.Builder().url(relayUrl).build()
                    val msgId = UUID.randomUUID().toString()
                    val envelope = """{"v":1,"type":"ping","msg_id":"$msgId","origin_device":"mobile","ts":${System.currentTimeMillis()}}"""
                    val ws = client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(envelope)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            webSocket.close(1000, "done")
                            promise.resolve(text)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            promise.reject("PING_FAILED", t.message ?: "unknown", t)
                        }
                    })
                    // shut the client's exec pool down after a window if nothing came back
                    kotlinx.coroutines.delay(10_000)
                    ws.cancel()
                } catch (e: Exception) {
                    promise.reject("PING_ERROR", e.message ?: "unknown", e)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Add OkHttp to module's Gradle**

Modify `mobile/modules/phone-sync-core/android/build.gradle` — add inside `dependencies { ... }`:

```gradle
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

- [ ] **Step 5: Update TS surface**

Overwrite `mobile/modules/phone-sync-core/src/PhoneSyncCoreModule.ts`:

```ts
import { NativeModule, requireNativeModule } from 'expo-modules-core';

declare class PhoneSyncCoreModuleType extends NativeModule {
  ping(relayUrl: string): Promise<string>;
}

export default requireNativeModule<PhoneSyncCoreModuleType>('PhoneSyncCore');
```

Overwrite `mobile/modules/phone-sync-core/src/index.ts`:

```ts
import PhoneSyncCoreModule from './PhoneSyncCoreModule';

export async function pingRelay(relayUrl: string): Promise<string> {
  return await PhoneSyncCoreModule.ping(relayUrl);
}
```

- [ ] **Step 6: Stub iOS module**

Overwrite `mobile/modules/phone-sync-core/ios/PhoneSyncCoreModule.swift`:

```swift
import ExpoModulesCore

public class PhoneSyncCoreModule: Module {
  public func definition() -> ModuleDefinition {
    Name("PhoneSyncCore")

    AsyncFunction("ping") { (relayUrl: String) -> String in
      throw Exception(name: "UnsupportedPlatform", description: "phone-sync-core is Android-only in v1")
    }
  }
}
```

- [ ] **Step 7: Typecheck**

```bash
cd mobile && npx tsc --noEmit
```

Expected: no errors (module resolves via `requireNativeModule`).

- [ ] **Step 8: Commit**

```bash
git add mobile/modules/ mobile/app.json mobile/package.json
git commit -m "feat(mobile): custom Expo Native Module phone-sync-core with Kotlin ping() over WS"
```

---

## Task 10: Wire `pingRelay()` into the home screen

**Files:**

- Modify: `mobile/app/index.tsx`

- [ ] **Step 1: Call the module from the button**

Overwrite `mobile/app/index.tsx`:

```tsx
import { View, Text, Button, TextInput, StyleSheet } from 'react-native';
import { useState } from 'react';
import { pingRelay } from '../modules/phone-sync-core/src';

export default function Home() {
  const [url, setUrl] = useState<string>('ws://10.0.2.2:8080/ws');
  const [status, setStatus] = useState<string>('idle');

  async function handlePing() {
    setStatus('pinging…');
    try {
      const res = await pingRelay(url);
      setStatus(`ok: ${res}`);
    } catch (e: unknown) {
      setStatus(`error: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phone-Sync</Text>
      <TextInput style={styles.input} value={url} onChangeText={setUrl} autoCapitalize="none" />
      <Button title="Ping relay" onPress={handlePing} />
      <Text style={styles.status}>{status}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'stretch', padding: 24, gap: 12 },
  title: { fontSize: 24, fontWeight: '600', textAlign: 'center' },
  input: { borderWidth: 1, borderColor: '#888', padding: 8, borderRadius: 4 },
  status: { marginTop: 16, textAlign: 'center', fontFamily: 'monospace' },
});
```

Note: `10.0.2.2` is the Android emulator's alias for the host machine. On a physical device, use the laptop's LAN IP (e.g., `ws://192.168.1.42:8080/ws`).

- [ ] **Step 2: Typecheck**

```bash
cd mobile && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add mobile/app/index.tsx
git commit -m "feat(mobile): home screen wires pingRelay from native module"
```

---

## Task 11: EAS Build config for dev client

**Files:**

- Create: `mobile/eas.json`
- Modify: `mobile/package.json` (scripts)

- [ ] **Step 1: Install EAS CLI (user-level)**

```bash
npm install -g eas-cli
```

- [ ] **Step 2: Write eas.json**

Create `mobile/eas.json`:

```json
{
  "cli": { "version": ">= 10.0.0" },
  "build": {
    "development": {
      "developmentClient": true,
      "distribution": "internal",
      "android": { "buildType": "apk" }
    },
    "preview": {
      "distribution": "internal",
      "android": { "buildType": "apk" }
    },
    "production": {}
  }
}
```

- [ ] **Step 3: Add scripts to package.json**

Modify `mobile/package.json` — add to `scripts`:

```json
{
  "scripts": {
    "android": "expo run:android",
    "prebuild": "expo prebuild --clean",
    "build:dev": "eas build --profile development --platform android --local",
    "typecheck": "tsc --noEmit"
  }
}
```

- [ ] **Step 4: Local prebuild check**

```bash
cd mobile && npx expo prebuild --platform android --clean
```

Expected: `android/` directory generated. Gradle sync may take 2–5 min.

- [ ] **Step 5: Commit**

```bash
git add mobile/eas.json mobile/package.json mobile/android/
git commit -m "chore(mobile): EAS Build config + local prebuild for Android"
```

Note: committing `android/` is optional. Many projects `.gitignore` it since `prebuild` regenerates. Decide per project policy. For now, keep it out:

```bash
echo "mobile/android/" >> .gitignore
echo "mobile/ios/" >> .gitignore
git rm -r --cached mobile/android mobile/ios 2>/dev/null || true
git add .gitignore
git commit -m "chore: ignore prebuild output"
```

---

## Task 12: Manual end-to-end smoke test

**Files:** none (manual verification)

- [ ] **Step 1: Start the relay**

```bash
cd deploy && docker compose up -d relay
# OR direct:
cd relay && go run ./cmd/relay &
```

Verify:

```bash
curl -sf http://localhost:8080/health
```

Expected: `{"status":"ok"}`.

- [ ] **Step 2: Find laptop LAN IP**

```bash
ipconfig getifaddr en0    # macOS WiFi
# or
hostname -I | awk '{print $1}'    # Linux
```

Record it, e.g., `192.168.1.42`.

- [ ] **Step 3: Build and install the dev client on Android device/emulator**

```bash
cd mobile
# Emulator:
npx expo run:android
# OR physical device (connected via ADB):
npx expo run:android --device
```

Expected: app launches to home screen after first build (~3–8 min).

- [ ] **Step 4: Enter ws URL and tap Ping**

In the app:

- Emulator: `ws://10.0.2.2:8080/ws`
- Physical device: `ws://192.168.1.42:8080/ws`

Tap "Ping relay".

Expected: status field shows `ok: {"v":1,"type":"ping",...}` (the echo of the envelope the Kotlin side sent).

- [ ] **Step 5: Verify relay logs saw the request**

```bash
# if using go run:
# check the foreground log output

# if docker:
docker compose logs relay
```

Expected: no errors; WebSocket upgrade completed.

- [ ] **Step 6: Document the result in docs/test-scenarios.md**

Create `docs/test-scenarios.md`:

```markdown
# Phone-Sync Test Scenarios

## Phase 1 — Smoke Test

**Ping round-trip (mobile → relay → mobile):**

- Environment: Android emulator OR physical device on same LAN as relay host.
- Steps: see plans/2026-04-20-phase-1-relay-proto-scaffold.md Task 12.
- Expected: status on home screen shows echoed envelope.
- Passed: ☐ date: __________ device: __________
```

- [ ] **Step 7: Commit**

```bash
git add docs/test-scenarios.md
git commit -m "docs: Phase 1 smoke test procedure"
```

---

## Task 13: GitHub Actions CI for relay and mobile typecheck

**Files:**

- Create: `.github/workflows/relay.yml`
- Create: `.github/workflows/mobile.yml`

- [ ] **Step 1: Write relay workflow**

Create `.github/workflows/relay.yml`:

```yaml
name: relay
on:
  push:
    paths: ['relay/**', 'proto/**', '.github/workflows/relay.yml']
  pull_request:
    paths: ['relay/**', 'proto/**', '.github/workflows/relay.yml']

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: relay
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'
      - name: Sync proto into relay embed
        run: |
          mkdir -p internal/server/schemas
          cp ../proto/*.schema.json internal/server/schemas/
      - run: go mod download
      - run: go vet ./...
      - run: go test ./... -race -count=1

  docker:
    runs-on: ubuntu-latest
    needs: test
    steps:
      - uses: actions/checkout@v4
      - name: Sync proto into relay embed
        run: |
          mkdir -p relay/internal/server/schemas
          cp proto/*.schema.json relay/internal/server/schemas/
      - uses: docker/setup-buildx-action@v3
      - uses: docker/build-push-action@v6
        with:
          context: ./relay
          push: false
          tags: phone-sync-relay:ci
```

- [ ] **Step 2: Write mobile workflow**

Create `.github/workflows/mobile.yml`:

```yaml
name: mobile
on:
  push:
    paths: ['mobile/**', '.github/workflows/mobile.yml']
  pull_request:
    paths: ['mobile/**', '.github/workflows/mobile.yml']

jobs:
  typecheck:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: mobile
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: mobile/package-lock.json
      - run: npm ci
      - run: npx tsc --noEmit
      - run: npx expo-doctor
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/
git commit -m "ci: relay tests + docker build; mobile typecheck + expo-doctor"
```

---

## Completion Criteria

Phase 1 is done when all of these hold:

- [ ] `cd relay && go test ./... -race -count=1` → all green.
- [ ] `cd mobile && npx tsc --noEmit` → no errors.
- [ ] `docker compose up -d relay` → container healthy; `curl http://localhost:8080/health` returns `ok`.
- [ ] Mobile app on emulator OR physical device pings the relay and sees the echoed envelope in the status line.
- [ ] Both GitHub Actions workflows pass on the commit.
- [ ] `/proto/packet.schema.json` validates the ping envelope used in tests.

Next plan: **Phase 2 — Crypto + Pairing** (Keystore-wrapped libsodium, QR pairing, msg_id replay protection, JWT auth against stored sign_pubkey). Will live at `docs/superpowers/plans/2026-04-20-phase-2-crypto-pairing.md`.
