package server

import (
	"sync"
	"testing"
)

func TestClientHubConcurrentReplaceAndSend(t *testing.T) {
	hub := NewClientHub()
	start := make(chan struct{})

	var wg sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for n := 0; n < 1000; n++ {
				client := hub.Register("dev", make(chan []byte, 1))
				hub.Send("dev", []byte("x"))
				hub.Unregister(client)
			}
		}()
	}

	close(start)
	wg.Wait()
}

func TestPairHubConcurrentReplaceAndPush(t *testing.T) {
	hub := NewPairHub()
	start := make(chan struct{})

	var wg sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for n := 0; n < 1000; n++ {
				ch := hub.Subscribe("pair-token", "A")
				hub.Push("pair-token", "A", []byte("x"))
				hub.Unsubscribe("pair-token", "A", ch)
			}
		}()
	}

	close(start)
	wg.Wait()
}

func TestClientHubSendCopiesFrame(t *testing.T) {
	hub := NewClientHub()
	client := hub.Register("dev", make(chan []byte, 1))
	frame := []byte("x")

	if !hub.Send("dev", frame) {
		t.Fatal("Send returned false")
	}
	frame[0] = 'y'

	if got := string(<-client.outbound); got != "x" {
		t.Fatalf("queued frame = %q, want %q", got, "x")
	}
}

func TestPairHubPushCopiesFrame(t *testing.T) {
	hub := NewPairHub()
	subscription := hub.Subscribe("pair-token", "A")
	frame := []byte("x")

	if !hub.Push("pair-token", "A", frame) {
		t.Fatal("Push returned false")
	}
	frame[0] = 'y'

	if got := string(<-subscription.outbound); got != "x" {
		t.Fatalf("queued frame = %q, want %q", got, "x")
	}
}
