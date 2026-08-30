#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
smoke_script="$repo_root/deploy/smoke-relay.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-smoke-script-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

fail() {
	printf 'smoke-relay test: %s\n' "$*" >&2
	exit 1
}

if "$smoke_script" --base-url http://relay.example.test >"$test_root/http.out" 2>"$test_root/http.err"; then
	fail "HTTP was accepted without the explicit local-test switch"
fi
grep -Fq 'requires HTTPS' "$test_root/http.err" || fail "HTTP rejection diagnostic is missing"

cat >"$test_root/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -Eeuo pipefail
body=
headers=
url=
resolve=
http1=false
request_headers=
while (($# > 0)); do
	case "$1" in
	--output | --dump-header | --write-out | --connect-timeout | --max-time | --proto | --resolve | --header)
		case "$1" in
		--output) body=$2 ;;
		--dump-header) headers=$2 ;;
		--resolve) resolve=$2 ;;
		--header) request_headers="$request_headers|$2" ;;
		esac
		shift 2
		;;
	--silent | --show-error | --tlsv1.2) shift ;;
	--http1.1) http1=true; shift ;;
	*) url=$1; shift ;;
	esac
done
[[ -n "$body" && -n "$headers" && -n "$url" ]]
if [[ -n "${FAKE_EXPECT_RESOLVE:-}" && "$resolve" != "$FAKE_EXPECT_RESOLVE" ]]; then
	exit 1
fi
if [[ "${FAKE_REQUIRE_HTTP1:-false}" == true && "$http1" != true ]]; then
	exit 1
fi
status=404
payload='not found'
upgrade_header=false
case "$url" in
*/health/live)
	status=200
	payload='{"status":"live","version":"relay-v-test"}'
	;;
*/health/ready)
	status=200
	payload='{"status":"ready","version":"relay-v-test"}'
	;;
*/metrics) status=${FAKE_METRICS_STATUS:-404} ;;
*/not-public) status=404 ;;
*/ws)
	if [[ "$request_headers" == *'|Upgrade: websocket'* ]]; then
		status=401
		payload='invalid token'
		if [[ -n "${FAKE_WS_UPGRADE_MARKER:-}" ]]; then
			: >"$FAKE_WS_UPGRADE_MARKER"
		fi
	else
		status=426
		upgrade_header=true
	fi
	;;
esac
printf 'HTTP/1.1 %s fixture\r\n' "$status" >"$headers"
if [[ "$upgrade_header" == true ]]; then
	printf 'Upgrade: websocket\r\n' >>"$headers"
fi
if [[ "${FAKE_SERVER_HEADER:-false}" == true ]]; then
	printf 'Server: fixture\r\n' >>"$headers"
fi
if [[ "${FAKE_SECURITY_HEADERS:-true}" == true ]]; then
	printf 'Strict-Transport-Security: max-age=31536000; includeSubDomains\r\n' >>"$headers"
	printf 'X-Content-Type-Options: nosniff\r\n' >>"$headers"
	printf 'Referrer-Policy: no-referrer\r\n' >>"$headers"
	printf "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n" >>"$headers"
	printf 'Cache-Control: no-store\r\n' >>"$headers"
fi
printf '\r\n' >>"$headers"
printf '%s\n' "$payload" >"$body"
printf '%s' "$status"
FAKE_CURL
chmod +x "$test_root/curl"

export TWINOTIFY_CURL_BIN="$test_root/curl"
export TWINOTIFY_SMOKE_ATTEMPTS=1
export TWINOTIFY_SMOKE_INTERVAL=0

"$smoke_script" --allow-http --base-url http://relay.example.test --expected-version relay-v-test >/dev/null || fail "valid public surface failed"
FAKE_EXPECT_RESOLVE=relay.example.test:443:127.0.0.1 FAKE_REQUIRE_HTTP1=true \
	FAKE_WS_UPGRADE_MARKER="$test_root/ws-upgrade" \
	TWINOTIFY_SMOKE_RESOLVE_IP=127.0.0.1 \
	"$smoke_script" --base-url https://relay.example.test --expected-version relay-v-test >/dev/null || \
	fail "HTTPS smoke did not resolve the production hostname through the requested local address"
[[ -f "$test_root/ws-upgrade" ]] || fail "HTTPS smoke did not exercise the WebSocket upgrade path"
if TWINOTIFY_SMOKE_RESOLVE_IP=999.1.1.1 \
	"$smoke_script" --base-url https://relay.example.test --expected-version relay-v-test \
	>"$test_root/resolve.out" 2>"$test_root/resolve.err"; then
	fail "invalid IPv4 resolve override was accepted"
fi

if FAKE_METRICS_STATUS=200 "$smoke_script" --allow-http --base-url http://relay.example.test --expected-version relay-v-test >"$test_root/metrics.out" 2>"$test_root/metrics.err"; then
	fail "public metrics endpoint was accepted"
fi
if FAKE_SERVER_HEADER=true "$smoke_script" --allow-http --base-url http://relay.example.test --expected-version relay-v-test >"$test_root/server.out" 2>"$test_root/server.err"; then
	fail "server-identifying response header was accepted"
fi
if FAKE_SECURITY_HEADERS=false "$smoke_script" --base-url https://relay.example.test --expected-version relay-v-test >"$test_root/headers.out" 2>"$test_root/headers.err"; then
	fail "missing production security headers were accepted"
fi
if "$smoke_script" --allow-http --base-url http://relay.example.test --expected-version wrong-version >"$test_root/version.out" 2>"$test_root/version.err"; then
	fail "wrong build version was accepted"
fi

printf 'smoke-relay tests: ok\n'
