# Ironman Retro Wear OS Watch Face & Test Suite Companion

A retro **Timex Ironman Triathlon (8-Lap)** inspired digital watch face, paired with a glanceable **Wear OS Tile** and **Complication Data Provider** to monitor your automated test suites (`pw-test`).

---

## 🚀 Features

- **Large Bold Digital Time:** High-contrast 7-segment digital time with optional small seconds (`10:09.36`).
- **Automatic Ambient Inverse Mode:**
  - **Active:** Vintage greenish-gray LCD tint with crisp dark segments.
  - **Idle / AOD:** Instant inversion to **Pitch Black OLED** background with **Luminescent Indiglo Cyan (or White/Amber)** glowing digits.
- **Parametric / Highly Configurable:**
  - Custom Ambient Glow Color (Indiglo Cyan, Pure White, Amber, Matrix Green).
  - Accent Ring Color (Iconic Orange, Racing Red, Electric Blue, Stealth Slate).
  - Active Screen Style (Vintage LCD vs Pure Black OLED).
  - Toggle Seconds on/off.
- **Test Suite Complication Slot:** Displays pass percentage ring (`96% PASS`) or passed count (`48/50`).
- **Wear OS Tile:** A dedicated full-screen swipeable card showing detailed test breakdowns, run history, and status.

---

## 📁 Project Structure

```
c:\Users\danse\APPS\WearOS\IronmanWatchFace\
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Declares Watch Face, Tile & Complications
│   │   ├── res/
│   │   │   ├── raw/watch_face.xml       # Declarative Watch Face Format (WFF) layout
│   │   │   └── values/strings.xml       # Parametric labels and options
│   │   └── java/com/dansemur/ironman/
│   │       ├── tile/
│   │       │   └── TestSuiteTileService.kt       # Wear OS Swipeable Tile
│   │       └── complication/
│   │           └── TestSuiteComplicationService.kt # Complication data source
│   └── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```

---

## ⚙️ Testing & Reconfiguring

### 1. Changing the Test Suite URL
Open [`TestSuiteComplicationService.kt`](file:///c:/Users/danse/APPS/WearOS/IronmanWatchFace/app/src/main/java/com/dansemur/ironman/complication/TestSuiteComplicationService.kt):
```kotlin
companion object {
    // Change this to your local server, ngrok tunnel, or staging URL
    const val DEFAULT_URL = "http://10.0.2.2:3000/api/test-status.json"
}
```

The service expects a simple JSON payload:
```json
{
  "passed": 48,
  "total": 50,
  "status": "PASS"
}
```

### 2. Tweaking the Watch Face Look
Open [`watch_face.xml`](file:///c:/Users/danse/APPS/WearOS/IronmanWatchFace/app/src/main/res/raw/watch_face.xml):
- Coordinate space is a standard circular **450 × 450** canvas.
- All colors and toggles are linked to `<UserConfigurations>` so you can change them live on your wrist!

---

## 🛠️ How to Test & Run on Watch / Emulator

1. Open this folder in **Android Studio**: `File -> Open -> c:\Users\danse\APPS\WearOS\IronmanWatchFace`.
2. Connect your Wear OS watch via Wi-Fi debugging (or start a Wear OS emulator):
   ```bash
   adb connect <WATCH_IP_ADDRESS>:5555
   ```
3. Click **Run** in Android Studio or run:
   ```bash
   ./gradlew installDebug
   ```
4. Long-press your watch face to select **Ironman Retro**, tap the gear icon to customize colors, and swipe left to add the **Test Suite Status Tile**!
