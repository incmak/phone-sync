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
	pair, err := s.pairStore.RevokeByDevice(deviceID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			http.Error(w, "unknown device", http.StatusUnauthorized)
			return
		}
		http.Error(w, "revoke", http.StatusInternalServerError)
		return
	}
	s.clientHub.Disconnect(pair.DeviceA)
	s.clientHub.Disconnect(pair.DeviceB)
	w.WriteHeader(http.StatusNoContent)
}
