package com.dansemur.ironman.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * SilentSelfUpdater
 *
 * Implements Android 12+ (API 31+) PackageInstaller with USER_ACTION_NOT_REQUIRED.
 * After initial sideload and one-time permission grant, allows the watch face
 * to seamlessly update itself in the background over the air without user interaction.
 */
object SilentSelfUpdater {

    private const val TAG = "SilentSelfUpdater"
    private const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/fchabot-dxf/wearos-ironman-watchface/main/ota_manifest.json"

    data class OtaManifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String?,
        val releaseNotes: String?
    )

    suspend fun checkForUpdate(context: Context, manifestUrl: String = DEFAULT_MANIFEST_URL): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Checking for OTA update at $manifestUrl")
                val manifest = fetchManifest(manifestUrl) ?: return@withContext false

                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode
                }

                Log.d(TAG, "Current versionCode: $currentVersionCode, remote versionCode: ${manifest.versionCode}")

                if (manifest.versionCode > currentVersionCode) {
                    Log.i(TAG, "Newer version available: ${manifest.versionName} (${manifest.versionCode}). Starting silent install...")
                    installApk(context, manifest)
                    true
                } else {
                    Log.d(TAG, "App is up to date.")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed during OTA update check: ${e.message}", e)
                false
            }
        }
    }

    private fun openConnectionWithRedirects(urlString: String, timeoutMs: Int = 15000): HttpURLConnection {
        var currentUrl = urlString
        var redirectCount = 0
        while (redirectCount < 8) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IronmanWearOS/1.0")
            }
            val status = conn.responseCode
            if (status in 300..399) {
                val newUrl = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = newUrl
                redirectCount++
                continue
            }
            return conn
        }
        return (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = 30000
            setRequestProperty("User-Agent", "IronmanWearOS/1.0")
        }
    }

    private fun fetchManifest(urlString: String): OtaManifest? {
        return try {
            val conn = openConnectionWithRedirects(urlString, timeoutMs = 8000)
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                OtaManifest(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.getString("apkUrl"),
                    sha256 = json.optString("sha256", null),
                    releaseNotes = json.optString("releaseNotes", null)
                )
            } else {
                Log.w(TAG, "Manifest fetch returned HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch OTA manifest: ${e.message}")
            null
        }
    }

    private fun installApk(context: Context, manifest: OtaManifest) {
        val packageInstaller = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Allows silent, zero-click update for same package name
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        var sessionId = -1
        try {
            sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val conn = openConnectionWithRedirects(manifest.apkUrl, timeoutMs = 15000)
            val contentLength = conn.contentLengthLong

            conn.inputStream.use { input ->
                session.openWrite("package", 0, if (contentLength > 0) contentLength else -1).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var total = 0L
                    val digest = manifest.sha256?.let { MessageDigest.getInstance("SHA-256") }

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest?.update(buffer, 0, bytesRead)
                        total += bytesRead
                    }
                    session.fsync(output)

                    // Verify SHA-256 if supplied
                    if (digest != null) {
                        val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!computedHash.equals(manifest.sha256, ignoreCase = true)) {
                            session.abandon()
                            throw IllegalStateException("SHA-256 mismatch! Expected ${manifest.sha256}, got $computedHash")
                        }
                    }
                }
            }

            val callbackIntent = Intent(context, UpdateReceiver::class.java).apply {
                putExtra("versionCode", manifest.versionCode)
                putExtra("versionName", manifest.versionName)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "OTA session $sessionId committed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Silent install session failed: ${e.message}", e)
            if (sessionId != -1) {
                try { packageInstaller.openSession(sessionId).abandon() } catch (_: Exception) {}
            }
        }
    }
}
