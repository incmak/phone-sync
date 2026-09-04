package scenario_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

// The Bluetooth plan is the only scenario that may claim a Bluetooth route, so
// its shape is asserted directly: LAN and relay are both faulted before any
// delivery, custody is claimed on Bluetooth rather than LAN, and the plan ends
// by restoring LAN and proving the promotion.
func TestBluetoothDirectRoutePlanFaultsLanAndRelayBeforeClaimingBluetooth(t *testing.T) {
	plan, err := scenario.Plan("bluetooth-direct-route")
	if err != nil {
		t.Fatal(err)
	}
	if err := scenario.ValidateExecutablePlan(plan); err != nil {
		t.Fatal(err)
	}
	actions := plan.Actions()
	for _, want := range []string{
		"A.route.fail:lan", "B.route.fail:lan", "A.route.fail:relay", "B.route.fail:relay",
		"A.await-route:bluetooth", "A.enqueue-fixture:1048576", "A.await-peer-receipt",
		"A.route.restore:lan", "B.route.restore:lan",
	} {
		if !contains(actions, want) {
			t.Fatalf("plan is missing action %q: %v", want, actions)
		}
	}
	firstDelivery := -1
	lastRelayFault := -1
	for index, act := range actions {
		if act == "A.shell.post:n1" && firstDelivery < 0 {
			firstDelivery = index
		}
		if act == "B.route.fail:relay" {
			lastRelayFault = index
		}
	}
	if firstDelivery < 0 || lastRelayFault < 0 || lastRelayFault > firstDelivery {
		t.Fatalf("delivery must follow both route faults: %v", actions)
	}
	var predicates []string
	for _, step := range plan.Steps {
		predicates = append(predicates, step.Predicate)
	}
	for _, want := range []string{
		"A.route.bluetooth", "B.route.bluetooth",
		"A.custody.bluetooth:notif_post:1", "A.peer-receipt.delta:1",
		"A.route.lan", "B.route.lan", "direct.terminal",
	} {
		if !contains(predicates, want) {
			t.Fatalf("plan is missing predicate %q: %v", want, predicates)
		}
	}
	if contains(predicates, "A.custody.lan:notif_post:1") {
		t.Fatalf("Bluetooth plan must not claim LAN custody: %v", predicates)
	}
}

func TestBluetoothDirectRouteProvesPromotionClosesBluetoothBeforeLan(t *testing.T) {
	result, err := runBluetoothRoute(t, "")
	if err != nil || result.Status != "passed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if result.Route.Route != "bluetooth" || result.Route.Phase != "authenticated" {
		t.Fatalf("delivery route evidence=%+v", result.Route)
	}
	if err := scenario.VerifyBluetoothPromotion(result.RouteTransitions); err != nil {
		t.Fatalf("promotion evidence %+v: %v", result.RouteTransitions, err)
	}
	if len(result.RouteTransitions) != 2 ||
		result.RouteTransitions[0].Route != "bluetooth" || result.RouteTransitions[1].Route != "lan" ||
		result.RouteTransitions[1].Generation <= result.RouteTransitions[0].Generation {
		t.Fatalf("transitions=%+v", result.RouteTransitions)
	}
	if result.After["A"].CustodyCounts["bluetooth"]["notif_post"] != 2 {
		t.Fatalf("bluetooth custody=%+v", result.After["A"].CustodyCounts)
	}
	if result.After["A"].CustodyCounts["lan"]["notif_post"] != 0 {
		t.Fatalf("no delivery may be claimed on LAN: %+v", result.After["A"].CustodyCounts)
	}
}

// Two overlapping route claims in one generation would mean two drainers. The
// promotion verifier is the host-side proof that never happened.
func TestVerifyBluetoothPromotionRejectsOverlappingOrWrongOrder(t *testing.T) {
	bluetooth := scenario.RouteEvidence{
		Route: "bluetooth", Phase: "authenticated", Generation: 4, PeerEvidence: "direct",
		DeliveryReason: "none", UserContentKind: "notifications",
	}
	lan := scenario.RouteEvidence{
		Route: "lan", Phase: "authenticated", Generation: 5, PeerEvidence: "direct",
		DeliveryReason: "none", UserContentKind: "notifications",
	}
	if err := scenario.VerifyBluetoothPromotion([]scenario.RouteEvidence{bluetooth, lan}); err != nil {
		t.Fatalf("valid promotion rejected: %v", err)
	}
	sameGeneration := lan
	sameGeneration.Generation = bluetooth.Generation
	for name, transitions := range map[string][]scenario.RouteEvidence{
		"reversed":        {lan, bluetooth},
		"same generation": {bluetooth, sameGeneration},
		"too short":       {bluetooth},
		"relay in place":  {bluetooth, {Route: "relay", Phase: "authenticated", Generation: 5, PeerEvidence: "recent", DeliveryReason: "none", UserContentKind: "notifications"}},
		"not direct":      {{Route: "bluetooth", Phase: "authenticated", Generation: 4, PeerEvidence: "unknown", DeliveryReason: "none", UserContentKind: "notifications"}, lan},
	} {
		if err := scenario.VerifyBluetoothPromotion(transitions); err == nil {
			t.Fatalf("%s promotion accepted", name)
		}
	}
}

