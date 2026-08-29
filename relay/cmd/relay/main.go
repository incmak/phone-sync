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

	"github.com/twinotify/relay/internal/server"
	"github.com/twinotify/relay/internal/store"
)

func main() {
	runtimeConfig, err := loadRuntimeConfig(os.Getenv)
	if err != nil {
		log.Fatalf("load relay config: %v", err)
	}
	b, err := store.OpenBolt(runtimeConfig.boltPath)
	if err != nil {
		log.Fatalf("open bolt: %v", err)
	}
	defer b.Close()

	config := server.DefaultConfig()
	config.TrustProxyHeaders = runtimeConfig.trustProxyHeaders
	config.RequireMutualPairSignatures = runtimeConfig.requireMutualPairSignatures
	config.CapacityCheck = server.NewDiskCapacityCheck(runtimeConfig.boltPath, runtimeConfig.minFreeDiskBytes)
	config.BuildVersion = runtimeConfig.buildVersion
	app, err := server.NewWithConfigChecked(b, config)
	if err != nil {
		log.Fatalf("initialize relay: %v", err)
	}
	srv := server.NewHTTPServer(runtimeConfig.listenAddr, app.Handler())
	maintenanceContext, stopMaintenance := context.WithCancel(context.Background())
	maintenanceDone := app.StartMaintenance(maintenanceContext)

	done := make(chan struct{})
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		if err := gracefulStop(stopMaintenance, maintenanceDone, 10*time.Second, srv.Shutdown); err != nil {
			log.Printf("shutdown: %v", err)
		}
		close(done)
	}()

	log.Printf("relay listening on %s", runtimeConfig.listenAddr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
	<-done
}

func gracefulStop(stopMaintenance context.CancelFunc, maintenanceDone <-chan struct{}, shutdownTimeout time.Duration, shutdown func(context.Context) error) error {
	stopMaintenance()
	<-maintenanceDone
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	return shutdown(ctx)
}
