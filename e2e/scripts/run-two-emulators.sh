#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-e2e.XXXXXX")
EMU_A=${E2E_SERIAL_A:-emulator-5554}
EMU_B=${E2E_SERIAL_B:-emulator-5556}
PORT_A=${E2E_PORT_A:-5554}
PORT_B=${E2E_PORT_B:-5556}
PACKAGE_NAME=${E2E_PACKAGE:-com.twinotify.app}
APK=${E2E_APK:-$ROOT_DIR/mobile/android/app/build/outputs/apk/debug/app-debug.apk}
RELAY_BIN=${E2E_RELAY_BIN:-$ROOT_DIR/bin/relay}
RELAY_PORT=${E2E_RELAY_PORT:-18080}
MIN_RAM_BYTES=${E2E_MIN_RAM_BYTES:-8589934592}
EMULATOR_BIN=${E2E_EMULATOR_BIN:-}
ADB_BIN=${E2E_ADB_BIN:-}
NC_BIN=${E2E_NC_BIN:-}
CURL_BIN=${E2E_CURL_BIN:-}
PREPARE_SCRIPT=${E2E_PREPARE_SCRIPT:-$ROOT_DIR/e2e/scripts/prepare-avds.sh}
SCENARIO=${E2E_SCENARIO:-post}
KEEP_RUN_DIR=${E2E_KEEP_RUN_DIR:-0}
RUN_DIR_FILE=${E2E_RUN_DIR_FILE:-}
[[ -n "$RUN_DIR_FILE" ]] && printf '%s\n' "$RUN_DIR" > "$RUN_DIR_FILE"
RELAY_PID=""; EMU_PID_A=""; EMU_PID_B=""; CLEANED=0

