package control_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
)

type fakeDevice struct {
	results map[string][]byte
	reads   int
	args    []control.Command
	err     error
}

type fakeSecretDevice struct {
	fakeDevice
	writes        map[string][]byte
	lastWrite     []byte
	secret        []byte
	cleanupCalls  []string
	readSecretErr error
	produceOutput bool
	broadcastErr  error
}

func (f *fakeSecretDevice) Broadcast(ctx context.Context, command control.Command) error {
	if f.produceOutput {
		f.secret = []byte("fixture-secret-output")
	}
	if f.broadcastErr != nil {
		return f.broadcastErr
	}
	return f.fakeDevice.Broadcast(ctx, command)
}

func (f *fakeSecretDevice) WriteSecret(_ context.Context, requestID string, value []byte) error {
	if f.writes == nil {
		f.writes = map[string][]byte{}
	}
	f.writes[requestID] = append([]byte(nil), value...)
	f.lastWrite = append([]byte(nil), value...)
	return nil
}
func (f *fakeSecretDevice) ReadSecretOnce(_ context.Context, requestID string) ([]byte, error) {
	if f.readSecretErr != nil {
		return nil, f.readSecretErr
	}
	return append([]byte(nil), f.secret...), nil
}
func (f *fakeSecretDevice) CleanupPrivateInput(ctx context.Context, requestID string) error {
	f.recordCleanup(ctx, "input:"+requestID)
	delete(f.writes, requestID)
	return nil
}
func (f *fakeSecretDevice) CleanupPrivateAuth(ctx context.Context, requestID string) error {
	f.recordCleanup(ctx, "auth:"+requestID)
	return nil
}
func (f *fakeSecretDevice) CleanupPrivateOutput(ctx context.Context, requestID string) error {
	f.recordCleanup(ctx, "output:"+requestID)
	f.secret = nil
	return nil
}
func (f *fakeSecretDevice) recordCleanup(ctx context.Context, value string) {
	if ctx.Err() != nil {
		panic("cleanup context inherited cancellation")
	}
	if _, ok := ctx.Deadline(); !ok {
		panic("cleanup context is unbounded")
	}
	f.cleanupCalls = append(f.cleanupCalls, value)
}

func TestExecuteSecretUsesPrivateChannelAndNormalResultContainsNoSecret(t *testing.T) {
	device := &fakeSecretDevice{fakeDevice: fakeDevice{results: map[string][]byte{"offline-1": []byte(`{"request_id":"offline-1","code":"ok","payload":{"phase":"advertising"}}`)}}, secret: []byte("fixture-secret-output")}
	client := control.New(device, "physical-a", "install-token", 100*time.Millisecond)
	result, secret, err := client.ExecuteSecret(context.Background(), control.Command{RequestID: "offline-1", Name: "OFFLINE_PAIR_START"}, []byte("fixture-secret-input"))
	if err != nil {
		t.Fatal(err)
	}
	if result.Code != "ok" || string(secret) != "fixture-secret-output" {
		t.Fatalf("result=%+v secret=%q", result, secret)
	}
	if string(device.lastWrite) != "fixture-secret-input" {
		t.Fatalf("private write=%q", device.lastWrite)
	}
	if strings.Contains(string(device.results["offline-1"]), "fixture-secret") {
		t.Fatal("normal result leaked secret")
	}
	if got := device.args[0].Params["secret_input_id"]; got != "offline-1" {
		t.Fatalf("secret handle=%q", got)
	}
}

func TestExecuteSecretCleansPrivateInputWhenBroadcastFails(t *testing.T) {
	device := &fakeSecretDevice{broadcastErr: errors.New("broadcast failed")}
	client := control.New(device, "physical-a", "install-token", 100*time.Millisecond)
	_, _, err := client.ExecuteSecret(context.Background(), control.Command{RequestID: "offline-2", Name: "OFFLINE_PAIR_JOIN"}, []byte("fixture-secret-input"))
	if err == nil {
		t.Fatal("expected failure")
	}
	for _, want := range []string{"input:offline-2", "auth:offline-2", "output:offline-2"} {
		if !contains(device.cleanupCalls, want) {
			t.Fatalf("cleanup=%v missing %s", device.cleanupCalls, want)
		}
	}
	if strings.Contains(err.Error(), "fixture-secret-input") {
		t.Fatalf("error leaked secret: %v", err)
	}
}

func contains(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}

func TestNotificationFixtureCommandIsClosedAndExact(t *testing.T) {
	command, err := control.NewNotificationFixtureCommand("request-fixture", "reply", "post")
	if err != nil {
		t.Fatal(err)
	}
	if command.RequestID != "request-fixture" || command.Name != "NOTIFICATION_FIXTURE" ||
		!reflect.DeepEqual(command.Params, map[string]string{"fixture": "reply", "operation": "post"}) {
		t.Fatalf("command=%+v", command)
	}
	for _, tc := range [][2]string{{"arbitrary", "post"}, {"reply", "arbitrary"}, {"", "post"}} {
		if _, err := control.NewNotificationFixtureCommand("request-fixture", tc[0], tc[1]); err == nil {
			t.Fatalf("accepted fixture=%q operation=%q", tc[0], tc[1])
		}
	}
}