// Bluetooth evidence is the highest-risk record in the project: an address, a
// device name, or an association identifier would leak a durable hardware ID.
func TestBluetoothDirectRouteEvidenceCarriesNoDeviceIdentifiers(t *testing.T) {
	result, err := runBluetoothRoute(t, "")
	if err != nil || result.Status != "passed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	raw, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.ToLower(string(raw))
	for _, forbidden := range []string{
		"association_id", "association", "bluetooth_address", "device_name", "rfcomm_uuid",
		"service_uuid", "mac", "ssid", "bssid", "aa:bb:cc:dd:ee:ff",
		"7c6f5d5e-6f54-4f6e-9b63-5457494e4f54", "5d7101b8-cad0-4d22-a41e-5457494e4f54",
	} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("evidence retained %q: %s", forbidden, raw)
		}
	}
	dir := t.TempDir()
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(dir, "scenario-result.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(content), `"bluetooth"`) {
		t.Fatalf("artifact lost the Bluetooth custody record: %s", content)
	}
}

func TestBluetoothDirectRouteRejectsMissingObservations(t *testing.T) {
	for omission, wantCode := range map[string]string{
		"bluetooth-route":    "missing_authenticated_bluetooth",
		"custody-notif_post": "missing_bluetooth_custody",
		"receipt":            "missing_peer_receipt",
		"promotion":          "missing_authenticated_lan",
		"terminal":           "missing_terminal_convergence",
		"sequence-1":         "missing_sequence_transition",
	} {
		t.Run(omission, func(t *testing.T) {
			result, err := runBluetoothRoute(t, omission)
			if err == nil || result.Status == "passed" || result.ErrorCode != wantCode {
				t.Fatalf("omission=%s result=%+v err=%v", omission, result, err)
			}
		})
	}
}

// The host may only name closed enums and bounded sizes. Everything else must
// be rejected before it can reach a device.
func TestBluetoothRouteControlCommandsAreClosedWorld(t *testing.T) {
	for _, route := range []string{"LAN", "BLUETOOTH", "RELAY"} {
		if _, err := control.NewRouteFaultCommand("r1", route, true); err != nil {
			t.Fatalf("route %q rejected: %v", route, err)
		}
	}
	for _, route := range []string{"", "lan", "wifi", "RELAY;LAN", "BLUETOOTH_LE"} {
		if _, err := control.NewRouteFaultCommand("r1", route, true); err == nil {
			t.Fatalf("route %q accepted", route)
		}
	}
	if _, err := control.NewRouteFaultCommand("", "LAN", true); err == nil {
		t.Fatal("empty request ID accepted")
	}
	command, err := control.NewAwaitRouteCommand("r2", "BLUETOOTH", "AUTHENTICATED", control.MaxRouteAwait)
	if err != nil {
		t.Fatal(err)
	}
	if command.Name != "AWAIT_ROUTE" || command.Params["route"] != "BLUETOOTH" ||
		command.Params["phase"] != "AUTHENTICATED" ||
		command.Params["timeout_ms"] != "10000" {
		t.Fatalf("await route command=%+v", command)
	}
	if _, err := control.NewAwaitRouteCommand("r2", "BLUETOOTH", "CONNECTED", control.MaxRouteAwait); err == nil {
		t.Fatal("unknown phase accepted")
	}
	if _, err := control.NewAwaitRouteCommand("r2", "BLUETOOTH", "AUTHENTICATED", control.MaxRouteAwait+time.Millisecond); err == nil {
		t.Fatal("unbounded await accepted")
	}
	fixture, err := control.NewEnqueueFixtureCommand("r3", control.MaxFixtureBytes)
	if err != nil {
		t.Fatal(err)
	}
	if fixture.Name != "ENQUEUE_FIXTURE" || fixture.Params["bytes"] != "1048576" {
		t.Fatalf("fixture command=%+v", fixture)
	}
	for _, size := range []int{0, -1, control.MaxFixtureBytes + 1} {
		if _, err := control.NewEnqueueFixtureCommand("r3", size); err == nil {
			t.Fatalf("fixture size %d accepted", size)
		}
	}
	receipt, err := control.NewAwaitPeerReceiptCommand("r4", control.MaxRouteAwait)
	if err != nil {
		t.Fatal(err)
	}
	if receipt.Name != "AWAIT_PEER_RECEIPT" || receipt.Params["timeout_ms"] != "10000" {
		t.Fatalf("await receipt command=%+v", receipt)
	}
	if _, err := control.NewAwaitPeerReceiptCommand("r4", 0); err == nil {
		t.Fatal("zero await accepted")
	}
}

func runBluetoothRoute(t *testing.T, omit string) (scenario.ScenarioResult, error) {
	t.Helper()
	return scenario.NewExecutor(newDirectSemanticBridge(omit), 20*time.Millisecond).
		RunResult(context.Background(), "bluetooth-direct-route")
}
