#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_PROJECT_DOCS_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
ROOT_README="$ROOT_DIR/README.md"
MOBILE_README="$ROOT_DIR/mobile/README.md"
PROTO_README="$ROOT_DIR/proto/README.md"
RELEASE_README="$ROOT_DIR/docs/release-evidence/README.md"
TEST_SCENARIOS="$ROOT_DIR/docs/test-scenarios.md"
ROOT_AGENTS="$ROOT_DIR/AGENTS.md"

die() {
  echo "project documentation check failed: $*" >&2
  exit 1
}

require_literal() {
  local file=$1 text=$2 message=$3
  grep -Fq "$text" "$file" || die "$message"
}

require_pattern() {
  local file=$1 pattern=$2 message=$3
  grep -Eiq "$pattern" "$file" || die "$message"
}

require_paragraph_pattern() {
  local file=$1 pattern=$2 message=$3
  awk -v pattern="$pattern" '
    function check_paragraph(  normalized) {
      normalized = paragraph
      gsub(/[[:space:]]+/, " ", normalized)
      if (tolower(normalized) ~ pattern) found = 1
    }
    /^[[:space:]]*$/ {
      if (paragraph != "") check_paragraph()
      paragraph = ""
      next
    }
    { paragraph = paragraph $0 "\n" }
    END {
      if (paragraph != "") check_paragraph()
      exit(found ? 0 : 1)
    }
  ' "$file" || die "$message"
}

reject_pattern() {
  local file=$1 pattern=$2 message=$3
  if grep -Eiq "$pattern" "$file"; then
    die "$message"
  fi
}

for file in "$ROOT_README" "$MOBILE_README" "$PROTO_README" "$RELEASE_README" "$TEST_SCENARIOS" "$ROOT_AGENTS"; do
  [[ -f "$file" ]] || die "required document is missing: $file"
done

require_literal "$ROOT_AGENTS" 'Expo SDK 57 / React Native 0.86' 'root AGENTS.md must name Expo SDK 57 and React Native 0.86'
reject_pattern "$ROOT_AGENTS" 'Expo[[:space:]]+SDK[[:space:]]+54' 'root AGENTS.md must not retain stale Expo SDK 54 guidance'
require_pattern "$ROOT_AGENTS" 'Room[[:space:]]+(is[[:space:]]+at[[:space:]]+)?version[[:space:]]+7' 'root AGENTS.md must name Room version 7'
require_literal "$ROOT_AGENTS" 'Migration(7,8)' 'root AGENTS.md must require the next Room Migration(7,8)'
require_pattern "$ROOT_AGENTS" 'schemas?[^0-9]*8\.json' 'root AGENTS.md must require the next committed Room schema 8.json'
reject_pattern "$ROOT_AGENTS" 'Room[[:space:]]+(is[[:space:]]+at[[:space:]]+)?version[[:space:]]+5|Migration\(5,6\)' 'root AGENTS.md must not retain stale Room 5-to-6 guidance'
require_paragraph_pattern "$ROOT_AGENTS" 'tasks[[:space:]]+1-9.*implementation and host automation.*complete' 'root AGENTS.md must record direct-LAN Tasks 1-9 source and host completion'
require_literal "$ROOT_AGENTS" 'pending physical two-phone run' 'root AGENTS.md must preserve pending physical two-phone direct-LAN evidence'
reject_pattern "$ROOT_AGENTS" 'Tasks[[:space:]]+1-4[[:space:]]+landed;[[:space:]]+5-9[[:space:]]+open|complete(d)?[[:space:]]+physical[[:space:]]+two-phone[[:space:]]+run' 'root AGENTS.md must not reopen direct-LAN tasks or complete pending hardware evidence'
require_literal "$ROOT_AGENTS" 'tracks Plans 001-030' 'root AGENTS.md must name the advisor ledger through Plan 030'
reject_pattern "$ROOT_AGENTS" 'tracks[[:space:]]+the[[:space:]]+audit-driven[[:space:]]+plans[[:space:]]+001-010' 'root AGENTS.md must not retain the stale advisor ledger range'
require_paragraph_pattern "$ROOT_AGENTS" 'plan[[:space:]]+004.*externally blocked.*eas.*(project|signing|token|certificate|attestation)' 'root AGENTS.md must preserve Plan 004 protected EAS block'
require_paragraph_pattern "$ROOT_AGENTS" 'plan[[:space:]]+015.*source is complete.*phy-call-01.*physical proof is deferred' 'root AGENTS.md must preserve Plan 015 deferred physical-call proof'
require_literal "$ROOT_AGENTS" 'Local APKs are QA artifacts, not protected release candidates.' 'root AGENTS.md must preserve local APK release status'
reject_pattern "$ROOT_AGENTS" 'Plan[[:space:]]+004[[:space:]]+is[[:space:]]+complete' 'root AGENTS.md must not complete protected EAS work'

