#!/usr/bin/env python3
"""Run prebuilt APKs and verify JUnit completion, including optional real model downloads."""

import argparse
from pathlib import Path
import re
import subprocess
from device_ui import prepare_emulator


def tests_passed(output):
    return bool(re.search(r"^OK \(([1-9][0-9]*) tests?\)\s*$", output, re.MULTILINE)) and not re.search(
        r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|"
        r"INSTRUMENTATION_RESULT: shortMsg=|Process crashed", output
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk_directory", type=Path)
    parser.add_argument("--download-models", action="store_true")
    parser.add_argument("--class", dest="test_class")
    args = parser.parse_args()
    reports = Path("build/reports/device-tests")
    reports.mkdir(parents=True, exist_ok=True)
    for name in ("app-debug.apk", "app-debug-androidTest.apk"):
        matches = list(args.apk_directory.rglob(name))
        if len(matches) != 1:
            parser.error(f"Expected exactly one {name}, found {len(matches)}")
        subprocess.run(["adb", "install", "-r", str(matches[0])], check=True)
    if prepare_emulator(reports):
        print("Recovered the emulator's boot-time launcher ANR before instrumentation")
    command = ["adb", "shell", "am", "instrument", "-w", "-r"]
    if args.download_models:
        command += ["-e", "downloadModels", "true"]
    if args.test_class:
        command += ["-e", "class", args.test_class]
    command += ["app.glimpse.test/androidx.test.runner.AndroidJUnitRunner"]
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", timeout=900)
        output = result.stdout
        success = result.returncode == 0 and tests_passed(output)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b""
        if isinstance(output, bytes):
            output = output.decode(errors="replace")
        output += "\nInstrumentation timed out after 900 seconds.\n"
        success = False
    (reports / "instrumentation.txt").write_text(output)
    logs = subprocess.run(["adb", "logcat", "-d"], capture_output=True, text=True, errors="replace", timeout=30)
    (reports / "logcat.txt").write_text(logs.stdout)
    print(output)
    if not success:
        raise SystemExit("Instrumentation did not report a successful completed test run")
    if not args.test_class:
        screenshots = reports / "screenshots"
        screenshots.mkdir(exist_ok=True)
        for theme in ("light", "dark"):
            image = subprocess.check_output(["adb", "exec-out", "run-as", "app.glimpse", "cat", f"cache/glimpse-{theme}.png"], timeout=30)
            if not image.startswith(b"\x89PNG\r\n\x1a\n"):
                raise SystemExit(f"Invalid {theme} screenshot")
            (screenshots / f"glimpse-{theme}.png").write_bytes(image)


if __name__ == "__main__":
    main()
