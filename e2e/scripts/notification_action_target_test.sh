#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-action-target.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
touch "$tmp/fixture.apk"

expect_failure() {
  if make -s -C "$repo_root" e2e-notification-actions "$@" >"$tmp/out" 2>"$tmp/err"; then
    echo "expected Make target preflight failure" >&2
    exit 1
  fi
}

expect_failure
expect_failure E2E_DEVICE_A=a E2E_DEVICE_B=a E2E_NOTIFICATION_ACTION_EVIDENCE_DIR="$tmp/evidence" E2E_NOTIFICATION_ACTION_FIXTURE_APK="$tmp/fixture.apk"
expect_failure E2E_DEVICE_A=a E2E_DEVICE_B=b E2E_NOTIFICATION_ACTION_FIXTURE_APK="$tmp/fixture.apk"
expect_failure E2E_DEVICE_A=a E2E_DEVICE_B=b E2E_NOTIFICATION_ACTION_EVIDENCE_DIR="$tmp/evidence"

recipe=$(make -n -C "$repo_root" e2e-notification-actions E2E_DEVICE_A=a E2E_DEVICE_B=b E2E_NOTIFICATION_ACTION_EVIDENCE_DIR="$tmp/evidence" E2E_NOTIFICATION_ACTION_FIXTURE_APK="$tmp/fixture.apk")
grep -Fq -- '-scenario notification-actions-correctness' <<<"$recipe"
grep -Fq -- '-fixture-apk' <<<"$recipe"
grep -Fq -- '-scenario-evidence-dir' <<<"$recipe"
for forbidden in 'reply_text' 'pm clear' 'unpair'; do
  ! grep -Fq "$forbidden" <<<"$recipe" || { echo "unsafe target recipe contains $forbidden" >&2; exit 1; }
done

echo "notification action Make target self-test passed"
