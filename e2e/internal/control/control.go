package control

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

var (
	ErrTimeout        = errors.New("E2E control result timeout")
	ErrResultNotReady = errors.New("E2E result not ready")
	ErrDeviceOffline  = errors.New("E2E control device offline")
)

type Command struct {
	RequestID string
	Name      string
	Token     string
	Params    map[string]string
}

var notificationFixtures = map[string]bool{
	"reply": true, "mark_read": true, "auto_cancel": true, "persistent": true,
}

var notificationFixtureOperations = map[string]bool{
	"post": true, "update": true, "cancel": true, "reset_counters": true,
}

var notificationMirrorOperations = map[string]bool{
	"invoke_reply": true, "invoke_mark_read": true, "replay_last_invoke": true,
	"arm_reply": true, "arm_mark_read": true, "invoke_armed": true, "tap": true,
}

// NewNotificationFixtureCommand is the only host-side constructor for the
// dedicated fixture APK. It cannot carry notification content or components.
func NewNotificationFixtureCommand(requestID, fixture, operation string) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("fixture request ID is required")
	}
	if !notificationFixtures[fixture] || !notificationFixtureOperations[operation] {
		return Command{}, errors.New("fixture command is outside the closed contract")
	}
	return Command{
		RequestID: requestID,
		Name:      "NOTIFICATION_FIXTURE",
		Params:    map[string]string{"fixture": fixture, "operation": operation},
	}, nil
}

// NewNotificationMirrorCommand addresses only the newest or previously armed
// mirrored fixture action. Identity and reply content stay inside Android.
func NewNotificationMirrorCommand(requestID, operation string) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("mirror request ID is required")
	}
	if !notificationMirrorOperations[operation] {
		return Command{}, errors.New("mirror command is outside the closed contract")
	}
	return Command{
		RequestID: requestID,
		Name:      "NOTIFICATION_MIRROR",
		Params:    map[string]string{"operation": operation},
	}, nil
}

var callControlSourceStates = map[string]bool{"ringing": true, "active": true, "idle": true}

var callControlTapKinds = map[string]bool{"answer": true, "decline": true, "hang_up": true, "replay": true}

var callControlAwaitKinds = map[string]bool{"answer": true, "decline": true, "hang_up": true}

// MaxCallControlAwait bounds how long the origin fixture may block waiting for
// one receiver dispatch. The device rejects anything outside 1..10000 ms.
const MaxCallControlAwait = 10 * time.Second

// NewCallControlsEnableCommand turns on synthetic debug call capture with
// control advertising on the origin. It carries no parameters.
func NewCallControlsEnableCommand(requestID string) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("call control request ID is required")
	}
	return Command{RequestID: requestID, Name: "CALL_CONTROLS_ENABLE"}, nil
}

// NewCallControlSourceCommand drives the origin fixture's local CallStyle
// notification and injected telephony state. Only the closed state enum
// crosses ADB; caller, number, and intent material never do.
func NewCallControlSourceCommand(requestID, state string) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("call control request ID is required")
	}
	if !callControlSourceStates[state] {
		return Command{}, errors.New("call control source state is outside the closed contract")
	}
	return Command{RequestID: requestID, Name: "CALL_CONTROL_SOURCE", Params: map[string]string{"state": state}}, nil
}

// NewCallControlTapCommand taps one native control on the mirror. The device
// resolves which mirror and which capability; the host names only the kind.
func NewCallControlTapCommand(requestID, kind string) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("call control request ID is required")
	}
	if !callControlTapKinds[kind] {
		return Command{}, errors.New("call control tap kind is outside the closed contract")
	}
	return Command{RequestID: requestID, Name: "CALL_CONTROL_TAP", Params: map[string]string{"kind": kind}}, nil
}

