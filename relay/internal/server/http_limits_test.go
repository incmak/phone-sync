package server

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/relay/internal/store"
)

func TestPairBodyLimitRejects65KiB(t *testing.T) {
	srv := newConfiguredTestServer(t, DefaultConfig())
	body := validPairInitBody("body-limit")
	body["display_name"] = strings.Repeat("a", 65<<10)

	response := pairRequest(t, srv, http.MethodPost, "/pair/init", body, "192.0.2.1:1000")
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("65 KiB pairing body status = %d, want 413; body=%q", response.Code, response.Body.String())
	}
}

func TestPairJSONRejectsUnknownFieldsAndTrailingValues(t *testing.T) {
	tests := []struct {
		name string
		body string
	}{
		{name: "unknown field", body: `{"pair_token":"unknown","device_id":"a","enc_pubkey":"` + encodedKey(32) + `","sign_pubkey":"` + encodedKey(32) + `","admin":true}`},
		{name: "trailing object", body: mustJSON(t, validPairInitBody("trailing-object")) + `{}`},
		{name: "trailing scalar", body: mustJSON(t, validPairInitBody("trailing-scalar")) + ` true`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			srv := newConfiguredTestServer(t, DefaultConfig())
			request := httptest.NewRequest(http.MethodPost, "/pair/init", strings.NewReader(test.body))
			request.RemoteAddr = "192.0.2.2:2000"
			response := httptest.NewRecorder()
			srv.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400; body=%q", response.Code, response.Body.String())
			}
		})
	}
}

func TestPairKeyLimitRequiresExactDecodedPublicKeySizes(t *testing.T) {
	tests := []struct {
		name      string
		encBytes  int
		signBytes int
		want      int
	}{
		{name: "exact", encBytes: 32, signBytes: 32, want: http.StatusOK},
		{name: "short X25519", encBytes: 31, signBytes: 32, want: http.StatusBadRequest},
		{name: "long X25519", encBytes: 33, signBytes: 32, want: http.StatusBadRequest},
		{name: "short Ed25519", encBytes: 32, signBytes: 31, want: http.StatusBadRequest},
		{name: "long Ed25519", encBytes: 32, signBytes: 33, want: http.StatusBadRequest},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			srv := newConfiguredTestServer(t, DefaultConfig())
			body := validPairInitBody("key-" + strings.ReplaceAll(test.name, " ", "-"))
			body["enc_pubkey"] = encodedKey(test.encBytes)
			body["sign_pubkey"] = encodedKey(test.signBytes)
			response := pairRequest(t, srv, http.MethodPost, "/pair/init", body, "192.0.2.3:3000")
			if response.Code != test.want {
				t.Fatalf("status = %d, want %d; body=%q", response.Code, test.want, response.Body.String())
			}
		})
	}
}

func TestPairDisplayNameLimitCountsUTF8Bytes(t *testing.T) {
	tests := []struct {
		name string
		text string
		want int
	}{
		{name: "64 bytes", text: strings.Repeat("é", 32), want: http.StatusOK},
		{name: "66 bytes", text: strings.Repeat("é", 33), want: http.StatusBadRequest},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			srv := newConfiguredTestServer(t, DefaultConfig())
			body := validPairInitBody("display-" + test.name)
			body["display_name"] = test.text
			response := pairRequest(t, srv, http.MethodPost, "/pair/init", body, "192.0.2.4:4000")
			if response.Code != test.want {
				t.Fatalf("status = %d, want %d; body=%q", response.Code, test.want, response.Body.String())
			}
		})
	}
}

func TestPairRateLimitNormalizesRemoteIPAndReturnsRetryAfter(t *testing.T) {
	now := time.Unix(1000, 0)
	config := DefaultConfig()
	config.Now = func() time.Time { return now }
	config.PairingRateLimits.IPBurst = 2
	config.PairingRateLimits.TokenBurst = 100
	config.PairingRateLimits.RefillInterval = time.Hour
	srv := newConfiguredTestServer(t, config)

	for index, remoteAddr := range []string{"192.0.2.20:1000", "192.0.2.20:2000", "192.0.2.20:3000"} {
		response := pairRequest(t, srv, http.MethodPost, "/pair/init", validPairInitBody("ip-"+remoteAddr), remoteAddr)
		if index < 2 && response.Code != http.StatusOK {
			t.Fatalf("request %d status = %d, want 200; body=%q", index+1, response.Code, response.Body.String())
		}
		if index == 2 {
			if response.Code != http.StatusTooManyRequests {
				t.Fatalf("burst status = %d, want 429; body=%q", response.Code, response.Body.String())
			}
			if response.Header().Get("Retry-After") == "" {
				t.Fatal("429 response has no Retry-After header")
			}
		}
	}
}

func TestPairRateLimitUsesForwardedIPOnlyForTrustedProxy(t *testing.T) {
	tests := []struct {
		name              string
		trustProxyHeaders bool
		wantSecond        int
	}{
		{name: "direct traffic cannot spoof", trustProxyHeaders: false, wantSecond: http.StatusTooManyRequests},
		{name: "trusted proxy separates clients", trustProxyHeaders: true, wantSecond: http.StatusOK},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			config := DefaultConfig()
			config.Now = func() time.Time { return time.Unix(1500, 0) }
			config.PairingRateLimits.IPBurst = 1
			config.PairingRateLimits.TokenBurst = 100
			config.PairingRateLimits.RefillInterval = time.Hour
			config.TrustProxyHeaders = test.trustProxyHeaders
			srv := newConfiguredTestServer(t, config)

			for index, forwardedIP := range []string{"192.0.2.30", "192.0.2.31"} {
				request := httptest.NewRequest(http.MethodPost, "/pair/init", strings.NewReader(mustJSON(t, validPairInitBody("proxy-"+forwardedIP))))
				request.RemoteAddr = "172.30.0.2:4000"
				request.Header.Set("X-Forwarded-For", forwardedIP)
				response := httptest.NewRecorder()
				srv.Handler().ServeHTTP(response, request)
				want := http.StatusOK
				if index == 1 {
					want = test.wantSecond
				}
				if response.Code != want {
					t.Fatalf("request %d status = %d, want %d; body=%q", index+1, response.Code, want, response.Body.String())
				}
			}
		})
	}
}

