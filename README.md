# Twinotify

Twinotify mirrors Android notifications between two paired Android phones. The
relay only receives ciphertext and routing metadata; device keys and plaintext
stay on the phones.

## Repository map

- `relay/`: Go WebSocket and HTTP relay with a durable mailbox.
- `mobile/`: Expo app. Its `twinotify-core` custom Kotlin native module owns
  notification capture, cryptography, transport, and mirror materialization.
- `proto/`: JSON Schema packet contracts shared by the relay and Android client.

## Start here

Use this primary checkout directly. Do not create or switch to a Git worktree
unless a task explicitly asks for one. Install Go 1.25.13, Node 20 with npm, the
Android SDK for Android 14 / API 34, and Docker for relay or deployment work.

Run the host-only suite first:

```bash
make host-verify
```

The complete native suite needs dependency installation, Expo prebuild output,
and Android tooling:

```bash
make verify
```

The mobile app cannot run in Expo Go because it loads the custom Kotlin native module.
Use a development build on an Android 14+ emulator or device after prebuilding:

```bash
cd mobile
npm ci
npx expo prebuild --platform android --clean --no-install
npm run android
```

`make e2e-emulator` requires two compatible Android emulators and performs the
native prerequisites. Real two-phone release scenarios remain pending private
operator evidence. Audit an authorized candidate only with:

```bash
make release-audit RELEASE_EVIDENCE_DIR=/private/path/to/evidence
```

## Local relay

For development, start the Compose relay and check its liveness endpoint:

```bash
cd deploy
docker compose up -d relay
curl -sf http://localhost:8080/health
```

Read the [threat model](docs/superpowers/specs/2026-04-20-phone-sync-design.md),
[two-phone scenarios](docs/test-scenarios.md), and
[private release-evidence contract](docs/release-evidence/README.md) before
changing protocol, delivery, or release work.
