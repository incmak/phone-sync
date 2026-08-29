package server

import "sync"

// webSocketLifecycle owns process-wide admission and shutdown accounting for
// every upgraded WebSocket route. Protocol-specific hubs remain responsible
// for routing, but no hijacked connection may outlive this lifecycle drain.
type webSocketLifecycle struct {
	mu        sync.Mutex
	active    map[*webSocketRegistration]struct{}
	draining  bool
	drainDone chan struct{}
}

type webSocketRegistration struct {
	lifecycle      *webSocketLifecycle
	drain          chan websocketDrain
	done           chan struct{}
	unregisterOnce sync.Once
}

func newWebSocketLifecycle() *webSocketLifecycle {
	return &webSocketLifecycle{active: make(map[*webSocketRegistration]struct{})}
}

// register reserves a lifecycle slot before a route upgrades its HTTP request.
// Once draining begins, no later request may become a hijacked connection.
func (l *webSocketLifecycle) register() (*webSocketRegistration, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.draining {
		return nil, false
	}
	registration := &webSocketRegistration{
		lifecycle: l,
		drain:     make(chan websocketDrain, 1),
		done:      make(chan struct{}),
	}
	l.active[registration] = struct{}{}
	return registration, true
}

// unregister acknowledges complete route cleanup. Callers must invoke it only
// after their connection and every route-owned goroutine have been joined.
func (r *webSocketRegistration) unregister() {
	r.unregisterOnce.Do(func() {
		r.lifecycle.mu.Lock()
		delete(r.lifecycle.active, r)
		r.lifecycle.mu.Unlock()
		close(r.done)
	})
}

// Drain prevents new upgrades, asks every admitted route to close with the
// supplied control frame, and completes only after all admitted handlers have
// acknowledged full cleanup. Repeated calls share the same shutdown epoch.
func (l *webSocketLifecycle) Drain(code int, reason string) <-chan struct{} {
	l.mu.Lock()
	if l.draining {
		done := l.drainDone
		l.mu.Unlock()
		return done
	}
	l.draining = true
	l.drainDone = make(chan struct{})
	registrations := make([]*webSocketRegistration, 0, len(l.active))
	signal := websocketDrain{code: code, reason: reason}
	for registration := range l.active {
		registrations = append(registrations, registration)
		registration.drain <- signal
	}
	done := l.drainDone
	l.mu.Unlock()

	if len(registrations) == 0 {
		close(done)
		return done
	}
	go func() {
		for _, registration := range registrations {
			<-registration.done
		}
		close(done)
	}()
	return done
}

func joinWebSocketDrains(drains ...<-chan struct{}) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		for _, drain := range drains {
			<-drain
		}
	}()
	return done
}
