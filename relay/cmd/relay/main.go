package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/phonesync/relay/internal/server"
	"github.com/phonesync/relay/internal/store"
)

func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	boltPath := os.Getenv("BOLT_PATH")
	if boltPath == "" {
		boltPath = "/tmp/phone-sync-relay.db"
	}
	b, err := store.OpenBolt(boltPath)
	if err != nil {
		log.Fatalf("open bolt: %v", err)
	}
	defer b.Close()

	srv := &http.Server{Addr: addr, Handler: server.NewWithStore(b).Handler()}

	done := make(chan struct{})
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			log.Printf("shutdown: %v", err)
		}
		close(done)
	}()

	log.Printf("relay listening on %s", addr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
	<-done
}
