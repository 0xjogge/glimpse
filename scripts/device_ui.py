#!/usr/bin/env python3
"""Inspect and exercise Android's accessibility hierarchy through ADB."""

import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(*args, text=True, check=True, timeout=30):
    result = subprocess.run(["adb", *args], capture_output=True, text=text, errors="replace" if text else None, check=check, timeout=timeout)
    return result.stdout


def visible_anrs(windows):
    packages = set()
    for window in re.split(r"\n\s*Window #\d+ ", windows):
        match = re.search(r"Application Not Responding:\s*([\w.]+)", window.partition("\n")[0])
        if match and "isVisible=true" in window:
            packages.add(match.group(1))
    return packages


def prepare_emulator(reports):
    """Recover only a known boot-time launcher ANR before running any app tests."""
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        return False
    windows = adb("shell", "dumpsys", "window", "windows")
    errors = visible_anrs(windows)
    if not errors:
        return False
    (reports / "emulator-bootstrap-windows.txt").write_text(windows)
    (reports / "emulator-bootstrap.png").write_bytes(adb("exec-out", "screencap", "-p", text=False))
    if errors != {"com.android.launcher3"}:
        raise RuntimeError(f"Emulator has unexpected ANR windows before testing: {sorted(errors)}")
    # The emulator runner injects MENU as soon as boot_completed is set. On a cold AOSP image
    # that can time out before Launcher owns focus, leaving its ANR dialog above every test.
    # Restart only the affected launcher. A generic close-dialog broadcast can leave Home without
    # a focused window after dismissing its error. Explicit HOME starts a fresh launcher task.
    adb("shell", "am", "force-stop", "com.android.launcher3")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        windows = adb("shell", "dumpsys", "window", "windows")
        if not visible_anrs(windows) and re.search(r"mCurrentFocus=Window\{[^}\n]+\}", windows):
            return True
        time.sleep(.3)
    raise RuntimeError("Emulator launcher did not recover focus; see bootstrap diagnostics")


def hierarchy():
    output = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    start = output.index("<?xml")
    end = output.index("</hierarchy>") + len("</hierarchy>")
    return ET.fromstring(output[start:end])


def find_in_tree(root, label, contains=False, enabled=False):
    def visit(node, parent_enabled=True):
        active = parent_enabled and node.get("enabled", "true") == "true"
        if enabled and not active:
            return None
        if any(label in node.get(key, "") if contains else label == node.get(key, "") for key in ("text", "content-desc")):
            return node
        for child in node:
            match = visit(child, active)
            if match is not None:
                return match
        return None
    return visit(root)


def find(label, contains=False, enabled=False):
    return find_in_tree(hierarchy(), label, contains, enabled)


def wait_for(label, timeout=30, contains=False, enabled=False):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            node = find(label, contains, enabled)
            if node is not None:
                return node
        except (ValueError, ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(0.3)
    raise RuntimeError(f"Timed out waiting for {label!r}")


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap(label, contains=False, timeout=30):
    left, top, right, bottom = bounds(wait_for(label, timeout, contains, enabled=True))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["inspect", "tap"])
    parser.add_argument("label", nargs="?")
    parser.add_argument("--contains", action="store_true")
    parser.add_argument("--package", default="app.glimpse")
    args = parser.parse_args()
    if args.command == "tap":
        if not args.label:
            parser.error("Supply a label")
        tap(args.label, args.contains)
    else:
        for node in hierarchy().iter("node"):
            label = node.get("text") or node.get("content-desc")
            if label and node.get("package") == args.package:
                print(f"{node.get('bounds')}  {label}")


if __name__ == "__main__":
    main()
