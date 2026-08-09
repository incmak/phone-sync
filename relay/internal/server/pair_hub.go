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
	subs map[pairKey]*pairSubscription
}

type pairSubscription struct {
	outbound chan []byte
	done     chan struct{}
	stopOnce sync.Once
}

func (s *pairSubscription) stop() {
	s.stopOnce.Do(func() { close(s.done) })
}

func NewPairHub() *PairHub {
	return &PairHub{subs: make(map[pairKey]*pairSubscription)}
}

// Subscribe returns the registration for frames targeted to (pairToken, role).
// Only ONE subscriber per (token, role) is permitted; a repeat subscribe cancels
// the old registration and installs a new one. Producer-visible outbound channels
// are never closed.
// Callers must pass the returned registration to Unsubscribe to guard against
// races where a re-subscription replaced this subscription before defer fires.
func (h *PairHub) Subscribe(pairToken, role string) *pairSubscription {
	h.mu.Lock()
	defer h.mu.Unlock()
	k := pairKey{Token: pairToken, Role: role}
	sub := &pairSubscription{outbound: make(chan []byte, 1), done: make(chan struct{})}
	if old, ok := h.subs[k]; ok {
		old.stop()
	}
	h.subs[k] = sub
	return sub
}

// Unsubscribe removes and cancels sub for (pairToken, role) only if sub is still
// the current subscriber. This prevents a stale defer from cancelling a later
// re-subscription.
func (h *PairHub) Unsubscribe(pairToken, role string, sub *pairSubscription) {
	h.mu.Lock()
	defer h.mu.Unlock()
	k := pairKey{Token: pairToken, Role: role}
	current, ok := h.subs[k]
	if ok && current == sub {
		delete(h.subs, k)
	}
	sub.stop()
}

// Push sends frame to the subscriber of (pairToken, role), non-blocking.
// Returns true if a subscriber received it, false if nobody subscribed or buffer full.
func (h *PairHub) Push(pairToken, role string, frame []byte) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	k := pairKey{Token: pairToken, Role: role}
	sub, ok := h.subs[k]
	if !ok {
		return false
	}
	select {
	case <-sub.done:
		return false
	case sub.outbound <- append([]byte(nil), frame...):
		return true
	default:
		return false // slow consumer — drop; device can retry pairing
	}
}
