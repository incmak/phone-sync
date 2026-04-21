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
	router    *chi.Mux
	validator *Validator
	pairStore *store.PairStore
	jtiCache  *JTICache
	pairHub   *PairHub
}

// NewWithStore builds a server backed by the given Bolt DB. Tests use this.
func NewWithStore(b *store.Bolt) *Server {
	v, err := NewValidator()
	if err != nil {
		panic(err)
	}
	s := &Server{
		router:    chi.NewRouter(),
		validator: v,
		pairStore: store.NewPairStore(b),
		jtiCache:  NewJTICache(60 * time.Second),
		pairHub:   NewPairHub(),
	}
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
	s.router.Post("/pair/init", s.handlePairInit)
	s.router.Post("/pair/complete", s.handlePairComplete)
	s.router.Get("/pair/notify", s.handlePairNotify)
	s.router.With(s.authMiddleware).Get("/ws", s.handleWebSocket)
}
