# Mirrored Notification Actions: Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task in the primary checkout.

**Goal:** Make the notification payload schema match the Android emitter and add strict, cross-layer contracts for action descriptors, action invocation, and action results.

**Architecture:** The relay remains payload-blind at runtime. `proto/` stays the source of truth, Go validates fixtures against compiled schemas, and Kotlin independently validates the same fixtures and runtime JSON. New action events use the reliable-delivery control lane and never enter canonical notification sequencing.

**Tech Stack:** JSON Schema 2020-12, Go 1.23 with `jsonschema/v5`, Kotlin/JVM, `org.json`, JUnit 4, Gradle.

## Global Constraints

- Work in the primary checkout. Do not create a worktree.
- Follow TDD for every behavior: add or change a test, observe the intended failure, then implement the smallest production change.
- Edit only `proto/`; never edit generated `relay/internal/server/schemas/` directly.
- Preserve v1 and v2 compatibility. Missing `actions` means an empty list and missing `is_auto_cancel` means `true`.
- Never put `RemoteInput` keys, package components, intent extras, or exception text on the wire.
- Run Go tests with `-race` before each commit.
- Keep commits small and conventional.

---

## Task 1: Turn the current notification payload into an enforced contract

**Files:**

- Modify: `proto/notif-post.schema.json`
- Modify: `proto/fixtures/manifest.json`
- Create: `proto/fixtures/v2-valid/notif-post-actions-valid.json`
- Create: `proto/fixtures/v2-valid/notif-post-legacy-valid.json`
- Create: `proto/fixtures/v2-invalid/notif-post-four-actions.json`
- Create: `proto/fixtures/v2-invalid/notif-post-long-action-title.json`
- Create: `proto/fixtures/v2-invalid/notif-post-remote-input-key.json`
- Modify: `relay/internal/server/validator.go`
- Modify: `relay/internal/server/fixture_test.go`

### Step 1: Add failing fixture declarations

Add manifest rows with fixture type `notif_post_payload`. The valid action fixture must use the exact Android names `package_name`, `small_icon_png_b64`, `large_icon_png_b64`, `is_auto_cancel`, and:

```json
"actions": [{
  "action_id": "b6d3142a-e936-4d7d-b15a-bdf318bb0539",
  "title": "Reply",
  "semantic": 1,
  "reply": true,
  "reply_label": "Message"
}]
```

The legacy fixture must omit both additive fields. Invalid fixtures must cover four actions, a 65-character title, and the forbidden `remote_input_key` property.

Extend `validateFixtureDeclaration` to route `notif_post_payload` through a compiled `notif-post.schema.json` validator. Run:

```bash
make sync-proto
cd relay && go test ./internal/server -run TestProtocolFixtures -race -count=1
```

Expected: FAIL because `Validator` does not yet compile or expose the notification payload schema and the stale schema rejects the real field names.

### Step 2: Rewrite the schema and compile it in Go

Replace the stale aspirational schema with the emitted `NotifPostJson` contract. Keep `$id` under `https://twinotify.app/schemas/`. Use `additionalProperties: false`. Permit `type` values `notif.post` and `notif.update`. Add:

```json
"is_auto_cancel": { "type": "boolean", "default": true },
"actions": {
  "type": "array",
  "maxItems": 3,
  "default": [],
  "items": {
    "type": "object",
    "additionalProperties": false,
    "required": ["action_id", "title", "semantic", "reply"],
    "properties": {
      "action_id": { "type": "string", "format": "uuid" },
      "title": { "type": "string", "minLength": 1, "maxLength": 64 },
      "semantic": { "type": "integer", "minimum": 0, "maximum": 12 },
      "reply": { "type": "boolean" },
      "reply_label": { "type": ["string", "null"], "maxLength": 64 }
    }
  }
}
```

Compile the schema in `NewValidator` and add a `ValidateNotifPostPayload` method used only by fixtures. Keep relay frame handling unchanged.

### Step 3: Prove the contract

Run the focused test again. Expected: PASS, including rejection of the three invalid payload fixtures.

Run:

```bash
git diff --check
git add proto/notif-post.schema.json proto/fixtures relay/internal/server/validator.go relay/internal/server/fixture_test.go
git commit -m "test(proto): enforce notification action payload"
```

---

## Task 2: Define strict action invoke and result events

**Files:**

- Modify: `proto/inner-event-v2.schema.json`
- Modify: `proto/fixtures/manifest.json`
- Create: `proto/fixtures/v2-valid/notif-action-invoke-valid.json`
- Create: `proto/fixtures/v2-valid/notif-action-result-valid.json`
- Create: `proto/fixtures/v2-invalid/notif-action-invoke-long-reply.json`
- Create: `proto/fixtures/v2-invalid/notif-action-invoke-missing-id.json`
- Create: `proto/fixtures/v2-invalid/notif-action-invoke-extra-field.json`
- Create: `proto/fixtures/v2-invalid/notif-action-invoke-bad-action-id.json`
- Create: `proto/fixtures/v2-invalid/notif-action-result-bad-status.json`
- Create: `proto/fixtures/v2-invalid/notif-action-result-extra-field.json`
- Modify: `relay/internal/server/fixture_test.go`

