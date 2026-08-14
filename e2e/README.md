# Two-device E2E harness

The `call-state` scenario drives the authenticated debug-only synthetic call
state command (`ringing -> active -> idle`) and verifies content-free state,
sequence, queue, and mirror convergence on the paired device. It rejects
phone/contact/audio fields and reports `ErrUnsupportedEnvironment` when the
debug control surface or a connected pair is unavailable.

This is synthetic evidence only. It does not claim that a physical cellular
call was observed. Physical-call evidence remains pending until an explicitly
selected device run is captured and audited.

```sh
GOCACHE=/tmp/phone-sync-call-e2e-cache go run ./cmd/twinotify-e2e -scenario call-state
```
