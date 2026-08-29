#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
required=(
  PHY-ACTION-REPLY-LAN PHY-ACTION-REPLY-RELAY PHY-ACTION-MARK-READ
  PHY-ACTION-LOCKED PHY-ACTION-DOZE PHY-ACTION-LATE PHY-ACTION-MID-CLAIM
  PHY-ACTION-REBIND PHY-ACTION-TAP-INSTALLED PHY-ACTION-TAP-FALLBACK
  PHY-ACTION-OEM-SHAPING PHY-ACTION-50-DAY-SANITY
)

die() { echo "notification-action-evidence: $*" >&2; exit 1; }
sha256_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

artifact() {
  local base=$1 rel=$2 label=$3 parent resolved
  [[ -n "$rel" && "$rel" != /* && "$rel" != *".."* ]] || die "$label path is unsafe"
  [[ -f "$base/$rel" && ! -L "$base/$rel" && -s "$base/$rel" ]] || die "$label is missing, empty, or a symlink"
  [[ $(wc -c <"$base/$rel") -le 1048576 ]] || die "$label exceeds 1 MiB"
  parent=$(cd "$(dirname "$base/$rel")" && pwd -P) || die "$label parent is invalid"
  resolved="$parent/$(basename "$rel")"
  [[ "$resolved" == "$base"/* ]] || die "$label escapes evidence directory"
  printf '%s\n' "$base/$rel"
}

verify() {
  local base=$1 manifest apk declared_sha actual_sha declared_commit current role count id note shot log
  command -v jq >/dev/null 2>&1 || die "jq is required"
  [[ -d "$base" && ! -L "$base" ]] || die "evidence directory is missing or unsafe"
  base=$(cd "$base" && pwd -P)
  manifest="$base/manifest.json"
  [[ -f "$manifest" && ! -L "$manifest" ]] || die "manifest.json is missing or unsafe"
  jq -e 'type == "object" and keys == (["apk","apk_sha256","devices","git_commit","scenarios","tested_at"] | sort)' "$manifest" >/dev/null || die "manifest root is not the closed contract"
  jq -e '
    [paths(objects) as $p | getpath($p) | keys[]? | ascii_downcase |
      select(test("(^|_)(title|text|reply_text|payload)($|_)"))] | length == 0
  ' "$manifest" >/dev/null || die "manifest contains notification content-bearing keys"
  declared_commit=$(jq -er '.git_commit | select(test("^[0-9a-f]{40}$"))' "$manifest") || die "git_commit must be 40 lowercase hex"
  current=$(git -C "$ROOT" rev-parse HEAD) || die "current git commit is unavailable"
  [[ "$declared_commit" == "$current" ]] || die "git commit mismatch"
  jq -e '.tested_at | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")' "$manifest" >/dev/null || die "tested_at must be UTC ISO-8601"
  apk=$(artifact "$base" "$(jq -er '.apk' "$manifest")" "APK")
  declared_sha=$(jq -er '.apk_sha256 | select(test("^[0-9a-f]{64}$"))' "$manifest") || die "apk_sha256 must be 64 lowercase hex"
  actual_sha=$(sha256_file "$apk")
  [[ "$declared_sha" == "$actual_sha" ]] || die "APK hash mismatch"

  jq -e '.devices | type == "array" and length == 2' "$manifest" >/dev/null || die "exactly two device records are required"
  for role in mi11x poco_f1; do
    count=$(jq --arg role "$role" '[.devices[] | select(.role == $role)] | length' "$manifest")
    [[ "$count" == 1 ]] || die "$role device identity is missing or duplicated"
    jq -e --arg role "$role" '
      .devices[] | select(.role == $role) |
      keys == (["android_version","app_build","model","network_class","oem_build","relay_build","role","route","stable_id_hash"] | sort) and
      (.stable_id_hash | test("^[0-9a-f]{64}$")) and
      ([.model,.android_version,.oem_build,.app_build,.relay_build] | all(.[]; type == "string" and length > 0 and length <= 160)) and
      (.route == "lan" or .route == "relay") and
      (.network_class == "wifi" or .network_class == "mobile" or .network_class == "mixed")
    ' "$manifest" >/dev/null || die "$role device record is malformed"
  done
  jq -e '.devices[] | select(.role=="mi11x") | .model | test("MI 11X|M2012K11AI"; "i")' "$manifest" >/dev/null || die "MI 11X model identity is missing"
  jq -e '.devices[] | select(.role=="poco_f1") | .model | test("POCO F1"; "i")' "$manifest" >/dev/null || die "POCO F1 model identity is missing"

  jq -e '.scenarios | type == "array" and length == 12 and ([.[].id] | unique | length == 12)' "$manifest" >/dev/null || die "scenario inventory must contain 12 unique rows"
  for id in "${required[@]}"; do
    count=$(jq --arg id "$id" '[.scenarios[] | select(.id == $id)] | length' "$manifest")
    [[ "$count" == 1 ]] || die "$id is missing or duplicated"
    jq -e --arg id "$id" '.scenarios[] | select(.id==$id) | keys == (["artifacts","completed_at","id","notes","status"] | sort) and .status == "pass" and (.notes|type=="string" and length>0 and length<=2000) and (.completed_at|test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and (.artifacts|keys==(["log_extract","screenshot"]|sort))' "$manifest" >/dev/null || die "$id must be a complete pass row"
    note=$(jq -er --arg id "$id" '.scenarios[] | select(.id==$id) | .notes' "$manifest")
    [[ -n "${note//[[:space:]]/}" ]] || die "$id operator notes are empty"
    shot=$(jq -er --arg id "$id" '.scenarios[] | select(.id==$id) | .artifacts.screenshot' "$manifest")
    log=$(jq -er --arg id "$id" '.scenarios[] | select(.id==$id) | .artifacts.log_extract' "$manifest")
    artifact "$base" "$shot" "$id screenshot" >/dev/null
    artifact "$base" "$log" "$id log extract" >/dev/null
  done
  echo "notification action evidence passed: $base"
}

self_test() {
  local tmp base current apk_sha id slug
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-action-evidence.XXXXXX")
  trap 'rm -rf -- "$tmp"' RETURN
  base="$tmp/evidence"; mkdir -p "$base/artifacts"
  printf 'fixture apk\n' >"$base/app.apk"
  current=$(git -C "$ROOT" rev-parse HEAD); apk_sha=$(sha256_file "$base/app.apk")
  for id in "${required[@]}"; do slug=$(printf '%s' "$id" | tr '[:upper:]' '[:lower:]'); printf 'image\n' >"$base/artifacts/$slug.png"; printf 'safe status counters\n' >"$base/artifacts/$slug.log"; done
  jq -n --arg commit "$current" --arg sha "$apk_sha" --argjson ids "$(printf '%s\n' "${required[@]}" | jq -R . | jq -s .)" '
    {git_commit:$commit,apk_sha256:$sha,apk:"app.apk",tested_at:"2026-08-29T10:00:00Z",
     devices:[
       {role:"mi11x",stable_id_hash:("a"*64),model:"M2012K11AI / MI 11X",android_version:"14",oem_build:"fixture",app_build:"fixture",relay_build:"fixture",route:"lan",network_class:"wifi"},
       {role:"poco_f1",stable_id_hash:("b"*64),model:"POCO F1",android_version:"13",oem_build:"fixture",app_build:"fixture",relay_build:"fixture",route:"relay",network_class:"wifi"}],
     scenarios:[$ids[] as $id | ($id|ascii_downcase) as $slug | {id:$id,status:"pass",completed_at:"2026-08-29T10:00:00Z",notes:"operator verified",artifacts:{screenshot:("artifacts/"+$slug+".png"),log_extract:("artifacts/"+$slug+".log")}}]}
  ' >"$base/manifest.json"
  verify "$base" >/dev/null

  cp "$base/manifest.json" "$tmp/good.json"
  expect_fail() { local name=$1 filter=$2; jq "$filter" "$tmp/good.json" >"$base/manifest.json"; if ( verify "$base" ) >"$tmp/$name.out" 2>"$tmp/$name.err"; then die "self-test expected $name failure"; fi; }
  expect_fail missing-device 'del(.devices[1])'
  expect_fail hash-mismatch '.apk_sha256=("0"*64)'
  expect_fail missing-row 'del(.scenarios[0])'
  expect_fail skipped '.scenarios[0].status="skipped"'
  expect_fail malformed-time '.scenarios[0].completed_at="2026-08-29"'
  expect_fail content-key '.scenarios[0].reply_text="secret"'
  expect_fail empty-notes '.scenarios[0].notes=""'
  expect_fail absolute-path '.scenarios[0].artifacts.screenshot="/tmp/x"'
  cp "$tmp/good.json" "$base/manifest.json"
  local first; first=$(jq -r '.scenarios[0].artifacts.log_extract' "$base/manifest.json"); rm "$base/$first"; if ( verify "$base" ) >/dev/null 2>"$tmp/missing-artifact.err"; then die "self-test expected missing artifact failure"; fi
  echo "notification action evidence self-test passed"
}

case "${1:-}" in
  --self-test) self_test ;;
  "") die "usage: $0 EVIDENCE_DIR | --self-test" ;;
  *) verify "$1" ;;
esac
