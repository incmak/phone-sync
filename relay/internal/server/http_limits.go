package server

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
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
}

type limiterEntry struct {
	tokens     float64
	lastRefill time.Time
	lastSeen   time.Time
}

type pairingRateLimiter struct {
	mu      sync.Mutex
	config  PairingRateLimitConfig
	entries map[string]limiterEntry
}

func newPairingRateLimiter(config PairingRateLimitConfig) *pairingRateLimiter {
	if config.IPBurst <= 0 || config.TokenBurst <= 0 || config.RefillInterval <= 0 || config.IdleTTL <= 0 {
		panic("invalid pairing rate limits")
	}
	return &pairingRateLimiter{config: config, entries: make(map[string]limiterEntry)}
}

func (l *pairingRateLimiter) allowIP(remoteAddr string, now time.Time) (bool, time.Duration) {
	return l.allow("ip:"+normalizedRemoteIP(remoteAddr), l.config.IPBurst, now)
}

func (l *pairingRateLimiter) allowToken(token string, now time.Time) (bool, time.Duration) {
	return l.allow("token:"+token, l.config.TokenBurst, now)
}

func (l *pairingRateLimiter) allow(key string, burst int, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	entry, exists := l.entries[key]
	if !exists {
		entry = limiterEntry{tokens: float64(burst), lastRefill: now}
	}
	elapsed := now.Sub(entry.lastRefill)
	if elapsed > 0 {
		entry.tokens = math.Min(float64(burst), entry.tokens+float64(elapsed)/float64(l.config.RefillInterval))
		entry.lastRefill = now
	}
	entry.lastSeen = now
	if entry.tokens < 1 {
		l.entries[key] = entry
		wait := time.Duration(math.Ceil((1 - entry.tokens) * float64(l.config.RefillInterval)))
		if wait < time.Second {
			wait = time.Second
		}
		return false, wait
	}
	entry.tokens--
	l.entries[key] = entry
	return true, 0
}

func (l *pairingRateLimiter) cleanup(now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for key, entry := range l.entries {
		if now.Sub(entry.lastSeen) >= l.config.IdleTTL {
			delete(l.entries, key)
		}
	}
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
		remoteAddr := r.RemoteAddr
		if s.trustProxyHeaders {
			if forwardedIP := firstForwardedIP(r.Header.Values("X-Forwarded-For")); forwardedIP != "" {
				remoteAddr = forwardedIP
			}
		}
		if allowed, retry := s.pairLimiter.allowIP(remoteAddr, s.now()); !allowed {
			writeRateLimited(w, retry)
			return
		}
		next.ServeHTTP(w, r)
	})
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
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(s.maintenanceInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.runMaintenance(s.now())
			}
		}
	}()
	return done
}

func (s *Server) runMaintenance(now time.Time) {
	if _, err := s.mailbox.Expire(now); err != nil {
		log.Printf("expire mailboxes: %v", err)
	}
	if err := s.mailbox.ExpireStatuses(now); err != nil {
		log.Printf("expire delivery statuses: %v", err)
	}
	if _, err := s.pairStore.SweepExpired(now); err != nil {
		log.Printf("expire pairing tokens: %v", err)
	}
	s.jtiCache.Cleanup(now)
	s.pairLimiter.cleanup(now)
}
