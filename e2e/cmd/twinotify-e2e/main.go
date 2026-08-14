package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/adb"
	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/metrics"
	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

func main() {
	scenario := flag.String("scenario", "status", "scenario identifier (status or pair)")
	serialA := flag.String("serial-a", "emulator-5554", "ADB serial for device A")
	serialB := flag.String("serial-b", "emulator-5556", "ADB serial for device B")
	tokenA := flag.String("token-a", "", "device A E2E token (read from run-as when omitted)")
	tokenB := flag.String("token-b", "", "device B E2E token (read from run-as when omitted)")
	packageName := flag.String("package", "com.twinotify.app", "Android application package")
	relayURL := flag.String("relay-url", "", "relay HTTP origin; defaults to http://10.0.2.2:<relay-port>")
	relayPort := flag.Int("relay-port", 0, "host relay port used with the emulator loopback address")
	timeout := flag.Duration("timeout", 30*time.Second, "bounded wait for each control phase")
	flag.Parse()
	if *scenario == "" {
		fmt.Fprintln(os.Stderr, "-scenario must not be empty")
		os.Exit(2)
	}
	options := options{scenario: *scenario, serialA: *serialA, serialB: *serialB,
		tokenA: *tokenA, tokenB: *tokenB, packageName: *packageName,
		relayURL: *relayURL, relayPort: *relayPort, timeout: *timeout}
	if err := runWithOptions(context.Background(), options); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

type options struct {
	scenario, serialA, serialB, tokenA, tokenB, packageName, relayURL string
	relayPort                                                         int
	timeout                                                           time.Duration
}

// run is kept small for callers that only need CLI argument validation.
func run(ctx context.Context, scenario string) error {
	return runWithOptions(ctx, options{scenario: scenario, serialA: "emulator-5554", serialB: "emulator-5556", packageName: "com.twinotify.app", timeout: 30 * time.Second})
}

func runWithOptions(ctx context.Context, cfg options) error {
	if cfg.scenario != "status" && cfg.scenario != "pair" {
		if err := metrics.ValidateScenarioID(cfg.scenario); err == nil {
			return scenario.ErrUnsupportedEnvironment
		}
		if _, err := scenario.Plan(cfg.scenario); err != nil {
			return err
		}
	}
	if strings.TrimSpace(cfg.serialA) == "" || strings.TrimSpace(cfg.serialB) == "" || cfg.serialA == cfg.serialB {
		return errors.New("two distinct ADB serials are required")
	}
	if cfg.timeout <= 0 {
		return errors.New("timeout must be positive")
	}
	if cfg.scenario == "pair" && strings.TrimSpace(cfg.relayURL) == "" {
		if cfg.relayPort <= 0 {
			return errors.New("relay URL or positive relay port is required")
		}
		cfg.relayURL = fmt.Sprintf("http://10.0.2.2:%d", cfg.relayPort)
	}

	adbA, adbB := adb.New(nil, cfg.serialA), adb.New(nil, cfg.serialB)
	if cfg.tokenA == "" {
		var err error
		cfg.tokenA, err = readToken(ctx, adbA, cfg.packageName)
		if err != nil {
			return fmt.Errorf("read device A session token: %w", err)
		}
	}
	if cfg.tokenB == "" {
		var err error
		cfg.tokenB, err = readToken(ctx, adbB, cfg.packageName)
		if err != nil {
			return fmt.Errorf("read device B session token: %w", err)
		}
	}
	a := control.New(adbDevice{client: adbA, packageName: cfg.packageName}, cfg.serialA, cfg.tokenA, cfg.timeout)
	b := control.New(adbDevice{client: adbB, packageName: cfg.packageName}, cfg.serialB, cfg.tokenB, cfg.timeout)
	if cfg.scenario == "pair" {
		return control.NewController(a, b, cfg.timeout).Pair(ctx, control.PairOptions{RelayURL: cfg.relayURL, DisplayNameA: "emulator-a", DisplayNameB: "emulator-b"})
	}
	if cfg.scenario != "status" {
		bridge := scenario.ADBBridge{A: a, B: b, ADBA: adbA, ADBB: adbB, Package: cfg.packageName}
		if cfg.scenario == "call-state" {
			_, err := scenario.RunSyntheticCallState(ctx, bridge, cfg.timeout)
			return err
		}
		return scenario.NewExecutor(bridge, cfg.timeout).Run(ctx, cfg.scenario)
	}
	for _, device := range []struct {
		label  string
		client *control.Client
	}{{"A", a}, {"B", b}} {
		label, client := device.label, device.client
		result, err := client.Execute(ctx, control.Command{RequestID: "e2e-status-" + strings.ToLower(label), Name: "STATUS"})
		if err != nil {
			return fmt.Errorf("status %s: %w", label, err)
		}
		fmt.Printf("%s %s\n", label, result.Payload)
	}
	return nil
}

type adbDevice struct {
	client      *adb.Client
	packageName string
}

func (d adbDevice) Broadcast(ctx context.Context, command control.Command) error {
	extras := map[string]string{"request_id": command.RequestID, "command": command.Name, "token": command.Token}
	for key, value := range command.Params {
		extras[key] = value
	}
	if err := d.client.BroadcastReceiver(ctx, d.packageName, "co.twinotify.core.e2e.E2eControlReceiver", "co.twinotify.e2e.CONTROL", extras); err != nil {
		if errors.Is(err, adb.ErrDeviceOffline) {
			return fmt.Errorf("%w: %v", control.ErrDeviceOffline, err)
		}
		return err
	}
	return nil
}

func (d adbDevice) ReadResult(ctx context.Context, requestID string) ([]byte, error) {
	if requestID == "" || strings.ContainsAny(requestID, "/\\") {
		return nil, errors.New("unsafe control request ID")
	}
	value, err := d.client.ReadRunAs(ctx, d.packageName, "files/e2e-results/"+requestID+".json")
	if errors.Is(err, adb.ErrNotFound) {
		return nil, control.ErrResultNotReady
	}
	if errors.Is(err, adb.ErrDeviceOffline) {
		return nil, fmt.Errorf("%w: %v", control.ErrDeviceOffline, err)
	}
	return value, err
}

func readToken(ctx context.Context, client *adb.Client, packageName string) (string, error) {
	value, err := client.ReadRunAs(ctx, packageName, "files/e2e-token")
	if err != nil {
		return "", err
	}
	token := strings.TrimSpace(string(value))
	if token == "" || strings.ContainsAny(token, "\r\n \t") {
		return "", errors.New("device returned an invalid E2E token")
	}
	return token, nil
}
