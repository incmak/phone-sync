package server

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/twinotify/relay/internal/store"
)

type Server struct {
	router                      *chi.Mux
	validator                   *Validator
	pairStore                   *store.PairStore
	jtiCache                    *PersistentJTICache
	pairHub                     *PairHub
	clientHub                   *ClientHub
	webSockets                  *webSocketLifecycle
	mailbox                     *store.MailboxStore
	bolt                        *store.Bolt
	handoffs                    *durableHandoffs
	pairLimiter                 *pairingRateLimiter
	authLimiter                 *pairingRateLimiter
	capacityCheck               CapacityCheck
	buildVersion                string
	shuttingDown                atomic.Bool
	metrics                     *relayMetrics
	durableTransferMaxBytes     uint64
	now                         func() time.Time
	mutationMu                  sync.RWMutex
	maintenanceInterval         time.Duration
	trustProxyHeaders           bool
	mailboxExpiryBatch          int
	statusExpiryBatch           int
	maintenanceMu               sync.Mutex
	deliveryTransferMu          sync.Mutex
	maintenanceDone             chan struct{}
	requireMutualPairSignatures bool

	// relayHelloBeforeActivate is a deterministic test seam around the
	// drain-to-live handoff. Production constructors leave it nil.
	relayHelloBeforeActivate func(deviceID string)
	// relayBeforeDeliveryTransfer is a deterministic test barrier immediately
	// before the Bolt-view-to-hub-queue linearization point.
	relayBeforeDeliveryTransfer func(deviceID string)
	// relayDeliveryTransferBeforeAdmission proves transfer workspace admission
	// ordering in tests. Production constructors leave it nil.
	relayDeliveryTransferBeforeAdmission func()
	webSocketBeforeRegister              func(deviceID, pairID string)
	revokeAfterCommit                    func(pairID string)
	relayPutBeforeStore                  func(deviceID, pairID, msgID string)
	relayAckBeforeStore                  func(deviceID, pairID, msgID string)
	// pairMutationBeforeStore is a deterministic test barrier immediately
	// before pairing handlers acquire shutdown-linearized write admission.
	pairMutationBeforeStore func(stage pairStage)
	// relayHelloBeforeMailboxStore is a deterministic test barrier before
	// hello-triggered expiry and expiry-cursor mutations.
	relayHelloBeforeMailboxStore func(operation string)
	// authBeforeJTIStore is a deterministic test barrier immediately before
	// persistent replay admission acquires shutdown-linearized write admission.
	authBeforeJTIStore func()
	// webSocketWriteAfterLock is a deterministic test barrier for proving that
	// shutdown control frames and connection joining do not depend on a data
	// writer releasing the application-level write mutex.
	webSocketWriteAfterLock func()
	// pairNotifyReaderStarted and pairNotifyReaderBeforeExit are deterministic
	// seams for proving that process-wide WebSocket drain joins pairing readers.
	pairNotifyReaderStarted    func()
	pairNotifyReaderBeforeExit func()
	maintenanceBeforeUnit      func(unit string)
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
	MailboxLimits                 store.MailboxLimits
	PendingPairLimits             store.PendingPairLimits
	PairingRateLimits             PairingRateLimitConfig
	AuthenticationRateLimits      AuthenticationRateLimitConfig
	JTI                           JTICacheConfig
	MaintenanceInterval           time.Duration
	MailboxExpiryBatch            int
	StatusExpiryBatch             int
	TrustProxyHeaders             bool
	RequireMutualPairSignatures   bool
	CapacityCheck                 CapacityCheck
	BuildVersion                  string
	WebSocketQueueMaxBytes        uint64
	WebSocketProcessQueueMaxBytes uint64
	DurableTransferMaxBytes       uint64
	RelayMemoryLimitBytes         uint64
	MaxOpenConnections            int
	Now                           func() time.Time
}

