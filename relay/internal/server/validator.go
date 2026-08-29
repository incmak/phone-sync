package server

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"strings"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

//go:embed schemas/*.schema.json
var schemaFS embed.FS

type Validator struct {
	legacyPacket *jsonschema.Schema
	encrypted    *jsonschema.Schema
	relayControl *jsonschema.Schema
	innerV2      *jsonschema.Schema
	peerReceipt  *jsonschema.Schema
	notifPost    *jsonschema.Schema
}

// schemaBaseURL must match the $id prefix used in /proto/*.schema.json.
const schemaBaseURL = "https://twinotify.app/schemas/"

// NewValidator compiles the v1 and v2 schemas from the embedded FS.
// All referenced schemas are registered first so intra-schema $ref resolution works.
func NewValidator() (*Validator, error) {
	compiler := jsonschema.NewCompiler()
	compiler.AssertFormat() // enforce format:"uuid" on msg_id — malformed UUIDs are rejected

	err := fs.WalkDir(schemaFS, "schemas", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, err := schemaFS.ReadFile(path)
		if err != nil {
			return err
		}
		var raw any
		if err := json.Unmarshal(b, &raw); err != nil {
			return fmt.Errorf("parse %s: %w", path, err)
		}
		filename := strings.TrimPrefix(path, "schemas/")
		return compiler.AddResource(schemaBaseURL+filename, raw)
	})
	if err != nil {
		return nil, err
	}

	legacyPacket, err := compiler.Compile(schemaBaseURL + "packet.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile legacy packet: %w", err)
	}
	encrypted, err := compiler.Compile(schemaBaseURL + "envelope-encrypted.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile encrypted envelope: %w", err)
	}
	relayControl, err := compiler.Compile(schemaBaseURL + "relay-control.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile relay control: %w", err)
	}
	innerV2, err := compiler.Compile(schemaBaseURL + "inner-event-v2.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile inner v2: %w", err)
	}
	peerReceipt, err := compiler.Compile(schemaBaseURL + "peer-receipt.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile peer receipt: %w", err)
	}
	notifPost, err := compiler.Compile(schemaBaseURL + "notif-post.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile notification post payload: %w", err)
	}

	return &Validator{
		legacyPacket: legacyPacket,
		encrypted:    encrypted,
		relayControl: relayControl,
		innerV2:      innerV2,
		peerReceipt:  peerReceipt,
		notifPost:    notifPost,
	}, nil
}

func validateJSON(schema *jsonschema.Schema, raw []byte) error {
	var doc any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return schema.Validate(doc)
}

func (v *Validator) ValidateLegacyPacket(raw []byte) error {
	return validateJSON(v.legacyPacket, raw)
}

func (v *Validator) ValidateEncryptedEnvelope(raw []byte) error {
	return validateJSON(v.encrypted, raw)
}

func (v *Validator) ValidateRelayControl(raw []byte) error {
	return validateJSON(v.relayControl, raw)
}

func (v *Validator) ValidateNotifPostPayload(raw []byte) error {
	return validateJSON(v.notifPost, raw)
}

// ValidateEnvelope preserves the v1 WebSocket call site until Task 4 introduces
// explicit legacy/v2 routing. It only admits v1 packets on that path, while
// continuing to route v1 encrypted envelopes through their dedicated schema.
func (v *Validator) ValidateEnvelope(raw []byte) error {
	var header struct {
		Version int    `json:"v"`
		Type    string `json:"type"`
	}
	if err := json.Unmarshal(raw, &header); err != nil {
		return fmt.Errorf("parse header: %w", err)
	}
	if header.Version != 1 {
		return fmt.Errorf("unsupported envelope version: %d", header.Version)
	}
	if header.Type == "enc" {
		return v.ValidateEncryptedEnvelope(raw)
	}
	return v.ValidateLegacyPacket(raw)
}
