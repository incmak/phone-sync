package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/twinotify/relay/internal/server"
	"github.com/twinotify/relay/internal/store"
)

func main() {
	slog.SetDefault(slog.New(newLogHandler(os.Stderr, os.Getenv("TWINOTIFY_ENV") == productionEnvironment)))
	runtimeConfig, err := loadRuntimeConfig(os.Getenv)
	if err != nil {
		slog.Error("relay_start_failed", "stage", "config")
		os.Exit(1)
	}
	b, err := store.OpenBolt(runtimeConfig.boltPath)
	if err != nil {
		slog.Error("relay_start_failed", "stage", "store")
		os.Exit(1)
	}
	defer b.Close()

	config := server.DefaultConfig()
	config.TrustProxyHeaders = runtimeConfig.trustProxyHeaders
	config.RequireMutualPairSignatures = runtimeConfig.requireMutualPairSignatures
	config.CapacityCheck = server.NewDiskCapacityCheck(runtimeConfig.boltPath, runtimeConfig.minFreeDiskBytes)
	config.BuildVersion = runtimeConfig.buildVersion
	app, err := server.NewWithConfigChecked(b, config)
	if err != nil {
		slog.Error("relay_start_failed", "stage", "server")
		os.Exit(1)
	}
	srv := server.NewHTTPServer(runtimeConfig.listenAddr, app.Handler())
	listener, err := net.Listen("tcp", runtimeConfig.listenAddr)
	if err != nil {
		slog.Error("relay_start_failed", "stage", "listener")
		os.Exit(1)
	}
	limitedListener, err := newLimitListener(listener, runtimeConfig.maxOpenConnections)
	if err != nil {
		_ = listener.Close()
		slog.Error("relay_start_failed", "stage", "listener_limit")
		os.Exit(1)
	}
	maintenanceContext, stopMaintenance := context.WithCancel(context.Background())
	maintenanceDone := app.StartMaintenance(maintenanceContext)

	done := make(chan struct{})
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		if err := gracefulStop(app.BeginShutdown, stopMaintenance, maintenanceDone, 10*time.Second, srv.Shutdown); err != nil {
			slog.Error("relay_shutdown_failed")
		}
		close(done)
	}()

	slog.Info("relay_listening")
	if err := srv.Serve(limitedListener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("relay_serve_failed")
		os.Exit(1)
	}
	<-done
}

func gracefulStop(beginShutdown func(), stopMaintenance context.CancelFunc, maintenanceDone <-chan struct{}, shutdownTimeout time.Duration, shutdown func(context.Context) error) error {
	beginShutdown()
	stopMaintenance()
	<-maintenanceDone
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	return shutdown(ctx)
}
