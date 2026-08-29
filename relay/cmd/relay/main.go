package main

import (
	"context"
	"errors"
	"fmt"
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
	if err := run(os.Args[1:], os.Getenv); err != nil {
		slog.Error("relay_command_failed")
		os.Exit(1)
	}
}

func run(arguments []string, getenv func(string) string) error {
	if len(arguments) > 0 {
		switch arguments[0] {
		case "backup":
			return runBackupCommand(arguments[1:], time.Now)
		case "restore":
			return runRestoreCommand(arguments[1:])
		case "healthcheck":
			return runHealthcheckCommand(arguments[1:], nil)
		default:
			return fmt.Errorf("unknown relay command %q", arguments[0])
		}
	}
	return runRelay(getenv)
}

func runRelay(getenv func(string) string) error {
	runtimeConfig, err := loadRuntimeConfig(getenv)
	if err != nil {
		return errors.New("invalid relay configuration")
	}
	b, err := store.OpenBolt(runtimeConfig.boltPath)
	if err != nil {
		return errors.New("open relay store")
	}
	defer func() { _ = b.Close() }()

	config := server.DefaultConfig()
	config.TrustProxyHeaders = runtimeConfig.trustProxyHeaders
	config.RequireMutualPairSignatures = runtimeConfig.requireMutualPairSignatures
	config.CapacityCheck = server.NewDiskCapacityCheck(runtimeConfig.boltPath, runtimeConfig.minFreeDiskBytes)
	config.BuildVersion = runtimeConfig.buildVersion
	app, err := server.NewWithConfigChecked(b, config)
	if err != nil {
		return errors.New("initialize relay server")
	}
	srv := server.NewHTTPServer(runtimeConfig.listenAddr, app.Handler())
	listener, err := net.Listen("tcp", runtimeConfig.listenAddr)
	if err != nil {
		return errors.New("open relay listener")
	}
	defer func() { _ = listener.Close() }()
	limitedListener, err := newLimitListener(listener, runtimeConfig.maxOpenConnections)
	if err != nil {
		return errors.New("limit relay listener")
	}
	backgroundContext, stopBackground := context.WithCancel(context.Background())
	maintenanceDone := app.StartMaintenance(backgroundContext)
	backupDone := closedDoneChannel()
	if runtimeConfig.backupDir != "" {
		manager, err := newBackupManager(
			b, runtimeConfig.backupDir, runtimeConfig.backupInterval, runtimeConfig.backupRetention,
			runtimeConfig.buildVersion, time.Now,
		)
		if err != nil {
			stopBackground()
			<-maintenanceDone
			return errors.New("initialize relay backups")
		}
		manager.observe = app.RecordBackupResult
		backupDone = manager.Run(backgroundContext)
	}
	backgroundDone := joinDoneChannels(maintenanceDone, backupDone)

	shutdownResult := make(chan error, 1)
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(signals)
	go func() {
		<-signals
		shutdownResult <- gracefulStop(app.BeginShutdown, stopBackground, backgroundDone, 10*time.Second, srv.Shutdown)
	}()

	slog.Info("relay_listening")
	if err := srv.Serve(limitedListener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		app.BeginShutdown()
		stopBackground()
		<-backgroundDone
		return errors.New("relay serve failed")
	}
	return <-shutdownResult
}

func gracefulStop(beginShutdown func(), stopMaintenance context.CancelFunc, backgroundDone <-chan struct{}, shutdownTimeout time.Duration, shutdown func(context.Context) error) error {
	beginShutdown()
	stopMaintenance()
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	shutdownDone := make(chan error, 1)
	go func() {
		shutdownDone <- shutdown(ctx)
	}()
	<-backgroundDone
	return <-shutdownDone
}

func closedDoneChannel() <-chan struct{} {
	done := make(chan struct{})
	close(done)
	return done
}

func joinDoneChannels(channels ...<-chan struct{}) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		for _, channel := range channels {
			<-channel
		}
	}()
	return done
}
