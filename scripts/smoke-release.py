#!/usr/bin/env python3
"""Exercise a signed optimized APK through real UI on the development emulator."""

import argparse
import json
from pathlib import Path
import time
import xml.etree.ElementTree as ET

from device_ui import adb, bounds, find_in_tree, hierarchy, tap, wait_for


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--visual-search", action="store_true", help="Also open Google's live image-upload page")
    args = parser.parse_args()
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Use the development emulator; this helper replaces its app installation")
    if not args.apk.is_file():
        parser.error("APK does not exist")
    reports = Path("build/reports/release-smoke")
    reports.mkdir(parents=True, exist_ok=True)
    steps = []
    report = {"apk": str(args.apk), "success": False, "steps": steps}
    previous = adb("shell", "cmd", "role", "get-role-holders", "--user", "0", "android.app.role.ASSISTANT").strip().splitlines()

    def snapshot(name):
        (reports / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", text=False))
        (reports / f"{name}.xml").write_text(ET.tostring(hierarchy(), encoding="unicode"))

    try:
        adb("uninstall", "app.glimpse", check=False)
        adb("install", str(args.apk.resolve()))
        adb("shell", "pm", "grant", "app.glimpse", "android.permission.CAMERA")
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-W", "-n", "app.glimpse/.MainActivity")
        wait_for("A closer look.")
        snapshot("home")
        steps.append("Signed optimized APK launches")

        tap("Try an example")
        wait_for("こんにちは", contains=True, timeout=60)
        image = wait_for("Image selection.", contains=True)
        left, top, right, bottom = bounds(image)
        scale = min((right - left) / 1080, (bottom - top) / 1400)
        x = left + ((right - left) - 1080 * scale) / 2 + 280 * scale
        y = top + ((bottom - top) - 1400 * scale) / 2 + 265 * scale
        adb("shell", "input", "tap", str(round(x)), str(round(y)))
        wait_for("こんにちは")
        snapshot("selection")
        steps.append("Real bundled Japanese OCR and touch selection")

        tap("Translate")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            tree = hierarchy()
            if find_in_tree(tree, "Download models", enabled=True) is not None:
                tap("Download models")
                break
            if find_in_tree(tree, "Translation", enabled=True) is not None:
                break
        else:
            raise RuntimeError("Translate action did not show a result or missing-model guidance")
        wait_for("Translation", enabled=True, timeout=180)
        tap("Translation")
        translated = False
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            translated = any(node.get("text", "").strip().lower().rstrip(".!") in {"hello", "hi", "good afternoon", "good day"} for node in hierarchy().iter("node"))
            if translated:
                break
        if not translated:
            raise RuntimeError("Japanese greeting did not produce its English translation")
        snapshot("translation")
        steps.append("Downloaded Japanese model produces an in-app translation in the R8 build")

        tap("Open camera")
        wait_for("Take picture", enabled=True, timeout=30)
        tap("Live translate")
        time.sleep(1)
        snapshot("live-camera")
        tap("Photo")
        tap("Take picture")
        wait_for("Camera photo", timeout=30)
        wait_for("Search image", enabled=True)
        snapshot("camera-photo")
        steps.append("Camera opens from the workspace, live mode is reachable, and still capture returns to selection")

        adb("shell", "cmd", "role", "add-role-holder", "--user", "0", "android.app.role.ASSISTANT", "app.glimpse")
        adb("shell", "settings", "put", "secure", "assist_screenshot_enabled", "1")
        adb("shell", "am", "start", "-W", "-a", "android.settings.SETTINGS")
        adb("shell", "input", "keyevent", "KEYCODE_ASSIST")
        wait_for("Close assistant", timeout=30)
        wait_for("Image selection.", contains=True)
        wait_for("Open camera", enabled=True)
        wait_for("Recognize music", enabled=True)
        wait_for("Translate screen", enabled=True)
        snapshot("assistant")
        steps.append("R8-retained assistant services open the edge-lit overlay with screen, music and camera actions")

        if args.visual_search:
            tap("Translate screen")
            tap("Search selected image")
            wait_for("Close visual search")
            time.sleep(8)
            snapshot("google-visual-search")
            steps.append("Google visual-search upload page opens with the selected crop staged")
        report = {"apk": str(args.apk), "success": True, "steps": steps}
        print("\n".join(f"PASS: {step}" for step in steps))
    except Exception as error:
        report = {"apk": str(args.apk), "success": False, "steps": steps, "error": str(error)}
        try:
            snapshot("failure")
        except Exception:
            pass
        raise
    finally:
        (reports / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        (reports / "logcat.txt").write_text(adb("logcat", "-d"))
        adb("shell", "cmd", "role", "remove-role-holder", "--user", "0", "android.app.role.ASSISTANT", "app.glimpse", check=False)
        for holder in previous:
            if holder.strip() and holder != "app.glimpse":
                adb("shell", "cmd", "role", "add-role-holder", "--user", "0", "android.app.role.ASSISTANT", holder)


if __name__ == "__main__":
    main()
