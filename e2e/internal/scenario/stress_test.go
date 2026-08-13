package scenario_test

import (
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/metrics"
)

func TestBurstOracleDeterministicallyGenerates100TagsAnd1000Events(t *testing.T) {
	result := metrics.BurstOracle(42)
	if result.Events != 1000 || result.Tags != 100 || result.Cancelled != 100 || result.Terminal != 1000 {
		t.Fatalf("oracle=%+v", result)
	}
	if result.Mirrors != 0 || result.Outbox != 0 || result.LoopEvents != 0 {
		t.Fatalf("final oracle=%+v", result)
	}
}

func TestOfflineCapacityOracleKeepsEleventhLocal(t *testing.T) {
	result := metrics.CapacityOracle(10)
	if result.Limit != 10 || result.Accepted != 10 || result.LocalPending != 1 || result.Dropped != 0 {
		t.Fatalf("capacity=%+v", result)
	}
}
