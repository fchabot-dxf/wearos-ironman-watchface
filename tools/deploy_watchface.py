#!/usr/bin/env python3
"""
tools/deploy_watchface.py — Unified Agent Deployment Tool for Wear OS Watch Faces

Features:
  - --ota, -o      : Build & publish release to GitHub Releases OTA + update ota_manifest.json
  - --local, -l    : Build & install directly to Pixel Watch over local Wi-Fi ADB (auto-discovers port)
  - --both, -b     : Publish to GitHub Releases OTA AND install locally to watch
  - --screencap, -s: Take live screencap from watch
  - --list         : List detected watch faces and current version
  - --layout       : News layout mode: '2liner' (default, 22pt) or '3liner' (19pt)
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
RAW_DIR = REPO_ROOT / "app" / "src" / "main" / "res" / "raw"
XML_DIR = REPO_ROOT / "app" / "src" / "main" / "res" / "xml"

GITHUB_REPO = "fchabot-dxf/wearos-ironman-watchface"
ADB_PATH = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
if not ADB_PATH.exists():
    ADB_PATH = Path(r"C:\Users\danse\APPS\WearOS\tools\platform-tools\adb.exe")

# ── News Stories ─────────────────────────────────────────────────────────────
# Each story has:
#   "title"   — short CAPS label shown before the bullet (2-liner line 1, 3-liner line 1)
#   "line1"   — body line 1 (2-liner) / continuation after title (3-liner)
#   "line2"   — body line 2 (2-liner) / 3-liner line 2
#   "line3"   — 3-liner line 3 only (ignored in 2-liner mode)
NEWS_STORIES = [
    {
        "title":  "ARTEMIS III",
        "line1":  "Mission 2026",
        "line2":  "4 astronautes vers le pôle Sud.",
        "line1_3": "Mission lunaire 2026",
        "line2_3": "Quatre astronautes vers le pôle Sud,",
        "line3":  "recherche d'eau glacée permanente.",
    },
    {
        "title":  "QUANTIQUE",
        "line1":  "Hub de Sherbrooke",
        "line2":  "Pôle d'excellence en calcul avancé.",
        "line1_3": "Hub d'innovation majeur",
        "line2_3": "Le Québec propulsé en tête mondiale",
        "line3":  "du calcul et de la physique avancée.",
    },
    {
        "title":  "FUSION",
        "line1":  "Record mondial net",
        "line2":  "Plasma stable de six minutes.",
        "line1_3": "Nouveau record mondial net",
        "line2_3": "Plasma stable maintenu six minutes,",
        "line3":  "percée vers une énergie propre infinie.",
    },
]

# ── News XML generator ────────────────────────────────────────────────────────

def _part_text(x, y, w, h, size, text, last=False):
    """Return a single <PartText> block as a string."""
    return (
        f'            <PartText x="{x}" y="{y}" width="{w}" height="{h}">\n'
        f'                <Variant mode="AMBIENT" target="alpha" value="102"/>\n'
        f'                <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="{size}" '
        f'weight="NORMAL" color="#38BDF8"><Template>{text}</Template></Font></Text>\n'
        f'            </PartText>\n'
    )


def generate_news_xml(layout: str = "2liner") -> str:
    """
    Generate the full <Condition>…</Condition> news block.

    layout: '2liner' → 2 lines @ 22pt, all 6 slots use 2-liner format
            '3liner' → 3 lines @ 19pt, all 6 slots use 3-liner format
    """
    stories = NEWS_STORIES  # extend this list to add more stories
    n = len(stories)

    lines = []
    lines.append("        <!-- ======================================================== -->")
    lines.append(f"        <!-- NEWS STREAM ({layout}: {'22pt × 2' if layout == '2liner' else '19pt × 3'} lines per slot) -->")
    lines.append("        <!-- Stationary page flip every 12s, zero battery drain       -->")
    lines.append("        <!-- ======================================================== -->")
    lines.append("        <Condition>")
    lines.append("            <Expressions>")
    for i in range(n - 1):
        lines.append(
            f'                <Expression name="h{i}"><![CDATA[([IS_AMBIENT] ? [MINUTE] : floor([SECOND] / 12)) % {n} == {i}]]></Expression>'
        )
    lines.append("            </Expressions>")

    for i, story in enumerate(stories):
        is_last = (i == n - 1)
        tag_open  = f"            <{'Default' if is_last else f'Compare expression=\"h{i}\"'}>"
        tag_close = f"            </{'Default' if is_last else 'Compare'}>"

        lines.append(f"            <!-- Slot {i}: {story['title']} -->" )
        lines.append(tag_open)

        if layout == "2liner":
            # Line 1: TITLE · line1
            lines.append(_part_text(15, 104, 420, 32, 22,
                                    f"{story['title']} · {story['line1']}"))
            # Line 2: line2
            lines.append(_part_text(15, 136, 420, 32, 22, story["line2"]))
        else:
            # 3-liner: TITLE · line1_3 / line2_3 / line3
            lines.append(_part_text(15, 97,  420, 25, 19,
                                    f"{story['title']} · {story['line1_3']}"))
            lines.append(_part_text(15, 122, 420, 25, 19, story["line2_3"]))
            lines.append(_part_text(15, 147, 420, 25, 19, story["line3"]))

        lines.append(tag_close)

    lines.append("        </Condition>")
    return "\n".join(lines)


def log(msg: str):
    print(f"\033[96m[DEPLOY]\033[0m {msg}", flush=True)


def warn(msg: str):
    print(f"\033[93m[WARN]\033[0m {msg}", flush=True)


def error(msg: str):
    print(f"\033[91m[ERROR]\033[0m {msg}", file=sys.stderr, flush=True)


def read_params_xml() -> dict:
    """Parse res/values/params.xml and return a dict of name→value."""
    params_path = REPO_ROOT / "app" / "src" / "main" / "res" / "values" / "params.xml"
    if not params_path.exists():
        return {}
    content = params_path.read_text(encoding="utf-8")
    return dict(re.findall(r'name="([^"]+)">([^<]+)</', content))


def apply_news_layout(layout: str):
    """
    If a dynamic ComplicationSlot is present, preserve it.
    Otherwise regenerate the static Condition news block.
    """
    src = RAW_DIR / "watch_face.xml"
    if not src.exists():
        src = RAW_DIR / "watchface.xml"
    if not src.exists():
        warn("watch_face.xml not found — skipping news layout injection.")
        return

    content = src.read_text(encoding="utf-8")
    if "LIVE NEWS STREAM COMPLICATION" in content:
        log("Dynamic News ComplicationSlot detected — preserving live complication.")
        return

    new_block = generate_news_xml(layout)

    # Replace the entire news Condition block (from the first NEWS STREAM comment to </Condition>)
    pattern = r"(        <!-- ={10,} -->\r?\n        <!-- NEWS STREAM.*?        </Condition>)"
    replacement = new_block
    updated, count = re.subn(pattern, replacement, content, flags=re.DOTALL)
    if count == 0:
        warn("Could not find NEWS STREAM block in watch_face.xml — layout not applied.")
        return

    src.write_text(updated, encoding="utf-8")
    log(f"News layout applied: {layout} ({len(NEWS_STORIES)} stories)")



def sync_xml_files():
    """Ensure watch_face.xml, watchface.xml, and res/xml/watch_face.xml are perfectly in sync."""
    src = RAW_DIR / "watch_face.xml"
    if not src.exists():
        src = RAW_DIR / "watchface.xml"
    if not src.exists():
        warn("Source watch face XML not found in res/raw.")
        return

    dst1 = RAW_DIR / "watchface.xml"
    dst2 = RAW_DIR / "watch_face.xml"
    dst3 = XML_DIR / "watch_face.xml"

    content = src.read_text(encoding="utf-8")
    for dst in [dst1, dst2, dst3]:
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(content, encoding="utf-8")
    log("Synchronized all Watch Face XML files.")


def get_current_version() -> tuple[int, str]:
    content = BUILD_GRADLE.read_text(encoding="utf-8")
    code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    code = int(code_match.group(1)) if code_match else 1
    name = name_match.group(1) if name_match else "1.0.0"
    return code, name


def bump_version_code() -> tuple[int, str]:
    content = BUILD_GRADLE.read_text(encoding="utf-8")
    code_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    if not code_match:
        error("Could not find 'versionCode' in app/build.gradle.kts")
        sys.exit(1)

    old_code = int(code_match.group(1))
    new_code = old_code + 1
    new_name = f"1.0.{new_code}"

    content = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {new_code}", content)
    content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{new_name}"', content)
    BUILD_GRADLE.write_text(content, encoding="utf-8")
    log(f"Bumped version: {old_code} -> {new_code} (versionName: {new_name})")
    return new_code, new_name


def build_apk():
    log("Compiling APK via Gradle...")
    sync_xml_files()

    env = os.environ.copy()
    jdk_paths = [
        Path(r"C:\Program Files\Android\Android Studio\jbr"),
        Path(r"C:\Users\danse\AppData\Local\Android\jdk17\jdk-17.0.12+7")
    ]
    for jdk in jdk_paths:
        if jdk.exists():
            env["JAVA_HOME"] = str(jdk)
            env["PATH"] = f"{jdk / 'bin'}{os.pathsep}{env.get('PATH', '')}"
            break

    gradle_cmd = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [str(REPO_ROOT / gradle_cmd), "assembleDebug"]
    res = subprocess.run(cmd, cwd=str(REPO_ROOT), env=env)
    if res.returncode != 0:
        error(f"Gradle build failed with code {res.returncode}")
        sys.exit(res.returncode)

    if not APK_PATH.exists():
        error(f"APK not found at {APK_PATH}")
        sys.exit(1)

    size_mb = APK_PATH.stat().st_size / (1024 * 1024)
    log(f"Build successful! APK size: {size_mb:.2f} MB ({APK_PATH.stat().st_size:,} bytes)")


def compute_sha256(file_path: Path) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def find_active_watch() -> str | None:
    """Auto-discover connected watch or discover port via mDNS."""
    adb = str(ADB_PATH)
    res = subprocess.run([adb, "devices"], capture_output=True, text=True)
    for line in res.stdout.strip().splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device" and not parts[0].startswith("emulator"):
            return parts[0]

    # Try mDNS discovery
    res = subprocess.run([adb, "mdns", "services"], capture_output=True, text=True)
    m = re.search(r"(\d+\.\d+\.\d+\.\d+:\d+)", res.stdout)
    if m:
        target = m.group(1)
        log(f"Found watch via mDNS at {target}. Connecting...")
        subprocess.run([adb, "connect", target], capture_output=True, text=True)
        res2 = subprocess.run([adb, "devices"], capture_output=True, text=True)
        for line in res2.stdout.strip().splitlines()[1:]:
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device" and not parts[0].startswith("emulator"):
                return parts[0]
    return None


def install_local(device: str | None = None):
    dev = device or find_active_watch()
    if not dev:
        error("No Pixel Watch connected via ADB. Run over local Wi-Fi or connect via USB.")
        return False

    adb = str(ADB_PATH)
    log(f"Streaming install to watch [{dev}]...")
    res = subprocess.run([adb, "-s", dev, "install", "-r", str(APK_PATH)], capture_output=True, text=True)
    if res.returncode == 0:
        log(f"Installed successfully on watch [{dev}]!")
        return True
    else:
        error(f"ADB install failed: {res.stderr or res.stdout}")
        return False


def take_screencap(out_path: Path | None = None) -> Path | None:
    dev = find_active_watch()
    if not dev:
        error("No watch found for screencap.")
        return None

    if out_path is None:
        out_path = REPO_ROOT / "screen_watch.png"

    adb = str(ADB_PATH)
    log(f"Capturing screenshot from [{dev}] -> {out_path.name}...")
    res = subprocess.run([adb, "-s", dev, "exec-out", "screencap", "-p"], capture_output=True)
    if res.returncode == 0 and len(res.stdout) > 1000:
        out_path.write_bytes(res.stdout)
        log(f"Screenshot saved to {out_path}")
        return out_path
    else:
        error("Failed to capture screenshot.")
        return None


def deploy_github_ota(notes: str = "Cloud OTA release"):
    version_code, version_name = bump_version_code()
    build_apk()

    sha256 = compute_sha256(APK_PATH)
    tag = f"v{version_name}"
    public_apk_url = f"https://github.com/{GITHUB_REPO}/releases/download/{tag}/app-debug.apk"

    manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "apkFile": "app-debug.apk",
        "apkSize": APK_PATH.stat().st_size,
        "sha256": sha256,
        "apkUrl": public_apk_url,
        "releaseNotes": notes,
        "timestamp": datetime.now(timezone.utc).isoformat()
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    log(f"Generated OTA manifest (versionCode: {version_code}, tag: {tag})")

    # 1. GitHub Release via gh CLI
    log(f"Creating GitHub Release {tag}...")
    gh_cmd = [
        "gh", "release", "create", tag,
        str(APK_PATH),
        "--title", f"Release {tag}",
        "--notes", notes
    ]
    gh_res = subprocess.run(gh_cmd, cwd=str(REPO_ROOT), capture_output=True, text=True)
    if gh_res.returncode != 0:
        # If tag already exists, upload with --clobber
        warn(f"Release create note: {gh_res.stderr.strip()}. Attempting upload/overwrite...")
        up_cmd = ["gh", "release", "upload", tag, str(APK_PATH), "--clobber"]
        subprocess.run(up_cmd, cwd=str(REPO_ROOT), capture_output=True, text=True)

    log(f"GitHub Release uploaded: {public_apk_url}")

    # 2. Commit and push ota_manifest.json to main
    log("Committing and pushing ota_manifest.json to main branch...")
    subprocess.run(["git", "add", "ota_manifest.json", "app/build.gradle.kts"], cwd=str(REPO_ROOT), capture_output=True)
    subprocess.run(["git", "commit", "-m", f"chore(ota): release {tag} [versionCode {version_code}]"], cwd=str(REPO_ROOT), capture_output=True)
    push_res = subprocess.run(["git", "push", "origin", "main"], cwd=str(REPO_ROOT), capture_output=True, text=True)

    if push_res.returncode == 0:
        log("Pushed ota_manifest.json to GitHub main branch!")
    else:
        warn(f"Git push notice: {push_res.stderr.strip()}")

    print("\n" + "=" * 60)
    print("\033[92m[OK] OTA DEPLOY COMPLETE!\033[0m")
    print(f"  • Version:      {version_name} (versionCode: {version_code})")
    print(f"  • APK URL:      {public_apk_url}")
    print(f"  • Manifest URL: https://raw.githubusercontent.com/{GITHUB_REPO}/main/ota_manifest.json")
    print(f"  • SHA-256:      {sha256[:16]}...")
    print("=" * 60 + "\n")
    return manifest


def main():
    # Read params.xml for defaults
    params = read_params_xml()
    default_layout = params.get("param_news_layout", "2liner")

    parser = argparse.ArgumentParser(description="Wear OS Watch Face Deployment Tool")
    parser.add_argument("--ota", "-o", action="store_true", help="Publish to GitHub Releases Cloud OTA")
    parser.add_argument("--local", "-l", action="store_true", help="Deploy directly to connected watch via ADB")
    parser.add_argument("--both", "-b", action="store_true", help="Publish to GitHub Releases OTA AND deploy locally")
    parser.add_argument("--screencap", "-s", action="store_true", help="Capture current watch screenshot")
    parser.add_argument("--notes", "-n", default="Cloud OTA update", help="Release notes for OTA")
    parser.add_argument("--list", action="store_true", help="List current watch face version and config")
    parser.add_argument(
        "--layout",
        choices=["2liner", "3liner"],
        default=default_layout,
        help=f"News layout: '2liner' (22pt, 2 lines) or '3liner' (19pt, 3 lines). "
             f"Default from params.xml: '{default_layout}'"
    )

    args = parser.parse_args()

    if args.list:
        code, name = get_current_version()
        dev = find_active_watch()
        print(f"Watch Face Repository: {GITHUB_REPO}")
        print(f"Current Version:       {name} (code: {code})")
        print(f"Connected Watch:       {dev or 'None found'}")
        print(f"News Layout (params):  {default_layout}")
        return

    if args.screencap:
        take_screencap()
        return

    # Apply news layout before any build
    def _prebuild():
        apply_news_layout(args.layout)

    if args.both:
        _prebuild()
        deploy_github_ota(args.notes)
        install_local()
        return

    if args.ota:
        _prebuild()
        deploy_github_ota(args.notes)
        return

    if args.local:
        _prebuild()
        build_apk()
        install_local()
        return

    # Default behavior: If watch is connected, do local deploy; else show usage
    dev = find_active_watch()
    if dev:
        log(f"Connected watch detected at {dev}. Running local build & install (use --ota to publish Cloud OTA).")
        _prebuild()
        build_apk()
        install_local(dev)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
