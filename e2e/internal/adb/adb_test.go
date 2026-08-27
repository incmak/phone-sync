package adb_test

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/adb"
)

type fakeRunner struct {
	args   [][]string
	inputs [][]byte
	output []byte
	err    error
}

func (f *fakeRunner) Run(_ context.Context, args ...string) ([]byte, error) {
	f.args = append(f.args, append([]string(nil), args...))
	return f.output, f.err
}

func (f *fakeRunner) RunWithInput(_ context.Context, input []byte, args ...string) ([]byte, error) {
	f.args = append(f.args, append([]string(nil), args...))
	f.inputs = append(f.inputs, append([]byte(nil), input...))
	return f.output, f.err
}

func TestPrivateRunAsHandoffKeepsSecretOutOfArgvAndUsesBoundedOneTimeFiles(t *testing.T) {
	runner := &fakeRunner{output: []byte("private-result")}
	client := adb.New(runner, "physical-a")
	sentinel := []byte("fixture-control-sentinel")
	if err := client.WriteRunAsPrivate(context.Background(), "com.twinotify.app", "e2e-inputs", "request-1", sentinel); err != nil {
		t.Fatal(err)
	}
	got, err := client.ReadRunAsPrivateOnce(context.Background(), "com.twinotify.app", "e2e-secrets", "request-1", 4096)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "private-result" {
		t.Fatalf("result=%q", got)
	}
	for _, argv := range runner.args {
		if strings.Contains(strings.Join(argv, " "), string(sentinel)) {
			t.Fatalf("secret leaked to argv: %q", argv)
		}
	}
	if len(runner.inputs) < 1 || string(runner.inputs[0]) != string(sentinel) {
		t.Fatalf("stdin handoff=%q", runner.inputs)
	}
	if !strings.Contains(strings.Join(runner.args[0], " "), "umask 077") || !strings.Contains(strings.Join(runner.args[1], " "), "trap") {
		t.Fatalf("private file ownership/cleanup contract missing: %q", runner.args)
	}
	if !strings.Contains(strings.Join(runner.args[0], " "), "test ! -L") || !strings.Contains(strings.Join(runner.args[1], " "), "test ! -L") {
		t.Fatalf("symlink rejection contract missing: %q", runner.args)
	}
	writeScript := strings.Join(runner.args[0], " ")
	readScript := strings.Join(runner.args[1], " ")
	for _, contract := range []string{"mktemp", "ln \"$tmp\" \"$target\"", "stat -c %u", "stat -c %a"} {
		if !strings.Contains(writeScript, contract) {
			t.Fatalf("atomic ownership contract %q missing from write: %q", contract, runner.args[0])
		}
	}
	for _, contract := range []string{"stat -c %u", "stat -c %a", "rm -f \"$target\""} {
		if !strings.Contains(readScript, contract) {
			t.Fatalf("ownership/one-time contract %q missing from read: %q", contract, runner.args[1])
		}
	}
}

func TestPrivateRunAsHandoffRejectsUnsafeHandlesAndOversizeResults(t *testing.T) {
	runner := &fakeRunner{output: []byte(strings.Repeat("x", 17))}
	client := adb.New(runner, "physical-a")
	if err := client.WriteRunAsPrivate(context.Background(), "com.twinotify.app", "../escape", "request-1", []byte("x")); err == nil {
		t.Fatal("expected bucket rejection")
	}
	if err := client.WriteRunAsPrivate(context.Background(), "com.twinotify.app", "e2e-inputs", "../escape", []byte("x")); err == nil {
		t.Fatal("expected handle rejection")
	}
	if _, err := client.ReadRunAsPrivateOnce(context.Background(), "com.twinotify.app", "e2e-secrets", "request-1", 16); err == nil {
		t.Fatal("expected oversize rejection")
	}
}

