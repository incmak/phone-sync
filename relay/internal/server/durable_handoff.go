package server

import "sync"

// durableHandoffs separates durable acceptance order from sender socket I/O.
// Put plus record is serialized per recipient, while accepted writes may run in
// parallel. Completion dispatches only the ready prefix in durable sequence
// order, and never holds a global or store lock while notifying the hub.
type durableHandoffs struct {
	mu         sync.Mutex
	recipients map[string]*recipientHandoff
	maxItems   int
	maxBytes   uint64
}

type recipientHandoff struct {
	commitMu     sync.Mutex
	dispatchMu   sync.Mutex
	mu           sync.Mutex
	pending      []*durableNotification
	pendingBytes uint64
	dispatching  bool
	refs         int
}

type durableNotification struct {
	pairID    string
	recipient string
	msgID     string
	sequence  uint64
	byteSize  uint64
	ready     bool
	queued    bool
}

func newDurableHandoffs(maxItems int, maxBytes uint64) *durableHandoffs {
	return &durableHandoffs{
		recipients: make(map[string]*recipientHandoff),
		maxItems:   maxItems,
		maxBytes:   maxBytes,
	}
}

// acquire leases the one ordering lane for recipient. The parent lock makes
// lookup/creation and refcounting atomic, so a concurrent releaser cannot
// delete a lane while another caller still holds its pointer.
func (h *durableHandoffs) acquire(recipient string) *recipientHandoff {
	h.mu.Lock()
	defer h.mu.Unlock()
	lane := h.recipients[recipient]
	if lane == nil {
		lane = &recipientHandoff{}
		h.recipients[recipient] = lane
	}
	lane.refs++
	return lane
}

// release reclaims a lane only after the complete put/record/accepted/dispatch
// lifecycle has ended. Lock order is always parent map then lane state.
func (h *durableHandoffs) release(recipient string, lane *recipientHandoff) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.recipients[recipient] != lane {
		panic("release of stale durable handoff lane")
	}
	lane.mu.Lock()
	defer lane.mu.Unlock()
	if lane.refs <= 0 {
		panic("durable handoff lane refcount underflow")
	}
	lane.refs--
	if lane.refs == 0 && len(lane.pending) == 0 && !lane.dispatching {
		delete(h.recipients, recipient)
	}
}

func (h *durableHandoffs) record(lane *recipientHandoff, pairID, recipient, msgID string, sequence, byteSize uint64) (*durableNotification, bool) {
	notification := &durableNotification{
		pairID:    pairID,
		recipient: recipient,
		msgID:     msgID,
		sequence:  sequence,
		byteSize:  byteSize,
		queued:    true,
	}
	lane.mu.Lock()
	defer lane.mu.Unlock()
	if h.maxItems <= 0 || len(lane.pending) >= h.maxItems ||
		byteSize > h.maxBytes || lane.pendingBytes > h.maxBytes-byteSize {
		for _, pending := range lane.pending {
			pending.queued = false
		}
		lane.pending = nil
		lane.pendingBytes = 0
		notification.queued = false
		return notification, true
	}
	lane.pending = append(lane.pending, notification)
	lane.pendingBytes += byteSize
	return notification, false
}

func (h *recipientHandoff) complete(notification *durableNotification, dispatch func([]durableNotification)) {
	h.dispatchMu.Lock()
	defer h.dispatchMu.Unlock()

	h.mu.Lock()
	if !notification.queued {
		h.mu.Unlock()
		return
	}
	notification.ready = true
	readyCount := 0
	for readyCount < len(h.pending) && h.pending[readyCount].ready {
		readyCount++
	}
	ready := make([]durableNotification, readyCount)
	for i := 0; i < readyCount; i++ {
		ready[i] = *h.pending[i]
		h.pendingBytes -= h.pending[i].byteSize
	}
	h.pending = h.pending[readyCount:]
	h.dispatching = len(ready) > 0
	h.mu.Unlock()

	if len(ready) > 0 {
		dispatch(ready)
		h.mu.Lock()
		h.dispatching = false
		h.mu.Unlock()
	}
}
