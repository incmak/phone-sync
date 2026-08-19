package scenario

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const (
	hashA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	hashB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	hashC = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	hashD = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
)

type fakeOfflinePairingHost struct {
	events               []string
	statusCalls          map[string]int
	stale                bool
	existingPeer         bool
	terminalIdle         string
	mismatchSAS          bool
	startSideEffectError bool
	started              bool
	cancelled            map[string]bool
	restarted            map[string]bool
}

func (f *fakeOfflinePairingHost) Hardware(_ context.Context, serial string) (bool, error) {
	f.events = append(f.events, "hardware:"+serial)
	return true, nil
}
func (f *fakeOfflinePairingHost) DisableMobileData(_ context.Context, serial string) error {
	f.events = append(f.events, "mobile-data-off:"+serial)
	return nil
}
func (f *fakeOfflinePairingHost) WiFiNetworkHash(_ context.Context, serial string) (string, error) {
	f.events = append(f.events, "wifi:"+serial)
	return hashC, nil
}
func (f *fakeOfflinePairingHost) Query(_ context.Context, serial string) (OfflinePairingSnapshot, OfflinePairingSecret, error) {
	f.statusCalls[serial]++
	call := f.statusCalls[serial]
	if f.stale && call == 1 {
		return OfflinePairingSnapshot{Phase: "complete", Completed: true, LanBindingPresent: true}, OfflinePairingSecret{}, nil
	}
	if f.existingPeer && call == 1 {
		return OfflinePairingSnapshot{Phase: "idle", PeerApplicationIdentityHash: hashB}, OfflinePairingSecret{}, nil
	}
	if f.terminalIdle != "" && call == 1 {
		return OfflinePairingSnapshot{Phase: "idle", Role: "initiator", ErrorCode: f.terminalIdle, SessionIDHash: hashD, SASHash: hashA}, OfflinePairingSecret{}, nil
	}
	if call == 1 {
		return OfflinePairingSnapshot{Phase: "idle"}, OfflinePairingSecret{}, nil
	}
	if f.startSideEffectError && serial == "phone-a" && !f.cancelled[serial] {
		return OfflinePairingSnapshot{Phase: "advertising", SessionIDHash: hashD}, OfflinePairingSecret{SessionID: "00000000-0000-4000-8000-000000000001"}, nil
	}
	if call == 2 {
		sas := "123456"
		if f.mismatchSAS && serial == "phone-b" {
			sas = "654321"
		}
		return OfflinePairingSnapshot{Phase: "verify_code", SessionIDHash: hashD, SASHash: hashA}, OfflinePairingSecret{SessionID: "00000000-0000-4000-8000-000000000001", SAS: sas}, nil
	}
	return f.completed(serial), OfflinePairingSecret{}, nil
}
func (f *fakeOfflinePairingHost) Start(_ context.Context, serial, _ string) (OfflinePairingSnapshot, OfflinePairingSecret, error) {
	f.events = append(f.events, "start:"+serial)
	f.started = true
	if f.startSideEffectError {
		return OfflinePairingSnapshot{}, OfflinePairingSecret{}, errors.New("redacted start response failure")
	}
	return OfflinePairingSnapshot{Phase: "advertising", SessionIDHash: hashD}, OfflinePairingSecret{QR: []byte("opaque-qr-fixture")}, nil
}
func (f *fakeOfflinePairingHost) Join(_ context.Context, serial, _ string, qr []byte) error {
	if string(qr) != "opaque-qr-fixture" {
		return errors.New("QR not relayed in memory")
	}
	f.events = append(f.events, "join:"+serial)
	return nil
}
func (f *fakeOfflinePairingHost) Confirm(_ context.Context, serial, sessionID string) error {
	if sessionID == "" {
		return errors.New("missing session ID")
	}
	f.events = append(f.events, "confirm:"+serial)
	return nil
}
func (f *fakeOfflinePairingHost) Cancel(_ context.Context, serial, sessionID string) error {
	f.events = append(f.events, "cancel:"+serial)
	if f.cancelled == nil {
		f.cancelled = map[string]bool{}
	}
	f.cancelled[serial] = true
	return nil
}
func (f *fakeOfflinePairingHost) RestartProcess(_ context.Context, serial string) error {
	f.events = append(f.events, "restart:"+serial)
	if f.restarted == nil {
		f.restarted = map[string]bool{}
	}
	f.restarted[serial] = true
	return nil
}
func (f *fakeOfflinePairingHost) completed(serial string) OfflinePairingSnapshot {
	role := "initiator"
	if serial == "phone-b" {
		role = "joiner"
	}
	if f.restarted[serial] {
		role = ""
	}
	if serial == "phone-a" {
		return OfflinePairingSnapshot{Role: role, Phase: "complete", Completed: true, DeviceApplicationIdentityHash: hashA, PeerApplicationIdentityHash: hashB, LanBindingPresent: true, LocalTLSPinHash: hashC, PeerTLSPinHash: hashD}
	}
	return OfflinePairingSnapshot{Role: role, Phase: "complete", Completed: true, DeviceApplicationIdentityHash: hashB, PeerApplicationIdentityHash: hashA, LanBindingPresent: true, LocalTLSPinHash: hashD, PeerTLSPinHash: hashC}
}

