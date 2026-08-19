package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"sync/atomic"
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
	packageName := flag.String("package", "com.twinotify.app", "Android application package")
	relayURL := flag.String("relay-url", "", "relay HTTP origin; defaults to http://10.0.2.2:<relay-port>")
	relayPort := flag.Int("relay-port", 0, "host relay port used with the emulator loopback address")
	timeout := flag.Duration("timeout", 30*time.Second, "bounded wait for each control phase")
	evidenceDir := flag.String("evidence-dir", "", "private directory for offline pairing evidence")
	scenarioEvidenceDir := flag.String("scenario-evidence-dir", "", "directory for sanitized scenario result artifacts")
	packetEvidenceHash := flag.String("packet-evidence-sha256", "", "SHA-256 of operator-captured internet-block packet evidence")
	dnsEvidenceHash := flag.String("dns-evidence-sha256", "", "SHA-256 of operator-captured DNS evidence")
	internetBlocked := flag.Bool("internet-blocked", false, "assert operator-controlled internet isolation is active")
	flag.Parse()
	if *scenario == "" {
		fmt.Fprintln(os.Stderr, "-scenario must not be empty")
		os.Exit(2)
	}
	options := options{scenario: *scenario, serialA: *serialA, serialB: *serialB,
		packageName: *packageName,
		relayURL:    *relayURL, relayPort: *relayPort, timeout: *timeout}
	options.evidenceDir = *evidenceDir
	options.scenarioEvidenceDir = *scenarioEvidenceDir
	options.packetEvidenceHash = *packetEvidenceHash
	options.dnsEvidenceHash = *dnsEvidenceHash
	options.internetBlocked = *internetBlocked
	if err := runWithOptions(context.Background(), options); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

type options struct {
	scenario, serialA, serialB, packageName, relayURL                     string
	evidenceDir, scenarioEvidenceDir, packetEvidenceHash, dnsEvidenceHash string
	internetBlocked                                                       bool
	relayPort                                                             int
	timeout                                                               time.Duration
}

// run is kept small for callers that only need CLI argument validation.
func run(ctx context.Context, scenario string) error {
	return runWithOptions(ctx, options{scenario: scenario, serialA: "emulator-5554", serialB: "emulator-5556", packageName: "com.twinotify.app", timeout: 30 * time.Second})
}

