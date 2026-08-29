package server

import (
	"container/list"
	"context"
	"crypto/ed25519"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

var ErrJTIReplay = errors.New("jti replay")
var ErrJTICapacity = errors.New("jti cache capacity reached")

const (
	maxJWTBytes          = 4 << 10
	maxJWTLifetime       = 60 * time.Second
	maxJWTIssuedAtFuture = 30 * time.Second
	jwtClockLeeway       = 5 * time.Second
)

type JTICacheConfig struct {
	TTL          time.Duration
	MaxEntries   int
	CleanupBatch int
}

type jtiEntry struct {
	jti    string
	seenAt time.Time
}

// JTICache tracks seen JWT IDs to prevent replay attacks.
// Entries are retained for 2*ttl so the cache outlives the matching JWT's
// exp window, closing the gap between a GC sweep and JWT expiry.
type JTICache struct {
	mu     sync.Mutex
	seen   map[string]*list.Element
	oldest *list.List
	config JTICacheConfig
}

// NewJTICache creates a JTICache with the given retention TTL.
func NewJTICache(ttl time.Duration) *JTICache {
	return NewJTICacheWithConfig(JTICacheConfig{TTL: ttl, MaxEntries: 100_000, CleanupBatch: 256})
}

func NewJTICacheWithConfig(config JTICacheConfig) *JTICache {
	if config.TTL <= 0 || config.MaxEntries <= 0 || config.CleanupBatch <= 0 {
		panic("invalid JTI cache config")
	}
	return &JTICache{seen: make(map[string]*list.Element), oldest: list.New(), config: config}
}

// CheckAndSet records jti or returns an error if already seen within 2*ttl.
func (c *JTICache) CheckAndSet(jti string, now time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if element, ok := c.seen[jti]; ok {
		entry := element.Value.(*jtiEntry)
		if now.Sub(entry.seenAt) <= 2*c.config.TTL {
			return ErrJTIReplay
		}
		delete(c.seen, jti)
		c.oldest.Remove(element)
	}
	if len(c.seen) >= c.config.MaxEntries {
		return ErrJTICapacity
	}
	c.seen[jti] = c.oldest.PushBack(&jtiEntry{jti: jti, seenAt: now})
	return nil
}

func (c *JTICache) Cleanup(now time.Time) int {
	inspected := 0
	for inspected < c.config.CleanupBatch {
		c.mu.Lock()
		element := c.oldest.Front()
		if element == nil {
			c.mu.Unlock()
			break
		}
		entry := element.Value.(*jtiEntry)
		inspected++
		if now.Sub(entry.seenAt) <= 2*c.config.TTL {
			c.mu.Unlock()
			break
		}
		delete(c.seen, entry.jti)
		c.oldest.Remove(element)
		c.mu.Unlock()
	}
	return inspected
}

func (c *JTICache) EntryCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.seen)
}

type ctxKey string

const (
	ctxDeviceID ctxKey = "device_id"
	ctxPairID   ctxKey = "pair_id"
)

func withPairSession(ctx context.Context, deviceID, pairID string) context.Context {
	ctx = context.WithValue(ctx, ctxDeviceID, deviceID)
	return context.WithValue(ctx, ctxPairID, pairID)
}

// DeviceIDFromContext is exported for handlers that need to know which paired device is connected.
func DeviceIDFromContext(ctx context.Context) (string, bool) {
	id, ok := ctx.Value(ctxDeviceID).(string)
	return id, ok
}

func PairIDFromContext(ctx context.Context) (string, bool) {
	id, ok := ctx.Value(ctxPairID).(string)
	return id, ok
}

// authMiddleware gates a handler behind JWT verification bound to the paired device's sign_pubkey.
func (s *Server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if len(authHeader) > len("Bearer ")+maxJWTBytes || !strings.HasPrefix(authHeader, "Bearer ") {
			http.Error(w, "missing bearer", http.StatusUnauthorized)
			return
		}
		tokenStr := strings.TrimPrefix(authHeader, "Bearer ")
		if tokenStr == "" || len(tokenStr) > maxJWTBytes {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}

		// Parse without verification first to extract sub, which selects the right public key.
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

		session, err := s.pairStore.SessionFor(sub)
		if err != nil || len(session.SignPubkey) != ed25519.PublicKeySize {
			http.Error(w, "unknown device", http.StatusUnauthorized)
			return
		}
		now := s.now()

		// Verify signature and require expiration using the stored pubkey. The
		// remaining claim relationships are checked below against the same clock.
		verified, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
			if t.Method.Alg() != "EdDSA" {
				return nil, errors.New("bad alg")
			}
			return ed25519.PublicKey(session.SignPubkey), nil
		}, jwt.WithValidMethods([]string{"EdDSA"}), jwt.WithExpirationRequired(),
			jwt.WithTimeFunc(func() time.Time { return now }), jwt.WithLeeway(jwtClockLeeway))
		if err != nil {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		verifiedClaims, ok := verified.Claims.(jwt.MapClaims)
		if !ok {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		jti, err := validateAuthClaims(verifiedClaims, sub, now)
		if err != nil {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		if err := s.jtiCache.CheckAndSet(jti, now); err != nil {
			http.Error(w, "jti replay", http.StatusUnauthorized)
			return
		}

		r = r.WithContext(withPairSession(r.Context(), sub, session.PairID))
		next.ServeHTTP(w, r)
	})
}

func validateAuthClaims(claims jwt.MapClaims, expectedSubject string, now time.Time) (string, error) {
	subject, err := claims.GetSubject()
	if err != nil || subject == "" || subject != expectedSubject {
		return "", errors.New("invalid subject")
	}
	jti, ok := claims["jti"].(string)
	parsedJTI, err := uuid.Parse(jti)
	if !ok || err != nil || parsedJTI.String() != jti {
		return "", errors.New("invalid jti")
	}
	issuedAt, err := claims.GetIssuedAt()
	if err != nil || issuedAt == nil {
		return "", errors.New("missing issued-at")
	}
	expiresAt, err := claims.GetExpirationTime()
	if err != nil || expiresAt == nil {
		return "", errors.New("missing expiration")
	}
	if issuedAt.Time.After(now.Add(maxJWTIssuedAtFuture)) {
		return "", errors.New("issued-at too far in future")
	}
	lifetime := expiresAt.Time.Sub(issuedAt.Time)
	if lifetime <= 0 || lifetime > maxJWTLifetime {
		return "", errors.New("invalid token lifetime")
	}
	return jti, nil
}
