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
	active          map[*wsClient]struct{}
	draining        bool
	drainDone       chan struct{}
	handoffMaxItems int
	handoffMaxBytes uint64
	queueMaxBytes   uint64
	processMaxBytes uint64
	outboundBytes   uint64
	capacityChanged chan struct{}
	metrics         *relayMetrics
	resolveHandoff  func(*wsClient, []queuedV2Notification) error
	// capacityWaitBeforeBlock is a deterministic test seam. Production leaves it nil.
	capacityWaitBeforeBlock func(*wsClient)
}

// wsClient wraps a single authenticated WebSocket connection. Its queue is
// bounded so a slow socket cannot stall another connection. Durable v2 mailbox
// state is never removed when this latency-only queue is full; v1 remains an
// explicit online-only path.
type wsClient struct {
	deviceID            string
	pairID              string
	outbound            chan []byte
	protocol            connectionProtocol
	protocols           []int
	pendingCapabilities []byte
	handshakeNotices    map[string]queuedV2Notification
	handshakeBytes      uint64
	handoffSuppress     map[string]struct{}
	done                chan struct{}
	drain               chan websocketDrain
	stopOnce            sync.Once
	closed              chan struct{}
	closedOnce          sync.Once
	admissionDone       chan struct{}
	admissionOnce       sync.Once
	outboundBytes       uint64
	deliveryCursor      uint64
}

type websocketDrain struct {
	code   int
	reason string
}

const (
	serviceRestartCloseCode   = 1012
	serviceRestartCloseReason = "service restart"
)

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
	c.cancelAdmission()
}

func (c *wsClient) cancelAdmission() { c.admissionOnce.Do(func() { close(c.admissionDone) }) }

func (c *wsClient) markClosed() {
	c.closedOnce.Do(func() { close(c.closed) })
}

func NewClientHub() *ClientHub {
	return NewClientHubWithMailboxLimits(2000, 128<<20)
}

func NewClientHubWithMailboxLimits(maxItems int, maxBytes uint64) *ClientHub {
	return newClientHubWithMemoryLimits(maxItems, maxBytes, defaultWebSocketQueueMaxBytes, defaultWebSocketProcessQueueMaxBytes, nil)
}

func newClientHubWithMemoryLimits(maxItems int, maxBytes, queueMaxBytes, processMaxBytes uint64, metrics *relayMetrics) *ClientHub {
	return &ClientHub{
		clients:         make(map[string]*wsClient),
		active:          make(map[*wsClient]struct{}),
		handoffMaxItems: maxItems,
		handoffMaxBytes: maxBytes,
		queueMaxBytes:   queueMaxBytes,
		processMaxBytes: processMaxBytes,
		metrics:         metrics,
		capacityChanged: make(chan struct{}),
	}
}

func (h *ClientHub) SetHandoffResolver(resolve func(*wsClient, []queuedV2Notification) error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.resolveHandoff = resolve
}

// Register replaces an existing unbound client for the same device. It remains
// available for isolated hub users; authenticated WebSockets use pair-bound
// registration below.
func (h *ClientHub) Register(deviceID string, out chan []byte) *wsClient {
	return h.RegisterPair(deviceID, "", out)
}

// RegisterPair replaces only an unbound or same-generation registration. A
// different non-empty generation is returned already stopped and is never
// installed, so a delayed revoked request cannot evict a rebound connection.
// The current generation remains authoritative until its exact disconnect or
// unregister; a legitimate later generation retries after that lifecycle ends.
func (h *ClientHub) RegisterPair(deviceID, pairID string, out chan []byte) *wsClient {
	client, _ := h.registerPair(deviceID, pairID, out)
	return client
}

func (h *ClientHub) registerPair(deviceID, pairID string, out chan []byte) (*wsClient, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c := &wsClient{
		deviceID: deviceID, pairID: pairID, outbound: out,
		done: make(chan struct{}), drain: make(chan websocketDrain, 1), closed: make(chan struct{}), admissionDone: make(chan struct{}),
	}
	if h.draining {
		c.stop()
		return c, false
	}
	if prev, ok := h.clients[deviceID]; ok {
		if prev.pairID != "" && pairID != "" && prev.pairID != pairID {
			c.stop()
			return c, false
		}
		prev.stop()
	}
	h.clients[deviceID] = c
	h.active[c] = struct{}{}
	return c, true
}

