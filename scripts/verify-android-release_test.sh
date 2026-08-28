#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

[[ -x "$ROOT_DIR/scripts/verify-standalone-android.sh" ]] || {
  echo "standalone Android verifier is missing or not executable" >&2
  exit 1
}
[[ -x "$ROOT_DIR/scripts/verify-android-release-workflow.sh" ]] || {
  echo "Android release workflow verifier is missing or not executable" >&2
  exit 1
}
[[ -x "$ROOT_DIR/scripts/verify-mobile-dependencies.sh" ]] || {
  echo "mobile dependency verifier is missing or not executable" >&2
  exit 1
}
[[ -x "$ROOT_DIR/scripts/verify-mobile-dependencies_test.sh" ]] || {
  echo "mobile dependency verifier self-test is missing or not executable" >&2
  exit 1
}

"$ROOT_DIR/scripts/verify-standalone-android.sh" --self-test
"$ROOT_DIR/scripts/verify-mobile-dependencies_test.sh"
"$ROOT_DIR/scripts/verify-android-release-workflow.sh" --self-test

echo "Android release verifier tests passed"
