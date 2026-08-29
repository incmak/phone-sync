package scenario

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var scenarioEvidenceNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)

// ScenarioResult is safe to persist: it contains only scenario/action IDs and
// summary observations, never notification bodies, tokens, or packet data.
type ScenarioResult struct {
	Scenario  string                 `json:"scenario"`
	Status    string                 `json:"status"`
	Events    []string               `json:"events"`
	Before    map[string]Observation `json:"before"`
	After     map[string]Observation `json:"after"`
	ErrorCode string                 `json:"error_code,omitempty"`
	// Route records which path actually carried the scenario.
	Route RouteEvidence `json:"route"`
	// Children are persisted as independent artifact subtrees rather than nested
	// into the aggregate JSON, keeping the closed-world evidence schema stable.
	Children []ScenarioResult `json:"-"`
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
	if err := validateEvidenceResult(result); err != nil {
		return err
	}
	return writeEvidenceArtifacts(dir, result)
}

func validateEvidenceResult(result ScenarioResult) error {
	if result.Scenario == "" || (result.Status != "passed" && result.Status != "failed") {
		return errors.New("invalid scenario result")
	}
	if !scenarioEvidenceNamePattern.MatchString(result.Scenario) {
		return errors.New("invalid scenario evidence name")
	}
	// A route record that names an endpoint, or evidence carrying secret material,
	// must fail the write rather than reach an uploaded artifact.
	if err := result.Route.Validate(); err != nil {
		return fmt.Errorf("invalid route evidence: %w", err)
	}
	if err := RejectSensitiveEvidence(result); err != nil {
		return fmt.Errorf("refusing to persist evidence: %w", err)
	}
	for _, child := range result.Children {
		if err := validateEvidenceResult(child); err != nil {
			return fmt.Errorf("invalid child evidence %s: %w", child.Scenario, err)
		}
	}
	return nil
}

func writeEvidenceArtifacts(dir string, result ScenarioResult) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return err
	}
	if len(result.Children) != 0 {
		childrenDir := filepath.Join(dir, "children")
		if err := os.RemoveAll(childrenDir); err != nil {
			return err
		}
		if err := os.Mkdir(childrenDir, 0o700); err != nil {
			return err
		}
		for index, child := range result.Children {
			name := fmt.Sprintf("%02d-%s", index+1, child.Scenario)
			if err := writeEvidenceArtifacts(filepath.Join(childrenDir, name), child); err != nil {
				return fmt.Errorf("write child evidence %s: %w", child.Scenario, err)
			}
		}
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
