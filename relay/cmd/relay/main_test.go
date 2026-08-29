package main

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
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

func TestRunHealthcheckRequiresReadyHTTP200(t *testing.T) {
	requests := 0
	healthServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.Method != http.MethodGet || r.URL.Path != "/health/ready" {
			t.Errorf("health request = %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer healthServer.Close()

	if err := runHealthcheckCommand([]string{"--url", healthServer.URL + "/health/ready"}, healthServer.Client()); err != nil {
		t.Fatalf("ready healthcheck failed: %v", err)
	}
	if requests != 1 {
		t.Fatalf("health requests = %d, want 1", requests)
	}

	unreadyServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer unreadyServer.Close()
	if err := runHealthcheckCommand([]string{"--url", unreadyServer.URL + "/health/ready"}, unreadyServer.Client()); err == nil {
		t.Fatal("unready endpoint passed healthcheck")
	}
	if err := runHealthcheckCommand([]string{"--url", "ftp://relay.invalid/health/ready"}, healthServer.Client()); err == nil {
		t.Fatal("non-HTTP healthcheck URL was accepted")
	}
	if err := runHealthcheckCommand([]string{"--url", healthServer.URL, "extra"}, healthServer.Client()); err == nil {
		t.Fatal("extra healthcheck argument was accepted")
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
