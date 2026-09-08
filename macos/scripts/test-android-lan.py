#!/usr/bin/env python3
"""Opt-in TLS/delivery interoperability; uses only the standalone Android test APK.

First run make macos-verify and Gradle :twinotify-core:assembleDebugAndroidTest.
Then run this script with --serial emulator-5554. It installs the test package,
opens one temporary adb forward and exchanges public test identities only.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("use an isolated Android emulator")
    adb = shutil.which("adb")
    if not adb:
        parser.error("put Android platform-tools on PATH")
    root = Path(__file__).resolve().parents[2]
    apk = root / "mobile/modules/twinotify-core/android/build/outputs/apk/androidTest/debug/twinotify-core-debug-androidTest.apk"
    if not apk.is_file():
        parser.error("first build :twinotify-core:assembleDebugAndroidTest")
    with tempfile.TemporaryDirectory(prefix="twinotify-lan-") as temporary:
        run = Path(temporary) / str(uuid.uuid4())
        run.mkdir(mode=0o700)
        subprocess.run([adb, "-s", args.serial, "install", "-r", str(apk)], check=True)
        mac = android = None
        forwarded_port = None
        watchdog = None
        try:
            with (run / "mac.log").open("w") as mac_log, (run / "android.log").open("w") as android_log:
                mac = subprocess.Popen(["swift", "test", "--package-path", str(root / "macos"),
                    "--skip-build", "--filter", "androidMacPinnedTLSExporterAndSignedHandshake"],
                    env={**os.environ, "TWINOTIFY_LAN_INTEROP_DIR": str(run)}, stdout=mac_log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 45
                while not (run / "mac.json").is_file():
                    if mac.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("Mac test did not publish its public test identity")
                    time.sleep(0.1)
                command = shlex.join(["am", "instrument", "-w", "-r", "-e", "class",
                    "co.twinotify.core.lan.MacLanInteropTest", "-e", "mac_lan_peer", (run / "mac.json").read_text(),
                    "co.twinotify.core.test/androidx.test.runner.AndroidJUnitRunner"])
                android = subprocess.Popen([adb, "-s", args.serial, "shell", command],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                # Bound even a stalled instrumentation process or pipe read.
                watchdog = threading.Timer(90, android.terminate)
                watchdog.start()
                for line in android.stdout:
                    android_log.write(line); android_log.flush()
                    if line.startswith("INSTRUMENTATION_STATUS: lan_interop="):
                        if forwarded_port is not None:
                            raise RuntimeError("duplicate test endpoint announcement")
                        peer = json.loads(line.split("=", 1)[1])
                        port = peer["port"]
                        if not isinstance(port, int) or not 1 <= port <= 65535:
                            raise RuntimeError("invalid test endpoint port")
                        forwarded_port = int(subprocess.check_output([adb, "-s", args.serial,
                            "forward", "tcp:0", f"tcp:{port}"], text=True).strip())
                        peer["host_port"] = forwarded_port
                        pending = run / "android.tmp"
                        pending.write_text(json.dumps(peer))
                        pending.rename(run / "android.json")
                android.wait(timeout=10)
                mac.wait(timeout=45)
            if mac.returncode != 0 or "OK (1 test)" not in (run / "android.log").read_text():
                raise RuntimeError("Android–Mac LAN interoperability assertions failed")
            print("PASS: pinned TLS, exporter, signed handshake, exact custody, encrypted receipts and duplicate delivery")
        except Exception:
            for name in ["mac.log", "android.log"]:
                path = run / name
                if path.exists():
                    print(path.read_text())
            raise
        finally:
            if watchdog:
                watchdog.cancel()
            for process in [mac, android]:
                if process and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait()
            if forwarded_port is not None:
                subprocess.run([adb, "-s", args.serial, "forward", "--remove", f"tcp:{forwarded_port}"], check=True)


if __name__ == "__main__":
    main()
