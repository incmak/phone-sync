#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERIFY="$ROOT_DIR/scripts/verify-project-docs.sh"

[[ -x "$VERIFY" ]] || {
  echo "project documentation verifier is missing or not executable: $VERIFY" >&2
  exit 1
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-project-docs.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT

write_valid_fixture() {
  rm -rf -- "$tmp/docs"
  mkdir -p "$tmp/docs/mobile" "$tmp/docs/proto" "$tmp/docs/docs/release-evidence"

  printf '%s\n' \
    '# Twinotify' \
    'Android notification mirroring uses a custom Kotlin native module and requires a development build.' \
    'Run `make host-verify`, `make verify`, `make e2e-emulator`, and `make release-audit`.' \
    > "$tmp/docs/README.md"
  printf '%s\n' \
    '# Mobile' \
    'Use `npm ci` and a development build for the custom Kotlin module. Expo Go cannot load it.' \
    > "$tmp/docs/mobile/README.md"
  printf '%s\n' \
    '# Protocol' \
    'The supported Android and Go consumers retain v1 compatibility while v2 provides authenticated durable delivery.' \
    > "$tmp/docs/proto/README.md"
  printf '%s\n' \
    '# Release evidence' \
    'The protected Android producer remains externally blocked until the owner supplies project linkage and protected signing inputs.' \
    'The absence of a physical evidence directory is a pending release gate, not a pass.' \
    '' \
    '```json' \
    '{' \
    '  "scenarios": {' \
    '    "PHY-PAIR-01": "pass",' \
    '    "PHY-DOZE-01": "pass",' \
    '    "PHY-OEM-01": "pass",' \
    '    "PHY-NET-01": "pass",' \
    '    "PHY-BATTERY-01": "pass",' \
    '    "PHY-CALL-01": "pass",' \
    '    "PHY-RELIABILITY-01": "pass"' \
    '  }' \
    '}' \
    '```' \
    > "$tmp/docs/docs/release-evidence/README.md"
  printf '%s\n' \
    '# Test scenarios' \
    'The automated aggregate includes lan-relay-fallback-return and lan-restart-persistence.' \
    'Their acceptance remains pending physical two-phone run.' \
    > "$tmp/docs/docs/test-scenarios.md"
}

expect_rejection() {
  local label=$1
  if TWINOTIFY_PROJECT_DOCS_ROOT="$tmp/docs" "$VERIFY" >/dev/null 2>"$tmp/error"; then
    echo "self-test expected rejection: $label" >&2
    return 1
  fi
}

remove_scenario() {
  local scenario=$1 manifest="$tmp/manifest.json"
  awk '
    /^```json[[:space:]]*$/ && !started { started = 1; print; next }
    started && /^```[[:space:]]*$/ { exit }
    !started { print }
    started { print > target }
  ' target="$manifest" "$tmp/docs/docs/release-evidence/README.md" > "$tmp/release-prefix.md"
  jq --arg id "$scenario" 'del(.scenarios[$id])' "$manifest" > "$tmp/release-without-scenario.json"
  awk '{ print }' "$tmp/release-prefix.md" > "$tmp/docs/docs/release-evidence/README.md"
  awk '{ print }' "$tmp/release-without-scenario.json" >> "$tmp/docs/docs/release-evidence/README.md"
  printf '%s\n' '```' >> "$tmp/docs/docs/release-evidence/README.md"
}

write_valid_fixture
TWINOTIFY_PROJECT_DOCS_ROOT="$tmp/docs" "$VERIFY"

write_valid_fixture
sed -i.bak 's/Expo Go cannot load it/Expo Go is required to load it/' "$tmp/docs/mobile/README.md"
expect_rejection 'Expo Go mobile workflow'

write_valid_fixture
printf '%s\n' 'npm install' >> "$tmp/docs/mobile/README.md"
expect_rejection 'npm install mobile workflow'

write_valid_fixture
printf '%s\n' 'reset-project' >> "$tmp/docs/mobile/README.md"
expect_rejection 'reset-project mobile workflow'

write_valid_fixture
printf '%s\n' 'Desktop Rust' >> "$tmp/docs/proto/README.md"
expect_rejection 'Desktop Rust protocol consumer'

write_valid_fixture
sed -i.bak 's/v2 provides/authenticated durable delivery provides/' "$tmp/docs/proto/README.md"
expect_rejection 'v1-only protocol guidance'

for scenario in PHY-PAIR-01 PHY-DOZE-01 PHY-OEM-01 PHY-NET-01 PHY-BATTERY-01 PHY-RELIABILITY-01 PHY-CALL-01; do
  write_valid_fixture
  remove_scenario "$scenario"
  expect_rejection "missing required physical scenario: $scenario"
done

write_valid_fixture
sed -i.bak '/custom Kotlin native module/d' "$tmp/docs/README.md"
expect_rejection 'missing custom native-module development-build guidance'

write_valid_fixture
sed -i.bak 's/make release-audit/make nothing/' "$tmp/docs/README.md"
expect_rejection 'missing release command'

write_valid_fixture
sed -i.bak 's/pending release gate/passed release gate/' "$tmp/docs/docs/release-evidence/README.md"
expect_rejection 'physical release gate rewritten as passed'

write_valid_fixture
sed -i.bak 's/protected Android producer remains externally blocked/protected Android producer passed/' "$tmp/docs/docs/release-evidence/README.md"
expect_rejection 'protected producer rewritten as passed'

write_valid_fixture
printf '%s\n' 'A device control that disables only the direct route is needed before this can be a host scenario.' >> "$tmp/docs/docs/test-scenarios.md"
expect_rejection 'stale missing direct-route control claim'

write_valid_fixture
sed -i.bak 's/lan-relay-fallback-return/fallback omitted/' "$tmp/docs/docs/test-scenarios.md"
expect_rejection 'missing automated fallback and return scenario'

write_valid_fixture
sed -i.bak 's/lan-restart-persistence/restart omitted/' "$tmp/docs/docs/test-scenarios.md"
expect_rejection 'missing automated restart persistence scenario'

write_valid_fixture
sed -i.bak 's/pending physical two-phone run/passed physical two-phone run/' "$tmp/docs/docs/test-scenarios.md"
expect_rejection 'physical LAN acceptance rewritten as passed'

echo 'project documentation verifier self-test passed'