func DefaultConfig() Config {
	return Config{
		MailboxLimits:     store.DefaultMailboxLimits(),
		PendingPairLimits: store.DefaultPendingPairLimits(),
		PairingRateLimits: PairingRateLimitConfig{
			IPBurst: 60, TokenBurst: 30, RefillInterval: time.Second, IdleTTL: 10 * time.Minute,
			MaxEntries: 10_000, CleanupBatch: 256,
		},
		AuthenticationRateLimits: AuthenticationRateLimitConfig{
			IPBurst: 2_000, DeviceBurst: 120, RefillInterval: time.Second, IdleTTL: 10 * time.Minute,
			MaxEntries: 20_000, CleanupBatch: 256,
		},
		JTI:                           JTICacheConfig{TTL: 60 * time.Second, MaxEntries: 100_000, CleanupBatch: 256},
		MaintenanceInterval:           time.Minute,
		MailboxExpiryBatch:            256,
		StatusExpiryBatch:             256,
		CapacityCheck:                 noCapacityLimit,
		BuildVersion:                  "dev",
		WebSocketQueueMaxBytes:        defaultWebSocketQueueMaxBytes,
		WebSocketProcessQueueMaxBytes: defaultWebSocketProcessQueueMaxBytes,
		DurableTransferMaxBytes:       defaultDurableTransferMaxBytes,
		RelayMemoryLimitBytes:         256 << 20,
		MaxOpenConnections:            64,
		Now:                           time.Now,
	}
}

func NewWithConfig(b *store.Bolt, config Config) *Server {
	s, err := NewWithConfigChecked(b, config)
	if err != nil {
		panic(fmt.Sprintf("initialize relay server: %v", err))
	}
	return s
}

// NewWithConfigChecked is the production initialization path. Persisted index
// corruption and configuration mismatches are returned to the caller so normal
// startup can fail closed without a constructor panic.
func NewWithConfigChecked(b *store.Bolt, config Config) (*Server, error) {
	authLimiterConfig := config.AuthenticationRateLimits.limiterConfig()
	if config.Now == nil || config.CapacityCheck == nil || config.BuildVersion == "" || config.MaintenanceInterval <= 0 || config.MailboxExpiryBatch <= 0 || config.StatusExpiryBatch <= 0 ||
		!validRateLimitConfig(config.PairingRateLimits) || !validRateLimitConfig(authLimiterConfig) {
		return nil, errors.New("invalid server config")
	}
	if err := validateWebSocketMemoryConfig(config); err != nil {
		return nil, err
	}
	v, err := NewValidator()
	if err != nil {
		return nil, err
	}
	pairStore, err := store.OpenPairStoreWithLimits(b, config.PendingPairLimits)
	if err != nil {
		return nil, err
	}
	mailbox, err := store.OpenMailboxStore(b, config.MailboxLimits)
	if err != nil {
		return nil, err
	}
	jtiCache, err := OpenPersistentJTICache(b, config.JTI)
	if err != nil {
		return nil, err
	}
	metrics := newRelayMetrics()
	clientHub := newClientHubWithMemoryLimits(config.MailboxLimits.MaxItems, config.MailboxLimits.MaxBytes, config.WebSocketQueueMaxBytes, config.WebSocketProcessQueueMaxBytes, metrics)
	s := &Server{
		router:                      chi.NewRouter(),
		validator:                   v,
		pairStore:                   pairStore,
		jtiCache:                    jtiCache,
		pairHub:                     NewPairHub(),
		clientHub:                   clientHub,
		webSockets:                  newWebSocketLifecycle(),
		mailbox:                     mailbox,
		bolt:                        b,
		handoffs:                    newDurableHandoffs(config.MailboxLimits.MaxItems, config.MailboxLimits.MaxBytes),
		pairLimiter:                 newPairingRateLimiter(config.PairingRateLimits),
		authLimiter:                 newPairingRateLimiter(authLimiterConfig),
		capacityCheck:               config.CapacityCheck,
		buildVersion:                config.BuildVersion,
		metrics:                     metrics,
		durableTransferMaxBytes:     config.DurableTransferMaxBytes,
		now:                         config.Now,
		maintenanceInterval:         config.MaintenanceInterval,
		trustProxyHeaders:           config.TrustProxyHeaders,
		mailboxExpiryBatch:          config.MailboxExpiryBatch,
		statusExpiryBatch:           config.StatusExpiryBatch,
		requireMutualPairSignatures: config.RequireMutualPairSignatures,
	}
	clientHub.SetHandoffResolver(s.transferHandoffFrames)
	s.routes()
	return s, nil
}