func TestClientUsesSeparateArgumentsForBroadcastPayload(t *testing.T) {
	runner := &fakeRunner{}
	client := adb.New(runner, "emulator-5554")
	wantText := `$(touch /tmp/pwned); "quoted" && spaces`

	if err := client.Broadcast(context.Background(), "co.twinotify.e2e.CONTROL", map[string]string{
		"request_id": "r1",
		"token":      wantText,
	}); err != nil {
		t.Fatal(err)
	}

	want := []string{
		"-s", "emulator-5554", "shell", "am", "broadcast",
		"-a", "co.twinotify.e2e.CONTROL",
		"--es", "request_id", "'r1'",
		"--es", "token", "'$(touch /tmp/pwned); \"quoted\" && spaces'",
	}
	if !reflect.DeepEqual(runner.args[0], want) {
		t.Fatalf("args=%q want=%q", runner.args[0], want)
	}
}

func TestClientTargetsDebugControlReceiverForAuthenticatedBroadcast(t *testing.T) {
	runner := &fakeRunner{}
	client := adb.New(runner, "emulator-5554")
	if err := client.BroadcastReceiver(context.Background(), "com.twinotify.app", "co.twinotify.core.e2e.E2eControlReceiver", "co.twinotify.e2e.CONTROL", map[string]string{
		"request_id": "pair-init",
		"token":      "install-token",
	}); err != nil {
		t.Fatal(err)
	}
	wantPrefix := []string{
		"-s", "emulator-5554", "shell", "am", "broadcast",
		"-n", "'com.twinotify.app/co.twinotify.core.e2e.E2eControlReceiver'",
		"-a", "co.twinotify.e2e.CONTROL",
	}
	if got := runner.args[0][:len(wantPrefix)]; !reflect.DeepEqual(got, wantPrefix) {
		t.Fatalf("args prefix=%q want=%q", got, wantPrefix)
	}
}

func TestClientShellQuotesStructuredBroadcastValues(t *testing.T) {
	runner := &fakeRunner{}
	client := adb.New(runner, "emulator-5554")
	payload := `{"pair_token":"pt-1","sign_pubkey":"+ab/=="}`
	if err := client.BroadcastReceiver(context.Background(), "com.twinotify.app", "co.twinotify.core.e2e.E2eControlReceiver", "co.twinotify.e2e.CONTROL", map[string]string{
		"pair_payload": payload,
	}); err != nil {
		t.Fatal(err)
	}
	want := `'{"pair_token":"pt-1","sign_pubkey":"+ab/=="}'`
	if got := runner.args[0][len(runner.args[0])-1]; got != want {
		t.Fatalf("quoted payload=%q want=%q", got, want)
	}
}

func TestClientRejectsShellMetacharactersInBroadcastComponent(t *testing.T) {
	runner := &fakeRunner{}
	client := adb.New(runner, "emulator-5554")
	err := client.BroadcastReceiver(context.Background(), "com.twinotify.app;touch /tmp/pwned", "co.twinotify.core.e2e.E2eControlReceiver", "co.twinotify.e2e.CONTROL", nil)
	if err == nil {
		t.Fatal("expected invalid package name error")
	}
	if len(runner.args) != 0 {
		t.Fatalf("runner invoked for invalid package: %q", runner.args)
	}
}

func TestEveryPackageShellBoundaryRejectsMetacharactersWithoutInvokingADB(t *testing.T) {
	for _, tc := range []struct {
		name string
		call func(*adb.Client) error
	}{
		{"read-run-as", func(c *adb.Client) error {
			_, err := c.ReadRunAs(context.Background(), "com.twinotify.app;id", "files/e2e-token")
			return err
		}},
		{"force-stop", func(c *adb.Client) error { return c.ForceStop(context.Background(), "com.twinotify.app$(id)") }},
		{"start-package", func(c *adb.Client) error { return c.StartPackage(context.Background(), "com.twinotify.app;id") }},
		{"whitespace", func(c *adb.Client) error { return c.ForceStop(context.Background(), " com.twinotify.app") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			runner := &fakeRunner{}
			if err := tc.call(adb.New(runner, "physical-a")); err == nil {
				t.Fatal("expected component rejection")
			}
			if len(runner.args) != 0 {
				t.Fatalf("adb invoked: %q", runner.args)
			}
		})
	}
}

