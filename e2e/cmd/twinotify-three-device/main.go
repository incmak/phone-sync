// twinotify-three-device exercises production Android and macOS receive paths
// using synthetic emulator notifications and an isolated Mac E2E bundle.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/device"
)

func main() {
	aSerial := flag.String("a", "", "source emulator serial")
	bSerial := flag.String("b", "", "second emulator serial")
	macDirectory := flag.String("mac-directory", "", "private run directory of TwinotifyE2E")
	androidRelay := flag.String("android-relay", "http://127.0.0.1:18080", "relay URL visible to emulators via adb reverse")
	macRelay := flag.String("mac-relay", "http://127.0.0.1:18080", "same relay as seen by the Mac")
	pair := flag.Bool("pair", false, "pair fresh synthetic installations before testing")
	pairMacOnly := flag.Bool("pair-mac", false, "add a fresh Mac to an existing reciprocal emulator pair")
	flag.Parse()
	if *pair && *pairMacOnly {
		fail(errors.New("choose --pair or --pair-mac"))
	}
	if !strings.HasPrefix(*aSerial, "emulator-") || !strings.HasPrefix(*bSerial, "emulator-") || *aSerial == *bSerial {
		fmt.Fprintln(os.Stderr, "two distinct emulator serials are required; this command does not operate on personal phones")
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Minute)
	defer cancel()
	a, err := device.OpenAndroid(ctx, *aSerial, "com.twinotify.app")
	if err != nil {
		fail(err)
	}
	b, err := device.OpenAndroid(ctx, *bSerial, "com.twinotify.app")
	if err != nil {
		fail(err)
	}
	m, err := device.OpenMac(*macDirectory)
	if err != nil {
		fail(err)
	}
	if *pair {
		for _, receiver := range []device.Receiver{a, b, m} {
			s, err := receiver.Snapshot(ctx)
			if err != nil {
				fail(err)
			}
			if len(s.Peers) != 0 {
				fail(errors.New("--pair requires fresh synthetic installations; existing links are never reset"))
			}
		}
		fmt.Fprintln(os.Stderr, "Pairing Android A and B")
		if err := control.NewController(a.Control, b.Control, 45*time.Second).Pair(ctx,
			control.PairOptions{RelayURL: *androidRelay, DisplayNameA: "E2E phone A", DisplayNameB: "E2E phone B"}); err != nil {
			fail(err)
		}
	}
	if *pairMacOnly {
		as, err := a.Snapshot(ctx)
		if err != nil {
			fail(err)
		}
		bs, err := b.Snapshot(ctx)
		if err != nil {
			fail(err)
		}
		ms, err := m.Snapshot(ctx)
		if err != nil {
			fail(err)
		}
		if len(as.Peers) != 1 || len(bs.Peers) != 1 || len(ms.Peers) != 0 ||
			as.Peers[0].DeviceHash != bs.DeviceHash || bs.Peers[0].DeviceHash != as.DeviceHash ||
			as.Peers[0].Lifecycle != "ACTIVE" || bs.Peers[0].Lifecycle != "ACTIVE" {
			fail(errors.New("--pair-mac requires exactly one reciprocal active phone link and a fresh Mac"))
		}
	}
	if *pair || *pairMacOnly {
		for _, phone := range []*device.Android{a, b} {
			fmt.Fprintln(os.Stderr, "Pairing a phone with the Mac")
			if err := pairMac(ctx, phone, m, *androidRelay, *macRelay); err != nil {
				fail(err)
			}
		}
	}
	if err := runMatrix(ctx, a, b, m); err != nil {
		fail(err)
	}
}

func fail(err error) { fmt.Fprintln(os.Stderr, err); os.Exit(1) }

