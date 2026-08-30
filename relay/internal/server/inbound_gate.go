package server

import (
	"context"
	"sync"
)

// Parsing and validating a maximum-sized JSON control frame creates several
// transient copies. Reserve a fixed conservative multiple before reading the
// payload so concurrent sockets cannot multiply those allocations without a
// process-wide bound.
const inboundFrameReservation = uint64(8 * maxRelayControlFrameSize)

type inboundFrameGate struct {
	mu      sync.Mutex
	limit   uint64
	charged uint64
	wake    chan struct{}
	metrics *relayMetrics
	// beforeWait is a deterministic saturated-wait seam. Production leaves it nil.
	beforeWait func()
}

func newInboundFrameGate(limit uint64, metrics *relayMetrics) *inboundFrameGate {
	return &inboundFrameGate{limit: limit, wake: make(chan struct{}), metrics: metrics}
}

func (g *inboundFrameGate) acquire(ctx context.Context) (func(), bool) {
	for {
		g.mu.Lock()
		if inboundFrameReservation <= g.limit && g.charged <= g.limit-inboundFrameReservation {
			g.charged += inboundFrameReservation
			if g.metrics != nil {
				g.metrics.addWebSocketInboundBytes(int64(inboundFrameReservation))
			}
			g.mu.Unlock()
			var once sync.Once
			return func() {
				once.Do(func() {
					g.mu.Lock()
					g.charged -= inboundFrameReservation
					if g.metrics != nil {
						g.metrics.addWebSocketInboundBytes(-int64(inboundFrameReservation))
					}
					close(g.wake)
					g.wake = make(chan struct{})
					g.mu.Unlock()
				})
			}, true
		}
		wake := g.wake
		if g.metrics != nil {
			g.metrics.recordWebSocketInboundWait()
		}
		g.mu.Unlock()
		if g.beforeWait != nil {
			g.beforeWait()
		}
		select {
		case <-ctx.Done():
			return nil, false
		case <-wake:
		}
	}
}

func (g *inboundFrameGate) bytesCharged() uint64 {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.charged
}
