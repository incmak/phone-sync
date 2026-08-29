package server

import (
	"errors"
	"fmt"
	"net/http"
	"path/filepath"

	"golang.org/x/sys/unix"
)

var ErrServerCapacity = errors.New("server capacity unavailable")

const capacityRetryAfterSeconds = "30"

type CapacityCheck func() error

type statfsFunc func(string, *unix.Statfs_t) error

func NewDiskCapacityCheck(databasePath string, minimumFreeBytes uint64) CapacityCheck {
	return newDiskCapacityCheck(databasePath, minimumFreeBytes, unix.Statfs)
}

func newDiskCapacityCheck(databasePath string, minimumFreeBytes uint64, statfs statfsFunc) CapacityCheck {
	directory := filepath.Dir(databasePath)
	return func() error {
		if minimumFreeBytes == 0 {
			return nil
		}
		var status unix.Statfs_t
		if err := statfs(directory, &status); err != nil {
			return fmt.Errorf("%w: inspect filesystem: %v", ErrServerCapacity, err)
		}
		if status.Bsize <= 0 {
			return fmt.Errorf("%w: invalid filesystem block size", ErrServerCapacity)
		}
		blockSize := uint64(status.Bsize)
		requiredBlocks := minimumFreeBytes / blockSize
		if minimumFreeBytes%blockSize != 0 {
			requiredBlocks++
		}
		if uint64(status.Bavail) < requiredBlocks {
			return ErrServerCapacity
		}
		return nil
	}
}

func noCapacityLimit() error { return nil }

func (s *Server) requireStorageCapacity(w http.ResponseWriter) bool {
	if err := s.capacityCheck(); err == nil {
		return true
	}
	w.Header().Set("Retry-After", capacityRetryAfterSeconds)
	http.Error(w, "server capacity unavailable", http.StatusServiceUnavailable)
	return false
}

func (s *Server) rejectDuringShutdown(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.shuttingDown.Load() {
			writeShutdownUnavailable(w)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) acquireMutationAdmission() (func(), bool) {
	s.mutationMu.RLock()
	if s.shuttingDown.Load() {
		s.mutationMu.RUnlock()
		return nil, false
	}
	return s.mutationMu.RUnlock, true
}

func (s *Server) acquirePairMutation(w http.ResponseWriter, stage pairStage) (func(), bool) {
	if s.pairMutationBeforeStore != nil {
		s.pairMutationBeforeStore(stage)
	}
	release, admitted := s.acquireMutationAdmission()
	if !admitted {
		writeShutdownUnavailable(w)
	}
	return release, admitted
}

func writeShutdownUnavailable(w http.ResponseWriter) {
	w.Header().Set("Retry-After", "1")
	http.Error(w, "server shutting down", http.StatusServiceUnavailable)
}
