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
	[[ "${FAKE_IMAGE_MISSING:-false}" != true ]] || exit 1
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
	if [[ "${TWINOTIFY_RELAY_IMAGE:-}" != "$FAKE_PREVIOUS_IMAGE" ]]; then
		[[ "${FAKE_START_FAIL:-false}" != true ]] || exit 1
		if [[ "${FAKE_SIGNAL_ON_START:-false}" == true ]]; then kill -TERM "$PPID"; fi
	fi
	exit 0
fi
if [[ "$1" == compose && "$*" == *' relay backup '* && "${FAKE_BACKUP_FAIL:-false}" == true ]]; then exit 1; fi
exit 0
FAKE_DOCKER
chmod +x "$test_root/docker"

cat >"$test_root/smoke" <<'FAKE_SMOKE'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'smoke %s\n' "$*" >> "$FAKE_DOCKER_LOG"
if [[ "${FAKE_SMOKE_FAIL:-false}" == true && "$*" == *'relay-v2'* ]]; then exit 1; fi
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
if grep -Fq "image=$previous version=relay-v1 docker compose" "$FAKE_DOCKER_LOG"; then
	fail "previous binary was started after the candidate could have migrated storage"
fi
grep -Fq ' stop caddy relay' "$FAKE_DOCKER_LOG" || fail "failed candidate was not stopped"
grep -Fxq 'result=restore_decision_required' "$test_root/state" || fail "explicit restore decision was not recorded"
grep -Fq 'explicit restore decision' "$test_root/rollback.err" || fail "restore decision diagnostic is missing"
if grep -Fqi 'restore' "$FAKE_DOCKER_LOG"; then
	fail "rollback attempted a database restore"
fi

for failure in start smoke signal; do
	: >"$FAKE_DOCKER_LOG"
	export FAKE_START_FAIL=false FAKE_SMOKE_FAIL=false FAKE_SIGNAL_ON_START=false FAKE_CANDIDATE_HEALTH=healthy
	case "$failure" in
	start) export FAKE_START_FAIL=true ;;
	smoke) export FAKE_SMOKE_FAIL=true ;;
	signal) export FAKE_SIGNAL_ON_START=true ;;
	esac
	if "$deploy_script" --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >"$test_root/$failure.out" 2>"$test_root/$failure.err"; then
		fail "$failure failure returned success"
	fi
	if grep -Fq "image=$previous version=relay-v1 docker compose" "$FAKE_DOCKER_LOG"; then
		fail "$failure failure restarted the previous binary against possibly migrated storage"
	fi
	grep -Fq ' stop caddy relay' "$FAKE_DOCKER_LOG" || fail "$failure failure did not stop the candidate"
	grep -Fxq 'result=restore_decision_required' "$test_root/state" || fail "$failure failure did not retain the restore decision"
done
export FAKE_START_FAIL=false FAKE_SMOKE_FAIL=false FAKE_SIGNAL_ON_START=false

# A read-only backup failure precedes any candidate start, so the untouched
# previous deployment can still be resumed automatically.
: >"$FAKE_DOCKER_LOG"
export FAKE_BACKUP_FAIL=true
if "$deploy_script" --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >"$test_root/backup.out" 2>"$test_root/backup.err"; then
	fail "failed backup returned success"
fi
grep -Fq "image=$previous version=relay-v1 docker compose" "$FAKE_DOCKER_LOG" || fail "pre-start backup failure did not resume the previous binary"
grep -Fxq 'result=rolled_back' "$test_root/state" || fail "pre-start recovery was not recorded"
export FAKE_BACKUP_FAIL=false

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

# A staged immutable image must be present before downtime and must not be pulled
# from a registry; the existing digest and version checks still apply.
: >"$FAKE_DOCKER_LOG"
export FAKE_IMAGE_MISSING=true
if "$deploy_script" --preloaded-image --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >/dev/null 2>&1; then
	fail "missing preloaded image was accepted"
fi
if grep -Fq ' stop relay' "$FAKE_DOCKER_LOG"; then fail "missing image stopped production"; fi
unset FAKE_IMAGE_MISSING
: >"$FAKE_DOCKER_LOG"
export FAKE_CANDIDATE_HEALTH=healthy
unset FAKE_START_FAIL FAKE_SIGNAL_ON_START FAKE_SMOKE_FAIL FAKE_BACKUP_FAIL
"$deploy_script" --preloaded-image --image "$candidate" --version relay-v2 --domain relay.example.test --record-file "$test_root/state" >/dev/null
grep -Fq ' pull caddy' "$FAKE_DOCKER_LOG" || fail "preloaded mode did not pull Caddy"
if grep -Fq ' pull relay' "$FAKE_DOCKER_LOG"; then fail "preloaded image was pulled"; fi
grep -Fxq 'result=deployed' "$test_root/state" || fail "preloaded deployment was not recorded"
