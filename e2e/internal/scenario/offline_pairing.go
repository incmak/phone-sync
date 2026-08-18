package scenario

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const MaxOfflinePairingFailureBytes = 4096
const MaxOfflinePairingEvidenceBytes = 16_384

var (
	offlineHashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
	offlineSASPattern  = regexp.MustCompile(`^[0-9]{6}$`)
)

// OfflinePairingSnapshot is deliberately restricted to bounded state codes and
// one-way hashes. Raw QR, transcript, session identifiers, SAS, keys and LAN
// secrets do not have fields in this type and therefore cannot enter evidence.
type OfflinePairingSnapshot struct {
	Role                          string `json:"role,omitempty"`
	Phase                         string `json:"phase"`
	ErrorCode                     string `json:"error_code,omitempty"`
	Completed                     bool   `json:"completed"`
	SessionIDHash                 string `json:"session_id_hash,omitempty"`
	SASHash                       string `json:"sas_hash,omitempty"`
	DeviceApplicationIdentityHash string `json:"device_application_identity_hash,omitempty"`
	PeerApplicationIdentityHash   string `json:"peer_application_identity_hash,omitempty"`
	LanBindingPresent             bool   `json:"lan_binding_present"`
	LocalTLSPinHash               string `json:"local_tls_pin_hash,omitempty"`
	PeerTLSPinHash                string `json:"peer_tls_pin_hash,omitempty"`
}

// OfflinePairingSecret exists only for the authenticated in-memory control
// handoff. It must never be marshalled, logged or included in an error.
type OfflinePairingSecret struct {
	QR        []byte `json:"-"`
	SessionID string `json:"-"`
	SAS       string `json:"-"`
}

type OfflineTopologyEvidence struct {
	InternetBlocked      bool   `json:"internet_blocked"`
	PacketEvidenceSHA256 string `json:"packet_evidence_sha256"`
	DNSEvidenceSHA256    string `json:"dns_evidence_sha256"`
}

type OfflinePairingOptions struct {
	SerialA, SerialB string
	DisplayNameA     string
	DisplayNameB     string
	Timeout          time.Duration
	Topology         OfflineTopologyEvidence
}

type OfflinePairingResult struct {
	Result                  string                  `json:"result"`
	SerialAHash             string                  `json:"serial_a_hash"`
	SerialBHash             string                  `json:"serial_b_hash"`
	WiFiNetworkHash         string                  `json:"wifi_network_hash"`
	DeviceA                 OfflinePairingSnapshot  `json:"device_a"`
	DeviceB                 OfflinePairingSnapshot  `json:"device_b"`
	Topology                OfflineTopologyEvidence `json:"topology"`
	MobileDataDisabled      bool                    `json:"mobile_data_disabled"`
	ProcessRestartPersisted bool                    `json:"process_restart_persisted"`
	RelayRequired           bool                    `json:"relay_required"`
	LaptopServiceRequired   bool                    `json:"laptop_service_required"`
}

type OfflinePairingFailureSnapshot struct {
	Stage   string                 `json:"stage"`
	DeviceA OfflinePairingSnapshot `json:"device_a"`
	DeviceB OfflinePairingSnapshot `json:"device_b"`
}

type OfflinePairingFailure struct {
	Stage    string
	Cause    error
	Snapshot OfflinePairingFailureSnapshot
}

func (e *OfflinePairingFailure) Error() string {
	return fmt.Sprintf("offline pairing %s failed", e.Stage)
}
func (e *OfflinePairingFailure) Unwrap() error { return e.Cause }

type OfflinePairingHost interface {
	Hardware(context.Context, string) (bool, error)
	DisableMobileData(context.Context, string) error
	WiFiNetworkHash(context.Context, string) (string, error)
	Query(context.Context, string) (OfflinePairingSnapshot, OfflinePairingSecret, error)
	Start(context.Context, string, string) (OfflinePairingSnapshot, OfflinePairingSecret, error)
	Join(context.Context, string, string, []byte) error
	Confirm(context.Context, string, string) error
	Cancel(context.Context, string, string) error
	RestartProcess(context.Context, string) error
}

