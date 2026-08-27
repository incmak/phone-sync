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

doc_dir="$tmp/docs"
mkdir -p "$doc_dir"
head_commit=$(git -C "$repo_root" rev-parse --short HEAD)
write_doc_fixture() {
  printf '%s\n' \
    '# Direct LAN plan' \
    "- [x] Implementation complete (commit \`$head_commit\`)." \
    '- [ ] Physical acceptance - pending physical two-phone run.' >"$doc_dir/plan-a.md"
  printf '%s\n' \
    '# Live service plan' \
    "- [x] Implementation complete (commit \`$head_commit\`)." \
    '- [ ] Operator handset acceptance - pending physical two-phone run.' >"$doc_dir/plan-b.md"
  printf '%s\n' \
    '# Direct LAN scenarios' \
    '- Run `make e2e-lan-product` through `e2e/cmd/twinotify-e2e`.' \
    '- Verify with `scripts/verify-lan-product-evidence.sh`.' \
    '- `lan-direct-delivery`' \
    '- `lan-direct-dismiss`' \
    '- `lan-direct-update`' \
    '- `lan-direct-peer-dismiss`' \
    '- `lan-direct-call-state`' \
    '- `lan-direct-snapshot-receipt`' \
    '- `lan-direct-burst-backpressure`' \
    '- `lan-direct-unpair-during-traffic`' \
    '- `lan-product-correctness`' \
    '- [ ] Physical aggregate - pending physical two-phone run.' >"$doc_dir/scenarios.md"
}

expect_doc_failure() {
  local name=$1
  if "$repo_root/scripts/verify-lan-product-evidence.sh" --check-doc-status \
    "$doc_dir/plan-a.md" "$doc_dir/plan-b.md" "$doc_dir/scenarios.md" \
    >"$tmp/$name.out" 2>"$tmp/$name.err"; then
    echo "expected $name documentation failure" >&2
    exit 1
  fi
}

write_doc_fixture
"$repo_root/scripts/verify-lan-product-evidence.sh" --check-doc-status \
  "$doc_dir/plan-a.md" "$doc_dir/plan-b.md" "$doc_dir/scenarios.md"
write_doc_fixture
printf '%s\n' "Unpair doesn't notify peer." >>"$doc_dir/scenarios.md"
expect_doc_failure stale-claim
write_doc_fixture
sed -i.bak 's/- \[ \] Physical/- [x] Physical/' "$doc_dir/scenarios.md"
rm "$doc_dir/scenarios.md.bak"
expect_doc_failure checked-physical
write_doc_fixture
sed -i.bak 's/pending physical two-phone run/pending operator run/' "$doc_dir/plan-b.md"
rm "$doc_dir/plan-b.md.bak"
expect_doc_failure missing-pending-physical
write_doc_fixture
printf '%s\n' '- Run `scripts/does-not-exist.sh`.' >>"$doc_dir/scenarios.md"
expect_doc_failure nonexistent-path
write_doc_fixture
printf '%s\n' '- Read [missing instructions](does-not-exist.md).' >>"$doc_dir/scenarios.md"
expect_doc_failure nonexistent-link
write_doc_fixture
printf '%s\n' '- Run `make does-not-exist`.' >>"$doc_dir/scenarios.md"
expect_doc_failure nonexistent-command
write_doc_fixture
sed -i.bak 's/commit `[^`]*`/commit `deadbee`/' "$doc_dir/plan-a.md"
rm "$doc_dir/plan-a.md.bak"
expect_doc_failure unreachable-commit
write_doc_fixture
sed -i.bak '/^- \[x\]/d' "$doc_dir/plan-a.md"
rm "$doc_dir/plan-a.md.bak"
sed -i.bak '/^- \[x\]/d' "$doc_dir/plan-b.md"
rm "$doc_dir/plan-b.md.bak"
expect_doc_failure missing-implementation-status
write_doc_fixture
sed -i.bak 's/lan-direct-update/lan-direct-unknown/' "$doc_dir/scenarios.md"
rm "$doc_dir/scenarios.md.bak"
expect_doc_failure unknown-scenario

echo "lan product Make target self-test passed"
