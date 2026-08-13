package metrics_test

import (
	"math"
	"os"
	"path/filepath"
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/metrics"
)

func TestNearestRankPercentiles(t *testing.T) {
	samples := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	for name, want := range map[string]float64{"p50": 5, "p95": 10, "p99": 10} {
		got, err := metrics.Percentile(samples, map[string]float64{"p50": .50, "p95": .95, "p99": .99}[name])
		if err != nil || got != want {
			t.Fatalf("%s=%v err=%v want=%v", name, got, err, want)
		}
	}
}

func TestLatencyRejectsNegativeClockSkew(t *testing.T) {
	if _, err := metrics.Latency(-1); err == nil {
		t.Fatal("expected negative latency error")
	}
	if got, err := metrics.Latency(12); err != nil || got != 12 {
		t.Fatalf("got=%v err=%v", got, err)
	}
}

func TestStressResultRequiresTerminalCountsAndMachineReadableFields(t *testing.T) {
	result := metrics.StressResult{Scenario: "burst-1000", Events: 1000, Terminal: 1000, Cancelled: 100, Mirrors: 0, Outbox: 0}
	if err := result.Validate(); err != nil {
		t.Fatal(err)
	}
	result.Terminal = 999
	if err := result.Validate(); err == nil {
		t.Fatal("expected missing terminal event failure")
	}
	result.Terminal = 1000
	result.P95 = 1
	result.P50 = 2
	if err := result.Validate(); err == nil {
		t.Fatal("expected percentile ordering failure")
	}
	result.P50 = math.NaN()
	if err := result.Validate(); err == nil {
		t.Fatal("expected non-finite percentile failure")
	}
	result.P50 = 0
	result.P95 = 0
	result.P99 = 0
	path := filepath.Join(t.TempDir(), "result.json")
	result = metrics.StressResult{Scenario: "burst-1000", Events: 1000, Terminal: 1000, Cancelled: 100, Mirrors: 0, Outbox: 0}
	if err := result.WriteJSON(path); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 {
		t.Fatalf("readback err=%v", err)
	}
}
