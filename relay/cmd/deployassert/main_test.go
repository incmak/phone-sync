package main

import (
	"fmt"
	"strings"
	"testing"
)

const validProdComposeJSON = `{
  "networks":{"edge":{"internal":false},"relay-internal":{"internal":true}},
  "services":{
    "relay":{
      "image":"ghcr.io/incmak/twinotify-relay@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "environment":{"TWINOTIFY_ENV":"production","LISTEN_ADDR":":8080","BOLT_PATH":"/data/twinotify-relay.db","TRUST_PROXY_HEADERS":"true","REQUIRE_MUTUAL_PAIR_SIGNATURES":"true","MIN_FREE_DISK_BYTES":"536870912","MAX_OPEN_CONNECTIONS":"512","BACKUP_DIR":"/backups","BACKUP_INTERVAL":"6h","BACKUP_RETENTION_COUNT":"14","BUILD_VERSION":"relay-v1.0.0"},
      "expose":["8080"],"networks":{"relay-internal":null},
      "volumes":[{"type":"volume","source":"relay-data","target":"/data"},{"type":"volume","source":"relay-backups","target":"/backups"}],
      "read_only":true,"user":"65532:65532","cap_drop":["ALL"],"security_opt":["no-new-privileges:true"],
      "pids_limit":128,"mem_limit":268435456,"cpus":1,
      "ulimits":{"nofile":{"soft":4096,"hard":4096}},
      "logging":{"driver":"json-file","options":{"max-size":"10m","max-file":"3"}},
      "healthcheck":{"test":["CMD","/relay","healthcheck","--url","http://127.0.0.1:8080/health/ready"],"interval":"30s","timeout":"5s","retries":3,"start_period":"5s"}
    },
    "caddy":{
      "image":"caddy:2.11.3-alpine@sha256:86deaf5e3d3408a6ccec08fbb79989783dd26e206ae10bcf78a801dc8c9ab794","environment":{"TWINOTIFY_DOMAIN":"relay.example.test"},
      "networks":{"edge":null,"relay-internal":null},
      "ports":[{"target":80,"published":"80","protocol":"tcp"},{"target":443,"published":"443","protocol":"tcp"}],
      "volumes":[{"type":"bind","source":"Caddyfile","target":"/etc/caddy/Caddyfile","read_only":true},{"type":"volume","source":"caddy-data","target":"/data"},{"type":"volume","source":"caddy-config","target":"/config"}],
      "read_only":true,"cap_drop":["ALL"],"cap_add":["NET_BIND_SERVICE"],"security_opt":["no-new-privileges:true"],
      "pids_limit":128,"mem_limit":268435456,"cpus":1,"ulimits":{"nofile":{"soft":4096,"hard":4096}},
      "logging":{"driver":"json-file","options":{"max-size":"10m","max-file":"3"}},
      "tmpfs":["/tmp:rw,noexec,nosuid,size=16m"],
      "depends_on":{"relay":{"condition":"service_healthy","required":true}}
    }
  }
}`

const validDevComposeJSON = `{
  "networks":{"twinotify-net":{"internal":false}},
  "services":{"relay":{"environment":{"LISTEN_ADDR":":8080","BOLT_PATH":"/data/twinotify-relay.db"},"networks":{"twinotify-net":null},"ports":[{"target":8080,"published":"8080","protocol":"tcp"}],"volumes":[{"type":"volume","source":"relay-data","target":"/data"}]}}
}`

const validCaddyfile = `{
    servers {
        max_header_size 16KB
    }
}
{$TWINOTIFY_DOMAIN} {
    @relay path /health /health/* /pair/* /ws
    header -Server
    handle @relay {
        reverse_proxy relay:8080
    }
    handle {
        respond 404
    }
}`

func TestValidateComposeJSONAcceptsExactDevAndProductionOwnership(t *testing.T) {
	if err := validateComposeJSON([]byte(validDevComposeJSON), "dev", ""); err != nil {
		t.Fatalf("valid dev config: %v", err)
	}
	if err := validateComposeJSON([]byte(validProdComposeJSON), "prod", "relay.example.test"); err != nil {
		t.Fatalf("valid production config: %v", err)
	}
}

