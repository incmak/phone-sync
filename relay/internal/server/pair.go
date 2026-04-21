package server

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/phonesync/relay/internal/store"
)

type pairInitReq struct {
	PairToken  string `json:"pair_token"`
	DeviceID   string `json:"device_id"`
	EncPubkey  string `json:"enc_pubkey"`
	SignPubkey string `json:"sign_pubkey"`
}

type pairCompleteReq struct {
	PairToken       string `json:"pair_token"`
	DeviceID        string `json:"device_id"`
	EncPubkey       string `json:"enc_pubkey"`
	SignPubkey      string `json:"sign_pubkey"`
	ConfirmationSig string `json:"confirmation_sig"`
}

func (s *Server) handlePairInit(w http.ResponseWriter, r *http.Request) {
	var req pairInitReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if req.PairToken == "" || req.DeviceID == "" || req.EncPubkey == "" || req.SignPubkey == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}
	encPk, err1 := base64.StdEncoding.DecodeString(req.EncPubkey)
	signPk, err2 := base64.StdEncoding.DecodeString(req.SignPubkey)
	if err1 != nil || err2 != nil {
		http.Error(w, "bad base64", http.StatusBadRequest)
		return
	}
	p := store.PendingPair{
		PairToken:   req.PairToken,
		DeviceAID:   req.DeviceID,
		AEncPubkey:  encPk,
		ASignPubkey: signPk,
		CreatedAt:   time.Now().Unix(),
	}
	if err := s.pairStore.PutPending(p); err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"pending"}`))
}

func (s *Server) handlePairComplete(w http.ResponseWriter, r *http.Request) {
	var req pairCompleteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	pending, err := s.pairStore.GetPending(req.PairToken)
	if err != nil {
		http.Error(w, "unknown pair_token", http.StatusBadRequest)
		return
	}
	if time.Now().Unix()-pending.CreatedAt > 300 {
		_ = s.pairStore.DeletePending(req.PairToken)
		http.Error(w, "token expired", http.StatusBadRequest)
		return
	}
	encPk, err1 := base64.StdEncoding.DecodeString(req.EncPubkey)
	signPk, err2 := base64.StdEncoding.DecodeString(req.SignPubkey)
	if err1 != nil || err2 != nil {
		http.Error(w, "bad base64", http.StatusBadRequest)
		return
	}
	// TODO(phase-2-task-4): verify req.ConfirmationSig is a valid Ed25519 signature by
	// pending.ASignPubkey over pair_token || A_enc || A_sign || B_enc || B_sign.

	cp := store.ConfirmedPair{
		PairID:      uuid.NewString(),
		DeviceA:     pending.DeviceAID,
		DeviceB:     req.DeviceID,
		AEncPubkey:  pending.AEncPubkey,
		ASignPubkey: pending.ASignPubkey,
		BEncPubkey:  encPk,
		BSignPubkey: signPk,
	}
	if err := s.pairStore.Confirm(cp); err != nil {
		http.Error(w, "confirm", http.StatusInternalServerError)
		return
	}
	_ = s.pairStore.DeletePending(req.PairToken)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"pair_id":"` + cp.PairID + `"}`))
}
