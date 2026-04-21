package server

import (
	"sync"
)

// PairHub routes Device A subscriptions by pair_token.
// Each subscription is a WS listening for the pair.sig frame.
// Unauthenticated (pre-pair); bounded by the pair token's 5-min TTL.
type PairHub struct {
	mu   sync.Mutex
	subs map[string]chan []byte
}

func NewPairHub() *PairHub {
	return &PairHub{subs: make(map[string]chan []byte)}
}

// Subscribe returns the bidirectional channel for frames targeted to pairToken.
// Only ONE subscriber per token is permitted (single-writer from /pair/complete);
// a repeat subscribe closes the old channel and installs a new one.
// Callers must pass the returned channel to Unsubscribe to guard against
// races where a re-subscription replaced this subscription before defer fires.
func (h *PairHub) Subscribe(pairToken string) chan []byte {
	h.mu.Lock()
	defer h.mu.Unlock()
	ch := make(chan []byte, 1)
	if old, ok := h.subs[pairToken]; ok {
		close(old)
	}
	h.subs[pairToken] = ch
	return ch
}

// Unsubscribe removes and closes ch for pairToken only if ch is still the
// current subscriber. This prevents a stale defer from closing a channel
// installed by a later re-subscription (double-close panic).
func (h *PairHub) Unsubscribe(pairToken string, ch chan []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.subs[pairToken]
	if !ok || current != ch {
		return
	}
	close(current)
	delete(h.subs, pairToken)
}

// Push sends frame to the subscriber of pairToken, non-blocking.
// Returns true if a subscriber received it, false if nobody subscribed or buffer full.
func (h *PairHub) Push(pairToken string, frame []byte) bool {
	h.mu.Lock()
	ch, ok := h.subs[pairToken]
	h.mu.Unlock()
	if !ok {
		return false
	}
	select {
	case ch <- frame:
		return true
	default:
		return false // slow consumer — drop; A can retry pairing
	}
}
