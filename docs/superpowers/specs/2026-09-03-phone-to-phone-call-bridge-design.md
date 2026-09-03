# Phone-to-phone call bridge research and design

**Date:** 2026-09-03

**Status:** Proposed, with two implementation tracks and one explicit product decision

**Scope:** Android-to-Android cellular/IMS call state, remote call controls, Bluetooth and Wi-Fi delivery, and the platform requirements for carrying live call audio on the second phone

**Depends on:** v2 reliable delivery, mirrored notification actions, direct LAN delivery, and the existing privacy-bounded `call.state` pipeline

## 1. Decision in one page

The smartwatch experience is made from two technically separate planes:

1. The **control plane** carries ringing state and commands such as answer,
   decline, and hang up.
2. The **media plane** carries the live microphone and earpiece audio.

Twinotify can add a useful stock-Android control plane without becoming the
default dialer. On a compatible source dialer, its notification listener can
hold the source `Notification.CallStyle` answer, decline, and hang-up
`PendingIntent` tokens in memory, advertise only opaque capability IDs to the
peer, and execute the original token after an authenticated peer tap. Android
documents those call intents as notification extras and describes a
`PendingIntent` as the capability granted to perform that operation
([`Notification` extras](https://developer.android.com/reference/android/app/Notification),
[`CallStyle` guide](https://developer.android.com/develop/ui/compose/notifications/call-style)).

That route is **not universally compatible**. It depends on the active OEM
dialer publishing a standards-compliant `CallStyle` notification with usable
tokens. It must therefore begin with a physical-device capability probe and
fail closed when the tokens are missing or ambiguous.

A normal Play-distributed app cannot carry the cellular call audio to the
second phone. `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK` capture require
`CAPTURE_AUDIO_OUTPUT`, which is reserved for system components
([Android audio-source reference](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)).
Playback capture accepts media, game, or unknown usages, not telephony audio
([Android playback-capture reference](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)).
Even an `InCallService` may select only endpoints supplied by Telecom; it may
not invent a remote endpoint
([`InCallService.requestCallEndpointChange`](https://developer.android.com/reference/android/telecom/InCallService)).

The exact smartwatch-like audio experience is therefore a separate
**system-integration product**:

- For Bluetooth, phone A remains the Hands-Free Profile Audio Gateway and phone
  B must expose the HFP Hands-Free/Client role. HFP explicitly defines the
  cellular phone as the Audio Gateway and the remote device as the audio
  input/output and control unit
  ([Bluetooth HFP 1.10](https://www.bluetooth.com/specifications/specs/hands-free-profile-1-10/)).
- AOSP contains the required HFP Client service and call controls, but its
  service gates important operations with `BLUETOOTH_PRIVILEGED`
  ([AOSP `HeadsetClientService`](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/hfpclient/HeadsetClientService.java)).
  Android Automotive documents HFP Client integration with Telecom and manual
  SCO audio connection
  ([AOSP Automotive Bluetooth](https://source.android.com/docs/automotive/ivi_connectivity)).
- Consequently, exact Bluetooth call audio requires an OEM partnership,
  preinstalled privileged app, or a controlled custom Android build on phone B.
- Wi-Fi has no public equivalent that exposes arbitrary carrier-call PCM. A
  Wi-Fi media bridge also requires an OEM/custom-system audio endpoint. Its
  network media engine can be shared across Wi-Fi LAN and Wi-Fi Aware, but it
  cannot replace the system HFP audio backend.

**Recommendation:** ship and validate the stock call-control bridge first, add
a Bluetooth data route for all existing E2EE events second, and pursue exact
call audio only if Twinotify is allowed to require a controlled system image or
OEM privileges on the receiving phone.

## 2. Goal, scope, non-goals, and acceptance

### Goal

Let a paired Android phone show an incoming cellular call and, where the source
dialer exposes safe capabilities, answer, decline, or end that incoming call.
Use the same authenticated E2EE control messages over LAN, Bluetooth, or relay.
Define an honest system-only path for speaking and listening on the paired
phone.

### In scope

- Ordinary cellular and IMS calls reported by Android telephony.
- Incoming-call state and remote answer/decline.
- Remote hang-up only for a session that Twinotify observed begin as incoming.
- A native Android `CallStyle` mirror on the paired phone.
- E2EE control transport over existing LAN/relay and a new Bluetooth direct
  data route.
- HFP Client as the exact Bluetooth media path on a privileged/system build.
- A separately gated custom-system Wi-Fi media backend.

### Non-goals

- iOS support.
- Capturing or injecting carrier-call PCM in the stock Twinotify APK.
- Pretending a second phone is a watch to obtain the companion-watch role.
- Replacing the phone app or requesting the default dialer role in the first
  implementation.
- Outgoing call placement, call waiting, conference calls, hold, swap, mute,
  DTMF, video calls, or emergency-call control in the stock implementation.
- Caller name, caller number, contact data, SIM identity, or call audio in the
  Twinotify protocol.
- Sending Bluetooth audio through Room, the relay mailbox, or the reliable
  notification outbox.

### Acceptance criteria

- A capability matrix names what works in a stock app, default dialer, wearable
  companion, and privileged/system build.
- A stock build never shows a live control without a currently registered
  origin-side `PendingIntent` for the exact call state generation.
- Every control command is authenticated, bound to one incoming call session
  and one state sequence, expires 15 seconds after the peer tap, and executes
  at most once.
- No number, contact, raw dialer notification text, intent description, or
  audio crosses the wire.
- Incoming-call answer/decline/hang-up works on every device/dialer combination
  promoted to the supported compatibility matrix. Unsupported combinations
  show state only.
- Direct LAN and Bluetooth control delivery reach the origin in under 750 ms at
  p95 in the physical two-phone test; relay delivery reaches it in under 2 s at
  p95 under the test network.
- The Bluetooth data route preserves the single `TransportCoordinator`
  outbox-drainer lease and the existing durable-custody invariants.
- Exact remote audio is not marked supported until a privileged phone-B build
  passes bidirectional SCO audio, call control, route switching, reconnect, and
  emergency-call isolation tests.

## 3. Current Twinotify baseline

The repository already has most of the stock control-plane foundation:

- `TelephonyCallStateSource` observes only `RINGING`, `OFFHOOK`, and `IDLE` with
  `READ_PHONE_STATE`.
- `CallStateCoordinator` assigns a random call session, emits ordered
  `call.state` events, and deliberately carries no identity or audio.
- `CallStateMaterializer` posts a generic, action-free `CATEGORY_CALL`
  notification with capability code `call_style_deferred_no_controls`.
- The notification-action subsystem already implements the correct security
  pattern: memory-only origin capability handles, opaque UUIDs, encrypted
  invoke/result events, durable at-most-once claims, and honest `dispatched`
  rather than fabricated success.
- `TransportCoordinator` grants one outbox-drainer lease to LAN or relay.
- Direct LAN already transports the same stored E2EE envelopes as the relay.
- The current Room schema is version 11. The earlier version-9 note in the
  repository handoff is stale; code and committed schemas are authoritative.
- `mobile/app.json` deliberately blocks `RECORD_AUDIO`, consistent with the
  current no-audio product boundary.

The missing work is to bind a source dialer's call capabilities to the existing
call session, add call-specific protocol semantics and native controls, and add
a Bluetooth direct route. Audio is not an incremental permission change.

## 4. Android capability matrix

| Capability | Stock Twinotify app | Twinotify as default dialer | Wearable companion contract | Privileged/OEM/custom Android |
|---|---:|---:|---:|---:|
| Observe coarse cellular state | Yes | Yes | Yes | Yes |
| Answer/decline incoming call | Conditional through source `CallStyle` token | Yes through `Call.answer/reject` | Yes for a physical wearable | Yes |
| Hang up an answered incoming call | Conditional through source token | Yes through `Call.disconnect` | Yes for a physical wearable | Yes |
| Safely control outgoing/emergency calls | No | Telecom-controlled, with emergency rules | Device/profile dependent | Yes, subject to platform policy |
| Hold, swap, DTMF, conference | No | Yes when the `Call` exposes it | Profile dependent | Yes |
| Create an arbitrary remote call endpoint | No | No | No public arbitrary endpoint | Yes, platform integration required |
| Capture cellular uplink/downlink PCM | No | No | Not granted by `InCallService` alone | Yes with privileged audio integration |
| Bluetooth call audio on phone B | No portable phone-to-phone role | No portable phone-to-phone role | Implemented by the wearable/system stack | Yes through HFP Client or LE Audio terminal integration |
| Wi-Fi call audio on phone B | No | No | Vendor/system implementation | Yes, with a custom Telecom/audio bridge |

### What “all smartwatch call features” decomposes into

HFP 1.10 does not define one all-or-nothing “smartwatch mode.” Its application
feature table marks capabilities separately for the Hands-Free and Audio
Gateway roles, and many advanced items are optional. The real feature set on a
phone pair is the intersection of both Bluetooth stacks, the network call, and
the UI/product policy ([Bluetooth HFP 1.10, Table 3.1](https://www.bluetooth.com/specifications/specs/hands-free-profile-1-10/)).

| Feature family | HFP role support | Stock Twinotify decision | Controlled-system target |
|---|---|---|---|
| Connection and phone-state indicators | Core HFP | Already represented by privacy-bounded `call.state` | Native HFP indicators are authoritative while connected |
| Accept, reject, terminate | Core, although reject support on the AG is optional | Answer/decline/hang-up only for compatible incoming-call capabilities | Native HFP control; do not duplicate through Twinotify |
| Bidirectional speech and audio transfer | Core synchronous audio connection | Impossible for the stock APK | Required through system HFP Client and SCO/eSCO |
| Dial a supplied number, memory dial, redial | Optional on HF; AG support varies by feature | Excluded from the first release | Later safety-reviewed HFP UI only |
| Caller ID | Optional on HF | Deliberately excluded from Twinotify payloads and UI | Separate explicit privacy decision before system UI exposure |
| Call waiting, three-way, hold, enhanced call control | Mostly optional and negotiated | Excluded because the current coarse telephony source cannot map multiple calls safely | Later phase only after per-call Telecom/HFP state is proven |
| DTMF | Optional on HF | Excluded | Later native HFP feature if both sides negotiate it |
| Remote volume | Optional | Does not grant carrier-call PCM access | Native audio-stack behavior; validate min/max/mute separately |
| Wideband/super-wideband speech and codec negotiation | Optional but coupled when wideband is enabled | Not applicable | Validate CVSD plus every codec the target claims, including fallback |
| Voice recognition, battery, operator/subscriber, call forwarding | Optional feature families | Outside the call-control bridge | Add only when there is a named product requirement and platform support |

This prevents “all features” from becoming an untestable promise. Phase 1 has
three named controls. The controlled-system track begins with the core HFP set,
then promotes optional features one by one only when both target phones report
and pass them.

The documented third-party `InCallService` path is either the default dialer or
a companion app associated with a **physical wearable** and declaring
`MANAGE_ONGOING_CALLS`
([`InCallService`](https://developer.android.com/reference/android/telecom/InCallService),
[`TelecomManager.hasManageOngoingCallsPermission`](https://developer.android.com/reference/kotlin/android/telecom/TelecomManager.html)).
AOSP's watch/glasses profiles require a connected wearable experience with a
screen, microphone, speaker, caller UI, and bridged calls
([companion device profiles](https://source.android.com/docs/core/connect/companion-device-profile)).
A second handheld phone is not that documented contract, so Twinotify must not
base a production feature on claiming the watch profile.

Becoming the default dialer would make call controls robust because the app
receives `Call` objects and can use `answer`, `reject`, and `disconnect`
([`Call`](https://developer.android.com/reference/android/telecom/Call)). It is
still not a public media-tap API, and it would turn Twinotify into a complete
phone UI with emergency-call and dial intent obligations. That cost is not
justified for the first control release.

## 5. Selected stock control design

### 5.1 Eligibility and fail-closed matching

A source notification may provide call capabilities only when all conditions
are true:

1. Call mirroring and the separate call-controls consent are enabled.
2. The notification package equals `TelecomManager.defaultDialerPackage`.
3. Its category is `Notification.CATEGORY_CALL`.
4. Exactly one Twinotify cellular call session is current.
5. For `ringing`, both `Notification.EXTRA_ANSWER_INTENT` and
   `Notification.EXTRA_DECLINE_INTENT` are present.
6. For `active`, the session direction is `incoming` and
   `Notification.EXTRA_HANG_UP_INTENT` is present.
7. There is exactly one eligible source call notification. Ambiguity produces
   no controls.

The required intent extras are public from API 31, and the current mobile floor
is API 34. Generic `notification.actions` remain a compatibility observation,
not the authority for this feature. Twinotify never matches localized action
labels such as "Answer".

Call waiting and concurrent calls are excluded because
`TelephonyCallback.CallStateListener` exposes one coarse aggregate phone state,
not stable per-call `Call` objects. A future default-dialer/system track may add
them.

### 5.2 Capability lifecycle

- `CallCapabilityCollector` copies only the live `PendingIntent` objects into a
  process-memory registry.
- It mints a random canonical UUID per `answer`, `decline`, or `hang_up`
  capability.
- `CallStateCoordinator.refreshControls(...)` emits a new sequence for the
  current session even when the telephony state did not change.
- The registry generation is installed only after that exact `call.state`
  sequence commits to Room, following `ActionGenerationCommitter`'s existing
  post-commit pattern.
- A newer source notification, terminal `idle`, notification removal, disable,
  unpair, or process restart purges the generation.
- Listener reattachment may recapture a currently active source notification
  and publish a new sequence with fresh UUIDs. Old UUIDs never become valid
  again.

### 5.3 Wire contract

`call.state` retains its privacy-bounded fields and gains an optional complete
control set:

```json
{
  "call_session_id": "7b3a2b6e-9df1-4cab-8bbd-d49fba875a10",
  "state": "ringing",
  "direction": "incoming",
  "controls": [
    { "control_id": "2a846785-e576-47d0-8c4b-e4fba30d88bd", "kind": "answer" },
    { "control_id": "0d47171d-c1ae-463a-bae7-3e8778517c0f", "kind": "decline" }
  ]
}
```

The only legal sets are:

- ringing/incoming: no controls or exactly `answer` plus `decline`;
- active/incoming: no controls or exactly one `hang_up`;
- idle, outgoing, or unknown direction: no controls.

The peer tap creates `call.control.invoke`:

```json
{
  "invocation_id": "2a846785-e576-47d0-8c4b-e4fba30d88bd",
  "canon_id": "call:7b3a2b6e-9df1-4cab-8bbd-d49fba875a10",
  "call_session_id": "7b3a2b6e-9df1-4cab-8bbd-d49fba875a10",
  "call_sequence": 2,
  "control_id": "2a846785-e576-47d0-8c4b-e4fba30d88bd",
  "kind": "answer",
  "invoked_at": 1788422400000
}
```

The invocation ID intentionally equals the one-use capability ID. Multiple
taps or duplicate transport deliveries therefore converge on one durable
origin claim and cannot dispatch twice. An invoke expires 15 seconds after the
tap. A result expires after 5 minutes:

```json
{
  "invocation_id": "2a846785-e576-47d0-8c4b-e4fba30d88bd",
  "canon_id": "call:7b3a2b6e-9df1-4cab-8bbd-d49fba875a10",
  "kind": "answer",
  "status": "dispatched"
}
```

Legal results are `dispatched`, `outcome_unknown`, `capability_gone`,
`call_gone`, `stale_state`, `expired`, and `failed`. As with notification
actions, `dispatched` means Android accepted `PendingIntent.send()`. The next
organic `call.state` transition is the only positive confirmation that the call
actually changed.

These are peer-control messages with `requiresPeerReceipt=false`. They use the
existing encrypted inner event and route-owned custody path; the relay sees
only ciphertext and routing metadata.

### 5.4 Origin execution

Before `PendingIntent.send()` the origin:

1. authenticates the peer and validates the closed schema, ID equality, and
   15-second freshness window;
2. durably claims `invocation_id`, bound to the call canonical ID, sequence,
   and kind, through the existing action-execution journal;
3. for a new claim, validates the current session, sequence, incoming
   direction, and exact in-memory capability generation;
4. dispatches once;
5. writes the truthful result and encrypted result outbox row in one Room
   transaction.

A duplicate reaches the claim first and replays the stored result without
consulting mutable current-call state. A message that reuses a control UUID
with a different canonical ID, sequence, or kind is an ID conflict.

A crash after the claim and before recorded completion returns
`outcome_unknown` after the existing claim-recovery window. It never retries a
possibly executed call command.

### 5.5 Mirror interaction

The paired phone uses the platform `Notification.CallStyle`, not a custom React
incoming-call screen:

- `ringing` plus both controls: `forIncomingCall` with Twinotify-owned direct
  broadcast `PendingIntent`s for decline and answer;
- `active/incoming` plus hang-up: `forOngoingCall` with a Twinotify-owned hang-up
  `PendingIntent`;
- no valid control set: the existing generic action-free `CATEGORY_CALL`
  notification;
- `idle`: cancel the stable call mirror.

The displayed person name is `Call on <paired device>`, with `Paired device` as
the fallback. No caller identity is inferred from the source notification.
Twinotify does not use a full-screen intent and does not draw fake or disabled
buttons.

Call controls remain usable from the paired phone's lock screen after a direct
user tap, matching ordinary call UX. The opt-in disclosure must state that
anyone holding the paired phone can answer, decline, or end an incoming call,
including while that phone is locked. This differs deliberately from mirrored
message actions, which require unlock.

The Settings surface adds a separate `Let paired phone control calls` switch
under the existing call-state switch. This is a source-side permission: turning
it on lets the paired phone operate capabilities from calls received on this
phone. A mirror does not require a second local opt-in because the call-origin
phone already granted the capability. Its consent copy is fixed:

> Let `<paired device>` control calls?
>
> Anyone holding `<paired device>` can answer, decline, or end an incoming
> cellular call on this phone, including while `<paired device>` is locked.
> Call audio stays on this phone. Twinotify never sends the caller's name or
> number.

Turning off call-state mirroring also turns off and purges locally advertised
call controls. It does not fabricate or retain a remote capability.

## 6. Bluetooth data route

The Bluetooth route is for Twinotify's E2EE messages, not carrier-call audio.
It transports notifications, receipts, snapshots, call states, and call-control
events when Wi-Fi is unavailable.

### Selected link

Use secure Bluetooth Classic RFCOMM first:

- Android exposes symmetric peer-to-peer server and client sockets with a
  private service UUID. Secure RFCOMM authenticates and encrypts the Bluetooth
  link, and first connection can trigger the system pairing dialog
  ([Android Bluetooth connection guide](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices),
  [`BluetoothDevice.createRfcommSocketToServiceRecord`](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)).
- RFCOMM is stream-oriented and fits Twinotify's length-prefixed frames and
  payloads up to 1 MiB. BLE GATT is not used as a bulk mailbox transport.
- LE L2CAP CoC is a good later optimization. Android exposes a secure,
  authenticated, encrypted LE channel from API 29, but the application must
  separately discover the dynamic PSM
  ([`BluetoothDevice.createL2capChannel`](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)).
  It is not required for the first route.

Use generic `CompanionDeviceManager` association for user-approved discovery
and background presence. Do not request `DEVICE_PROFILE_WATCH`.
Companion association does not itself create the data connection
([Android companion-device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)).

The manifest adds `BLUETOOTH_SCAN` with `neverForLocation`,
`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, and
`FOREGROUND_SERVICE_CONNECTED_DEVICE`. The existing service declares and starts
with `remoteMessaging|connectedDevice` while Bluetooth is enabled. Android
documents `connectedDevice` or companion presence as the supported long-running
Bluetooth choices
([Bluetooth background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background),
[foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)).

### Pair binding and authentication

- Bluetooth enablement happens only after the existing Twinotify E2EE pair is
  confirmed.
- Store the CDM association ID, pair generation/binding ID, peer device ID,
  peer signing-key digest, and protocol version in the existing encrypted LAN
  binding store or a sibling encrypted Bluetooth binding. Do not persist a raw
  Bluetooth address in Twinotify storage.
- Resolve the current associated `BluetoothDevice` from CDM at connection time.
- Both peers exchange fresh nonces, stable connection roles, device IDs, route
  label `bluetooth-rfcomm-v1`, and the protocol floor, signed with the existing
  Ed25519 pairing identities.
- Bluetooth bonding is defense in depth. The Twinotify signed handshake is the
  authority; a bonded but different device cannot become the peer.
- Use deterministic initiator selection by the lexicographically smaller
  Twinotify device ID. The other phone listens; a delayed reverse attempt
  handles asymmetric OEM behavior.

### Shared delivery core and route priority

Extract only the route-neutral custody loop from `LanTransport`: ordered put,
durable inbound commit, exact-digest accepted acknowledgement, heartbeat,
bounded writer, and close. Keep LAN TLS/discovery and Bluetooth RFCOMM/handshake
as separate adapters.

When the existing direct-delivery preference is on, the route order is:

1. authenticated same-network LAN;
2. authenticated Bluetooth RFCOMM;
3. relay.

Only the granted route reads `OutboxRepository.sendable`. A candidate route may
authenticate while relay is active, but it receives the lease only after the
old session closes and joins. `CustodyRoute.BLUETOOTH` is additive; a row sent
but not accepted remains eligible on the next route under the existing
idempotency rules.

When the user turns direct-delivery preference off, a healthy relay stays first
and LAN/Bluetooth are fallbacks. The UI must not silently override that explicit
choice.

Wi-Fi Aware remains an optional future LAN adapter, not a new application
protocol. It supports direct bidirectional sockets without an access point and
higher throughput/range than Bluetooth, but hardware support varies
([Android Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware)).
The current same-network LAN route remains the primary Wi-Fi implementation.

## 7. Exact Bluetooth audio track

### 7.1 Required product shape

Phone B must run a controlled Android build that enables the AOSP HFP Client
profile, exposes its Telecom `HfpClientConnectionService`, and grants the
required privileged Bluetooth access. Phone A remains an ordinary Android HFP
Audio Gateway. The platform Bluetooth stack, not Twinotify's reliable outbox,
owns AT call control, SCO codec negotiation, microphone input, downlink output,
and audio focus.

Twinotify's role is limited to:

- guide the user through pairing and show the exact peer identity;
- report whether the system HFP Client profile and SCO route are available;
- enable or disable the trusted paired phone as a call endpoint through a
  narrow privileged Binder service;
- keep notifications and non-audio state synchronized over its normal routes;
- never duplicate an HFP-native call command over `call.control.invoke` while
  the system HFP session owns the call.

This is closest to a real smartwatch/car implementation and minimizes custom
audio code. It requires platform-image work, vendor audio-policy validation,
Bluetooth qualification, and device-specific testing. It is not a feature that
can be enabled by adding `RECORD_AUDIO` to the current APK.

### 7.2 Media acceptance gate

Do not mark the HFP track complete until all of these pass on named physical
builds:

- incoming ring, answer, reject, and hang up from phone B;
- bidirectional speech with phone A screen on, off, and locked;
- narrow-band and wide-band speech negotiation;
- volume synchronization and mute behavior without changing an unrelated
  headset or phone-A media stream;
- speaker/Bluetooth/earpiece route switching without a stuck audio route;
- disconnect, reconnect, phone-B reboot, and phone-A reboot during idle and
  during a call;
- competing headset/car routing policy;
- no duplicate commands from Twinotify's stock control bridge;
- emergency calls remain owned by phone A's system dialer and never auto-route
  or remote-control without a separately reviewed platform safety policy;
- no call PCM enters Twinotify logs, Room, crash reports, relay, or history.

Record the negotiated HFP feature bitmap for both roles. Caller ID, DTMF,
outgoing dialing/redial, call waiting, hold/swap, and multiparty control remain
off until each is supported by both endpoints and passes a separately reviewed
test matrix. The initial controlled-system milestone is the core incoming-call
experience, not every optional HFP flag at once.

## 8. Wi-Fi media track

There is no selected stock implementation. If an OEM/custom-system product is
approved, the media architecture is:

```text
Telecom/vendor call endpoint on phone A
    -> privileged PCM bridge
    -> real-time encoder + SRTP/DTLS or equivalent authenticated media channel
    -> LAN or Wi-Fi Aware socket
    -> jitter buffer + decoder
    -> privileged Telecom/audio endpoint on phone B
    -> phone-B mic return path
```

This path needs acoustic echo cancellation, noise suppression, gain control,
jitter/loss concealment, audio focus, route switching, and a target mouth-to-ear
latency below 250 ms at p95. It must use a real-time ephemeral media channel;
audio frames never use Room, mailbox retention, peer receipts, or the relay.

The common code boundary is the call-session state machine, authorization,
route negotiation, and observability. The actual media backends are different:
HFP/SCO is system Bluetooth; Wi-Fi uses a packet media engine. Forcing them
through one transport implementation would make both worse.

AOSP's `CallStreamingService` is `@SystemApi`/hidden and describes a general
VoIP streaming sender, further confirming that call streaming is a system-role
integration rather than an ordinary application API
([AOSP `CallStreamingService`](https://android.googlesource.com/platform/frameworks/base/+/412454e5731d/telecomm/java/android/telecom/CallStreamingService.java)).

## 9. Security and privacy model

### Threats addressed

- **Forged command:** inner events remain signed/authenticated by the paired
  identity and encrypted end to end.
- **Replay:** the one-use capability UUID is also the durable invocation key;
  duplicates replay a result and never redispatch.
- **Late answer:** the invoke expires after 15 seconds and must still match the
  current session, sequence, direction, and in-memory capability.
- **Wrong call:** coarse or ambiguous multi-call state advertises no controls.
- **Intent injection:** the wire contains no component, package, action string,
  URI, extras, number, or `PendingIntent`. Only the origin-generated UUID is
  accepted.
- **Capability theft at rest:** source call intents are memory-only and purged
  at every terminal boundary.
- **Lost-process uncertainty:** a claimed but incomplete execution becomes
  `outcome_unknown`, never a retry.
- **Nearby attacker:** Bluetooth association and bonding do not replace the
  Ed25519 peer handshake.
- **Lock-screen abuse:** controls require an explicit tap on the paired phone,
  and enablement warns that physical possession is sufficient, as with a watch.

### Data minimization

The stock call protocol remains generic. It sends no number, contact, person,
SIM, verification text/icon, dialer package, notification title/body, or audio.
Call-control status in history is limited to kind, direction, route, timestamp,
and stable outcome code.

Twinotify must not add `READ_CALL_LOG`. Google Play restricts call-log access and
requires declaration/review for eligible connected-device or cross-device uses
([Play call-log policy](https://support.google.com/googleplay/android-developer/answer/10208820)).
The selected capability-token design does not need call-log data.

## 10. State and failure semantics

```text
IDLE
  -> RINGING_STATE_ONLY
  -> RINGING_CONTROLLABLE
       answer tap -> COMMAND_PENDING -> ACTIVE_CONTROLLABLE | NOT_CONFIRMED
       decline tap -> COMMAND_PENDING -> IDLE | NOT_CONFIRMED
  -> ACTIVE_STATE_ONLY
  -> ACTIVE_CONTROLLABLE (incoming sessions only)
       hang-up tap -> COMMAND_PENDING -> IDLE | NOT_CONFIRMED
  -> IDLE
```

Rules:

- A state-only event can become controllable when the default dialer posts its
  `CallStyle` notification; this is a new sequence, not an in-place mutation.
- A capability removal is also a new sequence with no controls.
- The mirror never retains controls from a lower sequence.
- `dispatched` does not move the canonical call state. Only a later authenticated
  `call.state` event does.
- A control failure restores no stale token. It leaves the current organic
  state authoritative and tells the user to use the source phone.
- Relay mailbox delay past 15 seconds yields `expired`; it never answers a call
  late.

## 11. Verification matrix

### Automated

- Closed JSON-schema fixtures for every valid control set, forbidden privacy
  field, duplicate kind, mismatch, and result status.
- JVM tests for candidate eligibility, ambiguity, generation commit/purge,
  sequence refresh, freshness, at-most-once claim, crash recovery, lock-screen
  receiver behavior, and native `CallStyle` selection.
- Instrumented tests with synthetic `CallStyle` source notifications carrying
  real local test `PendingIntent`s.
- Coordinator tests proving LAN, Bluetooth, and relay never drain concurrently.
- Bluetooth socket/frame tests for bounds, cancellation, digest acknowledgement,
  role arbitration, signed peer mismatch, and reconnect.
- TypeScript tests for truthful consent/status copy and 48 dp controls.

### Physical stock-control matrix

For each promoted source dialer and OS build, capture device model, build
fingerprint, dialer package/version, lock state, route, command, tap-to-origin
latency, observed source state transition, and redacted failure code. Run:

- screen on, screen off, source locked, peer locked;
- LAN, Bluetooth, relay, and loss during handoff;
- process death before tap and after durable claim;
- answer, decline, and hang-up for an incoming session;
- outgoing and multi-call cases proving controls are absent;
- Bluetooth permission denial, Bluetooth off, unpair, and stale CDM association;
- notification permission denial and call-controls disable.

An OEM/dialer enters the supported matrix only when all required incoming-call
cases pass. Otherwise Twinotify advertises state-only compatibility.

## 12. Delivery phases and stop/go gates

### Phase 0: compatibility probe

Build the debug-only capability inspector and run it on every target phone. It
records only presence/type booleans and stable error codes, never intent or
caller content.

**Go:** at least the target launch devices expose answer+decline while ringing
and hang-up after answering.

**Stop:** a target dialer exposes no usable tokens or produces ambiguous
candidates. Do not work around it with accessibility automation or hidden APIs.

### Phase 1: stock incoming-call controls

Implement the protocol, memory registry, durable one-use invocation, native
`CallStyle`, consent, and physical compatibility gate. Audio remains on phone A.

### Phase 2: Bluetooth E2EE data route

Add generic CDM association, secure RFCOMM, signed identity handshake, shared
direct-delivery core, and LAN > Bluetooth > relay route promotion.

### Phase 3: exact Bluetooth audio decision

Proceed only if the product accepts one of these requirements:

- phone B runs a Twinotify-controlled custom Android image; or
- an OEM/preload partner grants and supports the HFP Client integration.

If neither is acceptable, exact remote cellular audio is closed as infeasible
for the stock product. Do not keep an indefinite application-layer audio spike.

### Phase 4: Wi-Fi audio research

Proceed only after Phase 3 supplies a supported privileged call-audio endpoint.
Prototype the media channel behind that interface and compare LAN vs Wi-Fi
Aware. The prototype does not broaden the stock APK's permissions or claims.

## 13. Rejected alternatives

- **Record speaker plus microphone:** echo-prone, not private, not exact, and
  still cannot reliably capture the remote party under Android audio policy.
- **Accessibility-service button automation:** brittle, unsafe on the lock
  screen, UI-language dependent, and outside the intended API contract.
- **Deprecated `TelecomManager.acceptRingingCall()`:** not a supported modern
  architecture.
- **Claim `DEVICE_PROFILE_WATCH` for a phone:** outside the documented physical
  wearable contract and unsuitable as a production dependency.
- **Make Twinotify default dialer immediately:** a large emergency/dialer/UI
  commitment that still does not expose arbitrary carrier PCM.
- **Use notification action labels to identify answer/decline:** localized and
  OEM-dependent; the typed `CallStyle` extras are the authority.
- **BLE GATT for the entire outbox:** poorly matched to current 1 MiB frames and
  unnecessary when RFCOMM provides a stream.
- **Nearby Connections as the first Bluetooth route:** adds a Google Play
  services dependency and hides route behavior while Twinotify already owns a
  direct socket protocol.
- **Send audio through v2 reliability:** retransmission, durable storage, relay
  retention, and head-of-line blocking are all wrong for live media.

## 14. Product decision required before exact audio work

Choose one:

1. **Stock-app product:** state, answer/decline/hang-up where the source dialer
   is compatible, Bluetooth/Wi-Fi/relay control delivery, and explicit
   `Audio stays on your other phone` copy.
2. **Controlled-system product:** everything above plus a required custom
   Android/OEM build on phone B for real HFP call audio.

The first option is the recommended next build because it produces useful,
testable software without hiding a platform limitation. The second is the only
honest route to the complete smartwatch-like carrier-call experience.
