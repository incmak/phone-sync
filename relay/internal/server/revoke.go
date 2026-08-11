package server

import (
	"errors"
	"net/http"

	"github.com/twinotify/relay/internal/store"
)

func (s *Server) handlePairRevoke(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := DeviceIDFromContext(r.Context())
	if !ok || deviceID == "" {
		http.Error(w, "no device id", http.StatusUnauthorized)
		return
	}
	pairID, ok := PairIDFromContext(r.Context())
	if !ok || pairID == "" {
		http.Error(w, "no pair id", http.StatusUnauthorized)
		return
	}
	pair, err := s.pairStore.RevokeBySession(deviceID, pairID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			http.Error(w, "unknown device", http.StatusUnauthorized)
			return
		}
		http.Error(w, "revoke", http.StatusInternalServerError)
		return
	}
	if s.revokeAfterCommit != nil {
		s.revokeAfterCommit(pair.PairID)
	}
	s.clientHub.DisconnectPair(pair.DeviceA, pair.PairID)
	s.clientHub.DisconnectPair(pair.DeviceB, pair.PairID)
	w.WriteHeader(http.StatusNoContent)
}
