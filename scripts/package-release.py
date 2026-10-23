#!/usr/bin/env python3
"""Verify identity, signatures, native ABIs and 16 KB support before staging five APKs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

from release import parse_version

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
ELF_ARCHITECTURES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62), "x86": (1, 3)}
LABELS = {None: "Universal", "arm64-v8a": "ARM64 · Pixel 10 Pro", "armeabi-v7a": "ARM32", "x86_64": "Intel 64-bit · emulator", "x86": "Intel 32-bit"}


def read_outputs(directory, tag=None):
    directory = Path(directory).resolve()
    metadata = json.loads((directory / "output-metadata.json").read_text())
    if metadata.get("applicationId") != "app.glimpse" or metadata.get("variantName") != "release" or metadata.get("artifactType", {}).get("type") != "APK":
        raise ValueError("Expected Glimpse release APK metadata")
    elements = metadata.get("elements")
    if not isinstance(elements, list) or len(elements) != 5:
        raise ValueError("Build with -PglimpseSplitApks=true to produce all five APKs")
    tag = tag or f"{elements[0].get('versionName')}-release"
    feature, patch = parse_version(tag)
    outputs = {}
    for element in elements:
        if element.get("versionName") != f"{feature}.{patch}" or element.get("versionCode") != feature * 10_000 + patch:
            raise ValueError("All APKs must have the requested version and identical version code")
        filters = element.get("filters")
        if filters == []:
            abi = None
        elif isinstance(filters, list) and len(filters) == 1 and filters[0].get("filterType") == "ABI" and filters[0].get("value") in ABIS:
            abi = filters[0]["value"]
        else:
            raise ValueError("Unexpected ABI filters")
        if abi in outputs:
            raise ValueError("Duplicate ABI output")
        filename = element.get("outputFile")
        if not isinstance(filename, str) or Path(filename).is_absolute():
            raise ValueError("Output filename must be relative")
        apk = (directory / filename).resolve()
        if not apk.is_relative_to(directory) or apk.suffix != ".apk" or not apk.is_file() or apk in outputs.values():
            raise ValueError(f"Missing or unsafe APK output: {filename}")
        outputs[abi] = apk
    if set(outputs) != {None, *ABIS}:
        raise ValueError("Missing universal or ABI APK")
    return tag, {abi: outputs[abi] for abi in (None, *ABIS)}


def verify_identity(badging, tag):
    feature, patch = parse_version(tag)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if package is None or package.groups() != ("app.glimpse", str(feature * 10_000 + patch), f"{feature}.{patch}") or re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE):
        raise ValueError("APK identity or debuggable flag does not match the release")


def verify_certificate(verification, certificate):
    if re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", verification) != [certificate]:
        raise ValueError("APK is not signed by Glimpse's pinned release key")


def verify_elf(data, abi):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] not in (1, 2):
        raise ValueError("Invalid ELF library")
    endian = "<" if data[5] == 1 else ">"
    if (data[4], struct.unpack_from(endian + "H", data, 18)[0]) != ELF_ARCHITECTURES[abi]:
        raise ValueError("Native architecture does not match ABI")
    if data[4] == 2:
        offset = struct.unpack_from(endian + "Q", data, 32)[0]
        size, count = struct.unpack_from(endian + "HH", data, 54)
        if size < 56 or count == 0 or offset + size * count > len(data):
            raise ValueError("Invalid ELF program headers")
        for index in range(count):
            position = offset + index * size
            if struct.unpack_from(endian + "I", data, position)[0] == 1:
                alignment = struct.unpack_from(endian + "Q", data, position + 48)[0]
                if alignment < 16384:
                    raise ValueError(f"{abi} library lacks 16 KB page support")


def native_libraries(apk, abi):
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[1] not in ABIS or entry.filename in libraries:
                raise ValueError(f"Unexpected native library: {entry.filename}")
            data = archive.read(entry)
            try:
                verify_elf(data, parts[1])
            except ValueError as error:
                raise ValueError(f"{entry.filename}: {error}") from error
            libraries[entry.filename] = (hashlib.sha256(data).hexdigest(), len(data))
    expected = set(ABIS) if abi is None else {abi}
    if {name.split('/')[1] for name in libraries} != expected:
        raise ValueError(f"Incorrect native ABI set in {apk.name}")
    return libraries


def verify_build(outputs, tag, tools, certificate=None):
    contents = {}
    for abi, apk in outputs.items():
        verify_identity(subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True), tag)
        contents[abi] = native_libraries(apk, abi)
        subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True, capture_output=True)
        if certificate is not None:
            verification = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
            verify_certificate(verification, certificate)
        print(f"Checked {abi or 'universal'}: {apk.stat().st_size / 1024**2:.1f} MiB")
    for abi in ABIS:
        if contents[abi] != {name: value for name, value in contents[None].items() if name.startswith(f"lib/{abi}/")}:
            raise ValueError(f"Native contents differ between {abi} and universal APKs")


def asset_name(tag, abi):
    return f"glimpse-{tag}{f'-{abi}' if abi else ''}.apk"


def download_notes(tag, outputs):
    rows = ["<!-- glimpse-downloads -->", "", "### Downloads", "", "**Pixel 10 Pro: ARM64. Unsure: Universal.** Each APK is a complete installer.", "", "| Architecture | APK | Size |", "| --- | --- | ---: |"]
    for abi, apk in outputs.items():
        name = asset_name(tag, abi)
        rows.append(f"| {LABELS[abi]} | [{name}](https://github.com/0xjogge/glimpse/releases/download/{tag}/{name}) | {apk.stat().st_size / 1024**2:.1f} MiB |")
    rows += ["", "All builds share the package ID, permanent signing key, and version code. [Setup and Obtainium guidance](https://github.com/0xjogge/glimpse/blob/main/RELEASING.md#obtainium).", ""]
    return "\n".join(rows)


def stage_assets(outputs, tag, output):
    output = Path(output).resolve()
    if any(apk.is_relative_to(output) for apk in outputs.values()):
        raise ValueError("Staging directory must not contain input APKs")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    for abi, apk in outputs.items():
        shutil.copyfile(apk, output / asset_name(tag, abi))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", nargs="?")
    parser.add_argument("--apk-directory", type=Path, default=Path("app/build/outputs/apk/release"))
    parser.add_argument("--check-build", action="store_true")
    parser.add_argument("--notes-file", type=Path)
    args = parser.parse_args()
    if not args.check_build and args.tag is None:
        parser.error("Supply a release tag to stage signed APKs")
    if args.check_build and args.notes_file:
        parser.error("Check mode cannot update release notes")
    try:
        tag, outputs = read_outputs(args.apk_directory, args.tag)
        tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
        certificate = None if args.check_build else Path(".github/release-signing-certificate.sha256").read_text().strip()
        verify_build(outputs, tag, tools, certificate)
        if not args.check_build:
            stage_assets(outputs, tag, Path("build/release"))
            if args.notes_file:
                original = args.notes_file.read_text().split("<!-- glimpse-downloads -->", 1)[0].rstrip()
                args.notes_file.write_text(f"{original}\n\n{download_notes(tag, outputs)}")
            print("All five signed release APKs staged in build/release")
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