func runWithOptions(ctx context.Context, cfg options) error {
	if err := adb.ValidateComponentName(cfg.packageName); err != nil {
		return fmt.Errorf("invalid Android package: %w", err)
	}
	if err := validateScenarioBeforeADB(cfg.scenario); err != nil {
		if cfg.scenarioEvidenceDir != "" {
			result := scenario.ScenarioResult{Scenario: cfg.scenario, Status: "failed", Before: map[string]scenario.Observation{}, After: map[string]scenario.Observation{}, ErrorCode: scenario.ErrorCode(err)}
			if evidenceErr := scenario.WriteEvidenceArtifacts(cfg.scenarioEvidenceDir, result); evidenceErr != nil {
				return fmt.Errorf("%w; write scenario evidence: %v", err, evidenceErr)
			}
		}
		return err
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
	tokenA, err := readToken(ctx, adbA, cfg.packageName)
	if err != nil {
		return fmt.Errorf("read device A session token: %w", err)
	}
	tokenB, err := readToken(ctx, adbB, cfg.packageName)
	if err != nil {
		return fmt.Errorf("read device B session token: %w", err)
	}
	a := control.New(adbDevice{client: adbA, packageName: cfg.packageName}, cfg.serialA, tokenA, cfg.timeout)
	b := control.New(adbDevice{client: adbB, packageName: cfg.packageName}, cfg.serialB, tokenB, cfg.timeout)
	if cfg.scenario == "offline-pairing" {
		host, err := newOfflineADBHost(a, b, adbA, adbB, cfg.packageName)
		if err != nil {
			return err
		}
		result, err := scenario.RunOfflinePairing(ctx, host, scenario.OfflinePairingOptions{
			SerialA: cfg.serialA, SerialB: cfg.serialB, DisplayNameA: "Phone A", DisplayNameB: "Phone B", Timeout: cfg.timeout,
			Topology: scenario.OfflineTopologyEvidence{InternetBlocked: cfg.internetBlocked, PacketEvidenceSHA256: cfg.packetEvidenceHash, DNSEvidenceSHA256: cfg.dnsEvidenceHash},
		})
		if err != nil {
			return err
		}
		path, err := scenario.WriteOfflinePairingEvidence(cfg.evidenceDir, result)
		if err != nil {
			return err
		}
		fmt.Printf("offline pairing evidence: %s\n", path)
		return nil
	}
	if cfg.scenario == "pair" {
		return control.NewController(a, b, cfg.timeout).Pair(ctx, control.PairOptions{RelayURL: cfg.relayURL, DisplayNameA: "emulator-a", DisplayNameB: "emulator-b"})
	}
	if cfg.scenario != "status" {
		bridge := scenario.ADBBridge{A: a, B: b, ADBA: adbA, ADBB: adbB, Package: cfg.packageName}
		if cfg.scenario == "call-state" {
			_, err := scenario.RunSyntheticCallState(ctx, bridge, cfg.timeout)
			return err
		}
		result, runErr := scenario.NewExecutor(bridge, cfg.timeout).RunResult(ctx, cfg.scenario)
		if cfg.scenarioEvidenceDir != "" {
			if evidenceErr := scenario.WriteEvidenceArtifacts(cfg.scenarioEvidenceDir, result); evidenceErr != nil {
				return fmt.Errorf("write scenario evidence: %w", evidenceErr)
			}
		}
		return runErr
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

func validateScenarioBeforeADB(name string) error {
	if name == "status" || name == "pair" || name == "offline-pairing" || name == "call-state" {
		return nil
	}
	if err := metrics.ValidateScenarioID(name); err == nil {
		return scenario.ErrUnsupportedEnvironment
	}
	plan, err := scenario.Plan(name)
	if err != nil {
		return err
	}
	return scenario.ValidateExecutablePlan(plan)
}

type adbDevice struct {
	client      *adb.Client
	packageName string
}

func (d adbDevice) Broadcast(ctx context.Context, command control.Command) error {
	if err := d.client.WriteRunAsPrivate(ctx, d.packageName, "e2e-auth", command.RequestID, []byte(command.Token)); err != nil {
		return err
	}
	extras := map[string]string{"request_id": command.RequestID, "command": command.Name, "auth_input_id": command.RequestID}
	for key, value := range command.Params {
		extras[key] = value
	}
	if err := d.client.BroadcastReceiver(ctx, d.packageName, "co.twinotify.core.e2e.E2eControlReceiver", "co.twinotify.e2e.CONTROL", extras); err != nil {
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Second)
		_ = d.client.DeleteRunAsPrivate(cleanup, d.packageName, "e2e-auth", command.RequestID)
		cancel()
		if errors.Is(err, adb.ErrDeviceOffline) {
			return fmt.Errorf("%w: %v", control.ErrDeviceOffline, err)
		}
		return err
	}
	return nil
}

func (d adbDevice) BoundRequestID(token, command string) (string, error) {
	return control.NewBoundRequestID(token, command, time.Now(), rand.Reader)
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

func (d adbDevice) WriteSecret(ctx context.Context, requestID string, value []byte) error {
	return d.client.WriteRunAsPrivate(ctx, d.packageName, "e2e-inputs", requestID, value)
}

func (d adbDevice) ReadSecretOnce(ctx context.Context, requestID string) ([]byte, error) {
	return d.client.ReadRunAsPrivateOnce(ctx, d.packageName, "e2e-secrets", requestID, 4096)
}

func (d adbDevice) CleanupPrivateInput(ctx context.Context, requestID string) error {
	return d.client.DeleteRunAsPrivate(ctx, d.packageName, "e2e-inputs", requestID)
}

func (d adbDevice) CleanupPrivateAuth(ctx context.Context, requestID string) error {
	return d.client.DeleteRunAsPrivate(ctx, d.packageName, "e2e-auth", requestID)
}

func (d adbDevice) CleanupPrivateOutput(ctx context.Context, requestID string) error {
	return d.client.DeleteRunAsPrivate(ctx, d.packageName, "e2e-secrets", requestID)
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

type offlineADBHost struct {
	a, b          *control.Client
	adbA, adbB    *adb.Client
	packageName   string
	seq           uint64
	requestPrefix string
}

func newOfflineADBHost(a, b *control.Client, adbA, adbB *adb.Client, packageName string) (*offlineADBHost, error) {
	var nonce [8]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, errors.New("offline request nonce unavailable")
	}
	return &offlineADBHost{a: a, b: b, adbA: adbA, adbB: adbB, packageName: packageName, requestPrefix: fmt.Sprintf("%x", nonce)}, nil
}

func (h *offlineADBHost) clients(serial string) (*control.Client, *adb.Client, error) {
	switch serial {
	case h.adbA.Serial():
		return h.a, h.adbA, nil
	case h.adbB.Serial():
		return h.b, h.adbB, nil
	default:
		return nil, nil, errors.New("unknown hashed device handle")
	}
}
func (h *offlineADBHost) Hardware(ctx context.Context, serial string) (bool, error) {
	_, device, err := h.clients(serial)
	if err != nil {
		return false, err
	}
	return device.IsHardware(ctx)
}
func (h *offlineADBHost) DisableMobileData(ctx context.Context, serial string) error {
	_, device, err := h.clients(serial)
	if err != nil {
		return err
	}
	return device.DisableMobileData(ctx)
}
func (h *offlineADBHost) WiFiNetworkHash(ctx context.Context, serial string) (string, error) {
	_, device, err := h.clients(serial)
	if err != nil {
		return "", err
	}
	return device.WiFiNetworkHash(ctx)
}
func (h *offlineADBHost) Query(ctx context.Context, serial string) (scenario.OfflinePairingSnapshot, scenario.OfflinePairingSecret, error) {
	client, _, err := h.clients(serial)
	if err != nil {
		return scenario.OfflinePairingSnapshot{}, scenario.OfflinePairingSecret{}, err
	}
	result, private, err := client.ExecuteSecret(ctx, control.Command{RequestID: h.requestID("query"), Name: "OFFLINE_PAIR_QUERY"}, nil)
	if err != nil {
		return scenario.OfflinePairingSnapshot{}, scenario.OfflinePairingSecret{}, err
	}
	if result.Code != "ok" {
		return scenario.OfflinePairingSnapshot{}, scenario.OfflinePairingSecret{}, fmt.Errorf("offline query failed: %s", result.Code)
	}
	snapshot, err := parseOfflineSnapshot(result.Payload)
	if err != nil {
		clear(private)
		return snapshot, scenario.OfflinePairingSecret{}, err
	}
	secret, err := parseOfflineSecret(private)
	clear(private)
	return snapshot, secret, err
}
func (h *offlineADBHost) Start(ctx context.Context, serial, displayName string) (scenario.OfflinePairingSnapshot, scenario.OfflinePairingSecret, error) {
	client, _, err := h.clients(serial)
	if err != nil {
		return scenario.OfflinePairingSnapshot{}, scenario.OfflinePairingSecret{}, err
	}
	result, private, err := client.ExecuteSecret(ctx, control.Command{RequestID: h.requestID("start"), Name: "OFFLINE_PAIR_START", Params: map[string]string{"display_name": displayName}}, nil)
	if err != nil {
		return scenario.OfflinePairingSnapshot{}, scenario.OfflinePairingSecret{}, err
	}
	if result.Code != "ok" {
		clear(private)
		return scenario.OfflinePairingSnapshot{}, scenario.OfflinePairingSecret{}, fmt.Errorf("offline start failed: %s", result.Code)
	}
	snapshot, err := parseOfflineSnapshot(result.Payload)
	if err != nil {
		clear(private)
		return snapshot, scenario.OfflinePairingSecret{}, err
	}
	return snapshot, scenario.OfflinePairingSecret{QR: private}, err
}
func (h *offlineADBHost) Join(ctx context.Context, serial, displayName string, qr []byte) error {
	return h.secretCommand(ctx, serial, "join", "OFFLINE_PAIR_JOIN", map[string]string{"display_name": displayName}, qr)
}
func (h *offlineADBHost) Confirm(ctx context.Context, serial, sessionID string) error {
	return h.secretCommand(ctx, serial, "confirm", "OFFLINE_PAIR_CONFIRM", nil, []byte(sessionID))
}
func (h *offlineADBHost) Cancel(ctx context.Context, serial, sessionID string) error {
	return h.secretCommand(ctx, serial, "cancel", "OFFLINE_PAIR_CANCEL", nil, []byte(sessionID))
}
func (h *offlineADBHost) secretCommand(ctx context.Context, serial, prefix, name string, params map[string]string, secret []byte) error {
	client, _, err := h.clients(serial)
	if err != nil {
		return err
	}
	result, private, err := client.ExecuteSecret(ctx, control.Command{RequestID: h.requestID(prefix), Name: name, Params: params}, secret)
	clear(private)
	if err != nil {
		return err
	}
	if result.Code != "ok" {
		return fmt.Errorf("offline control %s failed: %s", prefix, result.Code)
	}
	return nil
}
func (h *offlineADBHost) RestartProcess(ctx context.Context, serial string) error {
	_, device, err := h.clients(serial)
	if err != nil {
		return err
	}
	if err = device.ForceStop(ctx, h.packageName); err != nil {
		return err
	}
	return device.StartPackage(ctx, h.packageName)
}
func (h *offlineADBHost) requestID(prefix string) string {
	return fmt.Sprintf("offline-%s-%s-%d", h.requestPrefix, prefix, atomic.AddUint64(&h.seq, 1))
}

func parseOfflineSnapshot(raw []byte) (scenario.OfflinePairingSnapshot, error) {
	if len(raw) > 65_536 {
		return scenario.OfflinePairingSnapshot{}, errors.New("offline snapshot exceeds bound")
	}
	var envelope struct {
		Offline                       scenario.OfflinePairingSnapshot `json:"offline_pairing"`
		DeviceApplicationIdentityHash string                          `json:"device_application_identity_hash"`
		PeerApplicationIdentityHash   string                          `json:"peer_application_identity_hash"`
		LanBindingPresent             bool                            `json:"lan_binding_present"`
		LocalTLSPinHash               string                          `json:"local_tls_pin_hash"`
		PeerTLSPinHash                string                          `json:"peer_tls_pin_hash"`
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&envelope); err != nil {
		return scenario.OfflinePairingSnapshot{}, err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return scenario.OfflinePairingSnapshot{}, errors.New("offline snapshot contains trailing JSON")
	}
	value := envelope.Offline
	value.DeviceApplicationIdentityHash, value.PeerApplicationIdentityHash = envelope.DeviceApplicationIdentityHash, envelope.PeerApplicationIdentityHash
	value.LanBindingPresent, value.LocalTLSPinHash, value.PeerTLSPinHash = envelope.LanBindingPresent, envelope.LocalTLSPinHash, envelope.PeerTLSPinHash
	allowedPhase := map[string]bool{"idle": true, "advertising": true, "resolving": true, "tls_authenticated": true, "verify_code": true, "local_confirmed": true, "mutually_signed": true, "committed": true, "complete": true}
	if !allowedPhase[value.Phase] {
		return scenario.OfflinePairingSnapshot{}, errors.New("offline snapshot phase is not allowlisted")
	}
	if value.Role != "" && value.Role != "initiator" && value.Role != "joiner" {
		return scenario.OfflinePairingSnapshot{}, errors.New("offline snapshot role is not allowlisted")
	}
	allowedError := map[string]bool{"": true, "pair_session_active": true, "pair_session_not_found": true, "pair_session_mismatch": true, "pair_invalid_display_name": true, "pair_invalid_qr": true, "pair_runtime_unavailable": true, "expired": true, "tls_pin_mismatch": true, "identity_mismatch": true, "invalid_frame": true, "commit_failed": true, "cancelled": true, "peer_rejected": true, "wifi_permission_denied": true, "wifi_unavailable": true}
	if !allowedError[value.ErrorCode] {
		return scenario.OfflinePairingSnapshot{}, errors.New("offline snapshot error is not allowlisted")
	}
	for _, hash := range []string{value.SessionIDHash, value.SASHash, value.DeviceApplicationIdentityHash, value.PeerApplicationIdentityHash, value.LocalTLSPinHash, value.PeerTLSPinHash} {
		if hash != "" && (len(hash) != 64 || strings.Trim(hash, "0123456789abcdef") != "") {
			return scenario.OfflinePairingSnapshot{}, errors.New("offline snapshot hash is invalid")
		}
	}
	return value, nil
}

func parseOfflineSecret(raw []byte) (scenario.OfflinePairingSecret, error) {
	if len(raw) > 4096 {
		return scenario.OfflinePairingSecret{}, errors.New("offline private status exceeds bound")
	}
	var value struct {
		SessionID string `json:"session_id"`
		SAS       string `json:"sas"`
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&value); err != nil {
		return scenario.OfflinePairingSecret{}, errors.New("offline private status invalid")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return scenario.OfflinePairingSecret{}, errors.New("offline private status invalid")
	}
	return scenario.OfflinePairingSecret{SessionID: value.SessionID, SAS: value.SAS}, nil
}
