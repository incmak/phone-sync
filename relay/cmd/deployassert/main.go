package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
)

type composeConfig struct {
	Networks map[string]composeNetwork `json:"networks"`
	Services map[string]composeService `json:"services"`
}

type composeNetwork struct {
	Internal bool `json:"internal"`
}

type composeService struct {
	Environment map[string]string    `json:"environment"`
	Expose      []string             `json:"expose"`
	Networks    map[string]any       `json:"networks"`
	Ports       []composePort        `json:"ports"`
	Volumes     []composeVolumeMount `json:"volumes"`
}

type composePort struct {
	Target    int             `json:"target"`
	Published json.RawMessage `json:"published"`
	Protocol  string          `json:"protocol"`
}

type composeVolumeMount struct {
	Type     string `json:"type"`
	Source   string `json:"source"`
	Target   string `json:"target"`
	ReadOnly bool   `json:"read_only"`
}

func main() {
	devPath := flag.String("dev-config", "", "resolved development Compose JSON")
	prodPath := flag.String("prod-config", "", "resolved production Compose JSON")
	caddyPath := flag.String("caddyfile", "", "production Caddyfile")
	domain := flag.String("domain", "", "expected production domain")
	flag.Parse()
	if *devPath == "" || *prodPath == "" || *caddyPath == "" || *domain == "" {
		fmt.Fprintln(os.Stderr, "dev-config, prod-config, caddyfile, and domain are required")
		os.Exit(2)
	}
	for _, check := range []struct {
		path string
		fn   func([]byte) error
	}{
		{*devPath, func(raw []byte) error { return validateComposeJSON(raw, "dev", "") }},
		{*prodPath, func(raw []byte) error { return validateComposeJSON(raw, "prod", *domain) }},
		{*caddyPath, validateCaddyfile},
	} {
		raw, err := os.ReadFile(check.path)
		if err == nil {
			err = check.fn(raw)
		}
		if err != nil {
			fmt.Fprintf(os.Stderr, "%s: %v\n", check.path, err)
			os.Exit(1)
		}
	}
}

func validateComposeJSON(raw []byte, mode, domain string) error {
	var config composeConfig
	decoder := json.NewDecoder(bytes.NewReader(raw))
	if err := decoder.Decode(&config); err != nil {
		return fmt.Errorf("decode Compose JSON: %w", err)
	}
	if mode != "dev" && mode != "prod" {
		return fmt.Errorf("unknown Compose mode %q", mode)
	}
	wantServices := []string{"relay"}
	if mode == "prod" {
		wantServices = []string{"caddy", "relay"}
	}
	if !sameStrings(mapKeys(config.Services), wantServices) {
		return fmt.Errorf("services = %v, want %v", mapKeys(config.Services), wantServices)
	}
	relay := config.Services["relay"]
	if relay.Environment["BOLT_PATH"] != "/data/twinotify-relay.db" {
		return errors.New("relay must own BOLT_PATH=/data/twinotify-relay.db")
	}
	if relay.Environment["LISTEN_ADDR"] != ":8080" {
		return errors.New("relay must listen on :8080")
	}
	if !hasVolumeTarget(relay.Volumes, "/data", "volume", false) {
		return errors.New("relay must persist a named volume at /data")
	}
	for serviceName, service := range config.Services {
		for _, key := range []string{"BOLT_PATH", "TRUST_PROXY_HEADERS"} {
			if _, exists := service.Environment[key]; exists && serviceName != "relay" {
				return fmt.Errorf("%s is owned by %s, want relay", key, serviceName)
			}
		}
		if _, exists := service.Environment["TWINOTIFY_DOMAIN"]; exists && serviceName != "caddy" {
			return fmt.Errorf("TWINOTIFY_DOMAIN is owned by %s, want caddy", serviceName)
		}
	}

	if mode == "dev" {
		if !sameStrings(mapKeys(relay.Networks), []string{"twinotify-net"}) {
			return fmt.Errorf("development relay networks = %v, want [twinotify-net]", mapKeys(relay.Networks))
		}
		if network, ok := config.Networks["twinotify-net"]; !ok || network.Internal {
			return errors.New("development twinotify-net must exist and be externally reachable")
		}
		if len(relay.Ports) != 1 || !portEquals(relay.Ports[0], 8080, "8080") {
			return errors.New("development relay must publish only TCP 8080")
		}
		return nil
	}

	if relay.Environment["TRUST_PROXY_HEADERS"] != "true" {
		return errors.New("production relay must enable trusted proxy headers")
	}
	if len(relay.Ports) != 0 {
		return errors.New("production relay must not publish a host port")
	}
	if !sameStrings(relay.Expose, []string{"8080"}) {
		return fmt.Errorf("relay expose = %v, want [8080]", relay.Expose)
	}
	if !sameStrings(mapKeys(relay.Networks), []string{"relay-internal"}) {
		return fmt.Errorf("relay networks = %v, want [relay-internal]", mapKeys(relay.Networks))
	}
	internal, ok := config.Networks["relay-internal"]
	if !ok || !internal.Internal {
		return errors.New("relay-internal network must be internal")
	}
	if edge, ok := config.Networks["edge"]; !ok || edge.Internal {
		return errors.New("Caddy edge network must exist and be externally reachable")
	}

	caddy := config.Services["caddy"]
	if caddy.Environment["TWINOTIFY_DOMAIN"] != domain {
		return fmt.Errorf("Caddy domain = %q, want %q", caddy.Environment["TWINOTIFY_DOMAIN"], domain)
	}
	if !sameStrings(mapKeys(caddy.Networks), []string{"edge", "relay-internal"}) {
		return fmt.Errorf("Caddy networks = %v, want edge and relay-internal", mapKeys(caddy.Networks))
	}
	if !hasVolumeTarget(caddy.Volumes, "/etc/caddy/Caddyfile", "bind", true) {
		return errors.New("Caddy must mount its Caddyfile read-only")
	}
	if len(caddy.Ports) != 2 || !portSetEquals(caddy.Ports, [][2]string{{"80", "80"}, {"443", "443"}}) {
		return errors.New("only Caddy TCP 80 and 443 may be published")
	}
	for serviceName, service := range config.Services {
		if serviceName != "caddy" && len(service.Ports) != 0 {
			return fmt.Errorf("%s publishes a host port", serviceName)
		}
	}
	return nil
}

