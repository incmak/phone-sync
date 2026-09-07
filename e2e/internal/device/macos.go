package device

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Mac addresses only the private directory of an explicitly built E2E app.
// It cannot post source notifications or invoke Android controls.
type Mac struct {
	directory string
	mu        sync.Mutex
}

func OpenMac(directory string) (*Mac, error) {
	info, err := os.Lstat(directory)
	if err != nil {
		return nil, err
	}
	resolved, err := filepath.EvalSymlinks(directory)
	if err != nil || resolved != filepath.Clean(directory) || !info.IsDir() || info.Mode().Perm() != 0700 {
		return nil, errors.New("Mac E2E directory must be a private, nonsymlink directory")
	}
	return &Mac{directory: directory}, nil
}

func (m *Mac) Execute(ctx context.Context, operation, value string) (json.RawMessage, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(value) > 16384 {
		return nil, errors.New("Mac E2E value exceeds bound")
	}
	id, err := requestID()
	if err != nil {
		return nil, err
	}
	request := struct {
		ID        string `json:"id"`
		Operation string `json:"operation"`
		Value     string `json:"value,omitempty"`
	}{id, operation, value}
	raw, err := json.Marshal(request)
	if err != nil {
		return nil, err
	}
	temporary := filepath.Join(m.directory, id+".tmp")
	inbox := filepath.Join(m.directory, id+".request.json")
	response := filepath.Join(m.directory, id+".response.json")
	if err := os.WriteFile(temporary, raw, 0600); err != nil {
		return nil, err
	}
	defer os.Remove(temporary)
	if err := os.Rename(temporary, inbox); err != nil {
		return nil, err
	}
	defer os.Remove(inbox)
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		file, err := os.Open(response)
		if err == nil {
			data, readErr := io.ReadAll(io.LimitReader(file, 2*1024*1024+1))
			file.Close()
			os.Remove(response)
			if readErr != nil || len(data) > 2*1024*1024 {
				return nil, errors.New("Mac E2E response exceeds bound")
			}
			var result struct {
				ID      string          `json:"id"`
				Code    string          `json:"code"`
				Payload json.RawMessage `json:"payload"`
				Detail  string          `json:"detail"`
			}
			if json.Unmarshal(data, &result) != nil || result.ID != id || result.Code != "ok" {
				if result.ID == id && (result.Detail == "invalidPacket" || result.Detail == "invalidJSON" || result.Detail == "invalidSchema" || result.Detail == "invalidResponse" || result.Detail == "storage_failed") {
					return nil, fmt.Errorf("Mac %s: %s", operation, result.Detail)
				}
				return nil, fmt.Errorf("Mac %s did not complete", operation)
			}
			return result.Payload, nil
		}
		if !os.IsNotExist(err) {
			return nil, err
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("Mac %s: result unknown: %w", operation, ctx.Err())
		case <-ticker.C:
		}
	}
}

func (m *Mac) Snapshot(ctx context.Context) (Snapshot, error) {
	raw, err := m.Execute(ctx, "state", "")
	if err != nil {
		return Snapshot{}, err
	}
	return decodeSnapshot(raw)
}
func (m *Mac) RemovePeer(ctx context.Context, link string) error {
	_, err := m.Execute(ctx, "remove_peer", link)
	return err
}
func (m *Mac) DismissLocal(ctx context.Context, canonicalHash string) error {
	_, err := m.Execute(ctx, "dismiss_local", canonicalHash)
	return err
}
func (m *Mac) PauseRelay(ctx context.Context, paused bool) error {
	_, err := m.Execute(ctx, "pause_relay", fmt.Sprint(paused))
	return err
}
