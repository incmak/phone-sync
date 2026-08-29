package server

import (
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"path"
	"regexp"
	"sort"
	"strings"
	"testing"
	"testing/fstest"
)

//go:embed fixtures/*/*.json fixtures/manifest.json
var protocolFixtureFS embed.FS

type protocolFixtureManifest struct {
	Fixtures []protocolFixture `json:"fixtures"`
}

func validateFixtureInventory(fixtureFS fs.FS, manifest protocolFixtureManifest) error {
	embedded := make(map[string]struct{})
	if err := fs.WalkDir(fixtureFS, "fixtures", func(filePath string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || filePath == "fixtures/manifest.json" {
			return nil
		}
		fixturePath := strings.TrimPrefix(filePath, "fixtures/")
		if !validProtocolFixturePath(fixturePath) {
			return fmt.Errorf("embedded fixture outside allowed directories: %q", fixturePath)
		}
		embedded[fixturePath] = struct{}{}
		return nil
	}); err != nil {
		return fmt.Errorf("walk embedded fixtures: %w", err)
	}

	if len(manifest.Fixtures) == 0 {
		return fmt.Errorf("fixture manifest is empty")
	}
	seen := make(map[string]struct{}, len(manifest.Fixtures))
	for _, fixture := range manifest.Fixtures {
		if err := validateFixtureDeclaration(fixture); err != nil {
			return err
		}
		if _, duplicate := seen[fixture.File]; duplicate {
			return fmt.Errorf("duplicate fixture manifest path %q", fixture.File)
		}
		seen[fixture.File] = struct{}{}
		if _, exists := embedded[fixture.File]; !exists {
			return fmt.Errorf("fixture manifest path has no embedded payload: %q", fixture.File)
		}
		delete(embedded, fixture.File)
	}
	if len(embedded) != 0 {
		missing := make([]string, 0, len(embedded))
		for fixturePath := range embedded {
			missing = append(missing, fixturePath)
		}
		sort.Strings(missing)
		return fmt.Errorf("embedded fixtures missing from manifest: %v", missing)
	}
	return nil
}

