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
}

// schemaBaseURL must match the $id prefix used in /proto/*.schema.json.
const schemaBaseURL = "https://phone-sync.local/schemas/"

// NewValidator compiles the packet envelope schema from the embedded FS.
// All referenced schemas are registered first so intra-schema $ref resolution works.
func NewValidator() (*Validator, error) {
	compiler := jsonschema.NewCompiler()

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
	return &Validator{envelope: env}, nil
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
