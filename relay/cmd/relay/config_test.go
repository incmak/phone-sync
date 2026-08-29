package main

import (
	"path/filepath"
	"testing"
	"time"
)

func TestProductionConfigAcceptsCompleteSafeEnvironment(t *testing.T) {
	environment := validProductionEnvironment()
	config, err := loadRuntimeConfig(environment.get)
	if err != nil {
		t.Fatalf("load production config: %v", err)
	}
	if config.environment != "production" || config.listenAddr != ":8080" {
		t.Fatalf("environment/listen = %q/%q", config.environment, config.listenAddr)
	}
	if config.boltPath != "/srv/twinotify/data/relay.db" || !config.trustProxyHeaders || !config.requireMutualPairSignatures {
		t.Fatalf("security config = %#v", config)
	}
	if config.minFreeDiskBytes != 536870912 || config.maxOpenConnections != 64 {
		t.Fatalf("capacity config = %#v", config)
	}
	if config.webSocketQueueMaxBytes != 8388608 || config.webSocketProcessQueueMaxBytes != 67108864 || config.durableTransferMaxBytes != 4194304 {
		t.Fatalf("WebSocket memory config = %#v", config)
	}
	if config.backupDir != "/srv/twinotify/backups" || config.backupInterval != 6*time.Hour || config.backupRetention != 14 {
		t.Fatalf("backup config = %#v", config)
	}
}

func TestProductionConfigFailsClosed(t *testing.T) {
	required := []string{
		"BOLT_PATH", "TRUST_PROXY_HEADERS", "REQUIRE_MUTUAL_PAIR_SIGNATURES", "MIN_FREE_DISK_BYTES",
		"MAX_OPEN_CONNECTIONS", "BACKUP_DIR", "BACKUP_INTERVAL", "BACKUP_RETENTION_COUNT",
		"WEBSOCKET_QUEUE_MAX_BYTES", "WEBSOCKET_PROCESS_QUEUE_MAX_BYTES", "DURABLE_TRANSFER_MAX_BYTES",
		"RELAY_MEMORY_LIMIT_BYTES",
	}
	for _, key := range required {
		t.Run("missing "+key, func(t *testing.T) {
			environment := validProductionEnvironment()
			delete(environment, key)
			if _, err := loadRuntimeConfig(environment.get); err == nil {
				t.Fatalf("missing %s was accepted", key)
			}
		})
	}

	tests := map[string]map[string]string{
		"unknown environment":              {"TWINOTIFY_ENV": "prod"},
		"relative database":                {"BOLT_PATH": "data/relay.db"},
		"temporary database":               {"BOLT_PATH": "/tmp/relay.db"},
		"false proxy trust":                {"TRUST_PROXY_HEADERS": "false"},
		"unknown proxy boolean":            {"TRUST_PROXY_HEADERS": "TRUE"},
		"false mutual confirmation":        {"REQUIRE_MUTUAL_PAIR_SIGNATURES": "false"},
		"zero disk reserve":                {"MIN_FREE_DISK_BYTES": "0"},
		"negative connection limit":        {"MAX_OPEN_CONNECTIONS": "-1"},
		"relative backup directory":        {"BACKUP_DIR": "backups"},
		"backup shares database directory": {"BACKUP_DIR": "/srv/twinotify/data"},
		"zero backup interval":             {"BACKUP_INTERVAL": "0s"},
		"malformed backup interval":        {"BACKUP_INTERVAL": "often"},
		"zero backup retention":            {"BACKUP_RETENTION_COUNT": "0"},
		"non-numeric backup retention":     {"BACKUP_RETENTION_COUNT": "many"},
		"non-numeric disk reserve":         {"MIN_FREE_DISK_BYTES": "large"},
		"non-numeric connection limit":     {"MAX_OPEN_CONNECTIONS": "several"},
		"queue below legal frame":          {"WEBSOCKET_QUEUE_MAX_BYTES": "1024"},
		"process below connection":         {"WEBSOCKET_PROCESS_QUEUE_MAX_BYTES": "4194304"},
		"unsafe process margin":            {"WEBSOCKET_PROCESS_QUEUE_MAX_BYTES": "262144000"},
	}
	for name, overrides := range tests {
		t.Run(name, func(t *testing.T) {
			environment := validProductionEnvironment()
			for key, value := range overrides {
				environment[key] = value
			}
			if name == "unknown environment" {
				environment = overrides
			}
			if _, err := loadRuntimeConfig(environment.get); err == nil {
				t.Fatal("unsafe production configuration was accepted")
			}
		})
	}
}

func TestDevelopmentConfigKeepsLocalDefaultsAndParsesExplicitValues(t *testing.T) {
	environment := testEnvironment{
		"TWINOTIFY_ENV":                  "development",
		"BOLT_PATH":                      filepath.Join(t.TempDir(), "relay.db"),
		"TRUST_PROXY_HEADERS":            "false",
		"REQUIRE_MUTUAL_PAIR_SIGNATURES": "true",
		"MIN_FREE_DISK_BYTES":            "1024",
		"MAX_OPEN_CONNECTIONS":           "32",
	}
	config, err := loadRuntimeConfig(environment.get)
	if err != nil {
		t.Fatal(err)
	}
	if config.environment != "development" || config.trustProxyHeaders || !config.requireMutualPairSignatures {
		t.Fatalf("development config = %#v", config)
	}
	if config.minFreeDiskBytes != 1024 || config.maxOpenConnections != 32 {
		t.Fatalf("development capacity = %#v", config)
	}
}

type testEnvironment map[string]string

func (e testEnvironment) get(key string) string { return e[key] }

func validProductionEnvironment() testEnvironment {
	return testEnvironment{
		"TWINOTIFY_ENV":                     "production",
		"BOLT_PATH":                         "/srv/twinotify/data/relay.db",
		"TRUST_PROXY_HEADERS":               "true",
		"REQUIRE_MUTUAL_PAIR_SIGNATURES":    "true",
		"MIN_FREE_DISK_BYTES":               "536870912",
		"MAX_OPEN_CONNECTIONS":              "64",
		"WEBSOCKET_QUEUE_MAX_BYTES":         "8388608",
		"WEBSOCKET_PROCESS_QUEUE_MAX_BYTES": "67108864",
		"DURABLE_TRANSFER_MAX_BYTES":        "4194304",
		"RELAY_MEMORY_LIMIT_BYTES":          "268435456",
		"BACKUP_DIR":                        "/srv/twinotify/backups",
		"BACKUP_INTERVAL":                   "6h",
		"BACKUP_RETENTION_COUNT":            "14",
	}
}
