import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from release import parse_version, previous_release


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


packaging = load("packaging", SCRIPTS / "package-release.py")
device = load("device_tests", SCRIPTS / "run-device-tests.py")
ui = load("device_ui", SCRIPTS / "device_ui.py")


class ReleaseTest(unittest.TestCase):
    def test_bootstrap_recovery_is_scoped_to_visible_launcher_anr(self):
        launcher = "Window #1 Window{abc u0 Application Not Responding: com.android.launcher3}:\n isVisible=true\n"
        app = "Window #2 Window{def u0 Application Not Responding: app.glimpse}:\n isVisible=true\n"
        hidden = "Window #3 Window{ghi u0 Application Not Responding: other.app}:\n isVisible=false\n"
        self.assertEqual({"com.android.launcher3", "app.glimpse"}, ui.visible_anrs(launcher + app + hidden))
        with tempfile.TemporaryDirectory() as directory, patch.object(ui, "adb", side_effect=["1", launcher + app, b"diagnostic image"]) as commands:
            with self.assertRaisesRegex(RuntimeError, "unexpected ANR"):
                ui.prepare_emulator(Path(directory))
            self.assertFalse(any("force-stop" in call.args for call in commands.call_args_list))

    def test_bootstrap_recovery_records_evidence_and_requires_focus(self):
        launcher = "Window #1 Window{abc u0 Application Not Responding: com.android.launcher3}:\n isVisible=true\n"
        ready = "mCurrentFocus=Window{xyz u0 com.android.launcher3/.Launcher}\n"
        with tempfile.TemporaryDirectory() as directory, patch.object(ui, "adb", side_effect=["1", launcher, b"diagnostic image", "", "", ready]) as commands:
            self.assertTrue(ui.prepare_emulator(Path(directory)))
            self.assertEqual(launcher, (Path(directory) / "emulator-bootstrap-windows.txt").read_text())
            commands.assert_any_call("shell", "am", "force-stop", "com.android.launcher3")
            commands.assert_any_call("shell", "input", "keyevent", "KEYCODE_HOME")
        with tempfile.TemporaryDirectory() as directory, patch.object(ui, "adb", return_value="0") as commands:
            self.assertFalse(ui.prepare_emulator(Path(directory)))
            self.assertEqual(1, commands.call_count)

    def test_enabled_child_label_cannot_bypass_disabled_control(self):
        root = ET.fromstring('<hierarchy><node enabled="false"><node text="Translation" enabled="true" /></node></hierarchy>')
        self.assertIsNotNone(ui.find_in_tree(root, "Translation"))
        self.assertIsNone(ui.find_in_tree(root, "Translation", enabled=True))
    def test_version_range_and_ambiguous_tags(self):
        self.assertEqual((1, 2), parse_version("1.2-release"))
        for tag in ("0.0-release", "01.2-release", "1.02-release", "1.10000-release", "210001.0-release", "v1.2", "1.2.3-release"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                parse_version(tag)

    def test_published_release_must_increase_but_drafts_do_not_block_retry(self):
        releases = [{"tagName": "0.1-release", "isDraft": False, "isPrerelease": False}, {"tagName": "0.2-release", "isDraft": True, "isPrerelease": False}]
        self.assertEqual("0.1-release", previous_release("0.2-release", releases))
        with self.assertRaises(ValueError):
            previous_release("0.1-release", releases)

    def test_release_identity_signature_and_debug_flag(self):
        valid = "package: name='app.glimpse' versionCode='1' versionName='0.1'\n"
        packaging.verify_identity(valid, "0.1-release")
        for badging in (valid + "application-debuggable\n", valid.replace("app.glimpse", "other.app"), valid.replace("versionCode='1'", "versionCode='2'")):
            with self.assertRaises(ValueError):
                packaging.verify_identity(badging, "0.1-release")
        packaging.verify_certificate("Signer #1 certificate SHA-256 digest: abc123", "abc123")
        with self.assertRaises(ValueError):
            packaging.verify_certificate("Signer #1 certificate SHA-256 digest: deadbeef", "abc123")

    def test_metadata_rejects_stale_paths_duplicates_and_mismatched_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            elements = []
            for abi in (None, *packaging.ABIS):
                name = f"{abi or 'universal'}.apk"
                (root / name).touch()
                elements.append({"versionName": "0.1", "versionCode": 1, "filters": [] if abi is None else [{"filterType": "ABI", "value": abi}], "outputFile": name})
            metadata = {"applicationId": "app.glimpse", "variantName": "release", "artifactType": {"type": "APK"}, "elements": elements}
            def write():
                (root / "output-metadata.json").write_text(json.dumps(metadata))
            write()
            self.assertEqual(5, len(packaging.read_outputs(root)[1]))
            elements[0]["outputFile"] = "../outside.apk"
            write()
            with self.assertRaises(ValueError):
                packaging.read_outputs(root)
            elements[0]["outputFile"] = "universal.apk"
            elements[1]["versionCode"] = 2
            write()
            with self.assertRaises(ValueError):
                packaging.read_outputs(root)

    def test_64_bit_native_libraries_require_real_architecture_and_16k_pages(self):
        data = bytearray(120)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, 183)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q", data, 112, 16384)
        packaging.verify_elf(data, "arm64-v8a")
        with self.assertRaises(ValueError):
            packaging.verify_elf(data, "x86_64")
        struct.pack_into("<Q", data, 112, 4096)
        with self.assertRaises(ValueError):
            packaging.verify_elf(data, "arm64-v8a")

    def test_instrumentation_must_report_completed_nonempty_junit_success(self):
        self.assertTrue(device.tests_passed("OK (7 tests)\n"))
        for output in ("OK (0 tests)", "INSTRUMENTATION_CODE: -1", "Process crashed\nOK (7 tests)", "FAILURES!!!\nOK (7 tests)"):
            self.assertFalse(device.tests_passed(output))


if __name__ == "__main__":
    unittest.main()
