package server

import (
	"embed"
	"encoding/json"
	"fmt"
	"path"
	"strings"
	"testing"
)

//go:embed fixtures/*/*.json fixtures/manifest.json
var protocolFixtureFS embed.FS

type protocolFixtureManifest struct {
	Fixtures []protocolFixture `json:"fixtures"`
}

type protocolFixture struct {
	File         string `json:"file"`
	Type         string `json:"type"`
	Valid        bool   `json:"valid,omitempty"`
	ExpectedCode string `json:"expected_code,omitempty"`
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

	validator, err := NewValidator()
	if err != nil {
		t.Fatalf("new validator: %v", err)
	}

	seen := make(map[string]struct{}, len(manifest.Fixtures))
	for _, fixture := range manifest.Fixtures {
		fixture := fixture
		t.Run(fixture.File, func(t *testing.T) {
			if fixture.File == "" || path.IsAbs(fixture.File) || path.Clean(fixture.File) != fixture.File || strings.HasPrefix(fixture.File, ".") {
				t.Fatalf("unsafe fixture path %q", fixture.File)
			}
			if _, duplicate := seen[fixture.File]; duplicate {
				t.Fatalf("duplicate fixture manifest entry %q", fixture.File)
			}
			seen[fixture.File] = struct{}{}

			if fixture.Valid == (fixture.ExpectedCode != "") {
				t.Fatal("fixture must declare exactly one of valid=true or expected_code")
			}
			if !isProtocolFixtureType(fixture.Type) {
				t.Fatalf("unknown fixture type %q", fixture.Type)
			}

			raw, err := protocolFixtureFS.ReadFile("fixtures/" + fixture.File)
			if err != nil {
				t.Fatalf("read fixture: %v", err)
			}

			validationErr := validateProtocolFixture(validator, fixture.Type, raw)
			if fixture.Valid {
				if validationErr != nil {
					t.Fatalf("expected valid fixture: %v", validationErr)
				}
				return
			}
			if validationErr == nil {
				t.Fatalf("expected stable failure code %q, fixture was accepted", fixture.ExpectedCode)
			}
			if fixture.ExpectedCode != "invalid_frame" {
				t.Fatalf("unsupported stable failure code %q", fixture.ExpectedCode)
			}
		})
	}
}

func isProtocolFixtureType(fixtureType string) bool {
	return fixtureType == "legacy_packet" || fixtureType == "encrypted_envelope" || fixtureType == "relay_control"
}

func validateProtocolFixture(validator *Validator, fixtureType string, raw []byte) error {
	switch fixtureType {
	case "legacy_packet":
		return validator.ValidateLegacyPacket(raw)
	case "encrypted_envelope":
		return validator.ValidateEncryptedEnvelope(raw)
	case "relay_control":
		return validator.ValidateRelayControl(raw)
	default:
		return fmt.Errorf("unknown fixture type %q", fixtureType)
	}
}
