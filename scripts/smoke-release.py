#!/usr/bin/env python3
"""Exercise a signed optimized APK through real UI on the development emulator."""

import argparse
import json
from pathlib import Path
import struct
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

    def positive_button(package):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            node = next((node for node in hierarchy().iter("node") if node.get("package") == package and node.get("resource-id") == "android:id/button1"), None)
            if node is not None:
                left, top, right, bottom = bounds(node)
                adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                return
            time.sleep(0.3)
        raise RuntimeError(f"No confirmation button for {package}")

    def screen_pixels():
        raw = adb("exec-out", "screencap", text=False)
        width, height, pixel_format = struct.unpack_from("<III", raw)
        header = len(raw) - width * height * 4
        if pixel_format != 1 or header not in (12, 16):
            raise RuntimeError("Expected an RGBA8888 emulator screencap")
        return width, height, memoryview(raw)[header:]

    def dark_fraction(rect):
        width, height, pixels = screen_pixels()
        left, top, right, bottom = rect
        dark = total = 0
        for y in range(max(0, top), min(height, bottom), 2):
            for x in range(max(0, left), min(width, right), 2):
                offset = (y * width + x) * 4
                dark += pixels[offset] < 125 and pixels[offset + 1] < 145 and pixels[offset + 2] < 145
                total += 1
        return dark / max(1, total)

    def dock_bounds():
        # The legacy `uiautomator dump` reads only the active window, excluding a pass-through
        # non-focusable dock. Find its opaque background in the actual compositor output instead.
        width, height, pixels = screen_pixels()
        rows = []
        for y in range(height * 55 // 100, height):
            offset = (y * width + width // 2) * 4
            if all(abs(pixels[offset + channel] - color) < 12 for channel, color in enumerate((24, 35, 30))):
                rows.append(y)
        if len(rows) < height * .03:
            return None
        return width, height, min(rows), max(rows)

    def wait_rendered(rect, baseline, translated):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            visible = dark_fraction(rect) > baseline + .2
            if visible == translated:
                return
            time.sleep(.3)
        raise RuntimeError(f"Expected translated labels visible={translated} in the compositor output")

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
        for action in ("Mark to search", "Translate", "Music", "Camera"):
            wait_for(action, enabled=True)
        tree = hierarchy()
        if find_in_tree(tree, "Close assistant") is not None:
            raise RuntimeError("Assistant still exposes the removed close button")
        if find_in_tree(tree, "Image selection.", contains=True) is not None:
            raise RuntimeError("Screenshot selection is active before choosing Mark to search")
        snapshot("assistant")
        steps.append("R8-retained assistant services expose Mark to search, Translate, Music and Camera without a close button")

        tap("Music")
        wait_for("Identify a song")
        for source in ("Both", "Phone", "Mic"):
            wait_for(source, enabled=True)
        tap("Close music")
        wait_for("Mark to search", enabled=True)
        steps.append("Music input choices open directly and Close music returns to the assistant")

        tap("Mark to search")
        wait_for("Image selection.", contains=True)
        snapshot("assistant-selection")
        steps.append("Mark to search enables screenshot selection")

        if args.visual_search:
            tap("Assistant options")
            tap("Select whole screen")
            tap("Search selected image")
            wait_for("Close visual search")
            time.sleep(8)
            snapshot("google-visual-search")
            steps.append("Google visual-search upload page opens with the selected crop staged")
            tap("Close visual search")
            wait_for("Mark to search", enabled=True)

        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            tree = hierarchy()
            if find_in_tree(tree, "Mark to search") is None and any(node.get("package") == "com.android.settings" for node in tree.iter("node")):
                break
            time.sleep(0.3)
        else:
            raise RuntimeError("Back did not dismiss the assistant and return to Android Settings")
        snapshot("assistant-dismissed")
        steps.append("Back dismisses screenshot selection and returns to the underlying app")

        adb("shell", "appops", "set", "app.glimpse", "SYSTEM_ALERT_WINDOW", "allow")
        adb("shell", "appops", "set", "app.glimpse", "PROJECT_MEDIA", "default")
        adb("shell", "am", "start", "-W", "-n", "app.glimpse/.MainActivity", "-a", "android.intent.action.PROCESS_TEXT", "--es", "android.intent.extra.PROCESS_TEXT", "こんにちは")
        source = wait_for("こんにちは")
        left, top, right, bottom = bounds(source)
        width, height, _ = screen_pixels()
        source_rect = (left, top, min(right, left + width * 22 // 100), min(bottom, top + height * 35 // 1000))
        original_darkness = dark_fraction(source_rect)
        adb("shell", "input", "keyevent", "KEYCODE_ASSIST")
        tap("Translate")
        wait_for("Translate your screen")
        tap("Detect language")
        tap("Japanese")
        positive_button("app.glimpse")
        positive_button("com.android.systemui")
        wait_rendered(source_rect, original_darkness, True)
        dock = dock_bounds()
        if dock is None:
            raise RuntimeError("Live translation dock was not rendered")
        width, height, dock_top, dock_bottom = dock
        controls_y = round(dock_bottom - width / 411 * 32)
        snapshot("live-whole-screen")
        adb("shell", "input", "tap", str(width // 2), str(controls_y))
        wait_rendered(source_rect, original_darkness, False)
        adb("shell", "input", "tap", str(width // 2), str(controls_y))
        wait_rendered(source_rect, original_darkness, True)
        adb("shell", "input", "tap", str(width * 4 // 5), str(controls_y))
        deadline = time.monotonic() + 15
        while dock_bounds() is not None:
            if time.monotonic() >= deadline:
                raise RuntimeError("Live translation did not stop")
            time.sleep(0.3)
        steps.append("Whole-display consent starts real Japanese overlay translation in the R8 build, with Pause, Resume and Stop")
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
        adb("shell", "am", "force-stop", "app.glimpse", check=False)
        adb("shell", "appops", "set", "app.glimpse", "SYSTEM_ALERT_WINDOW", "default", check=False)
        adb("shell", "cmd", "role", "remove-role-holder", "--user", "0", "android.app.role.ASSISTANT", "app.glimpse", check=False)
        for holder in previous:
            if holder.strip() and holder != "app.glimpse":
                adb("shell", "cmd", "role", "add-role-holder", "--user", "0", "android.app.role.ASSISTANT", holder)


if __name__ == "__main__":
    main()
