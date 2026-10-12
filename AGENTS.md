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

---

## 6. Watch Face Format (WFF) Complication Implementation Findings

Key requirements discovered for Wear OS Declarative Watch Face Runtime (DWF):

1. **Attribute Naming (`slotId` vs `id`):**
   - In `<ComplicationSlot>`, the unique numeric slot identifier **MUST** be `slotId="1"`, **NOT** `id="100"`. If `id` is used, the DWF runtime validator ignores/discards the slot, preventing complication registration in `ComplicationSlotsManager`.
2. **Mandatory Bounding Shape:**
   - Every `<ComplicationSlot>` **must** specify an immediate child bounding shape (e.g., `<BoundingBox x="0" y="0" width="370" height="28"/>` or `<BoundingOval/>`). Without this bounding shape, the slot dimensions evaluate to `RectF(0.0, 0.0, 0.0, 0.0)` and hit-testing/rendering fails.
3. **Supported Types (`EMPTY` vs `NO_DATA`):**
   - The allowed values for `supportedTypes` in WFF are `SHORT_TEXT | LONG_TEXT | MONOCHROMATIC_IMAGE | SMALL_IMAGE | PHOTO_IMAGE | RANGED_VALUE | EMPTY`. `NO_DATA` is an invalid token in WFF and will cause schema validation failure.
4. **Mandatory Default System Provider:**
   - Under `<DefaultProviderPolicy>`, `defaultSystemProvider` is a **required** attribute (e.g. `defaultSystemProvider="EMPTY"` or `"STEP_COUNT"`) alongside `defaultSystemProviderType="SHORT_TEXT"`.
   - The primary provider component should use standard component syntax: `primaryProvider="com.dansemur.ironman/.complication.TestSuiteComplicationService"`.
5. **Template Parameter Substitution:**
   - Inside `<Complication type="SHORT_TEXT">` or `<Complication type="RANGED_VALUE">`, the `<Template>` element **must** use printf formatting tokens with `<Parameter expression="..." />`, e.g.:
     ```xml
     <Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template>
     ```
     Writing raw text like `<Template>[COMPLICATION.TEXT]</Template>` without `<Parameter>` does not trigger expression evaluation.
6. **Shape Hierarchy Under `<PartDraw>`:**
   - Shape primitives (`<RoundRectangle>`, `<Rectangle>`, `<Ellipse>`, `<Line>`) must enclose their `<Stroke>` or `<Fill>` child elements, rather than being enclosed by `<Stroke>`.
7. **Both `watchface.xml` and `watch_face.xml` in `res/raw/`:**
   - Keep both file naming conventions (`res/raw/watchface.xml` and `res/raw/watch_face.xml`) in sync to guarantee compatibility across Wear OS DWF runtime versions.
8. **Fixed Provider Locking (`isCustomizable="FALSE"`):**
   - Wear OS treats slots with `isCustomizable="TRUE"` (or omitted) as unconfigured until the user manually configures them in the watch's UI editor.
   - Setting `isCustomizable="FALSE"` (must be uppercase `FALSE`) causes DWF to immediately set `fixedComplicationDataSource=true`, locking and auto-binding the slot to `primaryProvider` from `DefaultProviderPolicy` with zero watch friction.
9. **Mandatory Manifest Permission (`RECEIVE_COMPLICATION_DATA`):**
   - Wear OS strictly blocks delivering complication payloads to any watch face package that omits `<uses-permission android:name="com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA" />` (and `RECEIVE_COMPLICATION_DATA_PRIVILEGED`). Without this, slots remain stuck on `NoDataComplicationData` and `lastComplicationUpdate=1970-01-01`.
10. **Mandatory `<UserConfigurations>` for On-Watch "Customize" Button:**
   - If `<UserConfigurations>` is missing from `<WatchFace>`, Wear OS evaluates the watch face as having zero customizable properties, which completely disables/hides the "Customize" (pencil) button in the watch face picker carousel. Adding `<UserConfigurations>` immediately restores the Customize button and opens access to the Complications editor tab.

---

## 7. Telemetry Layout, Icon Tinting & News Pageflip Architecture

Key UX and runtime principles established for the Ironman HUD layout:

1. **Activity Monitor Line (Tri-Pod HUD at `y=284`):**
   - Combining Steps (`x=48..140`), Heart Rate (`x=182..257`), and Battery (`x=308..382`) into a single horizontal telemetry row frees up the entire lower hemisphere of the watch face.
   - **Desaturated Icon Tints:** Full-saturation primaries look like candy; subtle tints desaturated against Indiglo Cyan (`#38BDF8`) preserve military instrumentation aesthetics:
     - Steps Sole Icon: Soft Periwinkle / Lavender (`#818CF8`).
     - Heart Icon: Soft Coral / Rose (`#F472B6`).
     - Battery Shell & Bars: Soft Mint / Seafoam (`#5EEAD4`).
     - Weather Condition (Sun): Golden Luminous Amber (`#FDE047`).
     - Rain Droplet: Crystal Luminous Sky Blue (`#7DD3FC`).
   - Digits across all pods remain in cohesive Indiglo Cyan 7-segment DSEG (`size="20"`).

2. **Dedicated Lower Weather Station (`y=326..385`):**
   - Left side: Giant 50pt DSEG current temperature with tinted weather condition icon.
   - Right side: Forecast High (`↑`) and Low (`↓`) stacked vertically on top of each other, with precipitation chance (`💧`) positioned adjacent at `y=345`.

3. **News Stream: Multi-Line Pageflip Architecture (Zero GPU Drain):**
   - Rather than continuous vertical crawls that constantly wake the GPU compositor and chase the wearer's gaze, the stationary **Pageflip** pattern delivers double the narrative words (15–20 words per story across 3 lines) while resting at 0 FPS between flips.
   - Timing: `([IS_AMBIENT] ? [MINUTE] : floor([SECOND] / 12)) % 6` holds each story for 12 seconds in interactive mode and updates once per minute in Ambient AOD.
   - Font hierarchy: 15pt bold headline (topic) + two 13pt regular sublines (narrative story detail). Zero collision with the Date (`y=58`) or Time (`y=154`).