func validateCaddyfile(raw []byte) error {
	type caddyState struct {
		site, matcher, relayHandle, fallback, proxy, fallback404 int
		paths                                                    []string
		stack                                                    []string
	}
	state := caddyState{}
	scanner := bufio.NewScanner(bytes.NewReader(raw))
	for scanner.Scan() {
		line := strings.TrimSpace(strings.SplitN(scanner.Text(), "#", 2)[0])
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "tls" && fields[1] == "internal" {
			return errors.New("Caddyfile must use public certificates, not tls internal")
		}
		if line == "}" {
			if len(state.stack) == 0 {
				return errors.New("unmatched closing brace")
			}
			state.stack = state.stack[:len(state.stack)-1]
			continue
		}
		if strings.HasSuffix(line, " {") {
			header := strings.TrimSpace(strings.TrimSuffix(line, "{"))
			switch {
			case len(state.stack) == 0 && header == "{$TWINOTIFY_DOMAIN}":
				state.site++
				state.stack = append(state.stack, "site")
			case sameStrings(state.stack, []string{"site"}) && header == "handle @relay":
				state.relayHandle++
				state.stack = append(state.stack, "relay")
			case sameStrings(state.stack, []string{"site"}) && header == "handle":
				state.fallback++
				state.stack = append(state.stack, "fallback")
			default:
				return fmt.Errorf("unexpected Caddy block %q at %v", header, state.stack)
			}
			continue
		}
		switch {
		case sameStrings(state.stack, []string{"site"}) && len(fields) >= 3 && fields[0] == "@relay" && fields[1] == "path":
			state.matcher++
			state.paths = append([]string(nil), fields[2:]...)
		case sameStrings(state.stack, []string{"site", "relay"}) && len(fields) == 2 && fields[0] == "reverse_proxy" && fields[1] == "relay:8080":
			state.proxy++
		case sameStrings(state.stack, []string{"site", "fallback"}) && len(fields) == 2 && fields[0] == "respond" && fields[1] == "404":
			state.fallback404++
		default:
			return fmt.Errorf("unexpected Caddy directive %q at %v", line, state.stack)
		}
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	if len(state.stack) != 0 {
		return errors.New("unclosed Caddy block")
	}
	if state.site != 1 || state.matcher != 1 || state.relayHandle != 1 || state.fallback != 1 || state.proxy != 1 || state.fallback404 != 1 {
		return fmt.Errorf("incomplete Caddy route graph: %+v", state)
	}
	if !sameStrings(state.paths, []string{"/health", "/pair/*", "/ws"}) {
		return fmt.Errorf("allowed relay paths = %v", state.paths)
	}
	return nil
}

func hasVolumeTarget(volumes []composeVolumeMount, target, volumeType string, requireReadOnly bool) bool {
	for _, volume := range volumes {
		if volume.Target == target && volume.Source != "" && volume.Type == volumeType && (!requireReadOnly || volume.ReadOnly) {
			return true
		}
	}
	return false
}

func publishedString(raw json.RawMessage) string {
	var text string
	if json.Unmarshal(raw, &text) == nil {
		return text
	}
	var number int
	if json.Unmarshal(raw, &number) == nil {
		return strconv.Itoa(number)
	}
	return ""
}

func portEquals(port composePort, target int, published string) bool {
	return port.Target == target && publishedString(port.Published) == published && (port.Protocol == "" || port.Protocol == "tcp")
}

func portSetEquals(ports []composePort, wanted [][2]string) bool {
	got := make([]string, 0, len(ports))
	for _, port := range ports {
		if port.Protocol != "" && port.Protocol != "tcp" {
			return false
		}
		got = append(got, fmt.Sprintf("%d:%s", port.Target, publishedString(port.Published)))
	}
	want := make([]string, 0, len(wanted))
	for _, port := range wanted {
		want = append(want, port[0]+":"+port[1])
	}
	sort.Strings(got)
	sort.Strings(want)
	return sameStrings(got, want)
}

func mapKeys[T any](items map[string]T) []string {
	keys := make([]string, 0, len(items))
	for key := range items {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func sameStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	leftCopy := append([]string(nil), left...)
	rightCopy := append([]string(nil), right...)
	sort.Strings(leftCopy)
	sort.Strings(rightCopy)
	for index := range leftCopy {
		if leftCopy[index] != rightCopy[index] {
			return false
		}
	}
	return true
}