// NewCallControlAwaitCommand waits on the origin for the fixture receiver of
// one control kind and returns only kind, count, status, and timing.
func NewCallControlAwaitCommand(requestID, kind string, timeout time.Duration) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("call control request ID is required")
	}
	if !callControlAwaitKinds[kind] {
		return Command{}, errors.New("call control await kind is outside the closed contract")
	}
	if timeout < time.Millisecond || timeout > MaxCallControlAwait {
		return Command{}, errors.New("call control await timeout is outside 1ms..10s")
	}
	return Command{
		RequestID: requestID,
		Name:      "CALL_CONTROL_AWAIT",
		Params:    map[string]string{"kind": kind, "timeout_ms": strconv.FormatInt(timeout.Milliseconds(), 10)},
	}, nil
}

var routeFaultRoutes = map[string]bool{"LAN": true, "BLUETOOTH": true, "RELAY": true}

var awaitRoutePhases = map[string]bool{
	"IDLE": true, "CONNECTING": true, "AUTHENTICATED": true, "RECONNECTING": true,
}

// MaxRouteAwait bounds how long a device may block a control broadcast waiting
// for a route or a peer receipt. The plan asks for 15s, but the device-side
// validator has always capped a blocking wait at 10s and that bound is what
// keeps one stuck route from holding the per-process broadcast queue for the
// rest of the run. The host retries instead of asking for a longer single wait.
const MaxRouteAwait = 10 * time.Second

// MaxFixtureBytes is the protocol envelope maximum. A fixture may ask for the
// exact legal maximum and nothing above it.
const MaxFixtureBytes = 1_048_576

// NewRouteFaultCommand makes exactly one route unavailable to the device's own
// route factory, or restores it. Only the closed route enum crosses ADB.
func NewRouteFaultCommand(requestID, route string, enabled bool) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("route fault request ID is required")
	}
	if !routeFaultRoutes[route] {
		return Command{}, errors.New("route fault route is outside the closed contract")
	}
	return Command{
		RequestID: requestID,
		Name:      "ROUTE_FAULT",
		Params:    map[string]string{"route": route, "enabled": strconv.FormatBool(enabled)},
	}, nil
}

// NewAwaitRouteCommand blocks on the device until its own route status reaches
// the named route and phase. It returns only enums, a status, and a duration.
func NewAwaitRouteCommand(requestID, route, phase string, timeout time.Duration) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("await route request ID is required")
	}
	if !routeFaultRoutes[route] {
		return Command{}, errors.New("await route is outside the closed contract")
	}
	if !awaitRoutePhases[phase] {
		return Command{}, errors.New("await route phase is outside the closed contract")
	}
	if timeout < time.Millisecond || timeout > MaxRouteAwait {
		return Command{}, errors.New("await route timeout is outside 1ms..10s")
	}
	return Command{
		RequestID: requestID,
		Name:      "AWAIT_ROUTE",
		Params: map[string]string{
			"route": route, "phase": phase,
			"timeout_ms": strconv.FormatInt(timeout.Milliseconds(), 10),
		},
	}, nil
}

// NewEnqueueFixtureCommand asks the device to author one synthetic outbound
// envelope of the given size through the production capture boundary. The host
// names a byte count and nothing else; the device authors the content.
func NewEnqueueFixtureCommand(requestID string, bytes int) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("fixture request ID is required")
	}
	if bytes < 1 || bytes > MaxFixtureBytes {
		return Command{}, errors.New("fixture size is outside 1..1048576 bytes")
	}
	return Command{
		RequestID: requestID,
		Name:      "ENQUEUE_FIXTURE",
		Params:    map[string]string{"bytes": strconv.Itoa(bytes)},
	}, nil
}

// NewAwaitPeerReceiptCommand blocks until nothing on the device is still
// awaiting an authenticated peer receipt.
func NewAwaitPeerReceiptCommand(requestID string, timeout time.Duration) (Command, error) {
	if requestID == "" {
		return Command{}, errors.New("await receipt request ID is required")
	}
	if timeout < time.Millisecond || timeout > MaxRouteAwait {
		return Command{}, errors.New("await receipt timeout is outside 1ms..10s")
	}
	return Command{
		RequestID: requestID,
		Name:      "AWAIT_PEER_RECEIPT",
		Params:    map[string]string{"timeout_ms": strconv.FormatInt(timeout.Milliseconds(), 10)},
	}, nil
}