func RunOfflinePairing(ctx context.Context, host OfflinePairingHost, options OfflinePairingOptions) (OfflinePairingResult, error) {
	if host == nil {
		return OfflinePairingResult{}, errors.New("offline pairing host is required")
	}
	if err := validateOfflinePairingOptions(options); err != nil {
		return OfflinePairingResult{}, err
	}
	if options.DisplayNameA == "" {
		options.DisplayNameA = "Phone A"
	}
	if options.DisplayNameB == "" {
		options.DisplayNameB = "Phone B"
	}

	for _, serial := range []string{options.SerialA, options.SerialB} {
		hardware, err := host.Hardware(ctx, serial)
		if err != nil {
			return OfflinePairingResult{}, err
		}
		if !hardware {
			return OfflinePairingResult{}, fmt.Errorf("serial hash %s is not physical hardware", sha256Text(serial))
		}
	}
	initialA, _, err := host.Query(ctx, options.SerialA)
	if err != nil {
		return OfflinePairingResult{}, err
	}
	initialB, _, err := host.Query(ctx, options.SerialB)
	if err != nil {
		return OfflinePairingResult{}, err
	}
	if !freshOfflinePairingState(initialA) || !freshOfflinePairingState(initialB) {
		return OfflinePairingResult{}, errors.New("fresh unpaired state required; stale pairing state was detected")
	}
	if err := host.DisableMobileData(ctx, options.SerialA); err != nil {
		return OfflinePairingResult{}, err
	}
	if err := host.DisableMobileData(ctx, options.SerialB); err != nil {
		return OfflinePairingResult{}, err
	}
	wifiA, err := host.WiFiNetworkHash(ctx, options.SerialA)
	if err != nil {
		return OfflinePairingResult{}, err
	}
	wifiB, err := host.WiFiNetworkHash(ctx, options.SerialB)
	if err != nil {
		return OfflinePairingResult{}, err
	}
	if !validHash(wifiA) || wifiA != wifiB {
		return OfflinePairingResult{}, errors.New("phones are not proven on the same local Wi-Fi network")
	}

	pairComplete, cleanupDone := false, false
	var sessionA, sessionB string
	cleanup := func() {
		if pairComplete || cleanupDone {
			return
		}
		cleanupDone = true
		cancelOfflinePairingSessions(ctx, host, options, sessionA, sessionB)
	}
	fail := func(stage string, cause error) error {
		cleanup()
		return safeOfflineFailure(ctx, host, options, stage, cause)
	}
	defer cleanup()

	_, startSecret, err := host.Start(ctx, options.SerialA, options.DisplayNameA)
	if err != nil {
		return OfflinePairingResult{}, fail("start", err)
	}
	if len(startSecret.QR) == 0 || len(startSecret.QR) > 4096 {
		clear(startSecret.QR)
		return OfflinePairingResult{}, fail("start", errors.New("invalid secure QR handoff"))
	}
	qr := startSecret.QR
	if err := host.Join(ctx, options.SerialB, options.DisplayNameB, qr); err != nil {
		clear(qr)
		return OfflinePairingResult{}, fail("join", err)
	}
	clear(qr)
	startSecret.QR = nil

	verifyA, secretA, verifyB, secretB, err := waitOfflinePairingStatus(ctx, host, options, "verify_code")
	if err != nil {
		return OfflinePairingResult{}, fail("verify", err)
	}
	if secretA.SessionID == "" || secretB.SessionID == "" {
		return OfflinePairingResult{}, fail("verify", errors.New("secure session handle missing"))
	}
	sessionA, sessionB = secretA.SessionID, secretB.SessionID
	if !offlineSASPattern.MatchString(secretA.SAS) || secretA.SAS != secretB.SAS {
		return OfflinePairingResult{}, fail("verify", errors.New("six-digit SAS mismatch"))
	}
	if !validHash(verifyA.SASHash) || verifyA.SASHash != verifyB.SASHash {
		return OfflinePairingResult{}, fail("verify", errors.New("SAS hash mismatch"))
	}
	if err := host.Confirm(ctx, options.SerialA, sessionA); err != nil {
		return OfflinePairingResult{}, fail("confirm_a", err)
	}
	if err := host.Confirm(ctx, options.SerialB, sessionB); err != nil {
		return OfflinePairingResult{}, fail("confirm_b", err)
	}
	secretA = OfflinePairingSecret{}
	secretB = OfflinePairingSecret{}

	completeA, _, completeB, _, err := waitOfflinePairingStatus(ctx, host, options, "complete")
	if err != nil {
		return OfflinePairingResult{}, fail("complete", err)
	}
	if err := validateReciprocalBindings(completeA, completeB); err != nil {
		return OfflinePairingResult{}, fail("binding", err)
	}
	if err := host.RestartProcess(ctx, options.SerialA); err != nil {
		return OfflinePairingResult{}, fail("restart_a", err)
	}
	if err := host.RestartProcess(ctx, options.SerialB); err != nil {
		return OfflinePairingResult{}, fail("restart_b", err)
	}
	restartedA, _, err := host.Query(ctx, options.SerialA)
	if err != nil {
		return OfflinePairingResult{}, fail("restart_query_a", err)
	}
	restartedB, _, err := host.Query(ctx, options.SerialB)
	if err != nil {
		return OfflinePairingResult{}, fail("restart_query_b", err)
	}
	if err := validateReciprocalBindings(restartedA, restartedB); err != nil {
		return OfflinePairingResult{}, fail("restart_persistence", err)
	}
	finalWiFiA, err := host.WiFiNetworkHash(ctx, options.SerialA)
	if err != nil {
		return OfflinePairingResult{}, fail("wifi_persistence_a", err)
	}
	finalWiFiB, err := host.WiFiNetworkHash(ctx, options.SerialB)
	if err != nil {
		return OfflinePairingResult{}, fail("wifi_persistence_b", err)
	}
	if finalWiFiA != wifiA || finalWiFiB != wifiA {
		return OfflinePairingResult{}, fail("wifi_persistence", errors.New("local Wi-Fi network changed during pairing"))
	}
	pairComplete = true
	sessionA, sessionB = "", ""

	return OfflinePairingResult{
		Result: "pass", SerialAHash: sha256Text(options.SerialA), SerialBHash: sha256Text(options.SerialB),
		WiFiNetworkHash: wifiA, DeviceA: restartedA, DeviceB: restartedB, Topology: options.Topology,
		MobileDataDisabled: true, ProcessRestartPersisted: true,
		RelayRequired: false, LaptopServiceRequired: false,
	}, nil
}

