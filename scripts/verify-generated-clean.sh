#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)

verify_generated_clean() {
  local source_dir=${PROTO_SOURCE_DIR:-proto}
  local schema_dir=${RELAY_SCHEMA_DIR:-relay/internal/server/schemas}
  local fixture_dir=${RELAY_FIXTURE_DIR:-relay/internal/server/fixtures}

  cd "$repo_root"
  git diff --check
  for source in "$source_dir"/*.schema.json; do
    test -f "$source"
    cmp "$source" "$schema_dir/$(basename "$source")"
  done
  diff -ru "$source_dir/fixtures" "$fixture_dir"
}

if [[ "${1:-}" == "--self-test" ]]; then
  test "$#" -eq 1
  temp_root=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-verify-generated.XXXXXX")
  trap 'rm -rf -- "$temp_root"' EXIT
  mkdir -p "$temp_root/source/fixtures" "$temp_root/schema" "$temp_root/fixture"
  printf '{"name":"fixture"}\n' > "$temp_root/source/example.schema.json"
  printf 'fixture-payload\n' > "$temp_root/source/fixtures/example.json"
  cp "$temp_root/source/example.schema.json" "$temp_root/schema/example.schema.json"
  cp "$temp_root/source/fixtures/example.json" "$temp_root/fixture/example.json"

  PROTO_SOURCE_DIR="$temp_root/source" \
    RELAY_SCHEMA_DIR="$temp_root/schema" \
    RELAY_FIXTURE_DIR="$temp_root/fixture" \
    "$0"

  printf '{"name":"changed"}\n' > "$temp_root/schema/example.schema.json"
  if PROTO_SOURCE_DIR="$temp_root/source" \
      RELAY_SCHEMA_DIR="$temp_root/schema" \
      RELAY_FIXTURE_DIR="$temp_root/fixture" \
      "$0"; then
    echo "self-test expected mismatched schema to fail" >&2
    exit 1
  fi
  echo "verify-generated-clean self-test passed"
  exit 0
fi

if [[ $# -ne 0 ]]; then
  echo "usage: $0 [--self-test]" >&2
  exit 2
fi
verify_generated_clean
