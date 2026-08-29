package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/relay/internal/store"
)

func TestBackupRetentionKeepsNewestValidatedSnapshots(t *testing.T) {
	directory := t.TempDir()
	bolt, err := store.OpenBolt(filepath.Join(directory, "relay.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer bolt.Close()
	backupDirectory := filepath.Join(directory, "backups")
	if err := os.Mkdir(backupDirectory, 0700); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 8, 29, 10, 0, 0, 0, time.UTC)
	manager, err := newBackupManager(bolt, backupDirectory, time.Hour, 2, "relay/v1 unsafe", func() time.Time {
		now = now.Add(time.Second)
		return now
	})
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 3; index++ {
		if err := bolt.Put("state", "value", []byte{byte(index)}); err != nil {
			t.Fatal(err)
		}
		if _, err := manager.snapshot(); err != nil {
			t.Fatal(err)
		}
	}
	entries, err := os.ReadDir(backupDirectory)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("retained backup count = %d, want 2", len(entries))
	}
	for _, entry := range entries {
		if strings.Contains(entry.Name(), "/") || !strings.Contains(entry.Name(), "relay_v1_unsafe") {
			t.Fatalf("unsafe or unsanitized snapshot name %q", entry.Name())
		}
		if err := store.ValidateBolt(filepath.Join(backupDirectory, entry.Name())); err != nil {
			t.Fatalf("retained snapshot %q: %v", entry.Name(), err)
		}
	}
}

