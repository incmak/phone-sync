package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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
	command.Token = c.token
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
			DeviceID   string `json:"device_id"`
			PairedPeer string `json:"paired_peer"`
		}
		if err := json.Unmarshal(a.Payload, &as); err != nil {
			return false, err
		}
		if err := json.Unmarshal(b.Payload, &bs); err != nil {
			return false, err
		}
		return as.DeviceID == aID && as.PairedPeer == bID && bs.DeviceID == bID && bs.PairedPeer == aID, nil
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
