package main

import (
	"context"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/adb"
	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

type privateRunner struct {
	args   [][]string
	inputs [][]byte
	output []byte
}

func (r *privateRunner) Run(_ context.Context, args ...string) ([]byte, error) {
	r.args = append(r.args, append([]string(nil), args...))
	return r.output, nil
}
func (r *privateRunner) RunWithInput(_ context.Context, input []byte, args ...string) ([]byte, error) {
	r.args = append(r.args, append([]string(nil), args...))
	r.inputs = append(r.inputs, append([]byte(nil), input...))
	return r.output, nil
}

func TestADBDevicePrivateCeremonyChannelNeverAddsSecretToArgv(t *testing.T) {
	runner := &privateRunner{output: []byte("fixture-private-result")}
	device := adbDevice{client: adb.New(runner, "physical-a"), packageName: "com.twinotify.app"}
	secret := []byte("fixture-private-input")
	if err := device.WriteSecret(context.Background(), "request-1", secret); err != nil {
		t.Fatal(err)
	}
	got, err := device.ReadSecretOnce(context.Background(), "request-1")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, runner.output) {
		t.Fatalf("result=%q", got)
	}
	for _, args := range runner.args {
		if strings.Contains(strings.Join(args, " "), string(secret)) {
			t.Fatalf("argv leaked secret: %q", args)
		}
	}
}

func TestADBDeviceBroadcastSendsInstallTokenThroughPrivateAuthFile(t *testing.T) {
	runner := &privateRunner{}
	device := adbDevice{client: adb.New(runner, "physical-a"), packageName: "com.twinotify.app"}
	token := "fixture-install-token"
	if err := device.Broadcast(context.Background(), control.Command{RequestID: "request-auth", Name: "STATUS", Token: token}); err != nil {
		t.Fatal(err)
	}
	for _, args := range runner.args {
		if strings.Contains(strings.Join(args, " "), token) {
			t.Fatalf("install token leaked to argv: %q", args)
		}
	}
	if len(runner.inputs) == 0 || string(runner.inputs[0]) != token {
		t.Fatalf("token was not sent over private stdin: %q", runner.inputs)
	}
}

func TestShellBroadcastDeviceUsesNoRunAsTokenOrPrivateHandle(t *testing.T) {
	result := []byte(`{"request_id":"request-1","code":"ok","payload":{"active_outbox":0}}`)
	runner := &privateRunner{output: []byte("Broadcast completed: result=-1, data=\"" + base64.RawURLEncoding.EncodeToString(result) + "\"\n")}
	device := newShellBroadcastDevice(adb.New(runner, "physical-a"), "com.twinotify.app")
	if err := device.Broadcast(context.Background(), control.Command{
		RequestID: "request-1", Name: "STATUS", Token: "host-only-key",
	}); err != nil {
		t.Fatal(err)
	}
	got, err := device.ReadResult(context.Background(), "request-1")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(result) {
		t.Fatalf("result=%q want=%q", got, result)
	}
	if _, err := device.ReadResult(context.Background(), "request-1"); !errors.Is(err, control.ErrResultNotReady) {
		t.Fatalf("second read error=%v", err)
	}
	joined := strings.Join(runner.args[0], " ")
	for _, forbidden := range []string{"run-as", "host-only-key", "auth_input_id", "secret_input_id"} {
		if strings.Contains(joined, forbidden) {
			t.Fatalf("shell broadcast contains %q: %s", forbidden, joined)
		}
	}
	if !strings.Contains(joined, "com.twinotify.app/co.twinotify.core.e2e.E2eControlReceiver") {
		t.Fatalf("explicit receiver missing: %s", joined)
	}
}

func TestShellBroadcastResultRejectsMalformedAndOversizeData(t *testing.T) {
	for _, output := range [][]byte{
		[]byte("Broadcast completed: result=-1, data=\"not+base64\"\n"),
		[]byte("Broadcast completed: result=-1, data=\"" + base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat("x", 65_537))) + "\"\n"),
	} {
		if _, err := parseShellBroadcastResult(output, "request-1"); err == nil {
			t.Fatalf("accepted invalid result of %d bytes", len(output))
		}
	}
}

func TestShellBroadcastDeviceRejectsCredentialsBeforeADB(t *testing.T) {
	for _, key := range []string{"token", "auth_input_id", "secret_input_id"} {
		runner := &privateRunner{}
		device := newShellBroadcastDevice(adb.New(runner, "physical-a"), "com.twinotify.app")
		err := device.Broadcast(context.Background(), control.Command{
			RequestID: "request-1", Name: "STATUS", Params: map[string]string{key: "forbidden"},
		})
		if err == nil || len(runner.args) != 0 {
			t.Fatalf("key=%s error=%v adb_calls=%d", key, err, len(runner.args))
		}
	}
}

func TestShellBroadcastTransportRejectsSecretScenarios(t *testing.T) {
	for _, name := range []string{"pair", "offline-pairing", "lan-product-correctness"} {
		if err := validateControlTransport("shell-broadcast", name); err == nil {
			t.Fatalf("accepted shell transport for %s", name)
		}
	}
	for _, name := range []string{"status", "notification-actions-correctness", "action-origin-kill-after-claim"} {
		if err := validateControlTransport("shell-broadcast", name); err != nil {
			t.Fatalf("rejected %s: %v", name, err)
		}
	}
}

