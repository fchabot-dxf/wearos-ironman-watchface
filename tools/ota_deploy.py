#!/usr/bin/env python3
"""
tools/ota_deploy.py — Host-Side Pipeline for Wear OS In-App Self-Updater

Workflow:
1. Bumps `versionCode` in app/build.gradle.kts.
2. Compiles the APK via gradlew.bat assembleDebug.
3. Computes the SHA-256 hash and size of the output APK.
4. Copies APK to Cloudflare Pages distribution folder (progress-board/shots/out/watch).
5. Deploys to Cloudflare Pages CDN (https://app-progress-board.pages.dev/watch/app-debug.apk).
6. Pushes manifest to Cloudflare Worker (https://progress-board.dansemur.workers.dev/watch/ota).
7. Optionally publishes to GitHub Releases via `gh release create`.
"""

import sys
import os
import re
import shutil
import hashlib
import json
import subprocess
import argparse
from datetime import datetime, timezone
from pathlib import Path
import urllib.request

REPO_ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = REPO_ROOT / "app" / "build.gradle.kts"
APK_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
MANIFEST_PATH = REPO_ROOT / "ota_manifest.json"

PROGRESS_BOARD_ROOT = Path(r"C:\Users\danse\APPS\progress-board")
PAGES_WATCH_DIR = PROGRESS_BOARD_ROOT / "shots" / "out" / "watch"
KEY_FILE = Path.home() / ".ddcs-bridge" / "progress-push-key.txt"
WORKER_OTA_URL = "https://progress-board.dansemur.workers.dev/watch/ota"
PUBLIC_APK_URL = "https://progress-board.dansemur.workers.dev/watch/apk"


def bump_version_code() -> tuple[int, str]:
    content = BUILD_GRADLE.read_text(encoding="utf-8")
    code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    if not code_match:
        print("[ERROR] Could not find 'versionCode' in app/build.gradle.kts", file=sys.stderr)
        sys.exit(1)
        
    old_code = int(code_match.group(1))
    new_code = old_code + 1
    
    content = re.sub(
        r"versionCode\s*=\s*\d+",
        f"versionCode = {new_code}",
        content
    )
    
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    new_name = f"1.0.{new_code}"
    if name_match:
        content = re.sub(
            r'versionName\s*=\s*"[^"]+"',
            f'versionName = "{new_name}"',
            content
        )
        
    BUILD_GRADLE.write_text(content, encoding="utf-8")
    print(f"[PIPELINE] Bumped versionCode: {old_code} -> {new_code} (versionName: {new_name})")
    return new_code, new_name


def build_apk():
    print("[PIPELINE] Compiling APK (gradlew.bat assembleDebug)...")
    env = os.environ.copy()
    if not env.get("JAVA_HOME") or not Path(env["JAVA_HOME"]).exists():
        default_jdk = Path(r"C:\Users\danse\AppData\Local\Android\jdk17\jdk-17.0.12+7")
        if default_jdk.exists():
            env["JAVA_HOME"] = str(default_jdk)
            env["PATH"] = f"{default_jdk / 'bin'}{os.pathsep}{env.get('PATH', '')}"

    gradle_cmd = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [str(REPO_ROOT / gradle_cmd), "assembleDebug"]
    
    result = subprocess.run(cmd, cwd=str(REPO_ROOT), env=env)
    if result.returncode != 0:
        print("[ERROR] Build failed!", file=sys.stderr)
        sys.exit(result.returncode)
        
    if not APK_PATH.exists():
        print(f"[ERROR] APK not found at {APK_PATH}", file=sys.stderr)
        sys.exit(1)
        
    print(f"[PIPELINE] Build successful: {APK_PATH} ({APK_PATH.stat().st_size:,} bytes)")


def compute_sha256(file_path: Path) -> str:
    hasher = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def write_manifest(version_code: int, version_name: str, sha256_hash: str) -> dict:
    manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "apkFile": "app-debug.apk",
        "apkSize": APK_PATH.stat().st_size,
        "sha256": sha256_hash,
        "apkUrl": PUBLIC_APK_URL,
        "timestamp": datetime.now(timezone.utc).isoformat()
    }
    
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"[PIPELINE] Wrote local OTA manifest to {MANIFEST_PATH}")
    return manifest


