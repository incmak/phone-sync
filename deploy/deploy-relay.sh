#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
compose_file="$repo_root/deploy/docker-compose.prod.yml"
project_name=twinotify
image=
version=
domain=
record_file=${TWINOTIFY_DEPLOY_RECORD:-$repo_root/deploy/.relay-deploy-state}
docker_bin=${TWINOTIFY_DOCKER_BIN:-docker}
smoke_bin=${TWINOTIFY_SMOKE_BIN:-$repo_root/deploy/smoke-relay.sh}
health_attempts=${TWINOTIFY_DEPLOY_HEALTH_ATTEMPTS:-30}
health_interval=${TWINOTIFY_DEPLOY_HEALTH_INTERVAL:-2}

previous_image=
previous_version=
existing_container=
relay_was_stopped=false
deployment_finished=false

usage() {
	printf 'usage: %s --image REPOSITORY@sha256:DIGEST --version VERSION --domain HOST [--compose-file PATH] [--project-name NAME] [--record-file PATH]\n' "$0" >&2
	exit 2
}

die() {
	printf 'deploy-relay: %s\n' "$*" >&2
	exit 1
}

while (($# > 0)); do
	case "$1" in
	--image)
		(($# >= 2)) || usage
		image=$2
		shift 2
		;;
	--version)
		(($# >= 2)) || usage
		version=$2
		shift 2
		;;
	--domain)
		(($# >= 2)) || usage
		domain=$2
		shift 2
		;;
	--compose-file)
		(($# >= 2)) || usage
		compose_file=$2
		shift 2
		;;
	--project-name)
		(($# >= 2)) || usage
		project_name=$2
		shift 2
		;;
	--record-file)
		(($# >= 2)) || usage
		record_file=$2
		shift 2
		;;
	*) usage ;;
	esac
done

digest_reference() {
	[[ "$1" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]
}

valid_domain() {
	local candidate=$1 label
	local -a labels
	((${#candidate} <= 253)) || return 1
	IFS=. read -r -a labels <<<"$candidate"
	((${#labels[@]} >= 2)) || return 1
	for label in "${labels[@]}"; do
		[[ "$label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ ]] || return 1
	done
	return 0
}

digest_reference "$image" || { printf 'deploy-relay: image must be pinned by a full sha256 digest\n' >&2; exit 2; }
[[ "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || { printf 'deploy-relay: version is missing or invalid\n' >&2; exit 2; }
valid_domain "$domain" || { printf 'deploy-relay: domain is missing or invalid\n' >&2; exit 2; }
[[ "$project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { printf 'deploy-relay: project name is invalid\n' >&2; exit 2; }
[[ "$health_attempts" =~ ^[1-9][0-9]*$ && "$health_interval" =~ ^[0-9]+$ ]] || { printf 'deploy-relay: health polling configuration is invalid\n' >&2; exit 2; }
[[ -f "$compose_file" && ! -L "$compose_file" ]] || die "Compose file must be a regular non-symlink file: $compose_file"
[[ ! -L "$record_file" && ( ! -e "$record_file" || -f "$record_file" ) ]] || die "deploy record must be a regular non-symlink file"
command -v "$docker_bin" >/dev/null 2>&1 || die "Docker CLI is required"
[[ -x "$smoke_bin" ]] || die "smoke script is not executable: $smoke_bin"

compose_for() {
	local selected_image=$1 selected_version=$2
	shift 2
	TWINOTIFY_DOMAIN="$domain" TWINOTIFY_RELAY_IMAGE="$selected_image" TWINOTIFY_BUILD_VERSION="$selected_version" \
		"$docker_bin" compose --project-name "$project_name" -f "$compose_file" "$@"
}

container_health() {
	local selected_image=$1 selected_version=$2 container=$3
	TWINOTIFY_RELAY_IMAGE="$selected_image" TWINOTIFY_BUILD_VERSION="$selected_version" \
		"$docker_bin" inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null
}

wait_for_relay() {
	local selected_image=$1 selected_version=$2 container state
	for ((attempt = 1; attempt <= health_attempts; attempt++)); do
		container=$(compose_for "$selected_image" "$selected_version" ps -q relay) || return 1
		if [[ -n "$container" ]]; then
			state=$(container_health "$selected_image" "$selected_version" "$container") || state=missing
			if [[ "$state" == healthy ]]; then
				return 0
			fi
			if [[ "$state" == unhealthy ]]; then
				return 1
			fi
		fi
		if ((attempt < health_attempts)); then
			sleep "$health_interval"
		fi
	done
	return 1
}

write_record() {
	local result=$1
	local record_directory temporary
	record_directory=$(dirname -- "$record_file")
	mkdir -p "$record_directory"
	umask 077
	temporary=$(mktemp "$record_directory/.twinotify-deploy-state.XXXXXX")
	{
		printf 'recorded_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
		printf 'result=%s\n' "$result"
		printf 'previous_image=%s\n' "$previous_image"
		printf 'previous_version=%s\n' "$previous_version"
		printf 'candidate_image=%s\n' "$image"
		printf 'candidate_version=%s\n' "$version"
	} >"$temporary"
	mv -f "$temporary" "$record_file"
}

smoke() {
	local selected_version=$1
	"$smoke_bin" --base-url "https://$domain" --expected-version "$selected_version"
}

rollback_previous() {
	if [[ -z "$previous_image" ]]; then
		compose_for "$image" "$version" stop caddy relay >/dev/null 2>&1 || true
		write_record failed_no_previous
		printf 'deploy-relay: candidate failed and no previous digest exists\n' >&2
		return 1
	fi
	printf 'deploy-relay: rolling back relay binary to %s without restoring the database\n' "$previous_image" >&2
	if ! compose_for "$previous_image" "$previous_version" up -d --no-deps relay; then
		write_record rollback_failed
		return 1
	fi
	if ! wait_for_relay "$previous_image" "$previous_version"; then
		compose_for "$previous_image" "$previous_version" logs --no-color relay >&2 || true
		write_record rollback_failed
		return 1
	fi
	if ! compose_for "$previous_image" "$previous_version" up -d --no-deps caddy; then
		write_record rollback_failed
		return 1
	fi
	if ! smoke "$previous_version"; then
		write_record rollback_failed
		return 1
	fi
	write_record rolled_back
	printf 'deploy-relay: rollback healthy at %s\n' "$previous_image" >&2
	return 0
}

handle_signal() {
	trap - HUP INT TERM
	if $relay_was_stopped && ! $deployment_finished; then
		rollback_previous || true
	fi
	exit 130
}
trap handle_signal HUP INT TERM

compose_for "$image" "$version" config >/dev/null || die "production Compose configuration is invalid"
compose_for "$image" "$version" pull relay caddy || die "could not pull the pinned production images"
image_version=$("$docker_bin" image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "$image") || die "could not inspect the pulled relay image"
[[ "$image_version" == "$version" ]] || die "requested version does not match the relay image label"

existing_container=$(compose_for "$image" "$version" ps -q relay)
if [[ -n "$existing_container" ]]; then
	previous_image=$("$docker_bin" inspect --format '{{.Config.Image}}' "$existing_container") || die "could not inspect the current relay image"
	previous_version=$("$docker_bin" inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$existing_container" | sed -n 's/^BUILD_VERSION=//p' | head -n 1)
	digest_reference "$previous_image" || die "current relay image is not digest-pinned; refusing an unguarded replacement"
	[[ -n "$previous_version" ]] || die "current relay BUILD_VERSION could not be determined"
	printf 'deploy-relay: previous image %s\n' "$previous_image"
	write_record preparing
	compose_for "$image" "$version" stop relay
	relay_was_stopped=true
	if ! compose_for "$image" "$version" run --rm --no-deps relay backup \
		--from /data/twinotify-relay.db --to-dir /backups --retention 14; then
		rollback_previous || die "pre-deploy backup failed and rollback also failed"
		die "pre-deploy backup failed; previous digest restored"
	fi
else
	write_record preparing_first_deployment
	if ! compose_for "$image" "$version" run --rm --no-deps relay backup \
		--from /data/twinotify-relay.db --to-dir /backups --retention 14 --allow-missing; then
		die "first deployment found relay state that could not be backed up safely"
	fi
	printf 'deploy-relay: first deployment; surviving database state was backed up when present\n'
fi

if ! compose_for "$image" "$version" up -d --no-deps relay; then
	rollback_previous || die "candidate start and rollback both failed"
	die "candidate start failed; previous digest restored"
fi
relay_was_stopped=true

if ! wait_for_relay "$image" "$version"; then
	compose_for "$image" "$version" logs --no-color relay >&2 || true
	rollback_previous || die "candidate readiness and rollback both failed"
	die "candidate readiness failed; previous digest restored"
fi
if ! compose_for "$image" "$version" up -d --no-deps caddy; then
	rollback_previous || die "Caddy start and rollback both failed"
	die "Caddy start failed; previous digest restored"
fi
if ! smoke "$version"; then
	rollback_previous || die "candidate smoke and rollback both failed"
	die "candidate smoke failed; previous digest restored"
fi

deployment_finished=true
write_record deployed
printf 'deploy-relay: deployed %s\n' "$image"