func TestADBDeviceCleanupAPIsAddressDistinctPrivateBuckets(t *testing.T) {
	runner := &privateRunner{}
	device := adbDevice{client: adb.New(runner, "physical-a"), packageName: "com.twinotify.app"}
	if err := device.CleanupPrivateInput(context.Background(), "request-1"); err != nil {
		t.Fatal(err)
	}
	if err := device.CleanupPrivateAuth(context.Background(), "request-1"); err != nil {
		t.Fatal(err)
	}
	if err := device.CleanupPrivateOutput(context.Background(), "request-1"); err != nil {
		t.Fatal(err)
	}
	joined := make([]string, 0, len(runner.args))
	for _, args := range runner.args {
		joined = append(joined, strings.Join(args, " "))
	}
	for _, bucket := range []string{"files/e2e-inputs", "files/e2e-auth", "files/e2e-secrets"} {
		if !strings.Contains(strings.Join(joined, "\n"), bucket) {
			t.Fatalf("missing cleanup bucket %s: %q", bucket, runner.args)
		}
	}
}

func TestCLIRejectsInvalidPackageBeforeADB(t *testing.T) {
	err := runWithOptions(context.Background(), options{scenario: "status", serialA: "phone-a", serialB: "phone-b", packageName: "com.twinotify.app;id", timeout: time.Second})
	if err == nil || !strings.Contains(err.Error(), "invalid Android package") {
		t.Fatalf("error=%v", err)
	}
}

func TestCLIRejectsUnsupportedScenarioBeforeADB(t *testing.T) {
	err := validateScenarioBeforeADB("all-correctness")
	if !errors.Is(err, scenario.ErrUnsupportedEnvironment) {
		t.Fatalf("error=%v", err)
	}
}

func TestLanDirectSemanticScenariosPassPreflightBeforeADB(t *testing.T) {
	for _, name := range []string{
		"lan-direct-update", "lan-direct-peer-dismiss",
		"lan-direct-call-state", "lan-direct-snapshot-receipt",
		"lan-direct-burst-backpressure", "lan-direct-unpair-during-traffic",
		"lan-product-correctness",
	} {
		if err := validateScenarioBeforeADB(name); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
	}
	if err := validateScenarioBeforeADB("call-state"); err == nil {
		t.Fatal("legacy call-state bypass must not remain executable")
	}
}

func TestCLIBurstCountValidationRunsBeforeADB(t *testing.T) {
	for _, count := range []int{1, 1001} {
		err := runWithOptions(context.Background(), options{
			scenario: "lan-direct-burst-backpressure", burstCount: count,
			serialA: "phone-a", serialB: "phone-b", packageName: "com.twinotify.app", timeout: time.Second,
		})
		if err == nil || !strings.Contains(err.Error(), "burst count") {
			t.Fatalf("count=%d error=%v", count, err)
		}
	}
}

func TestCLIPreflightFailuresWriteFailedEvidenceBeforeADB(t *testing.T) {
	for _, tc := range []struct {
		name string
		code string
	}{
		{name: "all-correctness", code: "unsupported_environment"},
		{name: "not-real", code: "invalid_scenario"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			err := runWithOptions(context.Background(), options{
				scenario: tc.name, serialA: "phone-a", serialB: "phone-b", packageName: "com.twinotify.app",
				timeout: time.Second, scenarioEvidenceDir: dir,
			})
			if err == nil {
				t.Fatal("expected preflight failure")
			}
			state, readErr := os.ReadFile(filepath.Join(dir, "state.json"))
			if readErr != nil {
				t.Fatal(readErr)
			}
			if !strings.Contains(string(state), `"failed"`) || !strings.Contains(string(state), `"`+tc.code+`"`) {
				t.Fatalf("state=%s", state)
			}
		})
	}
}

func TestParseOfflineSnapshotAcceptsOnlyBoundedHashesAndStates(t *testing.T) {
	raw := []byte(`{"offline_pairing":{"role":"initiator","phase":"complete","completed":true,"session_id_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sas_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"device_application_identity_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","peer_application_identity_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","lan_binding_present":true,"local_tls_pin_hash":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","peer_tls_pin_hash":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}`)
	value, err := parseOfflineSnapshot(raw)
	if err != nil {
		t.Fatal(err)
	}
	if value.Phase != "complete" || !value.LanBindingPresent {
		t.Fatalf("snapshot=%+v", value)
	}
	if _, err := parseOfflineSnapshot([]byte(`{"offline_pairing":{"phase":"made-up"}}`)); err == nil {
		t.Fatal("expected closed-world phase rejection")
	}
	if _, err := parseOfflineSnapshot(append(raw, []byte(` {}`)...)); err == nil {
		t.Fatal("expected trailing JSON rejection")
	}
	if _, err := parseOfflineSnapshot([]byte(strings.Repeat(" ", 65_537))); err == nil {
		t.Fatal("expected bounded snapshot rejection")
	}
}
