package scenario

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"github.com/twinotify/phone-sync/e2e/internal/control"
)

// The Bluetooth scenario is the only plan allowed to claim a Bluetooth route.
//
// It faults LAN and the relay on both phones so nothing else can carry the
// traffic, waits for the coordinator to grant Bluetooth, delivers one small
// notification and one maximum-size fixture, requires digest-backed custody on
// the Bluetooth route plus an authenticated peer receipt for each, then
// restores LAN and proves the promotion opened a strictly later generation.
//
// The final steps deliberately contain no delivery action: every delivery in
// this plan must be carried by Bluetooth, so the executor holds the origin to
// an authenticated Bluetooth route for the whole delivering half of the run.
var bluetoothRouteSteps = map[string][]Step{
	"bluetooth-direct-route": {
		{Action: "A.route.fail:lan"}, {Action: "B.route.fail:lan"},
		{Action: "A.route.fail:relay"}, {Action: "B.route.fail:relay"},
		{Action: "A.await-route:bluetooth", Predicate: "A.route.bluetooth"},
		{Action: "B.await-route:bluetooth", Predicate: "B.route.bluetooth"},
		{Action: "A.shell.post:n1", Predicate: "B.tracked.sequence:1"},
		{Predicate: "A.custody.bluetooth:notif_post:1"},
		{Predicate: "A.peer-receipt.delta:1"},
		{Action: "A.enqueue-fixture:1048576"},
		{Action: "A.await-peer-receipt", Predicate: "A.peer-receipt.delta:2"},
		{Predicate: "A.custody.bluetooth:notif_post:2"},
		{Action: "A.route.restore:lan"}, {Action: "B.route.restore:lan"},
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.route.restore:relay"}, {Action: "B.route.restore:relay"},
		{Predicate: "direct.terminal"},
	},
}

const bluetoothAwaitTimeout = control.MaxRouteAwait

// bluetoothFixtureBytes is the exact protocol envelope maximum. Anything larger
// is not a legal envelope, so the scenario proves the boundary, not past it.
const bluetoothFixtureBytes = control.MaxFixtureBytes

func bluetoothRoutePlan(name string) (ScenarioPlan, bool) {
	steps, ok := bluetoothRouteSteps[name]
	if !ok {
		return ScenarioPlan{}, false
	}
	return ScenarioPlan{Name: name, Steps: append([]Step(nil), steps...)}, true
}

func isBluetoothRoutePlan(name string) bool { return bluetoothRouteSteps[name] != nil }

// routeFaultWireNames maps the scenario language's lowercase route token onto
// the closed uppercase enum the device accepts.
var routeFaultWireNames = map[string]string{"lan": "LAN", "bluetooth": "BLUETOOTH", "relay": "RELAY"}

// parseBluetoothRouteAction owns every action the Bluetooth plan introduces.
// Each one is built through a control constructor so the host cannot express a
// command the device would reject.
func parseBluetoothRouteAction(raw string) (action, bool, error) {
	switch {
	case strings.HasPrefix(raw, "A.route.fail:"), strings.HasPrefix(raw, "B.route.fail:"),
		strings.HasPrefix(raw, "A.route.restore:"), strings.HasPrefix(raw, "B.route.restore:"):
		device := raw[:1]
		enabled := strings.Contains(raw, ".route.fail:")
		route := raw[strings.LastIndex(raw, ":")+1:]
		command, err := control.NewRouteFaultCommand("plan", routeFaultWireNames[route], enabled)
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{
			kind: actionRouteFault, device: device, route: route, enabled: !enabled,
			command: command.Name, params: command.Params, original: raw,
		}, true, nil
	case strings.HasPrefix(raw, "A.await-route:"), strings.HasPrefix(raw, "B.await-route:"):
		device := raw[:1]
		route := raw[strings.LastIndex(raw, ":")+1:]
		command, err := control.NewAwaitRouteCommand("plan", routeFaultWireNames[route], "AUTHENTICATED", bluetoothAwaitTimeout)
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{
			kind: actionControl, device: device, command: command.Name,
			params: command.Params, original: raw,
		}, true, nil
	case strings.HasPrefix(raw, "A.enqueue-fixture:"):
		size, err := strconv.Atoi(strings.TrimPrefix(raw, "A.enqueue-fixture:"))
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		command, err := control.NewEnqueueFixtureCommand("plan", size)
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{
			kind: actionControl, device: "A", command: command.Name,
			params: command.Params, delivery: true, original: raw,
		}, true, nil
	case raw == "A.await-peer-receipt":
		command, err := control.NewAwaitPeerReceiptCommand("plan", bluetoothAwaitTimeout)
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{
			kind: actionControl, device: "A", command: command.Name,
			params: command.Params, original: raw,
		}, true, nil
	}
	return action{}, false, nil
}

func knownBluetoothRoutePredicate(predicate string) bool {
	return predicate == "A.route.bluetooth" || predicate == "B.route.bluetooth"
}

