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
}

expect_rejection() {
  local label=$1
  if TWINOTIFY_HOST_WORKFLOW_ROOT="$tmp" "$VERIFY" >/dev/null 2>"$tmp/error"; then
    echo "self-test expected rejection: $label" >&2
    exit 1
  fi
}

copy_workflows
sed -i.bak '/npm test -- --runInBand/d' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'missing mobile Jest'

copy_workflows
sed -i.bak 's/e2e\/\*\*/not-e2e\/\*\*/g' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'mobile paths omit E2E changes'

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
sed -i.bak 's#actions/checkout@[0-9a-f]\{40\}#actions/checkout@v4#' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'unpinned action'

copy_workflows
printf '\n      - run: adb devices\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'device command'

echo "host workflow verifier self-test passed"
