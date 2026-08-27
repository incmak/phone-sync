#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-lan-product-target.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT

expect_make_failure() {
  local name=$1
  shift
  if make -s -C "$repo_root" e2e-lan-product "$@" >"$tmp/$name.out" 2>"$tmp/$name.err"; then
    echo "expected $name failure" >&2
    exit 1
  fi
}

expect_make_failure missing-serials
expect_make_failure equal-serials E2E_DEVICE_A=phone-a E2E_DEVICE_B=phone-a E2E_LAN_PRODUCT_EVIDENCE_DIR="$tmp/evidence"
expect_make_failure missing-evidence E2E_DEVICE_A=phone-a E2E_DEVICE_B=phone-b

recipe=$(make -n -C "$repo_root" e2e-lan-product E2E_DEVICE_A=phone-a E2E_DEVICE_B=phone-b E2E_LAN_PRODUCT_EVIDENCE_DIR="$tmp/evidence" 2>/dev/null || true)
for forbidden in 'svc wifi' 'svc data' 'cmd wifi' 'settings put' 'pm clear' 'rm -rf' 'network.off' 'network.on'; do
  if grep -Fq "$forbidden" <<<"$recipe"; then
    echo "unsafe make recipe contains $forbidden" >&2
    exit 1
  fi
done
grep -Fq -- '-scenario lan-product-correctness' <<<"$recipe" || { echo "aggregate CLI invocation missing" >&2; exit 1; }
grep -Fq 'verify-lan-product-evidence.sh' <<<"$recipe" || { echo "evidence verifier invocation missing" >&2; exit 1; }

"$repo_root/scripts/verify-lan-product-evidence.sh" --self-test
echo "lan product Make target self-test passed"
