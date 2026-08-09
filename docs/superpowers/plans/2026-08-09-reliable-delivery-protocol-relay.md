# Reliable Delivery Protocol and Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the versioned protocol and durable Go relay required for lossless, authenticated, bounded notification delivery.

**Architecture:** Protocol v2 wraps authenticated inner event metadata in an opaque encrypted envelope. The relay persists envelopes in a per-recipient BoltDB mailbox before accepting them, streams them to authenticated peers, and deletes them only after the recipient acknowledges the exact digest. Legacy v1 forwarding remains explicitly online-only until both devices negotiate v2.

**Tech Stack:** Go 1.23, chi, gorilla/websocket, bbolt, JSON Schema 2020-12, Docker Compose, Caddy, Make, GitHub Actions

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-09-reliable-delivery-foundation-design.md` exactly.
- Relay retention is 24 hours from first durable acceptance.
- Maximum encrypted envelope is 1 MiB.
- Per-recipient mailbox caps are 2,000 pending items and 128 MiB.
- Mailbox overflow returns explicit backpressure and never evicts accepted ciphertext.
- The relay must never inspect notification plaintext, canonical IDs, sequences, titles, text, actions, or icons.
- Normal v2 acceptance means durable relay storage, not peer delivery.
- Go tests run with the race detector before every task commit.
- Do not weaken v1 compatibility until both devices have negotiated protocol floor 2.
- No source task is complete without a failing test observed first.

---

## File Structure

### Protocol contract

- Create `proto/inner-event-v2.schema.json`: authenticated decrypted event envelope.
- Create `proto/peer-receipt.schema.json`: receipt payload and terminal statuses.
- Create `proto/relay-control.schema.json`: client/relay transport control frames.
- Modify `proto/envelope-encrypted.schema.json`: versioned v1/v2 outer envelope.
- Modify `proto/packet.schema.json`: remove the conflicting role as the encrypted wire validator and document legacy use.
- Create `proto/fixtures/v2-valid/*.json`: committed positive golden frames.
- Create `proto/fixtures/v2-invalid/*.json`: committed negative fixtures plus expected failure names.
- Create `proto/fixtures/manifest.json`: maps every fixture to its validator and expected stable result.

### Relay persistence and transport

- Modify `relay/internal/store/bolt.go`: expose transaction helpers only inside the store package.
- Create `relay/internal/store/mailbox_store.go`: atomic mailbox, quota, expiry, status, and purge operations.
- Create `relay/internal/store/mailbox_store_test.go`: persistence and invariant tests.
- Modify `relay/internal/store/pair_store.go`: atomic capability floor and revocation operations.
- Modify `relay/internal/store/pair_store_test.go`: pairing-state and revocation tests.
- Create `relay/internal/server/relay_frame.go`: typed relay control parsing and serialization.
- Modify `relay/internal/server/validator.go`: compile and validate the correct v1/v2 schemas.
- Modify `relay/internal/server/validator_test.go`: golden fixture coverage.
- Modify `relay/internal/server/client_hub.go`: race-free connection replacement.
- Modify `relay/internal/server/pair_hub.go`: race-free subscription replacement.
- Create `relay/internal/server/hub_race_test.go`: concurrent send/register/unregister stress coverage.
- Modify `relay/internal/server/ws.go`: v2 hello, mailbox put/deliver/ack/expiry, and explicit v1 path.
- Create `relay/internal/server/ws_mailbox_test.go`: authenticated WebSocket delivery scenarios.
- Modify `relay/internal/server/server.go`: mailbox/capability dependencies and revoke route.
- Create `relay/internal/server/revoke.go`: authenticated pair revocation handler.
- Create `relay/internal/server/revoke_test.go`: purge and authorization tests.
- Modify `relay/internal/server/pair_notify.go`: replay persisted pending-pair state to late subscribers.
- Modify `relay/internal/server/pair_notify_test.go`: Device A-before-B race and idempotency tests.
- Create `relay/internal/server/http_limits.go`: request bounds and pairing rate limits.
- Modify `relay/cmd/relay/main.go`: HTTP timeouts and maintenance lifecycle.

### Deployment and verification

- Modify `Makefile`: protocol fixture and complete relay verification targets.
- Modify `.github/workflows/relay.yml`: run fixture validation and race tests.
- Modify `deploy/docker-compose.yml`: persist Bolt at `/data/twinotify-relay.db` for development.
- Create `deploy/docker-compose.prod.yml`: TLS-only public profile.
- Modify `deploy/caddy/Caddyfile`: production host parameter and internal relay proxy.
- Modify `relay/README.md`: v1/v2 semantics, limits, TLS, persistence, backup, and rollout.

---

### Task 1: Make Protocol v2 Executable

**Files:**

- Create: `proto/inner-event-v2.schema.json`
- Create: `proto/peer-receipt.schema.json`
- Create: `proto/relay-control.schema.json`
- Modify: `proto/envelope-encrypted.schema.json`
- Modify: `proto/packet.schema.json`
- Create: `proto/fixtures/v2-valid/relay-put.json`
- Create: `proto/fixtures/v2-valid/peer-receipt-inner.json`
- Create: `proto/fixtures/v2-invalid/outer-inner-id-mismatch.json`
- Modify: `relay/internal/server/validator.go`
- Modify: `relay/internal/server/validator_test.go`

**Interfaces:**

- Produces: `Validator.ValidateEncryptedEnvelope(raw []byte) error`
- Produces: `Validator.ValidateRelayControl(raw []byte) error`
- Produces: schemas with `$id` values under `https://twinotify.app/schemas/`
- Consumes: no new runtime interfaces

- [ ] **Step 1: Add failing validator tests for v2 outer and relay control frames**

Add these table cases to `relay/internal/server/validator_test.go`:

```go
func TestValidatorV2Contracts(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	validEnvelope := []byte(`{"v":2,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncryptedEnvelope(validEnvelope); err != nil {
		t.Fatalf("valid v2 envelope: %v", err)
	}
	validPut := []byte(`{"v":2,"type":"relay.put","envelope":{"v":2,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}}`)
	if err := v.ValidateRelayControl(validPut); err != nil {
		t.Fatalf("valid relay.put: %v", err)
	}
}

func TestValidatorRejectsOversizedOrUnknownRelayControl(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range [][]byte{
		[]byte(`{"v":2,"type":"relay.unknown"}`),
		[]byte(`{"v":2,"type":"relay.ack","msg_id":"not-a-uuid","envelope_sha256":"bad"}`),
	} {
		if err := v.ValidateRelayControl(raw); err == nil {
			t.Fatalf("expected rejection for %s", raw)
		}
	}
}
```

- [ ] **Step 2: Run the focused tests and observe the missing-method/schema failure**

Run:

```bash
make sync-proto
cd relay && go test ./internal/server -run 'TestValidatorV2Contracts|TestValidatorRejectsOversizedOrUnknownRelayControl' -count=1
```

Expected: compile failure because `ValidateEncryptedEnvelope` and `ValidateRelayControl` do not exist.

- [ ] **Step 3: Add exact v2 schemas**

Create `proto/inner-event-v2.schema.json` with required authenticated metadata:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://twinotify.app/schemas/inner-event-v2.schema.json",
  "type": "object",
  "required": ["v", "msg_id", "origin_device", "type", "created_at", "expires_at", "payload"],
  "properties": {
    "v": { "const": 2 },
    "msg_id": { "type": "string", "format": "uuid" },
    "origin_device": { "type": "string", "minLength": 1, "maxLength": 128 },
    "type": { "type": "string", "enum": ["notif.post", "notif.update", "notif.cancel", "peer.receipt", "state.digest", "state.snapshot.begin", "state.snapshot.item", "state.snapshot.end", "unpair"] },
    "canon_id": { "type": "string", "minLength": 1, "maxLength": 1024 },
    "sequence": { "type": "integer", "minimum": 1 },
    "created_at": { "type": "integer", "minimum": 0 },
    "expires_at": { "type": "integer", "minimum": 0 },
    "payload": { "type": "object" }
  },
  "allOf": [
    {
      "if": { "properties": { "type": { "enum": ["notif.post", "notif.update", "notif.cancel", "state.snapshot.item"] } } },
      "then": { "required": ["canon_id", "sequence"] }
    }
  ],
  "additionalProperties": false
}
```

Create `proto/peer-receipt.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://twinotify.app/schemas/peer-receipt.schema.json",
  "type": "object",
  "required": ["acked_msg_id", "envelope_sha256", "status"],
  "properties": {
    "acked_msg_id": { "type": "string", "format": "uuid" },
    "envelope_sha256": { "type": "string", "pattern": "^[0-9a-f]{64}$" },
    "status": { "type": "string", "enum": ["applied", "expired", "rejected", "decrypt_failed"] },
    "reason": { "type": "string", "maxLength": 128 }
  },
  "additionalProperties": false
}
```

Create `proto/relay-control.schema.json` with the complete transport union:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://twinotify.app/schemas/relay-control.schema.json",
  "$defs": {
    "base": {
      "type": "object",
      "required": ["v", "type"],
      "properties": { "v": { "const": 2 }, "type": { "type": "string" } }
    },
    "msg_id": { "type": "string", "format": "uuid" },
    "digest": { "type": "string", "pattern": "^[0-9a-f]{64}$" },
    "protocols": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "integer", "enum": [1, 2] }
    }
  },
  "oneOf": [
    {
      "type": "object",
      "required": ["v", "type", "protocols", "app_version"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.hello" },
        "protocols": { "$ref": "#/$defs/protocols" },
        "app_version": { "type": "string", "minLength": 1, "maxLength": 32 }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "envelope"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.put" },
        "envelope": { "$ref": "envelope-encrypted.schema.json" }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "msg_id", "envelope_sha256"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.ack" },
        "msg_id": { "$ref": "#/$defs/msg_id" },
        "envelope_sha256": { "$ref": "#/$defs/digest" }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "msg_id", "accepted_at"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.accepted" },
        "msg_id": { "$ref": "#/$defs/msg_id" },
        "accepted_at": { "type": "integer", "minimum": 0 }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "msg_id"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.legacy_forwarded" },
        "msg_id": { "$ref": "#/$defs/msg_id" }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "accepted_at", "envelope"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.deliver" },
        "accepted_at": { "type": "integer", "minimum": 0 },
        "envelope": { "$ref": "envelope-encrypted.schema.json" }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "msg_id", "reason"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.rejected" },
        "msg_id": { "$ref": "#/$defs/msg_id" },
        "reason": { "type": "string", "enum": ["mailbox_full", "id_conflict", "digest_mismatch", "not_recipient", "peer_legacy", "invalid_frame"] }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "msg_id", "expired_at"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.expired" },
        "msg_id": { "$ref": "#/$defs/msg_id" },
        "expired_at": { "type": "integer", "minimum": 0 }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "self", "peer", "floor"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "relay.capabilities" },
        "self": { "$ref": "#/$defs/protocols" },
        "peer": { "$ref": "#/$defs/protocols" },
        "floor": { "type": "integer", "enum": [1, 2] }
      },
      "additionalProperties": false
    }
  ]
}
```

Replace `proto/envelope-encrypted.schema.json` with:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://twinotify.app/schemas/envelope-encrypted.schema.json",
  "oneOf": [
    {
      "type": "object",
      "required": ["v", "type", "msg_id", "origin_device", "ts", "nonce", "ciphertext"],
      "properties": {
        "v": { "const": 1 }, "type": { "const": "enc" },
        "msg_id": { "type": "string", "format": "uuid" },
        "origin_device": { "type": "string", "minLength": 1, "maxLength": 128 },
        "ts": { "type": "integer", "minimum": 0 },
        "nonce": { "type": "string", "contentEncoding": "base64", "minLength": 32, "maxLength": 32 },
        "ciphertext": { "type": "string", "contentEncoding": "base64", "minLength": 1 }
      },
      "additionalProperties": false
    },
    {
      "type": "object",
      "required": ["v", "type", "msg_id", "origin_device", "created_at", "nonce", "ciphertext"],
      "properties": {
        "v": { "const": 2 }, "type": { "const": "enc" },
        "msg_id": { "type": "string", "format": "uuid" },
        "origin_device": { "type": "string", "minLength": 1, "maxLength": 128 },
        "created_at": { "type": "integer", "minimum": 0 },
        "nonce": { "type": "string", "contentEncoding": "base64", "minLength": 32, "maxLength": 32 },
        "ciphertext": { "type": "string", "contentEncoding": "base64", "minLength": 1 }
      },
      "additionalProperties": false
    }
  ]
}
```

Retain the 1 MiB runtime byte limit in Go because JSON Schema counts characters rather than decoded frame bytes.

Keep `proto/packet.schema.json` as an explicitly deprecated v1 cleartext packet schema: set its description to `Legacy v1 cleartext packet; never use for encrypted WebSocket validation`, keep `v` fixed at 1, add the already implemented `unpair` type, and do not use it for a frame whose outer `type` is `enc`. Task 4 routes encrypted v1 and v2 frames through `ValidateEncryptedEnvelope`; only legacy cleartext ping/test frames use `ValidateLegacyPacket`.

- [ ] **Step 4: Compile separate schemas in `Validator`**

Replace the ambiguous validator fields with:

```go
type Validator struct {
	legacyPacket *jsonschema.Schema
	encrypted    *jsonschema.Schema
	relayControl *jsonschema.Schema
	innerV2      *jsonschema.Schema
	peerReceipt  *jsonschema.Schema
}

func validateJSON(schema *jsonschema.Schema, raw []byte) error {
	var doc any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return schema.Validate(doc)
}

func (v *Validator) ValidateLegacyPacket(raw []byte) error {
	return validateJSON(v.legacyPacket, raw)
}

func (v *Validator) ValidateEncryptedEnvelope(raw []byte) error {
	return validateJSON(v.encrypted, raw)
}

func (v *Validator) ValidateRelayControl(raw []byte) error {
	return validateJSON(v.relayControl, raw)
}
```

Compile all five schemas in `NewValidator`. Remove `ValidateEnvelope` call sites only when Task 4 adds explicit legacy/v2 routing.

- [ ] **Step 5: Add committed positive and negative fixtures**

Store the exact JSON from the tests as `proto/fixtures/v2-valid/relay-put.json`. Store a complete decrypted `peer.receipt` inner event as `peer-receipt-inner.json`. Store an outer and inner pair with different IDs in the invalid directory; the Kotlin cross-layer test in the Android plan will prove mismatch rejection after decryption.

- [ ] **Step 6: Run schema and relay tests**

Run:

```bash
make sync-proto
cd relay && go test ./internal/server -run Validator -count=1
```

Expected: all validator tests pass.

- [ ] **Step 7: Commit the protocol contract**

```bash
git add proto relay/internal/server/validator.go relay/internal/server/validator_test.go
git commit -m "feat(proto): define reliable delivery v2 contracts"
```

---

### Task 2: Add an Atomic Durable Mailbox Store

**Files:**

- Create: `relay/internal/store/mailbox_store.go`
- Create: `relay/internal/store/mailbox_store_test.go`
- Modify: `relay/internal/store/bolt.go`

**Interfaces:**

- Produces: `NewMailboxStore(b *Bolt, limits MailboxLimits) *MailboxStore`
- Produces: `MailboxStore.Put(rec MailboxRecord, now time.Time) (PutResult, error)`
- Produces: `MailboxStore.Pending(recipient string, limit int) ([]MailboxRecord, error)`
- Produces: `MailboxStore.Ack(recipient, msgID, digest string, now time.Time) error`
- Produces: `MailboxStore.Expire(now time.Time) ([]ExpiredRecord, error)`
- Produces: `MailboxStore.ExpireStatuses(now time.Time) error`
- Produces: `MailboxStore.Statuses(sender string, since time.Time) ([]DeliveryStatus, error)`
- Produces: `MailboxStore.PurgePair(deviceA, deviceB string) error`
- Consumes: opaque validated envelope bytes and authenticated pair ownership from the server layer

- [ ] **Step 1: Write failing mailbox persistence and quota tests**

Create `relay/internal/store/mailbox_store_test.go` with tests that use a real temporary Bolt file:

```go
func TestMailboxPutIsIdempotentAndSurvivesReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "mailbox.db")
	b, err := OpenBolt(path)
	if err != nil { t.Fatal(err) }
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: 24 * time.Hour})
	rec := MailboxRecord{RecipientDevice: "dev-b", SenderDevice: "dev-a", MsgID: "11111111-1111-4111-8111-111111111111", EnvelopeSHA256: strings.Repeat("a", 64), Envelope: []byte(`{"v":2}`)}
	first, err := s.Put(rec, time.UnixMilli(1000))
	if err != nil { t.Fatal(err) }
	second, err := s.Put(rec, time.UnixMilli(2000))
	if err != nil { t.Fatal(err) }
	if first.AcceptedAt != second.AcceptedAt { t.Fatalf("acceptance time changed: %v != %v", first, second) }
	if err := b.Close(); err != nil { t.Fatal(err) }
	b, err = OpenBolt(path)
	if err != nil { t.Fatal(err) }
	defer b.Close()
	got, err := NewMailboxStore(b, MailboxLimits{MaxItems: 2, MaxBytes: 1024, Retention: 24 * time.Hour}).Pending("dev-b", 10)
	if err != nil { t.Fatal(err) }
	if len(got) != 1 || got[0].MsgID != rec.MsgID { t.Fatalf("pending = %#v", got) }
}

func TestMailboxRejectsCapacityWithoutEviction(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, MailboxLimits{MaxItems: 1, MaxBytes: 1024, Retention: 24 * time.Hour})
	if _, err := s.Put(testMailboxRecord("11111111-1111-4111-8111-111111111111", "a"), time.UnixMilli(1000)); err != nil { t.Fatal(err) }
	if _, err := s.Put(testMailboxRecord("22222222-2222-4222-8222-222222222222", "b"), time.UnixMilli(2000)); !errors.Is(err, ErrMailboxFull) { t.Fatalf("err = %v", err) }
	got, err := s.Pending("dev-b", 10)
	if err != nil { t.Fatal(err) }
	if len(got) != 1 || got[0].MsgID != "11111111-1111-4111-8111-111111111111" { t.Fatalf("mailbox was evicted: %#v", got) }
}

func TestMailboxRejectsDigestConflict(t *testing.T) {
	b := openTestBolt(t)
	s := NewMailboxStore(b, DefaultMailboxLimits())
	rec := testMailboxRecord("11111111-1111-4111-8111-111111111111", "a")
	if _, err := s.Put(rec, time.UnixMilli(1000)); err != nil { t.Fatal(err) }
	rec.EnvelopeSHA256 = strings.Repeat("b", 64)
	if _, err := s.Put(rec, time.UnixMilli(1001)); !errors.Is(err, ErrMessageIDConflict) { t.Fatalf("err = %v", err) }
}
```

Define the helpers in the same test file:

```go
func openTestBolt(t *testing.T) *Bolt {
	t.Helper()
	b, err := OpenBolt(filepath.Join(t.TempDir(), "mailbox.db"))
	if err != nil { t.Fatal(err) }
	t.Cleanup(func() { _ = b.Close() })
	return b
}

func testMailboxRecord(msgID, digestSeed string) MailboxRecord {
	return MailboxRecord{
		RecipientDevice: "dev-b",
		SenderDevice: "dev-a",
		MsgID: msgID,
		EnvelopeSHA256: strings.Repeat(digestSeed, 64),
		Envelope: []byte(`{"v":2,"type":"enc"}`),
	}
}
```

- [ ] **Step 2: Run the focused store tests and observe missing types**

Run:

```bash
cd relay && go test ./internal/store -run Mailbox -count=1
```

Expected: compile failure because the mailbox types do not exist.

- [ ] **Step 3: Implement store types and atomic bucket layout**

Define:

```go
var (
	ErrMailboxFull       = errors.New("mailbox full")
	ErrMessageIDConflict = errors.New("message id conflict")
	ErrDigestMismatch    = errors.New("digest mismatch")
)

type MailboxLimits struct {
	MaxItems int
	MaxBytes uint64
	Retention time.Duration
}

func DefaultMailboxLimits() MailboxLimits {
	return MailboxLimits{MaxItems: 2000, MaxBytes: 128 << 20, Retention: 24 * time.Hour}
}

type MailboxRecord struct {
	RecipientDevice string `json:"recipient_device"`
	SenderDevice string `json:"sender_device"`
	MsgID string `json:"msg_id"`
	EnvelopeSHA256 string `json:"envelope_sha256"`
	Envelope []byte `json:"envelope"`
	ByteSize uint64 `json:"byte_size"`
	AcceptedAt int64 `json:"accepted_at"`
	ExpiresAt int64 `json:"expires_at"`
}

type PutResult struct { AcceptedAt int64; Duplicate bool }

type DeliveryStatus struct {
	SenderDevice string `json:"sender_device"`
	RecipientDevice string `json:"recipient_device"`
	MsgID string `json:"msg_id"`
	Status string `json:"status"`
	OccurredAt int64 `json:"occurred_at"`
	ExpiresAt int64 `json:"expires_at"`
}
```

Use one Bolt update transaction per `Put`, `Ack`, `Expire`, or `PurgePair`. Within it, maintain:

```text
mailbox_items: recipient + 0x00 + msg_id -> JSON MailboxRecord
mailbox_order: recipient + 0x00 + big-endian accepted_at + 0x00 + msg_id -> empty
mailbox_stats: recipient -> big-endian count + bytes
mailbox_status: sender + 0x00 + msg_id -> metadata-only DeliveryStatus
```

Copy returned values before the transaction closes. Validate caller-provided record fields before entering Bolt. Compute `ByteSize` from `len(Envelope)` inside `Put`; do not trust a caller's size.

- [ ] **Step 4: Add ACK, expiry, and purge tests before their implementations**

Tests must prove:

```go
func TestMailboxAckRequiresRecipientAndDigest(t *testing.T)
func TestMailboxAckDeletesCiphertextAndRetainsStatus(t *testing.T)
func TestMailboxExpireUsesAcceptedAtAndReportsSender(t *testing.T)
func TestMailboxPurgePairDeletesBothDirections(t *testing.T)
func TestMailboxQuotaCountersRemainCorrectAfterAckAndExpire(t *testing.T)
```

Run each test once and confirm it fails for the absent method or behavior before implementing it.

- [ ] **Step 5: Implement ACK, expiry, status, and purge transactions**

`Ack` must compare recipient and lowercase digest using constant-time byte comparison after hex decoding. It deletes the ciphertext and order index, decrements statistics, and writes `DeliveryStatus{Status: "acknowledged"}` in the same transaction. `Expire` deletes ciphertext whose stored `ExpiresAt <= now`, writes `Status: "expired"` with a status expiry 24 hours later, and returns sender-addressed metadata. `ExpireStatuses` deletes only metadata tombstones past that second expiry. `PurgePair` scans only prefixes for the two device IDs and removes items, order indexes, statuses, and statistics atomically. Implement the prefix-removal body as `purgePairTx(tx *bbolt.Tx, deviceA, deviceB string) error` so Task 7 can compose it with pair-index deletion in the same Bolt transaction.

- [ ] **Step 6: Run store tests with race detection**

```bash
cd relay && go test ./internal/store -race -count=1
```

Expected: all store tests pass with no race report.

- [ ] **Step 7: Commit the mailbox store**

```bash
git add relay/internal/store
git commit -m "feat(relay): add durable bounded mailbox store"
```

---

### Task 3: Remove Hub Send-versus-Close Races

**Files:**

- Modify: `relay/internal/server/client_hub.go`
- Modify: `relay/internal/server/pair_hub.go`
- Create: `relay/internal/server/hub_race_test.go`

**Interfaces:**

- Preserves: `ClientHub.Register`, `ClientHub.Unregister`, `ClientHub.Send`
- Preserves: `PairHub.Subscribe`, `PairHub.Unsubscribe`, `PairHub.Push`
- Changes: returned registrations own an idempotent cancellation signal; producer-visible outbound channels are never closed

- [ ] **Step 1: Add concurrent stress tests that reproduce both races**

Create tests with 1,000 iterations each. Synchronize goroutine start with a closed start channel, run `Send` against repeated `Register`/`Unregister`, and run `Push` against repeated `Subscribe`/`Unsubscribe`. A panic must fail the test rather than being recovered as success.

Core shape:

```go
func TestClientHubConcurrentReplaceAndSend(t *testing.T) {
	h := NewClientHub()
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			<-start
			for n := 0; n < 1000; n++ {
				out := make(chan []byte, 1)
				c := h.Register("dev", out)
				h.Send("dev", []byte("x"))
				h.Unregister(c)
			}
		}(i)
	}
	close(start)
	wg.Wait()
}
```

- [ ] **Step 2: Run with the race detector and observe the race or panic**

```bash
cd relay && go test ./internal/server -run 'Test(Client|Pair)HubConcurrent' -race -count=20
```

Expected on the old implementation: race detector output or `send on closed channel`.

- [ ] **Step 3: Replace closeable producer channels with cancellation-owned registrations**

Use:

```go
type wsClient struct {
	deviceID string
	outbound chan []byte
	done chan struct{}
	stopOnce sync.Once
}

func (c *wsClient) stop() { c.stopOnce.Do(func() { close(c.done) }) }
```

`Register` stops the prior registration while holding the mutex but never closes `outbound`. `Send` holds the hub mutex through its nonblocking select so replacement cannot occur between lookup and enqueue:

```go
func (h *ClientHub) Send(deviceID string, frame []byte) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok { return false }
	select {
	case <-c.done:
		return false
	case c.outbound <- append([]byte(nil), frame...):
		return true
	default:
		return false
	}
}
```

Make the WebSocket writer select on `client.done` and `client.outbound`. Apply the same lifetime model to PairHub subscriptions.

- [ ] **Step 4: Run stress and the full relay suite**

```bash
cd relay && go test ./internal/server -run 'Test(Client|Pair)HubConcurrent' -race -count=20
cd relay && go test ./... -race -count=1
```

Expected: all tests pass without a race report or panic.

- [ ] **Step 5: Commit the race fix**

```bash
git add relay/internal/server/client_hub.go relay/internal/server/pair_hub.go relay/internal/server/hub_race_test.go relay/internal/server/ws.go relay/internal/server/pair_notify.go
git commit -m "fix(relay): make hub connection replacement race-free"
```

---

### Task 4: Add Typed Relay Frames and Durable WebSocket Delivery

**Files:**

- Create: `relay/internal/server/relay_frame.go`
- Modify: `relay/internal/server/server.go`
- Modify: `relay/internal/server/ws.go`
- Create: `relay/internal/server/ws_mailbox_test.go`

**Interfaces:**

- Consumes: `MailboxStore` from Task 2
- Consumes: v2 validators from Task 1
- Produces: `relay.hello`, `relay.capabilities`, `relay.put`, `relay.accepted`, `relay.legacy_forwarded`, `relay.deliver`, `relay.ack`, `relay.rejected`, and `relay.expired` WebSocket behavior
- Preserves: raw v1 online forwarding for legacy clients

- [ ] **Step 1: Add a failing offline-delivery WebSocket test**

The test must pair A and B, connect only A, send `relay.hello`, send a valid `relay.put`, assert A receives `relay.accepted`, disconnect A, connect B, send `relay.hello`, and assert B receives `relay.deliver` containing the original envelope. It then sends `relay.ack` and verifies a B reconnect does not redeliver it.

Use exact typed JSON decoding:

```go
type testFrame struct {
	V int `json:"v"`
	Type string `json:"type"`
	MsgID string `json:"msg_id"`
	Envelope json.RawMessage `json:"envelope"`
}
```

- [ ] **Step 2: Run the offline test and observe missing relay-control behavior**

```bash
make sync-proto
cd relay && go test ./internal/server -run TestWebSocketMailboxOfflineDelivery -count=1
```

Expected: failure because the existing server validates `relay.put` as a legacy packet and does not persist it.

- [ ] **Step 3: Add typed frame structs and strict parsing**

In `relay_frame.go`, define separate structs rather than one field-heavy catch-all:

```go
type RelayHeader struct { V int `json:"v"`; Type string `json:"type"` }
type RelayHello struct { V int `json:"v"`; Type string `json:"type"`; Protocols []int `json:"protocols"`; AppVersion string `json:"app_version"` }
type RelayPut struct { V int `json:"v"`; Type string `json:"type"`; Envelope json.RawMessage `json:"envelope"` }
type RelayAck struct { V int `json:"v"`; Type string `json:"type"`; MsgID string `json:"msg_id"`; EnvelopeSHA256 string `json:"envelope_sha256"` }
```

`parseRelayFrame` first unmarshals only `RelayHeader`, validates the full raw frame against `relay-control.schema.json`, then unmarshals the specific struct. Unknown types return a typed protocol error.

- [ ] **Step 4: Inject `MailboxStore` and add v2 connection state**

Extend `Server`:

```go
type Server struct {
	// existing fields
	mailbox *store.MailboxStore
}
```

`NewWithStore` uses `store.DefaultMailboxLimits()`. Add `NewWithDependencies` for tests that need a short retention or small quota; do not read environment variables from tests.

Each WebSocket connection starts in `protocolUnknown`. The first v2 frame must be `relay.hello`. A raw v1 packet selects legacy mode. Once selected, a raw legacy connection cannot send v2 control frames. A v2 control connection may wrap a v1 encrypted envelope in `relay.put` only while the negotiated pair floor is 1; this is the explicit new-sender-to-old-peer compatibility path.

- [ ] **Step 5: Implement put-before-accept and mailbox drain**

For `relay.put`:

1. validate the outer encrypted envelope;
2. parse only outer routing fields;
3. require outer origin to equal the authenticated JWT subject;
4. resolve the paired peer;
5. calculate SHA-256 over the exact envelope bytes;
6. call `MailboxStore.Put`;
7. send `relay.accepted` to the sender only after `Put` commits;
8. best-effort enqueue `relay.deliver` to a connected peer, leaving Bolt as the source of truth.

If the wrapped envelope is v1 and the peer is legacy, do not persist it. Forward the raw envelope only when the peer has a live legacy registration. After successful hub enqueue, return `relay.legacy_forwarded`; when the peer is offline or its writer queue is full, return `relay.rejected` with `peer_legacy`. This outcome is explicitly online-only and is never named accepted or delivered.

On v2 hello, send capabilities, sender statuses, then mailbox deliveries in acceptance order with a batch size of 64. Live delivery uses the same serialized `relay.deliver` frame. A full in-memory writer queue affects latency only; it never deletes Bolt state.

- [ ] **Step 6: Implement acknowledgement and explicit rejections**

`relay.ack` calls `MailboxStore.Ack(authenticatedDevice, msgID, digest, now)`. Map errors to bounded codes:

```text
mailbox_full
id_conflict
digest_mismatch
not_recipient
peer_legacy
invalid_frame
```

Do not expose raw Bolt, schema, JWT, or filesystem error strings to clients.

- [ ] **Step 7: Add and run failure-path tests**

Add tests for:

```go
func TestWebSocketAcceptsOnlyAfterDurablePut(t *testing.T)
func TestWebSocketRedeliversAfterDisconnectBeforeAck(t *testing.T)
func TestWebSocketAckDigestMismatchKeepsMessage(t *testing.T)
func TestWebSocketMailboxFullReturnsRejectedAndKeepsFirst(t *testing.T)
func TestWebSocketRejectsOriginDifferentFromJWTSubject(t *testing.T)
func TestWebSocketRejectsMixedLegacyAndV2Frames(t *testing.T)
```

Run:

```bash
cd relay && go test ./internal/server -run 'TestWebSocket(Accepts|Redelivers|Ack|Mailbox|Rejects)' -race -count=1
```

Expected: all focused tests pass.

- [ ] **Step 8: Run the complete relay suite and commit**

```bash
make relay-test
git add relay/internal/server
git commit -m "feat(relay): deliver v2 envelopes through durable mailboxes"
```

---

### Task 5: Negotiate Capabilities and Prevent Downgrade

**Files:**

- Modify: `relay/internal/store/pair_store.go`
- Modify: `relay/internal/store/pair_store_test.go`
- Modify: `relay/internal/server/ws.go`
- Modify: `relay/internal/server/ws_mailbox_test.go`

**Interfaces:**

- Produces: `PairStore.UpdateCapabilities(deviceID string, protocols []int, appVersion string) error`
- Produces: `PairStore.CapabilitiesFor(deviceID string) (self DeviceCapabilities, peer DeviceCapabilities, floor int, err error)`
- Produces: protocol floor 2 once both paired devices support v2

- [ ] **Step 1: Write failing capability-floor tests**

```go
func TestPairProtocolFloorAdvancesOnlyWhenBothSupportV2(t *testing.T) {
	ps := newTestPairStore(t)
	confirmTestPair(t, ps, "a", "b")
	if err := ps.UpdateCapabilities("a", []int{2, 1}, "0.8.0"); err != nil { t.Fatal(err) }
	_, _, floor, err := ps.CapabilitiesFor("a")
	if err != nil { t.Fatal(err) }
	if floor != 1 { t.Fatalf("floor after A only = %d", floor) }
	if err := ps.UpdateCapabilities("b", []int{2, 1}, "0.8.0"); err != nil { t.Fatal(err) }
	_, _, floor, err = ps.CapabilitiesFor("a")
	if err != nil { t.Fatal(err) }
	if floor != 2 { t.Fatalf("floor after both = %d", floor) }
}
```

- [ ] **Step 2: Run and observe the missing capability store**

```bash
cd relay && go test ./internal/store -run ProtocolFloor -count=1
```

Expected: compile failure for missing methods.

- [ ] **Step 3: Persist capabilities and monotonic floor atomically**

Add:

```go
type DeviceCapabilities struct {
	Protocols []int `json:"protocols"`
	AppVersion string `json:"app_version"`
	UpdatedAt int64 `json:"updated_at"`
}
```

Store capabilities by device and the negotiated floor by pair ID. Floor may move from 1 to 2 but never back automatically. Updating a device outside a confirmed pair returns `ErrNotFound`.

- [ ] **Step 4: Enforce floor and report capabilities in WebSocket tests**

Add tests proving:

- a new client sees peer v1 and `floor:1` before peer hello;
- a v1 envelope wrapped by a new sender returns `relay.legacy_forwarded` only when the legacy peer is live and returns `peer_legacy` when it is offline;
- a v2 envelope returns `peer_legacy` until both advertise v2;
- after both advertise v2, v2 put succeeds;
- a raw v1 packet is rejected after floor 2;
- reconnect preserves floor 2.

- [ ] **Step 5: Run store/server race suites and commit**

```bash
cd relay && go test ./internal/store ./internal/server -race -count=1
git add relay/internal/store/pair_store.go relay/internal/store/pair_store_test.go relay/internal/server/ws.go relay/internal/server/ws_mailbox_test.go
git commit -m "feat(relay): negotiate reliable protocol v2"
```

---

### Task 6: Make Pair Notification Resumable and Idempotent

**Files:**

- Modify: `relay/internal/server/pair_notify.go`
- Modify: `relay/internal/server/pair_handshake.go`
- Modify: `relay/internal/server/pair_notify_test.go`
- Modify: `relay/internal/store/pair_store.go`
- Modify: `relay/internal/store/pair_store_test.go`

**Interfaces:**

- Produces: `PairStore.PendingState(pairToken string) (PendingPairState, error)`
- Preserves: existing `/pair/notify`, `/pair/hello`, `/pair/send_sig`, and `/pair/complete` endpoints
- Changes: late subscribers immediately receive persisted current state

- [ ] **Step 1: Add the real Device-A-confirms-before-B-subscribes test**

The test sequence must be:

1. pair init;
2. Device B hello;
3. Device A send signature while no notify WebSocket exists;
4. Device B opens `/pair/notify`;
5. first relevant frame is the persisted `pair.sig`;
6. Device B completes pairing;
7. Device A reconnects and observes committed completion.

Name it `TestPairNotifyLateSubscriberReceivesPersistedSignatureAndCompletion`.

- [ ] **Step 2: Run and observe timeout on the current in-memory-only path**

```bash
cd relay && go test ./internal/server -run TestPairNotifyLateSubscriber -count=1
```

Expected: timeout waiting for the lost signature.

- [ ] **Step 3: Define persisted pairing transitions**

Add an enum-like state derived from persisted fields:

```go
type PendingPairState string
const (
	PairWaitingForPeer PendingPairState = "waiting_for_peer"
	PairWaitingForSignature PendingPairState = "waiting_for_signature"
	PairReadyToComplete PendingPairState = "ready_to_complete"
	PairCommitted PendingPairState = "committed"
)
```

Retain completed pair status for the original pair token until its five-minute expiry so either device can recover a lost HTTP/WebSocket response. Repeated hello, signature, and complete operations with identical cryptographic values return the prior success. Conflicting values return HTTP 409.

- [ ] **Step 4: Replay state on subscription before live frames**

After validating the pair token, `/pair/notify` reads persisted state and writes the applicable `peer.hello`, `pair.sig`, and `pair.complete` frames in transition order. It then subscribes to live changes and rereads once after subscription to close the read/subscribe race. Clients deduplicate identical transition frames by type and token.

- [ ] **Step 5: Run pairing tests repeatedly and commit**

```bash
cd relay && go test ./internal/server ./internal/store -run Pair -race -count=20
git add relay/internal/server/pair_notify.go relay/internal/server/pair_handshake.go relay/internal/server/pair_notify_test.go relay/internal/store/pair_store.go relay/internal/store/pair_store_test.go
git commit -m "fix(relay): make pairing notifications resumable"
```

---

### Task 7: Add Authenticated Revocation and Atomic Rebinding Protection

**Files:**

- Create: `relay/internal/server/revoke.go`
- Create: `relay/internal/server/revoke_test.go`
- Modify: `relay/internal/server/server.go`
- Modify: `relay/internal/store/pair_store.go`
- Modify: `relay/internal/store/pair_store_test.go`
- Modify: `relay/internal/server/client_hub.go`

**Interfaces:**

- Produces: authenticated `POST /pair/revoke`
- Produces: `PairStore.RevokeByDevice(deviceID string) (*ConfirmedPair, error)` with pair and mailbox deletion in one Bolt transaction
- Produces: `ClientHub.Disconnect(deviceID string)`
- Consumes: store-internal `purgePairTx`

- [ ] **Step 1: Write failing revocation tests**

Tests must prove that either paired device can revoke, an unpaired or wrong JWT cannot, both device indexes disappear, old JWT authentication fails, both active sockets close, both mailbox directions are purged, and a subsequent pair may bind the devices with new keys.

Handler test core:

```go
req := httptest.NewRequest(http.MethodPost, "/pair/revoke", strings.NewReader(`{"reason":"user_unpair"}`))
req.Header.Set("Authorization", "Bearer "+mintJWT(t, "dev-a", aPriv, ""))
rec := httptest.NewRecorder()
srv.Handler().ServeHTTP(rec, req)
if rec.Code != http.StatusNoContent { t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String()) }
if _, err := srv.pairStore.PeerFor("dev-a"); !errors.Is(err, store.ErrNotFound) { t.Fatalf("peer still bound: %v", err) }
```

- [ ] **Step 2: Run and observe route/method failures**

```bash
cd relay && go test ./internal/server ./internal/store -run Revoke -count=1
```

Expected: 404 or compile failure before implementation.

- [ ] **Step 3: Make confirmation and revocation atomic**

Move `PairStore.Confirm` into one Bolt update transaction. Before binding, reject any device already indexed to a different confirmed pair unless an authenticated revocation removed it. `RevokeByDevice` resolves the pair and removes the confirmed record, both indexes, capabilities, floor, retained pairing-token status, both mailbox directions, mailbox counters, and delivery-status tombstones in one Bolt update transaction by calling `purgePairTx`.

- [ ] **Step 4: Implement handler-side purge and disconnect**

The authenticated handler resolves and atomically revokes the pair plus mailboxes, disconnects both hub registrations, and returns 204. A retry after state removal fails authentication because the signing key is no longer indexed; the Android client treats that specific post-revocation 401 as terminal local success after it has already wiped its own keys.

- [ ] **Step 5: Run auth, pair, mailbox, and race suites; commit**

```bash
cd relay && go test ./internal/store ./internal/server -race -count=1
git add relay/internal/store relay/internal/server
git commit -m "feat(relay): revoke pair authorization and mailboxes"
```

---

### Task 8: Bound Public Inputs and Persist Production Data

**Files:**

- Create: `relay/internal/server/http_limits.go`
- Create: `relay/internal/server/http_limits_test.go`
- Modify: `relay/internal/server/pair.go`
- Modify: `relay/internal/server/pair_handshake.go`
- Modify: `relay/cmd/relay/main.go`
- Modify: `deploy/docker-compose.yml`
- Create: `deploy/docker-compose.prod.yml`
- Modify: `deploy/caddy/Caddyfile`

**Interfaces:**

- Produces: 64 KiB pairing-body limit, exact key-length checks, pending-pair caps, per-IP/token rate limits, and HTTP timeouts
- Produces: persistent `BOLT_PATH=/data/twinotify-relay.db`
- Produces: TLS-only production network topology

- [ ] **Step 1: Add failing body-limit, rate-limit, timeout-configuration, and persistence tests**

Use `httptest` to send a 65 KiB pairing body and expect 413. Send more than the configured burst from one test IP and expect 429 with `Retry-After`. Add a store test that creates pending pairs up to the global cap and rejects the next insert without growing Bolt.

Add a Compose assertion script target that parses resolved Compose config and verifies `BOLT_PATH=/data/twinotify-relay.db` plus no direct relay port in the production profile.

- [ ] **Step 2: Run tests and observe current unbounded behavior**

```bash
cd relay && go test ./internal/server ./internal/store -run 'Limit|Rate|PendingCap' -count=1
docker compose -f deploy/docker-compose.prod.yml config
```

Expected: test failures and missing production Compose file.

- [ ] **Step 3: Implement bounded decoding and key validation**

Wrap pairing bodies with `http.MaxBytesReader(w, r.Body, 64<<10)`. Use a decoder with `DisallowUnknownFields`, reject trailing JSON, enforce 32-byte decoded X25519 keys and 32-byte Ed25519 public keys, and cap display names at 64 UTF-8 bytes.

Implement a mutex-protected token bucket keyed by normalized remote IP for unauthenticated pairing routes. Configuration is injected into `Server` for deterministic tests. Evict idle limiter entries after ten minutes.

- [ ] **Step 4: Configure the HTTP server and maintenance lifecycle**

Construct:

```go
srv := &http.Server{
	Addr: addr,
	Handler: app.Handler(),
	ReadHeaderTimeout: 5 * time.Second,
	ReadTimeout: 15 * time.Second,
	WriteTimeout: 15 * time.Second,
	IdleTimeout: 75 * time.Second,
	MaxHeaderBytes: 16 << 10,
}
```

Start one cancellable maintenance loop for mailbox expiry, delivery-status expiry, pairing TTL sweep, JTI cleanup, and limiter cleanup. Stop it during graceful shutdown before closing Bolt.

- [ ] **Step 5: Separate development and production deployment**

Development Compose may publish port 8080 but must set `BOLT_PATH=/data/twinotify-relay.db`. Production Compose exposes only Caddy ports 80/443, keeps relay on an internal network, mounts `/data`, and requires `TWINOTIFY_DOMAIN`. Caddy proxies `/health`, `/pair/*`, and `/ws` to `relay:8080` using a trusted public certificate for the configured domain; it must not use `tls internal` in production.

- [ ] **Step 6: Run security and deployment tests; commit**

```bash
make relay-test
docker compose -f deploy/docker-compose.yml config
docker compose -f deploy/docker-compose.prod.yml config
git add relay deploy
git commit -m "fix(relay): bound inputs and persist production state"
```

---

### Task 9: Add One-Command Protocol and Relay Verification

**Files:**

- Modify: `Makefile`
- Modify: `.github/workflows/relay.yml`
- Modify: `relay/README.md`
- Create: `relay/internal/server/fixture_test.go`

**Interfaces:**

- Produces: `make proto-test`
- Produces: `make relay-verify`
- Preserves: `make relay-test` as a narrower developer command

- [ ] **Step 1: Add a failing fixture test that walks committed fixtures**

`fixture_test.go` embeds the generated fixture copy with `//go:embed fixtures/*/*.json fixtures/manifest.json`. `proto/fixtures/manifest.json` maps each filename to `legacy_packet`, `encrypted_envelope`, or `relay_control`, plus `valid: true` or a stable expected error code. The test does not snapshot library-specific error prose.

- [ ] **Step 2: Run before adding fixture sync and observe missing fixtures in the relay test environment**

```bash
cd relay && go test ./internal/server -run TestProtocolFixtures -count=1
```

Expected: failure locating the generated fixture directory.

- [ ] **Step 3: Add deterministic protocol sync and verification targets**

Extend `Makefile`:

```make
.PHONY: sync-proto proto-test relay-test relay-verify relay-build clean

