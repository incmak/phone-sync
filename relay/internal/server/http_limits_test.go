package server

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/twinotify/relay/internal/store"
)

func TestPairBodyLimitRejectsFourKiBPlusOne(t *testing.T) {
	srv := newConfiguredTestServer(t, DefaultConfig())
	body := validPairInitBody("body-limit")
	body["display_name"] = strings.Repeat("a", 4<<10)

	response := pairRequest(t, srv, http.MethodPost, "/pair/init", body, "192.0.2.1:1000")
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("4 KiB plus one pairing body status = %d, want 413; body=%q", response.Code, response.Body.String())
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

func TestPairingPOSTRejectsOversizedProtocolFieldsBeforeTokenAdmission(t *testing.T) {
	const maxPairTokenBytes = 128
	const maxDeviceIDBytes = 128
	const maxEncodedPublicKeyBytes = 44
	const maxEncodedSignatureBytes = 88

	validCompleteBody := func(token string) map[string]any {
		body := validPairInitBody(token)
		body["confirmation_sig"] = encodedKey(64)
		body["responder_confirmation_sig"] = encodedKey(64)
		return body
	}
	tests := []struct {
		name string
		path string
		body map[string]any
	}{
		{name: "init pair token", path: "/pair/init", body: validPairInitBody(strings.Repeat("t", maxPairTokenBytes+1))},
		{name: "hello pair token", path: "/pair/hello", body: validPairInitBody(strings.Repeat("t", maxPairTokenBytes+1))},
		{name: "send signature pair token", path: "/pair/send_sig", body: map[string]any{"pair_token": strings.Repeat("t", maxPairTokenBytes+1), "confirmation_sig": encodedKey(64)}},
		{name: "complete pair token", path: "/pair/complete", body: validCompleteBody(strings.Repeat("t", maxPairTokenBytes+1))},
		{name: "init device id", path: "/pair/init", body: func() map[string]any {
			body := validPairInitBody("bounded-init-device")
			body["device_id"] = strings.Repeat("d", maxDeviceIDBytes+1)
			return body
		}()},
		{name: "hello device id", path: "/pair/hello", body: func() map[string]any {
			body := validPairInitBody("bounded-hello-device")
			body["device_id"] = strings.Repeat("d", maxDeviceIDBytes+1)
			return body
		}()},
		{name: "complete device id", path: "/pair/complete", body: func() map[string]any {
			body := validCompleteBody("bounded-complete-device")
			body["device_id"] = strings.Repeat("d", maxDeviceIDBytes+1)
			return body
		}()},
		{name: "init encryption key", path: "/pair/init", body: func() map[string]any {
			body := validPairInitBody("bounded-init-key")
			body["enc_pubkey"] = strings.Repeat("a", maxEncodedPublicKeyBytes+1)
			return body
		}()},
		{name: "hello signing key", path: "/pair/hello", body: func() map[string]any {
			body := validPairInitBody("bounded-hello-key")
			body["sign_pubkey"] = strings.Repeat("a", maxEncodedPublicKeyBytes+1)
			return body
		}()},
		{name: "complete confirmation signature", path: "/pair/complete", body: func() map[string]any {
			body := validCompleteBody("bounded-complete-signature")
			body["confirmation_sig"] = strings.Repeat("a", maxEncodedSignatureBytes+1)
			return body
		}()},
		{name: "send signature confirmation signature", path: "/pair/send_sig", body: map[string]any{"pair_token": "bounded-send-signature", "confirmation_sig": strings.Repeat("a", maxEncodedSignatureBytes+1)}},
	}

	for index, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			srv := newConfiguredTestServer(t, DefaultConfig())
			response := pairRequest(t, srv, http.MethodPost, test.path, test.body, "192.0.2."+strconv.Itoa(index+1)+":1000")
			if response.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400; body=%q", response.Code, response.Body.String())
			}
			if got := srv.pairLimiter.entryCount(); got != 1 {
				t.Fatalf("limiter entries = %d, want only the IP admission after invalid request", got)
			}
		})
	}
}

