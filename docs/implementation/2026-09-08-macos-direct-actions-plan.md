# Mac direct delivery, reliability and actions

Authorized on 2026-09-08 after the inbox checkpoint (`687c4ef`). Work stays in the
primary checkout. Account/device-pool work is a design exploration, not an account
service rollout.

1. Implement automatic Mac-to-Android LAN connections with relay fallback.
2. Exercise three-device isolation, route loss/recovery, restart, sleep/wake and
   duplicate handling; distinguish emulator evidence from physical evidence.
3. Add Android-advertised notification actions and text replies to the Mac inbox,
   using the existing authenticated invocation/result protocol.
4. Document an account/device-pool design, trust model and staged migration.

## Transport decisions

Reuse encrypted `lan.bootstrap`, the derived per-peer LAN secret, daily private
Bonjour advertisement IDs, pinned mutual TLS, signed challenge/response, LAN v1
framing and exact-byte encrypted envelopes. Mac initially dials Android's existing
listener; it does not need to advertise a redundant receiver listener. Bind
production discovery/connections to the discovered local network interface.

Negotiate `twinotify-lan/2` with TLS ALPN for a portable exporter context:
32 bytes from the TLS exporter label `EXPORTER-twinotify-lan-v1`, without an
application context. The existing signed transcript and nonce challenges then
bind to that exporter. Android retains its existing context when ALPN is absent,
so older phone pairs remain compatible. Mac requires the exporter mode and falls
back to relay with old Android builds. No protocol floor downgrade or schema
change is involved; this negotiates the TLS binding, not the encrypted protocol.
Use system TLS and platform cryptography. No global trust override or plaintext
network path is introduced.

A single per-link coordinator owns the outbox-drainer lease. Relay may drain
while LAN discovers/authenticates; the coordinator cancels and joins relay before
granting the authenticated LAN connection the lease. LAN failure returns the
lease to relay. Cancelling a peer session joins its workers before a replacement
starts. Permission recovery uses the same receiver and desired-state journal.

Persist peer LAN pins in encrypted storage and reject conflicting replacements.
Extend the Mac outbox to distinguish custody-only controls from receipt-backed
commands before adding bootstrap/action sends. LAN custody requires exact digest
matching and follows durable inbound commit. Reliable commands remain until a
peer receipt or authenticated action result, never merely a socket write.

## Actions

Only render actions explicitly advertised by the authenticated phone. Bind each
invocation to link generation, canonical notification, sequence and action ID.
Persist one immutable invocation before transport; retries reuse its UUID and
ciphertext. Repeated clicks while pending reuse the pending operation. Never
reissue automatically after an uncertain result. Replies are capped at the
existing 4096-byte protocol limit; no reply text enters logs or activity metadata.
Use explicit Send and show dispatch/failure/unknown results honestly. Local inbox
Clear continues to affect Mac copies only.

## Verification

Use deterministic codec/crypto/custody/coordinator tests, real TLS loopback and
Android interoperability checks, native Mac UI exercises, then available device
checks. Record unavailable physical sleep/wake or OEM evidence explicitly.
Source completion does not substitute for hardware acceptance.

## Outcome

Source work and available emulator acceptance are complete. Native Reply, Send,
Cancel and Mark as read were exercised; queued reply delivery survived a Mac
restart without a second invocation. All eight three-device matrix checks and
the real Android–Mac encrypted LAN delivery test pass. The account/device-pool
proposal is written. See the [verification record](../qa/macos-direct-actions-2026-09-08.md)
for evidence and the remaining physical Wi-Fi/sleep-wake and pre-fix journal
upgrade gates. The personal installed app was not replaced.
