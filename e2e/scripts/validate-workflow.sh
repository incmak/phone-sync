#!/usr/bin/env bash
set -Eeuo pipefail
workflow=${1:-.github/workflows/e2e-android.yml}
[[ -f "$workflow" ]] || { echo "workflow missing: $workflow" >&2; exit 1; }
grep -Fq 'workflow_dispatch:' "$workflow" || { echo "workflow_dispatch trigger missing" >&2; exit 1; }
grep -Fq 'scenario:' "$workflow" || { echo "scenario input missing" >&2; exit 1; }
grep -Fq "default: core-correctness" "$workflow" || { echo "manual scenario default must be executable core-correctness" >&2; exit 1; }
grep -Fq "|| 'post'" "$workflow" || { echo "scheduled scenario must default to executable post" >&2; exit 1; }
grep -Fq 'timeout-minutes: 60' "$workflow" || { echo "60 minute timeout missing" >&2; exit 1; }
grep -Fq 'if: always()' "$workflow" || { echo "always artifact upload missing" >&2; exit 1; }
grep -Fq 'actions/checkout@' "$workflow" || { echo "checkout missing" >&2; exit 1; }
grep -Fq 'make verify' "$workflow" || { echo "verify gate missing" >&2; exit 1; }
grep -Fq 'prepare-avds.sh' "$workflow" || { echo "AVD preparation missing" >&2; exit 1; }
grep -Fq 'E2E_SCENARIO' "$workflow" || { echo "scenario forwarding missing" >&2; exit 1; }
grep -Fq 'E2E_KEEP_RUN_DIR' "$workflow" || { echo "run evidence retention missing" >&2; exit 1; }
grep -Fq 'e2e-sanitized' "$workflow" || { echo "sanitized staging missing" >&2; exit 1; }
grep -Fq '/sanitized/' "$workflow" || { echo "sanitized source path missing" >&2; exit 1; }
if grep -Fq 'find /tmp' "$workflow"; then echo "raw tmp discovery is forbidden" >&2; exit 1; fi
for artifact in health-a.json health-b.json state.json timeline.json metrics.json; do grep -Fq "$artifact" "$workflow" || { echo "expected artifact $artifact missing" >&2; exit 1; }; done
if grep -Fq '/tmp/twinotify-e2e.*' "$workflow"; then echo "raw tmp upload is forbidden" >&2; exit 1; fi
echo "workflow validation passed: $workflow"