func bluetoothRouteOracleCode(predicate string) (string, bool) {
	switch {
	case strings.HasSuffix(predicate, ".route.bluetooth"):
		return "missing_authenticated_bluetooth", true
	case strings.Contains(predicate, ".custody.bluetooth:"):
		return "missing_bluetooth_custody", true
	}
	return "", false
}

// Debug route results are closed key sets. A field that could name an address,
// an association, a service UUID, or a peer fails the oracle even when the
// device reports success.
var forbiddenBluetoothFields = []string{
	"address", "bluetooth_address", "mac", "device_name", "name", "association",
	"association_id", "uuid", "service_uuid", "l2cap_uuid", "psm", "ssid", "bssid",
	"peer_key", "public_key", "envelope", "ciphertext", "payload", "text", "title",
}

func containsForbiddenBluetoothMaterial(raw json.RawMessage) bool {
	text := strings.ToLower(string(raw))
	for _, field := range forbiddenBluetoothFields {
		if strings.Contains(text, `"`+field+`"`) {
			return true
		}
	}
	return rawUUIDAnywherePattern.MatchString(text)
}

func exactBluetoothFields(result control.Result, keys ...string) (map[string]json.RawMessage, bool) {
	if result.Code != "ok" || containsForbiddenBluetoothMaterial(result.Payload) {
		return nil, false
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(result.Payload, &fields); err != nil || len(fields) != len(keys) {
		return nil, false
	}
	for _, key := range keys {
		if value, ok := fields[key]; !ok || string(value) == "null" {
			return nil, false
		}
	}
	return fields, true
}

// parseAwaitRouteResult refuses a timeout: the device must have actually
// reached the requested route, not merely reported on it.
func parseAwaitRouteResult(result control.Result, wantRoute string) error {
	if _, ok := exactBluetoothFields(result, "route", "phase", "status", "elapsed_ms"); !ok {
		return oracleFailure("invalid_await_route")
	}
	var payload struct {
		Route     string `json:"route"`
		Phase     string `json:"phase"`
		Status    string `json:"status"`
		ElapsedMs int64  `json:"elapsed_ms"`
	}
	if err := json.Unmarshal(result.Payload, &payload); err != nil ||
		payload.ElapsedMs < 0 || payload.ElapsedMs > bluetoothAwaitTimeout.Milliseconds() ||
		(payload.Status != "matched" && payload.Status != "timeout") {
		return oracleFailure("invalid_await_route")
	}
	if payload.Status != "matched" || payload.Route != wantRoute || payload.Phase != "authenticated" {
		return oracleFailure("missing_authenticated_" + wantRoute)
	}
	return nil
}

func parseEnqueueFixtureResult(result control.Result, wantBytes int) error {
	if _, ok := exactBluetoothFields(result, "bytes", "status", "elapsed_ms"); !ok {
		return oracleFailure("invalid_enqueue_fixture")
	}
	var payload struct {
		Bytes     int    `json:"bytes"`
		Status    string `json:"status"`
		ElapsedMs int64  `json:"elapsed_ms"`
	}
	if err := json.Unmarshal(result.Payload, &payload); err != nil ||
		payload.Bytes < 1 || payload.Bytes > wantBytes ||
		payload.ElapsedMs < 0 || payload.Status != "enqueued" {
		return oracleFailure("invalid_enqueue_fixture")
	}
	return nil
}

func parseAwaitPeerReceiptResult(result control.Result) error {
	if _, ok := exactBluetoothFields(result, "status", "awaiting_peer_count", "elapsed_ms"); !ok {
		return oracleFailure("invalid_await_peer_receipt")
	}
	var payload struct {
		Status            string `json:"status"`
		AwaitingPeerCount int    `json:"awaiting_peer_count"`
		ElapsedMs         int64  `json:"elapsed_ms"`
	}
	if err := json.Unmarshal(result.Payload, &payload); err != nil ||
		payload.AwaitingPeerCount < 0 || payload.AwaitingPeerCount > 2_000 ||
		payload.ElapsedMs < 0 || payload.ElapsedMs > bluetoothAwaitTimeout.Milliseconds() ||
		(payload.Status != "receipted" && payload.Status != "timeout") {
		return oracleFailure("invalid_await_peer_receipt")
	}
	if payload.Status != "receipted" || payload.AwaitingPeerCount != 0 {
		return oracleFailure("missing_peer_receipt")
	}
	return nil
}

// verifyBluetoothControlResult is the single oracle for every Bluetooth debug
// command. Anything the plan sends is checked here before the host believes it.
func verifyBluetoothControlResult(a action, result control.Result) error {
	switch a.command {
	case "AWAIT_ROUTE":
		want := strings.ToLower(a.params["route"])
		return parseAwaitRouteResult(result, want)
	case "ENQUEUE_FIXTURE":
		size, err := strconv.Atoi(a.params["bytes"])
		if err != nil {
			return oracleFailure("invalid_enqueue_fixture")
		}
		return parseEnqueueFixtureResult(result, size)
	case "AWAIT_PEER_RECEIPT":
		return parseAwaitPeerReceiptResult(result)
	}
	return nil
}
