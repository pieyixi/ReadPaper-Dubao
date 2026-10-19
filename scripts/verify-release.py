"""校验真正的正式包：版本、非调试标志、原始签名及 APK 完整性。"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ORIGINAL_CERTIFICATE = "0c22a51a5eda729e3e45e742e7cbd326484120b4fc924c0c6e9ddd847b4a9669"


def verify(apk):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    tools = sdk / "build-tools" / "34.0.0"
    suffix = ".bat" if os.name == "nt" else ""
    signed = subprocess.check_output(
        [str(tools / ("apksigner" + suffix)), "verify", "--verbose", "--print-certs", str(apk)],
        text=True,
    )
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signed)
    if not certificate or certificate.group(1).lower() != ORIGINAL_CERTIFICATE:
        raise RuntimeError("Release certificate differs from the original signing key")
    if "Verified using v1 scheme (JAR signing): true" not in signed:
        raise RuntimeError("Android 5 compatibility requires v1 signing")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in signed:
        raise RuntimeError("Release v2 signature verification failed")
    badging = subprocess.check_output(
        [str(tools / ("aapt.exe" if os.name == "nt" else "aapt")), "dump", "badging", str(apk)],
        text=True,
    )
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != ("com.newspaper.helper", "8", "2.0.0"):
        raise RuntimeError("Unexpected Release application ID or version")
    if "application-debuggable" in badging:
        raise RuntimeError("Refusing to publish a debuggable APK")
    result = dict(package=package[1], versionCode=int(package[2]), versionName=package[3],
                  debuggable=False, certificateSha256=certificate[1].lower(),
                  sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), bytes=apk.stat().st_size,
                  signatureV1=True, signatureV2=True)
    target = Path("app/build/release-evidence")
    target.mkdir(parents=True, exist_ok=True)
    (target / "verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
