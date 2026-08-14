package adb

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"sort"
	"strings"
)

var (
	ErrDeviceOffline = errors.New("adb device offline")
	ErrNotFound      = errors.New("adb file not found")
)

// CommandRunner is intentionally argument-based so payloads never pass through a shell.
type CommandRunner interface {
	Run(context.Context, ...string) ([]byte, error)
}

type execRunner struct{ path string }

func (r execRunner) Run(ctx context.Context, args ...string) ([]byte, error) {
	return exec.CommandContext(ctx, r.path, args...).CombinedOutput()
}

type Client struct {
	runner CommandRunner
	serial string
}

func New(runner CommandRunner, serial string) *Client {
	if runner == nil {
		runner = execRunner{path: "adb"}
	}
	if strings.TrimSpace(serial) == "" {
		panic("adb serial is required")
	}
	return &Client{runner: runner, serial: serial}
}

func (c *Client) run(ctx context.Context, args ...string) ([]byte, error) {
	full := append([]string{"-s", c.serial}, args...)
	out, err := c.runner.Run(ctx, full...)
	if err == nil {
		return out, nil
	}
	if strings.Contains(strings.ToLower(string(out)), "offline") ||
		strings.Contains(strings.ToLower(string(out)), "no devices") {
		return nil, fmt.Errorf("%w: %s", ErrDeviceOffline, strings.TrimSpace(string(out)))
	}
	if strings.Contains(strings.ToLower(string(out)), "no such file") {
		return nil, fmt.Errorf("%w: %s", ErrNotFound, strings.TrimSpace(string(out)))
	}
	// Never include full arguments or command output here: broadcast extras can
	// contain install-scoped tokens, pair payloads, and signatures. Callers get
	// the operation and typed classification while raw diagnostics stay in the
	// explicitly captured gate logs.
	return nil, fmt.Errorf("adb %s failed: %w", adbOperation(args), err)
}

func adbOperation(args []string) string {
	if len(args) == 0 {
		return "command"
	}
	if args[0] == "shell" && len(args) > 1 {
		return "shell " + args[1]
	}
	return args[0]
}

func (c *Client) Install(ctx context.Context, apk string) error {
	_, err := c.run(ctx, "install", "-r", apk)
	return err
}

func (c *Client) Grant(ctx context.Context, permission string) error {
	return c.GrantPackage(ctx, "com.twinotify.app", permission)
}

// GrantPackage grants an Android permission without invoking a shell.
func (c *Client) GrantPackage(ctx context.Context, packageName, permission string) error {
	if strings.TrimSpace(packageName) == "" || strings.TrimSpace(permission) == "" {
		return errors.New("package and permission are required")
	}
	_, err := c.run(ctx, "shell", "pm", "grant", packageName, permission)
	return err
}

func (c *Client) Broadcast(ctx context.Context, action string, extras map[string]string) error {
	args := []string{"shell", "am", "broadcast", "-a", action}
	return c.broadcastArgs(ctx, args, extras)
}

// BroadcastReceiver targets a named debug receiver. Android's implicit
// broadcast resolution is not reliable for this install-scoped control
// surface, so the host harness must address the receiver explicitly.
func (c *Client) BroadcastReceiver(ctx context.Context, packageName, receiver, action string, extras map[string]string) error {
	if !validComponentName(packageName) || !validComponentName(receiver) {
		return errors.New("package and receiver must be valid Android component names")
	}
	// Keep the complete component one remote-shell word as defense in depth;
	// validation above also prevents malformed CLI input from reaching adb.
	args := []string{"shell", "am", "broadcast", "-n", shellQuote(packageName + "/" + receiver), "-a", action}
	return c.broadcastArgs(ctx, args, extras)
}

func validComponentName(value string) bool {
	value = strings.TrimSpace(value)
	if value == "" {
		return false
	}
	for _, part := range strings.Split(value, ".") {
		if part == "" {
			return false
		}
		for i, r := range part {
			if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '_' || (i > 0 && r >= '0' && r <= '9') {
				continue
			}
			return false
		}
	}
	return true
}

func (c *Client) broadcastArgs(ctx context.Context, args []string, extras map[string]string) error {
	keys := make([]string, 0, len(extras))
	for key := range extras {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		args = append(args, "--es", key, shellQuote(extras[key]))
	}
	_, err := c.run(ctx, args...)
	return err
}

// adb shell reparses the command after the host process has assembled argv.
// Quote every extra as one POSIX shell word so JSON pair payloads and tokens
// containing quotes, spaces, or shell metacharacters reach am unchanged.
func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'"
}

func (c *Client) ReadRunAs(ctx context.Context, packageName, path string) ([]byte, error) {
	return c.run(ctx, "shell", "run-as", packageName, "cat", path)
}

func (c *Client) ForceStop(ctx context.Context, packageName string) error {
	_, err := c.run(ctx, "shell", "am", "force-stop", packageName)
	return err
}

func (c *Client) Reboot(ctx context.Context) error {
	_, err := c.run(ctx, "reboot")
	return err
}

func (c *Client) WaitForDevice(ctx context.Context) error {
	_, err := c.run(ctx, "wait-for-device")
	return err
}

func (c *Client) SetAirplaneMode(ctx context.Context, enabled bool) error {
	value := "0"
	if enabled {
		value = "1"
	}
	_, err := c.run(ctx, "shell", "settings", "put", "global", "airplane_mode_on", value)
	return err
}

func (c *Client) SetWifiEnabled(ctx context.Context, enabled bool) error {
	state := "disable"
	if enabled {
		state = "enable"
	}
	_, err := c.run(ctx, "shell", "svc", "wifi", state)
	return err
}

func (c *Client) NotificationHelp(ctx context.Context) ([]byte, error) {
	return c.run(ctx, "shell", "cmd", "notification", "help")
}

func (c *Client) PostNotification(ctx context.Context, tag, text string) error {
	_, err := c.run(ctx, "shell", "cmd", "notification", "post", "-t", tag, tag, text)
	return err
}

func (c *Client) CancelNotification(ctx context.Context, tag string) error {
	_, err := c.run(ctx, "shell", "cmd", "notification", "cancel", tag)
	return err
}

func (c *Client) DumpsysNotification(ctx context.Context) ([]byte, error) {
	return c.run(ctx, "shell", "dumpsys", "notification", "--noredact")
}
