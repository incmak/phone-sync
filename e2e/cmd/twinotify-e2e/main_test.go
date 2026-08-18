package main

import (
	"context"
	"reflect"
	"strings"
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/adb"
	"github.com/twinotify/phone-sync/e2e/internal/control"
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
