package com.dansemur.ironman.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * NewsTileService
 *
 * Wear OS Tile displaying the latest top headlines from Radio-Canada / CBC.
 */
class NewsTileService : TileService() {

    companion object {
        private const val NEWS_URL = "https://progress-board.dansemur.workers.dev/watch/news"
        @Volatile
        private var cachedNewsJson: String? = null
        @Volatile
        private var lastFetchTime = 0L
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // Trigger background refresh if stale (> 15 min)
        if (System.currentTimeMillis() - lastFetchTime > 900_000L || cachedNewsJson == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchNews()
            }
        }

        val stories = parseNews(cachedNewsJson)

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // 1. Header
        column.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("RADIO-CANADA · À LA UNE")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFF38BDF8.toInt())) // Indiglo cyan
                        .build()
                )
                .build()
        )

        column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())

        // 2. Story 1 Card
        if (stories.isNotEmpty()) {
            val s1 = stories[0]
            val card1 = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(s1.first)
                        .setMaxLines(2)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(13f))
                                .setColor(argb(0xFFFFFFFF.toInt()))
                                .build()
                        )
                        .build()
                )
            if (s1.second.isNotEmpty()) {
                card1.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
                card1.addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(s1.second)
                        .setMaxLines(2)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(11f))
                                .setColor(argb(0xFF94A3B8.toInt()))
                                .build()
                        )
                        .build()
                )
            }
            column.addContent(card1.build())
        }

        column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())

        // 3. Story 2 Card
        if (stories.size > 1) {
            val s2 = stories[1]
            val card2 = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(s2.first)
                        .setMaxLines(2)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(12f))
                                .setColor(argb(0xFF38BDF8.toInt()))
                                .build()
                        )
                        .build()
                )
            column.addContent(card2.build())
        }

        root.addContent(column.build())

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(lastFetchTime.toString())
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root.build()).build())
                            .build()
                    )
                    .build()
            )
            .setFreshnessIntervalMillis(900_000L) // 15 min freshness
            .build()

        return Futures.immediateFuture(tile)
    }

    private fun fetchNews() {
        try {
            val conn = URL(NEWS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                cachedNewsJson = conn.inputStream.bufferedReader().use { it.readText() }
                lastFetchTime = System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }

    private fun parseNews(jsonStr: String?): List<Pair<String, String>> {
        if (jsonStr == null) {
            return listOf(
                Pair("L'animateur Jean-Luc Mongrain s'éteint", "À l'âge de 75 ans."),
                Pair("Message de Mark Carney aux Albertains", "« Le Canada fonctionne »")
            )
        }
        val list = mutableListOf<Pair<String, String>>()
        try {
            val obj = JSONObject(jsonStr)
            val arr = obj.optJSONArray("stories")
            if (arr != null) {
                for (i in 0 until minOf(2, arr.length())) {
                    val s = arr.getJSONObject(i)
                    val raw = s.optString("rawTitle", "")
                    val two = s.optJSONObject("twoLiner")
                    val l2 = two?.optString("line2", "") ?: ""
                    list.add(Pair(raw, l2))
                }
            }
        } catch (_: Exception) {}
        return if (list.isEmpty()) listOf(Pair("Fil de nouvelles", "Chargement...")) else list
    }
}
