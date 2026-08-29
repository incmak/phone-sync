package server

import (
	"container/list"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const pairingBodyLimit = 64 << 10

type PairingRateLimitConfig struct {
	IPBurst        int
	TokenBurst     int
	RefillInterval time.Duration
	IdleTTL        time.Duration
	MaxEntries     int
	CleanupBatch   int
}

type AuthenticationRateLimitConfig struct {
	IPBurst        int
	DeviceBurst    int
	RefillInterval time.Duration
	IdleTTL        time.Duration
	MaxEntries     int
	CleanupBatch   int
}

func (c AuthenticationRateLimitConfig) limiterConfig() PairingRateLimitConfig {
	return PairingRateLimitConfig{
		IPBurst: c.IPBurst, TokenBurst: c.DeviceBurst, RefillInterval: c.RefillInterval,
		IdleTTL: c.IdleTTL, MaxEntries: c.MaxEntries, CleanupBatch: c.CleanupBatch,
	}
}

type limiterEntry struct {
	key        string
	tokens     float64
	lastRefill time.Time
	lastSeen   time.Time
}

type pairingRateLimiter struct {
	mu      sync.Mutex
	config  PairingRateLimitConfig
	entries map[string]*list.Element
	oldest  *list.List
}

func newPairingRateLimiter(config PairingRateLimitConfig) *pairingRateLimiter {
	if !validRateLimitConfig(config) {
		panic("invalid pairing rate limits")
	}
	return &pairingRateLimiter{config: config, entries: make(map[string]*list.Element), oldest: list.New()}
}

func validRateLimitConfig(config PairingRateLimitConfig) bool {
	return config.IPBurst > 0 && config.TokenBurst > 0 && config.RefillInterval > 0 && config.IdleTTL > 0 && config.MaxEntries > 0 && config.CleanupBatch > 0
}

func (l *pairingRateLimiter) allowIP(remoteAddr string, now time.Time) (bool, time.Duration) {
	return l.allowAdmissions([]limiterAdmission{{key: "ip:" + normalizedRemoteIP(remoteAddr), burst: l.config.IPBurst}}, now)
}

func (l *pairingRateLimiter) allowToken(token string, now time.Time) (bool, time.Duration) {
	return l.allowAdmissions([]limiterAdmission{{key: "token:" + token, burst: l.config.TokenBurst}}, now)
}

func (l *pairingRateLimiter) allowIPAndDevice(remoteAddr, deviceID string, now time.Time) (bool, time.Duration) {
	return l.allowAdmissions([]limiterAdmission{
		{key: "ip:" + normalizedRemoteIP(remoteAddr), burst: l.config.IPBurst},
		{key: "device:" + deviceID, burst: l.config.TokenBurst},
	}, now)
}

type limiterAdmission struct {
	key   string
	burst int
}

func (l *pairingRateLimiter) allowAdmissions(admissions []limiterAdmission, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	elements := make([]*list.Element, len(admissions))
	missing := 0
	for index, admission := range admissions {
		if element, exists := l.entries[admission.key]; exists {
			elements[index] = element
		} else {
			missing++
		}
	}
	if len(l.entries)+missing > l.config.MaxEntries {
		return false, l.config.IdleTTL
	}
	retry := time.Duration(0)
	for index, element := range elements {
		if element == nil {
			continue
		}
		entry := element.Value.(*limiterEntry)
		elapsed := now.Sub(entry.lastRefill)
		if elapsed > 0 {
			entry.tokens = math.Min(float64(admissions[index].burst), entry.tokens+float64(elapsed)/float64(l.config.RefillInterval))
			entry.lastRefill = now
		}
		entry.lastSeen = now
		l.oldest.MoveToBack(element)
		if entry.tokens < 1 {
			wait := time.Duration(math.Ceil((1 - entry.tokens) * float64(l.config.RefillInterval)))
			if wait < time.Second {
				wait = time.Second
			}
			if wait > retry {
				retry = wait
			}
		}
	}
	if retry > 0 {
		return false, retry
	}
	for index, element := range elements {
		if element == nil {
			admission := admissions[index]
			element = l.oldest.PushBack(&limiterEntry{
				key: admission.key, tokens: float64(admission.burst), lastRefill: now, lastSeen: now,
			})
			l.entries[admission.key] = element
			elements[index] = element
		}
		element.Value.(*limiterEntry).tokens--
	}
	return true, 0
}

func (l *pairingRateLimiter) cleanup(now time.Time) int {
	inspected := 0
	for inspected < l.config.CleanupBatch {
		l.mu.Lock()
		element := l.oldest.Front()
		if element == nil {
			l.mu.Unlock()
			break
		}
		entry := element.Value.(*limiterEntry)
		inspected++
		if now.Sub(entry.lastSeen) < l.config.IdleTTL {
			l.mu.Unlock()
			break
		}
		delete(l.entries, entry.key)
		l.oldest.Remove(element)
		l.mu.Unlock()
	}
	return inspected
}

func (l *pairingRateLimiter) entryCount() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.entries)
}

func normalizedRemoteIP(remoteAddr string) string {
	host := remoteAddr
	if parsedHost, _, err := net.SplitHostPort(remoteAddr); err == nil {
		host = parsedHost
	}
	host = strings.Trim(host, "[]")
	if ip := net.ParseIP(host); ip != nil {
		if ipv4 := ip.To4(); ipv4 != nil {
			return ipv4.String()
		}
		return ip.String()
	}
	return host
}