// Drain prevents new registrations and asks every current handler to send a
// serialized WebSocket close control frame before closing its socket. The
// returned channel closes only after every handler active at the drain
// linearization point has joined its connection workers and unregistered.
func (h *ClientHub) Drain(code int, reason string) <-chan struct{} {
	h.mu.Lock()
	if h.draining {
		done := h.drainDone
		h.mu.Unlock()
		return done
	}
	h.draining = true
	h.drainDone = make(chan struct{})
	clients := make([]*wsClient, 0, len(h.active))
	signal := websocketDrain{code: code, reason: reason}
	for client := range h.active {
		clients = append(clients, client)
		client.cancelAdmission()
		select {
		case client.drain <- signal:
		default:
			client.stop()
		}
	}
	done := h.drainDone
	h.mu.Unlock()
	if len(clients) == 0 {
		close(done)
		return done
	}
	go func() {
		for _, client := range clients {
			<-client.closed
		}
		close(done)
	}()
	return done
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
	delete(h.active, c)
	c.stop()
	if len(c.pendingCapabilities) > 0 {
		h.releaseOutboundLocked(c, outboundFrameCharge(c.pendingCapabilities))
		c.pendingCapabilities = nil
	}
	for {
		select {
		case frame := <-c.outbound:
			h.releaseOutboundLocked(c, outboundFrameCharge(frame))
		default:
			c.markClosed()
			return
		}
	}
}

// releaseOutbound keeps bytes owned by the process until the socket writer has
// finished using the frame. Buffered frames are reclaimed by Unregister.
func (h *ClientHub) releaseOutbound(c *wsClient, bytes uint64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.releaseOutboundLocked(c, bytes)
}

func (h *ClientHub) releaseOutboundLocked(c *wsClient, bytes uint64) {
	if bytes > c.outboundBytes {
		bytes = c.outboundBytes
	}
	if bytes > h.outboundBytes {
		bytes = h.outboundBytes
	}
	c.outboundBytes -= bytes
	h.outboundBytes -= bytes
	h.signalCapacityChangedLocked()
	if h.metrics != nil {
		h.metrics.addWebSocketOutboundBytes(-int64(bytes))
	}
}

func (h *ClientHub) signalCapacityChangedLocked() {
	close(h.capacityChanged)
	h.capacityChanged = make(chan struct{})
}

func (h *ClientHub) waitForHandshakeCapacity(c *wsClient, items int, bytes uint64) bool {
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
		case <-c.admissionDone:
			h.mu.Unlock()
			return false
		default:
		}
		if h.capacityAvailableLocked(c, items, bytes) {
			h.mu.Unlock()
			return true
		}
		wake := h.capacityChanged
		h.mu.Unlock()
		if h.capacityWaitBeforeBlock != nil {
			h.capacityWaitBeforeBlock(c)
		}
		select {
		case <-c.done:
			return false
		case <-c.admissionDone:
			return false
		case <-wake:
		}
	}
}

func (h *ClientHub) activityChannel() <-chan struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.capacityChanged
}

func (h *ClientHub) waitForHandshakeActivity(c *wsClient, wake <-chan struct{}) bool {
	select {
	case <-c.done:
		return false
	case <-c.admissionDone:
		return false
	case <-wake:
		return true
	}
}

func (h *ClientHub) advanceHandshakeCursor(c *wsClient, sequence uint64) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[c.deviceID]
	if !ok || current != c || c.protocol != protocolV2Handshake {
		return false
	}
	if sequence > c.deliveryCursor {
		c.deliveryCursor = sequence
	}
	return true
}

// tryActivateCaughtUpV2 is called only while the caller holds the recipient
// commit lane and the process delivery-transfer mutex. This makes the final
// empty durable query and activation one linearization point.
func (h *ClientHub) tryActivateCaughtUpV2(c *wsClient) (activated, waitForSlot bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	current, ok := h.clients[c.deviceID]
	if !ok || current != c || c.protocol != protocolV2Handshake {
		return false, false
	}
	select {
	case <-c.done:
		return false, false
	default:
	}
	for msgID := range c.handshakeNotices {
		delete(c.handshakeNotices, msgID)
	}
	c.handshakeBytes = 0
	if len(c.pendingCapabilities) > 0 && !h.transferPendingCapabilitiesLocked(c) {
		return false, true
	}
	c.protocol = protocolV2
	return true, false
}

func (h *ClientHub) outboundBytesCharged() uint64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.outboundBytes
}

func (h *ClientHub) Stop(deviceID string) {
	h.stopPair(deviceID, "", false)
}

func (h *ClientHub) StopPair(deviceID, pairID string) {
	h.stopPair(deviceID, pairID, false)
}

