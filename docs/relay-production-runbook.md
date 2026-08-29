# Twinotify relay production runbook

This runbook operates the single-writer Go relay behind Caddy. It does not turn Bolt into a clustered database. Run exactly one `relay` service against the `relay-data` volume.

## Hosting choice

For an invite-only beta, use one Oracle Cloud Infrastructure Always Free Ampere A1 VM in the account's home region. Allocate 1 OCPU, 6 GB RAM, and the default 50 GB boot volume. Oracle currently documents an Always Free allowance equivalent to 2 Ampere OCPUs and 12 GB RAM, 200 GB combined boot/block storage, and five volume backups. Capacity can be temporarily unavailable, so this is a beta convenience rather than an availability commitment: [OCI Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm).

Before a broad Play Store launch, move the same Compose deployment and validated snapshot to a paid VM. A 1 GB DigitalOcean Basic Droplet is currently listed at USD 6/month with 25 GB SSD; select more disk or RAM when the mailbox/connection metrics justify it: [DigitalOcean Droplet pricing](https://www.digitalocean.com/pricing/droplets). Do not launch broadly on a host that may reclaim free capacity.

The release workflow publishes both `linux/amd64` and `linux/arm64`, so the same image digest works on either host architecture.

## One-time prerequisites

1. Create the VM with Ubuntu LTS or another maintained Docker-supported Linux distribution.
2. Install Docker Engine, the Docker Compose plugin, `curl`, `jq`, and Git from their maintained package sources.
3. Create an unprivileged operator account with narrowly scoped `sudo` and Docker access. Protect SSH with keys; disable password and root login.
4. Clone this repository at the exact release commit into an operator-owned directory.
5. Create a real DNS `A` record for the relay hostname. Add `AAAA` only when the VM has working public IPv6. Set TTL to 300 seconds during initial launch and migrations.
6. At the provider firewall and host firewall, allow TCP 80/443 from the internet and TCP 22 only from the operator's trusted source addresses. Do not allow 8080. Outbound HTTPS and DNS must remain available for image pulls, ACME, and off-host backups.
7. In GitHub, create a protected `relay-release` environment. Keep the GHCR package public for anonymous pulls, or log the host into `ghcr.io` with a read-only package token. Never place a registry token in Compose or the repository.

Verify the host before first deployment:

```sh
docker version
docker compose version
curl --version
jq --version
sudo ss -lntp
```

Port 8080 must not be listening publicly.

## Publish an immutable image

Create an annotated release tag only from a verified commit:

```sh
git status --short
git tag -a relay-v0.1.0 -m "Twinotify relay v0.1.0"
git push origin relay-v0.1.0
```

Alternatively, start the `relay-image-release` workflow manually from the exact commit. The workflow runs relay/deployment verification, publishes AMD64 and ARM64 images to GHCR, attaches BuildKit SBOM and provenance records, adds signed GitHub provenance, and uploads `relay-image.txt`. Copy the full `ghcr.io/...@sha256:...` value from that artifact. A tag alone is not a deployable production input.

## First deployment

Use `deploy/.env.production.example` as a reference for the three required values; do not commit a populated copy. Run the guarded deploy command using the digest and matching version:

```sh
./deploy/deploy-relay.sh \
  --image 'ghcr.io/incmak/twinotify-relay@sha256:REPLACE_WITH_RELEASE_DIGEST' \
  --version 'relay-v0.1.0' \
  --domain 'relay.example.com'
```

The script validates Compose, pulls both pinned images before downtime, safely backs up any database left by a removed container, starts the relay, waits for container readiness, starts Caddy, and checks the public surface. A genuinely empty data volume is accepted only through the backup command's explicit missing-source path; a corrupt or unsafe source still fails closed. The script writes a mode-`0600` audit record to `deploy/.relay-deploy-state`.

After Caddy obtains a public certificate, independently verify:

```sh
./deploy/smoke-relay.sh \
  --base-url 'https://relay.example.com' \
  --expected-version 'relay-v0.1.0'

TWINOTIFY_DOMAIN=relay.example.com \
TWINOTIFY_RELAY_IMAGE='ghcr.io/incmak/twinotify-relay@sha256:REPLACE_WITH_RELEASE_DIGEST' \
TWINOTIFY_BUILD_VERSION=relay-v0.1.0 \
  docker compose --project-name twinotify -f deploy/docker-compose.prod.yml ps
```

The relay must be healthy, Caddy must be the only service publishing ports, `/metrics` must return 404 publicly, and the response must not contain a `Server` header.

## Upgrade and automatic rollback

Run `deploy-relay.sh` with the new digest and version. When an existing relay is present, the script:

1. records its current digest and build version;
2. pulls the candidate and Caddy images before stopping anything;
3. stops the only relay writer;
4. runs the candidate image's offline `relay backup` against `/data` and `/backups`;
5. starts the candidate digest and waits for readiness;
6. runs the external smoke check;
7. restarts the previous digest if backup, readiness, Caddy, or smoke verification fails.

Binary rollback never restores the database. The hardening migrations are additive, and restoring older data would discard pair or mailbox changes accepted after the snapshot.

After a successful upgrade, keep the prior image digest and its pre-deploy snapshot until the next release has completed the observation window.

## Backups and off-host export

The relay creates a consistent snapshot immediately at startup and every six hours, retaining 14 files in the separate `relay-backups` volume. These files are `0600` and contain relationship metadata, timing, and ciphertext. They are security-sensitive even though notification content remains encrypted.

Local snapshots do not survive VM loss. Export them to encrypted storage on a different failure domain after every deployment and at least daily. Docker can copy from the running container without a shell in the distroless image:

```sh
mkdir -p /secure/twinotify-relay-backups
chmod 700 /secure/twinotify-relay-backups
relay_container=$(TWINOTIFY_DOMAIN=relay.example.com \
  TWINOTIFY_RELAY_IMAGE='ghcr.io/incmak/twinotify-relay@sha256:REPLACE_WITH_CURRENT_DIGEST' \
  TWINOTIFY_BUILD_VERSION=relay-v0.1.0 \
  docker compose --project-name twinotify -f deploy/docker-compose.prod.yml ps -q relay)
docker cp "$relay_container:/backups/." /secure/twinotify-relay-backups/
find /secure/twinotify-relay-backups -type f -name '*.db' -exec sha256sum {} \;
```

Encrypt and transfer the exported files with the organization's approved backup tool. Also schedule provider volume backups. Test that an operator other than the original author can retrieve the encrypted copy.

## Restore drill

Run this quarterly and before relying on a new backup destination. Use a maintenance window because restore requires the relay to be stopped.

1. Export the current `/data` and `/backups` volumes and record their hashes.
2. Select a snapshot and copy it to a protected host path, such as `/secure/restore/snapshot.db`.
3. Stop only the relay service.
4. Run the pinned image's guarded restore with the snapshot bind-mounted read-only.
5. Start the relay, wait for readiness, run the public smoke, and perform a real two-phone encrypted delivery test.

```sh
export TWINOTIFY_DOMAIN=relay.example.com
export TWINOTIFY_RELAY_IMAGE='ghcr.io/incmak/twinotify-relay@sha256:REPLACE_WITH_CURRENT_DIGEST'
export TWINOTIFY_BUILD_VERSION=relay-v0.1.0

docker compose --project-name twinotify -f deploy/docker-compose.prod.yml stop relay
docker compose --project-name twinotify -f deploy/docker-compose.prod.yml run --rm --no-deps \
  -v /secure/restore/snapshot.db:/restore/snapshot.db:ro \
  relay restore \
  --from /restore/snapshot.db \
  --to /data/twinotify-relay.db \
  --backup-dir /restore \
  --data-dir /data
docker compose --project-name twinotify -f deploy/docker-compose.prod.yml up -d --no-deps relay
./deploy/smoke-relay.sh --base-url 'https://relay.example.com' --expected-version 'relay-v0.1.0'
```

Restore preserves the previous database as a timestamped recovery copy. Do not delete that copy until the two-phone test passes and the restored instance has completed an observation window.

## Monitoring and incident checks

- Check `/health/live` externally every minute. Alert after two consecutive failures.
- Scrape `/metrics` only from a collector attached to `relay-internal`; never add it to Caddy.
- Alert on readiness false, backup failures, authentication rejection changes, mailbox capacity rejections, connection saturation, disk usage above 80%, and absence of a fresh snapshot for more than eight hours.
- Review `docker compose ... logs --since 1h relay caddy` for bounded operational events. Logs must not contain device IDs, message IDs, JWTs, pair tokens, keys, ciphertext, or notification plaintext.
- Treat repeated `server_capacity` as an incident. Expand disk or move to the paid host; do not lower the reserved free-space threshold simply to resume admission.
- If the VM is lost, provision a clean host, deploy the last verified digest, restore the latest validated off-host snapshot, run smoke plus two-phone delivery, then change DNS.

## Play Store launch and later Cloudflare move

Use the Oracle VM only for the controlled beta. Before broad Play Store availability, restore the latest validated snapshot onto the paid VM, run the complete physical Pixel/Samsung release scenarios, lower DNS TTL, switch DNS, and keep the old VM powered on but not accepting traffic during the rollback window.

Cloudflare is a later backend migration, not part of this launch. Preserve `/pair/*`, `/ws`, `/health/*`, pair/device identifiers, v1/v2 frames, exact encrypted envelope bytes, expiry behavior, and rejection reasons. Run the same protocol fixtures against the Go relay and Durable Objects implementation before moving traffic. A hosting change must not force users to re-pair.
