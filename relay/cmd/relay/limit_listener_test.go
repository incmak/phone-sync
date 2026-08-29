package main

import (
	"net"
	"testing"
	"time"
)

func TestLimitListenerBlocksAcceptAboveLiveConnectionBudget(t *testing.T) {
	base, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	limited, err := newLimitListener(base, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer limited.Close()

	firstAccepted := acceptAsync(limited)
	firstClient, err := net.Dial("tcp", limited.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer firstClient.Close()
	firstServer := awaitAccepted(t, firstAccepted)

	secondAccepted := acceptAsync(limited)
	secondClient, err := net.Dial("tcp", limited.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer secondClient.Close()
	select {
	case result := <-secondAccepted:
		if result.connection != nil {
			_ = result.connection.Close()
		}
		t.Fatal("listener accepted a second live connection above its budget")
	case <-time.After(100 * time.Millisecond):
	}

	if err := firstServer.Close(); err != nil {
		t.Fatal(err)
	}
	secondServer := awaitAccepted(t, secondAccepted)
	if err := secondServer.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestLimitListenerRejectsInvalidBudget(t *testing.T) {
	base, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer base.Close()
	if _, err := newLimitListener(base, 0); err == nil {
		t.Fatal("zero connection budget was accepted")
	}
}

type acceptResult struct {
	connection net.Conn
	err        error
}

func acceptAsync(listener net.Listener) <-chan acceptResult {
	result := make(chan acceptResult, 1)
	go func() {
		connection, err := listener.Accept()
		result <- acceptResult{connection: connection, err: err}
	}()
	return result
}

func awaitAccepted(t *testing.T, result <-chan acceptResult) net.Conn {
	t.Helper()
	select {
	case accepted := <-result:
		if accepted.err != nil {
			t.Fatal(accepted.err)
		}
		return accepted.connection
	case <-time.After(time.Second):
		t.Fatal("listener did not accept after capacity became available")
		return nil
	}
}
