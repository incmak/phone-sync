# Twinotify Relay

The Twinotify relay pairs two devices, authenticates their WebSocket sessions, and durably queues opaque encrypted envelopes for the paired recipient. The relay is a transport service: it cannot read notification content and it does not decide when a notification has been applied on a phone.

## Delivery semantics

For protocol v2, `relay.accepted` means the relay committed the encrypted envelope to BoltDB. It does not mean the peer received, decrypted, or applied the notification. Peer delivery is established only by an authenticated, end-to-end encrypted `peer.receipt` from the receiving device. A recipient sends `relay.ack` after its receipt has been durably accepted; until then the original envelope may be redelivered. Clients must therefore apply events idempotently.

Mailbox items expire 24 hours after the relay's first durable acceptance. Expiry uses relay time, not a client timestamp. The relay retains a metadata-only expiry status for another 24 hours so the sender can receive `relay.expired` after reconnecting.

Per recipient, the relay accepts at most:

- 2,000 pending mailbox items;
- 128 MiB of pending ciphertext;
- 1 MiB per encrypted envelope.

The item and byte limits return `relay.rejected` with `mailbox_full`. Existing ciphertext is not evicted to admit a new item.

## Privacy and relay-visible metadata

The relay persists only the encrypted envelope and routing metadata needed to operate the mailbox: pair and device identifiers, sender and recipient, outer message ID, acceptance and expiry times, byte size, and a SHA-256 digest of the exact envelope. Operators can also observe connection addresses, timing, and traffic sizes. Notification titles, text, package names, canonical notification IDs, event types, sequences, and peer receipts belong inside end-to-end encrypted ciphertext and are neither persisted nor intentionally logged by the relay.

TLS protects relay control frames and the metadata visible in transit. End-to-end encryption protects notification content from the relay.

## Protocol compatibility

Paired v2 clients start the authenticated WebSocket session with `relay.hello` and advertise supported protocols. The pair's protocol floor advances to v2 only after both devices advertise v2 support.

Legacy v1 is an online-only compatibility path. A raw v1 frame can be forwarded only while the peer is currently connected, and `relay.legacy_forwarded` means only that the frame entered the live peer writer queue. It is not durable acceptance or peer receipt. Raw v1 is rejected after the pair's persisted protocol floor reaches v2. A v1 encrypted envelope wrapped in `relay.put` can be stored for a typed peer while the pair remains at floor 1.

## Run and verify locally

From the repository root:

```sh
make proto-test
make relay-test
make relay-verify
```

`relay-test` is the narrower developer race-test command. `relay-verify` additionally checks formatting, runs `go vet`, executes all relay tests with the race detector, and builds `twinotify-relay:verify` from `relay/Dockerfile`.

To run the development deployment:

```sh
docker compose -f deploy/docker-compose.yml up -d
curl -fsS http://localhost:8080/health
```

The development Compose file publishes port 8080 for local clients and stores BoltDB in the `relay-data` volume at `/data/twinotify-relay.db`.

## Production TLS

Do not publish the relay's plaintext port in production. `deploy/docker-compose.prod.yml` places the relay on an internal network, exposes port 8080 only to Caddy, and publishes Caddy on ports 80 and 443. Set `TWINOTIFY_DOMAIN` to the production host and configure public DNS before starting it:

```sh
TWINOTIFY_DOMAIN=relay.example.com \
  docker compose -f deploy/docker-compose.prod.yml up -d
```

Release clients must use `https://` and `wss://`. Plain `http://` and `ws://` are for explicitly configured local development only. Keep `/health`, `/pair/*`, and `/ws` behind the trusted TLS proxy rules supplied in the production Caddy configuration.

## Backup and restore

`/data` is the authoritative persistent relay state. It contains pairing records, capability floors, expiry indexes, and queued ciphertext in `twinotify-relay.db`. Back up the whole `/data` volume using a volume snapshot or a copy taken while the relay container is stopped, so the BoltDB file is consistent.

To restore, stop the relay, restore the complete saved `/data` contents into the `relay-data` volume with the original ownership and permissions, then start the relay and check `/health`. Do not restore only selected Bolt buckets or merge databases. Protect backups like production metadata because they contain device relationships, timing, and ciphertext.

## Revocation

An authenticated device revokes its pair with `POST /pair/revoke`. Revocation atomically removes both device authorizations, paired capability state, pending pairing state, and ciphertext in both mailbox directions, then closes active pair sockets. Existing JWTs and signing keys for the revoked pair stop authorizing requests. Re-pairing creates new authorization state; restoring a backup taken before revocation can restore old state and must be treated as a security-sensitive rollback.

## Health and failure codes

`GET /health` is an unauthenticated liveness check and returns HTTP 200 with `{"status":"ok"}` while the process can serve requests. It is not a deep delivery, disk-capacity, or peer-connectivity check.

Stable relay WebSocket outcomes are:

- `relay.accepted`: ciphertext was durably committed; not peer delivery.
- `relay.legacy_forwarded`: a v1 frame entered a connected peer's in-memory queue; not durable acceptance.
- `relay.deliver`: a pending mailbox envelope is being delivered or redelivered.
- `relay.expired`: the 24-hour mailbox retention elapsed before acknowledgement.
- `relay.rejected` with `mailbox_full`: recipient item or byte quota is full.
- `relay.rejected` with `id_conflict`: the recipient already has the message ID with different envelope bytes.
- `relay.rejected` with `digest_mismatch`: acknowledgement digest does not match the stored envelope.
- `relay.rejected` with `not_recipient`: the authenticated device is not allowed to acknowledge or address that mailbox item.
- `relay.rejected` with `peer_legacy`: the requested durable path is unavailable for the peer's negotiated protocol state.
- `relay.rejected` with `invalid_frame`: malformed, out-of-order, or protocol-incompatible input. An encrypted envelope over 1 MiB closes the socket before normal frame handling; the relay-control read limit includes bounded wrapper overhead.

Pairing and revocation endpoints also use normal HTTP status codes for malformed input, authentication failure, rate limiting, state conflict, and capacity exhaustion. Client logic should branch on the stable machine-readable relay reason, never on validator or HTTP prose.