func TestNotificationMirrorCommandCarriesNoContentOrIdentity(t *testing.T) {
	for _, operation := range []string{"invoke_reply", "invoke_mark_read", "replay_last_invoke", "arm_reply", "arm_mark_read", "invoke_armed", "tap"} {
		command, err := control.NewNotificationMirrorCommand("request-mirror", operation)
		if err != nil {
			t.Fatalf("%s: %v", operation, err)
		}
		if command.Name != "NOTIFICATION_MIRROR" || !reflect.DeepEqual(command.Params, map[string]string{"operation": operation}) {
			t.Fatalf("command=%+v", command)
		}
		encoded, err := json.Marshal(command)
		if err != nil {
			t.Fatal(err)
		}
		for _, forbidden := range []string{"reply_text", "canon_id", "action_id", "package_name", "component"} {
			if strings.Contains(strings.ToLower(string(encoded)), forbidden) {
				t.Fatalf("command leaked %s: %s", forbidden, encoded)
			}
		}
	}
	if _, err := control.NewNotificationMirrorCommand("request-mirror", "arbitrary"); err == nil {
		t.Fatal("accepted an arbitrary mirror operation")
	}
}

func TestExecuteSecretCleansAllPrivateBucketsOnEveryExit(t *testing.T) {
	cases := []struct {
		name    string
		result  []byte
		readErr error
		cancel  bool
	}{
		{"success", []byte(`{"request_id":"offline-3","code":"ok"}`), nil, false},
		{"normal result failure", []byte(`{"request_id":"offline-3","code":"error"}`), nil, false},
		{"malformed response", []byte(`{`), nil, false},
		{"private read failure", []byte(`{"request_id":"offline-3","code":"ok"}`), errors.New("private read failed"), false},
		{"timeout", nil, nil, false},
		{"cancelled context", nil, nil, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			device := &fakeSecretDevice{fakeDevice: fakeDevice{results: map[string][]byte{}}, produceOutput: true, readSecretErr: tc.readErr}
			if tc.result != nil {
				device.results["offline-3"] = tc.result
			}
			client := control.New(device, "physical-a", "install-token", 20*time.Millisecond)
			ctx, cancel := context.WithCancel(context.Background())
			if tc.cancel {
				go func() { time.Sleep(time.Millisecond); cancel() }()
			} else {
				defer cancel()
			}
			_, secret, _ := client.ExecuteSecret(ctx, control.Command{RequestID: "offline-3", Name: "OFFLINE_PAIR_JOIN"}, []byte("fixture-secret-input"))
			clear(secret)
			for _, want := range []string{"input:offline-3", "auth:offline-3", "output:offline-3"} {
				if !contains(device.cleanupCalls, want) {
					t.Fatalf("cleanup=%v missing %s", device.cleanupCalls, want)
				}
			}
			if len(device.writes) != 0 || len(device.secret) != 0 {
				t.Fatalf("private residue input=%d output=%d", len(device.writes), len(device.secret))
			}
		})
	}
}

func TestBoundRequestIDIsRandomTokenCommandAndExpiryBoundWithoutLeakingToken(t *testing.T) {
	token := "fixture-install-token"
	first, err := control.NewBoundRequestID(token, "OFFLINE_PAIR_QUERY", time.Unix(2_000_000_000, 0), bytes.NewReader(bytes.Repeat([]byte{7}, 16)))
	if err != nil {
		t.Fatal(err)
	}
	second, err := control.NewBoundRequestID(token, "STATUS", time.Unix(2_000_000_000, 0), bytes.NewReader(bytes.Repeat([]byte{7}, 16)))
	if err != nil {
		t.Fatal(err)
	}
	if first == second || strings.Contains(first, token) || strings.ContainsAny(first, "/\\") {
		t.Fatalf("unsafe handles: %q %q", first, second)
	}
}

type controllerDevice struct {
	deviceID string
	commands []control.Command
}

func (d *controllerDevice) Broadcast(_ context.Context, command control.Command) error {
	d.commands = append(d.commands, command)
	return nil
}