func TestValidateComposeJSONRejectsStructuralMutations(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(string) string
	}{
		{
			name: "moved relay environment",
			mutate: func(config string) string {
				config = strings.Replace(config, `"BOLT_PATH":"/data/twinotify-relay.db",`, "", 1)
				return strings.Replace(config, `"TWINOTIFY_DOMAIN":"relay.example.test"`, `"TWINOTIFY_DOMAIN":"relay.example.test","BOLT_PATH":"/data/twinotify-relay.db"`, 1)
			},
		},
		{
			name: "non internal relay network",
			mutate: func(config string) string {
				return strings.Replace(config, `"relay-internal":{"internal":true}`, `"relay-internal":{"internal":false}`, 1)
			},
		},
		{
			name: "extra published port",
			mutate: func(config string) string {
				return strings.Replace(config, `"ports":[`, `"ports":[{"target":8080,"published":"8080","protocol":"tcp"},`, 1)
			},
		},
		{
			name: "relay publishes directly",
			mutate: func(config string) string {
				return strings.Replace(config, `"expose":["8080"]`, `"expose":["8080"],"ports":[{"target":8080,"published":"8080","protocol":"tcp"}]`, 1)
			},
		},
		{
			name: "relay data is a bind mount",
			mutate: func(config string) string {
				return strings.Replace(config, `"type":"volume","source":"relay-data","target":"/data"`, `"type":"bind","source":"/tmp/data","target":"/data"`, 1)
			},
		},
		{
			name: "Caddy bridge is not a bind mount",
			mutate: func(config string) string {
				return strings.Replace(config, `"type":"bind","source":"Caddyfile","target":"/etc/caddy/Caddyfile","read_only":true`, `"type":"volume","source":"caddyfile","target":"/etc/caddy/Caddyfile","read_only":true`, 1)
			},
		},
		{
			name: "production environment missing",
			mutate: func(config string) string {
				return strings.Replace(config, `"TWINOTIFY_ENV":"production",`, "", 1)
			},
		},
		{
			name: "backup volume missing",
			mutate: func(config string) string {
				return strings.Replace(config, `,{"type":"volume","source":"relay-backups","target":"/backups"}`, "", 1)
			},
		},
		{
			name: "tagged relay image",
			mutate: func(config string) string {
				return strings.Replace(config, `ghcr.io/incmak/twinotify-relay@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`, `ghcr.io/incmak/twinotify-relay:latest`, 1)
			},
		},
		{
			name: "relay build enabled",
			mutate: func(config string) string {
				return strings.Replace(config, `"image":"ghcr.io`, `"build":{"context":"."},"image":"ghcr.io`, 1)
			},
		},
		{
			name: "relay root filesystem writable",
			mutate: func(config string) string {
				return strings.Replace(config, `"read_only":true`, `"read_only":false`, 1)
			},
		},
		{
			name: "relay root user",
			mutate: func(config string) string {
				return strings.Replace(config, `"user":"65532:65532"`, `"user":"0:0"`, 1)
			},
		},
		{
			name: "relay capabilities retained",
			mutate: func(config string) string {
				return strings.Replace(config, `"cap_drop":["ALL"]`, `"cap_drop":[]`, 1)
			},
		},
		{
			name: "relay privilege escalation allowed",
			mutate: func(config string) string {
				return strings.Replace(config, `"security_opt":["no-new-privileges:true"]`, `"security_opt":[]`, 1)
			},
		},
		{
			name: "relay PID limit absent",
			mutate: func(config string) string {
				return strings.Replace(config, `"pids_limit":128`, `"pids_limit":0`, 1)
			},
		},
		{
			name: "relay memory limit absent",
			mutate: func(config string) string {
				return strings.Replace(config, `"mem_limit":268435456`, `"mem_limit":0`, 1)
			},
		},
		{
			name: "relay CPU limit absent",
			mutate: func(config string) string {
				return strings.Replace(config, `"cpus":1`, `"cpus":0`, 1)
			},
		},
		{
			name: "relay nofile limit absent",
			mutate: func(config string) string {
				return strings.Replace(config, `"soft":4096,"hard":4096`, `"soft":0,"hard":0`, 1)
			},
		},
		{
			name: "relay log rotation unbounded",
			mutate: func(config string) string {
				return strings.Replace(config, `"max-size":"10m","max-file":"3"`, `"max-size":"10m","max-file":"0"`, 1)
			},
		},
		{
			name: "relay healthcheck is not readiness",
			mutate: func(config string) string {
				return strings.Replace(config, `/health/ready"],"interval"`, `/health"],"interval"`, 1)
			},
		},
		{
			name: "Caddy starts before relay health",
			mutate: func(config string) string {
				return strings.Replace(config, `"condition":"service_healthy"`, `"condition":"service_started"`, 1)
			},
		},
		{
			name: "Caddy image is not immutable",
			mutate: func(config string) string {
				return strings.Replace(config, `caddy:2.11.3-alpine@sha256:86deaf5e3d3408a6ccec08fbb79989783dd26e206ae10bcf78a801dc8c9ab794`, `caddy:2.11.3-alpine`, 1)
			},
		},
		{
			name: "Caddy root filesystem writable",
			mutate: func(config string) string {
				return replaceLast(config, `"read_only":true`, `"read_only":false`)
			},
		},
		{
			name: "Caddy capabilities retained",
			mutate: func(config string) string {
				return replaceLast(config, `"cap_drop":["ALL"]`, `"cap_drop":[]`)
			},
		},
		{
			name: "Caddy bind capability absent",
			mutate: func(config string) string {
				return strings.Replace(config, `"cap_add":["NET_BIND_SERVICE"]`, `"cap_add":[]`, 1)
			},
		},
		{
			name: "Caddy tmpfs absent",
			mutate: func(config string) string {
				return strings.Replace(config, `"tmpfs":["/tmp:rw,noexec,nosuid,size=16m"],`, "", 1)
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := validateComposeJSON([]byte(tt.mutate(validProdComposeJSON)), "prod", "relay.example.test"); err == nil {
				t.Fatal("mutated production Compose config passed structural validation")
			}
		})
	}
}

