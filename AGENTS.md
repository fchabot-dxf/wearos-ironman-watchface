# AGENT.md — Ironman Retro Watch Face & Test Suite Companion

Operational guide, architecture inventory, declared parameters, and tooling reference for AI agents and developers working on this project.

---

## 1. Project Overview & Architectural Boundaries

This project combines three distinct Wear OS primitives into a single installable app:

1. **Watch Face (`Watch Face Format` / WFF standard):**
   - Declarative XML standard (Wear OS 4+, API 33+).
   - Handled directly by the system runtime (extreme battery efficiency).
   - Canvas space: `450 x 450` circular layout with `clipShape="CIRCLE"`.
   - File: [`app/src/main/res/raw/watch_face.xml`](app/src/main/res/raw/watch_face.xml).

2. **Parametric Computer Configuration (Zero-Watch Friction):**
   - Single source of truth: [`app/src/main/res/values/params.xml`](app/src/main/res/values/params.xml).
   - The user explicitly configures all styling, colors, and labels **on their computer** in this file, not by tapping through menus on the watch.
   - Every XML node in `watch_face.xml` consumes `@color/param_*` or `@string/param_*`.

3. **Active vs. Ambient (Idle) Behavior:**
   - **Active:** Vintage greenish-gray LCD tint (`@color/param_active_lcd_bg`) with dark 7-segment digits (`@color/param_active_digits`), outer bezel, and ticking seconds.
   - **Idle / Ambient AOD (`[IS_AMBIENT] == TRUE`):** Automatic inverse mode. The background becomes pure black OLED (`@color/param_ambient_bg` = `#000000`), decorative bezels and buttons hide, and the numbers switch to an Indiglo luminescent glow (`@color/param_ambient_glow`).

4. **Test Suite Complication Provider (`SuspendingComplicationDataSourceService`):**
   - Background Kotlin service delivering data to complication slots (`RANGED_VALUE` and `SHORT_TEXT`).
   - Polls your local or remote test runner (e.g., `pw-test` / Playwright) endpoint.
   - File: [`app/src/main/java/com/dansemur/ironman/complication/TestSuiteComplicationService.kt`](app/src/main/java/com/dansemur/ironman/complication/TestSuiteComplicationService.kt).

5. **Wear OS Tile (`ProtoLayout` / Jetpack Tiles):**
   - Swipeable card on the Wear OS carousel.
   - **Independent Lifecycle:** Changing or swapping the watch face does **NOT** remove the Tile.
   - File: [`app/src/main/java/com/dansemur/ironman/tile/TestSuiteTileService.kt`](app/src/main/java/com/dansemur/ironman/tile/TestSuiteTileService.kt).

---

## 2. Inventory of Files & Parameters

| File | Purpose | Key Parameters to Reconfigure |
|---|---|---|
| [`params.xml`](app/src/main/res/values/params.xml) | **Central declared parameters** | `param_active_lcd_bg`, `param_active_digits`, `param_active_accent_ring`, `param_ambient_glow`, `param_banner_label` |
| [`watch_face.xml`](app/src/main/res/raw/watch_face.xml) | Watch Face Format declarative canvas | Canvas elements, `<ComplicationSlot>`, and ambient logic (`[IS_AMBIENT] ? ...`) |
| [`TestSuiteComplicationService.kt`](app/src/main/java/com/dansemur/ironman/complication/TestSuiteComplicationService.kt) | Complication data fetcher | `DEFAULT_URL` (JSON status endpoint) |
| [`TestSuiteTileService.kt`](app/src/main/java/com/dansemur/ironman/tile/TestSuiteTileService.kt) | Full-screen Tile layout | Tile typography, cards, and refresh interval |
| [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) | Android manifest | Service declarations & permissions |

---

## 3. External Dependencies & Libraries

Configured in [`gradle/libs.versions.toml`](gradle/libs.versions.toml):

| Library | Version | Role |
|---|---|---|
| `androidx.wear.tiles` | `1.4.0` | Wear OS swipeable ProtoLayout Tiles |
| `androidx.wear.tiles.material` | `1.4.0` | Pre-built accessible components for Tiles |
| `androidx.wear.watchface.complications.data.source` | `1.2.1` | Wear OS Complication Provider Service |
| `androidx.concurrent.futures.ktx` | `1.2.0` | Coroutine to `ListenableFuture` bridges for services |
| `kotlinx.coroutines` | `2.0.20` | Asynchronous network fetching on IO dispatchers |

---

## 4. Development, Testing & OTA Deployment Commands

- **Build APK:**
  ```powershell
  ./gradlew assembleDebug
  ```
- **Automated OTA Release Pipeline (Hands-Free):**
  ```powershell
  python tools/ota_deploy.py
  ```
  *(Bumps versionCode, builds debug APK, updates ota_manifest.json, and publishes to GitHub Releases via `gh`)*.
- **Initial Install on Watch via Wireless ADB:**
  ```powershell
  adb connect <WATCH_IP>:5555
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Inspect Complication & OTA Logcat:**
  ```powershell
  adb logcat -s "TestSuiteComplication" "TestSuiteTile" "SilentSelfUpdater"
  ```

---

## 5. In-App Silent OTA Self-Updater Architecture
See full contract and Kotlin implementation blueprint in [`OTA_HARNESS_SPEC.md`](OTA_HARNESS_SPEC.md).
- **Zero-Touch on Phone & Watch:** Uses Android 12+ (API 31+) `PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED`.
- **Pipeline:** [`tools/ota_deploy.py`](tools/ota_deploy.py) produces releases and manifests.
- **Harness:** Watch polls or checks on launch; streams APK into `PackageInstaller.Session` and commits silently.
