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
	Build       json.RawMessage              `json:"build"`
	CapAdd      []string                     `json:"cap_add"`
	CapDrop     []string                     `json:"cap_drop"`
	CPUs        json.RawMessage              `json:"cpus"`
	DependsOn   map[string]composeDependency `json:"depends_on"`
	Environment map[string]string            `json:"environment"`
	Expose      []string                     `json:"expose"`
	Healthcheck composeHealthcheck           `json:"healthcheck"`
	Image       string                       `json:"image"`
	Logging     composeLogging               `json:"logging"`
	MemLimit    json.RawMessage              `json:"mem_limit"`
	Networks    map[string]any               `json:"networks"`
	PidsLimit   int                          `json:"pids_limit"`
	Ports       []composePort                `json:"ports"`
	ReadOnly    bool                         `json:"read_only"`
	SecurityOpt []string                     `json:"security_opt"`
	Tmpfs       []string                     `json:"tmpfs"`
	Ulimits     map[string]composeUlimit     `json:"ulimits"`
	User        string                       `json:"user"`
	Volumes     []composeVolumeMount         `json:"volumes"`
}

type composeDependency struct {
	Condition string `json:"condition"`
	Required  bool   `json:"required"`
}

type composeHealthcheck struct {
	Interval    string   `json:"interval"`
	Retries     int      `json:"retries"`
	StartPeriod string   `json:"start_period"`
	Test        []string `json:"test"`
	Timeout     string   `json:"timeout"`
}

type composeLogging struct {
	Driver  string            `json:"driver"`
	Options map[string]string `json:"options"`
}

