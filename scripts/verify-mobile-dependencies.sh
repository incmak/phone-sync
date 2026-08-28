#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MOBILE_DIR="$ROOT_DIR/mobile"

die() {
  echo "mobile-dependency-audit: $*" >&2
  exit 1
}

validate_report() {
  local report=$1

  jq -e '
    def nonnegative_integer:
      type == "number" and . >= 0 and floor == .;
    def count_severity($severity):
      [.vulnerabilities[] | select(.severity == $severity)] | length;

    type == "object" and
    .auditReportVersion == 2 and
    (.vulnerabilities | type == "object") and
    (.metadata | type == "object") and
    (.metadata.vulnerabilities | type == "object") and
    (.metadata.dependencies | type == "object") and
    (.metadata.dependencies.total | nonnegative_integer) and
    .metadata.dependencies.total > 0 and
    ([.vulnerabilities[] |
      type == "object" and
      (.name | type == "string" and length > 0) and
      (.severity as $severity |
        ["info", "low", "moderate", "high", "critical"] |
        index($severity) != null)
    ] | all) and
    (.metadata.vulnerabilities.info | nonnegative_integer) and
    (.metadata.vulnerabilities.low | nonnegative_integer) and
    (.metadata.vulnerabilities.moderate | nonnegative_integer) and
    (.metadata.vulnerabilities.high | nonnegative_integer) and
    (.metadata.vulnerabilities.critical | nonnegative_integer) and
    (.metadata.vulnerabilities.total | nonnegative_integer) and
    .metadata.vulnerabilities.info == count_severity("info") and
    .metadata.vulnerabilities.low == count_severity("low") and
    .metadata.vulnerabilities.moderate == count_severity("moderate") and
    .metadata.vulnerabilities.high == count_severity("high") and
    .metadata.vulnerabilities.critical == count_severity("critical") and
    .metadata.vulnerabilities.total == (.vulnerabilities | length) and
    .metadata.vulnerabilities.total == (
      .metadata.vulnerabilities.info +
      .metadata.vulnerabilities.low +
      .metadata.vulnerabilities.moderate +
      .metadata.vulnerabilities.high +
      .metadata.vulnerabilities.critical
    )
  ' "$report" >/dev/null 2>&1 || die "audit report is malformed, empty, or internally inconsistent"
}

check_report() {
  local report=$1
  local audit_status=${2:-0}
  local high critical dependencies

  [[ -f "$report" ]] || die "audit report is missing"
  validate_report "$report"
  high=$(jq -er '.metadata.vulnerabilities.high' "$report")
  critical=$(jq -er '.metadata.vulnerabilities.critical' "$report")
  dependencies=$(jq -er '.metadata.dependencies.total' "$report")

  if (( high > 0 || critical > 0 )); then
    die "high=$high critical=$critical advisories block protected release"
  fi
  [[ $audit_status -eq 0 ]] || die "npm audit command failed despite a zero-high report"

  printf 'mobile dependency audit passed: high=0 critical=0 dependencies=%s\n' "$dependencies"
}

require_full_tree_online_config() {
  local omit production offline

  omit=$(cd "$MOBILE_DIR" && npm config get omit 2>/dev/null) ||
    die "could not resolve npm omit configuration"
  production=$(cd "$MOBILE_DIR" && npm config get production 2>/dev/null) ||
    die "could not resolve npm production configuration"
  offline=$(cd "$MOBILE_DIR" && npm config get offline 2>/dev/null) ||
    die "could not resolve npm offline configuration"

  [[ -z "$omit" ]] || die "npm omit configuration must be empty for a full-tree audit"
  [[ "$production" == "null" || "$production" == "false" ]] ||
    die "npm production configuration must be disabled for a full-tree audit"
  [[ "$offline" == "false" ]] || die "npm offline configuration must be false for a fresh audit"
}

command -v jq >/dev/null 2>&1 || die "jq is required"

if [[ $# -eq 2 && $1 == "--check-json" ]]; then
  check_report "$2"
  exit 0
fi

[[ $# -eq 0 ]] || die "usage: verify-mobile-dependencies.sh [--check-json REPORT]"
command -v npm >/dev/null 2>&1 || die "npm is required"
[[ -f "$MOBILE_DIR/package-lock.json" ]] || die "mobile/package-lock.json is missing"
require_full_tree_online_config

REPORT_FILE=$(mktemp)
ERROR_FILE=$(mktemp)
trap 'rm -f "$REPORT_FILE" "$ERROR_FILE"' EXIT

set +e
(
  cd "$MOBILE_DIR"
  npm audit --audit-level=high --json
) >"$REPORT_FILE" 2>"$ERROR_FILE"
AUDIT_STATUS=$?
set -e

if ! jq -e . "$REPORT_FILE" >/dev/null 2>&1; then
  die "npm audit failed or returned invalid JSON"
fi

check_report "$REPORT_FILE" "$AUDIT_STATUS"
