#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
EXPECTED_PACKAGE="com.twinotify.app"

die() {
  echo "standalone-android: $*" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

lowercase() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

extract_signer_sha256_digests() {
  sed -nE \
    -e 's/^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$/\1/p' \
    -e 's/^V[0-9]+(\.[0-9]+)? Signer: certificate SHA-256 digest: ([0-9a-fA-F]{64})$/\2/p' \
    -e 's/^V[0-9]+(\.[0-9]+)? Signer: \([^)]*\) certificate SHA-256 digest: ([0-9a-fA-F]{64})$/\2/p'
}

find_android_tool() {
  local tool=$1 sdk candidate
  if command -v "$tool" >/dev/null 2>&1; then
    command -v "$tool"
    return
  fi
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [[ -z "$sdk" && -d "$HOME/Library/Android/sdk" ]]; then
    sdk="$HOME/Library/Android/sdk"
  fi
  [[ -n "$sdk" && -d "$sdk/build-tools" ]] || return 1
  candidate=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool" | sort | tail -n 1)
  [[ -n "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

verify_apk() {
  local apk=$1 provenance=$2 expected_cert=$3 expected_commit=$4
  [[ -f "$apk" && ! -L "$apk" ]] || die "APK must be a regular, non-symlink file"
  [[ -f "$provenance" && ! -L "$provenance" ]] || die "provenance must be a regular, non-symlink file"
  [[ "$expected_cert" =~ ^[0-9a-fA-F]{64}$ ]] || die "expected certificate SHA-256 must be supplied as 64 hex characters"
  [[ "$expected_commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "expected commit must be a full 40-character Git SHA"

  command -v unzip >/dev/null 2>&1 || die "unzip is required"
  command -v jq >/dev/null 2>&1 || die "jq is required"
  local apksigner aapt2
  apksigner=$(find_android_tool apksigner) || die "apksigner is required"
  aapt2=$(find_android_tool aapt2) || die "aapt2 is required"

  unzip -tq "$apk" >/dev/null 2>&1 || die "APK archive is malformed"

  local cert_output cert_count actual_cert
  cert_output=$("$apksigner" verify --verbose --print-certs "$apk" 2>&1) || die "APK signature verification failed"
  actual_cert=$(printf '%s\n' "$cert_output" | extract_signer_sha256_digests | sort -u)
  cert_count=$(printf '%s\n' "$actual_cert" | grep -Ec '^[0-9a-fA-F]{64}$' || true)
  [[ "$cert_count" -eq 1 ]] || die "APK must have exactly one verified signer SHA-256 digest"
  [[ "$(lowercase "$actual_cert")" == "$(lowercase "$expected_cert")" ]] || die "APK signer certificate SHA-256 does not match the protected expected fingerprint"

  local badging package_name
  badging=$("$aapt2" dump badging "$apk" 2>/dev/null) || die "unable to read APK package metadata"
  package_name=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
  [[ "$package_name" == "$EXPECTED_PACKAGE" ]] || die "unexpected application ID: ${package_name:-missing}"

  local archive_listing
  archive_listing=$(unzip -Z1 "$apk") || die "unable to list APK archive"
  grep -Fxq 'assets/index.android.bundle' <<<"$archive_listing" || die "production JavaScript bundle is missing"

  local manifest
  manifest=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null) || die "unable to inspect release manifest"
  # Every debug-only surface lives under co.twinotify.core.e2e, so the whole
  # package is rejected rather than a hand-maintained list of class names that
  # a new control (BluetoothRouteControl, for one) could be added outside of.
  if grep -Eq 'co\.twinotify\.core\.e2e\.[A-Za-z0-9_$]+|co\.twinotify\.e2e\.CONTROL|com\.twinotify\.app\.e2e' <<<"$manifest"; then
    die "release manifest contains a debug E2E receiver or provider"
  fi

  jq empty "$provenance" >/dev/null 2>&1 || die "provenance is not valid JSON"
  local provenance_commit provenance_sha actual_sha
  provenance_commit=$(jq -er '.git_commit' "$provenance") || die "provenance git_commit is missing"
  provenance_sha=$(jq -er '.app_sha256' "$provenance") || die "provenance app_sha256 is missing"
  [[ "$provenance_commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "provenance git_commit must be a full SHA"
  [[ "$provenance_sha" =~ ^[0-9a-fA-F]{64}$ ]] || die "provenance app_sha256 must be 64 hex characters"
  [[ "$(lowercase "$provenance_commit")" == "$(lowercase "$expected_commit")" ]] || die "provenance commit does not match the expected commit"
  actual_sha=$(sha256_file "$apk")
  [[ "$(lowercase "$provenance_sha")" == "$(lowercase "$actual_sha")" ]] || die "provenance APK SHA-256 does not match the artifact bytes"

  echo "standalone Android APK passed: $apk"
}

find_android_platform() {
  local sdk candidate
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [[ -z "$sdk" && -d "$HOME/Library/Android/sdk" ]]; then
    sdk="$HOME/Library/Android/sdk"
  fi
  [[ -n "$sdk" && -d "$sdk/platforms" ]] || return 1
  candidate=$(find "$sdk/platforms" -mindepth 2 -maxdepth 2 -type f -name android.jar | sort | tail -n 1)
  [[ -n "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

self_test() {
  local tmp aapt2 apksigner zipalign platform release_keystore debug_keystore release_apk debug_apk commit cert cert_output parser_fixture parser_expected parser_actual sha
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-standalone-android.XXXXXX")
  trap 'rm -rf -- "$tmp"' RETURN
  aapt2=$(find_android_tool aapt2) || die "self-test requires aapt2"
  apksigner=$(find_android_tool apksigner) || die "self-test requires apksigner"
  zipalign=$(find_android_tool zipalign) || die "self-test requires zipalign"
  platform=$(find_android_platform) || die "self-test requires an Android platform android.jar"
  command -v keytool >/dev/null 2>&1 || die "self-test requires keytool"

  parser_expected=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  parser_fixture=$(printf '%s\n' \
    "Signer #1 certificate SHA-256 digest: $parser_expected" \
    "V3.0 Signer: certificate SHA-256 digest: $parser_expected" \
    "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: $parser_expected" \
    'Source Stamp Signer: certificate SHA-256 digest: ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff')
  parser_actual=$(printf '%s\n' "$parser_fixture" | extract_signer_sha256_digests | sort -u)
  [[ "$parser_actual" == "$parser_expected" ]] || die "self-test signer certificate parser rejected supported apksigner output"

  release_keystore="$tmp/release.p12"
  debug_keystore="$tmp/debug.p12"
  keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$release_keystore" -storepass fixture-pass -keypass fixture-pass \
    -alias release -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=Twinotify fixture release' >/dev/null 2>&1
  keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$debug_keystore" -storepass fixture-pass -keypass fixture-pass \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1

  build_fixture() {
    local output=$1 keystore=$2 include_bundle=$3 include_e2e=$4 fixture_dir="$tmp/fixture-$RANDOM"
    mkdir -p "$fixture_dir/assets"
    if [[ "$include_bundle" == true ]]; then
      printf '__d(function(){return "fixture";});\n' > "$fixture_dir/assets/index.android.bundle"
    fi
    if [[ "$include_e2e" == true ]]; then
      printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.twinotify.app" android:versionCode="1" android:versionName="1">' \
        '  <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="35" />' \
        '  <application android:debuggable="false">' \
        '    <receiver android:name="co.twinotify.core.e2e.E2eControlReceiver" android:exported="true" />' \
        '    <provider android:name="co.twinotify.core.e2e.E2eStateProvider" android:authorities="com.twinotify.app.e2e" android:exported="true" />' \
        '  </application>' \
        '</manifest>' > "$fixture_dir/AndroidManifest.xml"
    elif [[ "$include_e2e" == bluetooth ]]; then
      printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.twinotify.app" android:versionCode="1" android:versionName="1">' \
        '  <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="35" />' \
        '  <application android:debuggable="false">' \
        '    <receiver android:name="co.twinotify.core.e2e.BluetoothRouteControl" android:exported="true" />' \
        '  </application>' \
        '</manifest>' > "$fixture_dir/AndroidManifest.xml"
    else
      printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.twinotify.app" android:versionCode="1" android:versionName="1">' \
        '  <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="35" />' \
        '  <application android:debuggable="false" />' \
        '</manifest>' > "$fixture_dir/AndroidManifest.xml"
    fi
    "$aapt2" link -o "$fixture_dir/unsigned.apk" -I "$platform" --manifest "$fixture_dir/AndroidManifest.xml" -A "$fixture_dir/assets" >/dev/null
    "$zipalign" -f 4 "$fixture_dir/unsigned.apk" "$fixture_dir/aligned.apk"
    "$apksigner" sign --ks "$keystore" --ks-pass pass:fixture-pass --key-pass pass:fixture-pass --out "$output" "$fixture_dir/aligned.apk"
  }

  release_apk="$tmp/release.apk"
  debug_apk="$tmp/debug.apk"
  build_fixture "$release_apk" "$release_keystore" true false
  build_fixture "$debug_apk" "$debug_keystore" true false
  commit=0123456789abcdef0123456789abcdef01234567
  cert_output=$("$apksigner" verify --print-certs "$release_apk")
  cert=$(printf '%s\n' "$cert_output" | extract_signer_sha256_digests | sort -u)
  if [[ ! "$cert" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf 'standalone-android: unable to parse fixture certificate from apksigner output:\n%s\n' "$cert_output" >&2
    return 1
  fi
  sha=$(sha256_file "$release_apk")
  printf '{"git_commit":"%s","app_sha256":"%s"}\n' "$commit" "$sha" > "$tmp/provenance.json"

  "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$release_apk" --provenance "$tmp/provenance.json" --expected-cert-sha256 "$cert" --expected-commit "$commit" >/dev/null

  expect_failure() {
    local label=$1
    shift
    if "$@" >/dev/null 2>"$tmp/error"; then
      die "self-test expected rejection: $label"
    fi
  }

  printf 'not an apk\n' > "$tmp/malformed.apk"
  expect_failure malformed "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$tmp/malformed.apk" --provenance "$tmp/provenance.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"
  expect_failure debug-certificate "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$debug_apk" --provenance "$tmp/provenance.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"
  expect_failure missing-protected-fingerprint env EXPECTED_RELEASE_CERT_SHA256="$cert" "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$release_apk" --provenance "$tmp/provenance.json" --expected-commit "$commit"

  local no_bundle_apk e2e_apk bluetooth_apk
  no_bundle_apk="$tmp/no-bundle.apk"
  e2e_apk="$tmp/e2e.apk"
  bluetooth_apk="$tmp/e2e-bluetooth.apk"
  build_fixture "$no_bundle_apk" "$release_keystore" false false
  build_fixture "$e2e_apk" "$release_keystore" true true
  build_fixture "$bluetooth_apk" "$release_keystore" true bluetooth
  expect_failure missing-bundle "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$no_bundle_apk" --provenance "$tmp/provenance.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"
  expect_failure e2e-component "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$e2e_apk" --provenance "$tmp/provenance.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"
  expect_failure bluetooth-route-control "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$bluetooth_apk" --provenance "$tmp/provenance.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"

  jq '.git_commit = ("0" * 40)' "$tmp/provenance.json" > "$tmp/wrong-commit.json"
  expect_failure wrong-commit "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$release_apk" --provenance "$tmp/wrong-commit.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"
  jq '.app_sha256 = ("0" * 64)' "$tmp/provenance.json" > "$tmp/wrong-sha.json"
  expect_failure wrong-sha "$ROOT_DIR/scripts/verify-standalone-android.sh" --apk "$release_apk" --provenance "$tmp/wrong-sha.json" --expected-cert-sha256 "$cert" --expected-commit "$commit"

  echo "standalone Android verifier self-test passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
  [[ $# -eq 1 ]] || die "--self-test accepts no additional arguments"
  self_test
  exit 0
fi

if [[ -n "${TWINOTIFY_STANDALONE_SELF_TEST_KEY:-}" || -n "${TWINOTIFY_STANDALONE_SKIP_CERTIFICATE:-}" ]]; then
  die "self-test or certificate-bypass environment overrides are not accepted"
fi

APK=""
PROVENANCE=""
EXPECTED_CERT=""
EXPECTED_COMMIT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) [[ $# -ge 2 ]] || die "--apk requires a value"; APK=$2; shift 2 ;;
    --provenance) [[ $# -ge 2 ]] || die "--provenance requires a value"; PROVENANCE=$2; shift 2 ;;
    --expected-cert-sha256) [[ $# -ge 2 ]] || die "--expected-cert-sha256 requires a value"; EXPECTED_CERT=$2; shift 2 ;;
    --expected-commit) [[ $# -ge 2 ]] || die "--expected-commit requires a value"; EXPECTED_COMMIT=$2; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$APK" && -n "$PROVENANCE" && -n "$EXPECTED_CERT" && -n "$EXPECTED_COMMIT" ]] || {
  die "usage: $0 --apk <apk> --provenance <json> --expected-cert-sha256 <hex> --expected-commit <sha>"
}
verify_apk "$APK" "$PROVENANCE" "$EXPECTED_CERT" "$EXPECTED_COMMIT"
