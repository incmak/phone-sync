package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/twinotify/relay/internal/store"
	"go.etcd.io/bbolt"
)

const (
	backupNamePrefix = "twinotify-relay-"
	backupNameSuffix = ".db"
)

type backupManager struct {
	bolt      *store.Bolt
	directory string
	interval  time.Duration
	retention int
	version   string
	now       func() time.Time
	observe   func(error)
}

func newBackupManager(
	bolt *store.Bolt,
	directory string,
	interval time.Duration,
	retention int,
	version string,
	now func() time.Time,
) (*backupManager, error) {
	if bolt == nil || !filepath.IsAbs(directory) || interval <= 0 || retention <= 0 || now == nil {
		return nil, errors.New("invalid backup manager configuration")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(directory)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("backup directory must be a non-symlink directory")
	}
	return &backupManager{
		bolt: bolt, directory: filepath.Clean(directory), interval: interval, retention: retention,
		version: sanitizeVersion(version), now: now, observe: func(error) {},
	}, nil
}

func (m *backupManager) snapshot() (string, error) {
	timestamp := m.now().UTC().Format("20060102T150405.000000000Z")
	path := filepath.Join(m.directory, backupNamePrefix+timestamp+"-"+m.version+backupNameSuffix)
	if _, err := os.Lstat(path); err == nil {
		return "", errors.New("backup name collision")
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	if err := m.bolt.Snapshot(path); err != nil {
		return "", err
	}
	if err := pruneSnapshots(m.directory, m.retention); err != nil {
		return "", err
	}
	return path, nil
}

func (m *backupManager) Run(ctx context.Context) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		m.runSnapshot()
		ticker := time.NewTicker(m.interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				m.runSnapshot()
			}
		}
	}()
	return done
}

func (m *backupManager) runSnapshot() {
	_, err := m.snapshot()
	m.observe(err)
	if err != nil {
		slog.Error("backup_failed")
	}
}

func pruneSnapshots(directory string, retention int) error {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, backupNamePrefix) || !strings.HasSuffix(name, backupNameSuffix) {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			return errors.New("backup retention encountered a non-regular snapshot")
		}
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names[:max(0, len(names)-retention)] {
		if err := os.Remove(filepath.Join(directory, name)); err != nil {
			return err
		}
	}
	return syncDirectoryPath(directory)
}

func sanitizeVersion(version string) string {
	version = strings.TrimSpace(version)
	if version == "" {
		return "unknown"
	}
	var output strings.Builder
	for _, value := range version {
		if value <= unicode.MaxASCII && (unicode.IsLetter(value) || unicode.IsDigit(value) || value == '.' || value == '-' || value == '_') {
			output.WriteRune(value)
		} else {
			output.WriteByte('_')
		}
		if output.Len() >= 64 {
			break
		}
	}
	if output.Len() == 0 {
		return "unknown"
	}
	return output.String()
}

type restoreConfig struct {
	Source      string
	Destination string
	BackupDir   string
	DataDir     string
	Now         func() time.Time
}

