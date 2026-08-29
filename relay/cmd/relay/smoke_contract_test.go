package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/twinotify/relay/internal/server"
	"github.com/twinotify/relay/internal/store"
)

func TestProductionSmokeMatchesLiveRelayPublicContract(t *testing.T) {
	bolt, err := store.OpenBolt(filepath.Join(t.TempDir(), "relay.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bolt.Close() })
	config := server.DefaultConfig()
	config.BuildVersion = "relay-smoke-live-test"
	app := server.NewWithConfig(bolt, config)
	public := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/health/live", "/health/ready", "/ws":
			app.Handler().ServeHTTP(w, r)
		default:
			http.NotFound(w, r)
		}
	})
	live := httptest.NewServer(public)
	t.Cleanup(live.Close)

	script := filepath.Join("..", "..", "..", "deploy", "smoke-relay.sh")
	command := exec.Command("bash", script,
		"--allow-http",
		"--base-url", live.URL,
		"--expected-version", config.BuildVersion,
	)
	command.Env = append(os.Environ(),
		"TWINOTIFY_SMOKE_ATTEMPTS=1",
		"TWINOTIFY_SMOKE_INTERVAL=0",
	)
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("production smoke rejected the live relay public contract: %v\n%s", err, output)
	}
}
