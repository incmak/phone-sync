# Direct Bluetooth route evidence

Physical Bluetooth evidence is private release material. Keep the evidence
directory outside the repository, or in an ignored location, and never paste a
Bluetooth address, device name, association identifier, service UUID, SSID,
BSSID, raw build fingerprint, device identifier, key, envelope, notification
text, caller data, token, or raw logcat into a report or issue. A Bluetooth
address is a durable hardware identifier; it is the single most damaging value
that could end up in a shared artifact.

## What this proves

`PHY-BLUETOOTH-01` in [`docs/test-scenarios.md`](../../test-scenarios.md)
records whether a project-owned target build carries Twinotify's existing
end-to-end-encrypted v2 envelopes over a user-associated LE L2CAP link
when Wi-Fi is unavailable, and hands the outbox back to LAN cleanly when Wi-Fi
returns. A record is valid only when both phones were associated through the
system Companion Device Manager picker, the link authenticated against the
stored Twinotify peer, and custody was observed on the `bluetooth` route with
an authenticated peer receipt.

This route transports Twinotify data only. It routes no call audio, uses no HFP
or LE Audio profile, and captures no microphone or speaker stream. A record
that implies otherwise is invalid.

The host scenario (`make e2e-bluetooth-route`) exercises the same protocol
against emulators or a deterministic fake bridge. Emulator instances have no
usable Bluetooth between them, so its artifacts live in a separate directory
and must not be copied into, or counted toward, this evidence. The absence of a
physical evidence directory is a pending gate, not a pass.

## Directory layout

Create one directory per phone pair, named from the sanitized `Build.MODEL` of
the phone under test, its SDK level, and the UTC date, for example
`pixel-8-sdk34-2026-09-04/`. Sanitize the model by lowercasing and replacing
anything outside `a-z0-9` with `-`. Inside it, write one JSON file per required
matrix case, named `<case>.json`, plus `operator-notes.md` with the operator,
the Bluetooth stack version as reported by the platform, and any anomaly. Do
not include screenshots that show a device name or a system Bluetooth picker.

## Record contract

Every case file has exactly this shape:

```json
{
  "model": "sanitized Build.MODEL",
  "build_fingerprint_sha256": "64 lowercase hex characters",
  "sdk": 34,
  "bluetooth_stack_version": "unknown",
  "app_version": "numeric versionCode",
  "peer_model": "sanitized Build.MODEL",
  "case": "maximum_envelope",
  "route": "bluetooth",
  "phase": "authenticated",
  "route_generation": 4,
  "envelope_bytes": 1048576,
  "delivered_count": 1,
  "peer_receipt_count": 1,
  "reconnect_count": 0,
  "p95_latency_ms": 0,
  "battery_delta_percent": 0,
  "error_code": "none"
}
```

Field rules:

- `model` and `peer_model`: sanitized `Build.MODEL` as described above.
- `build_fingerprint_sha256`: SHA-256 of the exact `Build.FINGERPRINT` string,
  never the raw fingerprint.
- `sdk`: integer `Build.VERSION.SDK_INT`, at least 34.
- `bluetooth_stack_version`: the platform-reported stack or chipset firmware
  version as a short string, or `unknown` when the platform does not expose
  one. Never a device name and never an adapter address.
- `app_version`: the app's numeric `versionCode` as a string.
- `case`: one of `envelope_coverage`, `screens_off`, `backgrounded`,
  `force_stop_restart`, `reboot`, `bluetooth_toggle`, `out_of_range`,
  `permission_revoked`, `association_removed`, `competing_device`,
  `maximum_envelope`, `control_round_trips`, `idle_hold_8h`, `lan_promotion`.
- `route`: `bluetooth` for every case except `lan_promotion`, which records
  `lan` as the route that took the lease.
- `phase`: `authenticated` for a granted route, `idle` for a case that proves
  a clean stop such as `permission_revoked` or `association_removed`.
- `route_generation`: the integer generation observed for that route. For
  `lan_promotion` it must be exactly one greater than the Bluetooth generation
  the same run recorded.
- `envelope_bytes`: the envelope size the device actually persisted, from the
  `ENQUEUE_FIXTURE` response. Required for `maximum_envelope`, `0` elsewhere.
- `delivered_count` and `peer_receipt_count`: exact counts for the case. They
  must be equal for every delivery case; a difference is a failure, not a
  rounding note.
- `reconnect_count`: reconnects observed during the case. Required for
  `idle_hold_8h`, `out_of_range`, `bluetooth_toggle`, and `reboot`.
- `p95_latency_ms`: integer milliseconds, required for `control_round_trips`
  over all 100 samples, `0` elsewhere.
- `battery_delta_percent`: integer percentage points consumed, required for
  `idle_hold_8h`, `0` elsewhere.
- `error_code`: a stable lowercase snake-case code, or `none`. Never a message,
  a stack trace, or anything derived from an address or a peer identity.

## Support decision

A build is supported only when every required cell of the `PHY-BLUETOOTH-01`
matrix has a record, `control_round_trips` shows p95 under 750 ms across all
100 samples, `maximum_envelope` shows a 1,048,576 byte envelope delivered whole
with bounded memory and no silent drop, `idle_hold_8h` shows the foreground
service alive with bounded logs, and `lan_promotion` shows the Bluetooth
session closed before LAN owned the outbox with the route generation advancing
exactly once and no message sent twice. Every delivery case must record equal
`delivered_count` and `peer_receipt_count`. Any missing, failed, or skipped
cell keeps the build unsupported; never manufacture a record without the
corresponding device observation.
