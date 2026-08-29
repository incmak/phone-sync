package server

import (
	"testing"
	"time"
)

func TestWebSocketLifecycleDrainSealsAdmissionAndWaitsForCleanup(t *testing.T) {
	lifecycle := newWebSocketLifecycle()
	registration, registered := lifecycle.register()
	if !registered {
		t.Fatal("initial WebSocket lifecycle registration was rejected")
	}

	drainDone := lifecycle.Drain(serviceRestartCloseCode, serviceRestartCloseReason)
	if repeated := lifecycle.Drain(serviceRestartCloseCode, serviceRestartCloseReason); repeated != drainDone {
		t.Fatal("repeated drain did not return the shared completion channel")
	}
	if _, registered = lifecycle.register(); registered {
		t.Fatal("WebSocket lifecycle admitted a registration after drain began")
	}
	select {
	case signal := <-registration.drain:
		if signal.code != serviceRestartCloseCode || signal.reason != serviceRestartCloseReason {
			t.Fatalf("drain signal = %#v, want service restart", signal)
		}
	case <-time.After(time.Second):
		t.Fatal("active WebSocket lifecycle registration was not signaled")
	}
	select {
	case <-drainDone:
		t.Fatal("WebSocket lifecycle drain completed before cleanup acknowledgement")
	default:
	}

	registration.unregister()
	registration.unregister()
	select {
	case <-drainDone:
	case <-time.After(time.Second):
		t.Fatal("WebSocket lifecycle drain did not complete after cleanup acknowledgement")
	}
}
