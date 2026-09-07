package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestPairingProofBindsBothKeysAndRejectsMalformedKeys(t *testing.T) {
	enc := base64.StdEncoding.EncodeToString(make([]byte, 32))
	sign := base64.StdEncoding.EncodeToString([]byte(strings.Repeat("s", 32)))
	a, err := fingerprint(enc, sign)
	if err != nil || len(a) != 64 {
		t.Fatal("valid fingerprint rejected")
	}
	b, _ := fingerprint(sign, enc)
	if a == b {
		t.Fatal("key roles are not bound")
	}
	if _, err := fingerprint("bad", sign); err == nil {
		t.Fatal("malformed key accepted")
	}
	if _, err := fingerprint(enc, base64.StdEncoding.EncodeToString(make([]byte, 31))); err == nil {
		t.Fatal("short signing key accepted")
	}
}
