package server

import "testing"

func TestValidatorV2Contracts(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	validEnvelope := []byte(`{"v":2,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncryptedEnvelope(validEnvelope); err != nil {
		t.Fatalf("valid v2 envelope: %v", err)
	}
	validPut := []byte(`{"v":2,"type":"relay.put","envelope":{"v":2,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}}`)
	if err := v.ValidateRelayControl(validPut); err != nil {
		t.Fatalf("valid relay.put: %v", err)
	}
}

func TestValidatorRejectsUnknownOrMalformedRelayControl(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range [][]byte{
		[]byte(`{"v":2,"type":"relay.unknown"}`),
		[]byte(`{"v":2,"type":"relay.ack","msg_id":"not-a-uuid","envelope_sha256":"bad"}`),
	} {
		if err := v.ValidateRelayControl(raw); err == nil {
			t.Fatalf("expected rejection for %s", raw)
		}
	}
}

func TestValidateEnvelope_RoutesV1EncryptedEnvelope(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	validV1EncryptedEnvelope := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEnvelope(validV1EncryptedEnvelope); err != nil {
		t.Fatalf("valid v1 encrypted envelope: %v", err)
	}
}

func TestValidateEnvelope_RejectsV2RelayControlUntilExplicitRouting(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	v2RelayControl := []byte(`{"v":2,"type":"relay.hello","protocols":[1,2],"app_version":"1.0.0"}`)
	if err := v.ValidateEnvelope(v2RelayControl); err == nil {
		t.Fatal("expected v2 relay control to be rejected on the legacy WebSocket path")
	}
}

func TestValidateEnvelope_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	good := []byte(`{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000}`)
	if err := v.ValidateEnvelope(good); err != nil {
		t.Fatalf("expected valid, got %v", err)
	}
}

func TestValidateEnvelope_Invalid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	bad := []byte(`{"v":2,"type":"ping","msg_id":"x","origin_device":"","ts":"oops"}`)
	if err := v.ValidateEnvelope(bad); err == nil {
		t.Fatal("expected invalid, got nil")
	}
}

func TestValidateLegacyPacket_RejectsEncryptedEnvelope(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	good := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateLegacyPacket(good); err == nil {
		t.Fatal("expected encrypted envelope to be rejected by legacy packet schema")
	}
}

func TestValidateEncryptedEnvelope_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	// 32 zero base64 chars = 24 zero bytes (valid nonce length per spec §4.6).
	good := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncryptedEnvelope(good); err != nil {
		t.Fatalf("expected valid enc envelope, got %v", err)
	}
}

func TestValidateEncryptedEnvelope_MissingNonce(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	bad := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncryptedEnvelope(bad); err == nil {
		t.Fatal("expected error for missing nonce, got nil")
	}
}

func TestValidateEncryptedEnvelope_InvalidType(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	// type must be exactly "enc" per envelope-encrypted schema
	bad := []byte(`{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncryptedEnvelope(bad); err == nil {
		t.Fatal("expected error for invalid type value, got nil")
	}
}

func TestValidateEnvelope_BadUUIDFormat(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	bad := []byte(`{"v":1,"type":"ping","msg_id":"not-a-uuid","origin_device":"devA","ts":1713600000000}`)
	if err := v.ValidateEnvelope(bad); err == nil {
		t.Fatal("expected validation error on malformed msg_id UUID")
	}
}
