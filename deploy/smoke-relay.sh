#!/usr/bin/env bash
set -Eeuo pipefail

base_url=
expected_version=
allow_http=false
attempts=${TWINOTIFY_SMOKE_ATTEMPTS:-12}
interval=${TWINOTIFY_SMOKE_INTERVAL:-5}
curl_bin=${TWINOTIFY_CURL_BIN:-curl}
jq_bin=${TWINOTIFY_JQ_BIN:-jq}

usage() {
	printf 'usage: %s --base-url https://relay.example.com [--expected-version VERSION] [--allow-http]\n' "$0" >&2
	exit 2
}

while (($# > 0)); do
	case "$1" in
	--base-url)
		(($# >= 2)) || usage
		base_url=$2
		shift 2
		;;
	--expected-version)
		(($# >= 2)) || usage
		expected_version=$2
		shift 2
		;;
	--allow-http)
		allow_http=true
		shift
		;;
	*) usage ;;
	esac
done

[[ "$attempts" =~ ^[1-9][0-9]*$ ]] || { printf 'smoke-relay: attempts must be positive\n' >&2; exit 2; }
[[ "$interval" =~ ^[0-9]+$ ]] || { printf 'smoke-relay: interval must be a non-negative integer\n' >&2; exit 2; }
[[ -n "$base_url" && "$base_url" != */ ]] || { printf 'smoke-relay: base URL is required and must not end in /\n' >&2; exit 2; }
if [[ "$base_url" == http://* ]]; then
	$allow_http || { printf 'smoke-relay: production smoke requires HTTPS\n' >&2; exit 2; }
elif [[ "$base_url" != https://* ]]; then
	printf 'smoke-relay: base URL must use HTTPS\n' >&2
	exit 2
fi
[[ "$base_url" != *'@'* && "$base_url" != *'?'* && "$base_url" != *'#'* ]] || {
	printf 'smoke-relay: credentials, query strings, and fragments are not allowed in the base URL\n' >&2
	exit 2
}
command -v "$curl_bin" >/dev/null 2>&1 || { printf 'smoke-relay: curl is required\n' >&2; exit 2; }
command -v "$jq_bin" >/dev/null 2>&1 || { printf 'smoke-relay: jq is required\n' >&2; exit 2; }

smoke_root=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-relay-smoke.XXXXXX")
trap 'rm -rf "$smoke_root"' EXIT HUP INT TERM

if [[ "$base_url" == https://* ]]; then
	protocol_args=(--proto '=https' --tlsv1.2)
else
	protocol_args=(--proto '=http')
fi

request_status() {
	local path=$1 body=$2 headers=$3
	"$curl_bin" "${protocol_args[@]}" --silent --show-error --connect-timeout 5 --max-time 10 \
		--dump-header "$headers" --output "$body" --write-out '%{http_code}' "$base_url$path"
}

check_once() {
	local code
	code=$(request_status /health/live "$smoke_root/live.json" "$smoke_root/live.headers") || return 1
	[[ "$code" == 200 ]] || return 1
	# $version is a jq variable supplied with --arg.
	# shellcheck disable=SC2016
	"$jq_bin" -e --arg version "$expected_version" \
		'.status == "live" and (.version | type == "string") and ($version == "" or .version == $version)' \
		"$smoke_root/live.json" >/dev/null || return 1

	code=$(request_status /health/ready "$smoke_root/ready.json" "$smoke_root/ready.headers") || return 1
	[[ "$code" == 200 ]] || return 1
	# $version is a jq variable supplied with --arg.
	# shellcheck disable=SC2016
	"$jq_bin" -e --arg version "$expected_version" \
		'.status == "ready" and (.version | type == "string") and ($version == "" or .version == $version)' \
		"$smoke_root/ready.json" >/dev/null || return 1

	if grep -Eiq '^Server:' "$smoke_root/live.headers" "$smoke_root/ready.headers"; then
		return 1
	fi

	code=$(request_status /metrics "$smoke_root/metrics.body" "$smoke_root/metrics.headers") || return 1
	[[ "$code" == 404 ]] || return 1
	code=$(request_status /not-public "$smoke_root/not-public.body" "$smoke_root/not-public.headers") || return 1
	[[ "$code" == 404 ]] || return 1
	code=$(request_status /ws "$smoke_root/ws.body" "$smoke_root/ws.headers") || return 1
	[[ "$code" == 401 ]] || return 1
	return 0
}

for ((attempt = 1; attempt <= attempts; attempt++)); do
	if check_once; then
		printf 'relay smoke: ok (%s)\n' "$base_url"
		exit 0
	fi
	if ((attempt < attempts)); then
		sleep "$interval"
	fi
done

printf 'relay smoke: failed after %d attempt(s): %s\n' "$attempts" "$base_url" >&2
exit 1
