#!/usr/bin/env bash
set -euo pipefail

die() { echo "offline-pairing-evidence: $*" >&2; exit 1; }
sha_pattern='^[0-9a-f]{64}$'

verify() {
  local evidence_dir=$1 result_file="$1/offline-pairing.json"
  [[ -d "$evidence_dir" && ! -L "$evidence_dir" ]] || die "evidence directory missing or unsafe"
  [[ -f "$result_file" && ! -L "$result_file" ]] || die "offline-pairing.json missing or unsafe"
  [[ $(wc -c < "$result_file") -le 16384 ]] || die "result exceeds size bound"
  command -v jq >/dev/null 2>&1 || die "jq is required"
  jq -e . "$result_file" >/dev/null || die "result is not valid JSON"

  jq -e '
    (keys - ["result","serial_a_hash","serial_b_hash","wifi_network_hash","device_a","device_b","topology","mobile_data_disabled","process_restart_persisted","relay_required","laptop_service_required"] | length) == 0 and
    (.topology | keys - ["internet_blocked","packet_evidence_sha256","dns_evidence_sha256"] | length) == 0 and
    ([.device_a,.device_b] | all(
      (keys - ["role","phase","error_code","completed","session_id_hash","sas_hash","device_application_identity_hash","peer_application_identity_hash","lan_binding_present","local_tls_pin_hash","peer_tls_pin_hash"] | length) == 0
    ))
  ' "$result_file" >/dev/null || die "result contains non-contract fields"

  local forbidden
  forbidden=$(jq -r '[paths(scalars) as $p | $p[-1] | strings | ascii_downcase | select(. == "qr" or . == "sas" or . == "session_id" or . == "session_token" or . == "transcript" or . == "secret" or . == "lan_secret")] | length' "$result_file")
  [[ "$forbidden" == 0 ]] || die "result contains forbidden ceremony field"
  ! grep -Eqi '(^|[^a-z])(ssid|bssid)([^a-z]|$)|([0-9]{1,3}\.){3}[0-9]{1,3}' "$result_file" || die "result contains raw Wi-Fi identity or IP"

  jq -e '
    .result == "pass" and
    .mobile_data_disabled == true and
    .process_restart_persisted == true and
    .relay_required == false and
    .laptop_service_required == false and
    .topology.internet_blocked == true and
    .device_a.completed == true and .device_b.completed == true and
    .device_a.lan_binding_present == true and .device_b.lan_binding_present == true and
    .device_a.device_application_identity_hash == .device_b.peer_application_identity_hash and
    .device_b.device_application_identity_hash == .device_a.peer_application_identity_hash and
    .device_a.local_tls_pin_hash == .device_b.peer_tls_pin_hash and
    .device_b.local_tls_pin_hash == .device_a.peer_tls_pin_hash and
    .serial_a_hash != .serial_b_hash
  ' "$result_file" >/dev/null || die "result assertions do not match"

  local value
  while IFS= read -r value; do
    [[ "$value" =~ $sha_pattern ]] || die "missing or malformed evidence hash"
  done < <(jq -r '[.serial_a_hash,.serial_b_hash,.wifi_network_hash,.topology.packet_evidence_sha256,.topology.dns_evidence_sha256,.device_a.device_application_identity_hash,.device_a.peer_application_identity_hash,.device_a.local_tls_pin_hash,.device_a.peer_tls_pin_hash,.device_b.device_application_identity_hash,.device_b.peer_application_identity_hash,.device_b.local_tls_pin_hash,.device_b.peer_tls_pin_hash][]' "$result_file")
  echo "offline pairing evidence passed: $evidence_dir"
}

self_test() {
  local tmp base valid
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-offline-evidence.XXXXXX")
  trap 'rm -rf "$tmp"' EXIT
  base="$tmp/evidence"
  mkdir -m 700 "$base"
  valid='{"result":"pass","serial_a_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","serial_b_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","wifi_network_hash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","device_a":{"phase":"complete","completed":true,"device_application_identity_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","peer_application_identity_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","lan_binding_present":true,"local_tls_pin_hash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","peer_tls_pin_hash":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"},"device_b":{"phase":"complete","completed":true,"device_application_identity_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","peer_application_identity_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","lan_binding_present":true,"local_tls_pin_hash":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","peer_tls_pin_hash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"topology":{"internet_blocked":true,"packet_evidence_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","dns_evidence_sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"mobile_data_disabled":true,"process_restart_persisted":true,"relay_required":false,"laptop_service_required":false}'

  if "$0" "$base" 2>"$tmp/missing.err"; then die "self-test expected missing-file failure"; fi
  printf '%s\n' "$valid" > "$base/offline-pairing.json"
  verify "$base" >/dev/null
  jq '.device_b.peer_application_identity_hash="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"' "$base/offline-pairing.json" > "$tmp/mismatch.json"
  mv "$tmp/mismatch.json" "$base/offline-pairing.json"
  if "$0" "$base" 2>"$tmp/mismatch.err"; then die "self-test expected identity-mismatch failure"; fi
  printf '%s\n' "$valid" | jq '.session_token="fixture-private-value"' > "$base/offline-pairing.json"
  if "$0" "$base" 2>"$tmp/secret.err"; then die "self-test expected secret-field failure"; fi
  printf '%s\n' "$valid" > "$base/offline-pairing.json"
  verify "$base" >/dev/null
  echo "offline pairing evidence self-test passed"
  trap - EXIT
  rm -rf "$tmp"
}

if [[ ${1:-} == "--self-test" && $# -eq 1 ]]; then self_test; exit 0; fi
[[ $# -eq 1 ]] || { echo "usage: $0 <evidence-dir> | --self-test" >&2; exit 2; }
verify "$1"
