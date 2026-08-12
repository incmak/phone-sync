package main

import (
	"context"
	"testing"
	"time"
)

func TestGracefulStopCreatesFreshHTTPDeadlineAfterMaintenanceJoin(t *testing.T) {
	maintenanceDone := make(chan struct{})
	stopCalled := make(chan struct{})
	shutdownCalled := false
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = gracefulStop(func() { close(stopCalled) }, maintenanceDone, 20*time.Millisecond, func(ctx context.Context) error {
			shutdownCalled = true
			if remaining := time.Until(deadline(t, ctx)); remaining < 15*time.Millisecond {
				t.Errorf("HTTP shutdown received stale deadline with %s remaining", remaining)
			}
			return nil
		})
	}()
	<-stopCalled
	time.Sleep(30 * time.Millisecond)
	close(maintenanceDone)
	<-done
	if !shutdownCalled {
		t.Fatal("HTTP shutdown was not called")
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
