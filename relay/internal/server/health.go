package server

import (
	"encoding/json"
	"net/http"

	"go.etcd.io/bbolt"
)

type healthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

func (s *Server) handleLive(w http.ResponseWriter, _ *http.Request) {
	s.writeHealth(w, http.StatusOK, "live")
}

func (s *Server) handleReady(w http.ResponseWriter, _ *http.Request) {
	if !s.isReady() {
		s.writeHealth(w, http.StatusServiceUnavailable, "not_ready")
		return
	}
	s.writeHealth(w, http.StatusOK, "ready")
}

func (s *Server) isReady() bool {
	return !s.shuttingDown.Load() && s.bolt.View(func(*bbolt.Tx) error { return nil }) == nil && s.capacityCheck(0) == nil
}

func (s *Server) writeHealth(w http.ResponseWriter, statusCode int, status string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(healthResponse{Status: status, Version: s.buildVersion})
}