func validateWebSocketMemoryConfig(config Config) error {
	minimumFrameCharge := uint64(maxRelayDeliverFrameBytes + webSocketFrameMemoryOverhead)
	if config.WebSocketQueueMaxBytes < minimumFrameCharge || config.WebSocketProcessQueueMaxBytes < config.WebSocketQueueMaxBytes ||
		config.DurableTransferMaxBytes < maxRelayDeliverFrameBytes || config.RelayMemoryLimitBytes == 0 || config.MaxOpenConnections <= 0 {
		return errors.New("invalid WebSocket memory limits")
	}
	connections := uint64(config.MaxOpenConnections)
	if connections > ^uint64(0)/uint64(maxRelayControlFrameSize) || config.DurableTransferMaxBytes > ^uint64(0)/2 {
		return errors.New("WebSocket connection memory overflow")
	}
	inbound := connections * uint64(maxRelayControlFrameSize)
	workspace := 2 * config.DurableTransferMaxBytes
	if config.WebSocketProcessQueueMaxBytes > ^uint64(0)-inbound || workspace > ^uint64(0)-inbound-config.WebSocketProcessQueueMaxBytes {
		return errors.New("WebSocket controlled memory overflow")
	}
	controlled := inbound + config.WebSocketProcessQueueMaxBytes + workspace
	if controlled > config.RelayMemoryLimitBytes-(config.RelayMemoryLimitBytes/4) {
		return errors.New("WebSocket memory limits exceed safe container margin")
	}
	return nil
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
		slog.Error("relay_start_failed", "stage", "store")
		os.Exit(1)
	}
	return NewWithStore(b)
}

func (s *Server) Handler() http.Handler { return s.router }

func (s *Server) BeginShutdown() <-chan struct{} {
	s.mutationMu.Lock()
	s.shuttingDown.Store(true)
	s.mutationMu.Unlock()
	webSocketDone := s.webSockets.Drain(serviceRestartCloseCode, serviceRestartCloseReason)
	relayClientDone := s.clientHub.Drain(serviceRestartCloseCode, serviceRestartCloseReason)
	return joinWebSocketDrains(webSocketDone, relayClientDone)
}

func (s *Server) routes() {
	s.router.Get("/health/live", s.handleLive)
	s.router.Get("/health/ready", s.handleReady)
	s.router.Get("/health", s.handleReady)
	s.router.Get("/metrics", s.handleMetrics)
	s.router.With(s.observePairMutation(pairStageInit), s.rejectDuringShutdown, s.pairIPRateLimit).Post("/pair/init", s.handlePairInit)
	s.router.With(s.observePairMutation(pairStageHello), s.rejectDuringShutdown, s.pairIPRateLimit).Post("/pair/hello", s.handlePairHello)
	s.router.With(s.observePairMutation(pairStageSignature), s.rejectDuringShutdown, s.pairIPRateLimit).Post("/pair/send_sig", s.handlePairSendSig)
	s.router.With(s.observePairMutation(pairStageComplete), s.rejectDuringShutdown, s.pairIPRateLimit).Post("/pair/complete", s.handlePairComplete)
	s.router.With(s.rejectDuringShutdown, s.pairIPRateLimit).Get("/pair/notify", s.handlePairNotify)
	s.router.With(s.rejectDuringShutdown, s.authMiddleware).Post("/pair/revoke", s.handlePairRevoke)
	s.router.With(s.rejectDuringShutdown, requireWebSocketUpgrade, s.authMiddleware).Get("/ws", s.handleWebSocket)
}
