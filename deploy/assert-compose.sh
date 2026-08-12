#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dev_file="$repo_root/deploy/docker-compose.yml"
prod_file="$repo_root/deploy/docker-compose.prod.yml"
domain=relay.example.test
assert_tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-deploy.XXXXXX")
trap 'rm -rf "$assert_tmp"' EXIT HUP INT TERM
GOCACHE=${GOCACHE:-/tmp/twinotify-go-cache}
export GOCACHE

test -f "$prod_file" || {
	echo "missing production Compose file: $prod_file" >&2
	exit 1
}

if env -u TWINOTIFY_DOMAIN docker compose -f "$prod_file" config --format json >"$assert_tmp/missing-domain.json" 2>"$assert_tmp/missing-domain.err"; then
	echo "production Compose accepted a missing TWINOTIFY_DOMAIN" >&2
	exit 1
fi

docker compose -f "$dev_file" config --format json >"$assert_tmp/dev.json"
TWINOTIFY_DOMAIN="$domain" docker compose -f "$prod_file" config --format json >"$assert_tmp/prod.json"

(
	cd "$repo_root/relay"
	go run ./cmd/deployassert \
		-dev-config "$assert_tmp/dev.json" \
		-prod-config "$assert_tmp/prod.json" \
		-caddyfile "$repo_root/deploy/caddy/Caddyfile" \
		-domain "$domain"
)
