#!/usr/bin/env bash
set -Eeuo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ -x "$root/scripts/verify-notification-action-evidence.sh" ]] || { echo "notification action verifier missing" >&2; exit 1; }
"$root/scripts/verify-notification-action-evidence.sh" --self-test
