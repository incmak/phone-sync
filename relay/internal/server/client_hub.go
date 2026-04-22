package server

import "sync"

// ClientHub routes encrypted envelopes between paired devices' open WebSocket connections.
// Keyed by device_id (the JWT sub of the connected client). Each client exposes a
// non-blocking send channel; the peer-lookup + forward logic lives in ws.go.
type ClientHub struct {
	mu      sync.Mutex
	clients map[string]*wsClient
}

// wsClient wraps a single authenticated WebSocket connection.
// The outbound channel is bounded; a slow reader on the peer side drops messages rather
// than stalling the sender's read loop. Lost messages will be retransmitted by the sender's
// OutboundQueue on reconnect.
type wsClient struct {
	deviceID string
	outbound chan []byte
}

func NewClientHub() *ClientHub {
	return &ClientHub{clients: make(map[string]*wsClient)}
}

// Register replaces any existing client for the same device (reconnect case).
// The previous client's outbound channel is closed so its writer goroutine can exit.
func (h *ClientHub) Register(deviceID string, out chan []byte) *wsClient {
	h.mu.Lock()
	defer h.mu.Unlock()
	if prev, ok := h.clients[deviceID]; ok {
		close(prev.outbound)
	}
	c := &wsClient{deviceID: deviceID, outbound: out}
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
}

// Send forwards a frame to deviceID if currently connected. Returns true on delivery
// (queued to outbound channel), false if peer is offline or its buffer is full.
func (h *ClientHub) Send(deviceID string, frame []byte) bool {
	h.mu.Lock()
	c, ok := h.clients[deviceID]
	h.mu.Unlock()
	if !ok {
		return false
	}
	select {
	case c.outbound <- frame:
		return true
	default:
		return false
	}
}
