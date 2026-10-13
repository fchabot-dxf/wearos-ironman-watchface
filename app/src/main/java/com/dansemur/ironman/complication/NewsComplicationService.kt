package com.dansemur.ironman.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.dansemur.ironman.ui.NewsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * NewsComplicationService
 *
 * Wear OS Complication Provider supplying live news headlines from Radio-Canada / CBC
 * (proxied via https://progress-board.dansemur.workers.dev/watch/news)
 * for the Ironman Watch Face.
 */
class NewsComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "NewsComplicationService"
        private const val NEWS_URL = "https://progress-board.dansemur.workers.dev/watch/news"
        private const val ROTATION_INTERVAL_MS = 180_000L // 3 minutes per headline
        private const val CACHE_TTL_MS = 600_000L // 10 minutes cache
    }

    data class Story(val title: String, val summary: String)

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val title = "RADIO-CANADA · À LA UNE"
        val text = "Dernières actualités en direct"
        return when (type) {
            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text).build(),
                    contentDescription = PlainComplicationText.Builder("$title: $text").build()
                )
                    .setTitle(PlainComplicationText.Builder(title).build())
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("NOUVELLES").build(),
                    contentDescription = PlainComplicationText.Builder("NOUVELLES").build()
                )
                    .setTitle(PlainComplicationText.Builder("INFO").build())
                    .build()
            }
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val stories = getStories()
        if (stories.isEmpty()) {
            return NoDataComplicationData()
        }

        // Select story slot rotating every 3 minutes
        val slotIndex = ((System.currentTimeMillis() / ROTATION_INTERVAL_MS) % stories.size).toInt()
        val currentStory = stories[slotIndex]

        // Tap action: launches full-screen NewsActivity reader
        val tapIntent = Intent(this, NewsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d(TAG, "Serving headline [slot $slotIndex/${stories.size}]: ${currentStory.title}")

        return when (request.complicationType) {
            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(currentStory.summary).build(),
                    contentDescription = PlainComplicationText.Builder("${currentStory.title}: ${currentStory.summary}").build()
                )
                    .setTitle(PlainComplicationText.Builder(currentStory.title).build())
                    .setTapAction(pendingIntent)
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(currentStory.title).build(),
                    contentDescription = PlainComplicationText.Builder(currentStory.title).build()
                )
                    .setTitle(PlainComplicationText.Builder("INFO").build())
                    .setTapAction(pendingIntent)
                    .build()
            }
            else -> NoDataComplicationData()
        }
    }

    private suspend fun getStories(): List<Story> = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("news_complication_cache", Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong("last_fetch", 0L)
        val cachedJson = prefs.getString("json", null)
        val now = System.currentTimeMillis()

        // Refresh from network if stale or missing
        if (now - lastFetch > CACHE_TTL_MS || cachedJson == null) {
            try {
                val conn = URL(NEWS_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    prefs.edit()
                        .putString("json", body)
                        .putLong("last_fetch", now)
                        .apply()

                    val parsed = parseStories(body)
                    if (parsed.isNotEmpty()) {
                        return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Live news network fetch error, falling back to cache: ${e.message}")
            }
        }

        if (cachedJson != null) {
            val cachedList = parseStories(cachedJson)
            if (cachedList.isNotEmpty()) {
                return@withContext cachedList
            }
        }

        // Clean initial fallbacks
        listOf(
            Story("RADIO-CANADA · EN DIRECT", "Fil d'information en continu."),
            Story("INFO EN DIRECT", "Connexion réseau requise.")
        )
    }

    private fun parseStories(jsonStr: String): List<Story> {
        val list = mutableListOf<Story>()
        try {
            val obj = JSONObject(jsonStr)
            val arr = obj.optJSONArray("stories") ?: return list
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val rawTitle = s.optString("rawTitle", "").trim()
                val twoLiner = s.optJSONObject("twoLiner")
                val l1 = twoLiner?.optString("line1", "")?.trim() ?: rawTitle
                val l2 = twoLiner?.optString("line2", "")?.trim() ?: s.optString("rawDesc", "").trim()

                if (rawTitle.isNotEmpty()) {
                    list.add(
                        Story(
                            title = if (l1.isNotEmpty()) l1 else rawTitle,
                            summary = if (l2.isNotEmpty()) l2 else "Radio-Canada · En direct"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing stories JSON", e)
        }
        return list
    }
}
