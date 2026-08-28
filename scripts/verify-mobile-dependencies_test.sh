#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERIFIER="$ROOT_DIR/scripts/verify-mobile-dependencies.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
  echo "mobile dependency verifier self-test: $*" >&2
  exit 1
}

expect_pass() {
  local label=$1
  shift
  "$@" >"$TEST_ROOT/$label.out" 2>"$TEST_ROOT/$label.err" ||
    fail "$label should pass"
}

expect_fail() {
  local label=$1
  shift
  if "$@" >"$TEST_ROOT/$label.out" 2>"$TEST_ROOT/$label.err"; then
    fail "$label should fail"
  fi
}

write_report() {
  local path=$1 severity=$2 metadata_high=$3 metadata_critical=$4 dependency_total=$5
  if [[ "$severity" == "none" ]]; then
    jq -n \
      --argjson high "$metadata_high" \
      --argjson critical "$metadata_critical" \
      --argjson dependencies "$dependency_total" \
      '{auditReportVersion:2,vulnerabilities:{},metadata:{vulnerabilities:{info:0,low:0,moderate:0,high:$high,critical:$critical,total:($high+$critical)},dependencies:{prod:$dependencies,dev:0,optional:0,peer:0,peerOptional:0,total:$dependencies}}}' \
      >"$path"
    return
  fi

  jq -n \
    --arg severity "$severity" \
    --argjson high "$metadata_high" \
    --argjson critical "$metadata_critical" \
    --argjson dependencies "$dependency_total" \
    '{auditReportVersion:2,vulnerabilities:{"fixture-package":{name:"fixture-package",severity:$severity,isDirect:false,via:[],effects:[],range:"*",nodes:["node_modules/fixture-package"],fixAvailable:true}},metadata:{vulnerabilities:{info:0,low:0,moderate:0,high:$high,critical:$critical,total:($high+$critical)},dependencies:{prod:$dependencies,dev:0,optional:0,peer:0,peerOptional:0,total:$dependencies}}}' \
    >"$path"
}

command -v jq >/dev/null 2>&1 || fail "jq is required"
[[ -x "$VERIFIER" ]] || fail "verifier is missing or not executable"

write_report "$TEST_ROOT/clean.json" none 0 0 12
write_report "$TEST_ROOT/high.json" high 1 0 12
write_report "$TEST_ROOT/critical.json" critical 0 1 12
write_report "$TEST_ROOT/disagreement.json" high 0 0 12
write_report "$TEST_ROOT/empty.json" none 0 0 0
printf '{not-json\n' >"$TEST_ROOT/malformed.json"

expect_pass clean "$VERIFIER" --check-json "$TEST_ROOT/clean.json"
expect_fail high "$VERIFIER" --check-json "$TEST_ROOT/high.json"
expect_fail critical "$VERIFIER" --check-json "$TEST_ROOT/critical.json"
expect_fail disagreement "$VERIFIER" --check-json "$TEST_ROOT/disagreement.json"
expect_fail malformed "$VERIFIER" --check-json "$TEST_ROOT/malformed.json"
expect_fail empty "$VERIFIER" --check-json "$TEST_ROOT/empty.json"
expect_fail missing-file "$VERIFIER" --check-json "$TEST_ROOT/missing.json"
expect_fail filtered-arguments "$VERIFIER" --omit=dev

mkdir -p "$TEST_ROOT/project/mobile" "$TEST_ROOT/project/scripts" "$TEST_ROOT/bin"
cp "$ROOT_DIR/mobile/package-lock.json" "$TEST_ROOT/project/mobile/package-lock.json"
cp "$VERIFIER" "$TEST_ROOT/project/scripts/verify-mobile-dependencies.sh"
chmod +x "$TEST_ROOT/project/scripts/verify-mobile-dependencies.sh"
TEST_VERIFIER="$TEST_ROOT/project/scripts/verify-mobile-dependencies.sh"

