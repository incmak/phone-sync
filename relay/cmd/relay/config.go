package main

import (
	"errors"
	"fmt"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	developmentEnvironment = "development"
	productionEnvironment  = "production"
)

var buildVersion = "dev"

type runtimeConfig struct {
	environment                     string
	listenAddr                      string
	boltPath                        string
	trustProxyHeaders               bool
	requireMutualPairSignatures     bool
	minFreeDiskBytes                uint64
	maxOpenConnections              int
	webSocketQueueMaxBytes          uint64
	webSocketProcessQueueMaxBytes   uint64
	webSocketInboundProcessMaxBytes uint64
	durableTransferMaxBytes         uint64
	relayMemoryLimitBytes           uint64
	backupDir                       string
	backupInterval                  time.Duration
	backupRetention                 int
	buildVersion                    string
}

func loadRuntimeConfig(getenv func(string) string) (runtimeConfig, error) {
	if getenv == nil {
		return runtimeConfig{}, errors.New("environment reader is required")
	}
	environment := getenv("TWINOTIFY_ENV")
	if environment == "" {
		environment = developmentEnvironment
	}
	if environment != developmentEnvironment && environment != productionEnvironment {
		return runtimeConfig{}, fmt.Errorf("unsupported TWINOTIFY_ENV %q", environment)
	}

	config := runtimeConfig{
		environment:                     environment,
		listenAddr:                      ":8080",
		boltPath:                        "/tmp/twinotify-relay.db",
		maxOpenConnections:              64,
		webSocketQueueMaxBytes:          8 << 20,
		webSocketProcessQueueMaxBytes:   64 << 20,
		webSocketInboundProcessMaxBytes: 32 << 20,
		durableTransferMaxBytes:         4 << 20,
		relayMemoryLimitBytes:           256 << 20,
		buildVersion:                    buildVersion,
	}
	if value := getenv("LISTEN_ADDR"); value != "" {
		config.listenAddr = value
	}
	if value := getenv("BUILD_VERSION"); value != "" {
		config.buildVersion = value
	}

	production := environment == productionEnvironment
	var err error
	if config.boltPath, err = configString(getenv, "BOLT_PATH", config.boltPath, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.trustProxyHeaders, err = configBool(getenv, "TRUST_PROXY_HEADERS", false, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.requireMutualPairSignatures, err = configBool(getenv, "REQUIRE_MUTUAL_PAIR_SIGNATURES", false, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.minFreeDiskBytes, err = configUint64(getenv, "MIN_FREE_DISK_BYTES", 0, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.maxOpenConnections, err = configPositiveInt(getenv, "MAX_OPEN_CONNECTIONS", config.maxOpenConnections, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.webSocketQueueMaxBytes, err = configUint64(getenv, "WEBSOCKET_QUEUE_MAX_BYTES", config.webSocketQueueMaxBytes, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.webSocketProcessQueueMaxBytes, err = configUint64(getenv, "WEBSOCKET_PROCESS_QUEUE_MAX_BYTES", config.webSocketProcessQueueMaxBytes, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.webSocketInboundProcessMaxBytes, err = configUint64(getenv, "WEBSOCKET_INBOUND_PROCESS_MAX_BYTES", config.webSocketInboundProcessMaxBytes, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.durableTransferMaxBytes, err = configUint64(getenv, "DURABLE_TRANSFER_MAX_BYTES", config.durableTransferMaxBytes, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.relayMemoryLimitBytes, err = configUint64(getenv, "RELAY_MEMORY_LIMIT_BYTES", config.relayMemoryLimitBytes, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.backupDir, err = configString(getenv, "BACKUP_DIR", "", production); err != nil {
		return runtimeConfig{}, err
	}
	if config.backupInterval, err = configDuration(getenv, "BACKUP_INTERVAL", 0, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.backupRetention, err = configPositiveInt(getenv, "BACKUP_RETENTION_COUNT", 0, production); err != nil {
		return runtimeConfig{}, err
	}
	if config.webSocketQueueMaxBytes < (1<<20)+(256)+(16<<10) ||
		config.webSocketProcessQueueMaxBytes < config.webSocketQueueMaxBytes || config.webSocketInboundProcessMaxBytes < 2*uint64(8*((1<<20)+(4<<10))) || config.durableTransferMaxBytes < (1<<20)+256 || config.relayMemoryLimitBytes == 0 {
		return runtimeConfig{}, errors.New("invalid WebSocket memory limits")
	}
	connections := uint64(config.maxOpenConnections)
	if connections > ^uint64(0)/uint64((1<<20)+(4<<10)) || config.durableTransferMaxBytes > ^uint64(0)/2 {
		return runtimeConfig{}, errors.New("WebSocket memory limits overflow")
	}
	inbound := connections * uint64((1<<20)+(4<<10))
	workspace := 2 * config.durableTransferMaxBytes
	if config.webSocketInboundProcessMaxBytes > ^uint64(0)-inbound || config.webSocketProcessQueueMaxBytes > ^uint64(0)-inbound-config.webSocketInboundProcessMaxBytes || workspace > ^uint64(0)-inbound-config.webSocketInboundProcessMaxBytes-config.webSocketProcessQueueMaxBytes {
		return runtimeConfig{}, errors.New("WebSocket memory limits overflow")
	}
	controlled := inbound + config.webSocketInboundProcessMaxBytes + config.webSocketProcessQueueMaxBytes + workspace
	if controlled > config.relayMemoryLimitBytes-(config.relayMemoryLimitBytes/4) {
		return runtimeConfig{}, errors.New("WebSocket memory limits exceed safe container margin")
	}

	if production {
		if !filepath.IsAbs(config.boltPath) || pathWithin(config.boltPath, "/tmp") {
			return runtimeConfig{}, errors.New("production BOLT_PATH must be absolute and outside /tmp")
		}
		if !config.trustProxyHeaders {
			return runtimeConfig{}, errors.New("production requires TRUST_PROXY_HEADERS=true")
		}
		if !config.requireMutualPairSignatures {
			return runtimeConfig{}, errors.New("production requires REQUIRE_MUTUAL_PAIR_SIGNATURES=true")
		}
		if config.minFreeDiskBytes == 0 {
			return runtimeConfig{}, errors.New("production MIN_FREE_DISK_BYTES must be positive")
		}
		if !filepath.IsAbs(config.backupDir) {
			return runtimeConfig{}, errors.New("production BACKUP_DIR must be absolute")
		}
		if filepath.Clean(config.backupDir) == filepath.Clean(filepath.Dir(config.boltPath)) {
			return runtimeConfig{}, errors.New("production BACKUP_DIR must differ from the database directory")
		}
		if config.backupInterval <= 0 || config.backupRetention <= 0 {
			return runtimeConfig{}, errors.New("production backup interval and retention must be positive")
		}
	}

	return config, nil
}

func configString(getenv func(string) string, key, fallback string, required bool) (string, error) {
	value := getenv(key)
	if value == "" {
		if required {
			return "", fmt.Errorf("%s is required", key)
		}
		return fallback, nil
	}
	return value, nil
}

func configBool(getenv func(string) string, key string, fallback, required bool) (bool, error) {
	value := getenv(key)
	if value == "" {
		if required {
			return false, fmt.Errorf("%s is required", key)
		}
		return fallback, nil
	}
	if value != "true" && value != "false" {
		return false, fmt.Errorf("%s must be true or false", key)
	}
	return value == "true", nil
}

func configUint64(getenv func(string) string, key string, fallback uint64, required bool) (uint64, error) {
	value := getenv(key)
	if value == "" {
		if required {
			return 0, fmt.Errorf("%s is required", key)
		}
		return fallback, nil
	}
	parsed, err := strconv.ParseUint(value, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%s must be an unsigned integer: %w", key, err)
	}
	if required && parsed == 0 {
		return 0, fmt.Errorf("%s must be positive", key)
	}
	return parsed, nil
}

func configPositiveInt(getenv func(string) string, key string, fallback int, required bool) (int, error) {
	value := getenv(key)
	if value == "" {
		if required {
			return 0, fmt.Errorf("%s is required", key)
		}
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", key)
	}
	return parsed, nil
}

func configDuration(getenv func(string) string, key string, fallback time.Duration, required bool) (time.Duration, error) {
	value := getenv(key)
	if value == "" {
		if required {
			return 0, fmt.Errorf("%s is required", key)
		}
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive duration", key)
	}
	return parsed, nil
}

func pathWithin(path, root string) bool {
	cleanPath := filepath.Clean(path)
	cleanRoot := filepath.Clean(root)
	return cleanPath == cleanRoot || strings.HasPrefix(cleanPath, cleanRoot+string(filepath.Separator))
}
