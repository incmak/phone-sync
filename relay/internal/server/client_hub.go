package server

import "sync"

// ClientHub routes encrypted envelopes between paired devices' open WebSocket connections.
// Keyed by device_id (the JWT sub of the connected client). Each client exposes a
// non-blocking send channel; the peer-lookup + forward logic lives in ws.go.
type ClientHub struct {
	mu      sync.Mutex
	clients map[string]*wsClient
}

// wsClient wraps a single authenticated WebSocket connection. Its queue is
// bounded so a slow socket cannot stall another connection. Durable v2 mailbox
// state is never removed when this latency-only queue is full; v1 remains an
// explicit online-only path.
type wsClient struct {
	deviceID string
	outbound chan []byte
	protocol connectionProtocol
	done     chan struct{}
	stopOnce sync.Once
}

type connectionProtocol uint8

const (
	protocolUnknown connectionProtocol = iota
	protocolLegacy
	protocolV2Handshake
	protocolV2
)

func (c *wsClient) stop() {
	c.stopOnce.Do(func() { close(c.done) })
}

func NewClientHub() *ClientHub {
	return &ClientHub{clients: make(map[string]*wsClient)}
}

// Register replaces any existing client for the same device (reconnect case).
// The previous client is cancelled so its writer goroutine can exit. The outbound
// channel remains open because concurrent producers may still hold its reference.
func (h *ClientHub) Register(deviceID string, out chan []byte) *wsClient {
	h.mu.Lock()
	defer h.mu.Unlock()
	if prev, ok := h.clients[deviceID]; ok {
		prev.stop()
	}
	c := &wsClient{deviceID: deviceID, outbound: out, done: make(chan struct{})}
	h.clients[deviceID] = c
	return c
}

// Unregister removes client only if it's still the registered entry for this device.
// Guards against a race where a reconnect replaced the registration before the old
// client's defer fires.
func (h *ClientHub) Unregister(c *wsClient) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cur, ok := h.clients[c.deviceID]; ok && cur == c {
		delete(h.clients, c.deviceID)
	}
	c.stop()
}

// Send forwards a frame to deviceID if currently connected. Returns true on delivery
// (queued to outbound channel), false if peer is offline or its buffer is full.
func (h *ClientHub) Send(deviceID string, frame []byte) bool {
	return h.send(deviceID, frame, func(connectionProtocol) bool { return true })
}

func (h *ClientHub) SendRawV1(deviceID string, frame []byte) bool {
	return h.send(deviceID, frame, func(protocol connectionProtocol) bool {
		return protocol == protocolUnknown || protocol == protocolLegacy
	})
}

func (h *ClientHub) SendLegacy(deviceID string, frame []byte) bool {
	return h.send(deviceID, frame, func(protocol connectionProtocol) bool {
		return protocol == protocolLegacy
	})
}

func (h *ClientHub) SendV2(deviceID string, frame []byte) bool {
	return h.send(deviceID, frame, func(protocol connectionProtocol) bool {
		return protocol == protocolV2
	})
}

func (h *ClientHub) send(deviceID string, frame []byte, accepts func(connectionProtocol) bool) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !accepts(c.protocol) {
		return false
	}
	select {
	case <-c.done:
		return false
	case c.outbound <- append([]byte(nil), frame...):
		return true
	default:
		return false
	}
}

func (h *ClientHub) SetProtocol(c *wsClient, protocol connectionProtocol) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[c.deviceID]
	if !ok || current != c {
		return false
	}
	c.protocol = protocol
	return true
}

func (h *ClientHub) ProtocolFor(deviceID string) (connectionProtocol, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok {
		return protocolUnknown, false
	}
	select {
	case <-c.done:
		return protocolUnknown, false
	default:
		return c.protocol, true
	}
}