func TestPairTokenRateLimitAppliesAcrossRemoteIPs(t *testing.T) {
	now := time.Unix(2000, 0)
	config := DefaultConfig()
	config.Now = func() time.Time { return now }
	config.PairingRateLimits.IPBurst = 100
	config.PairingRateLimits.TokenBurst = 2
	config.PairingRateLimits.RefillInterval = time.Hour
	srv := newConfiguredTestServer(t, config)

	for index := 0; index < 3; index++ {
		body := validPairInitBody("shared-token")
		body["device_id"] = "device-a"
		response := pairRequest(t, srv, http.MethodPost, "/pair/init", body, "198.51.100."+string(rune('1'+index))+":1000")
		if index < 2 && response.Code != http.StatusOK {
			t.Fatalf("request %d status = %d, want 200; body=%q", index+1, response.Code, response.Body.String())
		}
		if index == 2 && response.Code != http.StatusTooManyRequests {
			t.Fatalf("token burst status = %d, want 429; body=%q", response.Code, response.Body.String())
		}
	}
}

func TestPairRateLimiterCleanupEvictsOnlyIdleEntries(t *testing.T) {
	now := time.Unix(3000, 0)
	config := DefaultConfig()
	config.Now = func() time.Time { return now }
	config.PairingRateLimits.IdleTTL = 10 * time.Minute
	srv := newConfiguredTestServer(t, config)

	_ = pairRequest(t, srv, http.MethodPost, "/pair/init", validPairInitBody("idle"), "203.0.113.1:1000")
	if got := srv.pairLimiter.entryCount(); got != 2 {
		t.Fatalf("limiter entry count = %d, want IP and token entries", got)
	}
	srv.pairLimiter.cleanup(now.Add(10 * time.Minute))
	if got := srv.pairLimiter.entryCount(); got != 0 {
		t.Fatalf("idle limiter entries at cleanup boundary = %d, want 0", got)
	}
}

func TestHTTPServerTimeoutConfiguration(t *testing.T) {
	handler := http.NewServeMux()
	srv := NewHTTPServer(":9999", handler)
	if srv.Addr != ":9999" || srv.Handler != handler {
		t.Fatalf("server address/handler = %q/%T", srv.Addr, srv.Handler)
	}
	if srv.ReadHeaderTimeout != 5*time.Second || srv.ReadTimeout != 15*time.Second ||
		srv.WriteTimeout != 15*time.Second || srv.IdleTimeout != 75*time.Second || srv.MaxHeaderBytes != 16<<10 {
		t.Fatalf("HTTP bounds = header=%s read=%s write=%s idle=%s headers=%d",
			srv.ReadHeaderTimeout, srv.ReadTimeout, srv.WriteTimeout, srv.IdleTimeout, srv.MaxHeaderBytes)
	}
}

func TestJTICleanupRemovesOnlyExpiredReplayEntries(t *testing.T) {
	cache := NewJTICache(time.Minute)
	now := time.Unix(4000, 0)
	if err := cache.CheckAndSet("old", now); err != nil {
		t.Fatal(err)
	}
	if err := cache.CheckAndSet("fresh", now.Add(90*time.Second)); err != nil {
		t.Fatal(err)
	}
	cache.Cleanup(now.Add(2*time.Minute + time.Nanosecond))
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if _, exists := cache.seen["old"]; exists {
		t.Fatal("expired JTI survived cleanup")
	}
	if _, exists := cache.seen["fresh"]; !exists {
		t.Fatal("fresh JTI was removed")
	}
}

func TestMaintenanceLoopStopsAfterCancellation(t *testing.T) {
	config := DefaultConfig()
	config.MaintenanceInterval = time.Hour
	srv := newConfiguredTestServer(t, config)
	ctx, cancel := context.WithCancel(context.Background())
	done := srv.StartMaintenance(ctx)
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("maintenance loop did not stop after cancellation")
	}
}

func newConfiguredTestServer(t *testing.T, config Config) *Server {
	t.Helper()
	b, err := store.OpenBolt(filepath.Join(t.TempDir(), "relay.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = b.Close() })
	return NewWithConfig(b, config)
}

func validPairInitBody(token string) map[string]any {
	return map[string]any{
		"pair_token":  token,
		"device_id":   "device-a",
		"enc_pubkey":  encodedKey(32),
		"sign_pubkey": encodedKey(32),
	}
}

func encodedKey(size int) string {
	return base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{1}, size))
}

func pairRequest(t *testing.T, srv *Server, method, target string, body map[string]any, remoteAddr string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(method, target, strings.NewReader(mustJSON(t, body)))
	request.RemoteAddr = remoteAddr
	response := httptest.NewRecorder()
	srv.Handler().ServeHTTP(response, request)
	return response
}

func mustJSON(t *testing.T, value any) string {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}
