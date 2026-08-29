package server

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"net/http"

	"github.com/google/uuid"
	"github.com/twinotify/relay/internal/store"
)

type pairInitReq struct {
	PairToken   string `json:"pair_token"`
	DeviceID    string `json:"device_id"`
	EncPubkey   string `json:"enc_pubkey"`
	SignPubkey  string `json:"sign_pubkey"`
	DisplayName string `json:"display_name,omitempty"`
}

type pairCompleteReq struct {
	PairToken                string `json:"pair_token"`
	DeviceID                 string `json:"device_id"`
	EncPubkey                string `json:"enc_pubkey"`
	SignPubkey               string `json:"sign_pubkey"`
	ConfirmationSig          string `json:"confirmation_sig"`
	ResponderConfirmationSig string `json:"responder_confirmation_sig,omitempty"`
	DisplayName              string `json:"display_name,omitempty"`
}

func (s *Server) handlePairInit(w http.ResponseWriter, r *http.Request) {
	var req pairInitReq
	if !decodePairJSON(w, r, &req) {
		return
	}
	if req.PairToken == "" || req.DeviceID == "" || req.EncPubkey == "" || req.SignPubkey == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}
	if !s.allowPairToken(w, req.PairToken) {
		return
	}
	if !validDisplayName(req.DisplayName) {
		http.Error(w, "display_name too long", http.StatusBadRequest)
		return
	}
	encPk, signPk, err := decodePairPublicKeys(req.EncPubkey, req.SignPubkey)
	if err != nil {
		http.Error(w, "invalid public key", http.StatusBadRequest)
		return
	}
	p := store.PendingPair{
		PairToken:    req.PairToken,
		DeviceAID:    req.DeviceID,
		AEncPubkey:   encPk,
		ASignPubkey:  signPk,
		ADisplayName: req.DisplayName,
		CreatedAt:    s.now().Unix(),
	}
	if err := s.pairStore.PutPending(p); err != nil {
		if errors.Is(err, store.ErrPairConflict) {
			http.Error(w, "pair transition conflict", http.StatusConflict)
			return
		}
		if errors.Is(err, store.ErrPendingPairLimit) {
			http.Error(w, "too many pending pairs", http.StatusServiceUnavailable)
			return
		}
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"pending"}`))
}

func (s *Server) handlePairComplete(w http.ResponseWriter, r *http.Request) {
	var req pairCompleteReq
	if !decodePairJSON(w, r, &req) {
		return
	}
	if req.PairToken == "" || req.DeviceID == "" || req.EncPubkey == "" || req.SignPubkey == "" || req.ConfirmationSig == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}
	if s.requireMutualPairSignatures && req.ResponderConfirmationSig == "" {
		http.Error(w, "missing responder confirmation signature", http.StatusBadRequest)
		return
	}
	if !s.allowPairToken(w, req.PairToken) {
		return
	}
	if !validDisplayName(req.DisplayName) {
		http.Error(w, "display_name too long", http.StatusBadRequest)
		return
	}
	pending, err := s.pairStore.GetPending(req.PairToken)
	if err != nil {
		http.Error(w, "unknown pair_token", http.StatusBadRequest)
		return
	}
	if pairTokenExpired(pending, s.now()) {
		_ = s.pairStore.DeletePending(req.PairToken)
		http.Error(w, "token expired", http.StatusBadRequest)
		return
	}
	encPk, signPk, err := decodePairPublicKeys(req.EncPubkey, req.SignPubkey)
	if err != nil {
		http.Error(w, "invalid public key", http.StatusBadRequest)
		return
	}
	if (pending.DeviceBID != "" || len(pending.BEncPubkey) != 0 || len(pending.BSignPubkey) != 0) &&
		(pending.DeviceBID != req.DeviceID || !bytes.Equal(pending.BEncPubkey, encPk) || !bytes.Equal(pending.BSignPubkey, signPk)) {
		http.Error(w, "pair transition conflict", http.StatusConflict)
		return
	}
	msg := append([]byte(req.PairToken), pending.AEncPubkey...)
	msg = append(msg, pending.ASignPubkey...)
	msg = append(msg, encPk...)
	msg = append(msg, signPk...)
	sig, err := base64.StdEncoding.DecodeString(req.ConfirmationSig)
	if err != nil {
		http.Error(w, "bad confirmation_sig base64", http.StatusBadRequest)
		return
	}
	if len(pending.ConfirmationSig) != 0 && !bytes.Equal(pending.ConfirmationSig, sig) {
		http.Error(w, "pair transition conflict", http.StatusConflict)
		return
	}
	if len(pending.ASignPubkey) != ed25519.PublicKeySize {
		http.Error(w, "stored A sign_pubkey has wrong size", http.StatusInternalServerError)
		return
	}
	if !ed25519.Verify(ed25519.PublicKey(pending.ASignPubkey), msg, sig) {
		http.Error(w, "invalid confirmation signature", http.StatusBadRequest)
		return
	}
	var responderSig []byte
	if req.ResponderConfirmationSig != "" {
		responderSig, err = base64.StdEncoding.DecodeString(req.ResponderConfirmationSig)
		if err != nil {
			http.Error(w, "bad responder_confirmation_sig base64", http.StatusBadRequest)
			return
		}
		confirmation := *pending
		confirmation.DeviceBID = req.DeviceID
		confirmation.BEncPubkey = encPk
		confirmation.BSignPubkey = signPk
		if !ed25519.Verify(ed25519.PublicKey(signPk), responderConfirmationMessage(&confirmation, sig), responderSig) {
			http.Error(w, "invalid responder confirmation signature", http.StatusBadRequest)
			return
		}
	}
	if len(responderSig) != 0 {
		if err := s.pairStore.UpdatePendingResponderSig(req.PairToken, responderSig); err != nil {
			if errors.Is(err, store.ErrPairConflict) {
				http.Error(w, "pair transition conflict", http.StatusConflict)
				return
			}
			if errors.Is(err, store.ErrNotFound) {
				http.Error(w, "unknown pair_token", http.StatusBadRequest)
				return
			}
			http.Error(w, "store", http.StatusInternalServerError)
			return
		}
	}

	// Prefer B's display name from /pair/hello if already stored; allow override from this request.
	bDisplayName := pending.BDisplayName
	if req.DisplayName != "" {
		bDisplayName = req.DisplayName
	}

	cp := store.ConfirmedPair{
		PairID:       uuid.NewString(),
		DeviceA:      pending.DeviceAID,
		DeviceB:      req.DeviceID,
		AEncPubkey:   pending.AEncPubkey,
		ASignPubkey:  pending.ASignPubkey,
		ADisplayName: pending.ADisplayName,
		BEncPubkey:   encPk,
		BSignPubkey:  signPk,
		BDisplayName: bDisplayName,
	}
	confirmed, err := s.pairStore.ConfirmPending(req.PairToken, cp, sig)
	if err != nil {
		if errors.Is(err, store.ErrPairConflict) {
			http.Error(w, "pair transition conflict", http.StatusConflict)
			return
		}
		if errors.Is(err, store.ErrNotFound) {
			http.Error(w, "unknown pair_token", http.StatusBadRequest)
			return
		}
		http.Error(w, "confirm", http.StatusInternalServerError)
		return
	}
	pending, err = s.pairStore.GetPending(req.PairToken)
	if err != nil {
		http.Error(w, "confirm", http.StatusInternalServerError)
		return
	}
	frame, err := marshalPairComplete(pending)
	if err != nil {
		http.Error(w, "confirm", http.StatusInternalServerError)
		return
	}
	s.pairHub.Push(req.PairToken, "A", frame)
	s.pairHub.Push(req.PairToken, "B", frame)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"pair_id":"` + confirmed.PairID + `"}`))
}
