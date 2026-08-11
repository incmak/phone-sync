package server

import (
	"slices"
	"sort"
	"sync"
)

// ClientHub routes encrypted envelopes between paired devices' open WebSocket connections.
// Keyed by device_id (the JWT sub of the connected client). Each client exposes a
// non-blocking send channel; the peer-lookup + forward logic lives in ws.go.
type ClientHub struct {
	mu              sync.Mutex
	clients         map[string]*wsClient
	handoffMaxItems int
	handoffMaxBytes uint64
	resolveHandoff  func(*wsClient, []queuedV2Notification) error
}

// wsClient wraps a single authenticated WebSocket connection. Its queue is
// bounded so a slow socket cannot stall another connection. Durable v2 mailbox
// state is never removed when this latency-only queue is full; v1 remains an
// explicit online-only path.
type wsClient struct {
	deviceID            string
	outbound            chan []byte
	protocol            connectionProtocol
	protocols           []int
	pendingCapabilities []byte
	handshakeNotices    map[string]queuedV2Notification
	handshakeBytes      uint64
	handoffSuppress     map[string]struct{}
	done                chan struct{}
	stopOnce            sync.Once
}

type queuedV2Notification struct {
	msgID    string
	sequence uint64
	byteSize uint64
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
	return NewClientHubWithMailboxLimits(2000, 128<<20)
}

func NewClientHubWithMailboxLimits(maxItems int, maxBytes uint64) *ClientHub {
	return &ClientHub{
		clients:         make(map[string]*wsClient),
		handoffMaxItems: maxItems,
		handoffMaxBytes: maxBytes,
	}
}

