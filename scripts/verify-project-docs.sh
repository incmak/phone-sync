#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=${TWINOTIFY_PROJECT_DOCS_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
ROOT_README="$ROOT_DIR/README.md"
MOBILE_README="$ROOT_DIR/mobile/README.md"
PROTO_README="$ROOT_DIR/proto/README.md"
RELEASE_README="$ROOT_DIR/docs/release-evidence/README.md"

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

reject_pattern() {
  local file=$1 pattern=$2 message=$3
  if grep -Eiq "$pattern" "$file"; then
    die "$message"
  fi
}

for file in "$ROOT_README" "$MOBILE_README" "$PROTO_README" "$RELEASE_README"; do
  [[ -f "$file" ]] || die "required document is missing: $file"
done

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