fail() { local code=$1; shift; echo "e2e-emulator[$code]: $*" >&2; exit "$code"; }
notification_help_has_post() {
  grep -Eq '(^|[[:space:]])post([[:space:]]|$)' <<<"$1"
}
notification_self_test() {
  local post_only_help=$'usage: cmd notification SUBCOMMAND\n  post [flags] TAG TEXT\n  list\n  snooze'
  local no_post_help=$'usage: cmd notification SUBCOMMAND\n  list\n  snooze'
  notification_help_has_post "$post_only_help" || fail 24 "post-only notification help was rejected"
  notification_help_has_post "$no_post_help" && fail 24 "notification help without post was accepted"
  echo "notification shell capability self-test passed"
}
resolve_tools() {
  [[ -n "$ADB_BIN" ]] || ADB_BIN=$(command -v adb || true)
  [[ -n "$EMULATOR_BIN" ]] || EMULATOR_BIN=$(command -v emulator || true)
}
port_busy() { "$NC_BIN" -z 127.0.0.1 "$1" >/dev/null 2>&1; }
require_ram() {
  local bytes=0
  if command -v sysctl >/dev/null 2>&1; then bytes=$(sysctl -n hw.memsize 2>/dev/null || echo 0)
  elif [[ -r /proc/meminfo ]]; then bytes=$(awk '/MemTotal:/ {print $2 * 1024}' /proc/meminfo)
  fi
  [[ "$bytes" =~ ^[0-9]+$ && "$bytes" -ge "$MIN_RAM_BYTES" ]] || fail 23 "at least 8 GiB RAM is required (detected ${bytes:-unknown} bytes)"
}
check_image() {
  local sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
  [[ -n "$sdk_root" && -d "$sdk_root/system-images/android-35/google_apis/x86_64" ]] || fail 16 "API 35 google_apis x86_64 image is missing; run prepare-avds.sh"
}
preflight() {
  resolve_tools
  [[ -n "$ADB_BIN" && -x "$ADB_BIN" ]] || fail 10 "adb is required"
  [[ -n "$EMULATOR_BIN" && -x "$EMULATOR_BIN" ]] || fail 11 "emulator is required"
  command -v sdkmanager >/dev/null 2>&1 || fail 12 "sdkmanager is required"
  command -v avdmanager >/dev/null 2>&1 || fail 13 "avdmanager is required"
  [[ -n "$NC_BIN" ]] || NC_BIN=$(command -v nc || true)
  [[ -n "$CURL_BIN" ]] || CURL_BIN=$(command -v curl || true)
  [[ -n "$NC_BIN" && -x "$NC_BIN" ]] || fail 14 "nc is required for port checks"
  [[ -n "$CURL_BIN" && -x "$CURL_BIN" ]] || fail 15 "curl is required for relay health checks"
  [[ -x "$RELAY_BIN" ]] || fail 17 "relay binary not found or not executable: $RELAY_BIN"
  [[ -f "$APK" ]] || fail 18 "debug APK not found: $APK"
  [[ "$PORT_A" =~ ^[0-9]+$ && "$PORT_B" =~ ^[0-9]+$ && "$RELAY_PORT" =~ ^[0-9]+$ ]] || fail 19 "ports must be numeric"
  [[ "$PORT_A" -gt 0 && "$PORT_B" -gt 0 && "$RELAY_PORT" -gt 0 && $((PORT_A % 2)) -eq 0 && $((PORT_B % 2)) -eq 0 ]] || fail 19 "emulator console ports must be positive even numbers"
  local adb_port_a=$((PORT_A + 1)) adb_port_b=$((PORT_B + 1))
  [[ "$PORT_A" != "$PORT_B" && "$PORT_A" != "$RELAY_PORT" && "$PORT_B" != "$RELAY_PORT" && "$adb_port_a" != "$PORT_B" && "$adb_port_a" != "$RELAY_PORT" && "$adb_port_b" != "$PORT_A" && "$adb_port_b" != "$RELAY_PORT" ]] || fail 19 "emulator console/ADB and relay ports must be distinct"
  port_busy "$PORT_A" && fail 20 "emulator console port $PORT_A is already in use"
  port_busy "$adb_port_a" && fail 20 "emulator ADB port $adb_port_a is already in use"
  port_busy "$PORT_B" && fail 20 "emulator console port $PORT_B is already in use"
  port_busy "$adb_port_b" && fail 20 "emulator BDB port $adb_port_b is already in use"
  port_busy "$RELAY_PORT" && fail 20 "relay port $RELAY_PORT is already in use"
  require_ram
}
owned_pid() {
  local pid=$1 expected=$2 command_line
  [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  command_line=$(ps -p "$pid" -o command= 2>/dev/null || true)
  [[ "$command_line" == *"$expected"* ]]
}
cleanup() {
  (( CLEANED )) && return
  CLEANED=1; set +e
  owned_pid "$EMU_PID_A" "$EMULATOR_BIN" && kill "$EMU_PID_A" 2>/dev/null
  owned_pid "$EMU_PID_B" "$EMULATOR_BIN" && kill "$EMU_PID_B" 2>/dev/null
  owned_pid "$RELAY_PID" "$RELAY_BIN" && kill "$RELAY_PID" 2>/dev/null
  for pid in "$EMU_PID_A" "$EMU_PID_B" "$RELAY_PID"; do [[ "$pid" =~ ^[0-9]+$ ]] && wait "$pid" 2>/dev/null; done
  [[ "$KEEP_RUN_DIR" == "1" ]] || { [[ -d "$RUN_DIR" && "$RUN_DIR" == *twinotify-e2e.* ]] && rm -rf -- "$RUN_DIR"; }
}
trap cleanup EXIT INT TERM

if [[ "${1:-}" == "--notification-self-test" ]]; then
  notification_self_test
  exit 0
fi
if [[ "${1:-}" == "--preflight" ]]; then preflight; check_image; echo "preflight passed"; exit 0; fi
preflight
resolve_tools
"$PREPARE_SCRIPT"
check_image
if [[ "${E2E_PREPARE_ONLY:-0}" == "1" ]]; then
  echo "e2e-emulator: AVD bootstrap and image verification passed"
  exit 0
fi

"$EMULATOR_BIN" -avd "${E2E_AVD_A:-twinotify-api35-a}" -port "$PORT_A" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data >"$RUN_DIR/emulator-a.log" 2>&1 &
EMU_PID_A=$!
"$EMULATOR_BIN" -avd "${E2E_AVD_B:-twinotify-api35-b}" -port "$PORT_B" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data >"$RUN_DIR/emulator-b.log" 2>&1 &
EMU_PID_B=$!

wait_boot() {
  local serial=$1
  "$ADB_BIN" -s "$serial" wait-for-device
  for _ in $(seq 1 120); do
    [[ "$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && return 0
    sleep 2
  done
  fail 10 "$serial did not reach sys.boot_completed=1; see $RUN_DIR"
}
wait_boot "$EMU_A"
wait_boot "$EMU_B"

for serial in "$EMU_A" "$EMU_B"; do
  "$ADB_BIN" -s "$serial" shell settings put global window_animation_scale 0
  "$ADB_BIN" -s "$serial" shell settings put global transition_animation_scale 0
  "$ADB_BIN" -s "$serial" shell settings put global animator_duration_scale 0
  "$ADB_BIN" -s "$serial" install -r "$APK"
  "$ADB_BIN" -s "$serial" shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS
  "$ADB_BIN" -s "$serial" shell cmd notification allow_listener "$PACKAGE_NAME/co.twinotify.core.listener.TwinotifyNotificationListener"
  # Some OEM/API builds print valid help but return a non-zero shell status;
  # capability is determined from the returned command list, not that status.
  help=$("$ADB_BIN" -s "$serial" shell cmd notification help 2>&1) || true
  notification_help_has_post "$help" || fail 24 "$serial cmd notification lacks post support"
  "$ADB_BIN" -s "$serial" reverse "tcp:$RELAY_PORT" "tcp:$RELAY_PORT" >/dev/null
done

LISTEN_ADDR="127.0.0.1:$RELAY_PORT" BOLT_PATH="$RUN_DIR/relay.db" "$RELAY_BIN" >"$RUN_DIR/relay.log" 2>&1 &
RELAY_PID=$!
for _ in $(seq 1 30); do
  "$CURL_BIN" -fsS "http://127.0.0.1:$RELAY_PORT/health" >/dev/null 2>&1 && break
  sleep 1
done
"$CURL_BIN" -fsS "http://127.0.0.1:$RELAY_PORT/health" >/dev/null 2>&1 || fail 13 "relay did not become healthy; see $RUN_DIR/relay.log"

RELAY_URL="http://127.0.0.1:$RELAY_PORT"
(cd "$ROOT_DIR/e2e" && go run ./cmd/twinotify-e2e -scenario pair -serial-a "$EMU_A" -serial-b "$EMU_B" -package "$PACKAGE_NAME" -relay-url "$RELAY_URL" -timeout "${E2E_TIMEOUT:-60s}")
mkdir -p "$RUN_DIR/sanitized"
if [[ "$SCENARIO" != "pair" ]]; then
  (cd "$ROOT_DIR/e2e" && go run ./cmd/twinotify-e2e -scenario "$SCENARIO" -serial-a "$EMU_A" -serial-b "$EMU_B" -package "$PACKAGE_NAME" -relay-url "$RELAY_URL" -timeout "${E2E_TIMEOUT:-60s}" -scenario-evidence-dir "$RUN_DIR/sanitized")
fi

for serial in "$EMU_A" "$EMU_B"; do
  # `cmd notification` is not a stable cancellation API across Android/OEM
  # builds. The app-level instrumentation owns the real post/cancel assertion;
  # this shell probe only verifies that a device can inject a stimulus.
  run_tag=$(basename "$RUN_DIR" | tr -cd 'A-Za-z0-9')
  tag="twinotify-e2e-smoke-${run_tag}-${serial//[^A-Za-z0-9]/-}"
  "$ADB_BIN" -s "$serial" shell cmd notification post -t "$tag" "$tag" "smoke stimulus"
  "$ADB_BIN" -s "$serial" shell dumpsys notification --noredact | grep -Fq "$tag" || fail 24 "$serial did not show posted smoke notification"
done
status_output=$(cd "$ROOT_DIR/e2e" && go run ./cmd/twinotify-e2e -scenario status -serial-a "$EMU_A" -serial-b "$EMU_B" -package "$PACKAGE_NAME" -timeout "${E2E_TIMEOUT:-60s}")
printf '%s\n' "$status_output" | sed -n 's/^A //p' > "$RUN_DIR/sanitized/health-a.json"
printf '%s\n' "$status_output" | sed -n 's/^B //p' > "$RUN_DIR/sanitized/health-b.json"
echo "e2e-emulator: provisioned pair, executed $SCENARIO, and captured state-derived evidence"