func TestPairRateLimiterStoresFixedLengthDigestKeys(t *testing.T) {
	limiter := newPairingRateLimiter(PairingRateLimitConfig{
		IPBurst: 1, TokenBurst: 1, RefillInterval: time.Hour, IdleTTL: time.Hour,
		MaxEntries: 3, CleanupBatch: 1,
	})
	token := strings.Repeat("sensitive-token-material", 100)
	if allowed, _ := limiter.allowToken(token, time.Unix(1, 0)); !allowed {
		t.Fatal("token was unexpectedly rejected")
	}
	deviceID := strings.Repeat("sensitive-device-material", 100)
	if allowed, _ := limiter.allowIPAndDevice("192.0.2.1:1", deviceID, time.Unix(1, 0)); !allowed {
		t.Fatal("device was unexpectedly rejected")
	}

	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	for _, expected := range []struct {
		prefix string
		value  string
	}{
		{prefix: "token:", value: token},
		{prefix: "device:", value: deviceID},
	} {
		key := pairingLimiterKey(strings.TrimSuffix(expected.prefix, ":"), expected.value)
		if _, found := limiter.entries[key]; !found {
			t.Fatalf("missing %s limiter key", expected.prefix)
		}
		if strings.Contains(key, expected.value) {
			t.Fatalf("%s limiter key retains raw value: %q", expected.prefix, key)
		}
		if got, want := len(key), len(expected.prefix)+64; got != want {
			t.Fatalf("%s limiter key length = %d, want %d", expected.prefix, got, want)
		}
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

func TestPairRateLimiterGlobalBudgetFailsClosedWithoutRefreshingExhaustedKeys(t *testing.T) {
	now := time.Unix(3500, 0)
	limiter := newPairingRateLimiter(PairingRateLimitConfig{
		IPBurst: 1, TokenBurst: 1, RefillInterval: time.Hour, IdleTTL: 10 * time.Minute,
		MaxEntries: 2, CleanupBatch: 1,
	})
	if allowed, _ := limiter.allowIP("192.0.2.1:1", now); !allowed {
		t.Fatal("first known key was rejected")
	}
	if allowed, _ := limiter.allowToken("known", now); !allowed {
		t.Fatal("second known key was rejected")
	}
	if allowed, _ := limiter.allowIP("192.0.2.2:1", now); allowed {
		t.Fatal("unknown key was admitted over global budget")
	}
	if got := limiter.entryCount(); got != 2 {
		t.Fatalf("entry cardinality = %d, want 2", got)
	}
	if allowed, _ := limiter.allowIP("192.0.2.1:2", now); allowed {
		t.Fatal("exhausted existing key regained a fresh burst after overflow")
	}
}

func TestAuthenticationRateLimiterConsumesIPAndDeviceBudgetsAtomically(t *testing.T) {
	now := time.Unix(3550, 0)
	limiter := newPairingRateLimiter(PairingRateLimitConfig{
		IPBurst: 3, TokenBurst: 2, RefillInterval: time.Hour, IdleTTL: 10 * time.Minute,
		MaxEntries: 8, CleanupBatch: 1,
	})
	for index := 0; index < 2; index++ {
		if allowed, _ := limiter.allowIPAndDevice("192.0.2.10:1000", "device-a", now); !allowed {
			t.Fatalf("device A admission %d was rejected", index+1)
		}
	}
	if allowed, _ := limiter.allowIPAndDevice("192.0.2.10:1000", "device-a", now); allowed {
		t.Fatal("exhausted device budget was admitted")
	}
	if allowed, _ := limiter.allowIPAndDevice("192.0.2.10:1000", "device-b", now); !allowed {
		t.Fatal("device rejection consumed the shared IP token")
	}
	if allowed, _ := limiter.allowIPAndDevice("192.0.2.10:1000", "device-b", now); allowed {
		t.Fatal("exhausted IP budget was admitted")
	}
}

func TestPairRateLimiterCleanupIsBatchedAndAllowsConcurrentProgress(t *testing.T) {
	now := time.Unix(3600, 0)
	limiter := newPairingRateLimiter(PairingRateLimitConfig{
		IPBurst: 1, TokenBurst: 1, RefillInterval: time.Hour, IdleTTL: time.Minute,
		MaxEntries: 8, CleanupBatch: 2,
	})
	for index := 0; index < 6; index++ {
		limiter.allowToken(fmt.Sprintf("old-%d", index), now)
	}
	if inspected := limiter.cleanup(now.Add(time.Minute)); inspected != 2 {
		t.Fatalf("cleanup inspected = %d, want 2", inspected)
	}
	if got := limiter.entryCount(); got != 4 {
		t.Fatalf("entries after one cleanup batch = %d, want 4", got)
	}
	progress := make(chan struct{})
	go func() {
		limiter.allowIP("198.51.100.1:1", now.Add(time.Minute))
		close(progress)
	}()
	select {
	case <-progress:
	case <-time.After(time.Second):
		t.Fatal("concurrent limiter allow made no progress")
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

func TestMaintenanceStartIsSingleOwnerAndCancellationStopsBetweenUnits(t *testing.T) {
	config := DefaultConfig()
	config.MaintenanceInterval = time.Millisecond
	config.MailboxExpiryBatch = 1
	config.StatusExpiryBatch = 1
	srv := newConfiguredTestServer(t, config)
	entered := make(chan struct{})
	release := make(chan struct{})
	var once sync.Once
	srv.maintenanceBeforeUnit = func(unit string) {
		if unit == "mailbox" {
			once.Do(func() { close(entered); <-release })
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	first := srv.StartMaintenance(ctx)
	second := srv.StartMaintenance(ctx)
	if first != second {
		t.Fatal("second maintenance start created a distinct worker")
	}
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("maintenance worker did not enter first unit")
	}
	cancel()
	close(release)
	select {
	case <-first:
	case <-time.After(time.Second):
		t.Fatal("maintenance did not join after cancellation")
	}
}

func TestShutdownLinearizesBeforeEveryMaintenanceStoreMutation(t *testing.T) {
	now := time.Now()
	tests := []struct {
		unit  string
		setup func(*testing.T, *Server) func(*testing.T)
	}{
		{
			unit: "mailbox",
			setup: func(t *testing.T, server *Server) func(*testing.T) {
				pair := registerMailboxTestPair(t, server)
				msgID := "44444444-4444-4444-8444-444444444444"
				putMailboxRecord(t, server, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), now.Add(-25*time.Hour))
				return func(t *testing.T) {
					pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
					if err != nil || len(pending) != 1 || pending[0].MsgID != msgID {
						t.Fatalf("mailbox maintenance mutated after shutdown: %#v, %v", pending, err)
					}
				}
			},
		},
		{
			unit: "statuses",
			setup: func(t *testing.T, server *Server) func(*testing.T) {
				pair := registerMailboxTestPair(t, server)
				msgID := "33333333-3333-4333-8333-333333333333"
				putMailboxRecord(t, server, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), now.Add(-50*time.Hour))
				if expired, err := server.mailbox.ExpireForPair(pair.pairID, pair.deviceA, now.Add(-25*time.Hour)); err != nil || len(expired) != 1 {
					t.Fatalf("prepare expired status = %d, %v", len(expired), err)
				}
				return func(t *testing.T) {
					statuses, err := server.mailbox.Statuses(pair.deviceA, time.UnixMilli(0))
					if err != nil || len(statuses) != 1 || statuses[0].MsgID != msgID {
						t.Fatalf("status maintenance mutated after shutdown: %#v, %v", statuses, err)
					}
				}
			},
		},
		{
			unit: "pairs",
			setup: func(t *testing.T, server *Server) func(*testing.T) {
				token := "shutdown-maintenance-pair"
				if err := server.pairStore.PutPending(store.PendingPair{
					PairToken: token, DeviceAID: "maintenance-a", AEncPubkey: bytes.Repeat([]byte{1}, 32),
					ASignPubkey: bytes.Repeat([]byte{2}, 32), CreatedAt: now.Add(-10 * time.Minute).Unix(),
				}); err != nil {
					t.Fatal(err)
				}
				return func(t *testing.T) {
					if _, err := server.pairStore.GetPending(token); err != nil {
						t.Fatalf("pair maintenance mutated after shutdown: %v", err)
					}
				}
			},
		},
		{
			unit: "jti",
			setup: func(t *testing.T, server *Server) func(*testing.T) {
				if err := server.jtiCache.CheckAndSet("shutdown-maintenance-jti", now.Add(-3*time.Minute)); err != nil {
					t.Fatal(err)
				}
				return func(t *testing.T) {
					count, err := server.jtiCache.EntryCount()
					if err != nil || count != 1 {
						t.Fatalf("JTI maintenance mutated after shutdown: count=%d err=%v", count, err)
					}
				}
			},
		},
	}

	for _, test := range tests {
		t.Run(test.unit, func(t *testing.T) {
			server := newConfiguredTestServer(t, DefaultConfig())
			assertUnchanged := test.setup(t, server)
			entered := make(chan struct{})
			release := make(chan struct{})
			var once sync.Once
			server.maintenanceBeforeUnit = func(unit string) {
				if unit == test.unit {
					once.Do(func() { close(entered); <-release })
				}
			}
			done := make(chan struct{})
			go func() {
				server.runMaintenance(context.Background(), now)
				close(done)
			}()
			select {
			case <-entered:
			case <-time.After(time.Second):
				t.Fatalf("maintenance did not reach %s", test.unit)
			}
			server.BeginShutdown()
			close(release)
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatalf("maintenance did not stop after %s lost shutdown admission", test.unit)
			}
			assertUnchanged(t)
		})
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