### Step 1: Add fixtures before schema support

Register fixture types `notif_action_invoke` and `notif_action_result`. Each file is a complete inner v2 event. Keep top-level `canon_id` and `sequence` absent because these are control-lane events.

Run the focused Go fixture test. Expected: FAIL because both types are outside the schema enum.

### Step 2: Extend `inner-event-v2.schema.json`

Add both event names to the type allowlist and add `if`/`then` payload branches patterned after `call.state`:

```text
notif.action.invoke payload:
  invocation_id UUID, canon_id bounded string, action_id UUID,
  notification_sequence integer >= 1, invoked_at integer >= 0,
  optional reply_text string with maxLength 4096

notif.action.result payload:
  invocation_id UUID, canon_id bounded string,
  status enum dispatched|outcome_unknown|action_gone|
              notification_gone|expired|failed
```

Both payload objects use `additionalProperties: false`. Set invoke `expires_at` exactly 120,000 ms after `created_at` in the valid fixture and result expiry 600,000 ms after creation. Length-in-characters is a schema backstop; Kotlin adds the authoritative 4,096-byte UTF-8 check in Task 3.

### Step 3: Prove Go schema enforcement

Run:

```bash
make sync-proto
cd relay && go test ./internal/server -run TestProtocolFixtures -race -count=1
```

Expected: PASS. Confirm with `git diff -- relay/internal/server` that no runtime frame or mailbox logic changed.

Commit:

```bash
git add proto/inner-event-v2.schema.json proto/fixtures relay/internal/server/fixture_test.go
git commit -m "feat(proto): define notification action controls"
```

---

## Task 3: Enforce the same action contracts in Kotlin

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolFixtureTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolFixtures.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolValidationTest.kt`

### Step 1: Make the shared fixtures fail in Kotlin

Teach `ProtocolFixtureTest` to dispatch the three new manifest types but do not change production validation yet. Add unit assertions that:

- action events validate without top-level canonical fields;
- a 4,096-byte reply is accepted and a 4,097-byte UTF-8 reply is rejected;
- unknown payload keys are rejected;
- invalid UUIDs and result statuses are rejected.

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.ProtocolFixtureTest' --tests 'co.twinotify.core.protocol.ProtocolValidationTest'
```

Expected: FAIL with `Unsupported inner type` or missing action validators.

### Step 2: Add production validation

Extend `ProtocolJson.innerTypes`, not `canonicalTypes`. Add closed `actionResultStatuses` and private validators with these signatures:

```kotlin
private fun validateActionInvokePayload(payload: JSONObject)
private fun validateActionResultPayload(payload: JSONObject)
private fun requireUuid(payload: JSONObject, key: String): String
```

Use exact allowed-key sets. Measure replies with `replyText.toByteArray(Charsets.UTF_8).size <= 4096`. Reject reply text on a malformed type instead of coercing. Do not add event names to any canonical sequencing branch.

### Step 3: Run the Kotlin and Go fixture gates

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.*'
cd ../..
make sync-proto
cd relay && go test ./internal/server -run TestProtocolFixtures -race -count=1
```

Expected: PASS in both implementations.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol
git commit -m "feat(mobile/protocol): validate notification action controls"
```

---

## Task 4: Parse the additive notification fields compatibly

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotifPostBuilder.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/NotifPostJsonTest.kt`

### Step 1: Specify the data model with failing tests

Add tests for this target shape:

```kotlin
data class NotifActionJson(
  val actionId: String,
  val title: String,
  val semantic: Int,
  val reply: Boolean,
  val replyLabel: String?,
)

data class NotifPostJson(
  // existing fields
  val isAutoCancel: Boolean = true,
  val actions: List<NotifActionJson> = emptyList(),
)
```

Verify a legacy payload produces `true` and an empty list. Verify a current payload preserves source order and rejects more than three actions, forbidden action keys, over-long labels/titles, and bad UUIDs.

Run `NotifPostJsonTest`. Expected: FAIL because the fields do not exist.

### Step 2: Implement parsing and serialization

Keep the existing old-peer compatibility at the top level, while validating every recognized action descriptor strictly. Do not accept or emit `remote_input_key`. Add an emitter helper if needed so origin capture can serialize through the same model in the next plan.

### Step 3: Regression gate and commit

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.service.NotifPostJsonTest' --tests 'co.twinotify.core.service.InboundDispatcherControlTest'
```

Expected: PASS, including the legacy payload case.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotifPostBuilder.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/NotifPostJsonTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt
git commit -m "feat(mobile/protocol): parse mirrored action descriptors"
```

---

## Plan Completion Gate

Run all protocol gates from a synced schema tree:

```bash
make sync-proto
cd relay && go test ./internal/server -run TestProtocolFixtures -race -count=1
cd ../../mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.*' --tests 'co.twinotify.core.service.NotifPostJsonTest'
git diff --check
git status --short
```

Expected: all tests pass; the only relay source changes are schema compilation and fixture routing; the working tree is clean after commits. Record exact commands and results in the execution handoff before starting the origin plan.
