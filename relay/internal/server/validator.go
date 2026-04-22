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
	envelope *jsonschema.Schema
	enc      *jsonschema.Schema // encrypted outer envelope
	ack      *jsonschema.Schema // inner ack cleartext shape
}

// schemaBaseURL must match the $id prefix used in /proto/*.schema.json.
const schemaBaseURL = "https://twinotify.app/schemas/"

// NewValidator compiles the packet envelope schema from the embedded FS.
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

	env, err := compiler.Compile(schemaBaseURL + "packet.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile envelope: %w", err)
	}
	enc, err := compiler.Compile(schemaBaseURL + "envelope-encrypted.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile enc envelope: %w", err)
	}
	ack, err := compiler.Compile(schemaBaseURL + "ack.schema.json")
	if err != nil {
		return nil, fmt.Errorf("compile ack: %w", err)
	}
	return &Validator{envelope: env, enc: enc, ack: ack}, nil
}

// ValidateEnvelope unmarshals raw JSON and validates it against the packet schema.
// Returns a non-nil error for malformed JSON or schema violations.
func (v *Validator) ValidateEnvelope(raw []byte) error {
	var doc any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return v.envelope.Validate(doc)
}

// ValidateEncEnvelope validates raw JSON against the encrypted outer envelope schema
// (envelope-encrypted.schema.json). Call this when type=="enc" is confirmed.
func (v *Validator) ValidateEncEnvelope(raw []byte) error {
	var doc any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return v.enc.Validate(doc)
}

// ValidateAck validates raw JSON against the ack inner cleartext schema (ack.schema.json).
// Call this on the decrypted payload when the outer type=="ack".
func (v *Validator) ValidateAck(raw []byte) error {
	var doc any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse: %w", err)
	}
	return v.ack.Validate(doc)
}