func (h *ClientHub) stopPair(deviceID, pairID string, unregister bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if c := h.clients[deviceID]; c != nil && clientMatchesPair(c, pairID) {
		if unregister {
			delete(h.clients, deviceID)
		}
		c.stop()
	}
}

// Disconnect cancels and unregisters the current connection for deviceID.
// The private outbound queue stays open because producers may still hold it.
func (h *ClientHub) Disconnect(deviceID string) {
	h.stopPair(deviceID, "", true)
}

func (h *ClientHub) DisconnectPair(deviceID, pairID string) {
	h.stopPair(deviceID, pairID, true)
}

// Send forwards a frame to deviceID if currently connected. Returns true on delivery
// (queued to outbound channel), false if peer is offline or its buffer is full.
func (h *ClientHub) Send(deviceID string, frame []byte) bool {
	return h.send(deviceID, "", frame, func(*wsClient) bool { return true })
}

func (h *ClientHub) SendRawV1(deviceID string, frame []byte) bool {
	return h.SendRawV1ForPair(deviceID, "", frame)
}

func (h *ClientHub) SendRawV1ForPair(deviceID, pairID string, frame []byte) bool {
	return h.send(deviceID, pairID, frame, func(c *wsClient) bool {
		return c.protocol == protocolUnknown || c.protocol == protocolLegacy ||
			(c.protocol == protocolV2 && supportsProtocol(c.protocols, 1))
	})
}

func (h *ClientHub) SendLegacy(deviceID string, frame []byte) bool {
	return h.SendLegacyForPair(deviceID, "", frame)
}

func (h *ClientHub) SendLegacyForPair(deviceID, pairID string, frame []byte) bool {
	return h.send(deviceID, pairID, frame, func(c *wsClient) bool {
		return c.protocol == protocolLegacy
	})
}

// SendCapabilities queues a bounded typed control snapshot for the current
// connection only. Handshaking clients receive the latest snapshot after their
// initial response and mailbox drain; a full active queue is stopped so the
// client reconnects and reads the persistent negotiation state.
func (h *ClientHub) SendCapabilities(deviceID string, selfProtocols []int, frame []byte) {
	h.SendCapabilitiesForPair(deviceID, "", selfProtocols, frame)
}

