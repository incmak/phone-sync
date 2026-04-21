package server

import "testing"

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

func TestValidateEnvelope_EncType_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	// ValidateEnvelope accepts "enc" as a valid type string (packet schema enum widened).
	good := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEnvelope(good); err != nil {
		t.Fatalf("expected valid enc envelope in packet schema, got %v", err)
	}
}

func TestValidateEncEnvelope_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	// 32 zero base64 chars = 24 zero bytes (valid nonce length per spec §4.6).
	good := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncEnvelope(good); err != nil {
		t.Fatalf("expected valid enc envelope, got %v", err)
	}
}

func TestValidateEncEnvelope_MissingNonce(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	bad := []byte(`{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncEnvelope(bad); err == nil {
		t.Fatal("expected error for missing nonce, got nil")
	}
}

func TestValidateEncEnvelope_InvalidType(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	// type must be exactly "enc" per envelope-encrypted schema
	bad := []byte(`{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`)
	if err := v.ValidateEncEnvelope(bad); err == nil {
		t.Fatal("expected error for invalid type value, got nil")
	}
}

func TestValidateAck_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	good := []byte(`{"canon_id":"devA:com.example:42:","status":"delivered"}`)
	if err := v.ValidateAck(good); err != nil {
		t.Fatalf("expected valid ack, got %v", err)
	}
}

func TestValidateAck_DecryptFailed_Valid(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	good := []byte(`{"canon_id":"devA:com.example:42:","status":"decrypt_failed"}`)
	if err := v.ValidateAck(good); err != nil {
		t.Fatalf("expected valid, got %v", err)
	}
}

func TestValidateAck_InvalidStatus(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	bad := []byte(`{"canon_id":"devA:com.example:42:","status":"unknown"}`)
	if err := v.ValidateAck(bad); err == nil {
		t.Fatal("expected error for invalid status, got nil")
	}
}

func TestValidateAck_MissingCanonID(t *testing.T) {
	v, err := NewValidator()
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	bad := []byte(`{"status":"delivered"}`)
	if err := v.ValidateAck(bad); err == nil {
		t.Fatal("expected error for missing canon_id, got nil")
	}
}
