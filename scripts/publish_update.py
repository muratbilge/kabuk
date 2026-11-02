#!/usr/bin/env python3
"""Publish a signed Kabuk APK for the in-app updater (format shared with Paperscanner's VerifiedUpdater).

  publish_update.py <signed.apk> <share-dir> [--key PEM] [--notes TEXT]

Copies the APK as kabuk-<versionCode>.apk into <share-dir> (immutable: an existing file must be identical), then writes
<share-dir>/update.json = {"payload": base64(json), "signature": base64(RSA-SHA256(payload))}. The private manifest key
stays outside the repo (default ~/.android-keys/kabuk-update-manifest.pem); the app only holds the public key.
"""
import argparse, base64, hashlib, json, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path

PACKAGE = "app.kabuk"


def run(args, data=None):
    r = subprocess.run(args, input=data, capture_output=True)
    if r.returncode:
        sys.exit(f"{args[0]} failed: {r.stderr.decode(errors='replace')[:300]}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path)
    ap.add_argument("share", type=Path)
    ap.add_argument("--key", type=Path, default=Path.home() / ".android-keys/kabuk-update-manifest.pem")
    ap.add_argument("--notes", default="")
    ap.add_argument("--build-tools", type=Path,
                    default=Path(os.environ.get("BUILD_TOOLS", Path.home() / "Android/Sdk/build-tools/35.0.0")))
    a = ap.parse_args()
    bt = a.build_tools
    badging = run([str(bt / "aapt2"), "dump", "badging", str(a.apk)]).decode()
    pkg = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    mn = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$",
                       run([str(bt / "apksigner"), "verify", "--verbose", "--print-certs", str(a.apk)]).decode(), re.M)
    if not pkg or not mn or len(certs) != 1 or pkg[1] != PACKAGE:
        sys.exit("expected a verified single-signer app.kabuk APK")
    code = int(pkg[2])
    sha = hashlib.sha256(a.apk.read_bytes()).hexdigest()
    name = f"kabuk-{code}.apk"
    payload = json.dumps({
        "packageName": PACKAGE, "versionCode": code, "versionName": pkg[3], "minSdk": int(mn[1]),
        "certificateSha256": certs[0].lower(), "apk": name, "size": a.apk.stat().st_size, "sha256": sha,
        "notes": a.notes[:4000],
    }, separators=(",", ":"), ensure_ascii=False).encode()
    sig = run(["openssl", "dgst", "-sha256", "-sign", str(a.key), "-passin", "pass:"], payload)
    envelope = json.dumps({"payload": base64.b64encode(payload).decode(), "signature": base64.b64encode(sig).decode()},
                          separators=(",", ":")).encode()
    a.share.mkdir(parents=True, exist_ok=True)
    target = a.share / name
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != sha:
        sys.exit(f"{target} already exists with different content (published builds are immutable): bump versionCode")
    if not target.exists():
        shutil.copyfile(a.apk, target)
    with tempfile.NamedTemporaryFile(dir=a.share, delete=False, prefix=".update-") as t:
        t.write(envelope)
    os.chmod(t.name, 0o644)
    os.replace(t.name, a.share / "update.json")   # atomic: the phone never sees a half-written manifest
    print(f"published {name} (versionCode {code}, {pkg[3]}) + update.json in {a.share}")


main()