reject_pattern "$MOBILE_README" 'Expo[[:space:]]+Go[[:space:]]+(is|provides|offers|supports|can)[[:space:]]+(a|the|for|recommended|available|supported|used|required)' 'mobile README must not recommend Expo Go'
reject_pattern "$MOBILE_README" '(run|open|use)[[:space:]]+(the[[:space:]]+)?Expo[[:space:]]+Go' 'mobile README must not recommend Expo Go'
reject_pattern "$MOBILE_README" 'npm[[:space:]]+install' 'mobile README must use npm ci, not npm install'
reject_pattern "$MOBILE_README" 'reset-project' 'mobile README must not recommend reset-project'

reject_pattern "$PROTO_README" 'Desktop[[:space:]]+Rust' 'protocol README must not name a nonexistent Desktop Rust consumer'
require_pattern "$PROTO_README" 'v1' 'protocol README must document retained v1 compatibility'
require_pattern "$PROTO_README" 'v2' 'protocol README must document current v2 delivery'

require_pattern "$ROOT_README" 'custom[[:space:]]+(Kotlin[[:space:]]+)?native[[:space:]-]*module' 'root README must name the custom native module'
require_pattern "$ROOT_README" 'development[[:space:]-]*build' 'root README must require a development build'
for command in 'make host-verify' 'make verify' 'make e2e-emulator' 'make release-audit'; do
  require_literal "$ROOT_README" "$command" "root README must document $command"
done

require_pattern "$RELEASE_README" 'physical.*pending|pending.*physical' 'release README must preserve the pending physical-release gate'
require_pattern "$RELEASE_README" 'protected.*(externally blocked|not yet linked)|not yet linked.*protected' 'release README must preserve the protected-release pending state'

require_literal "$TEST_SCENARIOS" 'lan-relay-fallback-return' 'test scenarios must document automated LAN fallback and return'
require_literal "$TEST_SCENARIOS" 'lan-restart-persistence' 'test scenarios must document automated two-sided restart persistence'
require_pattern "$TEST_SCENARIOS" 'pending physical two-phone run|physical two-phone run.*pending' 'test scenarios must preserve pending physical LAN acceptance'
reject_pattern "$TEST_SCENARIOS" 'device control that disables only the direct route is needed' 'test scenarios must not claim the implemented direct-route control is missing'

manifest=$(mktemp "${TMPDIR:-/tmp}/twinotify-project-docs-manifest.XXXXXX")
trap 'rm -f -- "$manifest"' EXIT
awk '
  /^```json[[:space:]]*$/ && !started { started = 1; next }
  started && /^```[[:space:]]*$/ { ended = 1; exit }
  started { print }
  END { if (!started || !ended) exit 1 }
' "$RELEASE_README" > "$manifest" || die 'release README must contain a closed fenced JSON manifest example'

jq -e . "$manifest" >/dev/null || die 'release manifest example must be valid JSON'
jq -e --argjson expected '["PHY-BATTERY-01","PHY-CALL-01","PHY-DOZE-01","PHY-NET-01","PHY-OEM-01","PHY-PAIR-01","PHY-RELIABILITY-01"]' \
  '(.scenarios | type == "object") and ((.scenarios | keys | sort) == $expected)' \
  "$manifest" >/dev/null || die 'release manifest example must contain exactly the seven required physical scenario IDs'

echo 'project documentation validation passed'