func cancelOfflinePairingSessions(parent context.Context, host OfflinePairingHost, options OfflinePairingOptions, sessionA, sessionB string) {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(parent), 5*time.Second)
	defer cancel()
	if sessionA == "" {
		_, secret, _ := host.Query(ctx, options.SerialA)
		sessionA = secret.SessionID
	}
	if sessionB == "" {
		_, secret, _ := host.Query(ctx, options.SerialB)
		sessionB = secret.SessionID
	}
	if sessionA != "" {
		_ = host.Cancel(ctx, options.SerialA, sessionA)
	}
	if sessionB != "" {
		_ = host.Cancel(ctx, options.SerialB, sessionB)
	}
}

func validateOfflinePairingOptions(options OfflinePairingOptions) error {
	a, b := strings.TrimSpace(options.SerialA), strings.TrimSpace(options.SerialB)
	if a == "" || b == "" || a == b {
		return errors.New("two explicit distinct hardware serials are required")
	}
	if strings.HasPrefix(a, "emulator-") || strings.HasPrefix(b, "emulator-") {
		return errors.New("physical hardware serials are required")
	}
	if options.Timeout <= 0 {
		return errors.New("positive offline pairing timeout is required")
	}
	if !options.Topology.InternetBlocked || !validHash(options.Topology.PacketEvidenceSHA256) || !validHash(options.Topology.DNSEvidenceSHA256) {
		return errors.New("verified internet-block and packet/DNS evidence hashes are required")
	}
	return nil
}

func freshOfflinePairingState(value OfflinePairingSnapshot) bool {
	return !value.Completed && !value.LanBindingPresent && value.PeerApplicationIdentityHash == "" && (value.Phase == "" || value.Phase == "idle")
}

