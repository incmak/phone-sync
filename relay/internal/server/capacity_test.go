package server

import (
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/relay/internal/store"
	"golang.org/x/sys/unix"
)

func TestServerCapacityControlsHealthAndPairMutation(t *testing.T) {
	config := DefaultConfig()
	config.BuildVersion = "test-build"
	config.CapacityCheck = func(uint64) error { return ErrServerCapacity }
	server := newTestServerWithConfig(t, config)

	assertHealthStatus(t, server, "/health/live", http.StatusOK, `"status":"live"`)
	assertHealthStatus(t, server, "/health/ready", http.StatusServiceUnavailable, `"status":"not_ready"`)
	assertHealthStatus(t, server, "/health", http.StatusServiceUnavailable, `"version":"test-build"`)

	response := pairRequest(t, server, http.MethodPost, "/pair/init", validPairInitBody("capacity-pair"), "192.0.2.1:1000")
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("pair mutation status = %d, want 503; body=%q", response.Code, response.Body.String())
	}
	if response.Header().Get("Retry-After") == "" {
		t.Fatal("capacity rejection omitted Retry-After")
	}
	if _, err := server.pairStore.GetPending("capacity-pair"); err == nil {
		t.Fatal("capacity-rejected pairing request persisted state")
	}
}

func TestHealthBecomesNotReadyWhenShutdownBeginsOrBoltIsUnreadable(t *testing.T) {
	server, _ := newMailboxTestServerWithBolt(t)
	assertHealthStatus(t, server, "/health/ready", http.StatusOK, `"status":"ready"`)
	server.BeginShutdown()
	assertHealthStatus(t, server, "/health/ready", http.StatusServiceUnavailable, `"status":"not_ready"`)
	assertHealthStatus(t, server, "/health/live", http.StatusOK, `"status":"live"`)

	secondServer, secondBolt := newMailboxTestServerWithBolt(t)
	if err := secondBolt.Close(); err != nil {
		t.Fatal(err)
	}
	assertHealthStatus(t, secondServer, "/health/ready", http.StatusServiceUnavailable, `"status":"not_ready"`)
	assertHealthStatus(t, secondServer, "/health/live", http.StatusOK, `"status":"live"`)
}

func TestShutdownRejectsNewPairMutationWithoutPersistence(t *testing.T) {
	server := newTestServer(t)
	server.BeginShutdown()

	response := pairRequest(t, server, http.MethodPost, "/pair/init", validPairInitBody("shutdown-pair"), "192.0.2.1:1000")
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("pair mutation status = %d, want 503; body=%q", response.Code, response.Body.String())
	}
	if response.Header().Get("Retry-After") == "" {
		t.Fatal("shutdown rejection omitted Retry-After")
	}
	if _, err := server.pairStore.GetPending("shutdown-pair"); err == nil {
		t.Fatal("shutdown-rejected pairing request persisted state")
	}
}

func TestServerCapacityRejectsRelayPutWithoutPersistence(t *testing.T) {
	config := DefaultConfig()
	config.CapacityCheck = func(uint64) error { return ErrServerCapacity }
	server := newTestServerWithConfig(t, config)
	pair := registerMailboxTestPair(t, server)
	msgID := "99999999-9999-4999-8999-999999999999"
	var rejection string
	server.handleRelayPutForPair(pair.deviceA, pair.pairID, RelayPut{
		V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID),
	}, func(any) error {
		t.Fatal("capacity-rejected put was accepted")
		return nil
	}, func(_ string, reason string) error {
		rejection = reason
		return nil
	})
	if rejection != "server_capacity" {
		t.Fatalf("rejection = %q, want server_capacity", rejection)
	}
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("capacity-rejected put persisted %d mailbox records", len(pending))
	}
}

