package metrics

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"sort"
)

func Percentile(samples []float64, quantile float64) (float64, error) {
	if len(samples) == 0 || quantile <= 0 || quantile > 1 {
		return 0, errors.New("percentile requires samples and quantile in (0,1]")
	}
	values := append([]float64(nil), samples...)
	for _, value := range values {
		if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
			return 0, errors.New("samples must be finite and non-negative")
		}
	}
	sort.Float64s(values)
	rank := int(math.Ceil(quantile * float64(len(values))))
	return values[rank-1], nil
}

func Latency(millis int64) (float64, error) {
	if millis < 0 {
		return 0, errors.New("latency cannot be negative")
	}
	return float64(millis), nil
}

type StressResult struct {
	Scenario  string  `json:"scenario"`
	Events    int     `json:"events"`
	Terminal  int     `json:"terminal"`
	Cancelled int     `json:"cancelled"`
	Mirrors   int     `json:"mirrors"`
	Outbox    int     `json:"outbox"`
	P50       float64 `json:"p50_ms,omitempty"`
	P95       float64 `json:"p95_ms,omitempty"`
	P99       float64 `json:"p99_ms,omitempty"`
}

type BurstResult struct {
	Events, Tags, Cancelled, Terminal, Mirrors, Outbox, LoopEvents int
	Operations                                                     []string
}

func BurstOracle(seed int64) BurstResult {
	state := uint64(seed)
	operations := make([]string, 0, 1000)
	for tag := 0; tag < 100; tag++ {
		operations = append(operations, fmt.Sprintf("post:%03d", tag))
		for update := 1; update <= 7; update++ {
			state = state*6364136223846793005 + 1
			operations = append(operations, fmt.Sprintf("update:%03d:%d:%d", tag, update, state%2))
		}
		operations = append(operations, fmt.Sprintf("cancel:%03d", tag), fmt.Sprintf("stale:%03d", tag))
	}
	return BurstResult{Events: len(operations), Tags: 100, Cancelled: 100, Terminal: len(operations), Operations: operations}
}

type CapacityResult struct{ Limit, Accepted, LocalPending, Dropped int }

func CapacityOracle(limit int) CapacityResult {
	if limit < 0 {
		limit = 0
	}
	return CapacityResult{Limit: limit, Accepted: limit, LocalPending: 1}
}

func ValidateScenarioID(id string) error {
	switch id {
	case "burst-1000", "offline-capacity", "latency-awake":
		return nil
	default:
		return fmt.Errorf("unsupported stress scenario %q", id)
	}
}

func (r StressResult) Validate() error {
	if r.Scenario == "" || r.Events <= 0 || r.Terminal != r.Events {
		return fmt.Errorf("stress result missing terminal events: %+v", r)
	}
	if r.Cancelled < 0 || r.Mirrors < 0 || r.Outbox < 0 {
		return errors.New("stress counts cannot be negative")
	}
	for _, value := range []float64{r.P50, r.P95, r.P99} {
		if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
			return errors.New("percentiles must be finite and non-negative")
		}
	}
	if r.P95 < r.P50 || r.P99 < r.P95 {
		return errors.New("percentiles must be ordered p50<=p95<=p99")
	}
	if r.Scenario == "burst-1000" && (r.Events != 1000 || r.Cancelled != 100 || r.Mirrors != 0 || r.Outbox != 0) {
		return fmt.Errorf("burst oracle mismatch: %+v", r)
	}
	return nil
}

func (r StressResult) WriteJSON(path string) error {
	if err := r.Validate(); err != nil {
		return err
	}
	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o600)
}
