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
 * MaisonTileService
 *
 * Wear OS Tile mirroring the dashboard's "Maison" tab: the Tivoli radio's
 * current station and whether it's grouped with other speakers. Not
 * position-dependent (unlike the Collecte/Bus tiles), so no location logic.
 */
class MaisonTileService : TileService() {

    companion object {
        private const val BASE_URL = "https://local-dash-api.dansemur.workers.dev/bus/watch/maison"
        @Volatile
        private var cachedJson: String? = null
        @Volatile
        private var lastFetchTime = 0L
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        if (System.currentTimeMillis() - lastFetchTime > 600_000L || cachedJson == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchMaison()
            }
        }

        val data = parseMaison(cachedJson)

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
                .setText("MAISON")
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
                        .setSize(sp(18f))
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .build()
                )
                .setMaxLines(2)
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
            .setFreshnessIntervalMillis(600_000L) // 10 min
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

    private fun fetchMaison() {
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

    private data class MaisonTileData(val headline: String, val subline: String?)

    private fun parseMaison(jsonStr: String?): MaisonTileData {
        if (jsonStr == null) {
            return MaisonTileData("Chargement...", null)
        }
        try {
            val obj = JSONObject(jsonStr)
            val station = obj.optString("station", null)
            val frequency = obj.optString("frequency", null)
            val grouped = obj.optBoolean("grouped", false)
            val groupedWith = obj.optString("groupedWith", null)
            val occupied = obj.optString("occupied", null)

            val headline = if (!station.isNullOrEmpty()) {
                if (!frequency.isNullOrEmpty()) "$station $frequency" else station
            } else {
                "Radio éteinte"
            }

            val subline = when {
                !occupied.isNullOrEmpty() -> "Occupé ($occupied)"
                grouped && !groupedWith.isNullOrEmpty() -> "Groupé: $groupedWith"
                else -> null
            }

            return MaisonTileData(headline, subline)
        } catch (_: Exception) {
            return MaisonTileData("Erreur", null)
        }
    }
}
