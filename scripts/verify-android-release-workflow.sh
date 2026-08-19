#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_ANDROID_RELEASE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
WORKFLOW="$ROOT_DIR/.github/workflows/android-release.yml"
EAS_CONFIG="$ROOT_DIR/mobile/eas.json"

die() {
  echo "android-release-workflow: $*" >&2
  exit 1
}

verify_config() {
  command -v jq >/dev/null 2>&1 || die "jq is required"
  [[ -f "$EAS_CONFIG" ]] || die "mobile/eas.json is missing"
  jq -e '
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
      if (entry !~ /secrets\./) next
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

verify_workflow() {
  [[ -f "$WORKFLOW" ]] || die "protected Android release workflow is missing"
  verify_release_triggers
  verify_read_only_permissions
  verify_secret_scopes
  grep -Eq '^[[:space:]]*environment:[[:space:]]*android-release[[:space:]]*$' "$WORKFLOW" || die "android-release protected environment is required"

  local action_count pinned_count
  action_count=$(grep -Ec '^[[:space:]]*- uses: ' "$WORKFLOW" || true)
  pinned_count=$(grep -Ec '^[[:space:]]*- uses: [^[:space:]]+@[0-9a-f]{40}([[:space:]]|$)' "$WORKFLOW" || true)
  [[ "$action_count" -gt 0 && "$action_count" -eq "$pinned_count" ]] || die "all actions must be pinned to full commit SHAs"

  grep -Fq 'run: make host-verify' "$WORKFLOW" || die "mandatory host verification is missing"
  grep -Fq 'npm exec eas -- build --platform android --profile release-apk --non-interactive --wait --json' "$WORKFLOW" || die "release-apk EAS build is missing"
  grep -Fq 'npm exec eas -- build --platform android --profile production --non-interactive --wait --json' "$WORKFLOW" || die "production AAB EAS build is missing"
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
  }
  expect_rejection() {
    local label=$1
    if TWINOTIFY_ANDROID_RELEASE_ROOT="$tmp" "$ROOT_DIR/scripts/verify-android-release-workflow.sh" >/dev/null 2>"$tmp/error"; then
      die "self-test expected rejection: $label"
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
  sed -i.bak 's/environment: android-release/environment: unprotected/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection missing-environment
  copy_fixture
  sed -i.bak 's/actions\/checkout@[0-9a-f]\{40\}/actions\/checkout@v4/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection unpinned-action
  copy_fixture
  sed -i.bak '/run: make host-verify/d' "$tmp/.github/workflows/android-release.yml"
  expect_rejection missing-host-gate
  copy_fixture
  sed -i.bak 's/--profile production/--profile preview/' "$tmp/.github/workflows/android-release.yml"
  expect_rejection wrong-aab-profile
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
