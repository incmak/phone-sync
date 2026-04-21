package server

import (
	"context"
	"crypto/ed25519"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// JTICache tracks seen JWT IDs to prevent replay attacks.
// Entries are retained for 2*ttl so the cache outlives the matching JWT's
// exp window, closing the gap between a GC sweep and JWT expiry.
type JTICache struct {
	mu     sync.Mutex
	seen   map[string]time.Time
	ttl    time.Duration
	lastGC time.Time
}

// NewJTICache creates a JTICache with the given retention TTL.
func NewJTICache(ttl time.Duration) *JTICache {
	return &JTICache{seen: make(map[string]time.Time), ttl: ttl}
}

// CheckAndSet records jti or returns an error if already seen within 2*ttl.
func (c *JTICache) CheckAndSet(jti string, now time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if now.Sub(c.lastGC) > c.ttl {
		for k, t := range c.seen {
			if now.Sub(t) > 2*c.ttl {
				delete(c.seen, k)
			}
		}
		c.lastGC = now
	}
	if _, ok := c.seen[jti]; ok {
		return errors.New("jti replay")
	}
	c.seen[jti] = now
	return nil
}

type ctxKey string

const ctxDeviceID ctxKey = "device_id"

func withDeviceID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, ctxDeviceID, id)
}

// DeviceIDFromContext is exported for handlers that need to know which paired device is connected.
func DeviceIDFromContext(ctx context.Context) (string, bool) {
	id, ok := ctx.Value(ctxDeviceID).(string)
	return id, ok
}

// authMiddleware gates a handler behind JWT verification bound to the paired device's sign_pubkey.
func (s *Server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if !strings.HasPrefix(authHeader, "Bearer ") {
			http.Error(w, "missing bearer", http.StatusUnauthorized)
			return
		}
		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")

		// Parse without verification first to extract sub — needed to look up the right pubkey.
		parser := jwt.NewParser()
		unverified, _, err := parser.ParseUnverified(tokenStr, jwt.MapClaims{})
		if err != nil {
			http.Error(w, "bad token", http.StatusUnauthorized)
			return
		}
		claims, ok := unverified.Claims.(jwt.MapClaims)
		if !ok {
			http.Error(w, "bad claims", http.StatusUnauthorized)
			return
		}
		sub, _ := claims["sub"].(string)
		if sub == "" {
			http.Error(w, "missing sub", http.StatusUnauthorized)
			return
		}

		signPk, err := s.pairStore.SignPubkeyFor(sub)
		if err != nil || len(signPk) != ed25519.PublicKeySize {
			http.Error(w, "unknown device", http.StatusUnauthorized)
			return
		}

		// Verify signature + validate exp using the stored pubkey.
		_, err = jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
			if t.Method.Alg() != "EdDSA" {
				return nil, errors.New("bad alg")
			}
			return ed25519.PublicKey(signPk), nil
		})
		if err != nil {
			http.Error(w, "invalid signature or expired", http.StatusUnauthorized)
			return
		}

		jti, _ := claims["jti"].(string)
		if jti == "" {
			http.Error(w, "missing jti", http.StatusUnauthorized)
			return
		}
		if err := s.jtiCache.CheckAndSet(jti, time.Now()); err != nil {
			http.Error(w, "jti replay", http.StatusUnauthorized)
			return
		}

		r = r.WithContext(withDeviceID(r.Context(), sub))
		next.ServeHTTP(w, r)
	})
}
