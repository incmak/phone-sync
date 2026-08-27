#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_ANDROID_RELEASE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
WORKFLOW="$ROOT_DIR/.github/workflows/android-release.yml"
EAS_CONFIG="$ROOT_DIR/mobile/eas.json"
PACKAGE_JSON="$ROOT_DIR/mobile/package.json"

die() {
  echo "android-release-workflow: $*" >&2
  exit 1
}

verify_config() {
  command -v jq >/dev/null 2>&1 || die "jq is required"
  [[ -f "$EAS_CONFIG" ]] || die "mobile/eas.json is missing"
  [[ -f "$PACKAGE_JSON" ]] || die "mobile/package.json is missing"
  jq -e '
    .cli.version == "22.0.0" and
    .cli.appVersionSource == "local" and
    .build["release-apk"].developmentClient == false and
    .build["release-apk"].distribution == "internal" and
    .build["release-apk"].environment == "production" and
    .build["release-apk"].android.buildType == "apk" and
    (.build["release-apk"].android.withoutCredentials == null) and
    .build.production.developmentClient == false and
    .build.production.distribution == "store" and
    .build.production.environment == "production" and
    .build.production.android.buildType == "app-bundle" and
    (.build.production.android.withoutCredentials == null)
  ' "$EAS_CONFIG" >/dev/null || die "EAS release profiles are not fail-closed APK/AAB production profiles"
  jq -e '
    ((.dependencies // {})["eas-cli"] == null) and
    ((.devDependencies // {})["eas-cli"] == null) and
    .scripts["build:dev"] == "npx --yes eas-cli@22.0.0 build --profile development --platform android --local"
  ' "$PACKAGE_JSON" >/dev/null || die "project-local eas-cli dependency is forbidden; use the pinned ephemeral CLI"
}

verify_eas_cli_invocations() {
  awk '
    function trim(value) {
      sub(/^[ \t]+/, "", value)
      sub(/[ \t]+$/, "", value)
      return value
    }
    {
      code = $0
      sub(/[ \t]+#.*/, "", code)
      entry = trim(code)
      if (entry == "") next
      normalized = tolower(entry)
      invokes_eas = normalized ~ /(^|[^[:alnum:]_-])eas-cli([^[:alnum:]_-]|$)/ ||
                    normalized ~ /(^|[^[:alnum:]_])npm[ \t]+exec[ \t]+eas([^[:alnum:]_]|$)/ ||
                    normalized ~ /(^|[^[:alnum:]_])eas[ \t]+build([^[:alnum:]_]|$)/
      if (!invokes_eas) next
      if (entry == "npx --yes eas-cli@22.0.0 build --platform android --profile release-apk --non-interactive --wait --json > \"$RUNNER_TEMP/release-apk-build.json\"") {
        apk++
      } else if (entry == "npx --yes eas-cli@22.0.0 build --platform android --profile production --non-interactive --wait --json > \"$RUNNER_TEMP/production-build.json\"") {
        production++
      } else {
        invalid = 1
      }
    }
    END {
      if (apk != 1 || production != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$WORKFLOW" || die "EAS builds must use exactly the two pinned ephemeral CLI commands"
}

verify_read_only_permissions() {
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
  ' "$WORKFLOW" || die "workflow permissions must contain exactly one top-level contents: read entry"
}

verify_release_triggers() {
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

      if (entry == "on:") {
        if (indent != 0 || on_blocks != 0) invalid = 1
        on_blocks++
        in_on = 1
        active_event = ""
        next
      }
      if (!in_on) next
      if (indent == 0) {
        in_on = 0
        active_event = ""
        next
      }
      if (indent == 2) {
        if (entry == "workflow_dispatch:") {
          workflow_dispatch++
          active_event = "workflow_dispatch"
        } else if (entry == "push:") {
          push++
          active_event = "push"
        } else {
          invalid = 1
          active_event = "invalid"
        }
        next
      }
      if (indent == 4 && active_event == "push" && entry == "tags: ['\''android-v*'\'']") {
        push_tags++
        next
      }
      invalid = 1
    }
    END {
      if (on_blocks != 1 || workflow_dispatch != 1 || push != 1 || push_tags != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$WORKFLOW" || die "release triggers must be exactly workflow_dispatch and push.tags android-v*"
}

verify_secret_scopes() {
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
      if (indent == 6 && entry ~ /^- name: /) {
        step = entry
        sub(/^- name: /, "", step)
      }
      normalized = tolower(entry)
      if (normalized !~ /(^|[^[:alnum:]_])secrets([^[:alnum:]_]|$)/) next
      if (indent != 10) invalid = 1
      if (entry == "EAS_TOKEN: ${{ secrets.EAS_TOKEN }}" &&
          (step == "Build installable release APK at exact commit" || step == "Build Play AAB at exact commit")) {
        eas_token++
      } else if (entry == "EXPECTED_RELEASE_CERT_SHA256: ${{ secrets.ANDROID_RELEASE_CERT_SHA256 }}" &&
                 (step == "Require protected certificate input" || step == "Verify standalone APK")) {
        certificate++
      } else if (entry == "RELEASE_ATTESTATION_PRIVATE_KEY: ${{ secrets.RELEASE_ATTESTATION_PRIVATE_KEY }}" &&
                 step == "Sign release attestation") {
        attestation_key++
      } else {
        invalid = 1
      }
    }
    END {
      if (eas_token != 2 || certificate != 2 || attestation_key != 1) invalid = 1
      exit invalid ? 1 : 0
    }
  ' "$WORKFLOW" || die "protected secrets must be scoped only to their exact consuming steps"
}

canonical_run_payload() {
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
      # Ignore true comments and exactly one single-quoted literal printf
      # argument. Every other run payload is structural contract input,
      # including wrappers and expansions that could execute work indirectly.
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
  ' "$WORKFLOW"
}

verify_canonical_run_payload() {
  local actual_digest
  if command -v shasum >/dev/null 2>&1; then
    actual_digest=$(canonical_run_payload | shasum -a 256 | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    actual_digest=$(canonical_run_payload | sha256sum | awk '{print $1}')
  else
    die "shasum or sha256sum is required to verify the protected-release run-payload contract"
  fi
  [[ "$actual_digest" == 'e0d6b6158649f8c9389fcdf95a99f4e0d989f65c569c1319e283dda65fe48f7e' ]] ||
    die "protected-release run payload differs from its reviewed contract (got $actual_digest); review the workflow and update the checked digest intentionally"
}

verify_host_verification_ownership() {
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
    function inspect_run(command, normalized) {
      normalized = trim(sanitize_executable(command))
      if (normalized == "make host-verify") {
        host_verify++
        host_verify_line = NR
      }
    }
    {
      raw_code = $0
      secret_code = raw_code
      sub(/[ \t]+#.*/, "", secret_code)
      if (tolower(secret_code) ~ /secrets[[:space:]]*([.\[]|[[:space:]]+[.\[])/ && first_secret == 0) first_secret = NR
      executable_code = sanitize_executable(raw_code)
      indent = indentation(raw_code)
      if (in_run) {
        if (indent > run_indent) {
          inspect_run(raw_code)
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
        } else inspect_run(command)
      }
    }
    END {
      if (host_verify != 1 || host_verify_line == 0 ||
          first_secret == 0 || host_verify_line >= first_secret) exit 1
    }
  ' "$WORKFLOW" || die "make host-verify must own the only locked install and run before protected secrets"
}

verify_workflow() {
  [[ -f "$WORKFLOW" ]] || die "protected Android release workflow is missing"
  verify_release_triggers
  verify_read_only_permissions
  verify_secret_scopes
  verify_host_verification_ownership
  verify_canonical_run_payload
  verify_eas_cli_invocations
  grep -Eq '^[[:space:]]*environment:[[:space:]]*android-release[[:space:]]*$' "$WORKFLOW" || die "android-release protected environment is required"

  local action_count pinned_count
  action_count=$(grep -Ec '^[[:space:]]*- uses: ' "$WORKFLOW" || true)
  pinned_count=$(grep -Ec '^[[:space:]]*- uses: [^[:space:]]+@[0-9a-f]{40}([[:space:]]|$)' "$WORKFLOW" || true)
  [[ "$action_count" -gt 0 && "$action_count" -eq "$pinned_count" ]] || die "all actions must be pinned to full commit SHAs"

  grep -Fq 'run: make host-verify' "$WORKFLOW" || die "mandatory host verification is missing"
  grep -Fq 'git rev-parse HEAD' "$WORKFLOW" || die "exact checkout commit capture is missing"
  grep -Fq 'gitCommitHash' "$WORKFLOW" || die "EAS result commit assertion is missing"
  grep -Fq './scripts/verify-standalone-android.sh' "$WORKFLOW" || die "standalone APK verifier is missing"
  grep -Fq 'app-provenance.json' "$WORKFLOW" || die "artifact provenance generation is missing"
  grep -Fq 'app-attestation.json' "$WORKFLOW" || die "artifact attestation generation is missing"
  grep -Fq 'openssl dgst -sha256 -sign' "$WORKFLOW" || die "detached attestation signing is missing"
  grep -Fq 'actions/upload-artifact@' "$WORKFLOW" || die "release artifact upload is missing"
  grep -Fq 'retention-days:' "$WORKFLOW" || die "explicit artifact retention is missing"

  ! grep -Eqi 'eas[[:space:]]+submit|--auto-submit|withoutCredentials|set[[:space:]]+-x|printenv|env[[:space:]]*\|' "$WORKFLOW" || die "auto-submit, credential bypass, or secret-dumping commands are forbidden"
}

verify_all() {
  verify_config
  verify_workflow
  echo "Android release workflow contract passed"
}

self_test() {
  verify_all >/dev/null
  local tmp
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-android-release-workflow.XXXXXX")
  trap 'rm -rf -- "$tmp"' RETURN

  copy_fixture() {
    rm -rf -- "$tmp/.github" "$tmp/mobile"
    mkdir -p "$tmp/.github/workflows" "$tmp/mobile"
    cp "$WORKFLOW" "$tmp/.github/workflows/android-release.yml"
    cp "$EAS_CONFIG" "$tmp/mobile/eas.json"
    cp "$PACKAGE_JSON" "$tmp/mobile/package.json"
  }
  expect_rejection() {
    local label=$1
    if TWINOTIFY_ANDROID_RELEASE_ROOT="$tmp" "$ROOT_DIR/scripts/verify-android-release-workflow.sh" >/dev/null 2>"$tmp/error"; then
      die "self-test expected rejection: $label"
    fi
  }
  expect_acceptance() {
    local label=$1
    if ! TWINOTIFY_ANDROID_RELEASE_ROOT="$tmp" "$ROOT_DIR/scripts/verify-android-release-workflow.sh" >/dev/null 2>"$tmp/error"; then
      cat "$tmp/error" >&2
      die "self-test expected acceptance: $label"
    fi
  }

  copy_fixture
  sed -i.bak 's/android-v\*/release-v*/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection unprotected-tag
  copy_fixture
  awk '/^on:$/ { print; print "  pull_request:"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection pull-request-trigger
  copy_fixture
  awk '/^on:$/ { print; print "  pull_request_target:"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection pull-request-target-trigger
  copy_fixture
  awk '/^on:$/ { print; print "  schedule:"; print "    - cron: \"0 0 * * *\""; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection schedule-trigger
  copy_fixture
  awk '/^on:$/ { print; print "  workflow_run:"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection workflow-run-trigger
  copy_fixture
  awk '/^on:$/ { print; print "  repository_dispatch:"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection repository-dispatch-trigger
  copy_fixture
  awk '/^on:$/ { print; print "  workflow_call:"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection workflow-call-trigger
  copy_fixture
  awk '/^on:$/ { print; print "  merge_group:"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection extra-event-trigger
  copy_fixture
  awk '/^  push:$/ { print; print "    branches: [main]"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection push-branches-filter
  copy_fixture
  awk '/^  push:$/ { print; print "    paths: [mobile/**]"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection push-paths-filter
  copy_fixture
  awk '/timeout-minutes: 120/ { print; print "    env:"; print "      EAS_TOKEN: ${{ secrets.EAS_TOKEN }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection job-level-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ secrets[\047EAS_TOKEN\047] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection early-step-single-quoted-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ secrets[\"EAS_TOKEN\"] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection early-step-double-quoted-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ secrets[format(\"{0}\", \"EAS_TOKEN\")] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection early-step-dynamic-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        if: ${{ secrets[\"EAS_TOKEN\"] != \"\" }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection early-step-if-secret
  copy_fixture
  awk '/fetch-depth: 1/ { print; print "          leak: ${{ secrets[\"EAS_TOKEN\"] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection early-step-with-secret
  copy_fixture
  awk '/^[[:space:]]*run: make host-verify$/ { print "        run: printf \"%s\" \"${{ secrets.EAS_TOKEN }}\""; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection early-step-run-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ secrets [\047EAS_TOKEN\047] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection whitespace-before-bracket-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ secrets  . EAS_TOKEN }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection whitespace-around-dot-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ secrets\t[\047EAS_TOKEN\047] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection tab-before-bracket-secret
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: >-"; print "            ${{"; print "              secrets"; print "              [\047EAS_TOKEN\047]"; print "            }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection multiline-secret-context
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print; print "        env:"; print "          LEAK: ${{ SeCrEtS [\047EAS_TOKEN\047] }}"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection mixed-case-secret-context
  copy_fixture
  sed -i.bak 's/environment: android-release/environment: unprotected/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection missing-environment
  copy_fixture
  sed -i.bak 's/actions\/checkout@[0-9a-f]\{40\}/actions\/checkout@v4/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection unpinned-action
  copy_fixture
  sed -i.bak '/run: make host-verify/d' "$tmp/.github/workflows/android-release.yml"
  expect_rejection missing-host-gate
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: npm ci"; print "        working-directory: mobile" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          cd mobile && npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-block-scalar
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          cd mobile;npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-no-space-semicolon
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          command npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-command-prefix
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          env CHECK=1 npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-env-prefix
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          npm${IFS}ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-ifs-construction
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          npm$(printf \" \")ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-command-substitution
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          printf \"$(npm ci)\"" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-printf-command-substitution
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          sh -c \047npm ci\047" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-sh-c
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          eval \047npm ci\047" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-eval
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          n\\pm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-escaped-command-name
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          :&&npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-no-space-and
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          false||npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-no-space-or
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          printf x|npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-no-space-pipe
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Install locked dependencies"; print "        run: |"; print "          (npm ci)" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection duplicate-locked-install-subshell
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Quoted hash before executable install"; print "        run: |"; print "          printf \047 # \047;npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection quoted-hash-before-install
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Quoted install mention"; print "        run: |"; print "          printf \047:;npm ci\047" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_acceptance quoted-install-mention
  copy_fixture
  awk '/^[[:space:]]*- name: Verify exact source and host contract$/ { print "      - name: Commented install mention"; print "        run: |"; print "          # :;npm ci" } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_acceptance commented-install-mention
  copy_fixture
  sed -i.bak 's/--profile production/--profile preview/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection wrong-aab-profile
  copy_fixture
  sed -i.bak 's/eas-cli@22\.0\.0/eas-cli/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection unpinned-eas-cli
  copy_fixture
  sed -i.bak 's/release-apk-build\.json"/release-apk-build.json" --local/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection noncanonical-pinned-eas-command
  copy_fixture
  sed -i.bak 's/npx --yes eas-cli@22\.0\.0/npm exec eas --/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection project-local-eas-cli-invocation
  copy_fixture
  jq '.devDependencies["eas-cli"] = "22.0.0"' "$tmp/mobile/package.json" > "$tmp/mobile/package.tmp"
  mv "$tmp/mobile/package.tmp" "$tmp/mobile/package.json"
  expect_rejection project-local-eas-cli-dependency
  copy_fixture
  jq '.scripts["build:dev"] = "eas build --profile development --platform android --local"' "$tmp/mobile/package.json" > "$tmp/mobile/package.tmp"
  mv "$tmp/mobile/package.tmp" "$tmp/mobile/package.json"
  expect_rejection unpinned-development-build-script
  copy_fixture
  sed -i.bak 's/contents: read/contents: write/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection write-permission
  copy_fixture
  awk '/contents: read/ { print; print "  actions: write # comment-hidden expansion"; next } { print }' "$tmp/.github/workflows/android-release.yml" > "$tmp/workflow.tmp"
  mv "$tmp/workflow.tmp" "$tmp/.github/workflows/android-release.yml"
  expect_rejection comment-hidden-write-permission
  copy_fixture
  jq '.build["release-apk"].developmentClient = true' "$tmp/mobile/eas.json" > "$tmp/mobile/eas.tmp"
  mv "$tmp/mobile/eas.tmp" "$tmp/mobile/eas.json"
  expect_rejection development-client
  copy_fixture
  jq '.build.production.android.withoutCredentials = true' "$tmp/mobile/eas.json" > "$tmp/mobile/eas.tmp"
  mv "$tmp/mobile/eas.tmp" "$tmp/mobile/eas.json"
  expect_rejection credential-bypass

  echo "Android release workflow verifier self-test passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
  [[ $# -eq 1 ]] || die "--self-test accepts no additional arguments"
  self_test
  exit 0
fi
[[ $# -eq 0 ]] || die "usage: $0 [--self-test]"
verify_all
