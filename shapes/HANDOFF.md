# Handoff: "Shapes" watch face (`:shapes` module)

From: Claude Code cloud session (branch `ccr-05208b4e-auikwa`)
To: Claude in VS Code on Fred's Windows PC, which has wireless ADB to the watch

## Background

The user wanted a face like the Pixel Watch stock face: colored shape at each hour, thick red hour hand, white minute hand, a weather bubble and a camera shortcut bubble. Stock faces can't be modified, because they're Google-signed and their assets are Google's. So this is an original rebuild in Watch Face Format (WFF), with its own shapes, palette and features.

## Status

| Item | State |
|---|---|
| WFF XML generated | Done: `shapes/src/main/res/raw/watchface.xml` |
| Validates against official WFF v2 XSD | Done (`VALID`) |
| `./gradlew :shapes:assembleDebug` | Builds on Linux (42 KB APK, `hasCode=false`) |
| Installed and seen on a real watch | **Not done.** This is your first job |
| OTA updater (`tools/ota_deploy.py`) | Not wired. It only handles `:app` |

## Files

```
shapes/
  build.gradle.kts            resource-only app, applicationId com.dansemur.shapes, minSdk 33
  src/main/AndroidManifest.xml hasCode=false + <property> format.version=2
  src/main/res/raw/watchface.xml   GENERATED, do not hand-edit
  src/main/res/drawable/preview.png  GENERATED picker thumbnail (also app icon)
  src/main/res/xml/watch_face_info.xml
  tools/generate_face.py      SOURCE OF TRUTH: CONFIG dict + MARKERS list → XML + PNGs
  tools/validate_wff.py       downloads Google's XSD, validates any WFF XML
  preview/                    git-ignored; active.png / ambient.png after generating
settings.gradle.kts           include(":shapes") added
```

**Workflow for any visual change:** edit `CONFIG`/`MARKERS` in `generate_face.py`, then run:
```powershell
python shapes/tools/generate_face.py
python shapes/tools/validate_wff.py shapes/src/main/res/raw/watchface.xml
```
You need `pip install pillow xmlschema`. Look at `shapes/preview/active.png` and `ambient.png` before building. The PNGs are drawn from the same geometry as the XML.

## Install and verify (first job)

```powershell
./gradlew :shapes:assembleDebug
adb connect <WATCH_IP>:5555
adb install -r shapes/build/outputs/apk/debug/shapes-debug.apk
```
Then on the watch, long-press the face, find "Shapes", and select it. Check these:
1. **Hands.** They're `PartDraw`s rotated via `<Transform target="angle">` around pivot 0.5/0.5, not `<AnalogClock>`. Confirm they point at the right time.
2. **Current-hour marker.** It should be bigger and brighter, and spin one turn per minute in 6° steps. The expressions use ternaries like `[HOUR_0_11] == 3 ? 1.4 : 1` on `scaleX`/`scaleY`/`alpha`/`angle`.
3. **Ambient (cover the screen).** Markers dim to alpha 70, the hour hand becomes an outline, and the second hand and bottom bubble hide.
4. **Customize (pencil).** Check *Hour hand* color (ColorConfiguration → `[CONFIGURATION.hand_color.0]`), *Markers* Colour/Mono (ListConfiguration), and *Second hand* (BooleanConfiguration).
5. **Complications.** Slot 1 (big centre circle) defaults to `DAY_AND_DATE`, and the user wants Weather there. Slot 2 (small bubble) defaults to `WATCH_BATTERY`, and the user wants an App shortcut (Camera). Both are `isCustomizable="TRUE"`.
6. **RANGED_VALUE arc in slot 1.** It's driven by `<Transform target="endAngle">` on an `<Arc>`. This hasn't been checked on a device.

If something renders wrong, read logcat: `adb logcat | Select-String -Pattern "WatchFace|wff|DWF"`.

## Possible next work (ask the user first)

- **More animation.** The user asked "is there animation of the shapes?" Right now only the current-hour marker animates (pop + ticking spin). Ideas: smooth spin, a wobble on wrist raise, a pop when the hour changes (`<Animation>` inside `<Transform>`). Keep battery in mind: nothing should animate in ambient.
- **Ironman face schema errors.** `python shapes/tools/validate_wff.py app/src/main/res/raw/watchface.xml` reports 14 errors:
  - `<Variant>` inside `<Rectangle>`. This isn't allowed: battery bars at `PartDraw[5]`. Use a `<Transform target="alpha">` on the shape instead.
  - `<Template>` with no `<Parameter>`, e.g. `°`, `%%`, `SYSTEM STANDBY…`. Either add a dummy parameter or move to plain text.

  Keep `res/raw/watch_face.xml` and `watchface.xml` in sync (AGENTS.md §6.7).
- **OTA for `:shapes`.** It would need its own manifest/release in `tools/ota_deploy.py`. Note that `:app`'s silent updater can only update its own package.

## Gotchas learned in this session

- **One WFF face per APK.** That's why this is a separate module and package, not a second face inside `:app`.
- Part `x`/`y`/`width`/`height` must be **integers**. The schema rejects `205.0`.
- `<Variant>` only supports `mode="AMBIENT"`, and is only allowed on Parts, Groups and ComplicationSlots, not on shapes.
- Shapes available in `PartDraw` are only `Arc`, `Ellipse`, `Line`, `Rectangle` and `RoundRectangle`. There are **no polygons or paths**. That's why the triangle and pentagon became diamonds (a RoundRectangle rotated 45°), plus signs and so on. A filled half-disc is an `Arc` whose stroke thickness equals its diameter.
- Strokes straddle the shape edge: half inside, half outside.
- `BooleanOption`/`ListOption` children must be wrapped in a `<Group>`.
- `xmllint` can't read the WFF XSD (it's XSD 1.1). Use `validate_wff.py`.
- **Cloud build only:** `gradle.properties` pins `org.gradle.java.home` to a Windows JDK path. On Linux it was overridden with `-Dorg.gradle.java.home=...`. You don't need this on Windows.