func pairMac(ctx context.Context, phone *device.Android, mac *device.Mac, androidRelay, macRelay string) error {
	init, err := phone.Execute(ctx, "PAIR_INIT", map[string]string{"relay_url": androidRelay, "display_name": "E2E phone"})
	if err != nil {
		return err
	}
	var qr control.PairPayload
	if err := json.Unmarshal(init.Payload, &qr); err != nil {
		return err
	}
	phoneQR := qr
	qr.RelayURL = macRelay
	raw, _ := json.Marshal(qr)
	proof, err := mac.Execute(ctx, "pair", string(raw))
	if err != nil {
		return err
	}
	var fingerprints struct {
		Phone string `json:"phone_fingerprint"`
		Mac   string `json:"mac_fingerprint"`
	}
	phoneFP, err := fingerprint(phoneQR.EncPubKey, phoneQR.SignPubKey)
	if err != nil {
		return err
	}
	if json.Unmarshal(proof, &fingerprints) != nil || normalizeFingerprint(fingerprints.Phone) != phoneFP {
		return errors.New("synthetic phone fingerprint mismatch")
	}
	// Confirm the scanned phone fingerprint before the Mac starts Device-B hello.
	// The phone still verifies the Mac's proof before signing its confirmation.
	if _, err := mac.Execute(ctx, "confirm_pairing", ""); err != nil {
		return err
	}
	hello, err := phone.Execute(ctx, "AWAIT_PEER_HELLO", map[string]string{"relay_url": androidRelay, "pair_token": qr.PairToken})
	if err != nil {
		return err
	}
	var peer struct {
		Enc  string `json:"enc_pubkey"`
		Sign string `json:"sign_pubkey"`
	}
	if json.Unmarshal(hello.Payload, &peer) != nil {
		return errors.New("pairing proof unavailable")
	}
	macFP, err := fingerprint(peer.Enc, peer.Sign)
	if err != nil {
		return err
	}
	if normalizeFingerprint(fingerprints.Phone) != phoneFP || normalizeFingerprint(fingerprints.Mac) != macFP {
		return errors.New("synthetic pairing fingerprint mismatch")
	}
	signed, err := phone.Execute(ctx, "SIGN_CONFIRMATION", map[string]string{"pair_token": qr.PairToken,
		"b_enc_pubkey": peer.Enc, "b_sign_pubkey": peer.Sign})
	if err != nil {
		return err
	}
	var signature struct {
		Value string `json:"confirmation_sig"`
	}
	if json.Unmarshal(signed.Payload, &signature) != nil || signature.Value == "" {
		return errors.New("signature unavailable")
	}
	if _, err := phone.Execute(ctx, "SEND_CONFIRMATION_SIG", map[string]string{"relay_url": androidRelay,
		"pair_token": qr.PairToken, "confirmation_sig": signature.Value}); err != nil {
		return err
	}
	if _, err := phone.Execute(ctx, "AWAIT_PAIR_COMPLETE", map[string]string{"relay_url": androidRelay, "pair_token": qr.PairToken}); err != nil {
		return err
	}
	_, err = phone.Execute(ctx, "START_SYNC", map[string]string{"relay_url": androidRelay})
	return err
}

