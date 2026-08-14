#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEFAULT_ATTESTATION_PUBLIC_KEY="$ROOT_DIR/docs/release-evidence/attestation-public.pem"
ATTESTATION_PUBLIC_KEY="$DEFAULT_ATTESTATION_PUBLIC_KEY"

fail() {
  echo "release-evidence: $*" >&2
  return 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

lowercase() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

die() {
  fail "$*"
  exit 1
}

safe_relpath() {
  local value=$1 label=$2
  [[ -n "$value" && "$value" != /* && "$value" != *".."* ]] || die "$label must be a relative path without .."
}

artifact_path() {
  local rel=$1 label=$2
  safe_relpath "$rel" "$label"
  local path="$EVIDENCE_DIR/$rel"
  [[ -f "$path" ]] || die "$label missing: $rel"
  [[ ! -L "$path" ]] || die "$label must not be a symlink"
  local resolved_dir resolved_path
  resolved_dir=$(cd "$(dirname "$path")" 2>/dev/null && pwd -P) || die "$label parent cannot be resolved"
  resolved_path="$resolved_dir/$(basename "$path")"
  [[ "$resolved_path" == "$EVIDENCE_DIR"/* ]] || die "$label escapes evidence directory"
  printf '%s\n' "$path"
}

verify_manifest() {
  EVIDENCE_DIR=$1
  [[ -d "$EVIDENCE_DIR" ]] || die "evidence directory missing: $EVIDENCE_DIR"
  EVIDENCE_DIR=$(cd "$EVIDENCE_DIR" && pwd -P)
  MANIFEST="$EVIDENCE_DIR/manifest.json"
  [[ -f "$MANIFEST" ]] || die "manifest.json missing"
  [[ ! -L "$MANIFEST" ]] || die "manifest.json must not be a symlink"
  command -v jq >/dev/null 2>&1 || die "jq is required to verify release evidence"
  jq empty "$MANIFEST" >/dev/null || die "manifest.json is not valid JSON"

  local app_sha relay_commit e2e_sha e2e_commit app_commit tested_at app_rel provenance_rel attestation_rel signature_rel result_rel timeline_rel notes_rel
  app_sha=$(jq -er '.app_sha256' "$MANIFEST") || die "app_sha256 missing"
  relay_commit=$(jq -er '.relay_git_commit' "$MANIFEST") || die "relay_git_commit missing"
  e2e_sha=$(jq -er '.e2e_result_sha256' "$MANIFEST") || die "e2e_result_sha256 missing"
  e2e_commit=$(jq -er '.e2e_git_commit' "$MANIFEST") || die "e2e_git_commit missing"
  app_commit=$(jq -er '.app_git_commit' "$MANIFEST") || die "app_git_commit missing"
  tested_at=$(jq -er '.tested_at' "$MANIFEST") || die "tested_at missing"
  app_rel=$(jq -er '.artifacts.app' "$MANIFEST") || die "artifacts.app missing"
  provenance_rel=$(jq -er '.artifacts.app_provenance' "$MANIFEST") || die "artifacts.app_provenance missing"
  attestation_rel=$(jq -er '.artifacts.app_attestation' "$MANIFEST") || die "artifacts.app_attestation missing"
  signature_rel=$(jq -er '.artifacts.app_attestation_signature' "$MANIFEST") || die "artifacts.app_attestation_signature missing"
  result_rel=$(jq -er '.artifacts.e2e_result' "$MANIFEST") || die "artifacts.e2e_result missing"
  timeline_rel=$(jq -er '.artifacts.timeline' "$MANIFEST") || die "artifacts.timeline missing"
  notes_rel=$(jq -er '.artifacts.operator_notes' "$MANIFEST") || die "artifacts.operator_notes missing"

  [[ "$app_sha" =~ ^[0-9a-fA-F]{64}$ ]] || die "app_sha256 must be 64 hex characters"
  [[ "$relay_commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "relay_git_commit must be a 40-character SHA"
  [[ "$e2e_sha" =~ ^[0-9a-fA-F]{64}$ ]] || die "e2e_result_sha256 must be 64 hex characters"
  [[ "$e2e_commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "e2e_git_commit must be a 40-character SHA"
  [[ "$app_commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "app_git_commit must be a 40-character SHA"
  [[ "$tested_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || die "tested_at must be an ISO-8601 UTC timestamp"

  local app_file provenance_file attestation_file signature_file result_file timeline_file notes_file actual_app_sha actual_result_sha current_commit provenance_commit provenance_sha
  app_file=$(artifact_path "$app_rel" "APK artifact")
  provenance_file=$(artifact_path "$provenance_rel" "APK provenance")
  attestation_file=$(artifact_path "$attestation_rel" "APK attestation")
  signature_file=$(artifact_path "$signature_rel" "APK attestation signature")
  result_file=$(artifact_path "$result_rel" "E2E result artifact")
  timeline_file=$(artifact_path "$timeline_rel" "sanitized timeline")
  notes_file=$(artifact_path "$notes_rel" "operator notes")
  actual_app_sha=$(sha256_file "$app_file")
  [[ "$(lowercase "$actual_app_sha")" == "$(lowercase "$app_sha")" ]] || die "build mismatch: APK SHA256 is $actual_app_sha, manifest declares $app_sha"
  provenance_commit=$(jq -er '.git_commit' "$provenance_file") || die "APK provenance git_commit missing"
  provenance_sha=$(jq -er '.app_sha256' "$provenance_file") || die "APK provenance app_sha256 missing"
  [[ "$(lowercase "$provenance_commit")" == "$(lowercase "$app_commit")" ]] || die "APK provenance commit mismatch"
  [[ "$(lowercase "$provenance_sha")" == "$(lowercase "$actual_app_sha")" ]] || die "APK provenance SHA mismatch"
  actual_result_sha=$(sha256_file "$result_file")
  [[ "$(lowercase "$actual_result_sha")" == "$(lowercase "$e2e_sha")" ]] || die "E2E result SHA256 mismatch"

  current_commit=$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null) || die "unable to determine current git commit"
  [[ "$(lowercase "$current_commit")" == "$(lowercase "$relay_commit")" ]] || die "relay commit mismatch: current $current_commit, manifest $relay_commit"
  [[ "$(lowercase "$current_commit")" == "$(lowercase "$e2e_commit")" ]] || die "E2E commit mismatch: current $current_commit, manifest $e2e_commit"
  [[ "$(lowercase "$current_commit")" == "$(lowercase "$app_commit")" ]] || die "APK commit mismatch: current $current_commit, manifest $app_commit"

  command -v openssl >/dev/null 2>&1 || die "openssl is required to verify the release attestation"
  [[ -f "$ATTESTATION_PUBLIC_KEY" ]] || die "pinned attestation public key missing: $ATTESTATION_PUBLIC_KEY"
  openssl dgst -sha256 -verify "$ATTESTATION_PUBLIC_KEY" -signature "$signature_file" "$attestation_file" >/dev/null 2>&1 \
    || die "APK attestation signature invalid"
  jq -e --arg app "$actual_app_sha" --arg commit "$current_commit" --arg relay "$relay_commit" --arg e2e "$e2e_commit" \
    '.app_sha256 == $app and .app_git_commit == $commit and .relay_git_commit == $relay and .e2e_git_commit == $e2e' "$attestation_file" >/dev/null \
    || die "APK attestation claims do not match current APK and commits"

  jq -e --arg expected "$e2e_commit" '.e2e_git_commit == $expected and (.scenarios["all-correctness"] == "pass") and (.scenarios["burst-1000"] == "pass") and (.scenarios["offline-capacity"] == "pass")' "$result_file" >/dev/null \
    || die "E2E result must record the current commit and pass all-correctness, burst-1000, and offline-capacity"

  local role count android
  for role in pixel samsung; do
    count=$(jq --arg role "$role" '[.devices[]? | select(.role == $role)] | length' "$MANIFEST")
    [[ "$count" -eq 1 ]] || die "device role missing or duplicated: $role"
    android=$(jq -er --arg role "$role" '.devices[] | select(.role == $role) | .android' "$MANIFEST")
    [[ "$android" =~ ^[0-9]+$ && "$android" -ge 14 ]] || die "$role device must be Android 14 or newer"
    local battery_rel battery_file
    battery_rel=$(jq -er --arg role "$role" '.artifacts.batterystats[$role]' "$MANIFEST") || die "$role batterystats path missing"
    if [[ ! -f "$EVIDENCE_DIR/$battery_rel" ]]; then die "PHY-BATTERY-01 $role missing: $battery_rel"; fi
    battery_file=$(artifact_path "$battery_rel" "$role batterystats")
    [[ -s "$battery_file" ]] || die "$role batterystats file is empty"
  done

  local scenario
  for scenario in PHY-PAIR-01 PHY-DOZE-01 PHY-OEM-01 PHY-NET-01 PHY-BATTERY-01 PHY-RELIABILITY-01; do
    jq -e --arg id "$scenario" '.scenarios[$id] == "pass"' "$MANIFEST" >/dev/null || die "$scenario must be pass"
  done
  [[ -s "$timeline_file" ]] || die "sanitized timeline is empty"
  [[ -s "$notes_file" ]] || die "operator notes are empty"
  jq -e 'type == "array"' "$timeline_file" >/dev/null || die "sanitized timeline must be a JSON array"

  echo "release evidence passed: $EVIDENCE_DIR"
}

self_test() {
  local tmp app result current app_sha result_sha base
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-release-evidence.XXXXXX")
  trap 'rm -rf -- "$tmp"' RETURN
  base="$tmp/evidence"
  mkdir -p "$base/artifacts/batterystats"
  printf 'debug apk fixture\n' > "$base/artifacts/app.apk"
  printf '[{"scenario":"all-correctness","status":"pass"}]\n' > "$base/artifacts/timeline.json"
  printf 'operator: fixture only\n' > "$base/artifacts/operator-notes.md"
  printf 'pixel batterystats\n' > "$base/artifacts/batterystats/pixel.txt"
  printf 'samsung batterystats\n' > "$base/artifacts/batterystats/samsung.txt"
  current=$(git -C "$ROOT_DIR" rev-parse HEAD)
  printf '{"git_commit":"%s","app_sha256":"pending"}\n' "$current" > "$base/artifacts/app-provenance.json"
  printf '{"e2e_git_commit":"%s","scenarios":{"all-correctness":"pass","burst-1000":"pass","offline-capacity":"pass"}}\n' "$current" > "$base/artifacts/e2e-result.json"
  app_sha=$(sha256_file "$base/artifacts/app.apk")
  result_sha=$(sha256_file "$base/artifacts/e2e-result.json")
  jq --arg app "$app_sha" '.app_sha256 = $app' "$base/artifacts/app-provenance.json" > "$base/artifacts/app-provenance.tmp" && mv "$base/artifacts/app-provenance.tmp" "$base/artifacts/app-provenance.json"
  printf '{"app_sha256":"%s","app_git_commit":"%s","relay_git_commit":"%s","e2e_git_commit":"%s"}\n' "$app_sha" "$current" "$current" "$current" > "$base/artifacts/app-attestation.json"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/attestation-key.pem" >/dev/null 2>&1
  openssl pkey -in "$tmp/attestation-key.pem" -pubout -out "$tmp/attestation-public.pem" >/dev/null 2>&1
  openssl dgst -sha256 -sign "$tmp/attestation-key.pem" -out "$base/artifacts/app-attestation.sig" "$base/artifacts/app-attestation.json"
  jq -n --arg app "$app_sha" --arg relay "$current" --arg result "$result_sha" --arg e2e "$current" \
    '{app_sha256:$app,relay_git_commit:$relay,e2e_result_sha256:$result,e2e_git_commit:$e2e,app_git_commit:$e2e,tested_at:"2026-08-14T00:00:00Z",devices:[{role:"pixel",model:"fixture",android:14,build:"fixture"},{role:"samsung",model:"fixture",android:14,build:"fixture"}],scenarios:{"PHY-PAIR-01":"pass","PHY-DOZE-01":"pass","PHY-OEM-01":"pass","PHY-NET-01":"pass","PHY-BATTERY-01":"pass","PHY-RELIABILITY-01":"pass"},artifacts:{app:"artifacts/app.apk",app_provenance:"artifacts/app-provenance.json",app_attestation:"artifacts/app-attestation.json",app_attestation_signature:"artifacts/app-attestation.sig",e2e_result:"artifacts/e2e-result.json",timeline:"artifacts/timeline.json",operator_notes:"artifacts/operator-notes.md",batterystats:{pixel:"artifacts/batterystats/pixel.txt",samsung:"artifacts/batterystats/samsung.txt"}}}' > "$base/manifest.json"

  rm "$base/artifacts/batterystats/samsung.txt"
  if "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" 2>"$tmp/missing.err"; then die "self-test expected missing Samsung battery failure"; fi
  grep -Fq 'PHY-BATTERY-01 samsung missing' "$tmp/missing.err" || die "self-test missing-battery diagnostic is wrong"
  printf 'samsung batterystats\n' > "$base/artifacts/batterystats/samsung.txt"
  jq '.app_sha256 = ("0" * 64)' "$base/manifest.json" > "$base/manifest.tmp" && mv "$base/manifest.tmp" "$base/manifest.json"
  if "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" 2>"$tmp/mismatch.err"; then die "self-test expected SHA mismatch failure"; fi
  grep -Fq 'build mismatch' "$tmp/mismatch.err" || die "self-test SHA diagnostic is wrong"
  jq --arg app "$app_sha" '.app_sha256 = $app' "$base/manifest.json" > "$base/manifest.tmp" && mv "$base/manifest.tmp" "$base/manifest.json"
  jq '.git_commit = ("0" * 40)' "$base/artifacts/app-provenance.json" > "$base/artifacts/app-provenance.tmp" && mv "$base/artifacts/app-provenance.tmp" "$base/artifacts/app-provenance.json"
  if "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" 2>"$tmp/provenance.err"; then die "self-test expected APK provenance mismatch failure"; fi
  grep -Fq 'APK provenance commit mismatch' "$tmp/provenance.err" || die "self-test provenance diagnostic is wrong"
  jq --arg commit "$current" '.git_commit = $commit' "$base/artifacts/app-provenance.json" > "$base/artifacts/app-provenance.tmp" && mv "$base/artifacts/app-provenance.tmp" "$base/artifacts/app-provenance.json"
  outside="$tmp/outside-timeline.json"
  printf '[]\n' > "$outside"
  rm "$base/artifacts/timeline.json"
  ln -s "$outside" "$base/artifacts/timeline.json"
  if "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" 2>"$tmp/symlink.err"; then die "self-test expected symlink escape failure"; fi
  grep -Fq 'sanitized timeline must not be a symlink' "$tmp/symlink.err" || die "self-test symlink diagnostic is wrong"
  rm "$base/artifacts/timeline.json"
  printf '[]\n' > "$base/artifacts/timeline.json"
  mkdir -p "$tmp/outside-dir"
  printf '[]\n' > "$tmp/outside-dir/timeline.json"
  ln -s "$tmp/outside-dir" "$base/artifacts/escape-dir"
  jq '.artifacts.timeline = "artifacts/escape-dir/timeline.json"' "$base/manifest.json" > "$base/manifest.tmp" && mv "$base/manifest.tmp" "$base/manifest.json"
  if "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" 2>"$tmp/escape.err"; then die "self-test expected symlink directory escape failure"; fi
  grep -Fq 'sanitized timeline escapes evidence directory' "$tmp/escape.err" || die "self-test symlink escape diagnostic is wrong"
  rm "$base/artifacts/escape-dir"
  jq '.artifacts.timeline = "artifacts/timeline.json"' "$base/manifest.json" > "$base/manifest.tmp" && mv "$base/manifest.tmp" "$base/manifest.json"
  printf 'x' >> "$base/artifacts/app-attestation.json"
  if "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" 2>"$tmp/attestation.err"; then die "self-test expected attestation signature failure"; fi
  grep -Fq 'APK attestation signature invalid' "$tmp/attestation.err" || die "self-test attestation diagnostic is wrong"
  printf '{"app_sha256":"%s","app_git_commit":"%s","relay_git_commit":"%s","e2e_git_commit":"%s"}\n' "$app_sha" "$current" "$current" "$current" > "$base/artifacts/app-attestation.json"
  openssl dgst -sha256 -sign "$tmp/attestation-key.pem" -out "$base/artifacts/app-attestation.sig" "$base/artifacts/app-attestation.json"
  "$ROOT_DIR/scripts/verify-release-evidence.sh" --self-test-key "$tmp/attestation-public.pem" "$base" >/dev/null
  if RELEASE_EVIDENCE_SELF_TEST=1 RELEASE_ATTESTATION_PUBLIC_KEY="$tmp/attestation-public.pem" "$ROOT_DIR/scripts/verify-release-evidence.sh" "$base" 2>"$tmp/override.err"; then die "self-test expected normal override rejection"; fi
  grep -Fq 'attestation key overrides are only accepted' "$tmp/override.err" || die "self-test override rejection diagnostic is wrong"
  echo "release evidence self-test passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit 0
fi
if [[ "${1:-}" == "--self-test-key" ]]; then
  [[ $# -eq 3 && -f "$2" ]] || { echo "usage: $0 --self-test-key <public-key.pem> <evidence-dir>" >&2; exit 2; }
  ATTESTATION_PUBLIC_KEY="$2"
  verify_manifest "$3"
  exit 0
fi
[[ $# -eq 1 ]] || { echo "usage: $0 <evidence-dir> | --self-test" >&2; exit 2; }
if [[ -n "${RELEASE_EVIDENCE_SELF_TEST:-}" || -n "${RELEASE_ATTESTATION_PUBLIC_KEY:-}" ]]; then
  die "attestation key overrides are only accepted by the internal --self-test-key mode"
fi
verify_manifest "$1"
