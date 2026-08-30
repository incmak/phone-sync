#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
deploy_script="$repo_root/deploy/deploy-relay.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-deploy-script-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

fail() {
	printf 'deploy-relay test: %s\n' "$*" >&2
	exit 1
}

candidate=ghcr.io/incmak/twinotify-relay@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
previous=ghcr.io/incmak/twinotify-relay@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

if "$deploy_script" --image ghcr.io/incmak/twinotify-relay:latest --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >"$test_root/tagged.out" 2>"$test_root/tagged.err"; then
	fail "tag-only image was accepted"
fi
grep -Fqi 'digest' "$test_root/tagged.err" || fail "digest rejection diagnostic is missing"

cat >"$test_root/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'image=%s version=%s docker %s\n' "${TWINOTIFY_RELAY_IMAGE:-}" "${TWINOTIFY_BUILD_VERSION:-}" "$*" >> "$FAKE_DOCKER_LOG"

if [[ "$1" == inspect && "$*" == *'.Config.Image'* ]]; then
	printf '%s\n' "$FAKE_PREVIOUS_IMAGE"
	exit 0
fi
if [[ "$1" == inspect && "$*" == *'.Config.Env'* ]]; then
	printf 'BUILD_VERSION=%s\n' "$FAKE_PREVIOUS_VERSION"
	exit 0
fi
if [[ "$1" == inspect && "$*" == *'.State.Health.Status'* ]]; then
	if [[ "${TWINOTIFY_RELAY_IMAGE:-}" == "$FAKE_PREVIOUS_IMAGE" ]]; then
		printf 'healthy\n'
	elif [[ "${FAKE_CANDIDATE_HEALTH:-unhealthy}" == healthy ]]; then
		printf 'healthy\n'
	else
		printf 'unhealthy\n'
	fi
	exit 0
fi
if [[ "$1" == image && "$2" == inspect ]]; then
	printf '%s\n' "$FAKE_CANDIDATE_VERSION"
	exit 0
fi
if [[ "$1" == compose && "$*" == *' ps -q relay'* ]]; then
	if [[ "${FAKE_EXISTING:-true}" == true || -f "$FAKE_RELAY_RUNNING" ]]; then
		printf 'relay-container-id\n'
	fi
	exit 0
fi
if [[ "$1" == compose && "$*" == *' up -d --no-deps relay'* ]]; then
	: >"$FAKE_RELAY_RUNNING"
	exit 0
fi
exit 0
FAKE_DOCKER
chmod +x "$test_root/docker"

cat >"$test_root/smoke" <<'FAKE_SMOKE'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'smoke %s\n' "$*" >> "$FAKE_DOCKER_LOG"
exit 0
FAKE_SMOKE
chmod +x "$test_root/smoke"

export FAKE_DOCKER_LOG="$test_root/docker.log"
export FAKE_PREVIOUS_IMAGE="$previous"
export FAKE_PREVIOUS_VERSION=relay-v1
export FAKE_CANDIDATE_VERSION=relay-v2
export FAKE_RELAY_RUNNING="$test_root/relay-running"
export TWINOTIFY_DOCKER_BIN="$test_root/docker"
export TWINOTIFY_SMOKE_BIN="$test_root/smoke"
export TWINOTIFY_DEPLOY_HEALTH_ATTEMPTS=1
export TWINOTIFY_DEPLOY_HEALTH_INTERVAL=0

export FAKE_CANDIDATE_VERSION=wrong-version
if "$deploy_script" --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >"$test_root/label.out" 2>"$test_root/label.err"; then
	fail "mismatched image version label was accepted"
fi
grep -Fq 'does not match' "$test_root/label.err" || fail "image label mismatch diagnostic is missing"
if grep -Fq ' stop relay' "$FAKE_DOCKER_LOG"; then
	fail "relay stopped before candidate identity validation"
fi

: >"$FAKE_DOCKER_LOG"
export FAKE_CANDIDATE_VERSION=relay-v2
if "$deploy_script" --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >"$test_root/rollback.out" 2>"$test_root/rollback.err"; then
	fail "failed candidate deployment returned success"
fi
grep -Fq "image=$previous version=relay-v1 docker compose" "$FAKE_DOCKER_LOG" || fail "previous digest was not selected for rollback"
grep -Fq ' up -d --no-deps relay' "$FAKE_DOCKER_LOG" || fail "relay was not restarted during rollback"
grep -Fq ' up -d --force-recreate --no-deps caddy' "$FAKE_DOCKER_LOG" || fail "Caddy was not recreated during rollback"
if grep -Fqi 'restore' "$FAKE_DOCKER_LOG"; then
	fail "rollback attempted a database restore"
fi

: >"$FAKE_DOCKER_LOG"
rm -f "$FAKE_RELAY_RUNNING"
export FAKE_EXISTING=false
export FAKE_CANDIDATE_HEALTH=healthy
if ! "$deploy_script" --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >"$test_root/first.out" 2>"$test_root/first.err"; then
	fail "first deployment failed"
fi
grep -Fq ' relay backup --from /data/twinotify-relay.db --to-dir /backups --retention 14 --allow-missing' "$FAKE_DOCKER_LOG" || fail "first deployment did not safely inspect and back up surviving data"
grep -Fq ' up -d --force-recreate --no-deps caddy' "$FAKE_DOCKER_LOG" || fail "Caddy was not recreated to remount the release configuration"

printf 'deploy-relay tests: ok\n'
