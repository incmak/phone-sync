# Stock dialer call-control evidence

Physical call-control evidence is private release material. Keep the evidence
directory outside the repository, or in an ignored location, and do not paste
phone numbers, contact names, SIM identity, notification text, raw build
fingerprints, device identifiers, control or invocation UUIDs, keys, tokens, or
raw logcat into a report or issue.

## What this proves

`PHY-CALL-CONTROL-01` in [`docs/test-scenarios.md`](../../test-scenarios.md)
records whether a project-owned target build can answer, decline, or end an
incoming cellular call from the paired phone by invoking the source dialer's
own `Notification.CallStyle` capabilities. A record is only valid when a real
incoming cellular call reached the source, the peer tapped one native control,
and the organic source `call.state` transition was observed. `dispatched` is
the `PendingIntent.send()` outcome and never a pass on its own.

The synthetic host gate (`make e2e-call-control`) exercises the same protocol
against a debug fixture on emulators or debug builds. Its artifacts live in a
separate directory and must not be copied into, or counted toward, this
evidence. The absence of a physical evidence directory is a pending gate, not
a pass.

## Directory layout

Create one directory per source build, named from the sanitized `Build.MODEL`,
SDK level, and UTC date, for example `pixel-8-sdk34-2026-09-03/`. Sanitize the
model by lowercasing and replacing anything outside `a-z0-9` with `-`. Inside
it, write one JSON file per required matrix case, named
`<control>-<route>-<source_lock_state>-<peer_lock_state>.json`, plus
`operator-notes.md` with the operator, the dialer product name as shown in
Settings, and any anomaly. Do not include screenshots that show a caller name
or number.

## Record contract

Every case file has exactly this shape:

```json
{
  "source_model": "sanitized Build.MODEL",
  "source_build_fingerprint_sha256": "64 lowercase hex characters",
  "source_sdk": 34,
  "dialer_package_sha256": "64 lowercase hex characters",
  "dialer_version": "numeric versionCode",
  "peer_model": "sanitized Build.MODEL",
  "source_lock_state": "locked",
  "peer_lock_state": "locked",
  "route": "LAN",
  "control": "answer",
  "dispatch_status": "dispatched",
  "state_transition": "active",
  "latency_ms": 420
}
```

Field rules:

- `source_model` and `peer_model`: sanitized `Build.MODEL` as described above.
- `source_build_fingerprint_sha256`: SHA-256 of the exact `Build.FINGERPRINT`
  string, never the raw fingerprint.
- `source_sdk`: integer `Build.VERSION.SDK_INT`, at least 34.
- `dialer_package_sha256`: SHA-256 of the default dialer package name, never
  the package name itself.
- `dialer_version`: the dialer's numeric `versionCode` as a string.
- `source_lock_state` and `peer_lock_state`: one of `on`, `off`, `locked`.
- `route`: `LAN` for an authenticated direct route or `relay` with the direct
  route disabled through the app-internal control.
- `control`: `answer`, `decline`, `hang_up`, `outgoing`, `concurrent`, or
  `duplicate`. The last three are negative cases.
- `dispatch_status`: `dispatched` for a control the source dispatched once,
  `none` when no control was advertised or sent, `terminal` for the second tap
  of a duplicate case.
- `state_transition`: the organic source state observed after the tap:
  `active`, `idle`, or `none` for negative cases.
- `latency_ms`: integer milliseconds from the peer tap to the organic
  transition; `0` for negative cases.

## Support decision

A build is supported only when every required cell of the
`PHY-CALL-CONTROL-01` matrix has a record with the expected transition, the
outgoing and concurrent cases record no advertised control and no sent invoke,
the duplicate case records one dispatch, direct p95 latency is under 750 ms,
and relay p95 latency is under 2000 ms. Compute p95 per control and route over
all screen states. Any missing, failed, or skipped cell keeps the build
unsupported; never manufacture a record without the corresponding device
observation.