type composeUlimit struct {
	Hard int `json:"hard"`
	Soft int `json:"soft"`
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
	for key, value := range map[string]string{
		"TWINOTIFY_ENV":                       "production",
		"REQUIRE_MUTUAL_PAIR_SIGNATURES":      "true",
		"MIN_FREE_DISK_BYTES":                 "536870912",
		"MAX_OPEN_CONNECTIONS":                "64",
		"WEBSOCKET_QUEUE_MAX_BYTES":           "8388608",
		"WEBSOCKET_PROCESS_QUEUE_MAX_BYTES":   "67108864",
		"WEBSOCKET_INBOUND_PROCESS_MAX_BYTES": "33554432",
		"DURABLE_TRANSFER_MAX_BYTES":          "4194304",
		"RELAY_MEMORY_LIMIT_BYTES":            "268435456",
		"BACKUP_DIR":                          "/backups",
		"BACKUP_INTERVAL":                     "6h",
		"BACKUP_RETENTION_COUNT":              "14",
	} {
		if relay.Environment[key] != value {
			return fmt.Errorf("production relay %s = %q, want %q", key, relay.Environment[key], value)
		}
	}
	if buildVersion := relay.Environment["BUILD_VERSION"]; buildVersion == "" || buildVersion == "dev" {
		return errors.New("production relay must declare a non-development BUILD_VERSION")
	}
	if !digestPinnedImage(relay.Image) {
		return errors.New("production relay image must use a full sha256 digest")
	}
	if hasBuild(relay.Build) {
		return errors.New("production relay must consume a published image, not build locally")
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
	if !hasVolumeTarget(relay.Volumes, "/backups", "volume", false) {
		return errors.New("relay must persist a separate named volume at /backups")
	}
	if err := validateConstrainedService("relay", relay, true); err != nil {
		return err
	}
	if !sameStrings(relay.Healthcheck.Test, []string{"CMD", "/relay", "healthcheck", "--url", "http://127.0.0.1:8080/health/ready"}) ||
		relay.Healthcheck.Interval != "30s" || relay.Healthcheck.Timeout != "5s" ||
		relay.Healthcheck.StartPeriod != "5s" || relay.Healthcheck.Retries != 3 {
		return errors.New("relay must use the bounded exec-form readiness healthcheck")
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
	const caddyImage = "caddy:2.11.3-alpine@sha256:86deaf5e3d3408a6ccec08fbb79989783dd26e206ae10bcf78a801dc8c9ab794"
	if caddy.Image != caddyImage {
		return fmt.Errorf("Caddy image = %q, want %s", caddy.Image, caddyImage)
	}
	if err := validateConstrainedService("Caddy", caddy, false); err != nil {
		return err
	}
	if !sameStrings(caddy.CapAdd, []string{"NET_BIND_SERVICE"}) {
		return errors.New("Caddy must add only NET_BIND_SERVICE")
	}
	if dependency, ok := caddy.DependsOn["relay"]; !ok || dependency.Condition != "service_healthy" || !dependency.Required {
		return errors.New("Caddy must wait for a healthy relay")
	}
	if !hasSecureTmpfs(caddy.Tmpfs, "/tmp") {
		return errors.New("Caddy must mount a bounded noexec,nosuid tmpfs at /tmp")
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
		global, servers, headerLimit                        int
		site, matcher, stripServer, securityHeaders         int
		relayHandle, fallback, proxy, proxyXFF, fallback404 int
		paths                                               []string
		stack                                               []string
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
		if line == "{" {
			if len(state.stack) != 0 || state.site != 0 || state.global != 0 {
				return errors.New("global Caddy options must be the first and only root options block")
			}
			state.global++
			state.stack = append(state.stack, "global")
			continue
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
			case sameStrings(state.stack, []string{"global"}) && header == "servers":
				state.servers++
				state.stack = append(state.stack, "servers")
			case len(state.stack) == 0 && header == "{$TWINOTIFY_DOMAIN}":
				state.site++
				state.stack = append(state.stack, "site")
			case sameStrings(state.stack, []string{"site"}) && header == "header":
				state.stack = append(state.stack, "headers")
			case sameStrings(state.stack, []string{"site"}) && header == "handle @relay":
				state.relayHandle++
				state.stack = append(state.stack, "relay")
			case sameStrings(state.stack, []string{"site", "relay"}) && header == "reverse_proxy relay:8080":
				state.proxy++
				state.stack = append(state.stack, "proxy")
			case sameStrings(state.stack, []string{"site"}) && header == "handle":
				state.fallback++
				state.stack = append(state.stack, "fallback")
			default:
				return fmt.Errorf("unexpected Caddy block %q at %v", header, state.stack)
			}
			continue
		}
		switch {
		case sameStrings(state.stack, []string{"global", "servers"}) && len(fields) == 2 && fields[0] == "max_header_size" && fields[1] == "16KB":
			state.headerLimit++
		case sameStrings(state.stack, []string{"site"}) && len(fields) >= 3 && fields[0] == "@relay" && fields[1] == "path":
			state.matcher++
			state.paths = append([]string(nil), fields[2:]...)
		case sameStrings(state.stack, []string{"site", "headers"}) && line == "-Server":
			state.stripServer++
		case sameStrings(state.stack, []string{"site", "headers"}) && isRequiredSecurityHeader(line):
			state.securityHeaders++
		case sameStrings(state.stack, []string{"site", "relay", "proxy"}) && line == "header_up X-Forwarded-For {remote_host}":
			state.proxyXFF++
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
	if state.global != 1 || state.servers != 1 || state.headerLimit != 1 || state.site != 1 || state.matcher != 1 ||
		state.stripServer != 1 || state.securityHeaders != 5 || state.relayHandle != 1 || state.fallback != 1 ||
		state.proxy != 1 || state.proxyXFF != 1 || state.fallback404 != 1 {
		return fmt.Errorf("incomplete Caddy route graph: %+v", state)
	}
	if !sameStrings(state.paths, []string{"/health", "/health/*", "/pair/*", "/ws"}) {
		return fmt.Errorf("allowed relay paths = %v", state.paths)
	}
	return nil
}

func isRequiredSecurityHeader(line string) bool {
	switch line {
	case `Strict-Transport-Security "max-age=31536000; includeSubDomains"`,
		`X-Content-Type-Options "nosniff"`,
		`Referrer-Policy "no-referrer"`,
		`Content-Security-Policy "default-src 'none'; frame-ancestors 'none'"`,
		`Cache-Control "no-store"`:
		return true
	default:
		return false
	}
}

func validateConstrainedService(name string, service composeService, requireNonRoot bool) error {
	if !service.ReadOnly {
		return fmt.Errorf("%s root filesystem must be read-only", name)
	}
	if requireNonRoot && service.User != "65532:65532" {
		return fmt.Errorf("%s user = %q, want 65532:65532", name, service.User)
	}
	if !sameStrings(service.CapDrop, []string{"ALL"}) {
		return fmt.Errorf("%s must drop all capabilities", name)
	}
	if !sameStrings(service.SecurityOpt, []string{"no-new-privileges:true"}) {
		return fmt.Errorf("%s must disable privilege escalation", name)
	}
	if service.PidsLimit != 128 || !numberEquals(service.MemLimit, 268435456) || !numberEquals(service.CPUs, 1) {
		return fmt.Errorf("%s must use the approved PID, memory, and CPU bounds", name)
	}
	if nofile, ok := service.Ulimits["nofile"]; !ok || nofile.Soft != 4096 || nofile.Hard != 4096 || len(service.Ulimits) != 1 {
		return fmt.Errorf("%s must bound nofile at 4096", name)
	}
	if service.Logging.Driver != "json-file" || service.Logging.Options["max-size"] != "10m" ||
		service.Logging.Options["max-file"] != "3" || len(service.Logging.Options) != 2 {
		return fmt.Errorf("%s must use bounded json-file logging", name)
	}
	return nil
}

func digestPinnedImage(image string) bool {
	parts := strings.Split(image, "@sha256:")
	if len(parts) != 2 || parts[0] == "" || len(parts[1]) != 64 {
		return false
	}
	for _, character := range parts[1] {
		if !strings.ContainsRune("0123456789abcdef", character) {
			return false
		}
	}
	return true
}

func hasBuild(raw json.RawMessage) bool {
	trimmed := bytes.TrimSpace(raw)
	return len(trimmed) != 0 && !bytes.Equal(trimmed, []byte("null"))
}

func numberEquals(raw json.RawMessage, expected float64) bool {
	var number float64
	if json.Unmarshal(raw, &number) == nil {
		return number == expected
	}
	var text string
	if json.Unmarshal(raw, &text) != nil {
		return false
	}
	number, err := strconv.ParseFloat(text, 64)
	return err == nil && number == expected
}

func hasSecureTmpfs(mounts []string, target string) bool {
	for _, mount := range mounts {
		parts := strings.Split(mount, ":")
		if len(parts) < 2 || parts[0] != target {
			continue
		}
		options := strings.Join(parts[1:], ",")
		if strings.Contains(options, "noexec") && strings.Contains(options, "nosuid") && strings.Contains(options, "size=") {
			return true
		}
	}
	return false
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
