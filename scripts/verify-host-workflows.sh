#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_HOST_WORKFLOW_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
MOBILE_WORKFLOW="$ROOT_DIR/.github/workflows/mobile.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-host.yml"

require_literal() {
  local file=$1 text=$2 message=$3
  grep -Fq "$text" "$file" || {
    echo "$message" >&2
    exit 1
  }
}

require_occurrences() {
  local file=$1 text=$2 expected=$3 message=$4 count
  count=$(grep -Foc "$text" "$file" || true)
  [[ "$count" -eq "$expected" ]] || {
    echo "$message" >&2
    exit 1
  }
}

require_pinned_actions() {
  local workflow=$1
  local action_count pinned_count
  action_count=$(grep -Ec '^[[:space:]]*- uses: ' "$workflow" || true)
  pinned_count=$(grep -Ec '^[[:space:]]*- uses: [^[:space:]]+@[0-9a-f]{40}([[:space:]]|$)' "$workflow" || true)
  [[ "$action_count" -gt 0 && "$action_count" -eq "$pinned_count" ]] || {
    echo "workflow actions must use full commit SHAs: $workflow" >&2
    exit 1
  }
}

require_read_only_permissions() {
  local workflow=$1
  require_literal "$workflow" 'permissions:' "workflow permissions missing: $workflow"
  require_literal "$workflow" 'contents: read' "workflow must use read-only contents permission: $workflow"
  if grep -Eq 'contents:[[:space:]]*write|write-all' "$workflow"; then
    echo "workflow must not request write permissions: $workflow" >&2
    exit 1
  fi
}

[[ -f "$MOBILE_WORKFLOW" ]] || { echo "mobile workflow missing: $MOBILE_WORKFLOW" >&2; exit 1; }
[[ -f "$E2E_WORKFLOW" ]] || { echo "E2E host workflow missing: $E2E_WORKFLOW" >&2; exit 1; }

require_literal "$MOBILE_WORKFLOW" 'npm test -- --runInBand' 'mobile PR workflow must run all Jest tests'
require_occurrences "$MOBILE_WORKFLOW" "'e2e/**'" 2 'mobile push and PR workflows must cover E2E changes'
require_occurrences "$MOBILE_WORKFLOW" "'scripts/verify-offline-pairing-evidence.sh'" 2 'mobile push and PR workflows must cover offline evidence verifier changes'
require_occurrences "$MOBILE_WORKFLOW" "'scripts/verify-release-evidence.sh'" 2 'mobile push and PR workflows must cover release evidence verifier changes'
require_occurrences "$MOBILE_WORKFLOW" "'.github/workflows/e2e-host.yml'" 2 'mobile push and PR workflows must cover E2E host workflow changes'

require_literal "$E2E_WORKFLOW" 'push:' 'E2E host workflow must trigger on push'
require_literal "$E2E_WORKFLOW" 'pull_request:' 'E2E host workflow must trigger on pull requests'
require_read_only_permissions "$E2E_WORKFLOW"
require_pinned_actions "$E2E_WORKFLOW"
for command in \
  'cd e2e && go test ./... -race -count=1' \
  'cd e2e && go vet ./...' \
  './e2e/scripts/validate-workflow.sh' \
  './e2e/scripts/preflight_test.sh' \
  './scripts/verify-offline-pairing-evidence.sh --self-test' \
  './scripts/verify-release-evidence.sh --self-test'; do
  require_literal "$E2E_WORKFLOW" "$command" "E2E host workflow is missing required command: $command"
done
if grep -Eq '^[[:space:]]*-[[:space:]]+run:[[:space:]].*(adb|emulator|sdkmanager|docker)([[:space:]]|$)|^[[:space:]]+run:[[:space:]].*(adb|emulator|sdkmanager|docker)([[:space:]]|$)' "$E2E_WORKFLOW" || grep -Eqi 'secrets\.' "$E2E_WORKFLOW"; then
  echo "E2E host workflow must not require devices, Docker, or secrets" >&2
  exit 1
fi

echo 'host workflow validation passed'
