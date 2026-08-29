package main

import (
	"io"
	"log/slog"
)

func newLogHandler(writer io.Writer, production bool) slog.Handler {
	options := &slog.HandlerOptions{Level: slog.LevelInfo}
	if production {
		return slog.NewJSONHandler(writer, options)
	}
	return slog.NewTextHandler(writer, options)
}
