# Relay upgrade preparation — 2026-09-07

The production relay at `https://relay.twinotify.nuvaynlabs.com` runs
`relay-manual-30b99c6f3ea6` and returns HTTP 404 for `/pair/session`. This blocks
resolving an existing Android peer's relay membership before adding the Mac.
The client retry loop and changing QR were fixed separately.

The existing Oracle VM and its saved SSH credentials were located successfully.
Production Compose and Caddy configuration hashes match this checkout. The live
relay has recent completed backups and approximately 44 GB of free disk space.

A local candidate was built from the current verified relay/protocol source:

- Version: `relay-mac-pair-2af0c936c68c`
- Source tree fingerprint: `2af0c936c68c080b9b4c80e461eccdcf9379b7ff45f1c13e431633fcb149198a`
- Loaded image: `twinotify-relay@sha256:bf7487e80c11eb14f287e45fac7f38687841e98d8ca37e1c7c907941cf38e224`
- Image archive SHA-256: `2dc79937b1b6d5c2f2e39d08d9a0b82764a18aa960228100dc8656243741188a`

This is a locally built candidate, not a published or GitHub-attested release.
The source fingerprint hashes sorted relay/proto file paths and contents,
excluding the generated relay schema directory. No source commit was created.

The image archive was copied to a private staging directory on the VM and its
SHA-256 verified before loading. A completed 09:34 UTC backup was copied into
separate rehearsal data and backup directories. Both the original and copied
files initially hashed to
`b1d2aefc8715f386bd0359e5b305e5f3039c528092572c554fb1836e419fa951`.
The candidate ran against this copy with the complete production environment,
nonroot user, read-only root filesystem, dropped capabilities, and resource
limits. Only a VM-loopback test port was exposed.

The copied database migrated successfully: readiness reported the candidate
version, and unauthenticated `/pair/session` returned the expected HTTP 401.
The first rehearsal launch omitted required production environment values and
failed closed; rerunning with the full Compose-equivalent environment passed.
The rehearsal container was stopped and removed. The original backup checksum
and the production container's healthy status and old image were checked again.
Evidence: `/tmp/twinotify-relay-migration-rehearsal.log` on the development Mac.

Before the cutover below, the production database had not been migrated. Applying the upgrade required
explicit confirmation because the storage format changes. The cutover must
stop the only writer, take a fresh pre-upgrade backup, use the immutable candidate,
verify readiness and public smoke checks, and preserve the old image and backup.
After a candidate start attempt, do not automatically restart the old binary or
restore old data; follow the explicit recovery decision in the production runbook.


## Approved production cutover

The user explicitly approved the live migration. The guarded deployment completed
successfully at 2026-09-07 15:23 UTC using the candidate digest above. A fresh
pre-migration backup was written after stopping the only relay writer:
`twinotify-relay-20260907T152304.682441908Z-manual.db` (19,320,832 bytes).
The new relay also created its startup snapshot. Both the prior image and backup
remain available; no restore was performed.

Public smoke checks passed, including exact version, readiness, security headers,
restricted endpoints, and WebSocket authentication. Public `/pair/session` now
returns HTTP 401 without credentials, replacing the missing-route HTTP 404. Both
production containers are running and the relay is healthy. The temporary
rehearsal container remains removed.

The deploy script now has an explicit `--preloaded-image` option for the verified
transferred image. It still requires a repository digest and matching version,
checks local availability before downtime, and pulls Caddy normally. Regression
tests cover missing-image refusal before stopping production and successful
preloaded deployment without a registry pull of the candidate.

The VM's canonical deploy script now contains the migration failure safeguards;
the previous script is preserved in the private upgrade directory. A mode-0600
Compose `.env` records the active digest/version/domain so future Compose starts
use the upgraded image. No private key or database content was added to the repo.
Evidence: `/tmp/twinotify-live-relay-upgrade.log` and
`/tmp/twinotify-preloaded-deploy-tests.log` on the development Mac.

A fresh physical phone–Mac pairing has not been performed as part of the server
cutover; the user can create a new Mac code and retry the scan.