func (h *ClientHub) SendCapabilitiesForPair(deviceID, pairID string, selfProtocols []int, frame []byte) {
	expectedSelf := append([]int(nil), selfProtocols...)
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !clientMatchesPair(c, pairID) {
		return
	}
	select {
	case <-c.done:
		return
	default:
	}
	if !slices.Equal(c.protocols, expectedSelf) {
		return
	}
	switch c.protocol {
	case protocolV2Handshake:
		if !h.replacePendingCapabilitiesLocked(c, frame) {
			c.stop()
		}
	case protocolV2:
		if !h.enqueueLocked(c, frame) {
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
	return h.TransferV2BatchForPair(deviceID, "", notifications, frames)
}

func (h *ClientHub) TransferV2BatchForPair(deviceID, pairID string, notifications []queuedV2Notification, frames [][]byte) bool {
	if len(notifications) != len(frames) {
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !clientMatchesPair(c, pairID) {
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
		h.signalCapacityChangedLocked()
		return true
	case protocolV2:
		queueIndexes := make([]int, 0, len(notifications))
		for i, notification := range notifications {
			if notification.sequence > c.deliveryCursor {
				if _, suppress := c.handoffSuppress[notification.msgID]; suppress {
					continue
				}
				queueIndexes = append(queueIndexes, i)
			}
		}
		queueBytes, ok := framesBytes(frames, queueIndexes)
		if !ok || !h.canEnqueueLocked(c, len(queueIndexes), queueBytes) {
			c.stop()
			return false
		}
		for i, notification := range notifications {
			if notification.sequence <= c.deliveryCursor {
				continue
			}
			if _, suppress := c.handoffSuppress[notification.msgID]; suppress {
				delete(c.handoffSuppress, notification.msgID)
				continue
			}
			h.enqueueAdmittedLocked(c, frames[i])
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
	queueBytes, ok := framesBytes(frames, nil)
	if !ok || !h.canEnqueueLocked(c, len(frames), queueBytes) {
		return false
	}
	for _, frame := range frames {
		h.enqueueAdmittedLocked(c, frame)
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
			if notification.sequence > c.deliveryCursor {
				if _, alreadyDrained := drained[msgID]; !alreadyDrained {
					queued = append(queued, notification)
				}
			} else {
				c.handoffSuppress[msgID] = struct{}{}
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
				if !h.transferPendingCapabilitiesLocked(c) {
					h.mu.Unlock()
					if !h.waitForHandshakeCapacity(c, 1, 0) {
						return false
					}
					continue
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

func (h *ClientHub) send(deviceID, pairID string, frame []byte, accepts func(*wsClient) bool) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !clientMatchesPair(c, pairID) || !accepts(c) {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
		return h.enqueueLocked(c, frame)
	}
}

func (h *ClientHub) enqueueLocked(c *wsClient, frame []byte) bool {
	bytes := outboundFrameCharge(frame)
	if !h.canEnqueueLocked(c, 1, bytes) {
		return false
	}
	h.enqueueAdmittedLocked(c, frame)
	return true
}

func (h *ClientHub) canEnqueueLocked(c *wsClient, items int, bytes uint64) bool {
	if !h.capacityAvailableLocked(c, items, bytes) {
		if h.metrics != nil {
			h.metrics.recordWebSocketAdmissionRejected()
		}
		return false
	}
	return true
}

func (h *ClientHub) capacityAvailableLocked(c *wsClient, items int, bytes uint64) bool {
	if items < 0 || cap(c.outbound)-len(c.outbound) < items || bytes > h.queueMaxBytes ||
		c.outboundBytes > h.queueMaxBytes-bytes || bytes > h.processMaxBytes ||
		h.outboundBytes > h.processMaxBytes-bytes {
		return false
	}
	return true
}

func (h *ClientHub) enqueueAdmittedLocked(c *wsClient, frame []byte) {
	copyFrame := append([]byte(nil), frame...)
	bytes := outboundFrameCharge(copyFrame)
	c.outboundBytes += bytes
	h.outboundBytes += bytes
	if h.metrics != nil {
		h.metrics.addWebSocketOutboundBytes(int64(bytes))
	}
	c.outbound <- copyFrame
}

func framesBytes(frames [][]byte, indexes []int) (uint64, bool) {
	var total uint64
	if indexes == nil {
		indexes = make([]int, len(frames))
		for i := range frames {
			indexes[i] = i
		}
	}
	for _, index := range indexes {
		bytes := outboundFrameCharge(frames[index])
		if total > ^uint64(0)-bytes {
			return 0, false
		}
		total += bytes
	}
	return total, true
}

func (h *ClientHub) replacePendingCapabilitiesLocked(c *wsClient, frame []byte) bool {
	oldCharge := uint64(0)
	if len(c.pendingCapabilities) > 0 {
		oldCharge = outboundFrameCharge(c.pendingCapabilities)
	}
	newCharge := outboundFrameCharge(frame)
	if newCharge > oldCharge {
		additional := newCharge - oldCharge
		if additional > h.queueMaxBytes || c.outboundBytes > h.queueMaxBytes-additional ||
			additional > h.processMaxBytes || h.outboundBytes > h.processMaxBytes-additional {
			if h.metrics != nil {
				h.metrics.recordWebSocketAdmissionRejected()
			}
			return false
		}
		c.outboundBytes += additional
		h.outboundBytes += additional
		if h.metrics != nil {
			h.metrics.addWebSocketOutboundBytes(int64(additional))
		}
	} else {
		h.releaseOutboundLocked(c, oldCharge-newCharge)
	}
	c.pendingCapabilities = append([]byte(nil), frame...)
	return true
}

// transferPendingCapabilitiesLocked moves the already charged owned frame to
// the socket queue without copying or charging it a second time.
func (h *ClientHub) transferPendingCapabilitiesLocked(c *wsClient) bool {
	if cap(c.outbound)-len(c.outbound) < 1 {
		return false
	}
	c.outbound <- c.pendingCapabilities
	c.pendingCapabilities = nil
	return true
}

func outboundFrameCharge(frame []byte) uint64 {
	return uint64(len(frame)) + webSocketFrameMemoryOverhead
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
	return h.ConnectionForPair(deviceID, "")
}

func (h *ClientHub) ConnectionForPair(deviceID, pairID string) (connectionProtocol, []int, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := h.clients[deviceID]
	if !ok || !clientMatchesPair(c, pairID) {
		return protocolUnknown, nil, false
	}
	select {
	case <-c.done:
		return protocolUnknown, nil, false
	default:
		return c.protocol, append([]int(nil), c.protocols...), true
	}
}

func clientMatchesPair(c *wsClient, expectedPairID string) bool {
	return expectedPairID == "" || c.pairID == expectedPairID
}

func supportsProtocol(protocols []int, protocol int) bool {
	for _, advertised := range protocols {
		if advertised == protocol {
			return true
		}
	}
	return false
}
