#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_HOST_WORKFLOW_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
MOBILE_WORKFLOW="$ROOT_DIR/.github/workflows/mobile.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-host.yml"
MAKEFILE="$ROOT_DIR/Makefile"

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
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    function indentation(value, prefix) {
      prefix = value
      sub(/[^ \t].*$/, "", prefix)
      return length(prefix)
    }
    {
      code = $0
      sub(/[ \t]+#.*/, "", code)
      if (trim(code) == "") next
      indent = indentation(code)
      entry = trim(code)

      if (entry == "permissions:") {
        if (indent != 0 || permission_blocks != 0) invalid = 1
        permission_blocks++
        in_permissions = 1
        permission_indent = indent
        next
      }

      if (in_permissions) {
        if (indent > permission_indent) {
          if (indent != permission_indent + 2 || entry != "contents: read") invalid = 1
          else contents_read++
          next
        }
        in_permissions = 0
      }
    }
    END {
      if (permission_blocks != 1 || contents_read != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$workflow" || {
    echo "workflow permissions must contain exactly one top-level contents: read entry: $workflow" >&2
    exit 1
  }
}

require_approved_run_commands() {
  local workflow=$1
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    function indentation(value, prefix) {
      prefix = value
      sub(/[^ \t].*$/, "", prefix)
      return length(prefix)
    }
    BEGIN {
      approved["cd e2e && go test ./... -race -count=1"] = 1
      approved["cd e2e && go vet ./..."] = 1
      approved["./e2e/scripts/validate-workflow.sh"] = 1
      approved["./e2e/scripts/preflight_test.sh"] = 1
      approved["./scripts/verify-offline-pairing-evidence.sh --self-test"] = 1
      approved["./scripts/verify-release-evidence.sh --self-test"] = 1
      approved["./scripts/verify-host-workflows.sh"] = 1
      approved["./scripts/verify-host-workflows_test.sh"] = 1
    }
    {
      code = $0
      sub(/[ \t]+#.*/, "", code)
      if (trim(code) == "") next
      indent = indentation(code)
      entry = trim(code)

      if (in_run_block) {
        if (indent > run_indent) invalid = 1
        in_run_block = 0
      }

      if (entry ~ /^-[ \t]+run:[ \t]*/ || entry ~ /^run:[ \t]*/) {
        command = entry
        sub(/^(-[ \t]+)?run:[ \t]*/, "", command)
        if (command ~ /^[>|][+-]?$/) {
          in_run_block = 1
          run_indent = indent
        } else if (!(command in approved)) invalid = 1
        else observed[command]++
      }
    }
    END {
      for (command in approved) if (observed[command] != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$workflow" || {
    echo "E2E host workflow must contain only the approved host verification run commands, exactly once each" >&2
    exit 1
  }
}

require_no_secrets() {
  local workflow=$1
  awk '
    {
      code = $0
      sub(/[ \t]+#.*/, "", code)
      if (code ~ /secrets[[:space:]]*\./) exit 1
    }
  ' "$workflow" || {
    echo "E2E host workflow must not reference secrets" >&2
    exit 1
  }
}

require_make_target_command() {
  local makefile=$1 target=$2 command=$3
  awk -v target="$target" -v command="$command" '
    $0 ~ "^" target ":" { in_target = 1; next }
    in_target && $0 ~ /^[^[:space:]#][^:]*:/ { in_target = 0 }
    in_target && index($0, command) > 0 { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$makefile" || {
    echo "Make target $target must run: $command" >&2
    exit 1
  }
}

[[ -f "$MOBILE_WORKFLOW" ]] || { echo "mobile workflow missing: $MOBILE_WORKFLOW" >&2; exit 1; }
[[ -f "$E2E_WORKFLOW" ]] || { echo "E2E host workflow missing: $E2E_WORKFLOW" >&2; exit 1; }
[[ -f "$MAKEFILE" ]] || { echo "Makefile missing: $MAKEFILE" >&2; exit 1; }

require_literal "$MOBILE_WORKFLOW" 'npm test -- --runInBand' 'mobile PR workflow must run all Jest tests'
require_occurrences "$MOBILE_WORKFLOW" "'e2e/**'" 2 'mobile push and PR workflows must cover E2E changes'
require_occurrences "$MOBILE_WORKFLOW" "'scripts/verify-offline-pairing-evidence.sh'" 2 'mobile push and PR workflows must cover offline evidence verifier changes'
require_occurrences "$MOBILE_WORKFLOW" "'scripts/verify-release-evidence.sh'" 2 'mobile push and PR workflows must cover release evidence verifier changes'
require_occurrences "$MOBILE_WORKFLOW" "'.github/workflows/e2e-host.yml'" 2 'mobile push and PR workflows must cover E2E host workflow changes'

require_literal "$E2E_WORKFLOW" 'push:' 'E2E host workflow must trigger on push'
require_literal "$E2E_WORKFLOW" 'pull_request:' 'E2E host workflow must trigger on pull requests'
require_occurrences "$E2E_WORKFLOW" "'.github/workflows/mobile.yml'" 2 'E2E host push and PR workflows must cover mobile workflow changes'
require_read_only_permissions "$E2E_WORKFLOW"
require_pinned_actions "$E2E_WORKFLOW"
for command in \
  'cd e2e && go test ./... -race -count=1' \
  'cd e2e && go vet ./...' \
  './e2e/scripts/validate-workflow.sh' \
  './e2e/scripts/preflight_test.sh' \
  './scripts/verify-offline-pairing-evidence.sh --self-test' \
  './scripts/verify-release-evidence.sh --self-test' \
  './scripts/verify-host-workflows_test.sh'; do
  require_literal "$E2E_WORKFLOW" "$command" "E2E host workflow is missing required command: $command"
done
require_literal "$E2E_WORKFLOW" 'run: ./scripts/verify-host-workflows_test.sh' 'E2E host workflow must run the workflow verifier self-test'
require_make_target_command "$MAKEFILE" 'host-verify' './scripts/verify-host-workflows_test.sh'
require_approved_run_commands "$E2E_WORKFLOW"
require_no_secrets "$E2E_WORKFLOW"

echo 'host workflow validation passed'
