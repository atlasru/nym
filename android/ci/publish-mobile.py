#!/usr/bin/env python3
"""Publish verified debug artifacts. No release signing credentials."""
import base64
import hashlib
import json
import os
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path("download")
apk_dir = root / "apk"
apk = apk_dir / "Nym_Mobile_0.1.1_arm64-debug.apk"
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest == (apk_dir / "SHA256SUMS.txt").read_text().split()[0]
unit_reports = list((root / "unit").rglob("TEST-*.xml"))
unit_cases = [c for report in unit_reports for c in ET.parse(report).getroot().iter("testcase")]
assert unit_cases
assert not any(c.find("failure") is not None or c.find("error") is not None for c in unit_cases)
summaries = []
for api in (29, 36):
    reports = list((root / f"api{api}").rglob("TEST-*.xml"))
    assert reports, f"No device report for API {api}"
    cases = []
    for report in reports:
        cases.extend(ET.parse(report).getroot().iter("testcase"))
    assert not any(
        c.find("failure") is not None or c.find("error") is not None for c in cases
    )
    skipped = sum(c.find("skipped") is not None for c in cases)
    summaries.append(f"API {api}: {len(cases)-skipped} passed, {skipped} skipped")
screens = root / "api36" / "device-artifacts" / "screenshots"
images = sorted(screens.glob("*.png"))
assert len(images) >= 5, "Real device screenshots missing"
cooldown = (screens / "429-stop.txt").read_text()
def read_evidence(api, name):
    return (root / f"api{api}" / "device-artifacts" / "screenshots" / name).read_text()


recovery = [
    read_evidence(api, "process-recovery.txt") + read_evidence(api, "cooldown-process-recovery.txt")
    for api in (29, 36)
]
performance = [read_evidence(api, "performance.txt") for api in (29, 36)]
out = Path("published")
out.mkdir(exist_ok=True)
validation = out / "VALIDATION.md"
source = os.environ["SOURCE_COMMIT"]
run_url = f"https://github.com/{os.environ['GH_REPO']}/actions/runs/{os.environ['RUN_ID']}"
fence = chr(96) * 3
validation.write_text(
    "# Nym Mobile 0.1.1 validation\n\n"
    f"Source commit: {source}\n\nCI: {run_url}\n\n"
    f"- Kotlin core: {len(unit_cases)} automated tests.\n"
    + "".join(f"- {s}\n" for s in summaries)
    + "- APK installed and launched by the real Android test runner.\n"
    "- Actual force-stop/manual-relaunch restores an interrupted random session, "
    "reserves the same pending name and completes without skipped or duplicate results.\n"
    "- Navigation, recreation, landscape, notification actions, background checks, "
    "checkpoint/resume and SQLite reopening tested.\n"
    "- HTTP authentication, authenticated SOCKS5 and TLS through HTTPS proxies tested "
    "using loopback fixtures on Android.\n"
    "- HTTP 429: mocked 1800-second Retry-After, immediate Stop; exact timings below.\n"
    "- Debug certificate is public. Release signing credentials are not configured.\n"
    "- x86_64 emulators; no physical ARM64, live VPN-provider, cellular-radio "
    "or battery measurements.\n"
    "- Android system routing is preserved; a proxy does not automatically bypass a VPN.\n\n"
    f"APK SHA-256: {digest}\n\n"
    "## HTTP 429 Stop\n\n" + fence + "text\n" + cooldown + fence + "\n\n"
    "## Emulator performance\n\n"
    + "".join(fence + "text\n" + p + fence + "\n\n" for p in performance)
)
bundle = out / "Nym_Mobile_0.1.1_validation.zip"
with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(validation, "VALIDATION.md")
    for directory in (root / "api29", root / "api36", root / "unit"):
        for item in sorted(directory.rglob("*")):
            if item.is_file():
                z.write(item, item.relative_to(root))
tag = "nym-mobile-v0.1.1-debug"
notes = out / "release-notes.md"
notes.write_text(
    "Native Kotlin + Compose Android app, Android 10–16, ARM64 primary.\n\n"
    f"Source: {source}\nCI: {run_url}\n\n"
    "Debug APK, SHA-256, real emulator screenshots and validation reports are attached. "
    "Debug-signed prerelease; release signing is not configured.\n\n"
    "See the Android README for generation, proxy/VPN behavior and foreground-service limits.\n\n"
    + "\n".join(summaries)
    + "\n\nMock HTTP 429 Stop:\n" + fence + "\n" + cooldown + fence + "\n"
)
existing = subprocess.run(
    ["gh", "release", "view", tag],
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
)
if existing.returncode:
    subprocess.run(
        ["gh", "release", "create", tag, "--target", source, "--prerelease", "--latest=false",
         "--title", "Nym Mobile 0.1.1 (Android debug)", "--notes-file", str(notes)],
        check=True,
    )
else:
    subprocess.run(
        ["gh", "api", "--method", "PATCH",
         f"repos/{os.environ['GH_REPO']}/git/refs/tags/{tag}",
         "-f", f"sha={source}", "-F", "force=true"],
        check=True,
    )
    subprocess.run(
        ["gh", "release", "edit", tag, "--notes-file", str(notes),
         "--prerelease", "--latest=false"],
        check=True,
    )
subprocess.run(
    ["gh", "release", "upload", tag, str(apk), str(apk_dir / "SHA256SUMS.txt"),
     str(apk_dir / "SIGNATURE.txt"), str(validation), str(bundle),
     *map(str, images), "--clobber"],
    check=True,
)
for image in images:
    payload = json.dumps({
        "encoding": "base64",
        "content": base64.b64encode(image.read_bytes()).decode(),
    }).encode()
    request = urllib.request.Request(
        f"https://api.github.com/repos/{os.environ['GH_REPO']}/git/blobs",
        data=payload,
        headers={
            "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request) as response:
        blob = json.load(response)
    print(f"SCREENSHOT_BLOB {image.name} {blob['sha']}")
print("\n".join(summaries))
print("MOCK_429_STOP\n" + cooldown)
print("PROCESS_RECOVERY\n" + "\n".join(recovery))
print("EMULATOR_PERFORMANCE\n" + "\n".join(performance))
print("APK_SHA256 " + digest)
print(f"RELEASE https://github.com/{os.environ['GH_REPO']}/releases/tag/{tag}")