func TestRelayPutCapacityAdmissionReservesEncodedMailboxBytes(t *testing.T) {
	config := DefaultConfig()
	var reservedBytes uint64
	config.CapacityCheck = func(requiredBytes uint64) error {
		if requiredBytes == 0 {
			return nil
		}
		reservedBytes = requiredBytes
		return ErrServerCapacity
	}
	server := newTestServerWithConfig(t, config)
	pair := registerMailboxTestPair(t, server)
	msgID := "67676767-6767-4767-8767-676767676767"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	var rejection string
	server.handleRelayPutForPair(pair.deviceA, pair.pairID, RelayPut{
		V: 2, Type: "relay.put", Envelope: envelope,
	}, func(any) error {
		t.Fatal("capacity-rejected put was accepted")
		return nil
	}, func(_ string, reason string) error {
		rejection = reason
		return nil
	})
	if rejection != "server_capacity" {
		t.Fatalf("rejection = %q, want server_capacity", rejection)
	}
	if reservedBytes <= uint64(len(envelope)) {
		t.Fatalf("capacity reservation = %d, want more than raw envelope bytes %d", reservedBytes, len(envelope))
	}
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("capacity-rejected put persisted %d mailbox records", len(pending))
	}
}

func TestShutdownRejectsRelayPutWithoutPersistence(t *testing.T) {
	server := newTestServer(t)
	pair := registerMailboxTestPair(t, server)
	server.BeginShutdown()
	msgID := "88888888-8888-4888-8888-888888888888"
	var rejection string
	server.handleRelayPutForPair(pair.deviceA, pair.pairID, RelayPut{
		V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID),
	}, func(any) error {
		t.Fatal("shutdown-rejected put was accepted")
		return nil
	}, func(_ string, reason string) error {
		rejection = reason
		return nil
	})
	if rejection != "server_capacity" {
		t.Fatalf("rejection = %q, want server_capacity", rejection)
	}
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("shutdown-rejected put persisted %d mailbox records", len(pending))
	}
}

func TestShutdownLinearizesBeforeInFlightRelayPutCommit(t *testing.T) {
	server := newTestServer(t)
	pair := registerMailboxTestPair(t, server)
	beforeStore := make(chan struct{})
	releaseStore := make(chan struct{})
	server.relayPutBeforeStore = func(_, _, _ string) {
		close(beforeStore)
		<-releaseStore
	}
	msgID := "77777777-7777-4777-8777-777777777777"
	rejection := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		server.handleRelayPutForPair(pair.deviceA, pair.pairID, RelayPut{
			V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID),
		}, func(any) error {
			t.Error("in-flight shutdown put was accepted")
			return nil
		}, func(_ string, reason string) error {
			rejection <- reason
			return nil
		})
	}()
	<-beforeStore
	server.BeginShutdown()
	close(releaseStore)
	<-done
	select {
	case reason := <-rejection:
		if reason != "server_capacity" {
			t.Fatalf("rejection = %q, want server_capacity", reason)
		}
	default:
		t.Fatal("in-flight shutdown put was not rejected")
	}
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil || len(pending) != 0 {
		t.Fatalf("in-flight shutdown put persisted records: %#v, %v", pending, err)
	}
}

func TestShutdownLinearizesBeforeInFlightRelayAckCommit(t *testing.T) {
	server := newTestServer(t)
	pair := registerMailboxTestPair(t, server)
	msgID := "66666666-6666-4666-8666-666666666666"
	putMailboxRecord(t, server, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), time.Now())
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil || len(pending) != 1 {
		t.Fatalf("prepare ack mailbox = %#v, %v", pending, err)
	}
	beforeStore := make(chan struct{})
	releaseStore := make(chan struct{})
	server.relayAckBeforeStore = func(_, _, _ string) {
		close(beforeStore)
		<-releaseStore
	}
	ackDone := make(chan error, 1)
	go func() {
		ackDone <- server.handleRelayAckForPair(pair.deviceB, pair.pairID, RelayAck{
			V: 2, Type: "relay.ack", MsgID: msgID, EnvelopeSHA256: pending[0].EnvelopeSHA256,
		})
	}()
	<-beforeStore
	server.BeginShutdown()
	close(releaseStore)
	if err := <-ackDone; !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("ack error = %v, want ErrServerCapacity", err)
	}
	pending, err = server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil || len(pending) != 1 || pending[0].MsgID != msgID {
		t.Fatalf("in-flight shutdown ack mutated mailbox: %#v, %v", pending, err)
	}
}