type Result struct {
	RequestID string          `json:"request_id"`
	Code      string          `json:"code"`
	Detail    string          `json:"detail,omitempty"`
	Payload   json.RawMessage `json:"payload,omitempty"`
}

// Device is implemented by an ADB-backed adapter. Keeping it small makes host tests hermetic.
type Device interface {
	Broadcast(context.Context, Command) error
	ReadResult(context.Context, string) ([]byte, error)
}

type SecretDevice interface {
	WriteSecret(context.Context, string, []byte) error
	ReadSecretOnce(context.Context, string) ([]byte, error)
	CleanupPrivateInput(context.Context, string) error
	CleanupPrivateOutput(context.Context, string) error
}

type PrivateAuthDevice interface {
	CleanupPrivateAuth(context.Context, string) error
}

type SecureRequestDevice interface {
	BoundRequestID(string, string) (string, error)
}

type Client struct {
	device  Device
	serial  string
	token   string
	timeout time.Duration
	poll    time.Duration
}

func New(device Device, serial, token string, timeout time.Duration) *Client {
	if device == nil {
		panic("control device is required")
	}
	if timeout <= 0 {
		panic("control timeout must be positive")
	}
	return &Client{device: device, serial: serial, token: token, timeout: timeout, poll: 10 * time.Millisecond}
}

func (c *Client) Execute(ctx context.Context, command Command) (Result, error) {
	if err := ctx.Err(); err != nil {
		return Result{}, err
	}
	if command.RequestID == "" || command.Name == "" {
		return Result{}, errors.New("control request ID and command name are required")
	}
	if c.token == "" {
		return Result{}, errors.New("control session token is required")
	}
	if command.Token != "" && command.Token != c.token {
		return Result{}, errors.New("control command token does not match client token")
	}
	var err error
	command, err = c.prepare(command)
	if err != nil {
		return Result{}, err
	}
	defer c.cleanupAuth(ctx, command.RequestID)
	return c.executePrepared(ctx, command)
}