func (s *Server) pairIPRateLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if allowed, retry := s.pairLimiter.allowIP(s.requestRemoteAddr(r), s.now()); !allowed {
			writeRateLimited(w, retry)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) requestRemoteAddr(r *http.Request) string {
	remoteAddr := r.RemoteAddr
	if s.trustProxyHeaders {
		if forwardedIP := firstForwardedIP(r.Header.Values("X-Forwarded-For")); forwardedIP != "" {
			remoteAddr = forwardedIP
		}
	}
	return remoteAddr
}

func firstForwardedIP(values []string) string {
	for _, value := range values {
		for _, candidate := range strings.Split(value, ",") {
			candidate = strings.TrimSpace(candidate)
			if net.ParseIP(candidate) != nil {
				return candidate
			}
		}
	}
	return ""
}

func (s *Server) allowPairToken(w http.ResponseWriter, token string) bool {
	allowed, retry := s.pairLimiter.allowToken(token, s.now())
	if !allowed {
		writeRateLimited(w, retry)
	}
	return allowed
}

func writeRateLimited(w http.ResponseWriter, retry time.Duration) {
	seconds := int(math.Ceil(retry.Seconds()))
	if seconds < 1 {
		seconds = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(seconds))
	http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
}

func decodePairJSON(w http.ResponseWriter, r *http.Request, destination any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, pairingBodyLimit)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		writePairJSONError(w, err)
		return false
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		if err == nil {
			http.Error(w, "trailing json", http.StatusBadRequest)
		} else {
			writePairJSONError(w, err)
		}
		return false
	}
	return true
}

func writePairJSONError(w http.ResponseWriter, err error) {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) {
		http.Error(w, "request body too large", http.StatusRequestEntityTooLarge)
		return
	}
	http.Error(w, "bad json", http.StatusBadRequest)
}

func validDisplayName(name string) bool {
	return utf8.ValidString(name) && len([]byte(name)) <= 64
}

func decodePairPublicKeys(encodedEncryptionKey, encodedSigningKey string) ([]byte, []byte, error) {
	encryptionKey, encryptionErr := base64.StdEncoding.DecodeString(encodedEncryptionKey)
	signingKey, signingErr := base64.StdEncoding.DecodeString(encodedSigningKey)
	if encryptionErr != nil || signingErr != nil || len(encryptionKey) != 32 || len(signingKey) != ed25519.PublicKeySize {
		return nil, nil, errors.New("invalid pairing public key")
	}
	return encryptionKey, signingKey, nil
}

func NewHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       75 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
}

// StartMaintenance starts the server's single periodic maintenance worker.
// Cancel ctx and wait for the returned channel before closing the Bolt store.
func (s *Server) StartMaintenance(ctx context.Context) <-chan struct{} {
	s.maintenanceMu.Lock()
	defer s.maintenanceMu.Unlock()
	if s.maintenanceDone != nil {
		return s.maintenanceDone
	}
	done := make(chan struct{})
	s.maintenanceDone = done
	go func() {
		defer close(done)
		ticker := time.NewTicker(s.maintenanceInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.runMaintenance(ctx, s.now())
			}
		}
	}()
	return done
}

func (s *Server) runMaintenance(ctx context.Context, now time.Time) {
	if !s.beforeMaintenanceUnit(ctx, "mailbox") {
		return
	}
	release, admitted := s.acquireMutationAdmission()
	if !admitted {
		return
	}
	_, err := s.mailbox.ExpireBatch(now, s.mailboxExpiryBatch)
	release()
	s.metrics.recordMaintenance(maintenanceMailbox, err)
	if err != nil {
		slog.Error("maintenance_failed", "operation", "mailbox_expiry")
	}
	if !s.beforeMaintenanceUnit(ctx, "statuses") {
		return
	}
	release, admitted = s.acquireMutationAdmission()
	if !admitted {
		return
	}
	_, err = s.mailbox.ExpireStatusesBatch(now, s.statusExpiryBatch)
	release()
	s.metrics.recordMaintenance(maintenanceStatuses, err)
	if err != nil {
		slog.Error("maintenance_failed", "operation", "status_expiry")
	}
	if !s.beforeMaintenanceUnit(ctx, "pairs") {
		return
	}
	release, admitted = s.acquireMutationAdmission()
	if !admitted {
		return
	}
	_, err = s.pairStore.SweepExpired(now)
	release()
	s.metrics.recordMaintenance(maintenancePairs, err)
	if err != nil {
		slog.Error("maintenance_failed", "operation", "pair_expiry")
	}
	if !s.beforeMaintenanceUnit(ctx, "jti") {
		return
	}
	release, admitted = s.acquireMutationAdmission()
	if !admitted {
		return
	}
	_, err = s.jtiCache.Cleanup(now)
	release()
	s.metrics.recordMaintenance(maintenanceJTI, err)
	if err != nil {
		slog.Error("maintenance_failed", "operation", "jti_expiry")
	}
	if !s.beforeMaintenanceUnit(ctx, "limiter") {
		return
	}
	s.pairLimiter.cleanup(now)
	s.authLimiter.cleanup(now)
}

func (s *Server) beforeMaintenanceUnit(ctx context.Context, unit string) bool {
	if ctx.Err() != nil {
		return false
	}
	if s.maintenanceBeforeUnit != nil {
		s.maintenanceBeforeUnit(unit)
	}
	return ctx.Err() == nil
}
