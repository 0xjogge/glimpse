# Releasing Glimpse

Glimpse follows Frem's release setup: two-component release tags, Conventional Commit notes, a permanent backed-up signing key, optimized APKs, five verified architecture assets, and draft-first publication.

## Versions and tags

Tags use **`<feature>.<patch>-release`**. `0.1-release` produces version name `0.1`, code `1`; `1.0-release` produces name `1.0`, code `10000`. Code = `feature * 10000 + patch`, without ABI offsets. Patches range from 0 to 9999; published versions must increase.

Once `main` passes CI:

For a full pre-tag check including model downloads and networking-disabled translation, run:

```sh
gh workflow run ci.yml -f download-models=true
```

Wait for that run to pass before tagging:

```sh
git switch main
git pull --ff-only
git tag -a 0.5-release -m "Release 0.5"
git push origin 0.5-release
```

The [release workflow](https://github.com/0xjogge/glimpse/actions/workflows/release.yml) validates tag ancestry/version, runs build/lint/unit/device checks (including real model download and networking-disabled translation), signs optimized APKs, checks their package/version/non-debuggable status, pinned certificate, native ABI/content parity, and 16 KB ELF/ZIP alignment, then uploads all five APKs to a draft before publishing it as stable/latest.

Published release assets are immutable in this workflow. Failed runs can resume an unpublished draft; fix the cause and rerun failed jobs. Use a new patch version for a changed published APK.

## APKs

| Installer                             | Purpose                             |
| ------------------------------------- | ----------------------------------- |
| `glimpse-0.5-release.apk`             | Universal                           |
| `glimpse-0.5-release-arm64-v8a.apk`   | Pixel 10 Pro / modern ARM64 phones  |
| `glimpse-0.5-release-armeabi-v7a.apk` | ARM32                               |
| `glimpse-0.5-release-x86_64.apk`      | 64-bit Intel / development emulator |
| `glimpse-0.5-release-x86.apk`         | 32-bit Intel                        |

All share `app.glimpse`, the same version code, and one permanent signing certificate, allowing compatible architecture/universal updates without losing data. Release notes include measured APK sizes. Native libraries must match the corresponding universal-build contents and 64-bit libraries must support 16 KB pages.

## Signing and backup

The Git-ignored **`.signing/` directory** contains the permanent `glimpse-release.p12` RSA-4096 key and `credentials.json`. Keep a secure backup of the entire directory; future updates require this identity. The public fingerprint is pinned in `.github/release-signing-certificate.sha256`.

```sh
nix develop --command python3 scripts/signing.py setup --upload
```

This generates an initial key or verifies/reuses the existing one, then uploads `GLIMPSE_KEYSTORE_BASE64`, `GLIMPSE_KEYSTORE_PASSWORD`, `GLIMPSE_KEY_ALIAS`, and `GLIMPSE_KEY_PASSWORD` repository secrets. It refuses a key that differs from the pinned fingerprint. Restore the existing backup before using setup on another machine.

Build and inspect signed release APKs locally:

```sh
nix develop --command python3 scripts/signing.py run \
  ./gradlew :app:assembleRelease -PglimpseVersion=0.5 -PglimpseSplitApks=true
nix develop --command python3 scripts/package-release.py 0.5-release
```

Verified installers are staged in `build/release/`. CI's `--check-build` mode inspects unsigned optimized outputs without staging or publishing them. Normal development leaves ABI splitting disabled; pass `glimpseSplitApks` only to release builds.

The optimized APK smoke helper installs a signed release on the development emulator, exercises the real UI, and writes a report:

```sh
nix develop --command glimpse-emulator --headless -- \
  python3 scripts/smoke-release.py build/release/glimpse-0.5-release-x86_64.apk
```

It replaces the development installation on that emulator because debug/release signing identities differ. The report and screenshots are saved under `build/reports/release-smoke/`.

## Changelog

Use Conventional Commit titles (`feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `chore`, `test`, `style`, `ci`). The PR-title workflow enforces this. `changelogen` produces grouped notes and contributors from local Git history.

```sh
npm ci --ignore-scripts
npm run release:notes -- 0.5-release HEAD 0.4-release
```

For subsequent releases append the previous published tag. Notes are written to `build/release-notes.md`; packaging can append download sizes with `--notes-file build/release-notes.md`.

## Obtainium

Add `https://github.com/0xjogge/glimpse` as a GitHub source. While the repository is private, access requires GitHub authentication. Use stable releases and select exactly one APK filter:

| Build        | Filter                                            |
| ------------ | ------------------------------------------------- |
| ARM64        | `^glimpse-[0-9]+\.[0-9]+-release-arm64-v8a\.apk$` |
| Universal    | `^glimpse-[0-9]+\.[0-9]+-release\.apk$`           |
| Intel 64-bit | `^glimpse-[0-9]+\.[0-9]+-release-x86_64\.apk$`    |

Subsequent releases use increasing version codes and the same signing key. Obtainium reads the GitHub release notes and installs updates normally.
