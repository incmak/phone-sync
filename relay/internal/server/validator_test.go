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
	v, _ := NewValidator()
	bad := []byte(`{"v":2,"type":"ping","msg_id":"x","origin_device":"","ts":"oops"}`)
	if err := v.ValidateEnvelope(bad); err == nil {
		t.Fatal("expected invalid, got nil")
	}
}
