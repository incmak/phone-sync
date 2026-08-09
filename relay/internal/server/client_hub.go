package server

import (
	"sort"
	"sync"
)

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
	deviceID        string
	outbound        chan []byte
	protocol        connectionProtocol
	protocols       []int
	handshakeFrames map[string]queuedV2Frame
	handoffSuppress map[string]struct{}
	done            chan struct{}
	stopOnce        sync.Once
}

type queuedV2Frame struct {
	msgID    string
	sequence uint64
	frame    []byte
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
	return h.send(deviceID, frame, func(*wsClient) bool { return true })
}

func (h *ClientHub) SendRawV1(deviceID string, frame []byte) bool {
	return h.send(deviceID, frame, func(c *wsClient) bool {
		return c.protocol == protocolUnknown || c.protocol == protocolLegacy ||
			(c.protocol == protocolV2 && supportsProtocol(c.protocols, 1))
	})
}

func (h *ClientHub) SendLegacy(deviceID string, frame []byte) bool {
	return h.send(deviceID, frame, func(c *wsClient) bool {
		return c.protocol == protocolLegacy
	})
}

func (h *ClientHub) SendV2(deviceID, msgID string, sequence uint64, frame []byte) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !supportsProtocol(c.protocols, 2) {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
	}
	switch c.protocol {
	case protocolV2Handshake:
		if c.handshakeFrames == nil {
			c.handshakeFrames = make(map[string]queuedV2Frame)
		}
		c.handshakeFrames[msgID] = queuedV2Frame{
			msgID: msgID, sequence: sequence, frame: append([]byte(nil), frame...),
		}
		return true
	case protocolV2:
		if _, suppress := c.handoffSuppress[msgID]; suppress {
			delete(c.handoffSuppress, msgID)
			return true
		}
		select {
		case c.outbound <- append([]byte(nil), frame...):
			return true
		default:
			return false
		}
	default:
		return false
	}
}

// FlushOrActivateV2 closes the drain-to-live handoff under the hub lock. The
// caller writes returned frames while the client remains handshaking, then
// calls again until activated is true. Drained IDs suppress both already
// buffered notifications and Put calls that committed before the drain but
// reached the hub only after activation.
func (h *ClientHub) FlushOrActivateV2(c *wsClient, drainedIDs []string) (frames [][]byte, activated bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[c.deviceID]
	if !ok || current != c || c.protocol != protocolV2Handshake {
		return nil, false
	}
	if c.handoffSuppress == nil {
		c.handoffSuppress = make(map[string]struct{})
	}
	drained := make(map[string]struct{}, len(drainedIDs))
	for _, msgID := range drainedIDs {
		drained[msgID] = struct{}{}
		if _, buffered := c.handshakeFrames[msgID]; !buffered {
			c.handoffSuppress[msgID] = struct{}{}
		}
	}
	queued := make([]queuedV2Frame, 0, len(c.handshakeFrames))
	for msgID, frame := range c.handshakeFrames {
		if _, alreadyDrained := drained[msgID]; !alreadyDrained {
			queued = append(queued, frame)
		}
		delete(c.handshakeFrames, msgID)
	}
	sort.Slice(queued, func(i, j int) bool {
		if queued[i].sequence == queued[j].sequence {
			return queued[i].msgID < queued[j].msgID
		}
		return queued[i].sequence < queued[j].sequence
	})
	for _, queuedFrame := range queued {
		frames = append(frames, append([]byte(nil), queuedFrame.frame...))
	}
	if len(frames) > 0 {
		return frames, false
	}
	c.protocol = protocolV2
	return nil, true
}

func (h *ClientHub) send(deviceID string, frame []byte, accepts func(*wsClient) bool) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !accepts(c) {
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
	return h.SetProtocolAndCapabilities(c, protocol, nil)
}

func (h *ClientHub) SetProtocolAndCapabilities(c *wsClient, protocol connectionProtocol, protocols []int) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[c.deviceID]
	if !ok || current != c {
		return false
	}
	c.protocol = protocol
	if protocols != nil {
		c.protocols = append([]int(nil), protocols...)
	}
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

func (h *ClientHub) ConnectionFor(deviceID string) (connectionProtocol, []int, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok {
		return protocolUnknown, nil, false
	}
	select {
	case <-c.done:
		return protocolUnknown, nil, false
	default:
		return c.protocol, append([]int(nil), c.protocols...), true
	}
}

func supportsProtocol(protocols []int, protocol int) bool {
	for _, advertised := range protocols {
		if advertised == protocol {
			return true
		}
	}
	return false
}
