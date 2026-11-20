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

        mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())

        // Current show: time range (small, muted) above the title (bold, white)
        mainColumn.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(data.currentTime ?: "")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(10f))
                        .setColor(argb(0xFFF59E0B.toInt())) // amber: "on air now"
                        .build()
                )
                .build()
        )
        mainColumn.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(data.currentName)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(14f))
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .build()
                )
                .setMaxLines(2)
                .build()
        )

        mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

        // Next show: same shape, dimmer - "what's coming up"
        if (data.nextName != null) {
            mainColumn.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(data.nextTime ?: "")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(10f))
                            .setColor(argb(0xFF64748B.toInt()))
                            .build()
                    )
                    .build()
            )
            mainColumn.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(data.nextName)
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
                // Without this, the tile only re-renders at the next natural freshness tick or
                // when the user swipes away and back - the very first view (or any view started
                // before this fetch lands) would be stuck showing "Chargement..." indefinitely.
                TileService.getUpdater(this).requestUpdate(GrilleTileService::class.java)
            }
        } catch (_: Exception) {}
    }

    private data class GrilleTileData(
        val label: String,
        val currentName: String,
        val currentTime: String?,
        val nextName: String?,
        val nextTime: String?,
    )

    private fun parseGrille(jsonStr: String?): GrilleTileData {
        if (jsonStr == null) {
            return GrilleTileData("Première", "Chargement...", null, null, null)
        }
        try {
            val obj = JSONObject(jsonStr)
            val label = obj.optString("label", "Première")
            val current = obj.optJSONObject("current")
            val next = obj.optJSONObject("next")

            val currentName = current?.optString("name", null)
            val currentTime = current?.let { c ->
                val start = c.optString("start", null)
                val end = c.optString("end", null)
                if (!start.isNullOrEmpty() && !end.isNullOrEmpty()) "$start – $end" else null
            }
            val nextName = next?.optString("name", null)
            val nextTime = next?.let { n ->
                val start = n.optString("start", null)
                val end = n.optString("end", null)
                if (!start.isNullOrEmpty() && !end.isNullOrEmpty()) "$start – $end" else null
            }

            return GrilleTileData(
                label,
                if (!currentName.isNullOrEmpty()) currentName else "Aucune émission",
                currentTime,
                nextName,
                nextTime,
            )
        } catch (_: Exception) {
            return GrilleTileData("Première", "Erreur", null, null, null)
        }
    }
}