func TestShutdownLinearizesBeforeRelayHelloExpiryMutation(t *testing.T) {
	server := newTestServer(t)
	pair := registerMailboxTestPair(t, server)
	msgID := "55555555-5555-4555-8555-555555555555"
	putMailboxRecord(t, server, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), time.Now().Add(-25*time.Hour))
	beforeExpiry := make(chan struct{})
	releaseExpiry := make(chan struct{})
	server.relayHelloBeforeMailboxStore = func(operation string) {
		if operation != "expiry" {
			t.Errorf("mailbox operation = %q, want expiry", operation)
		}
		close(beforeExpiry)
		<-releaseExpiry
	}
	helloDone := make(chan error, 1)
	go func() {
		helloDone <- server.handleRelayHelloForPair(pair.deviceA, pair.pairID, nil, RelayHello{
			V: 2, Type: "relay.hello", Protocols: []int{2, 1}, AppVersion: "shutdown-test",
		}, func(any) error { return nil }, func([]string) {})
	}()
	<-beforeExpiry
	server.BeginShutdown()
	close(releaseExpiry)
	if err := <-helloDone; !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("relay hello error = %v, want ErrServerCapacity", err)
	}
	pending, err := server.mailbox.PendingForPair(pair.pairID, pair.deviceB, 10)
	if err != nil || len(pending) != 1 || pending[0].MsgID != msgID {
		t.Fatalf("relay hello expiry mutated after shutdown: %#v, %v", pending, err)
	}
}

func TestShutdownLinearizesBeforeRelayHelloExpiryCursorMutation(t *testing.T) {
	server := newTestServer(t)
	pair := registerMailboxTestPair(t, server)
	now := time.Now()
	for index := 0; index < mailboxBatchSize+1; index++ {
		msgID := fmt.Sprintf("%08d-0000-4000-8000-%012d", index+1, index+1)
		putMailboxRecord(t, server, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), now.Add(-25*time.Hour))
	}
	if expired, err := server.mailbox.ExpireForPair(pair.pairID, pair.deviceA, now); err != nil || len(expired) != mailboxBatchSize+1 {
		t.Fatalf("prepare expiry statuses = %d, %v", len(expired), err)
	}
	before, err := server.mailbox.ExpiryStatusesForPair(pair.pairID, pair.deviceA, pair.deviceB, mailboxBatchSize, now)
	if err != nil || len(before) != mailboxBatchSize {
		t.Fatalf("initial expiry page = %d, %v", len(before), err)
	}
	beforeCursor := make(chan struct{})
	releaseCursor := make(chan struct{})
	server.relayHelloBeforeMailboxStore = func(operation string) {
		if operation != "cursor" {
			return
		}
		close(beforeCursor)
		<-releaseCursor
	}
	helloDone := make(chan error, 1)
	go func() {
		helloDone <- server.handleRelayHelloForPair(pair.deviceA, pair.pairID, nil, RelayHello{
			V: 2, Type: "relay.hello", Protocols: []int{2, 1}, AppVersion: "shutdown-test",
		}, func(any) error { return nil }, func([]string) {})
	}()
	<-beforeCursor
	server.BeginShutdown()
	close(releaseCursor)
	if err := <-helloDone; !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("relay hello error = %v, want ErrServerCapacity", err)
	}
	after, err := server.mailbox.ExpiryStatusesForPair(pair.pairID, pair.deviceA, pair.deviceB, mailboxBatchSize, now)
	if err != nil || len(after) != len(before) {
		t.Fatalf("expiry page after blocked cursor = %d, %v; want %d", len(after), err, len(before))
	}
	for index := range before {
		if after[index].MsgID != before[index].MsgID {
			t.Fatalf("expiry cursor advanced at index %d: got %q, want %q", index, after[index].MsgID, before[index].MsgID)
		}
	}
}

