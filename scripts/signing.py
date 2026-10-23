#!/usr/bin/env python3
"""Create or reuse Glimpse's permanent release key, back it up, and run signed builds."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / ".signing"
KEYSTORE = BACKUP / "glimpse-release.p12"
CREDENTIALS = BACKUP / "credentials.json"
CERTIFICATE = ROOT / ".github/release-signing-certificate.sha256"


def signing_environment():
    return {**os.environ, **json.loads(CREDENTIALS.read_text()), "GLIMPSE_KEYSTORE_PATH": str(KEYSTORE)}


def setup(upload, repo):
    BACKUP.mkdir(mode=0o700, exist_ok=True)
    if not CREDENTIALS.exists():
        if KEYSTORE.exists():
            raise SystemExit("Restore credentials.json alongside the existing keystore first")
        password = secrets.token_urlsafe(48)
        credentials = {"GLIMPSE_KEYSTORE_PASSWORD": password, "GLIMPSE_KEY_ALIAS": "glimpse-release", "GLIMPSE_KEY_PASSWORD": password}
        with open(CREDENTIALS, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
            json.dump(credentials, output, indent=2)
            output.write("\n")
    environment = signing_environment()
    if not KEYSTORE.exists():
        subprocess.run([
            "keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(KEYSTORE),
            "-alias", environment["GLIMPSE_KEY_ALIAS"], "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
            "-dname", "CN=Glimpse, O=Glimpse", "-storepass:env", "GLIMPSE_KEYSTORE_PASSWORD", "-keypass:env", "GLIMPSE_KEY_PASSWORD",
        ], env=environment, check=True)
        KEYSTORE.chmod(0o600)
    certificate = subprocess.check_output(["keytool", "-exportcert", "-keystore", str(KEYSTORE), "-alias", environment["GLIMPSE_KEY_ALIAS"], "-storepass:env", "GLIMPSE_KEYSTORE_PASSWORD"], env=environment)
    digest = hashlib.sha256(certificate).hexdigest()
    if CERTIFICATE.exists() and CERTIFICATE.read_text().strip() != digest:
        raise SystemExit("The backup key does not match the pinned release certificate")
    CERTIFICATE.parent.mkdir(exist_ok=True)
    CERTIFICATE.write_text(f"{digest}\n")
    if upload:
        values = {name: environment[name] for name in ("GLIMPSE_KEYSTORE_PASSWORD", "GLIMPSE_KEY_ALIAS", "GLIMPSE_KEY_PASSWORD")}
        values["GLIMPSE_KEYSTORE_BASE64"] = base64.b64encode(KEYSTORE.read_bytes()).decode()
        for name, value in values.items():
            subprocess.run(["gh", "secret", "set", name, "--repo", repo], input=value, text=True, check=True)
            print(f"Uploaded {name}")
    print(f"Release-key backup: {BACKUP}\nCertificate SHA-256: {digest}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup_parser = commands.add_parser("setup")
    setup_parser.add_argument("--upload", action="store_true")
    setup_parser.add_argument("--repo", default="0xjogge/glimpse")
    commands.add_parser("run").add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == "setup":
        setup(args.upload, args.repo)
    else:
        if not args.args:
            parser.error("Supply a command, for example: run ./gradlew :app:assembleRelease")
        raise SystemExit(subprocess.run(args.args, env=signing_environment(), cwd=ROOT).returncode)


if __name__ == "__main__":
    main()
