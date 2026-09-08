# Account and device-pool proposal

This is the requested exploration, not an implemented account service.

## Recommended first version

Use an account to find and manage a small device roster. Keep encryption trust on
the devices. Start with **three devices per pool**: the current two peer links per
device already support the full triangle, so this delivers a useful account flow
without coupling it to a larger fan-out migration.

A phone can send notifications to another phone and a receiver-only Mac. Each
recipient still gets its own encrypted envelope and durable receipt. LAN and
relay are routes for the same pair; an account is not a new transport or a shared
decryption key. Existing QR-paired devices continue working without an account.

## Joining a device

1. Sign in on an already trusted device and create a named pool.
2. On the new device, sign in and request to join. Signing in grants access to a
   pending roster entry, not notification contents or encryption membership.
3. An existing trusted device approves the new device's public identity using a
   QR/fingerprint check. Reuse the current pairing transcript and confirmation
   proof wherever a pair is created.
4. Show the directions the user is enabling, such as “POCO → Mac.” Do not silently
   send notifications to every device merely because it joined the roster.
5. Establish each approved peer link. Show partial enrollment honestly until each
   affected device has committed its link. Retry the enrollment by its immutable
   ID; do not create a second pairing generation on retry.

The account's human-readable device name is separate from the cryptographic
device ID. Names are display metadata, never authorization identifiers.

## Trust and server responsibilities

An account-authentication service proves control of an account. A signed roster
proves device membership. A compromised account session must not be able to add a
notification recipient without approval from a trusted device.

Each roster update binds the pool ID, epoch, previous roster hash, device public
keys, permitted roles and revoked membership IDs. Persist the highest accepted
epoch to reject rollback. Designate a trusted owner device for roster changes in
the first version; adding multiple concurrent owners needs an explicit conflict
and recovery design before implementation.

The server stores account identifiers, device public identities, roster updates,
pending approvals and routing metadata. Notification content, reply text, private
keys and LAN discovery secrets stay out of the account service. Treat public
roster information as private account metadata with access controls and retention
limits, even though it is not a decryption secret.

Keep every relay mailbox operation authorized by the existing pair generation.
Pool membership must never become a bypass around pair-scoped put/ack/revoke.
Rate-limit account and invitation endpoints independently from notification
traffic. Joining a pool does not change the negotiated v2 protocol floor.

## Removal and recovery

“Remove device” produces a signed roster revocation and revokes each affected
pair generation. Each remaining device deletes the removed link's LAN trust and
rejects its old authenticated packets. Record progress per affected device;
removal is pending where an offline device has not learned the revocation.
Instant revocation between two fully offline devices is not a promise this design
can make. Do not hide that limitation behind an “all devices removed” status.

An account-password or passkey reset does not recreate device encryption keys.
If the trusted owner remains, it can approve a replacement. If the owner is lost,
the first version starts a new pool on a surviving trusted device and explicitly
re-enrolls the others; existing pair links can continue while that happens. A
non-owner cannot silently promote itself in the old signed roster. If all trusted
devices are lost, recovery starts a new pool identity and requires re-pairing;
it cannot recover old encrypted notification history. Never roll back the nonce
counter or restore an old identity database independently of its Keychain state.

## Data model and staged migration

Proposed account records: `account`, `pool`, `pool_device`, `roster_update`, and
`enrollment`. Device records add an optional pool ID/epoch and enrollment state.
Existing `peer_link` and pair-generation storage remain authoritative for
transport and crypto. Keep pool membership and enrollment IDs distinct from
installation device IDs and relay pair IDs.

Ship in stages:

1. Optional account and roster UI, with existing QR links imported only after the
   local device confirms their public identities. No key or notification-format
   changes in this stage.
2. Trusted-device approval and resumable enrollment of the third device. Test
   account takeover, replay, rollback, partial enrollment and offline revocation.
3. Device roles and notification-direction controls with explicit consent.
4. Only after this works, consider more than three devices. That requires a
   reviewed increase in per-device peer limits, fan-out quotas, storage and
   acceptance coverage. A group-encryption protocol is a separate decision; it
   is not needed for a three-device pool.

## Decisions to make before implementation

- Account authentication provider and whether passkeys are the initial sign-in
  method. Choose after verifying platform support, pricing and recovery behavior.
- Account/roster hosting and data retention. Reusing the relay deployment reduces
  operations; a separate service isolates login failures from delivery.
- Whether ownership can be transferred without another trusted device online.
- What the product shows when roster revocation is pending on an offline device.

Success means a user can approve a third device once, see exactly which links and
notification directions are enabled, remove it without affecting the other pair,
and understand recovery without the server ever holding notification keys.