func TestShutdownLinearizesBeforeEveryInFlightPairingCommit(t *testing.T) {
	tests := []struct {
		name    string
		stage   pairStage
		target  string
		prepare func(*testing.T, *Server) (map[string]any, func(*testing.T))
	}{
		{
			name: "init", stage: pairStageInit, target: "/pair/init",
			prepare: func(t *testing.T, server *Server) (map[string]any, func(*testing.T)) {
				token := "shutdown-init"
				return validPairInitBody(token), func(t *testing.T) {
					if _, err := server.pairStore.GetPending(token); err == nil {
						t.Fatal("in-flight pair init persisted after shutdown")
					}
				}
			},
		},
		{
			name: "hello", stage: pairStageHello, target: "/pair/hello",
			prepare: func(t *testing.T, server *Server) (map[string]any, func(*testing.T)) {
				token := "shutdown-hello"
				aEnc, aSign, _ := ed25519Keypair(t)
				bEnc, bSign, _ := ed25519Keypair(t)
				if err := server.pairStore.PutPending(store.PendingPair{
					PairToken: token, DeviceAID: "shutdown-a", AEncPubkey: aEnc, ASignPubkey: aSign, CreatedAt: server.now().Unix(),
				}); err != nil {
					t.Fatal(err)
				}
				return map[string]any{
						"pair_token": token, "device_id": "shutdown-b",
						"enc_pubkey": base64.StdEncoding.EncodeToString(bEnc), "sign_pubkey": base64.StdEncoding.EncodeToString(bSign),
					}, func(t *testing.T) {
						pending, err := server.pairStore.GetPending(token)
						if err != nil || pending.DeviceBID != "" {
							t.Fatalf("in-flight pair hello mutated state: %#v, %v", pending, err)
						}
					}
			},
		},
		{
			name: "signature", stage: pairStageSignature, target: "/pair/send_sig",
			prepare: func(t *testing.T, server *Server) (map[string]any, func(*testing.T)) {
				token := "shutdown-signature"
				aEnc, aSign, aPrivate := ed25519Keypair(t)
				bEnc, bSign, _ := ed25519Keypair(t)
				if err := server.pairStore.PutPending(store.PendingPair{
					PairToken: token, DeviceAID: "shutdown-a", AEncPubkey: aEnc, ASignPubkey: aSign,
					DeviceBID: "shutdown-b", BEncPubkey: bEnc, BSignPubkey: bSign, CreatedAt: server.now().Unix(),
				}); err != nil {
					t.Fatal(err)
				}
				message := append([]byte(token), aEnc...)
				message = append(message, aSign...)
				message = append(message, bEnc...)
				message = append(message, bSign...)
				signature := ed25519.Sign(aPrivate, message)
				return map[string]any{
						"pair_token": token, "confirmation_sig": base64.StdEncoding.EncodeToString(signature),
					}, func(t *testing.T) {
						pending, err := server.pairStore.GetPending(token)
						if err != nil || len(pending.ConfirmationSig) != 0 {
							t.Fatalf("in-flight pair signature mutated state: %#v, %v", pending, err)
						}
					}
			},
		},
		{
			name: "complete", stage: pairStageComplete, target: "/pair/complete",
			prepare: func(t *testing.T, server *Server) (map[string]any, func(*testing.T)) {
				token := "shutdown-complete"
				aEnc, aSign, aPrivate := ed25519Keypair(t)
				bEnc, bSign, bPrivate := ed25519Keypair(t)
				if err := server.pairStore.PutPending(store.PendingPair{
					PairToken: token, DeviceAID: "shutdown-a", AEncPubkey: aEnc, ASignPubkey: aSign,
					DeviceBID: "shutdown-b", BEncPubkey: bEnc, BSignPubkey: bSign, CreatedAt: server.now().Unix(),
				}); err != nil {
					t.Fatal(err)
				}
				message := append([]byte(token), aEnc...)
				message = append(message, aSign...)
				message = append(message, bEnc...)
				message = append(message, bSign...)
				aSignature := ed25519.Sign(aPrivate, message)
				bMessage := append([]byte("twinotify-pair-confirm-b-v1\n"), message...)
				bMessage = append(bMessage, aSignature...)
				bSignature := ed25519.Sign(bPrivate, bMessage)
				return map[string]any{
						"pair_token": token, "device_id": "shutdown-b",
						"enc_pubkey": base64.StdEncoding.EncodeToString(bEnc), "sign_pubkey": base64.StdEncoding.EncodeToString(bSign),
						"confirmation_sig":           base64.StdEncoding.EncodeToString(aSignature),
						"responder_confirmation_sig": base64.StdEncoding.EncodeToString(bSignature),
					}, func(t *testing.T) {
						pending, err := server.pairStore.GetPending(token)
						if err != nil || pending.PairID != "" || len(pending.ResponderConfirmationSig) != 0 {
							t.Fatalf("in-flight pair completion mutated state: %#v, %v", pending, err)
						}
					}
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := newTestServer(t)
			body, assertUnchanged := test.prepare(t, server)
			beforeStore := make(chan struct{})
			releaseStore := make(chan struct{})
			server.pairMutationBeforeStore = func(stage pairStage) {
				if stage != test.stage {
					t.Errorf("mutation stage = %d, want %d", stage, test.stage)
				}
				close(beforeStore)
				<-releaseStore
			}
			rawBody := mustJSON(t, body)
			responseDone := make(chan *httptest.ResponseRecorder, 1)
			go func() {
				request := httptest.NewRequest(http.MethodPost, test.target, strings.NewReader(rawBody))
				request.RemoteAddr = "192.0.2.60:6000"
				response := httptest.NewRecorder()
				server.Handler().ServeHTTP(response, request)
				responseDone <- response
			}()
			<-beforeStore
			server.BeginShutdown()
			close(releaseStore)
			response := <-responseDone
			if response.Code != http.StatusServiceUnavailable {
				t.Fatalf("status = %d, want 503; body=%q", response.Code, response.Body.String())
			}
			assertUnchanged(t)
		})
	}
}