func (c *Client) executePrepared(ctx context.Context, command Command) (Result, error) {
	if err := c.device.Broadcast(ctx, command); err != nil {
		return Result{}, err
	}

	deadline := time.NewTimer(c.timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(c.poll)
	defer ticker.Stop()
	for {
		resultBytes, err := c.device.ReadResult(ctx, command.RequestID)
		if err == nil {
			var result Result
			if decodeErr := json.Unmarshal(resultBytes, &result); decodeErr != nil {
				return Result{}, fmt.Errorf("decode control result: %w", decodeErr)
			}
			if result.RequestID == command.RequestID {
				return result, nil
			}
		} else if !errors.Is(err, ErrResultNotReady) {
			return Result{}, err
		}

		select {
		case <-ctx.Done():
			return Result{}, ctx.Err()
		case <-deadline.C:
			return Result{}, fmt.Errorf("%w: serial=%s request_id=%s", ErrTimeout, c.serial, command.RequestID)
		case <-ticker.C:
		}
	}
}

// ExecuteSecret transfers ceremony material through an app-private, one-time
// channel. Only the opaque request handle enters the authenticated broadcast.
func (c *Client) ExecuteSecret(ctx context.Context, command Command, secretInput []byte) (Result, []byte, error) {
	if err := ctx.Err(); err != nil {
		return Result{}, nil, err
	}
	if command.RequestID == "" || command.Name == "" {
		return Result{}, nil, errors.New("control request ID and command name are required")
	}
	device, ok := c.device.(SecretDevice)
	if !ok {
		return Result{}, nil, errors.New("control device does not support private ceremony channel")
	}
	var err error
	command, err = c.prepare(command)
	if err != nil {
		return Result{}, nil, err
	}
	if !privateRequestID(command.RequestID) {
		return Result{}, nil, errors.New("private control request ID is invalid")
	}
	defer c.cleanupAuth(ctx, command.RequestID)
	defer boundedCleanup(ctx, func(cleanup context.Context) { _ = device.CleanupPrivateInput(cleanup, command.RequestID) })
	defer boundedCleanup(ctx, func(cleanup context.Context) { _ = device.CleanupPrivateOutput(cleanup, command.RequestID) })
	if len(secretInput) > 4096 {
		return Result{}, nil, errors.New("private control input exceeds bound")
	}
	if len(secretInput) > 0 {
		if err := device.WriteSecret(ctx, command.RequestID, secretInput); err != nil {
			return Result{}, nil, err
		}
		if command.Params == nil {
			command.Params = map[string]string{}
		}
		command.Params["secret_input_id"] = command.RequestID
	}
	result, err := c.executePrepared(ctx, command)
	if err != nil {
		return Result{}, nil, err
	}
	if result.Code != "ok" {
		return result, nil, nil
	}
	secret, err := device.ReadSecretOnce(ctx, command.RequestID)
	if err != nil {
		return Result{}, nil, err
	}
	if len(secret) > 4096 {
		clear(secret)
		return Result{}, nil, errors.New("private control result exceeds bound")
	}
	return result, secret, nil
}

const privateCleanupTimeout = 2 * time.Second

func boundedCleanup(parent context.Context, cleanup func(context.Context)) {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(parent), privateCleanupTimeout)
	defer cancel()
	cleanup(ctx)
}

func (c *Client) cleanupAuth(parent context.Context, requestID string) {
	device, ok := c.device.(PrivateAuthDevice)
	if !ok {
		return
	}
	boundedCleanup(parent, func(ctx context.Context) { _ = device.CleanupPrivateAuth(ctx, requestID) })
}

func (c *Client) prepare(command Command) (Command, error) {
	if c.token == "" {
		return Command{}, errors.New("control session token is required")
	}
	if command.Token != "" && command.Token != c.token {
		return Command{}, errors.New("control command token does not match client token")
	}
	if secure, ok := c.device.(SecureRequestDevice); ok {
		requestID, err := secure.BoundRequestID(c.token, command.Name)
		if err != nil {
			return Command{}, err
		}
		command.RequestID = requestID
	}
	command.Token = c.token
	return command, nil
}

func NewBoundRequestID(token, command string, now time.Time, randomness io.Reader) (string, error) {
	if token == "" || command == "" {
		return "", errors.New("token and command are required")
	}
	if randomness == nil {
		randomness = rand.Reader
	}
	nonce := make([]byte, 16)
	if _, err := io.ReadFull(randomness, nonce); err != nil {
		return "", errors.New("request randomness unavailable")
	}
	expires := now.Add(2 * time.Minute).UnixMilli()
	commandHash := sha256.Sum256([]byte(command))
	prefix := fmt.Sprintf("v1.%d.%s.%s", expires, hex.EncodeToString(nonce), hex.EncodeToString(commandHash[:8]))
	mac := hmac.New(sha256.New, []byte(token))
	_, _ = mac.Write([]byte(prefix))
	return prefix + "." + hex.EncodeToString(mac.Sum(nil)[:16]), nil
}

func privateRequestID(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-' {
			continue
		}
		return false
	}
	return true
}

// PairPayload is the wire payload produced by PAIR_INIT. Keeping this as a
// typed value prevents the host controller from passing opaque JSON between
// devices and accidentally dropping one of the authenticated key fields.
type PairPayload struct {
	RelayURL   string `json:"relay_url"`
	DeviceID   string `json:"device_id"`
	EncPubKey  string `json:"enc_pubkey"`
	SignPubKey string `json:"sign_pubkey"`
	PairToken  string `json:"pair_token"`
}

