package scenario

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// ScenarioResult is safe to persist: it contains only scenario/action IDs and
// summary observations, never notification bodies, tokens, or packet data.
type ScenarioResult struct {
	Scenario  string                 `json:"scenario"`
	Status    string                 `json:"status"`
	Events    []string               `json:"events"`
	Before    map[string]Observation `json:"before"`
	After     map[string]Observation `json:"after"`
	ErrorCode string                 `json:"error_code,omitempty"`
}

func ErrorCode(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, ErrUnsupportedEnvironment) {
		return "unsupported_environment"
	}
	message := strings.ToLower(err.Error())
	if strings.Contains(message, "unknown") && strings.Contains(message, "scenario") {
		return "invalid_scenario"
	}
	return "execution_failed"
}

func errorCode(err error) string { return ErrorCode(err) }

// WriteEvidenceArtifacts atomically replaces every derived artifact only after
// successful JSON serialization. Callers can upload these files without
// manufacturing a pass claim from shell templates.
func WriteEvidenceArtifacts(dir string, result ScenarioResult) error {
	if strings.TrimSpace(dir) == "" {
		return errors.New("evidence directory is required")
	}
	if result.Scenario == "" || (result.Status != "passed" && result.Status != "failed") {
		return errors.New("invalid scenario result")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	state := struct {
		Scenario  string                 `json:"scenario"`
		Status    string                 `json:"status"`
		Before    map[string]Observation `json:"before"`
		After     map[string]Observation `json:"after"`
		ErrorCode string                 `json:"error_code,omitempty"`
	}{result.Scenario, result.Status, result.Before, result.After, result.ErrorCode}
	timeline := struct {
		Scenario string   `json:"scenario"`
		Status   string   `json:"status"`
		Events   []string `json:"events"`
	}{result.Scenario, result.Status, result.Events}
	metrics := struct {
		Scenario  string                 `json:"scenario"`
		Status    string                 `json:"status"`
		Converged map[string]bool        `json:"converged"`
		After     map[string]Observation `json:"after"`
		ErrorCode string                 `json:"error_code,omitempty"`
	}{result.Scenario, result.Status, convergence(result.After), result.After, result.ErrorCode}
	files := map[string]any{
		"scenario-result.json": result,
		"state.json":           state,
		"timeline.json":        timeline,
		"metrics.json":         metrics,
	}
	for name, value := range files {
		payload, err := json.Marshal(value)
		if err != nil {
			return err
		}
		if err := writeJSONAtomically(filepath.Join(dir, name), payload); err != nil {
			return err
		}
	}
	return nil
}

func convergence(states map[string]Observation) map[string]bool {
	result := make(map[string]bool, len(states))
	for device, state := range states {
		result[device] = state.Terminal && state.Outbox == 0 && state.LoopEvents == 0
	}
	return result
}

func writeJSONAtomically(path string, payload []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if _, err := tmp.Write(append(payload, '\n')); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}
