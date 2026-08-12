package main

import (
	"fmt"
	"strings"
	"testing"
)

const validProdComposeJSON = `{
  "networks":{"edge":{"internal":false},"relay-internal":{"internal":true}},
  "services":{
    "relay":{"environment":{"LISTEN_ADDR":":8080","BOLT_PATH":"/data/twinotify-relay.db","TRUST_PROXY_HEADERS":"true"},"expose":["8080"],"networks":{"relay-internal":null},"volumes":[{"type":"volume","source":"relay-data","target":"/data"}]},
    "caddy":{"environment":{"TWINOTIFY_DOMAIN":"relay.example.test"},"networks":{"edge":null,"relay-internal":null},"ports":[{"target":80,"published":"80","protocol":"tcp"},{"target":443,"published":"443","protocol":"tcp"}],"volumes":[{"type":"bind","source":"Caddyfile","target":"/etc/caddy/Caddyfile","read_only":true}]}
  }
}`

const validDevComposeJSON = `{
  "networks":{"twinotify-net":{"internal":false}},
  "services":{"relay":{"environment":{"LISTEN_ADDR":":8080","BOLT_PATH":"/data/twinotify-relay.db"},"networks":{"twinotify-net":null},"ports":[{"target":8080,"published":"8080","protocol":"tcp"}],"volumes":[{"type":"volume","source":"relay-data","target":"/data"}]}}
}`

const validCaddyfile = `{$TWINOTIFY_DOMAIN} {
    @relay path /health /pair/* /ws
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
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := validateComposeJSON([]byte(tt.mutate(validProdComposeJSON)), "prod", "relay.example.test"); err == nil {
				t.Fatal("mutated production Compose config passed structural validation")
			}
		})
	}
}

func TestValidateCaddyfileRejectsRouteMutations(t *testing.T) {
	if err := validateCaddyfile([]byte(validCaddyfile)); err != nil {
		t.Fatalf("valid Caddyfile: %v", err)
	}
	tests := map[string]string{
		"wrong upstream":       strings.Replace(validCaddyfile, "relay:8080", "relay:9090", 1),
		"missing allowed path": strings.Replace(validCaddyfile, " /ws", "", 1),
		"extra allowed path":   strings.Replace(validCaddyfile, " /ws", " /ws /admin", 1),
		"internal tls":         strings.Replace(validCaddyfile, "    handle @relay", "    tls internal\n    handle @relay", 1),
		"missing fallback":     strings.Replace(validCaddyfile, "    handle {\n        respond 404\n    }\n", "", 1),
	}
	for name, config := range tests {
		t.Run(name, func(t *testing.T) {
			if err := validateCaddyfile([]byte(config)); err == nil {
				t.Fatal(fmt.Sprintf("mutated Caddyfile passed structural validation: %s", config))
			}
		})
	}
}