type PairOptions struct {
	RelayURL     string
	DisplayNameA string
	DisplayNameB string
}

type Controller struct {
	a, b    *Client
	timeout time.Duration
	seq     uint64
}

func NewController(a, b *Client, timeout time.Duration) *Controller {
	if a == nil || b == nil {
		panic("both control clients are required")
	}
	if timeout <= 0 {
		panic("controller timeout must be positive")
	}
	return &Controller{a: a, b: b, timeout: timeout}
}

// Pair executes the complete authenticated two-device setup. A confirmation
// signature is read from A's persisted peer-hello status before it is sent to
// either device; this makes the host wait for the real protocol state rather
// than treating a broadcast acknowledgement as pairing success.
func (c *Controller) Pair(ctx context.Context, options PairOptions) error {
	if strings.TrimSpace(options.RelayURL) == "" {
		return errors.New("relay URL is required")
	}
	init, err := c.execute(ctx, c.a, "PAIR_INIT", map[string]string{
		"relay_url": options.RelayURL, "display_name": options.DisplayNameA,
	})
	if err != nil {
		return c.withSnapshots(ctx, "PAIR_INIT", err)
	}
	payload, err := decodePairPayload(init.Payload)
	if err != nil {
		return c.withSnapshots(ctx, "PAIR_INIT payload", err)
	}
	if payload.RelayURL == "" {
		payload.RelayURL = options.RelayURL
	}
	if err = c.executeOnly(ctx, c.b, "PAIR_JOIN", map[string]string{
		"pair_payload": mustJSON(payload), "display_name": options.DisplayNameB,
	}); err != nil {
		return c.withSnapshots(ctx, "PAIR_JOIN", err)
	}

	peerHello, err := c.waitForPeerHello(ctx, payload)
	if err != nil {
		return c.withSnapshots(ctx, "peer hello", err)
	}
	signature, err := c.execute(ctx, c.a, "SIGN_CONFIRMATION", map[string]string{
		"pair_token":    payload.PairToken,
		"b_enc_pubkey":  peerHello.EncPubKey,
		"b_sign_pubkey": peerHello.SignPubKey,
	})
	if err != nil {
		return c.withSnapshots(ctx, "SIGN_CONFIRMATION", err)
	}
	sigValue, err := decodeSignature(signature.Payload)
	if err != nil {
		return c.withSnapshots(ctx, "SIGN_CONFIRMATION payload", err)
	}
	if err = c.executeOnly(ctx, c.a, "SEND_CONFIRMATION_SIG", map[string]string{
		"relay_url": payload.RelayURL, "pair_token": payload.PairToken,
		"confirmation_sig": sigValue,
	}); err != nil {
		return c.withSnapshots(ctx, "SEND_CONFIRMATION_SIG", err)
	}
	pairSig, err := c.execute(ctx, c.b, "AWAIT_PAIR_SIG", map[string]string{
		"relay_url": payload.RelayURL, "pair_token": payload.PairToken,
	})
	if err != nil {
		return c.withSnapshots(ctx, "AWAIT_PAIR_SIG", err)
	}
	peerSignature, err := decodeSignature(pairSig.Payload)
	if err != nil {
		return c.withSnapshots(ctx, "AWAIT_PAIR_SIG payload", err)
	}
	if err = c.executeOnly(ctx, c.b, "PAIR_COMPLETE", map[string]string{
		"relay_url": payload.RelayURL, "pair_token": payload.PairToken,
		"confirmation_sig": peerSignature,
	}); err != nil {
		return c.withSnapshots(ctx, "PAIR_COMPLETE", err)
	}
	if err = c.waitForReciprocalPeers(ctx, payload.DeviceID, peerHello.DeviceID); err != nil {
		return c.withSnapshots(ctx, "reciprocal status", err)
	}
	if err = c.executeOnly(ctx, c.a, "START_SYNC", map[string]string{"relay_url": payload.RelayURL}); err != nil {
		return c.withSnapshots(ctx, "START_SYNC A", err)
	}
	if err = c.executeOnly(ctx, c.b, "START_SYNC", map[string]string{"relay_url": payload.RelayURL}); err != nil {
		return c.withSnapshots(ctx, "START_SYNC B", err)
	}
	if err = c.waitForHealthy(ctx); err != nil {
		return c.withSnapshots(ctx, "connected health", err)
	}
	return nil
}