func validProtocolFixturePath(fixturePath string) bool {
	if fixturePath == "" || path.IsAbs(fixturePath) || path.Clean(fixturePath) != fixturePath || strings.Contains(fixturePath, `\`) {
		return false
	}
	if path.Ext(fixturePath) != ".json" || strings.HasPrefix(path.Base(fixturePath), ".") {
		return false
	}
	return path.Dir(fixturePath) == "v2-valid" || path.Dir(fixturePath) == "v2-invalid"
}

func validateFixtureDeclaration(fixture protocolFixture) error {
	if !validProtocolFixturePath(fixture.File) {
		return fmt.Errorf("invalid fixture manifest path %q", fixture.File)
	}
	if fixture.Valid == (fixture.ExpectedCode != "") {
		return fmt.Errorf("fixture %q must declare exactly one outcome", fixture.File)
	}
	if fixture.Valid && path.Dir(fixture.File) != "v2-valid" {
		return fmt.Errorf("valid fixture %q must be in v2-valid", fixture.File)
	}
	if !fixture.Valid && path.Dir(fixture.File) != "v2-invalid" {
		return fmt.Errorf("invalid fixture %q must be in v2-invalid", fixture.File)
	}

	switch fixture.Scope {
	case "server":
		if fixture.Type != "legacy_packet" && fixture.Type != "encrypted_envelope" && fixture.Type != "relay_control" {
			return fmt.Errorf("unknown server fixture type %q", fixture.Type)
		}
		if !fixture.Valid && fixture.ExpectedCode != "invalid_frame" {
			return fmt.Errorf("unsupported server fixture code %q", fixture.ExpectedCode)
		}
	case "cross_layer":
		if fixture.Type != "peer_receipt_inner" && fixture.Type != "outer_inner_pair" && fixture.Type != "call_state" && fixture.Type != "notif_post_payload" && fixture.Type != "notif_action_invoke" && fixture.Type != "notif_action_result" {
			return fmt.Errorf("unknown cross-layer fixture type %q", fixture.Type)
		}
		if !fixture.Valid && fixture.Type == "outer_inner_pair" && fixture.ExpectedCode != "outer_inner_id_mismatch" {
			return fmt.Errorf("unsupported cross-layer fixture code %q", fixture.ExpectedCode)
		}
		if !fixture.Valid && (fixture.Type == "call_state" || fixture.Type == "notif_post_payload" || fixture.Type == "notif_action_invoke" || fixture.Type == "notif_action_result") && fixture.ExpectedCode != "invalid_frame" {
			return fmt.Errorf("unsupported cross-layer fixture code %q", fixture.ExpectedCode)
		}
	default:
		return fmt.Errorf("unknown fixture scope %q", fixture.Scope)
	}
	return nil
}

type protocolFixture struct {
	File         string `json:"file"`
	Scope        string `json:"scope"`
	Type         string `json:"type"`
	Valid        bool   `json:"valid,omitempty"`
	ExpectedCode string `json:"expected_code,omitempty"`
}

func TestProtocolFixturesRejectInventoryDrift(t *testing.T) {
	baseFS := fstest.MapFS{
		"fixtures/v2-valid/relay-put.json": {Data: []byte(`{"v":2}`)},
	}
	baseEntry := protocolFixture{
		File: "v2-valid/relay-put.json", Scope: "server", Type: "relay_control", Valid: true,
	}

	tests := []struct {
		name     string
		fs       fstest.MapFS
		fixtures []protocolFixture
	}{
		{
			name:     "manifest path missing",
			fs:       baseFS,
			fixtures: []protocolFixture{{Scope: "server", Type: "relay_control", Valid: true}},
		},
		{
			name:     "duplicate manifest path",
			fs:       baseFS,
			fixtures: []protocolFixture{baseEntry, baseEntry},
		},
		{
			name: "embedded payload missing from manifest",
			fs: fstest.MapFS{
				"fixtures/v2-valid/relay-put.json":  {Data: []byte(`{"v":2}`)},
				"fixtures/v2-valid/unlisted.json":   {Data: []byte(`{"v":2}`)},
				"fixtures/v2-invalid/negative.json": {Data: []byte(`{"v":2}`)},
			},
			fixtures: []protocolFixture{baseEntry},
		},
		{
			name: "orphan manifest path",
			fs:   baseFS,
			fixtures: []protocolFixture{{
				File: "v2-valid/missing.json", Scope: "server", Type: "relay_control", Valid: true,
			}},
		},
		{
			name: "unknown scope",
			fs:   baseFS,
			fixtures: []protocolFixture{{
				File: baseEntry.File, Scope: "android", Type: "relay_control", Valid: true,
			}},
		},
		{
			name: "unknown type for recognized scope",
			fs:   baseFS,
			fixtures: []protocolFixture{{
				File: baseEntry.File, Scope: "server", Type: "inner_event", Valid: true,
			}},
		},
		{
			name: "fixture path outside allowed directories",
			fs:   baseFS,
			fixtures: []protocolFixture{{
				File: "../v2-valid/relay-put.json", Scope: "server", Type: "relay_control", Valid: true,
			}},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			manifest := protocolFixtureManifest{Fixtures: test.fixtures}
			if err := validateFixtureInventory(test.fs, manifest); err == nil {
				t.Fatal("fixture inventory drift was accepted")
			}
		})
	}
}

func TestProtocolFixtures(t *testing.T) {
	manifestRaw, err := protocolFixtureFS.ReadFile("fixtures/manifest.json")
	if err != nil {
		t.Fatalf("read fixture manifest: %v", err)
	}

	var manifest protocolFixtureManifest
	if err := json.Unmarshal(manifestRaw, &manifest); err != nil {
		t.Fatalf("decode fixture manifest: %v", err)
	}
	if len(manifest.Fixtures) == 0 {
		t.Fatal("fixture manifest is empty")
	}
	if err := validateFixtureInventory(protocolFixtureFS, manifest); err != nil {
		t.Fatalf("fixture inventory: %v", err)
	}

	validator, err := NewValidator()
	if err != nil {
		t.Fatalf("new validator: %v", err)
	}

	for _, fixture := range manifest.Fixtures {
		fixture := fixture
		t.Run(fixture.File, func(t *testing.T) {
			raw, err := protocolFixtureFS.ReadFile("fixtures/" + fixture.File)
			if err != nil {
				t.Fatalf("read fixture: %v", err)
			}

			validationErr := validateProtocolFixture(validator, fixture, raw)
			if fixture.Valid {
				if validationErr != nil {
					t.Fatalf("expected valid fixture: %v", validationErr)
				}
				return
			}
			if validationErr == nil {
				t.Fatalf("expected stable failure code %q, fixture was accepted", fixture.ExpectedCode)
			}
			if got := stableFixtureErrorCode(fixture.Scope, validationErr); got != fixture.ExpectedCode {
				t.Fatalf("stable failure code = %q, want %q", got, fixture.ExpectedCode)
			}
		})
	}
}

type fixtureCodeError string

func (e fixtureCodeError) Error() string { return string(e) }

func stableFixtureErrorCode(scope string, err error) string {
	var coded fixtureCodeError
	if errors.As(err, &coded) {
		return string(coded)
	}
	if scope == "server" {
		return "invalid_frame"
	}
	return "invalid_cross_layer_fixture"
}

func validateProtocolFixture(validator *Validator, fixture protocolFixture, raw []byte) error {
	if fixture.Scope == "cross_layer" {
		return validateCrossLayerFixture(validator, fixture.Type, raw)
	}
	switch fixture.Type {
	case "legacy_packet":
		return validator.ValidateLegacyPacket(raw)
	case "encrypted_envelope":
		return validator.ValidateEncryptedEnvelope(raw)
	case "relay_control":
		return validator.ValidateRelayControl(raw)
	default:
		return fmt.Errorf("unknown server fixture type %q", fixture.Type)
	}
}

func validateCrossLayerFixture(validator *Validator, fixtureType string, raw []byte) error {
	switch fixtureType {
	case "peer_receipt_inner":
		if err := validateJSON(validator.innerV2, raw); err != nil {
			return err
		}
		var inner struct {
			Type    string          `json:"type"`
			Payload json.RawMessage `json:"payload"`
		}
		if err := json.Unmarshal(raw, &inner); err != nil {
			return err
		}
		if inner.Type != "peer.receipt" {
			return fmt.Errorf("cross-layer inner type is not peer.receipt")
		}
		return validateJSON(validator.peerReceipt, inner.Payload)
	case "outer_inner_pair":
		var pair struct {
			Outer json.RawMessage `json:"outer"`
			Inner json.RawMessage `json:"inner"`
		}
		if err := json.Unmarshal(raw, &pair); err != nil {
			return err
		}
		if err := validator.ValidateEncryptedEnvelope(pair.Outer); err != nil {
			return err
		}
		if err := validateJSON(validator.innerV2, pair.Inner); err != nil {
			return err
		}
		var outer, inner struct {
			MsgID string `json:"msg_id"`
		}
		if err := json.Unmarshal(pair.Outer, &outer); err != nil {
			return err
		}
		if err := json.Unmarshal(pair.Inner, &inner); err != nil {
			return err
		}
		if outer.MsgID != inner.MsgID {
			return fixtureCodeError("outer_inner_id_mismatch")
		}
		return nil
	case "call_state":
		if err := validateJSON(validator.innerV2, raw); err != nil {
			return fixtureCodeError("invalid_frame")
		}
		var inner struct {
			Type    string `json:"type"`
			CanonID string `json:"canon_id"`
			Payload struct {
				SessionID string `json:"call_session_id"`
			} `json:"payload"`
		}
		if err := json.Unmarshal(raw, &inner); err != nil || inner.Type != "call.state" {
			return fixtureCodeError("invalid_frame")
		}
		if !callSessionIDPattern.MatchString(inner.Payload.SessionID) || inner.CanonID != "call:"+strings.ToLower(inner.Payload.SessionID) {
			return fixtureCodeError("invalid_frame")
		}
		return nil
	case "notif_post_payload":
		if err := validator.ValidateNotifPostPayload(raw); err != nil {
			return fixtureCodeError("invalid_frame")
		}
		return nil
	case "notif_action_invoke", "notif_action_result":
		if err := validateJSON(validator.innerV2, raw); err != nil {
			return fixtureCodeError("invalid_frame")
		}
		var inner struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(raw, &inner); err != nil {
			return fixtureCodeError("invalid_frame")
		}
		expectedType := map[string]string{
			"notif_action_invoke": "notif.action.invoke",
			"notif_action_result": "notif.action.result",
		}[fixtureType]
		if inner.Type != expectedType {
			return fixtureCodeError("invalid_frame")
		}
		return nil
	default:
		return fmt.Errorf("unknown cross-layer fixture type %q", fixtureType)
	}
}

var callSessionIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
