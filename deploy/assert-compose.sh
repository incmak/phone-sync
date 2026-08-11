#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dev_file="$repo_root/deploy/docker-compose.yml"
prod_file="$repo_root/deploy/docker-compose.prod.yml"

test -f "$prod_file" || {
	echo "missing production Compose file: $prod_file" >&2
	exit 1
}

dev_config=$(docker compose -f "$dev_file" config)
prod_config=$(TWINOTIFY_DOMAIN=relay.example.test docker compose -f "$prod_file" config)

printf '%s\n' "$dev_config" | grep -q 'BOLT_PATH: /data/twinotify-relay.db'
printf '%s\n' "$prod_config" | grep -q 'BOLT_PATH: /data/twinotify-relay.db'
printf '%s\n' "$prod_config" | grep -q 'TRUST_PROXY_HEADERS: "true"'

relay_block=$(printf '%s\n' "$prod_config" | awk '
  /^  relay:$/ { in_relay=1; next }
  in_relay && /^  [a-zA-Z0-9_-]+:$/ { exit }
  in_relay { print }
')
if printf '%s\n' "$relay_block" | grep -Eq '^[[:space:]]+(ports|published):'; then
	echo "production relay service publishes a host port" >&2
	exit 1
fi
printf '%s\n' "$prod_config" | grep -q 'published: "80"'
printf '%s\n' "$prod_config" | grep -q 'published: "443"'

if grep -q 'tls internal' "$repo_root/deploy/caddy/Caddyfile"; then
	echo "production Caddyfile uses tls internal" >&2
	exit 1
fi
grep -q 'handle @relay' "$repo_root/deploy/caddy/Caddyfile"
grep -q '^    handle {' "$repo_root/deploy/caddy/Caddyfile"
