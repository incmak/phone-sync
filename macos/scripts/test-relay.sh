#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
make -C "$ROOT" relay-build
python3 "$ROOT/macos/scripts/sync-schemas.py"
TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-mac-relay.XXXXXX")
RELAY_PID=""
cleanup() {
  if [[ -n $RELAY_PID ]]; then kill "$RELAY_PID" 2>/dev/null || true; wait "$RELAY_PID" || true; fi
  rm -rf -- "$TEST_DIR"
}
trap cleanup EXIT
PORT=$(python3 - <<'PY'
import socket
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    print(listener.getsockname()[1])
PY
)
LISTEN_ADDR="127.0.0.1:$PORT" BOLT_PATH="$TEST_DIR/relay.db" "$ROOT/bin/relay" >"$TEST_DIR/relay.log" 2>&1 &
RELAY_PID=$!
for attempt in {1..50}; do
  if curl -sf "http://127.0.0.1:$PORT/health" >/dev/null; then break; fi
  kill -0 "$RELAY_PID" || { cat "$TEST_DIR/relay.log"; exit 1; }
  sleep 0.1
done
curl -sf "http://127.0.0.1:$PORT/health" >/dev/null
TWINOTIFY_TEST_RELAY="http://127.0.0.1:$PORT" swift test --package-path "$ROOT/macos" --disable-automatic-resolution --filter livePairingExactBytesAndTwoHeartbeats