func (h *ClientHub) SetHandoffResolver(resolve func(*wsClient, []queuedV2Notification) error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.resolveHandoff = resolve
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

func (h *ClientHub) Stop(deviceID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if c := h.clients[deviceID]; c != nil {
		c.stop()
	}
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

// SendCapabilities queues a bounded typed control snapshot for the current
// connection only. Handshaking clients receive the latest snapshot after their
// initial response and mailbox drain; a full active queue is stopped so the
// client reconnects and reads the persistent negotiation state.
func (h *ClientHub) SendCapabilities(deviceID string, selfProtocols []int, frame []byte) {
	expectedSelf := append([]int(nil), selfProtocols...)
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok {
		return
	}
	select {
	case <-c.done:
		return
	default:
	}
	switch c.protocol {
	case protocolV2Handshake:
		if !slices.Equal(c.protocols, expectedSelf) {
			return
		}
		c.pendingCapabilities = append(c.pendingCapabilities[:0], frame...)
	case protocolV2:
		select {
		case c.outbound <- append([]byte(nil), frame...):
		default:
			c.stop()
		}
	}
}

func (h *ClientHub) SendV2(deviceID, msgID string, sequence, byteSize uint64, frame []byte) bool {
	return h.TransferV2Batch(deviceID, []queuedV2Notification{{
		msgID: msgID, sequence: sequence, byteSize: byteSize,
	}}, [][]byte{frame})
}

// TransferV2Batch performs only bounded, nonblocking mutation while holding the
// hub lock. Callers may invoke it from inside a mailbox read transaction.
func (h *ClientHub) TransferV2Batch(deviceID string, notifications []queuedV2Notification, frames [][]byte) bool {
	if len(notifications) != len(frames) {
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
	}
	switch c.protocol {
	case protocolV2Handshake:
		newItems := 0
		newBytes := uint64(0)
		for _, notification := range notifications {
			if _, exists := c.handshakeNotices[notification.msgID]; exists {
				continue
			}
			newItems++
			if notification.byteSize > h.handoffMaxBytes || newBytes > h.handoffMaxBytes-notification.byteSize {
				c.stop()
				return false
			}
			newBytes += notification.byteSize
		}
		if h.handoffMaxItems <= 0 || len(c.handshakeNotices)+newItems > h.handoffMaxItems ||
			newBytes > h.handoffMaxBytes || c.handshakeBytes > h.handoffMaxBytes-newBytes {
			c.stop()
			return false
		}
		if c.handshakeNotices == nil {
			c.handshakeNotices = make(map[string]queuedV2Notification)
		}
		for _, notification := range notifications {
			if _, exists := c.handshakeNotices[notification.msgID]; exists {
				continue
			}
			c.handshakeNotices[notification.msgID] = notification
			c.handshakeBytes += notification.byteSize
		}
		return true
	case protocolV2:
		queueIndexes := make([]int, 0, len(notifications))
		for i, notification := range notifications {
			if _, suppress := c.handoffSuppress[notification.msgID]; !suppress {
				queueIndexes = append(queueIndexes, i)
			}
		}
		if cap(c.outbound)-len(c.outbound) < len(queueIndexes) {
			c.stop()
			return false
		}
		for i, notification := range notifications {
			if _, suppress := c.handoffSuppress[notification.msgID]; suppress {
				delete(c.handoffSuppress, notification.msgID)
				continue
			}
			c.outbound <- append([]byte(nil), frames[i]...)
		}
		return true
	default:
		return false
	}
}

// TransferHandshakeV2Batch queues already-authorized frames only for the exact
// still-current handshaking connection. It never waits for queue capacity.
func (h *ClientHub) TransferHandshakeV2Batch(c *wsClient, notifications []queuedV2Notification, frames [][]byte) bool {
	if len(notifications) != len(frames) {
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[c.deviceID]
	if !ok || current != c || c.protocol != protocolV2Handshake {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
	}
	if cap(c.outbound)-len(c.outbound) < len(frames) {
		c.stop()
		return false
	}
	for _, frame := range frames {
		c.outbound <- append([]byte(nil), frame...)
	}
	return true
}

// FlushOrActivateV2 closes the drain-to-live handoff under the hub lock. Each
// snapshot is revalidated and transferred into the exact handshaking client's
// bounded queue before activation. Drained IDs suppress both already buffered
// notifications and Put calls that committed before the drain but reached the
// hub only after activation.
func (h *ClientHub) FlushOrActivateV2(c *wsClient, drainedIDs []string) bool {
	for {
		h.mu.Lock()
		current, ok := h.clients[c.deviceID]
		if !ok || current != c || c.protocol != protocolV2Handshake {
			h.mu.Unlock()
			return false
		}
		select {
		case <-c.done:
			h.mu.Unlock()
			return false
		default:
		}
		if c.handoffSuppress == nil {
			c.handoffSuppress = make(map[string]struct{})
		}
		drained := make(map[string]struct{}, len(drainedIDs))
		for _, msgID := range drainedIDs {
			drained[msgID] = struct{}{}
			if _, buffered := c.handshakeNotices[msgID]; !buffered {
				c.handoffSuppress[msgID] = struct{}{}
			}
		}
		drainedIDs = nil
		queued := make([]queuedV2Notification, 0, len(c.handshakeNotices))
		for msgID, notification := range c.handshakeNotices {
			if _, alreadyDrained := drained[msgID]; !alreadyDrained {
				queued = append(queued, notification)
			}
			delete(c.handshakeNotices, msgID)
		}
		c.handshakeBytes = 0
		sort.Slice(queued, func(i, j int) bool {
			if queued[i].sequence == queued[j].sequence {
				return queued[i].msgID < queued[j].msgID
			}
			return queued[i].sequence < queued[j].sequence
		})
		if len(queued) == 0 {
			if len(c.pendingCapabilities) > 0 {
				select {
				case c.outbound <- append([]byte(nil), c.pendingCapabilities...):
					c.pendingCapabilities = nil
				default:
					c.stop()
					h.mu.Unlock()
					return false
				}
			}
			c.protocol = protocolV2
			h.mu.Unlock()
			return true
		}
		resolve := h.resolveHandoff
		h.mu.Unlock()

		if resolve == nil {
			c.stop()
			return false
		}
		if err := resolve(c, queued); err != nil {
			c.stop()
			return false
		}
		// Every snapshot item was ACKed, expired, or purged. Recheck the
		// handoff atomically before activation in case a new notice arrived
		// while Bolt was being read.
	}
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