func (d *controllerDevice) ReadResult(_ context.Context, requestID string) ([]byte, error) {
	if len(d.commands) == 0 {
		return nil, control.ErrResultNotReady
	}
	command := d.commands[len(d.commands)-1]
	result := control.Result{RequestID: requestID, Code: "ok"}
	switch command.Name {
	case "PAIR_INIT":
		result.Payload = json.RawMessage(`{"relay_url":"http://10.0.2.2:8090","device_id":"a","enc_pubkey":"enc-a","sign_pubkey":"sign-a","pair_token":"pair-token"}`)
	case "STATUS":
		if d.deviceID == "a" {
			result.Payload = json.RawMessage(`{"device_id_hash":"ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb","paired_peer_hash":"3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d","health":{"service":"connected","protocolFloor":2}}`)
		} else {
			result.Payload = json.RawMessage(`{"device_id_hash":"3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d","paired_peer_hash":"ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb","health":{"service":"connected","protocolFloor":2}}`)
		}
	case "AWAIT_PEER_HELLO":
		result.Payload = json.RawMessage(`{"device_id":"b","enc_pubkey":"enc-b","sign_pubkey":"sign-b"}`)
	case "SIGN_CONFIRMATION":
		result.Payload = json.RawMessage(`{"confirmation_sig":"sig-a"}`)
	case "AWAIT_PAIR_SIG":
		result.Payload = json.RawMessage(`{"confirmation_sig":"sig-a"}`)
	}
	return json.Marshal(result)
}

func TestControllerRunsAuthenticatedPairSequenceAndWaitsForHealth(t *testing.T) {
	aDevice := &controllerDevice{deviceID: "a"}
	bDevice := &controllerDevice{deviceID: "b"}
	a := control.New(aDevice, "a", "token-a", 100*time.Millisecond)
	b := control.New(bDevice, "b", "token-b", 100*time.Millisecond)
	err := control.NewController(a, b, 250*time.Millisecond).Pair(context.Background(), control.PairOptions{RelayURL: "http://10.0.2.2:8090"})
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, command := range append(aDevice.commands, bDevice.commands...) {
		names = append(names, command.Name)
	}
	joined := strings.Join(names, ",")
	for _, expected := range []string{"PAIR_INIT", "PAIR_JOIN", "AWAIT_PEER_HELLO", "SIGN_CONFIRMATION", "SEND_CONFIRMATION_SIG", "AWAIT_PAIR_SIG", "PAIR_COMPLETE", "START_SYNC", "STATUS"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("commands=%s missing %s", joined, expected)
		}
	}
	for _, command := range bDevice.commands {
		if command.Name == "PAIR_JOIN" && !strings.Contains(command.Params["pair_payload"], "pair-token") {
			t.Fatalf("pair payload did not preserve token: %#v", command.Params)
		}
	}
}

func (f *fakeDevice) Broadcast(_ context.Context, command control.Command) error {
	f.args = append(f.args, command)
	return nil
}

func (f *fakeDevice) ReadResult(_ context.Context, requestID string) ([]byte, error) {
	f.reads++
	if f.err != nil {
		return nil, f.err
	}
	result, ok := f.results[requestID]
	if !ok {
		return nil, control.ErrResultNotReady
	}
	return result, nil
}

func TestControlWaitsForMatchingRequestAndReturnsResult(t *testing.T) {
	device := &fakeDevice{results: map[string][]byte{
		"r1": []byte(`{"request_id":"r1","code":"ok"}`),
	}}
	c := control.New(device, "emulator-5554", "token", 100*time.Millisecond)
	got, err := c.Execute(context.Background(), control.Command{RequestID: "r1", Name: "STATUS"})
	if err != nil {
		t.Fatal(err)
	}
	if got.Code != "ok" || got.RequestID != "r1" {
		t.Fatalf("result=%#v", got)
	}
	if len(device.args) != 1 || device.args[0].Token != "token" {
		t.Fatalf("broadcast=%#v", device.args)
	}
}

func TestControlRejectsStaleResultAndTimesOut(t *testing.T) {
	device := &fakeDevice{results: map[string][]byte{
		"r1": []byte(`{"request_id":"stale","code":"ok"}`),
	}}
	c := control.New(device, "emulator-5554", "token", 20*time.Millisecond)
	_, err := c.Execute(context.Background(), control.Command{RequestID: "r1", Name: "STATUS"})
	if !errors.Is(err, control.ErrTimeout) {
		t.Fatalf("error=%v, want timeout", err)
	}
}

func TestControlPropagatesCancellation(t *testing.T) {
	device := &fakeDevice{}
	c := control.New(device, "emulator-5554", "token", time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := c.Execute(ctx, control.Command{RequestID: "r1", Name: "STATUS"})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("error=%v, want context cancellation", err)
	}
}

func TestControlRejectsOfflineDevice(t *testing.T) {
	device := &fakeDevice{err: control.ErrDeviceOffline}
	c := control.New(device, "emulator-5554", "token", 100*time.Millisecond)
	_, err := c.Execute(context.Background(), control.Command{RequestID: "r1", Name: "STATUS"})
	if !errors.Is(err, control.ErrDeviceOffline) {
		t.Fatalf("error=%v, want offline classification", err)
	}
}
