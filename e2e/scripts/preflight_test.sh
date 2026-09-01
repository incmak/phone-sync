#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-preflight-test.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
set +e
E2E_ADB_BIN="$tmp/missing-adb" E2E_EMULATOR_BIN="$tmp/missing-emulator" /bin/bash "$SCRIPT_DIR/run-two-emulators.sh" --preflight >/dev/null 2>"$tmp/error"
status=$?
set -e
[[ "$status" -eq 10 ]] || { echo "expected missing-tool exit 10, got $status" >&2; cat "$tmp/error" >&2; exit 1; }
grep -Fq "adb is required" "$tmp/error" || { echo "missing actionable adb diagnostic" >&2; exit 1; }

set +e
/bin/bash "$SCRIPT_DIR/run-two-emulators.sh" --notification-self-test >/dev/null 2>"$tmp/notification-error"
status=$?
set -e
[[ "$status" -eq 0 ]] || { echo "notification shell capability self-test failed with $status" >&2; cat "$tmp/notification-error" >&2; exit 1; }

grep -Fq '"$ADB_BIN" -s "$serial" reverse "tcp:$RELAY_PORT" "tcp:$RELAY_PORT"' "$SCRIPT_DIR/run-two-emulators.sh" || {
  echo "emulator harness must reverse the loopback relay port on every target" >&2
  exit 1
}
grep -Fq 'RELAY_URL="http://127.0.0.1:$RELAY_PORT"' "$SCRIPT_DIR/run-two-emulators.sh" || {
  echo "emulator harness must give the app a policy-compliant loopback relay URL" >&2
  exit 1
}
if grep -Fq 'RELAY_URL="http://10.0.2.2:' "$SCRIPT_DIR/run-two-emulators.sh"; then
  echo "emulator harness must not bypass the debug loopback-only relay policy" >&2
  exit 1
fi

fakebin="$tmp/bin"
mkdir -p "$fakebin"
for tool in adb emulator sdkmanager avdmanager nc curl; do
  printf '#!/usr/bin/env bash\nexit 1\n' > "$fakebin/$tool"
  chmod +x "$fakebin/$tool"
done
sdk="$tmp/sdk/system-images/android-35/google_apis/x86_64"
mkdir -p "$sdk"
touch "$tmp/app.apk" "$tmp/relay"
chmod +x "$tmp/relay"
for expected in 11 12 13 14 15; do
  case "$expected" in
    11) missing=emulator;; 12) missing=sdkmanager;; 13) missing=avdmanager;; 14) missing=nc;; 15) missing=curl;;
  esac
  mv "$fakebin/$missing" "$fakebin/$missing.off"
  set +e
  PATH="$fakebin:/usr/bin:/bin" ANDROID_SDK_ROOT="$tmp/sdk" E2E_MIN_RAM_BYTES=0 E2E_APK="$tmp/app.apk" E2E_RELAY_BIN="$tmp/relay" E2E_ADB_BIN="$fakebin/adb" E2E_EMULATOR_BIN="$fakebin/emulator" E2E_NC_BIN="$fakebin/nc" E2E_CURL_BIN="$fakebin/curl" /bin/bash "$SCRIPT_DIR/run-two-emulators.sh" --preflight >/dev/null 2>"$tmp/error"
  status=$?
  set -e
  [[ "$status" -eq "$expected" ]] || { echo "expected $missing exit $expected, got $status" >&2; cat "$tmp/error" >&2; exit 1; }
  mv "$fakebin/$missing.off" "$fakebin/$missing"
done

set +e
PATH="$fakebin:/usr/bin:/bin" E2E_ADB_BIN="$fakebin/adb" E2E_EMULATOR_BIN="$fakebin/emulator" E2E_NC_BIN="$fakebin/nc" E2E_CURL_BIN="$fakebin/curl" ANDROID_SDK_ROOT="$tmp/sdk" E2E_MIN_RAM_BYTES=0 E2E_APK="$tmp/app.apk" E2E_RELAY_BIN="$tmp/relay" E2E_PORT_A=5554 E2E_PORT_B=5554 /bin/bash "$SCRIPT_DIR/run-two-emulators.sh" --preflight >/dev/null 2>"$tmp/error"
status=$?
set -e
[[ "$status" -eq 19 ]] || { echo "expected duplicate-port exit 19, got $status" >&2; exit 1; }
set +e
PATH="$fakebin:/usr/bin:/bin" E2E_ADB_BIN="$fakebin/adb" E2E_EMULATOR_BIN="$fakebin/emulator" E2E_NC_BIN="$fakebin/nc" E2E_CURL_BIN="$fakebin/curl" ANDROID_SDK_ROOT="$tmp/sdk" E2E_MIN_RAM_BYTES=0 E2E_APK="$tmp/app.apk" E2E_RELAY_BIN="$tmp/relay" E2E_PORT_A=5555 E2E_PORT_B=5556 E2E_RELAY_PORT=18080 /bin/bash "$SCRIPT_DIR/run-two-emulators.sh" --preflight >/dev/null 2>"$tmp/error"
status=$?
set -e
[[ "$status" -eq 19 ]] || { echo "expected odd-console-port exit 19, got $status" >&2; exit 1; }
set +e
PATH="$fakebin:/usr/bin:/bin" E2E_ADB_BIN="$fakebin/adb" E2E_EMULATOR_BIN="$fakebin/emulator" E2E_NC_BIN="$fakebin/nc" E2E_CURL_BIN="$fakebin/curl" ANDROID_SDK_ROOT="$tmp/sdk" E2E_MIN_RAM_BYTES=0 E2E_APK="$tmp/app.apk" E2E_RELAY_BIN="$tmp/relay" E2E_PORT_A=5554 E2E_PORT_B=5556 E2E_RELAY_PORT=5555 /bin/bash "$SCRIPT_DIR/run-two-emulators.sh" --preflight >/dev/null 2>"$tmp/error"
status=$?
set -e
[[ "$status" -eq 19 ]] || { echo "expected console-adb/relay collision exit 19, got $status" >&2; exit 1; }

clean_sdk="$tmp/clean-sdk"
mkdir -p "$clean_sdk"
prepare="$tmp/prepare.sh"
cat > "$prepare" <<'EOF'
#!/usr/bin/env bash
mkdir -p "$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64"
EOF
chmod +x "$prepare"
PATH="$fakebin:/usr/bin:/bin" ANDROID_SDK_ROOT="$clean_sdk" E2E_MIN_RAM_BYTES=0 E2E_APK="$tmp/app.apk" E2E_RELAY_BIN="$tmp/relay" E2E_ADB_BIN="$fakebin/adb" E2E_EMULATOR_BIN="$fakebin/emulator" E2E_NC_BIN="$fakebin/nc" E2E_CURL_BIN="$fakebin/curl" E2E_PREPARE_SCRIPT="$prepare" E2E_PREPARE_ONLY=1 /bin/bash "$SCRIPT_DIR/run-two-emulators.sh" >/dev/null
[[ -d "$clean_sdk/system-images/android-35/google_apis/x86_64" ]] || { echo "clean-host bootstrap did not run" >&2; exit 1; }
echo "preflight_test: missing-tool classification passed"