func TestBeginShutdownWaitsForAnAdmittedMutationToFinish(t *testing.T) {
	server := newTestServer(t)
	releaseMutation, admitted := server.acquireMutationAdmission()
	if !admitted {
		t.Fatal("healthy server rejected mutation admission")
	}
	shutdownDone := make(chan struct{})
	go func() {
		server.BeginShutdown()
		close(shutdownDone)
	}()
	select {
	case <-shutdownDone:
		t.Fatal("shutdown crossed an admitted mutation")
	case <-time.After(50 * time.Millisecond):
	}
	releaseMutation()
	select {
	case <-shutdownDone:
	case <-time.After(time.Second):
		t.Fatal("shutdown did not continue after the admitted mutation finished")
	}
}

func TestDiskCapacityCheckUsesAvailableBlocksAndFailsClosed(t *testing.T) {
	check := newDiskCapacityCheck("/srv/data/relay.db", 10*4096, func(path string, stat *unix.Statfs_t) error {
		if path != "/srv/data" {
			t.Fatalf("statfs path = %q, want database directory", path)
		}
		stat.Bsize = 4096
		stat.Bavail = 9
		return nil
	})
	if err := check(0); !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("low disk error = %v, want ErrServerCapacity", err)
	}

	check = newDiskCapacityCheck("/srv/data/relay.db", 10*4096, func(_ string, stat *unix.Statfs_t) error {
		stat.Bsize = 4096
		stat.Bavail = 10
		return nil
	})
	if err := check(0); err != nil {
		t.Fatalf("sufficient disk rejected: %v", err)
	}
	if err := check(1); !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("write reservation crossed free-space floor: %v, want ErrServerCapacity", err)
	}

	check = newDiskCapacityCheck("/srv/data/relay.db", 1, func(string, *unix.Statfs_t) error {
		return errors.New("statfs unavailable")
	})
	if err := check(0); !errors.Is(err, ErrServerCapacity) {
		t.Fatalf("statfs failure = %v, want ErrServerCapacity", err)
	}
}

func assertHealthStatus(t *testing.T, server *Server, path string, want int, bodyFragment string) {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, path, nil)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != want {
		t.Fatalf("%s status = %d, want %d; body=%q", path, response.Code, want, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), bodyFragment) {
		t.Fatalf("%s body = %q, want fragment %q", path, response.Body.String(), bodyFragment)
	}
}
