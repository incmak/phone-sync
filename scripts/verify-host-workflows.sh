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

require_mobile_typecheck_runs() {
  local workflow=$1
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    BEGIN {
      expected[1] = "npm ci"
      expected[2] = "npx tsc --noEmit"
      expected[3] = "npm test -- --runInBand"
      expected[4] = "npx expo-doctor"
    }
    {
      code = $0
      sub(/[ \t]+#.*/, "", code)
      if (trim(code) == "") next

      if (code ~ /^  typecheck:[ \t]*$/) {
        typecheck_jobs++
        in_typecheck = 1
        next
      }
      if (in_typecheck && code ~ /^  [^ \t#][^:]*:/) {
        in_typecheck = 0
        next
      }
      if (in_typecheck) {
        entry = trim(code)
        if (entry ~ /^- run:[ \t]*/) {
          command = entry
          sub(/^- run:[ \t]*/, "", command)
          observed++
          if (observed > 4 || command != expected[observed]) invalid = 1
        }
      }
    }
    END {
      if (typecheck_jobs != 1 || observed != 4) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$workflow" || {
    echo "mobile typecheck job must run npm ci, typecheck, Jest, and Expo Doctor exactly once and in order" >&2
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

require_unconditional_pull_request() {
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
      if (entry == "on:" && indent == 0) {
        in_on = 1
        next
      }
      if (in_on && indent == 0) in_on = 0
      if (!in_on) next
      if (entry == "pull_request:" && indent == 2) {
        pull_requests++
        in_pull_request = 1
        next
      }
      if (in_pull_request) {
        if (indent > 2) invalid = 1
        else in_pull_request = 0
      }
    }
    END {
      if (pull_requests != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$workflow" || {
    echo "E2E host pull_request trigger must be unconditional with no paths or paths-ignore" >&2
    exit 1
  }
}

require_push_path() {
  local workflow=$1 expected=$2
  awk -v expected="$expected" '
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
      if (entry == "on:" && indent == 0) {
        in_on = 1
        next
      }
      if (in_on && indent == 0) in_on = 0
      if (!in_on) next
      if (entry == "push:" && indent == 2) {
        in_push = 1
        in_paths = 0
        next
      }
      if (in_push && indent == 2) {
        in_push = 0
        in_paths = 0
      }
      if (in_push && entry == "paths:" && indent == 4) {
        in_paths = 1
        next
      }
      if (in_paths && indent == 6 && entry ~ /^- /) {
        value = entry
        sub(/^- /, "", value)
        if (value ~ /^'\''.*'\''$/) {
          sub(/^'\''/, "", value)
          sub(/'\''$/, "", value)
        }
        if (value == expected) found++
        next
      }
      if (in_paths && indent <= 4) in_paths = 0
    }
    END { exit found == 1 ? 0 : 1 }
  ' "$workflow" || {
    echo "E2E host push paths must contain exactly once: $expected" >&2
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
      approved["./scripts/verify-android-release_test.sh"] = 1
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

require_host_verify_recipe() {
  local makefile=$1
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    BEGIN {
      expected[1] = "cd mobile && npm ci"
      expected[2] = "cd mobile && npm run typecheck"
      expected[3] = "cd mobile && npm test -- --runInBand"
      expected[4] = "cd e2e && go test ./... -race -count=1"
      expected[5] = "cd e2e && go vet ./..."
      expected[6] = "./e2e/scripts/validate-workflow.sh"
      expected[7] = "./e2e/scripts/preflight_test.sh"
      expected[8] = "./scripts/verify-offline-pairing-evidence.sh --self-test"
      expected[9] = "./scripts/verify-release-evidence.sh --self-test"
      expected[10] = "./scripts/verify-android-release_test.sh"
      expected[11] = "./scripts/verify-host-workflows.sh"
      expected[12] = "./scripts/verify-host-workflows_test.sh"
      expected[13] = "./scripts/verify-generated-clean.sh"
    }
    $0 ~ /^host-verify:[ \t]*proto-test[ \t]*$/ {
      target_count++
      in_target = 1
      next
    }
    in_target {
      if ($0 ~ /^[^ \t#][^:]*:/) {
        in_target = 0
        next
      }
      if ($0 ~ /^[ \t]/) {
        recipe = trim($0)
        if (recipe == "" || recipe ~ /^#/) next
        observed++
        if (observed > 13 || recipe != expected[observed]) invalid = 1
      }
    }
    END {
      if (target_count != 1 || observed != 13) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$makefile" || {
    echo "host-verify must use the complete fail-fast host recipe exactly once and in order" >&2
    exit 1
  }
}

require_mobile_verify_recipe() {
  local makefile=$1
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    BEGIN {
      expected[1] = "cd mobile && npm ci"
      expected[2] = "cd mobile && npm run typecheck"
      expected[3] = "cd mobile && npm test -- --runInBand"
      expected[4] = "cd mobile && npx expo-doctor"
      expected[5] = "cd mobile && npx expo prebuild --platform android --clean --no-install"
      expected[6] = "cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug"
    }
    $0 ~ /^mobile-verify:[ \t]*sync-proto[ \t]*$/ {
      target_count++
      in_target = 1
      next
    }
    in_target {
      if ($0 ~ /^[^ \t#][^:]*:/) {
        in_target = 0
        next
      }
      if ($0 ~ /^[ \t]/) {
        recipe = trim($0)
        if (recipe == "" || recipe ~ /^#/) next
        observed++
        if (observed > 6 || recipe != expected[observed]) invalid = 1
      }
    }
    END {
      if (target_count != 1 || observed != 6) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$makefile" || {
    echo "mobile-verify must run its complete recipe with Jest immediately after typecheck" >&2
    exit 1
  }
}

[[ -f "$MOBILE_WORKFLOW" ]] || { echo "mobile workflow missing: $MOBILE_WORKFLOW" >&2; exit 1; }
[[ -f "$E2E_WORKFLOW" ]] || { echo "E2E host workflow missing: $E2E_WORKFLOW" >&2; exit 1; }
[[ -f "$MAKEFILE" ]] || { echo "Makefile missing: $MAKEFILE" >&2; exit 1; }

require_mobile_typecheck_runs "$MOBILE_WORKFLOW"
require_occurrences "$MOBILE_WORKFLOW" "'e2e/**'" 2 'mobile push and PR workflows must cover E2E changes'
require_occurrences "$MOBILE_WORKFLOW" "'scripts/verify-offline-pairing-evidence.sh'" 2 'mobile push and PR workflows must cover offline evidence verifier changes'
require_occurrences "$MOBILE_WORKFLOW" "'scripts/verify-release-evidence.sh'" 2 'mobile push and PR workflows must cover release evidence verifier changes'
require_occurrences "$MOBILE_WORKFLOW" "'.github/workflows/e2e-host.yml'" 2 'mobile push and PR workflows must cover E2E host workflow changes'

require_literal "$E2E_WORKFLOW" 'push:' 'E2E host workflow must trigger on push'
require_unconditional_pull_request "$E2E_WORKFLOW"
for release_path in \
  'mobile/eas.json' \
  'mobile/package.json' \
  'mobile/package-lock.json' \
  'scripts/verify-standalone-android.sh' \
  'scripts/verify-android-release-workflow.sh' \
  'scripts/verify-android-release_test.sh' \
  'scripts/verify-host-workflows.sh' \
  'scripts/verify-host-workflows_test.sh' \
  '.github/workflows/e2e-host.yml' \
  '.github/workflows/mobile.yml' \
  '.github/workflows/android-release.yml'; do
  require_push_path "$E2E_WORKFLOW" "$release_path"
done
require_read_only_permissions "$E2E_WORKFLOW"
require_pinned_actions "$E2E_WORKFLOW"
for command in \
  'cd e2e && go test ./... -race -count=1' \
  'cd e2e && go vet ./...' \
  './e2e/scripts/validate-workflow.sh' \
  './e2e/scripts/preflight_test.sh' \
  './scripts/verify-offline-pairing-evidence.sh --self-test' \
  './scripts/verify-release-evidence.sh --self-test' \
  './scripts/verify-android-release_test.sh' \
  './scripts/verify-host-workflows_test.sh'; do
  require_literal "$E2E_WORKFLOW" "$command" "E2E host workflow is missing required command: $command"
done
require_literal "$E2E_WORKFLOW" 'run: ./scripts/verify-host-workflows_test.sh' 'E2E host workflow must run the workflow verifier self-test'
require_host_verify_recipe "$MAKEFILE"
require_mobile_verify_recipe "$MAKEFILE"
require_approved_run_commands "$E2E_WORKFLOW"
require_no_secrets "$E2E_WORKFLOW"

echo 'host workflow validation passed'
