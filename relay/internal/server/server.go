package server

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/twinotify/relay/internal/store"
)

type Server struct {
	router              *chi.Mux
	validator           *Validator
	pairStore           *store.PairStore
	jtiCache            *JTICache
	pairHub             *PairHub
	clientHub           *ClientHub
	mailbox             *store.MailboxStore
	handoffs            *durableHandoffs
	pairLimiter         *pairingRateLimiter
	now                 func() time.Time
	maintenanceInterval time.Duration
	trustProxyHeaders   bool

	// relayHelloBeforeActivate is a deterministic test seam around the
	// drain-to-live handoff. Production constructors leave it nil.
	relayHelloBeforeActivate func(deviceID string)
	// relayBeforeDeliveryTransfer is a deterministic test barrier immediately
	// before the Bolt-view-to-hub-queue linearization point.
	relayBeforeDeliveryTransfer func(deviceID string)
	webSocketBeforeRegister     func(deviceID, pairID string)
	revokeAfterCommit           func(pairID string)
	relayPutBeforeStore         func(deviceID, pairID, msgID string)
	relayAckBeforeStore         func(deviceID, pairID, msgID string)
}

// NewWithStore builds a server backed by the given Bolt DB. Tests use this.
func NewWithStore(b *store.Bolt) *Server {
	return NewWithConfig(b, DefaultConfig())
}

// NewWithDependencies builds a server with explicit mailbox limits. Production
// uses NewWithStore; focused durability tests use this constructor so test-only
// quotas and retention never leak through environment variables.
func NewWithDependencies(b *store.Bolt, mailboxLimits store.MailboxLimits) *Server {
	config := DefaultConfig()
	config.MailboxLimits = mailboxLimits
	return NewWithConfig(b, config)
}

type Config struct {
	MailboxLimits       store.MailboxLimits
	PendingPairLimits   store.PendingPairLimits
	PairingRateLimits   PairingRateLimitConfig
	MaintenanceInterval time.Duration
	TrustProxyHeaders   bool
	Now                 func() time.Time
}

func DefaultConfig() Config {
	return Config{
		MailboxLimits:     store.DefaultMailboxLimits(),
		PendingPairLimits: store.DefaultPendingPairLimits(),
		PairingRateLimits: PairingRateLimitConfig{
			IPBurst: 60, TokenBurst: 30, RefillInterval: time.Second, IdleTTL: 10 * time.Minute,
		},
		MaintenanceInterval: time.Minute,
		Now:                 time.Now,
	}
}

func NewWithConfig(b *store.Bolt, config Config) *Server {
	if config.Now == nil || config.MaintenanceInterval <= 0 {
		panic("invalid server config")
	}
	v, err := NewValidator()
	if err != nil {
		panic(err)
	}
	mailbox := store.NewMailboxStore(b, config.MailboxLimits)
	clientHub := NewClientHubWithMailboxLimits(config.MailboxLimits.MaxItems, config.MailboxLimits.MaxBytes)
	s := &Server{
		router:              chi.NewRouter(),
		validator:           v,
		pairStore:           store.NewPairStoreWithLimits(b, config.PendingPairLimits),
		jtiCache:            NewJTICache(60 * time.Second),
		pairHub:             NewPairHub(),
		clientHub:           clientHub,
		mailbox:             mailbox,
		handoffs:            newDurableHandoffs(config.MailboxLimits.MaxItems, config.MailboxLimits.MaxBytes),
		pairLimiter:         newPairingRateLimiter(config.PairingRateLimits),
		now:                 config.Now,
		maintenanceInterval: config.MaintenanceInterval,
		trustProxyHeaders:   config.TrustProxyHeaders,
	}
	clientHub.SetHandoffResolver(s.transferHandoffFrames)
	s.routes()
	return s
}

// New is a convenience wrapper that opens BOLT_PATH (or a default) and calls NewWithStore.
// Used by main.go; not used in tests.
func New() *Server {
	path := os.Getenv("BOLT_PATH")
	if path == "" {
		path = "/tmp/twinotify-relay.db"
	}
	b, err := store.OpenBolt(path)
	if err != nil {
		log.Fatalf("open bolt: %v", err)
	}
	return NewWithStore(b)
}

func (s *Server) Handler() http.Handler { return s.router }

func (s *Server) routes() {
	s.router.Get("/health", s.handleHealth)
	s.router.With(s.pairIPRateLimit).Post("/pair/init", s.handlePairInit)
	s.router.With(s.pairIPRateLimit).Post("/pair/hello", s.handlePairHello)
	s.router.With(s.pairIPRateLimit).Post("/pair/send_sig", s.handlePairSendSig)
	s.router.With(s.pairIPRateLimit).Post("/pair/complete", s.handlePairComplete)
	s.router.With(s.pairIPRateLimit).Get("/pair/notify", s.handlePairNotify)
	s.router.With(s.authMiddleware).Post("/pair/revoke", s.handlePairRevoke)
	s.router.With(s.authMiddleware).Get("/ws", s.handleWebSocket)
}