func TestRunOfflinePairingUsesTwoHardwarePhonesAndNeverNeedsRelay(t *testing.T) {
	host := &fakeOfflinePairingHost{statusCalls: map[string]int{}}
	result, err := RunOfflinePairing(context.Background(), host, OfflinePairingOptions{
		SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second,
		Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !result.ProcessRestartPersisted || result.RelayRequired || result.LaptopServiceRequired {
		t.Fatalf("result=%+v", result)
	}
	wantOrder := []string{"hardware:phone-a", "hardware:phone-b", "mobile-data-off:phone-a", "mobile-data-off:phone-b", "wifi:phone-a", "wifi:phone-b", "start:phone-a", "join:phone-b", "confirm:phone-a", "confirm:phone-b", "restart:phone-a", "restart:phone-b"}
	joined := strings.Join(host.events, ",")
	position := -1
	for _, event := range wantOrder {
		next := strings.Index(joined[position+1:], event)
		if next < 0 {
			t.Fatalf("events=%v missing ordered %s", host.events, event)
		}
		position += next + 1
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"opaque-qr-fixture", "123456", "00000000-0000-4000-8000-000000000001"} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("result leaked secret: %s", encoded)
		}
	}
}

func TestRunOfflinePairingRejectsSameSerialEmulatorStaleStateAndMissingTopology(t *testing.T) {
	tests := []struct {
		name    string
		options OfflinePairingOptions
		stale   bool
	}{
		{"same serial", OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-a", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}}, false},
		{"emulator", OfflinePairingOptions{SerialA: "emulator-5554", SerialB: "phone-b", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}}, false},
		{"stale", OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}}, true},
		{"topology", OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second}, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			host := &fakeOfflinePairingHost{statusCalls: map[string]int{}, stale: tc.stale}
			if _, err := RunOfflinePairing(context.Background(), host, tc.options); err == nil {
				t.Fatal("expected rejection")
			}
		})
	}
}

func TestRunOfflinePairingRejectsExistingRelayPeerAsNotFresh(t *testing.T) {
	host := &fakeOfflinePairingHost{statusCalls: map[string]int{}, existingPeer: true}
	_, err := RunOfflinePairing(context.Background(), host, OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}})
	if err == nil || !strings.Contains(err.Error(), "fresh unpaired state") {
		t.Fatalf("error=%v, want fresh-state rejection", err)
	}
	if strings.Contains(strings.Join(host.events, ","), "start:") {
		t.Fatalf("pairing started for existing peer: %v", host.events)
	}
}

func TestRunOfflinePairingRejectsProductionTerminalIdleCancelledAndFailedStates(t *testing.T) {
	for _, code := range []string{"cancelled", "transport_unavailable"} {
		t.Run(code, func(t *testing.T) {
			host := &fakeOfflinePairingHost{statusCalls: map[string]int{}, terminalIdle: code}
			_, err := RunOfflinePairing(context.Background(), host, OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}})
			if err == nil || !strings.Contains(err.Error(), "fresh unpaired state") {
				t.Fatalf("error=%v", err)
			}
			if strings.Contains(strings.Join(host.events, ","), "mobile-data-off") {
				t.Fatalf("radio changed before rejection: %v", host.events)
			}
		})
	}
}

func TestRunOfflinePairingCancelsStartSideEffectWhenStartResponseFails(t *testing.T) {
	host := &fakeOfflinePairingHost{statusCalls: map[string]int{}, startSideEffectError: true, cancelled: map[string]bool{}}
	_, err := RunOfflinePairing(context.Background(), host, OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}})
	if err == nil {
		t.Fatal("expected start response failure")
	}
	if !host.cancelled["phone-a"] {
		t.Fatalf("start-side effect was not cancelled: %v", host.events)
	}
}