func TestStartPackageUsesFixedValidatedLauncherArguments(t *testing.T) {
	runner := &fakeRunner{}
	client := adb.New(runner, "physical-a")
	if err := client.StartPackage(context.Background(), "com.twinotify.app"); err != nil {
		t.Fatal(err)
	}
	want := []string{"-s", "physical-a", "shell", "monkey", "-p", "com.twinotify.app", "-c", "android.intent.category.LAUNCHER", "1"}
	if len(runner.args) != 1 || !reflect.DeepEqual(runner.args[0], want) {
		t.Fatalf("args=%q want=%q", runner.args, want)
	}
}

func TestClientWrapsNonzeroADBExit(t *testing.T) {
	runner := &fakeRunner{output: []byte("device offline"), err: errors.New("exit status 1")}
	client := adb.New(runner, "emulator-5554")
	if err := client.ForceStop(context.Background(), "com.twinotify.app"); err == nil {
		t.Fatal("expected ADB error")
	} else if !errors.Is(err, adb.ErrDeviceOffline) {
		t.Fatalf("error=%v, want device-offline classification", err)
	}
}

func TestClientRedactsPayloadsFromADBErrors(t *testing.T) {
	secret := "install-token-secret"
	runner := &fakeRunner{output: []byte("permission denied: " + secret), err: errors.New("exit status 1")}
	client := adb.New(runner, "emulator-5554")
	err := client.Broadcast(context.Background(), "co.twinotify.e2e.CONTROL", map[string]string{"token": secret})
	if err == nil {
		t.Fatal("expected ADB error")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("ADB error leaked secret: %v", err)
	}
	if !strings.Contains(err.Error(), "adb shell am failed") {
		t.Fatalf("error=%v, want safe operation context", err)
	}
}

func TestClientProvidesTypedNotificationAndStateCommands(t *testing.T) {
	runner := &fakeRunner{output: []byte("Notification help\n  post\n  cancel\n")}
	client := adb.New(runner, "emulator-5556")
	if _, err := client.NotificationHelp(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := client.PostNotification(context.Background(), "tag-1", "hello; world"); err != nil {
		t.Fatal(err)
	}
	if err := client.CancelNotification(context.Background(), "tag-1"); err != nil {
		t.Fatal(err)
	}
	if err := client.SetAirplaneMode(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	if len(runner.args) != 4 {
		t.Fatalf("calls=%d want 4", len(runner.args))
	}
}

func TestPhysicalOfflinePairingPreflightDisablesOnlyMobileDataAndHashesWiFi(t *testing.T) {
	runner := &fakeRunner{}
	client := adb.New(runner, "physical-a")
	runner.output = []byte("0\n")
	hardware, err := client.IsHardware(context.Background())
	if err != nil || !hardware {
		t.Fatalf("hardware=%v err=%v", hardware, err)
	}
	if err := client.DisableMobileData(context.Background()); err != nil {
		t.Fatal(err)
	}
	runner.output = []byte("Wifi is enabled\nWifiInfo SSID: local-fixture, BSSID: 00:00:00:00:00:00\n4: wlan0    inet 192.0.2.41/24 brd 192.0.2.255 scope global wlan0\n")
	hash, err := client.WiFiNetworkHash(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(hash) != 64 || strings.Contains(hash, "local-fixture") || strings.Contains(hash, "192.0.2") {
		t.Fatalf("unsafe Wi-Fi hash=%q", hash)
	}
	joined := make([]string, len(runner.args))
	for i, args := range runner.args {
		joined[i] = strings.Join(args, " ")
	}
	all := strings.Join(joined, "\n")
	if !strings.Contains(all, "svc data disable") {
		t.Fatalf("mobile data was not disabled: %s", all)
	}
	if strings.Contains(all, "svc wifi disable") || strings.Contains(all, "airplane_mode") {
		t.Fatalf("Wi-Fi/ADB destructive command used: %s", all)
	}
}
