package server

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"time"
)

type pairHelloReq struct {
	PairToken   string `json:"pair_token"`
	DeviceID    string `json:"device_id"`
	EncPubkey   string `json:"enc_pubkey"`
	SignPubkey  string `json:"sign_pubkey"`
	DisplayName string `json:"display_name,omitempty"`
}

// handlePairHello is called by Device B. It stores B's pubkeys and pushes
// a peer.hello frame to Device A's /pair/notify?role=A subscription.
func (s *Server) handlePairHello(w http.ResponseWriter, r *http.Request) {
	var req pairHelloReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if req.PairToken == "" || req.DeviceID == "" || req.EncPubkey == "" || req.SignPubkey == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}
	pending, err := s.pairStore.GetPending(req.PairToken)
	if err != nil {
		http.Error(w, "unknown pair_token", http.StatusNotFound)
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
	if err := s.pairStore.UpdatePendingB(req.PairToken, req.DeviceID, encPk, signPk, req.DisplayName); err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	// Push peer.hello to Device A's subscription.
	frame, err := json.Marshal(map[string]any{
		"v":            1,
		"type":         "peer.hello",
		"pair_token":   req.PairToken,
		"device_id":    req.DeviceID,
		"enc_pubkey":   req.EncPubkey,
		"sign_pubkey":  req.SignPubkey,
		"display_name": req.DisplayName,
	})
	if err != nil {
		log.Printf("peer.hello marshal: %v", err)
	} else {
		s.pairHub.Push(req.PairToken, "A", frame)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

type pairSendSigReq struct {
	PairToken       string `json:"pair_token"`
	ConfirmationSig string `json:"confirmation_sig"`
}

// handlePairSendSig is called by Device A after it sees peer.hello and constructs
// the ed25519 signature over the 5-field canonical message. The relay verifies the
// sig against A's stored pubkey, stores it, and pushes pair.sig to Device B.
func (s *Server) handlePairSendSig(w http.ResponseWriter, r *http.Request) {
	var req pairSendSigReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if req.PairToken == "" || req.ConfirmationSig == "" {
		http.Error(w, "missing fields", http.StatusBadRequest)
		return
	}
	pending, err := s.pairStore.GetPending(req.PairToken)
	if err != nil {
		http.Error(w, "unknown pair_token", http.StatusNotFound)
		return
	}
	if time.Now().Unix()-pending.CreatedAt > 300 {
		_ = s.pairStore.DeletePending(req.PairToken)
		http.Error(w, "token expired", http.StatusBadRequest)
		return
	}
	// B's pubkeys must be populated (via /pair/hello) before A can sign.
	if len(pending.BEncPubkey) == 0 || len(pending.BSignPubkey) == 0 {
		http.Error(w, "peer.hello not yet received", http.StatusConflict)
		return
	}
	sig, err := base64.StdEncoding.DecodeString(req.ConfirmationSig)
	if err != nil {
		http.Error(w, "bad base64", http.StatusBadRequest)
		return
	}
	// Reconstruct and verify the 5-field signed message:
	// pair_token || A_enc || A_sign || B_enc || B_sign
	msg := append([]byte(req.PairToken), pending.AEncPubkey...)
	msg = append(msg, pending.ASignPubkey...)
	msg = append(msg, pending.BEncPubkey...)
	msg = append(msg, pending.BSignPubkey...)
	if len(pending.ASignPubkey) != ed25519.PublicKeySize {
		http.Error(w, "stored A sign_pubkey has wrong size", http.StatusInternalServerError)
		return
	}
	if !ed25519.Verify(ed25519.PublicKey(pending.ASignPubkey), msg, sig) {
		http.Error(w, "invalid confirmation signature", http.StatusBadRequest)
		return
	}
	// Store sig + forward to Device B.
	if err := s.pairStore.UpdatePendingSig(req.PairToken, sig); err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	frame, err := json.Marshal(map[string]any{
		"v":                1,
		"type":             "pair.sig",
		"pair_token":       req.PairToken,
		"confirmation_sig": req.ConfirmationSig,
	})
	if err != nil {
		log.Printf("pair.sig marshal: %v", err)
	} else {
		s.pairHub.Push(req.PairToken, "B", frame)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}