sync-proto:
	mkdir -p relay/internal/server/schemas relay/internal/server/fixtures
	rm -f relay/internal/server/schemas/*.schema.json
	rm -rf relay/internal/server/fixtures/*
	cp proto/*.schema.json relay/internal/server/schemas/
	cp -R proto/fixtures/. relay/internal/server/fixtures/

proto-test: sync-proto
	cd relay && go test ./internal/server -run 'TestValidator|TestProtocolFixtures' -count=1

relay-verify: sync-proto
	@test -z "$$(cd relay && gofmt -l .)"
	cd relay && go vet ./...
	cd relay && go test ./... -race -count=1
	docker build -t twinotify-relay:verify relay
```

- [ ] **Step 4: Update CI and operator documentation**

CI runs `make relay-verify`, resolved development and production Compose configuration checks, and uploads the race-test log on failure. README documents:

- acceptance versus peer receipt;
- 24-hour retention and quotas;
- metadata visible to relay;
- v1 legacy behavior;
- `/data` backup and restore;
- revocation;
- TLS requirements;
- health and failure codes.

- [ ] **Step 5: Run the full gate**

```bash
make proto-test
make relay-verify
git diff --check
```

Expected: all commands exit 0; the race detector is silent; Docker image builds.

- [ ] **Step 6: Commit verification and documentation**

```bash
git add Makefile .github/workflows/relay.yml relay/README.md relay/internal/server/fixture_test.go
git commit -m "test(relay): gate reliable delivery protocol"
```

---

## Plan Completion Audit

Before handing the protocol/relay lane to Android implementation, verify every item below with current artifacts:

- [ ] `make proto-test` passes against committed positive and negative fixtures.
- [ ] `make relay-verify` passes with no race output.
- [ ] Offline put, relay restart, recipient reconnect, redelivery, digest ACK, and expiry are covered by WebSocket integration tests.
- [ ] The old send-versus-close stress test passes at least 20 repeated race-enabled runs.
- [ ] Pairing succeeds when Device A signs before Device B subscribes.
- [ ] Revocation invalidates old JWT keys, closes sockets, and purges ciphertext.
- [ ] Duplicate message IDs cannot replace stored ciphertext.
- [ ] Mailbox quota returns backpressure without eviction.
- [ ] Bolt survives server reopen and production Compose points at `/data`.
- [ ] Production Compose exposes no plaintext relay port.
- [ ] Protocol floor advances only when both paired devices advertise v2.
- [ ] Raw v1 remains online-only before floor 2 and is rejected after floor 2.
- [ ] No notification plaintext field is persisted or logged by the relay.

The next plan consumes the exact v2 schemas, relay control frames, failure codes, and golden fixtures defined here to implement Android outbox, receipts, ordering, materialization, lifecycle recovery, and snapshots.
