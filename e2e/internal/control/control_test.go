package control_test

import (
	"context"
	"encoding/json"
	"errors"
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
			result.Payload = json.RawMessage(`{"device_id":"a","paired_peer":"b","peer_hello":{"device_id":"b","confirmation_sig":"sig"},"health":{"service":"connected","protocolFloor":2}}`)
		} else {
			result.Payload = json.RawMessage(`{"device_id":"b","paired_peer":"a","health":{"service":"connected","protocolFloor":2}}`)
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
