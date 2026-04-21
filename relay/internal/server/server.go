package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

type Server struct {
	router    *chi.Mux
	validator *Validator
}

func New() *Server {
	v, err := NewValidator()
	if err != nil {
		panic(err)
	}
	s := &Server{router: chi.NewRouter(), validator: v}
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler {
	return s.router
}

func (s *Server) routes() {
	s.router.Get("/health", s.handleHealth)
	s.router.Get("/ws", s.handleWebSocket)
}
