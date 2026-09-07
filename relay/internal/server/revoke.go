package server

import (
	"encoding/json"
	"errors"
	"io"
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
	// The body selector is optional for legacy single-pair clients. Updated
	// clients bind it to the already-verified JWT/query selection.
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, pairingBodyLimit))
	var body map[string]json.RawMessage
	if err := decoder.Decode(&body); err != nil && !errors.Is(err, io.EOF) {
		writePairJSONError(w, err)
		return
	}
	if raw, exists := body["pair_id"]; exists {
		var selected string
		if json.Unmarshal(raw, &selected) != nil || selected != pairID {
			http.Error(w, "pair selector mismatch", http.StatusBadRequest)
			return
		}
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		http.Error(w, "trailing json", http.StatusBadRequest)
		return
	}

	releaseMutation, admitted := s.acquireMutationAdmission()
	if !admitted {
		writeShutdownUnavailable(w)
		return
	}
	pair, err := s.pairStore.RevokeBySession(deviceID, pairID)
	releaseMutation()
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

// Lets an upgraded single-pair client recover the pair ID that older clients
// did not persist, before enabling a second membership.
func (s *Server) handlePairSession(w http.ResponseWriter, r *http.Request) {
	deviceID, _ := DeviceIDFromContext(r.Context())
	pairID, _ := PairIDFromContext(r.Context())
	session, err := s.pairStore.SessionForPair(deviceID, pairID)
	if err != nil {
		http.Error(w, "unknown pair", http.StatusUnauthorized)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"pair_id": session.PairID, "device_id": session.DeviceID, "peer_device_id": session.PeerID})
}