func restoreBolt(config restoreConfig) (string, error) {
	if config.Now == nil {
		config.Now = time.Now
	}
	backupRoot, err := validateRoot(config.BackupDir)
	if err != nil {
		return "", err
	}
	dataRoot, err := validateRoot(config.DataDir)
	if err != nil {
		return "", err
	}
	if backupRoot == dataRoot {
		return "", errors.New("backup and data roots must differ")
	}
	source, err := validateContainedPath(config.Source, backupRoot, true)
	if err != nil {
		return "", fmt.Errorf("invalid restore source: %w", err)
	}
	destination, err := validateContainedPath(config.Destination, dataRoot, false)
	if err != nil {
		return "", fmt.Errorf("invalid restore destination: %w", err)
	}
	if source == destination {
		return "", errors.New("restore source and destination must differ")
	}
	if err := store.ValidateBolt(source); err != nil {
		return "", fmt.Errorf("validate restore source: %w", err)
	}
	if err := ensureBoltUnlocked(destination); err != nil {
		return "", err
	}

	temporary, err := os.CreateTemp(dataRoot, ".relay-restore-*.db")
	if err != nil {
		return "", err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := copySnapshot(temporary, source); err != nil {
		return "", err
	}
	if err := store.ValidateBolt(temporaryPath); err != nil {
		return "", fmt.Errorf("validate restore copy: %w", err)
	}

	recoveryPath := ""
	if _, err := os.Lstat(destination); err == nil {
		recoveryPath = destination + ".recovery-" + config.Now().UTC().Format("20060102T150405.000000000Z")
		if _, err := os.Lstat(recoveryPath); err == nil {
			return "", errors.New("restore recovery path already exists")
		} else if !errors.Is(err, os.ErrNotExist) {
			return "", err
		}
		if err := os.Rename(destination, recoveryPath); err != nil {
			return "", fmt.Errorf("preserve current database: %w", err)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}

	if err := os.Rename(temporaryPath, destination); err != nil {
		if recoveryPath != "" {
			_ = os.Rename(recoveryPath, destination)
		}
		return "", fmt.Errorf("install restored database: %w", err)
	}
	if err := syncDirectoryPath(dataRoot); err != nil {
		return recoveryPath, err
	}
	return recoveryPath, nil
}

func copySnapshot(destination *os.File, sourcePath string) error {
	closed := false
	defer func() {
		if !closed {
			_ = destination.Close()
		}
	}()
	sourceInfo, err := os.Lstat(sourcePath)
	if err != nil || sourceInfo.Mode()&os.ModeSymlink != 0 || !sourceInfo.Mode().IsRegular() {
		return errors.New("restore source changed or is not a regular file")
	}
	source, err := os.Open(sourcePath)
	if err != nil {
		return err
	}
	defer source.Close()
	if err := destination.Chmod(0600); err != nil {
		_ = destination.Close()
		return err
	}
	if _, err := io.Copy(destination, source); err != nil {
		_ = destination.Close()
		return err
	}
	if err := destination.Sync(); err != nil {
		_ = destination.Close()
		return err
	}
	err = destination.Close()
	closed = true
	return err
}

func validateRoot(root string) (string, error) {
	if !filepath.IsAbs(root) {
		return "", errors.New("root must be absolute")
	}
	root = filepath.Clean(root)
	info, err := os.Lstat(root)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", errors.New("root must be a non-symlink directory")
	}
	return root, nil
}

func validateContainedPath(path, root string, mustExist bool) (string, error) {
	if !filepath.IsAbs(path) {
		return "", errors.New("path must be absolute")
	}
	path = filepath.Clean(path)
	relative, err := filepath.Rel(root, path)
	if err != nil || relative == "." || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New("path is outside its allowed root")
	}
	current := root
	parts := strings.Split(relative, string(filepath.Separator))
	for index, part := range parts {
		current = filepath.Join(current, part)
		info, statErr := os.Lstat(current)
		if errors.Is(statErr, os.ErrNotExist) && !mustExist && index == len(parts)-1 {
			return path, nil
		}
		if statErr != nil {
			return "", statErr
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("symlinked paths are not allowed")
		}
		if index < len(parts)-1 && !info.IsDir() {
			return "", errors.New("path parent is not a directory")
		}
	}
	info, err := os.Lstat(path)
	if err != nil {
		if !mustExist && errors.Is(err, os.ErrNotExist) {
			return path, nil
		}
		return "", err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return "", errors.New("path must be a regular non-symlink file")
	}
	return path, nil
}

func ensureBoltUnlocked(path string) error {
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	database, err := bbolt.Open(path, 0600, &bbolt.Options{Timeout: 100 * time.Millisecond})
	if err != nil {
		return errors.New("restore destination is locked")
	}
	return database.Close()
}

func syncDirectoryPath(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

func runBackupCommand(arguments []string, now func() time.Time) error {
	flags := flag.NewFlagSet("backup", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	from := flags.String("from", "", "source Bolt path")
	toDirectory := flags.String("to-dir", "", "backup directory")
	retention := flags.Int("retention", 0, "snapshot retention count")
	if err := flags.Parse(arguments); err != nil || flags.NArg() != 0 {
		return errors.New("invalid backup arguments")
	}
	if *from == "" || *toDirectory == "" || *retention <= 0 {
		return errors.New("backup requires --from, --to-dir, and positive --retention")
	}
	info, err := os.Lstat(*from)
	if err != nil || info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return errors.New("backup source must be an existing non-symlink file")
	}
	bolt, err := store.OpenBolt(*from)
	if err != nil {
		return errors.New("backup source is locked or invalid")
	}
	defer bolt.Close()
	manager, err := newBackupManager(bolt, *toDirectory, time.Hour, *retention, "manual", now)
	if err != nil {
		return err
	}
	_, err = manager.snapshot()
	return err
}

func runRestoreCommand(arguments []string) error {
	flags := flag.NewFlagSet("restore", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	from := flags.String("from", "", "snapshot path")
	to := flags.String("to", "", "destination Bolt path")
	backupDirectory := flags.String("backup-dir", "", "allowed backup root")
	dataDirectory := flags.String("data-dir", "", "allowed data root")
	if err := flags.Parse(arguments); err != nil || flags.NArg() != 0 {
		return errors.New("invalid restore arguments")
	}
	if *from == "" || *to == "" || *backupDirectory == "" || *dataDirectory == "" {
		return errors.New("restore requires --from, --to, --backup-dir, and --data-dir")
	}
	_, err := restoreBolt(restoreConfig{
		Source: *from, Destination: *to, BackupDir: *backupDirectory, DataDir: *dataDirectory,
	})
	return err
}