func replaceLast(value, old, replacement string) string {
	index := strings.LastIndex(value, old)
	if index < 0 {
		return value
	}
	return value[:index] + replacement + value[index+len(old):]
}

func TestValidateCaddyfileRejectsRouteMutations(t *testing.T) {
	if err := validateCaddyfile([]byte(validCaddyfile)); err != nil {
		t.Fatalf("valid Caddyfile: %v", err)
	}
	tests := map[string]string{
		"wrong upstream":       strings.Replace(validCaddyfile, "relay:8080", "relay:9090", 1),
		"missing allowed path": strings.Replace(validCaddyfile, " /health/*", "", 1),
		"extra allowed path":   strings.Replace(validCaddyfile, " /ws", " /ws /admin", 1),
		"public metrics":       strings.Replace(validCaddyfile, " /ws", " /ws /metrics", 1),
		"internal tls":         strings.Replace(validCaddyfile, "    handle @relay", "    tls internal\n    handle @relay", 1),
		"missing fallback":     strings.Replace(validCaddyfile, "    handle {\n        respond 404\n    }\n", "", 1),
		"missing header bound": strings.Replace(validCaddyfile, "        max_header_size 16KB\n", "", 1),
		"missing server strip": strings.Replace(validCaddyfile, "    header -Server\n", "", 1),
	}
	for name, config := range tests {
		t.Run(name, func(t *testing.T) {
			if err := validateCaddyfile([]byte(config)); err == nil {
				t.Fatal(fmt.Sprintf("mutated Caddyfile passed structural validation: %s", config))
			}
		})
	}
}