func TestBackupManagerRunsImmediatelyAndJoinsAfterCancellation(t *testing.T) {
	root := t.TempDir()
	bolt, err := store.OpenBolt(filepath.Join(root, "relay.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer bolt.Close()
	backupDirectory := filepath.Join(root, "backups")
	manager, err := newBackupManager(bolt, backupDirectory, time.Hour, 2, "test", time.Now)
	if err != nil {
		t.Fatal(err)
	}
	observed := make(chan error, 1)
	manager.observe = func(err error) { observed <- err }
	ctx, cancel := context.WithCancel(context.Background())
	done := manager.Run(ctx)
	select {
	case err := <-observed:
		if err != nil {
			t.Fatalf("initial scheduled backup: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("backup manager did not take an immediate snapshot")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("backup manager did not stop after cancellation")
	}
}

func TestOfflineBackupCommandCreatesValidatedSnapshot(t *testing.T) {
	root := t.TempDir()
	source := createSnapshotFixture(t, filepath.Join(root, "relay.db"), "offline")
	backupDirectory := filepath.Join(root, "backups")
	if err := runBackupCommand([]string{
		"--from", source, "--to-dir", backupDirectory, "--retention", "2",
	}, func() time.Time { return time.Date(2026, 8, 29, 13, 0, 0, 0, time.UTC) }); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(backupDirectory)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("offline backup count = %d, want 1", len(entries))
	}
	if err := store.ValidateBolt(filepath.Join(backupDirectory, entries[0].Name())); err != nil {
		t.Fatal(err)
	}
}

func TestOfflineBackupCommandAllowsOnlyAnExplicitlyMissingSource(t *testing.T) {
	root := t.TempDir()
	missing := filepath.Join(root, "data", "relay.db")
	backupDirectory := filepath.Join(root, "backups")
	now := func() time.Time { return time.Date(2026, 8, 29, 13, 0, 0, 0, time.UTC) }

	arguments := []string{
		"--from", missing, "--to-dir", backupDirectory, "--retention", "2",
	}
	if err := runBackupCommand(arguments, now); err == nil {
		t.Fatal("missing source was accepted without --allow-missing")
	}
	if err := runBackupCommand(append(arguments, "--allow-missing"), now); err != nil {
		t.Fatalf("explicitly allowed missing source: %v", err)
	}

	if err := os.MkdirAll(filepath.Dir(missing), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(missing, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := runBackupCommand(append(arguments, "--allow-missing"), now); err == nil {
		t.Fatal("corrupt source was accepted by --allow-missing")
	}
}

func TestRestoreRejectsUnsafeCorruptOrLockedInputsWithoutMutation(t *testing.T) {
	root := t.TempDir()
	backupDirectory := filepath.Join(root, "backups")
	dataDirectory := filepath.Join(root, "data")
	if err := os.MkdirAll(backupDirectory, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dataDirectory, 0700); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(dataDirectory, "relay.db")
	current, err := store.OpenBolt(destination)
	if err != nil {
		t.Fatal(err)
	}
	if err := current.Put("state", "value", []byte("current")); err != nil {
		t.Fatal(err)
	}
	if err := current.Close(); err != nil {
		t.Fatal(err)
	}
	validSource := createSnapshotFixture(t, filepath.Join(backupDirectory, "valid.db"), "replacement")
	corruptSource := filepath.Join(backupDirectory, "corrupt.db")
	if err := os.WriteFile(corruptSource, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	symlinkSource := filepath.Join(backupDirectory, "symlink.db")
	if err := os.Symlink(validSource, symlinkSource); err != nil {
		t.Fatal(err)
	}

	tests := map[string]restoreConfig{
		"source outside backup root": {
			Source: createSnapshotFixture(t, filepath.Join(root, "outside.db"), "outside"), Destination: destination,
			BackupDir: backupDirectory, DataDir: dataDirectory,
		},
		"destination outside data root": {
			Source: validSource, Destination: filepath.Join(root, "outside-destination.db"),
			BackupDir: backupDirectory, DataDir: dataDirectory,
		},
		"corrupt source": {
			Source: corruptSource, Destination: destination, BackupDir: backupDirectory, DataDir: dataDirectory,
		},
		"symlink source": {
			Source: symlinkSource, Destination: destination, BackupDir: backupDirectory, DataDir: dataDirectory,
		},
	}
	for name, config := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := restoreBolt(config); err == nil {
				t.Fatal("unsafe restore was accepted")
			}
			assertBoltValue(t, destination, "current")
		})
	}

	locked, err := store.OpenBolt(destination)
	if err != nil {
		t.Fatal(err)
	}
	defer locked.Close()
	if _, err := restoreBolt(restoreConfig{
		Source: validSource, Destination: destination, BackupDir: backupDirectory, DataDir: dataDirectory,
	}); err == nil {
		t.Fatal("restore proceeded while destination database was locked")
	}
	value, err := locked.Get("state", "value")
	if err != nil || string(value) != "current" {
		t.Fatalf("locked destination changed to %q, %v", value, err)
	}
}

func TestRestoreAtomicallyKeepsRecoveryCopy(t *testing.T) {
	root := t.TempDir()
	backupDirectory := filepath.Join(root, "backups")
	dataDirectory := filepath.Join(root, "data")
	if err := os.MkdirAll(backupDirectory, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dataDirectory, 0700); err != nil {
		t.Fatal(err)
	}
	destination := createSnapshotFixture(t, filepath.Join(dataDirectory, "relay.db"), "current")
	source := createSnapshotFixture(t, filepath.Join(backupDirectory, "replacement.db"), "replacement")
	recovery, err := restoreBolt(restoreConfig{
		Source: source, Destination: destination, BackupDir: backupDirectory, DataDir: dataDirectory,
		Now: func() time.Time { return time.Date(2026, 8, 29, 12, 34, 56, 0, time.UTC) },
	})
	if err != nil {
		t.Fatal(err)
	}
	if recovery == "" || !strings.Contains(filepath.Base(recovery), ".recovery-20260829T123456") {
		t.Fatalf("recovery path = %q", recovery)
	}
	assertBoltValue(t, destination, "replacement")
	assertBoltValue(t, recovery, "current")
	if mode := fileMode(t, destination); mode.Perm() != 0600 {
		t.Fatalf("restored mode = %o, want 600", mode.Perm())
	}
}

func createSnapshotFixture(t *testing.T, path, value string) string {
	t.Helper()
	bolt, err := store.OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := bolt.Put("state", "value", []byte(value)); err != nil {
		t.Fatal(err)
	}
	if err := bolt.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func assertBoltValue(t *testing.T, path, want string) {
	t.Helper()
	bolt, err := store.OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	defer bolt.Close()
	value, err := bolt.Get("state", "value")
	if err != nil || string(value) != want {
		t.Fatalf("%s state = %q, %v; want %q", path, value, err, want)
	}
}

func fileMode(t *testing.T, path string) os.FileMode {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.Mode()
}
