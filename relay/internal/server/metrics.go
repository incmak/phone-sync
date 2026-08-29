package server

import (
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
)

type pairStage uint8

const (
	pairStageInit pairStage = iota
	pairStageHello
	pairStageSignature
	pairStageComplete
	pairStageCount
)

type authRejectReason uint8

const (
	authRejectMissing authRejectReason = iota
	authRejectMalformed
	authRejectUnknownDevice
	authRejectSignature
	authRejectClaims
	authRejectReplay
	authRejectStore
	authRejectRateLimited
	authRejectReasonCount
)

type maintenanceOperation uint8

const (
	maintenanceMailbox maintenanceOperation = iota
	maintenanceStatuses
	maintenancePairs
	maintenanceJTI
	maintenanceOperationCount
)

const (
	pairResultAccepted = iota
	pairResultRejected
	pairResultFailure
	pairResultCount
)

var (
	relayPutReasonLabels = [...]string{
		"mailbox_full", "id_conflict", "digest_mismatch", "not_recipient", "peer_legacy", "server_capacity", "invalid_frame",
	}
	pairStageLabels   = [...]string{"init", "hello", "signature", "complete"}
	pairResultLabels  = [...]string{"accepted", "rejected", "failure"}
	authReasonLabels  = [...]string{"missing", "malformed", "unknown_device", "signature", "claims", "replay", "store", "rate_limited"}
	maintenanceLabels = [...]string{"mailbox_expiry", "status_expiry", "pair_expiry", "jti_expiry"}
)

type relayMetrics struct {
	websocketConnections       atomic.Int64
	websocketOutboundBytes     atomic.Int64
	websocketAdmissionRejected atomic.Uint64
	relayPutAccepted           atomic.Uint64
	relayPutRejected           [len(relayPutReasonLabels)]atomic.Uint64
	pairMutations              [pairStageCount][pairResultCount]atomic.Uint64
	authRejected               [authRejectReasonCount]atomic.Uint64
	maintenance                [maintenanceOperationCount][2]atomic.Uint64
	backup                     [2]atomic.Uint64
}

func newRelayMetrics() *relayMetrics { return &relayMetrics{} }

func (m *relayMetrics) connectionOpened() { m.websocketConnections.Add(1) }

func (m *relayMetrics) connectionClosed() { m.websocketConnections.Add(-1) }

func (m *relayMetrics) activeConnections() int64 { return m.websocketConnections.Load() }

func (m *relayMetrics) addWebSocketOutboundBytes(delta int64) { m.websocketOutboundBytes.Add(delta) }

func (m *relayMetrics) recordWebSocketAdmissionRejected() { m.websocketAdmissionRejected.Add(1) }

func (m *relayMetrics) recordRelayPutAccepted() { m.relayPutAccepted.Add(1) }

func (m *relayMetrics) recordRelayPutRejected(reason string) {
	m.relayPutRejected[relayPutReasonIndex(reason)].Add(1)
}

func relayPutReasonIndex(reason string) int {
	for index, candidate := range relayPutReasonLabels {
		if reason == candidate {
			return index
		}
	}
	return len(relayPutReasonLabels) - 1
}

func (m *relayMetrics) recordPairMutation(stage pairStage, status int) {
	if stage >= pairStageCount {
		return
	}
	result := pairResultFailure
	if status >= 200 && status < 300 {
		result = pairResultAccepted
	} else if status >= 400 && status < 500 {
		result = pairResultRejected
	}
	m.pairMutations[stage][result].Add(1)
}

func (m *relayMetrics) recordAuthRejected(reason authRejectReason) {
	if reason < authRejectReasonCount {
		m.authRejected[reason].Add(1)
	}
}

func (m *relayMetrics) recordMaintenance(operation maintenanceOperation, err error) {
	if operation >= maintenanceOperationCount {
		return
	}
	result := 0
	if err != nil {
		result = 1
	}
	m.maintenance[operation][result].Add(1)
}

func (m *relayMetrics) recordBackup(err error) {
	result := 0
	if err != nil {
		result = 1
	}
	m.backup[result].Add(1)
}

func (s *Server) RecordBackupResult(err error) {
	s.metrics.recordBackup(err)
}

func (s *Server) handleMetrics(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	_, _ = w.Write([]byte(s.metrics.render(s.isReady())))
}

func (m *relayMetrics) render(ready bool) string {
	var output strings.Builder
	output.WriteString("# TYPE twinotify_websocket_connections gauge\n")
	fmt.Fprintf(&output, "twinotify_websocket_connections %d\n", m.websocketConnections.Load())
	output.WriteString("# TYPE twinotify_websocket_outbound_bytes gauge\n")
	fmt.Fprintf(&output, "twinotify_websocket_outbound_bytes %d\n", m.websocketOutboundBytes.Load())
	output.WriteString("# TYPE twinotify_websocket_admission_rejected_total counter\n")
	fmt.Fprintf(&output, "twinotify_websocket_admission_rejected_total %d\n", m.websocketAdmissionRejected.Load())
	output.WriteString("# TYPE twinotify_relay_put_accepted_total counter\n")
	fmt.Fprintf(&output, "twinotify_relay_put_accepted_total %d\n", m.relayPutAccepted.Load())
	output.WriteString("# TYPE twinotify_relay_put_rejected_total counter\n")
	for index, label := range relayPutReasonLabels {
		fmt.Fprintf(&output, "twinotify_relay_put_rejected_total{reason=%q} %d\n", label, m.relayPutRejected[index].Load())
	}
	output.WriteString("# TYPE twinotify_pairing_mutation_total counter\n")
	for stage, stageLabel := range pairStageLabels {
		for result, resultLabel := range pairResultLabels {
			fmt.Fprintf(&output, "twinotify_pairing_mutation_total{stage=%q,result=%q} %d\n", stageLabel, resultLabel, m.pairMutations[stage][result].Load())
		}
	}
	output.WriteString("# TYPE twinotify_auth_rejection_total counter\n")
	for index, label := range authReasonLabels {
		fmt.Fprintf(&output, "twinotify_auth_rejection_total{reason=%q} %d\n", label, m.authRejected[index].Load())
	}
	output.WriteString("# TYPE twinotify_maintenance_total counter\n")
	for operation, label := range maintenanceLabels {
		fmt.Fprintf(&output, "twinotify_maintenance_total{operation=%q,result=\"success\"} %d\n", label, m.maintenance[operation][0].Load())
		fmt.Fprintf(&output, "twinotify_maintenance_total{operation=%q,result=\"failure\"} %d\n", label, m.maintenance[operation][1].Load())
	}
	output.WriteString("# TYPE twinotify_backup_total counter\n")
	fmt.Fprintf(&output, "twinotify_backup_total{result=\"success\"} %d\n", m.backup[0].Load())
	fmt.Fprintf(&output, "twinotify_backup_total{result=\"failure\"} %d\n", m.backup[1].Load())
	output.WriteString("# TYPE twinotify_readiness gauge\n")
	readiness := 0
	if ready {
		readiness = 1
	}
	fmt.Fprintf(&output, "twinotify_readiness %d\n", readiness)
	return output.String()
}

type statusCapture struct {
	http.ResponseWriter
	status int
}

func (w *statusCapture) WriteHeader(status int) {
	if w.status == 0 {
		w.status = status
	}
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusCapture) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(body)
}

func (s *Server) observePairMutation(stage pairStage) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			capture := &statusCapture{ResponseWriter: w}
			next.ServeHTTP(capture, r)
			status := capture.status
			if status == 0 {
				status = http.StatusOK
			}
			s.metrics.recordPairMutation(stage, status)
		})
	}
}
