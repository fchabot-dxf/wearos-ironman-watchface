package com.dansemur.ironman.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
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
 * GrilleTileService
 *
 * Wear OS Tile mirroring the dashboard's "Grille horaire" card: the current
 * and next show on a radio station. Always defaults to ICI Première - this
 * is a separate, fixed-station tile, not tied to whatever is actually
 * playing on the Tivoli radio (see MaisonTileService for that). Not
 * position-dependent.
 */
class GrilleTileService : TileService() {

    companion object {
        private const val BASE_URL = "https://local-dash-api.dansemur.workers.dev/bus/watch/grille?station=premiere"
        @Volatile
        private var cachedJson: String? = null
        @Volatile
        private var lastFetchTime = 0L
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // Shows change on the hour/half-hour, not just every 10 min - refresh a bit more often.
        if (System.currentTimeMillis() - lastFetchTime > 300_000L || cachedJson == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchGrille()
            }
        }

        val data = parseGrille(cachedJson)

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        val mainColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        mainColumn.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(data.label.uppercase())
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFF38BDF8.toInt())) // Indiglo cyan
                        .build()
                )
                .build()
        )

        mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

        mainColumn.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(data.headline)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(16f))
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .build()
                )
                .setMaxLines(3)
                .build()
        )

        if (data.subline != null) {
            mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            mainColumn.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(data.subline)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFF94A3B8.toInt()))
                            .build()
                    )
                    .setMaxLines(2)
                    .build()
            )
        }

        root.addContent(mainColumn.build())

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(System.currentTimeMillis().toString())
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root.build()).build())
                            .build()
                    )
                    .build()
            )
            .setFreshnessIntervalMillis(300_000L) // 5 min
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(requestParams.version)
                .build()
        )
    }

    private fun fetchGrille() {
        try {
            val conn = URL(BASE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                cachedJson = conn.inputStream.bufferedReader().use { it.readText() }
                lastFetchTime = System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }

    private data class GrilleTileData(val label: String, val headline: String, val subline: String?)

    private fun parseGrille(jsonStr: String?): GrilleTileData {
        if (jsonStr == null) {
            return GrilleTileData("Première", "Chargement...", null)
        }
        try {
            val obj = JSONObject(jsonStr)
            val label = obj.optString("label", "Première")
            val current = obj.optString("current", null)
            val next = obj.optString("next", null)
            val nextTime = obj.optString("nextTime", null)

            val headline = if (!current.isNullOrEmpty()) current else "Aucune émission"
            val subline = if (!next.isNullOrEmpty()) {
                if (!nextTime.isNullOrEmpty()) "Ensuite: $next ($nextTime)" else "Ensuite: $next"
            } else null

            return GrilleTileData(label, headline, subline)
        } catch (_: Exception) {
            return GrilleTileData("Première", "Erreur", null)
        }
    }
}