type peerHello struct {
	DeviceID   string `json:"device_id"`
	EncPubKey  string `json:"enc_pubkey"`
	SignPubKey string `json:"sign_pubkey"`
}

func (c *Controller) waitForPeerHello(ctx context.Context, payload PairPayload) (peerHello, error) {
	var last Result
	return waitValue(ctx, c.timeout, func(ctx context.Context) (peerHello, bool, error) {
		var err error
		last, err = c.execute(ctx, c.a, "AWAIT_PEER_HELLO", map[string]string{
			"relay_url":  payload.RelayURL,
			"pair_token": payload.PairToken,
		})
		if err != nil {
			if errors.Is(err, ErrTimeout) {
				return peerHello{}, false, nil
			}
			return peerHello{}, false, err
		}
		var hello peerHello
		if err := json.Unmarshal(last.Payload, &hello); err != nil {
			return peerHello{}, false, fmt.Errorf("decode peer hello: %w", err)
		}
		if hello.DeviceID != "" && hello.EncPubKey != "" && hello.SignPubKey != "" {
			return hello, true, nil
		}
		return peerHello{}, false, nil
	})
}

func decodeSignature(raw json.RawMessage) (string, error) {
	var value struct {
		Signature string `json:"confirmation_sig"`
	}
	if err := json.Unmarshal(raw, &value); err != nil {
		return "", fmt.Errorf("decode confirmation signature: %w", err)
	}
	if value.Signature == "" {
		return "", errors.New("confirmation signature is empty")
	}
	return value.Signature, nil
}

func (c *Controller) waitForReciprocalPeers(ctx context.Context, aID, bID string) error {
	aHash := sha256.Sum256([]byte(aID))
	bHash := sha256.Sum256([]byte(bID))
	expectedA := hex.EncodeToString(aHash[:])
	expectedB := hex.EncodeToString(bHash[:])
	return waitValueErr(ctx, c.timeout, func(ctx context.Context) (bool, error) {
		a, err := c.execute(ctx, c.a, "STATUS", nil)
		if err != nil {
			return false, err
		}
		b, err := c.execute(ctx, c.b, "STATUS", nil)
		if err != nil {
			return false, err
		}
		var as, bs struct {
			DeviceIDHash   string `json:"device_id_hash"`
			PairedPeerHash string `json:"paired_peer_hash"`
		}
		if err := json.Unmarshal(a.Payload, &as); err != nil {
			return false, err
		}
		if err := json.Unmarshal(b.Payload, &bs); err != nil {
			return false, err
		}
		return as.DeviceIDHash == expectedA && as.PairedPeerHash == expectedB &&
			bs.DeviceIDHash == expectedB && bs.PairedPeerHash == expectedA, nil
	})
}

func (c *Controller) waitForHealthy(ctx context.Context) error {
	return waitValueErr(ctx, c.timeout, func(ctx context.Context) (bool, error) {
		for _, client := range []*Client{c.a, c.b} {
			result, err := c.execute(ctx, client, "STATUS", nil)
			if err != nil {
				return false, err
			}
			var status struct {
				Health struct {
					Service       string `json:"service"`
					ProtocolFloor int    `json:"protocolFloor"`
				} `json:"health"`
			}
			if err := json.Unmarshal(result.Payload, &status); err != nil {
				return false, err
			}
			if status.Health.Service != "connected" || status.Health.ProtocolFloor < 2 {
				return false, nil
			}
		}
		return true, nil
	})
}

