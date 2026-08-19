#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERIFY="$ROOT_DIR/scripts/verify-host-workflows.sh"

[[ -x "$VERIFY" ]] || {
  echo "host workflow verifier is missing or not executable: $VERIFY" >&2
  exit 1
}

"$VERIFY"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-host-workflows.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT

copy_workflows() {
  rm -rf -- "$tmp/.github"
  mkdir -p "$tmp/.github/workflows"
  cp "$ROOT_DIR/.github/workflows/mobile.yml" "$tmp/.github/workflows/mobile.yml"
  cp "$ROOT_DIR/.github/workflows/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
  cp "$ROOT_DIR/Makefile" "$tmp/Makefile"
}

missing_rejections=0
expect_rejection() {
  local label=$1
  if TWINOTIFY_HOST_WORKFLOW_ROOT="$tmp" "$VERIFY" >/dev/null 2>"$tmp/error"; then
    echo "self-test expected rejection: $label" >&2
    missing_rejections=$((missing_rejections + 1))
  fi
}

copy_workflows
sed -i.bak '/npm test -- --runInBand/d' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'missing mobile Jest'

copy_workflows
sed -i.bak 's/e2e\/\*\*/not-e2e\/\*\*/g' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'mobile paths omit E2E changes'

copy_workflows
sed -i.bak 's#\.github/workflows/mobile\.yml#.github/workflows/not-mobile.yml#g' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'E2E host paths omit mobile workflow changes'

copy_workflows
sed -i.bak 's/-race -count=1/-short/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing E2E race test'

copy_workflows
sed -i.bak 's/go vet \.\/\.\./go vet .\/internal/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing E2E vet'

copy_workflows
sed -i.bak 's/verify-release-evidence\.sh --self-test/verify-release-evidence.sh --not-a-self-test/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing release evidence self-test'

copy_workflows
sed -i.bak 's/contents: read/contents: write/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'write permission'

copy_workflows
awk '/contents: read/ { print; print "  actions: write"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'additional root permission'

copy_workflows
awk '/contents: read/ { print; print "  id-token: write"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'id-token write permission'

copy_workflows
awk '/contents: read/ { print; print "  packages: write"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'packages write permission'

copy_workflows
printf '\n  rogue-job:\n    permissions:\n      contents: read\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'nested permission block'

copy_workflows
sed -i.bak 's#actions/checkout@[0-9a-f]\{40\}#actions/checkout@v4#' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'unpinned action'

copy_workflows
printf '\n      - run: adb devices\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'device command'

copy_workflows
printf '\n      - run: |\n          adb devices\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'multiline device command'

copy_workflows
printf '\n      - run: |\n          printf "$(adb)"\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'subshell device command'

copy_workflows
printf "\n      - run: |\n          \$('a'db) devices\n" >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'quoted-shell device command'

copy_workflows
printf '\n      - run: printf safe\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'additional benign-looking run command'

expect_acceptance() {
  local label=$1
  if ! TWINOTIFY_HOST_WORKFLOW_ROOT="$tmp" "$VERIFY" >/dev/null 2>"$tmp/error"; then
    echo "self-test expected acceptance: $label" >&2
    cat "$tmp/error" >&2
    exit 1
  fi
}

copy_workflows
sed -i.bak 's/Run E2E Go race tests/ADB wording is not a command/' "$tmp/.github/workflows/e2e-host.yml"
printf '\n      # adb devices is intentionally mentioned in a comment\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_acceptance 'comments and labels may mention prohibited tools'

copy_workflows
sed -i.bak '/verify-host-workflows_test\.sh/d' "$tmp/Makefile"
expect_rejection 'host Make target omits verifier self-test'

copy_workflows
sed -i.bak '/verify-host-workflows_test\.sh/d' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'E2E host workflow omits verifier self-test'

[[ "$missing_rejections" -eq 0 ]] || exit 1

echo "host workflow verifier self-test passed"
