package device

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/adb"
	"github.com/twinotify/phone-sync/e2e/internal/control"
)

type Android struct {
	ADB     *adb.Client
	Control *control.Client
	Package string
}

func OpenAndroid(ctx context.Context, serial, packageName string) (*Android, error) {
	client := adb.New(nil, serial)
	raw, err := client.ReadRunAs(ctx, packageName, "files/e2e-token")
	if err != nil {
		return nil, err
	}
	token := strings.TrimSpace(string(raw))
	if token == "" || strings.ContainsAny(token, "\r\n \t") {
		return nil, errors.New("invalid Android control token")
	}
	transport := androidControl{client, packageName}
	return &Android{client, control.New(transport, serial, token, 45*time.Second), packageName}, nil
}

func (a *Android) Execute(ctx context.Context, name string, params map[string]string) (control.Result, error) {
	id, err := requestID()
	if err != nil {
		return control.Result{}, err
	}
	result, err := a.Control.Execute(ctx, control.Command{RequestID: id, Name: name, Params: params})
	if err == nil && result.Code != "ok" {
		err = fmt.Errorf("Android %s: %s", name, result.Code)
	}
	return result, err
}

func (a *Android) Snapshot(ctx context.Context) (Snapshot, error) {
	result, err := a.Execute(ctx, "STATUS", nil)
	if err != nil {
		return Snapshot{}, err
	}
	return decodeSnapshot(result.Payload)
}

func (a *Android) RemovePeer(ctx context.Context, link string) error {
	_, err := a.Execute(ctx, "REMOVE_PEER", map[string]string{"peer_link_id": link})
	return err
}

func (a *Android) Post(ctx context.Context, tag, text string) error {
	return a.ADB.PostNotification(ctx, tag, text)
}
func (a *Android) Cancel(ctx context.Context, tag string) error {
	_, err := a.Execute(ctx, "CANCEL_SOURCE_FIXTURE", map[string]string{"tag": tag})
	return err
}
func (a *Android) DismissMirror(ctx context.Context) error {
	_, err := a.Execute(ctx, "DISMISS_NEWEST_MIRROR", nil)
	return err
}
func (a *Android) Repair(ctx context.Context) error {
	_, err := a.Execute(ctx, "FORCE_REPAIR_SNAPSHOT", nil)
	return err
}

type androidControl struct {
	client      *adb.Client
	packageName string
}

func (d androidControl) BoundRequestID(token, command string) (string, error) {
	return control.NewBoundRequestID(token, command, time.Now(), nil)
}
func (d androidControl) Broadcast(ctx context.Context, command control.Command) error {
	if err := d.client.WriteRunAsPrivate(ctx, d.packageName, "e2e-auth", command.RequestID, []byte(command.Token)); err != nil {
		return err
	}
	extras := map[string]string{"request_id": command.RequestID, "command": command.Name, "auth_input_id": command.RequestID}
	for key, value := range command.Params {
		extras[key] = value
	}
	return d.client.BroadcastReceiver(ctx, d.packageName, "co.twinotify.core.e2e.E2eControlReceiver", "co.twinotify.e2e.CONTROL", extras)
}
func (d androidControl) ReadResult(ctx context.Context, id string) ([]byte, error) {
	if strings.ContainsAny(id, "/\\") {
		return nil, errors.New("invalid result ID")
	}
	data, err := d.client.ReadRunAs(ctx, d.packageName, "files/e2e-results/"+id+".json")
	if errors.Is(err, adb.ErrNotFound) {
		return nil, control.ErrResultNotReady
	}
	return data, err
}
func (d androidControl) CleanupPrivateAuth(ctx context.Context, id string) error {
	return d.client.DeleteRunAsPrivate(ctx, d.packageName, "e2e-auth", id)
}