func TestRunOfflinePairingRejectsMismatchedSASWithBoundedSecretFreeSnapshot(t *testing.T) {
	host := &fakeOfflinePairingHost{statusCalls: map[string]int{}, mismatchSAS: true}
	_, err := RunOfflinePairing(context.Background(), host, OfflinePairingOptions{SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second, Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}})
	var failure *OfflinePairingFailure
	if !errors.As(err, &failure) {
		t.Fatalf("error=%v", err)
	}
	encoded, marshalErr := json.Marshal(failure.Snapshot)
	if marshalErr != nil {
		t.Fatal(marshalErr)
	}
	if len(encoded) > MaxOfflinePairingFailureBytes {
		t.Fatalf("failure snapshot unbounded: %d", len(encoded))
	}
	for _, forbidden := range []string{"123456", "654321", "00000000-0000-4000-8000-000000000001", "opaque-qr"} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("failure leaked secret: %s", encoded)
		}
	}
	joined := strings.Join(host.events, ",")
	if !strings.Contains(joined, "cancel:phone-a") || !strings.Contains(joined, "cancel:phone-b") {
		t.Fatalf("active sessions were not cancelled after failure: %v", host.events)
	}
}

func TestWriteOfflinePairingEvidenceUsesPrivateBoundedSecretFreeArtifact(t *testing.T) {
	root := filepath.Join(t.TempDir(), "evidence")
	restarted := &fakeOfflinePairingHost{restarted: map[string]bool{"phone-a": true, "phone-b": true}}
	result := OfflinePairingResult{Result: "pass", SerialAHash: hashA, SerialBHash: hashB, WiFiNetworkHash: hashC, CeremonyRoles: OfflineCeremonyRoles{DeviceA: "initiator", DeviceB: "joiner"}, DeviceA: restarted.completed("phone-a"), DeviceB: restarted.completed("phone-b"), Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB}, MobileDataDisabled: true, ProcessRestartPersisted: true}
	path, err := WriteOfflinePairingEvidence(root, result)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode=%o", info.Mode().Perm())
	}
	entries, err := os.ReadDir(root)
	if err != nil || len(entries) != 1 || entries[0].Name() != "offline-pairing.json" {
		t.Fatalf("atomic evidence inventory=%v err=%v", entries, err)
	}
	if _, err := WriteOfflinePairingEvidence(root, result); err == nil {
		t.Fatal("expected immutable exclusive evidence publication")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(content) == 0 || len(content) > MaxOfflinePairingEvidenceBytes {
		t.Fatalf("artifact size=%d", len(content))
	}
	for _, forbidden := range []string{"qr", "session_id\"", "\"sas\"", "transcript", "secret"} {
		if strings.Contains(strings.ToLower(string(content)), forbidden) {
			t.Fatalf("artifact leaked forbidden field: %s", content)
		}
	}
	realRoot := filepath.Join(t.TempDir(), "real")
	if err := os.Mkdir(realRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	linkedRoot := filepath.Join(t.TempDir(), "linked")
	if err := os.Symlink(realRoot, linkedRoot); err != nil {
		t.Fatal(err)
	}
	if _, err := WriteOfflinePairingEvidence(linkedRoot, result); err == nil {
		t.Fatal("expected symlink evidence directory rejection")
	}
}

func TestRunOfflinePairingEvidenceMatchesAuthoritativeVerifier(t *testing.T) {
	host := &fakeOfflinePairingHost{statusCalls: map[string]int{}}
	result, err := RunOfflinePairing(context.Background(), host, OfflinePairingOptions{
		SerialA: "phone-a", SerialB: "phone-b", Timeout: time.Second,
		Topology: OfflineTopologyEvidence{InternetBlocked: true, PacketEvidenceSHA256: hashA, DNSEvidenceSHA256: hashB},
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.DeviceA.Role != "" || result.DeviceB.Role != "" {
		t.Fatalf("post-restart snapshots retained transient roles: %+v %+v", result.DeviceA, result.DeviceB)
	}
	root := filepath.Join(t.TempDir(), "evidence")
	if _, err := WriteOfflinePairingEvidence(root, result); err != nil {
		t.Fatal(err)
	}
	verifier, err := filepath.Abs(filepath.Join("..", "..", "..", "scripts", "verify-offline-pairing-evidence.sh"))
	if err != nil {
		t.Fatal(err)
	}
	if output, err := exec.Command(verifier, root).CombinedOutput(); err != nil {
		t.Fatalf("authoritative verifier rejected RunOfflinePairing output: %v: %s", err, output)
	}
}