func (c *Controller) execute(ctx context.Context, client *Client, name string, params map[string]string) (Result, error) {
	id := fmt.Sprintf("e2e-%s-%d", strings.ToLower(name), atomic.AddUint64(&c.seq, 1))
	return client.Execute(ctx, Command{RequestID: id, Name: name, Params: params})
}

func (c *Controller) executeOnly(ctx context.Context, client *Client, name string, params map[string]string) error {
	_, err := c.execute(ctx, client, name, params)
	return err
}

func (c *Controller) withSnapshots(ctx context.Context, stage string, cause error) error {
	snapshotCtx, cancel := context.WithTimeout(ctx, snapshotTimeout(c.timeout))
	defer cancel()
	a, aErr := c.execute(snapshotCtx, c.a, "STATUS", nil)
	b, bErr := c.execute(snapshotCtx, c.b, "STATUS", nil)
	return fmt.Errorf("%s failed: %w (health_a=%s err_a=%v health_b=%s err_b=%v)",
		stage, cause, compactPayload(a.Payload), aErr, compactPayload(b.Payload), bErr)
}

func snapshotTimeout(timeout time.Duration) time.Duration {
	if timeout/2 < 250*time.Millisecond {
		return 250 * time.Millisecond
	}
	return timeout / 2
}

func compactPayload(payload json.RawMessage) string {
	if len(payload) == 0 {
		return "<unavailable>"
	}
	const max = 512
	if len(payload) > max {
		return string(payload[:max]) + "..."
	}
	return string(payload)
}

func decodePairPayload(raw json.RawMessage) (PairPayload, error) {
	var p PairPayload
	if len(raw) == 0 {
		return p, errors.New("PAIR_INIT returned no payload")
	}
	if err := json.Unmarshal(raw, &p); err != nil {
		return p, fmt.Errorf("decode pair payload: %w", err)
	}
	if p.PairToken == "" || p.DeviceID == "" || p.EncPubKey == "" || p.SignPubKey == "" {
		return p, errors.New("PAIR_INIT payload missing authenticated fields")
	}
	return p, nil
}

func mustJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

func waitValue[T any](ctx context.Context, timeout time.Duration, fn func(context.Context) (T, bool, error)) (T, error) {
	var zero T
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		value, done, err := fn(ctx)
		if err != nil {
			return zero, err
		}
		if done {
			return value, nil
		}
		select {
		case <-ctx.Done():
			return zero, ctx.Err()
		case <-deadline.C:
			return zero, fmt.Errorf("%w: controller wait", ErrTimeout)
		case <-ticker.C:
		}
	}
}

func waitValueErr(ctx context.Context, timeout time.Duration, fn func(context.Context) (bool, error)) error {
	_, err := waitValue(ctx, timeout, func(ctx context.Context) (struct{}, bool, error) { done, err := fn(ctx); return struct{}{}, done, err })
	return err
}

// ParseHealthProtocol is exported for the CLI and tests that need to validate
// a state payload without coupling to Android's implementation details.
func ParseHealthProtocol(payload json.RawMessage) (int, string, error) {
	var value struct {
		Health struct {
			ProtocolFloor int    `json:"protocolFloor"`
			Service       string `json:"service"`
		} `json:"health"`
	}
	if err := json.Unmarshal(payload, &value); err != nil {
		return 0, "", err
	}
	return value.Health.ProtocolFloor, value.Health.Service, nil
}

func ParseIntField(payload json.RawMessage, field string) (int, error) {
	var values map[string]any
	if err := json.Unmarshal(payload, &values); err != nil {
		return 0, err
	}
	n, ok := values[field].(float64)
	if !ok {
		return 0, fmt.Errorf("%s missing", field)
	}
	return strconv.Atoi(strconv.Itoa(int(n)))
}
