package adb

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"net"
	"os/exec"
	"regexp"
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

func (r execRunner) RunWithInput(ctx context.Context, input []byte, args ...string) ([]byte, error) {
	command := exec.CommandContext(ctx, r.path, args...)
	command.Stdin = bytes.NewReader(input)
	return command.Output()
}

type inputRunner interface {
	RunWithInput(context.Context, []byte, ...string) ([]byte, error)
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

func (c *Client) Serial() string { return c.serial }

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

func (c *Client) runWithInput(ctx context.Context, input []byte, args ...string) ([]byte, error) {
	runner, ok := c.runner.(inputRunner)
	if !ok {
		return nil, errors.New("ADB runner does not support private stdin")
	}
	full := append([]string{"-s", c.serial}, args...)
	out, err := runner.RunWithInput(ctx, input, full...)
	if err == nil {
		return out, nil
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return nil, err
	}
	return nil, fmt.Errorf("adb private run-as %s failed: %w", adbOperation(args), err)
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
	if strings.TrimSpace(apk) == "" {
		return errors.New("APK path is required")
	}
	_, err := c.run(ctx, "install", "-r", apk)
	return err
}

func (c *Client) Uninstall(ctx context.Context, packageName string) error {
	if err := ValidateComponentName(packageName); err != nil {
		return err
	}
	_, err := c.run(ctx, "uninstall", packageName)
	return err
}

func (c *Client) ForegroundPackage(ctx context.Context) (string, error) {
	out, err := c.run(ctx, "shell", "dumpsys", "activity", "activities")
	if err != nil {
		return "", err
	}
	match := regexp.MustCompile(`(?m)mResumedActivity:.*? ([A-Za-z0-9_.]+)/`).FindSubmatch(out)
	if len(match) != 2 {
		return "", nil
	}
	return string(match[1]), nil
}

func (c *Client) Grant(ctx context.Context, permission string) error {
	return c.GrantPackage(ctx, "com.twinotify.app", permission)
}

// GrantPackage grants an Android permission without invoking a shell.
func (c *Client) GrantPackage(ctx context.Context, packageName, permission string) error {
	if !validComponentName(packageName) || strings.TrimSpace(permission) == "" {
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
	if value == "" || value != strings.TrimSpace(value) {
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

// ValidateComponentName applies the grammar used at every Android package and
// component boundary before any value can reach adb shell.
func ValidateComponentName(value string) error {
	if !validComponentName(value) {
		return errors.New("invalid Android component name")
	}
	return nil
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
	if err := ValidateComponentName(packageName); err != nil {
		return nil, err
	}
	return c.run(ctx, "shell", "run-as", packageName, "cat", path)
}

var privateHandlePattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

func privateRunAsPath(packageName, bucket, requestID string) (string, error) {
	if !validComponentName(packageName) {
		return "", errors.New("package must be a valid Android component name")
	}
	if bucket != "e2e-inputs" && bucket != "e2e-secrets" && bucket != "e2e-auth" {
		return "", errors.New("private run-as bucket is not allowlisted")
	}
	if !privateHandlePattern.MatchString(requestID) {
		return "", errors.New("private run-as handle is invalid")
	}
	return "files/" + bucket, nil
}

// WriteRunAsPrivate sends bounded ceremony material over stdin. The secret is
// absent from argv, process listings, shell history and ordinary diagnostics.
func (c *Client) WriteRunAsPrivate(ctx context.Context, packageName, bucket, requestID string, value []byte) error {
	directory, err := privateRunAsPath(packageName, bucket, requestID)
	if err != nil {
		return err
	}
	if len(value) == 0 || len(value) > 4096 {
		return errors.New("private run-as input must be 1..4096 bytes")
	}
	const script = `set -eu; umask 077; dir="$1"; name="$2"; uid=$(id -u); test ! -L "$dir"; mkdir -p "$dir"; test -d "$dir"; test "$(stat -c %u "$dir")" = "$uid"; chmod 700 "$dir"; test "$(stat -c %a "$dir")" = 700; target="$dir/$name"; test ! -e "$target"; test ! -L "$target"; tmp=$(mktemp "$dir/.$name.tmp.XXXXXX"); trap 'rm -f "$tmp"' EXIT HUP INT TERM; test "$(stat -c %u "$tmp")" = "$uid"; chmod 600 "$tmp"; test "$(stat -c %a "$tmp")" = 600; cat > "$tmp"; ln "$tmp" "$target"; rm -f "$tmp"`
	_, err = c.runWithInput(ctx, value, "exec-out", "run-as", packageName, "sh", "-c", script, "sh", directory, requestID)
	return err
}

// ReadRunAsPrivateOnce atomically consumes a mode-0600 app-private result and
// rejects oversized output. The remote trap deletes the file on every exit.
func (c *Client) ReadRunAsPrivateOnce(ctx context.Context, packageName, bucket, requestID string, maxBytes int) ([]byte, error) {
	directory, err := privateRunAsPath(packageName, bucket, requestID)
	if err != nil {
		return nil, err
	}
	if maxBytes <= 0 || maxBytes > 4096 {
		return nil, errors.New("private run-as read bound must be 1..4096 bytes")
	}
	const script = `set -eu; dir="$1"; uid=$(id -u); test ! -L "$dir"; test -d "$dir"; test "$(stat -c %u "$dir")" = "$uid"; test "$(stat -c %a "$dir")" = 700; target="$dir/$2"; trap 'rm -f "$target"' EXIT HUP INT TERM; test ! -L "$target"; test -f "$target"; test "$(stat -c %u "$target")" = "$uid"; test "$(stat -c %a "$target")" = 600; size=$(wc -c < "$target"); test "$size" -le "$3"; cat "$target"`
	out, err := c.runWithInput(ctx, nil, "exec-out", "run-as", packageName, "sh", "-c", script, "sh", directory, requestID, fmt.Sprint(maxBytes))
	if err != nil {
		return nil, err
	}
	if len(out) > maxBytes {
		return nil, errors.New("private run-as result exceeded bound")
	}
	return out, nil
}

func (c *Client) DeleteRunAsPrivate(ctx context.Context, packageName, bucket, requestID string) error {
	directory, err := privateRunAsPath(packageName, bucket, requestID)
	if err != nil {
		return err
	}
	const script = `set -eu; dir="$1"; test ! -L "$dir"; if test ! -e "$dir"; then exit 0; fi; test -d "$dir"; test "$(stat -c %u "$dir")" = "$(id -u)"; rm -f "$dir/$2" "$dir/.$2.tmp" "$dir/.$2.tmp."*`
	_, err = c.runWithInput(ctx, nil, "exec-out", "run-as", packageName, "sh", "-c", script, "sh", directory, requestID)
	return err
}

func (c *Client) ForceStop(ctx context.Context, packageName string) error {
	if err := ValidateComponentName(packageName); err != nil {
		return err
	}
	_, err := c.run(ctx, "shell", "am", "force-stop", packageName)
	return err
}

func (c *Client) StartPackage(ctx context.Context, packageName string) error {
	if !validComponentName(packageName) {
		return errors.New("package must be a valid Android component name")
	}
	_, err := c.run(ctx, "shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
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
	state := "disable"
	if enabled {
		state = "enable"
	}
	_, err := c.run(ctx, "shell", "cmd", "connectivity", "airplane-mode", state)
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

func (c *Client) IsHardware(ctx context.Context) (bool, error) {
	out, err := c.run(ctx, "shell", "getprop", "ro.kernel.qemu")
	if err != nil {
		return false, err
	}
	return strings.TrimSpace(string(out)) != "1" && !strings.HasPrefix(c.serial, "emulator-"), nil
}

func (c *Client) DisableMobileData(ctx context.Context) error {
	_, err := c.run(ctx, "shell", "svc", "data", "disable")
	return err
}

var (
	ssidPattern = regexp.MustCompile(`(?m)WifiInfo SSID:\s*([^,\r\n]+)`)
	inetPattern = regexp.MustCompile(`(?m)\binet\s+([0-9.]+/[0-9]+)\b`)
)

// WiFiNetworkHash keeps raw SSID and addresses in process memory only. Its
// return value is a one-way fingerprint of SSID plus the IPv4 subnet.
func (c *Client) WiFiNetworkHash(ctx context.Context) (string, error) {
	const script = `cmd wifi status; ip -o -4 addr show wlan0`
	out, err := c.run(ctx, "shell", "sh", "-c", script)
	if err != nil {
		return "", err
	}
	ssidMatch, inetMatch := ssidPattern.FindSubmatch(out), inetPattern.FindSubmatch(out)
	if len(ssidMatch) != 2 || len(inetMatch) != 2 {
		return "", errors.New("active Wi-Fi network proof unavailable")
	}
	_, subnet, parseErr := net.ParseCIDR(string(inetMatch[1]))
	if parseErr != nil {
		return "", errors.New("active Wi-Fi subnet proof invalid")
	}
	material := append([]byte(strings.TrimSpace(string(ssidMatch[1]))), 0)
	material = append(material, subnet.String()...)
	digest := sha256.Sum256(material)
	clear(material)
	clear(out)
	return fmt.Sprintf("%x", digest), nil
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
