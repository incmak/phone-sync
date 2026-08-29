package server

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

func TestServerCapacityControlsHealthAndPairMutation(t *testing.T) {
	config := DefaultConfig()
	config.BuildVersion = "test-build"
	config.CapacityCheck = func() error { return ErrServerCapacity }
	server := newTestServerWithConfig(t, config)

	assertHealthStatus(t, server, "/health/live", http.StatusOK, `"status":"live"`)
	assertHealthStatus(t, server, "/health/ready", http.StatusServiceUnavailable, `"status":"not_ready"`)
	assertHealthStatus(t, server, "/health", http.StatusServiceUnavailable, `"version":"test-build"`)

	response := pairRequest(t, server, http.MethodPost, "/pair/init", validPairInitBody("capacity-pair"), "192.0.2.1:1000")
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("pair mutation status = %d, want 503; body=%q", response.Code, response.Body.String())
	}
	if response.Header().Get("Retry-After") == "" {
		t.Fatal("capacity rejection omitted Retry-After")
	}
	if _, err := server.pairStore.GetPending("capacity-pair"); err == nil {
		t.Fatal("capacity-rejected pairing request persisted state")
	}
}

func TestHealthBecomesNotReadyWhenShutdownBeginsOrBoltIsUnreadable(t *testing.T) {
	server, _ := newMailboxTestServerWithBolt(t)
	assertHealthStatus(t, server, "/health/ready", http.StatusOK, `"status":"ready"`)
	server.BeginShutdown()
	assertHealthStatus(t, server, "/health/ready", http.StatusServiceUnavailable, `"status":"not_ready"`)
	assertHealthStatus(t, server, "/health/live", http.StatusOK, `"status":"live"`)

	secondServer, secondBolt := newMailboxTestServerWithBolt(t)
	if err := secondBolt.Close(); err != nil {
		t.Fatal(err)
	}
	assertHealthStatus(t, secondServer, "/health/ready", http.StatusServiceUnavailable, `"status":"not_ready"`)
	assertHealthStatus(t, secondServer, "/health/live", http.StatusOK, `"status":"live"`)
}

func TestServerCapacityRejectsRelayPutWithoutPersistence(t *testing.T) {
	config := DefaultConfig()
	config.CapacityCheck = func() error { return ErrServerCapacity }
	server := newTestServerWithConfig(t, config)
	pair := registerMailboxTestPair(t, server)
	msgID := "99999999-9999-4999-8999-999999999999"
	var rejection string
	server.handleRelayPutForPair(pair.deviceA, pair.pairID, RelayPut{
		V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID),
	}, func(any) error {
		t.Fatal("capacity-rejected put was accepted")
		return nil
	}, func(_ string, reason string) error {
		rejection = reason
		return nil
	})
	if rejection != "server_capacity" {
		t.Fatalf("rejection = %q, want server_capacity", rejection)
	}
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("capacity-rejected put persisted %d mailbox records", len(pending))
	}
}

func TestDiskCapacityCheckUsesAvailableBlocksAndFailsClosed(t *testing.T) {
	check := newDiskCapacityCheck("/srv/data/relay.db", 10*4096, func(path string, stat *unix.Statfs_t) error {
		if path != "/srv/data" {
			t.Fatalf("statfs path = %q, want database directory", path)
		}
		stat.Bsize = 4096
		stat.Bavail = 9
		return nil
	})
	if err := check(); !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("low disk error = %v, want ErrServerCapacity", err)
	}

	check = newDiskCapacityCheck("/srv/data/relay.db", 10*4096, func(_ string, stat *unix.Statfs_t) error {
		stat.Bsize = 4096
		stat.Bavail = 10
		return nil
	})
	if err := check(); err != nil {
		t.Fatalf("sufficient disk rejected: %v", err)
	}

	check = newDiskCapacityCheck("/srv/data/relay.db", 1, func(string, *unix.Statfs_t) error {
		return errors.New("statfs unavailable")
	})
	if err := check(); !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("statfs failure = %v, want ErrServerCapacity", err)
	}
}

func assertHealthStatus(t *testing.T, server *Server, path string, want int, bodyFragment string) {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, path, nil)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != want {
		t.Fatalf("%s status = %d, want %d; body=%q", path, response.Code, want, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), bodyFragment) {
		t.Fatalf("%s body = %q, want fragment %q", path, response.Body.String(), bodyFragment)
	}
}