func fingerprint(enc, sign string) (string, error) {
	e, err := base64.StdEncoding.DecodeString(enc)
	if err != nil || len(e) != 32 {
		return "", errors.New("invalid pairing encryption key")
	}
	s, err := base64.StdEncoding.DecodeString(sign)
	if err != nil || len(s) != 32 {
		return "", errors.New("invalid pairing signing key")
	}
	sum := sha256.Sum256(append(e, s...))
	return hex.EncodeToString(sum[:]), nil
}
func normalizeFingerprint(value string) string {
	return strings.ToLower(strings.ReplaceAll(value, "-", ""))
}
func hash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func wait(ctx context.Context, stage string, check func() (bool, error)) error {
	deadline := time.NewTimer(60 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(250 * time.Millisecond)
	defer tick.Stop()
	for {
		ok, err := check()
		if err != nil {
			return fmt.Errorf("%s: %w", stage, err)
		}
		if ok {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return fmt.Errorf("%s did not converge", stage)
		case <-tick.C:
		}
	}
}

func find(snapshot device.Snapshot, canonical string) (device.Canonical, bool) {
	for _, row := range snapshot.Canonical {
		if row.Hash == canonical {
			return row, true
		}
	}
	return device.Canonical{}, false
}

func runMatrix(ctx context.Context, a, b *device.Android, mac *device.Mac) error {
	receivers := []device.Receiver{a, b, mac}
	if err := wait(ctx, "three reciprocal links", func() (bool, error) {
		for _, receiver := range receivers {
			s, err := receiver.Snapshot(ctx)
			if err != nil {
				return false, err
			}
			if len(s.Peers) != 2 {
				return false, nil
			}
		}
		return true, nil
	}); err != nil {
		return err
	}
	ms, err := mac.Snapshot(ctx)
	if err != nil {
		return err
	}
	if ms.Permission != "Allowed" || !ms.StorageOK {
		return errors.New("Mac notification permission and identity storage must be verified before the matrix")
	}
	// UUID-shaped tags are recognized by the debug source cancellation boundary.
	tagA := fmt.Sprintf("twinotify-e2e-%08x-1111-4111-8111-%012x", time.Now().Unix()&0xffffffff, time.Now().UnixNano()&0xffffffffffff)
	tagB := strings.Replace(tagA, "-1111-", "-2222-", 1)
	defer func() {
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
		defer cancel()
		_ = mac.PauseRelay(cleanup, false)
		_ = a.Cancel(cleanup, tagA)
		_ = b.Cancel(cleanup, tagB)
	}()
	var checks []string
	record := func(name string) { checks = append(checks, name); fmt.Fprintln(os.Stderr, "Passed:", name) }
	source := func(phone *device.Android, tag string, after int64) (device.Canonical, error) {
		var result device.Canonical
		err := wait(ctx, "source capture", func() (bool, error) {
			s, err := phone.Snapshot(ctx)
			if err != nil {
				return false, err
			}
			for _, row := range s.Canonical {
				if row.FixtureTagHash == hash(tag) && row.Sequence > after && row.State == "ACTIVE" {
					result = row
					return true, nil
				}
			}
			return false, nil
		})
		return result, err
	}
	converged := func(origin device.Canonical, state string, selected []device.Receiver, displayed bool) error {
		var last string
		err := wait(ctx, "canonical "+state, func() (bool, error) {
			for index, receiver := range selected {
				s, err := receiver.Snapshot(ctx)
				if err != nil {
					return false, err
				}
				row, ok := find(s, origin.Hash)
				if !ok || row.Sequence != origin.Sequence || row.Materialized != row.Sequence || row.State != state || row.Delivered != displayed {
					last = fmt.Sprintf("receiver=%d found=%t sequence=%d want=%d materialized=%d state=%s delivered=%t want_displayed=%t", index, ok, row.Sequence, origin.Sequence, row.Materialized, row.State, row.Delivered, displayed)
					return false, nil
				}
			}
			return true, nil
		})
		if err != nil {
			return fmt.Errorf("%w (%s)", err, last)
		}
		return nil
	}
	if err := a.Post(ctx, tagA, "Synthetic first message"); err != nil {
		return err
	}
	current, err := source(a, tagA, 0)
	if err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", receivers, true); err != nil {
		return err
	}
	record("A post reaches B and Mac")
	if err := a.Post(ctx, tagA, "Synthetic updated message"); err != nil {
		return err
	}
	current, err = source(a, tagA, current.Sequence)
	if err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", receivers, true); err != nil {
		return err
	}
	record("update replaces the same canonical entry")
	if err := mac.DismissLocal(ctx, current.Hash); err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", []device.Receiver{mac}, false); err != nil {
		return err
	}
	before, err := mac.Snapshot(ctx)
	if err != nil {
		return err
	}
	if err := a.Repair(ctx); err != nil {
		return err
	}
	if err := wait(ctx, "snapshot after local dismissal", func() (bool, error) {
		s, err := mac.Snapshot(ctx)
		return s.SnapshotCommits > before.SnapshotCommits, err
	}); err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", []device.Receiver{mac}, false); err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", []device.Receiver{a, b}, true); err != nil {
		return err
	}
	record("Mac dismissal stays local through snapshot repair")
	// Three revisions within 15 seconds intentionally trigger Android's local
	// repeat protection. Keep this delivery scenario outside that policy window.
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(16 * time.Second):
	}
	if err := mac.PauseRelay(ctx, true); err != nil {
		return err
	}
	if err := a.Post(ctx, tagA, "Synthetic offline update"); err != nil {
		return err
	}
	current, err = source(a, tagA, current.Sequence)
	if err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", []device.Receiver{a, b}, true); err != nil {
		return err
	}
	if err := mac.PauseRelay(ctx, false); err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", receivers, true); err != nil {
		return err
	}
	record("offline Mac catches up without delaying phone delivery")
	if err := b.DismissMirror(ctx); err != nil {
		return err
	}
	if err := wait(ctx, "origin-authored dismissal", func() (bool, error) {
		s, err := a.Snapshot(ctx)
		if err != nil {
			return false, err
		}
		row, ok := find(s, current.Hash)
		if ok && row.State == "CANCELLED" && row.Sequence > current.Sequence {
			current = row
			return true, nil
		}
		return false, nil
	}); err != nil {
		return err
	}
	if err := converged(current, "CANCELLED", receivers, false); err != nil {
		return err
	}
	record("phone mirror dismissal converges through the origin")
	if err := b.Post(ctx, tagB, "Synthetic second-phone message"); err != nil {
		return err
	}
	fromB, err := source(b, tagB, 0)
	if err != nil {
		return err
	}
	if err := converged(fromB, "ACTIVE", receivers, true); err != nil {
		return err
	}
	if err := b.Cancel(ctx, tagB); err != nil {
		return err
	}
	if err := wait(ctx, "B source cancellation", func() (bool, error) {
		s, err := b.Snapshot(ctx)
		if err != nil {
			return false, err
		}
		row, ok := find(s, fromB.Hash)
		if ok && row.State == "CANCELLED" && row.Sequence > fromB.Sequence {
			fromB = row
			return true, nil
		}
		return false, nil
	}); err != nil {
		return err
	}
	if err := converged(fromB, "CANCELLED", receivers, false); err != nil {
		return err
	}
	record("B source post and cancel reach both recipients")
	// Independent origins publish concurrently; each recipient must retain both
	// canonical identities and their subsequent revisions.
	for revision := 0; revision < 2; revision++ {
		results := make(chan error, 2)
		go func() { results <- a.Post(ctx, tagA, fmt.Sprintf("Synthetic concurrent A %d", revision)) }()
		go func() { results <- b.Post(ctx, tagB, fmt.Sprintf("Synthetic concurrent B %d", revision)) }()
		for range 2 {
			if err := <-results; err != nil {
				return err
			}
		}
		current, err = source(a, tagA, current.Sequence)
		if err != nil {
			return err
		}
		fromB, err = source(b, tagB, fromB.Sequence)
		if err != nil {
			return err
		}
		for _, origin := range []device.Canonical{current, fromB} {
			if err := converged(origin, "ACTIVE", receivers, true); err != nil {
				return err
			}
		}
		// Respect the receiver's repeat-protection policy across revisions.
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(16 * time.Second):
		}
	}
	record("concurrent origin posts and updates retain both canonical entries")
	// Removing A↔Mac must leave A↔B and B↔Mac able to deliver.
	as, err := a.Snapshot(ctx)
	if err != nil {
		return err
	}
	ms, err = mac.Snapshot(ctx)
	if err != nil {
		return err
	}
	var aMac, macA string
	for _, peer := range as.Peers {
		if peer.DeviceHash == ms.DeviceHash {
			aMac = peer.LinkID
		}
	}
	for _, peer := range ms.Peers {
		if peer.DeviceHash == as.DeviceHash {
			macA = peer.LinkID
		}
	}
	if aMac == "" || macA == "" {
		return errors.New("selected removal links missing")
	}
	if err := mac.PauseRelay(ctx, true); err != nil {
		return err
	}
	if err := a.Post(ctx, tagA, "Synthetic traffic pending during removal"); err != nil {
		return err
	}
	current, err = source(a, tagA, current.Sequence)
	if err != nil {
		return err
	}
	if err := converged(current, "ACTIVE", []device.Receiver{a, b}, true); err != nil {
		return err
	}
	if err := a.RemovePeer(ctx, aMac); err != nil {
		return err
	}
	if err := mac.PauseRelay(ctx, false); err != nil {
		return err
	}
	if err := mac.RemovePeer(ctx, macA); err != nil {
		return err
	}
	if err := wait(ctx, "scoped removal", func() (bool, error) {
		aa, err := a.Snapshot(ctx)
		if err != nil {
			return false, err
		}
		mm, err := mac.Snapshot(ctx)
		return aa.DeviceHash == as.DeviceHash && mm.DeviceHash == ms.DeviceHash && len(aa.Peers) == 1 && len(mm.Peers) == 1, err
	}); err != nil {
		return err
	}
	if err := b.Post(ctx, tagB, "Synthetic surviving links"); err != nil {
		return err
	}
	fromB, err = source(b, tagB, fromB.Sequence)
	if err != nil {
		return err
	}
	if err := converged(fromB, "ACTIVE", receivers, true); err != nil {
		return err
	}
	record("offline removal with queued traffic preserves identity and both surviving links")
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"result": "pass", "checks": checks,
		"unverified": []string{"signed permission denial/recovery", "sleep/wake", "process interruption", "large interrupted snapshots", "physical-phone direct routes"}})
}
