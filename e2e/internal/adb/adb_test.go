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
	output []byte
	err    error
}

func (f *fakeRunner) Run(_ context.Context, args ...string) ([]byte, error) {
	f.args = append(f.args, append([]string(nil), args...))
	return f.output, f.err
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
