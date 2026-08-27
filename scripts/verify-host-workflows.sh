#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_HOST_WORKFLOW_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
MOBILE_WORKFLOW="$ROOT_DIR/.github/workflows/mobile.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-host.yml"
E2E_ANDROID_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-android.yml"
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

require_mobile_native_android_runs() {
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
    function normalized_key(value, colon, key, first, last, single_quote) {
      sub(/^-[ \t]+/, "", value)
      colon = index(value, ":")
      if (colon == 0) return ""
      key = trim(substr(value, 1, colon - 1))
      first = substr(key, 1, 1)
      last = substr(key, length(key), 1)
      single_quote = sprintf("%c", 39)
      if (length(key) >= 2 && ((first == "\"" && last == "\"") ||
          (first == single_quote && last == single_quote))) {
        key = substr(key, 2, length(key) - 2)
      }
      return key
    }
    function mapping_value(value, colon) {
      colon = index(value, ":")
      if (colon == 0) return ""
      return trim(substr(value, colon + 1))
    }
    function supported_step_key(key) {
      return key == "uses" || key == "run" || key == "name" ||
             key == "if" || key == "continue-on-error" ||
             key == "shell" || key == "working-directory"
    }
    function unsupported_yaml_syntax(value) {
      return value ~ /[{}]/ || value ~ /(^|[ \t])[&*!][^ \t]+/ ||
             value ~ /^(-[ \t]+)?\?[ \t]+/
    }
    function finish_step(expected_directory) {
      if (!step_active) return
      if (step_required_index > 0) {
        if (step_shell_count != 0) invalid = 1
        expected_directory = ""
        if (step_required_index == 2) expected_directory = "."
        if (step_required_index == 4) expected_directory = "mobile/android"
        if (expected_directory == "") {
          if (step_working_directory_count != 0) invalid = 1
        } else if (step_working_directory_count != 1 ||
                   step_working_directory != expected_directory) {
          invalid = 1
        }
      }
      step_active = 0
      step_required_index = 0
      step_shell_count = 0
      step_working_directory_count = 0
      step_working_directory = ""
    }
    BEGIN {
      expected[1] = "npm ci"
      expected[2] = "make sync-proto"
      expected[3] = "npx expo prebuild --platform android --clean --no-install"
      expected[4] = "./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug"
    }
    {
      code = $0
      sub(/[ \t]+#.*/, "", code)
      if (trim(code) == "") next
      indent = indentation(code)

      if (code ~ /^  native-android:[ \t]*$/) {
        native_jobs++
        in_native = 1
        next
      }
      if (in_native && code ~ /^  [^ \t#][^:]*:/) {
        finish_step()
        in_native = 0
        next
      }
      if (in_native) {
        entry = trim(code)
        if (unsupported_yaml_syntax(entry)) invalid = 1
        entry_key = normalized_key(entry)
        entry_value = mapping_value(entry)

        if (indent == 4) {
          in_defaults = (entry_key == "defaults")
          in_defaults_run = 0
          if (in_defaults) defaults_count++
        } else if (in_defaults && indent == 6 && entry !~ /^-[ \t]+/) {
          if (entry_key == "run") {
            defaults_run_count++
            in_defaults_run = 1
          } else {
            invalid = 1
            in_defaults_run = 0
          }
        } else if (in_defaults_run && indent == 8) {
          if (entry_key == "working-directory") {
            defaults_working_directory_count++
            defaults_working_directory = entry_value
          } else {
            invalid = 1
          }
        }

        if (indent == 4 && entry_key == "if") invalid = 1
        if (indent == 4 && entry_key == "continue-on-error") invalid = 1
        if (indent == 6 && entry ~ /^-[ \t]+/) {
          finish_step()
          step_active = 1
          step_conditional = 0
          step_continue = 0
          if (!supported_step_key(entry_key)) invalid = 1
          if (entry_key == "if") step_conditional = 1
          if (entry_key == "continue-on-error") step_continue = 1
        }
        if (indent == 8 && entry_key == "if") {
          step_conditional = 1
          if (step_required_index > 0) invalid = 1
        }
        if (indent == 8 && entry_key == "continue-on-error") {
          step_continue = 1
          if (step_required_index > 0) invalid = 1
        }
        if (step_active && (indent == 6 || indent == 8) && entry_key == "shell") {
          step_shell_count++
        }
        if (step_active && (indent == 6 || indent == 8) &&
            entry_key == "working-directory") {
          step_working_directory_count++
          step_working_directory = entry_value
        }
        if (entry_key == "run" &&
            ((indent == 6 && entry ~ /^-[ \t]+/) || indent == 8)) {
          command = entry_value
          observed++
          if (observed > 4 || command != expected[observed]) invalid = 1
          step_required_index = observed
          if (step_conditional || step_continue) invalid = 1
        }
      }
    }
    END {
      finish_step()
      if (defaults_count != 1 || defaults_run_count != 1 ||
          defaults_working_directory_count != 1 ||
          defaults_working_directory != "mobile") invalid = 1
      if (native_jobs != 1 || observed != 4) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$workflow" || {
    echo "mobile native-android job must run npm ci, proto sync, prebuild, and the canonical instrumentation-compiling Gradle command exactly once and in order" >&2
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
      approved["./e2e/scripts/lan_product_target_test.sh"] = 1
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
	  expected[6] = "./e2e/scripts/lan_product_target_test.sh"
	  expected[7] = "./e2e/scripts/validate-workflow.sh"
	  expected[8] = "./e2e/scripts/preflight_test.sh"
	  expected[9] = "./scripts/verify-offline-pairing-evidence.sh --self-test"
	  expected[10] = "./scripts/verify-release-evidence.sh --self-test"
	  expected[11] = "./scripts/verify-project-docs.sh"
	  expected[12] = "./scripts/verify-project-docs_test.sh"
	  expected[13] = "./scripts/verify-android-release_test.sh"
	  expected[14] = "./scripts/verify-host-workflows.sh"
	  expected[15] = "./scripts/verify-host-workflows_test.sh"
	  expected[16] = "./scripts/verify-generated-clean.sh"
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
		if (observed > 16 || recipe != expected[observed]) invalid = 1
      }
    }
    END {
	  if (target_count != 1 || observed != 16) invalid = 1
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
      expected[6] = "cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug"
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

require_e2e_emulator_targets() {
  local makefile=$1
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    function finish_target() {
      if (target == "e2e-emulator") {
        local_target_count++
        if (recipe_count != 1 || recipe[1] != "$(MAKE) e2e-emulator-run") invalid = 1
      } else if (target == "e2e-emulator-run") {
        run_target_count++
        if (recipe_count != 3 ||
            recipe[1] != "test -x bin/relay || { echo \"e2e-emulator-run: relay binary not found or not executable: bin/relay\" >&2; exit 2; }" ||
            recipe[2] != "test -f mobile/android/app/build/outputs/apk/debug/app-debug.apk || { echo \"e2e-emulator-run: debug APK not found: mobile/android/app/build/outputs/apk/debug/app-debug.apk\" >&2; exit 2; }" ||
            recipe[3] != "./e2e/scripts/run-two-emulators.sh") invalid = 1
      }
      target = ""
      recipe_count = 0
      delete recipe
    }
    /^[^ \t#][^:]*:/ {
      finish_target()
      if ($0 == "e2e-emulator: relay-build mobile-verify") target = "e2e-emulator"
      else if ($0 == "e2e-emulator-run:") target = "e2e-emulator-run"
      next
    }
    target != "" && /^[ \t]/ {
      command = trim($0)
      if (command == "" || command ~ /^#/) next
      sub(/^@/, "", command)
      recipe[++recipe_count] = command
    }
    END {
      finish_target()
      if (local_target_count != 1 || run_target_count != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$makefile" || {
    echo "e2e-emulator must retain local preparation and delegate to a run-only target with exact APK and relay artifact preflight" >&2
    exit 1
  }
}

canonical_run_payload() {
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
    function sanitize_executable(value, result, position, character, quote, escaped, previous) {
      for (position = 1; position <= length(value); position++) {
        character = substr(value, position, 1)
        if (quote != "") {
          if (quote == "\"" && escaped) {
            escaped = 0
            continue
          }
          if (quote == "\"" && character == "\\") {
            escaped = 1
            continue
          }
          if (character == quote) quote = ""
          continue
        }
        if (character == "\"" || character == sprintf("%c", 39)) {
          quote = character
          continue
        }
        previous = substr(result, length(result), 1)
        if (character == "#" && (result == "" || previous ~ /[[:space:];&|()]/)) break
        result = result character
      }
      return result
    }
    function without_shell_comment(value, result, position, character, quote, escaped, previous) {
      for (position = 1; position <= length(value); position++) {
        character = substr(value, position, 1)
        if (quote != "") {
          result = result character
          if (quote == "\"" && escaped) {
            escaped = 0
            continue
          }
          if (quote == "\"" && character == "\\") {
            escaped = 1
            continue
          }
          if (character == quote) quote = ""
          continue
        }
        if (character == "\"" || character == sprintf("%c", 39)) {
          quote = character
          result = result character
          continue
        }
        previous = substr(result, length(result), 1)
        if (character == "#" && (result == "" || previous ~ /[[:space:];&|()]/)) break
        result = result character
      }
      return trim(result)
    }
    function inert_quoted_printf(canonical, single_quote) {
      single_quote = sprintf("%c", 39)
      return canonical ~ ("^printf[ \t]+" single_quote "[^" single_quote "]*" single_quote "$")
    }
    function emit(kind, value, executable, canonical) {
      executable = trim(sanitize_executable(value))
      # Comments are inert. The only inert printf fixture is one single-quoted
      # literal argument: double quotes, substitutions, extra arguments, and
      # operators all remain structural contract input.
      if (executable == "") return
      canonical = without_shell_comment(value)
      if (inert_quoted_printf(canonical)) return
      if (canonical != "") print kind ":" canonical
    }
    {
      raw = $0
      indent = indentation(raw)
      if (in_run) {
        if (indent > run_indent) {
          emit("block", raw)
          next
        }
        in_run = 0
      }
      entry = trim(raw)
      if (entry ~ /^-?[[:space:]]*run:[[:space:]]*/) {
        command = entry
        sub(/^-?[[:space:]]*run:[[:space:]]*/, "", command)
        if (command ~ /^[>|][+-]?$/) {
          in_run = 1
          run_indent = indent
        } else {
          emit("scalar", command)
        }
      }
    }
  ' "$workflow"
}

require_canonical_run_payload() {
  local workflow=$1 expected_digest=$2 label=$3 actual_digest
  if command -v shasum >/dev/null 2>&1; then
    actual_digest=$(canonical_run_payload "$workflow" | shasum -a 256 | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    actual_digest=$(canonical_run_payload "$workflow" | sha256sum | awk '{print $1}')
  else
    echo "shasum or sha256sum is required to verify the $label run-payload contract" >&2
    exit 1
  fi
  [[ "$actual_digest" == "$expected_digest" ]] || {
    echo "$label run payload differs from its reviewed contract (got $actual_digest); review the workflow and update the checked digest intentionally" >&2
    exit 1
  }
}

require_e2e_android_artifact_flow() {
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
    function sanitize_executable(value, result, position, character, quote, escaped, previous) {
      for (position = 1; position <= length(value); position++) {
        character = substr(value, position, 1)
        if (quote != "") {
          if (quote == "\"" && escaped) {
            escaped = 0
            continue
          }
          if (quote == "\"" && character == "\\") {
            escaped = 1
            continue
          }
          if (character == quote) quote = ""
          continue
        }
        if (character == "\"" || character == sprintf("%c", 39)) {
          quote = character
          continue
        }
        previous = substr(result, length(result), 1)
        if (character == "#" && (result == "" || previous ~ /[[:space:];&|()]/)) break
        result = result character
      }
      return result
    }
    function inspect(command, normalized) {
      normalized = trim(sanitize_executable(command))
      if (normalized == "make verify") { verify++; verify_line = NR }
      if (normalized == "make relay-build") { relay_build++; relay_build_line = NR }
      if (normalized == "./e2e/scripts/prepare-avds.sh") { prepare_avds++; prepare_avds_line = NR }
      if (normalized == "make e2e-emulator-run") { run_only++; run_only_line = NR }
    }
    {
      code = $0
      executable_code = sanitize_executable(code)
      indent = indentation(code)
      if (in_run) {
        if (indent > run_indent) {
          inspect(code)
          next
        }
        in_run = 0
      }
      if (trim(executable_code) == "") next
      entry = trim(executable_code)
      if (entry ~ /^-?[[:space:]]*run:[[:space:]]*/) {
        command = entry
        sub(/^-?[[:space:]]*run:[[:space:]]*/, "", command)
        if (command ~ /^[>|][+-]?$/) {
          in_run = 1
          run_indent = indent
        } else inspect(command)
      }
    }
    END {
      if (verify != 1 || relay_build != 1 || prepare_avds != 1 || run_only != 1 ||
          verify_line >= relay_build_line || relay_build_line >= prepare_avds_line ||
          prepare_avds_line >= run_only_line) exit 1
    }
  ' "$workflow" || {
    echo "E2E Android must run one full verification, one relay build, one AVD preparation, and the run-only scenario without direct mobile rebuild work" >&2
    exit 1
  }
}

[[ -f "$MOBILE_WORKFLOW" ]] || { echo "mobile workflow missing: $MOBILE_WORKFLOW" >&2; exit 1; }
[[ -f "$E2E_WORKFLOW" ]] || { echo "E2E host workflow missing: $E2E_WORKFLOW" >&2; exit 1; }
[[ -f "$E2E_ANDROID_WORKFLOW" ]] || { echo "E2E Android workflow missing: $E2E_ANDROID_WORKFLOW" >&2; exit 1; }
[[ -f "$MAKEFILE" ]] || { echo "Makefile missing: $MAKEFILE" >&2; exit 1; }

require_mobile_typecheck_runs "$MOBILE_WORKFLOW"
require_mobile_native_android_runs "$MOBILE_WORKFLOW"
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
  'scripts/verify-lan-product-evidence.sh' \
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
  './e2e/scripts/lan_product_target_test.sh' \
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
require_e2e_emulator_targets "$MAKEFILE"
require_e2e_android_artifact_flow "$E2E_ANDROID_WORKFLOW"
require_canonical_run_payload "$E2E_ANDROID_WORKFLOW" '4355e3c2ad67cdf2088a3aa8b45e093bb18aab72e3c5df1f62353404efb93e0f' 'E2E Android workflow'
require_approved_run_commands "$E2E_WORKFLOW"
require_no_secrets "$E2E_WORKFLOW"

echo 'host workflow validation passed'
