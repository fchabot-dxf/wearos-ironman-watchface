# OTA_HARNESS_SPEC.md — In-App Silent Self-Updater Specification

## Mission
Enable the `IronmanWatchFace` Wear OS app to update itself **over the air with zero user interaction and zero phone interaction**, using Android 12+ (API 31+) `PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED`.

Once the app is sideloaded initially and granted "Install unknown apps" permission once, all subsequent builds pushed by the host pipeline will install silently in the background on the watch.

---

## 1. Architectural Contract

### 1.1 OTA Manifest Schema
The watch app checks for updates by fetching a JSON manifest from `manifest_url`:
```json
{
  "versionCode": 2,
  "versionName": "1.0.2",
  "apkUrl": "https://progress-board.dansemur.workers.dev/watch/apk",
  "sha256": "3a7b...8f",
  "releaseNotes": "Updated middle progress bar layout"
}
```

### 1.2 Decision Rule
- If `manifest.versionCode > BuildConfig.VERSION_CODE`: trigger update flow.
- Otherwise: no-op.

---

## 2. Watch-Side Harness Implementation Requirements

### 2.1 Manifest (`app/src/main/AndroidManifest.xml`)
1. Ensure `android:hasCode="true"` under `<application>` (required to execute Kotlin background updater code).
2. Add permissions:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   ```
3. Register the update receiver:
   ```xml
   <receiver
       android:name=".ota.UpdateReceiver"
       android:exported="false" />
   ```

### 2.2 Updater Service (`app/src/main/java/com/dansemur/ironman/ota/SilentSelfUpdater.kt`)
1. **Network Fetching**:
   - Downloads the APK stream to a local cache file or directly into the `PackageInstaller.Session` stream.
   - Verifies SHA-256 matches `manifest.sha256` (if provided).

2. **Session Creation with Zero-Action Flag**:
   ```kotlin
   val packageInstaller = context.packageManager.packageInstaller
   val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
       setAppPackageName(context.packageName)
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
           // THIS ENABLES ZERO-CLICK SILENT INSTALL FOR SAME-PACKAGE UPDATES
           setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
       }
   }

   val sessionId = packageInstaller.createSession(params)
   val session = packageInstaller.openSession(sessionId)

   // Write APK bytes
   apkInputStream.use { input ->
       session.openWrite("package", 0, apkSize).use { output ->
           input.copyTo(output)
           session.fsync(output)
       }
   }

   // Commit session
   val callbackIntent = Intent(context, UpdateReceiver::class.java)
   val pendingIntent = PendingIntent.getBroadcast(
       context,
       sessionId,
       callbackIntent,
       PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
   )
   session.commit(pendingIntent.intentSender)
   session.close()
   ```

3. **Status Receiver (`UpdateReceiver.kt`)**:
   - Listens for `PackageInstaller.STATUS_SUCCESS` or `PackageInstaller.STATUS_FAILURE`.
   - Logs errors or cleanup temp files.

### 2.3 Trigger Points
- **On Watch Face Ambient/Active Wake or Tile launch**: Check manifest if at least 15 minutes since last check.
- **Manual Debug Trigger**: Tap on complication or long-press on banner (optional).

---

## 3. Host-Side Pipeline Contract (Already Built)

The host deployment pipeline script:
[`tools/ota_deploy.py`](tools/ota_deploy.py)

1. Bumps `versionCode` in [`app/build.gradle.kts`](app/build.gradle.kts).
2. Runs `./gradlew assembleDebug`.
3. Computes SHA-256 and packages `app-debug.apk`.
4. Updates `ota_manifest.json` and publishes/uploads the bundle.