def deploy_to_cloudflare(manifest: dict):
    if not PROGRESS_BOARD_ROOT.exists():
        print(f"[WARN] Progress board directory not found at {PROGRESS_BOARD_ROOT}")
        return

    PAGES_WATCH_DIR.mkdir(parents=True, exist_ok=True)
    target_apk = PAGES_WATCH_DIR / "app-debug.apk"
    target_manifest = PAGES_WATCH_DIR / "ota_manifest.json"

    print(f"[PIPELINE] Copying APK to Pages staging directory ({target_apk})...")
    shutil.copy2(APK_PATH, target_apk)
    shutil.copy2(MANIFEST_PATH, target_manifest)

    # 1. Deploy Pages
    print("[PIPELINE] Deploying APK to Cloudflare Pages CDN...")
    cmd = ["npx.cmd" if os.name == "nt" else "npx", "wrangler", "pages", "deploy", "shots/out",
           "--project-name", "app-progress-board", "--branch", "main", "--commit-dirty=true"]
    res = subprocess.run(cmd, cwd=str(PROGRESS_BOARD_ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if res.returncode == 0:
        print("[PIPELINE] Cloudflare Pages deploy succeeded!")
    else:
        print(f"[WARN] Pages deploy warning:\n{res.stderr}", file=sys.stderr)

    # 2. Push manifest to Worker
    if KEY_FILE.exists():
        push_key = KEY_FILE.read_text(encoding="utf-8-sig").strip()
        url = f"{WORKER_OTA_URL}?k={push_key}"
        data = json.dumps(manifest).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 200:
                    print(f"[PIPELINE] Successfully posted manifest to Worker: {WORKER_OTA_URL}")
                else:
                    print(f"[WARN] Worker responded with status {resp.status}")
        except Exception as e:
            print(f"[WARN] Failed to post manifest to Worker: {e}", file=sys.stderr)


def publish_github_release(version_code: int, version_name: str):
    tag = f"v{version_code}"
    print(f"[PIPELINE] Creating GitHub Release {tag} via gh CLI...")
    cmd = [
        "gh", "release", "create", tag,
        str(APK_PATH),
        str(MANIFEST_PATH),
        "--title", f"OTA Build {tag} ({version_name})",
        "--notes", f"Automated OTA build deployed at {datetime.now(timezone.utc).isoformat()}"
    ]
    res = subprocess.run(cmd, cwd=str(REPO_ROOT), capture_output=True, text=True)
    if res.returncode == 0:
        print(f"[PIPELINE] Successfully published release {tag} to GitHub!")
    else:
        print(f"[WARN] gh release output: {res.stderr or res.stdout}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description="Wear OS Host-Side OTA Deployment Pipeline")
    parser.add_argument("--no-bump", action="store_true", help="Do not bump versionCode")
    parser.add_argument("--no-gh", action="store_true", help="Skip GitHub release")
    args = parser.parse_args()

    if args.no_bump:
        content = BUILD_GRADLE.read_text(encoding="utf-8")
        code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
        name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
        version_code = int(code_match.group(1)) if code_match else 1
        version_name = name_match.group(1) if name_match else "1.0.0"
    else:
        version_code, version_name = bump_version_code()

    build_apk()
    sha256 = compute_sha256(APK_PATH)
    manifest = write_manifest(version_code, version_name, sha256)

    # Deploy to Cloudflare edge
    deploy_to_cloudflare(manifest)

    if not args.no_gh:
        publish_github_release(version_code, version_name)

    print("\n" + "=" * 60)
    print(f"OTA Pipeline Complete!")
    print(f"Manifest URL: {WORKER_OTA_URL}")
    print(f"Download URL: {PUBLIC_APK_URL}")
    print("=" * 60)


if __name__ == "__main__":
    main()