cat >"$TEST_ROOT/bin/npm" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  "config get omit") printf '\n' ;;
  "config get production") printf 'null\n' ;;
  "config get offline") printf 'false\n' ;;
  "audit --audit-level=high --json")
    printf '%s\n' '{"error":{"code":"ENETUNREACH","summary":"network unavailable"}}'
    exit 1
    ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/npm"

expect_fail command-failure env \
  PATH="$TEST_ROOT/bin:$PATH" \
  "$TEST_VERIFIER"

cat >"$TEST_ROOT/bin/npm" <<EOF
#!/usr/bin/env bash
case "\$*" in
  "config get omit")
    if [[ -n "\${NPM_CONFIG_OMIT:-}" ]]; then
      printf '%s\n' "\$NPM_CONFIG_OMIT"
    elif [[ "\${NODE_ENV:-}" == "production" || "\${NPM_CONFIG_PRODUCTION:-}" == "true" ]]; then
      printf 'dev\n'
    elif [[ -f .npmrc ]] && grep -Eq '^[[:space:]]*omit[[:space:]]*=' .npmrc; then
      sed -nE 's/^[[:space:]]*omit[[:space:]]*=[[:space:]]*(.*)[[:space:]]*$/\1/p' .npmrc | tail -n 1
    else
      printf '\n'
    fi
    ;;
  "config get production")
    printf '%s\n' "\${NPM_CONFIG_PRODUCTION:-null}"
    ;;
  "config get offline")
    if [[ -n "\${NPM_CONFIG_OFFLINE:-}" ]]; then
      printf '%s\n' "\$NPM_CONFIG_OFFLINE"
    elif [[ -f .npmrc ]] && grep -Eq '^[[:space:]]*offline[[:space:]]*=' .npmrc; then
      sed -nE 's/^[[:space:]]*offline[[:space:]]*=[[:space:]]*(.*)[[:space:]]*$/\1/p' .npmrc | tail -n 1
    else
      printf 'false\n'
    fi
    ;;
  "audit --audit-level=high --json")
    cat '$TEST_ROOT/clean.json'
    ;;
  *)
    exit 64
    ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/npm"

expect_pass normal-clean env \
  PATH="$TEST_ROOT/bin:$PATH" \
  "$TEST_VERIFIER"

expect_fail configured-omit env \
  PATH="$TEST_ROOT/bin:$PATH" \
  NPM_CONFIG_OMIT=dev \
  "$TEST_VERIFIER"

expect_fail configured-offline env \
  PATH="$TEST_ROOT/bin:$PATH" \
  NPM_CONFIG_OFFLINE=true \
  "$TEST_VERIFIER"

expect_fail production-node-env env \
  PATH="$TEST_ROOT/bin:$PATH" \
  NODE_ENV=production \
  "$TEST_VERIFIER"

printf 'omit=dev\noffline=true\n' >"$TEST_ROOT/project/mobile/.npmrc"
expect_fail project-npmrc-filter env \
  PATH="$TEST_ROOT/bin:$PATH" \
  "$TEST_VERIFIER"
rm -f "$TEST_ROOT/project/mobile/.npmrc"

if rg -q 'fixture-package|node_modules' "$TEST_ROOT/normal-clean.out"; then
  fail "normal success output leaked the dependency tree"
fi

cat >"$TEST_ROOT/bin/npm" <<EOF
#!/usr/bin/env bash
case "\$*" in
  "config get omit") printf '\n' ;;
  "config get production") printf 'null\n' ;;
  "config get offline") printf 'false\n' ;;
  "audit --audit-level=high --json")
    cat '$TEST_ROOT/clean.json'
    exit 1
    ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/npm"

expect_fail nonzero-clean-report env \
  PATH="$TEST_ROOT/bin:$PATH" \
  "$TEST_VERIFIER"

if rg -q 'audit passed' "$TEST_ROOT/nonzero-clean-report.out"; then
  fail "nonzero npm audit printed a misleading success message"
fi

echo "mobile dependency verifier self-test passed"
