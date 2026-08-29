package main

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"testing"
	"time"
)

func TestGracefulStopCreatesFreshHTTPDeadlineAfterMaintenanceJoin(t *testing.T) {
	maintenanceDone := make(chan struct{})
	beginCalled := make(chan struct{})
	stopCalled := make(chan struct{})
	shutdownCalled := false
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = gracefulStop(func() { close(beginCalled) }, func() { close(stopCalled) }, maintenanceDone, 20*time.Millisecond, func(ctx context.Context) error {
			shutdownCalled = true
			if remaining := time.Until(deadline(t, ctx)); remaining < 15*time.Millisecond {
				t.Errorf("HTTP shutdown received stale deadline with %s remaining", remaining)
			}
			return nil
		})
	}()
	<-beginCalled
	<-stopCalled
	time.Sleep(30 * time.Millisecond)
	close(maintenanceDone)
	<-done
	if !shutdownCalled {
		t.Fatal("HTTP shutdown was not called")
	}
}

func TestProductionLogHandlerEmitsStructuredJSON(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(newLogHandler(&output, true))
	logger.Info("relay_started", "status", "ready")
	var record map[string]any
	if err := json.Unmarshal(output.Bytes(), &record); err != nil {
		t.Fatalf("production log is not JSON: %v; output=%q", err, output.String())
	}
	if record["msg"] != "relay_started" || record["status"] != "ready" {
		t.Fatalf("structured record = %#v", record)
	}
}

func deadline(t *testing.T, ctx context.Context) time.Time {
	t.Helper()
	deadline, ok := ctx.Deadline()
	if !ok {
		t.Fatal("shutdown context has no deadline")
	}
	return deadline
}
