package server

import (
	"sync"
)

// pairKey is the composite key for role-aware subscriptions.
// Role is "A" (waiting for peer.hello) or "B" (waiting for pair.sig).
type pairKey struct {
	Token string
	Role  string
}

// PairHub routes WebSocket subscriptions by (pair_token, role).
// Unauthenticated (pre-pair); bounded by the pair token's 5-min TTL.
type PairHub struct {
	mu   sync.Mutex
	subs map[pairKey]chan []byte
}

func NewPairHub() *PairHub {
	return &PairHub{subs: make(map[pairKey]chan []byte)}
}

// Subscribe returns the channel for frames targeted to (pairToken, role).
// Only ONE subscriber per (token, role) is permitted; a repeat subscribe closes
// the old channel and installs a new one.
// Callers must pass the returned channel to Unsubscribe to guard against
// races where a re-subscription replaced this subscription before defer fires.
func (h *PairHub) Subscribe(pairToken, role string) chan []byte {
	h.mu.Lock()
	defer h.mu.Unlock()
	k := pairKey{Token: pairToken, Role: role}
	ch := make(chan []byte, 1)
	if old, ok := h.subs[k]; ok {
		close(old)
	}
	h.subs[k] = ch
	return ch
}

// Unsubscribe removes and closes ch for (pairToken, role) only if ch is still
// the current subscriber. This prevents a stale defer from closing a channel
// installed by a later re-subscription (double-close panic).
func (h *PairHub) Unsubscribe(pairToken, role string, ch chan []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	k := pairKey{Token: pairToken, Role: role}
	current, ok := h.subs[k]
	if !ok || current != ch {
		return
	}
	close(current)
	delete(h.subs, k)
}

// Push sends frame to the subscriber of (pairToken, role), non-blocking.
// Returns true if a subscriber received it, false if nobody subscribed or buffer full.
func (h *PairHub) Push(pairToken, role string, frame []byte) bool {
	h.mu.Lock()
	k := pairKey{Token: pairToken, Role: role}
	ch, ok := h.subs[k]
	h.mu.Unlock()
	if !ok {
		return false
	}
	select {
	case ch <- frame:
		return true
	default:
		return false // slow consumer — drop; device can retry pairing
	}
}