func waitOfflinePairingStatus(ctx context.Context, host OfflinePairingHost, options OfflinePairingOptions, phase string) (OfflinePairingSnapshot, OfflinePairingSecret, OfflinePairingSnapshot, OfflinePairingSecret, error) {
	var a, b OfflinePairingSnapshot
	var secretA, secretB OfflinePairingSecret
	err := Eventually(ctx, 100*time.Millisecond, options.Timeout, func() (bool, error) {
		var err error
		a, secretA, err = host.Query(ctx, options.SerialA)
		if err != nil {
			return false, err
		}
		b, secretB, err = host.Query(ctx, options.SerialB)
		if err != nil {
			return false, err
		}
		if a.ErrorCode != "" || b.ErrorCode != "" {
			return false, fmt.Errorf("bounded pairing error states: a=%s b=%s", a.ErrorCode, b.ErrorCode)
		}
		return a.Phase == phase && b.Phase == phase, nil
	})
	return a, secretA, b, secretB, err
}

func validateReciprocalBindings(a, b OfflinePairingSnapshot) error {
	for _, value := range []string{a.DeviceApplicationIdentityHash, a.PeerApplicationIdentityHash, a.LocalTLSPinHash, a.PeerTLSPinHash, b.DeviceApplicationIdentityHash, b.PeerApplicationIdentityHash, b.LocalTLSPinHash, b.PeerTLSPinHash} {
		if !validHash(value) {
			return errors.New("missing or malformed persisted identity hash")
		}
	}
	if !a.Completed || !b.Completed || !a.LanBindingPresent || !b.LanBindingPresent {
		return errors.New("both sealed LAN bindings must be complete")
	}
	if a.DeviceApplicationIdentityHash != b.PeerApplicationIdentityHash || b.DeviceApplicationIdentityHash != a.PeerApplicationIdentityHash {
		return errors.New("application identities are not reciprocal")
	}
	if a.LocalTLSPinHash != b.PeerTLSPinHash || b.LocalTLSPinHash != a.PeerTLSPinHash {
		return errors.New("TLS pins are not reciprocal")
	}
	return nil
}

func safeOfflineFailure(ctx context.Context, host OfflinePairingHost, options OfflinePairingOptions, stage string, cause error) error {
	snapshot := OfflinePairingFailureSnapshot{Stage: stage}
	snapshot.DeviceA, _, _ = host.Query(ctx, options.SerialA)
	snapshot.DeviceB, _, _ = host.Query(ctx, options.SerialB)
	encoded, _ := json.Marshal(snapshot)
	if len(encoded) > MaxOfflinePairingFailureBytes {
		snapshot = OfflinePairingFailureSnapshot{Stage: "snapshot_too_large"}
	}
	return &OfflinePairingFailure{Stage: stage, Cause: cause, Snapshot: snapshot}
}

func validHash(value string) bool    { return offlineHashPattern.MatchString(value) }
func sha256Text(value string) string { return fmt.Sprintf("%x", sha256.Sum256([]byte(value))) }

func WriteOfflinePairingEvidence(root string, result OfflinePairingResult) (string, error) {
	if strings.TrimSpace(root) == "" {
		return "", errors.New("offline pairing evidence directory is required")
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return "", err
	}
	if len(encoded) == 0 || len(encoded) > MaxOfflinePairingEvidenceBytes {
		return "", errors.New("offline pairing evidence exceeds bound")
	}
	lower := strings.ToLower(string(encoded))
	for _, forbidden := range []string{`"qr"`, `"session_id"`, `"sas"`, `"transcript"`, `"secret"`, `"token"`} {
		if strings.Contains(lower, forbidden) {
			return "", errors.New("offline pairing evidence contains forbidden field")
		}
	}
	if err := os.MkdirAll(root, 0o700); err != nil {
		return "", err
	}
	rootInfo, err := os.Lstat(root)
	if err != nil || !rootInfo.IsDir() || rootInfo.Mode()&os.ModeSymlink != 0 {
		return "", errors.New("offline pairing evidence directory is unsafe")
	}
	if err := os.Chmod(root, 0o700); err != nil {
		return "", err
	}
	path := filepath.Join(root, "offline-pairing.json")
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return "", err
	}
	defer file.Close()
	if _, err := file.Write(encoded); err != nil {
		return "", err
	}
	if err := file.Sync(); err != nil {
		return "", err
	}
	return path, nil
}
