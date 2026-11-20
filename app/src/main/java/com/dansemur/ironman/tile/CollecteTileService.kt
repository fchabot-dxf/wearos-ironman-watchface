package com.dansemur.ironman.tile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * CollecteTileService
 *
 * Wear OS Tile showing the house's collecte (garbage/recycling/compost)
 * reminder from the maison dashboard's own worker. Mirrors the dashboard's
 * "Collecte" tab - no Tivoli/radio status here, see MaisonTileService for that.
 */
class CollecteTileService : TileService() {

    companion object {
        private const val BASE_URL = "https://local-dash-api.dansemur.workers.dev/bus/watch/collecte"
        @Volatile
        private var cachedJson: String? = null
        @Volatile
        private var lastFetchTime = 0L
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        if (System.currentTimeMillis() - lastFetchTime > 600_000L || cachedJson == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchCollecte()
            }
        }

        val data = parseCollecte(cachedJson)

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
                .setText("COLLECTE")
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
                        .setSize(sp(20f))
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

    /** Last known location only (no active GPS fix) - cheap, called from a background thread. */
    private fun lastKnownLocation(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return try {
            val task = LocationServices.getFusedLocationProviderClient(this).lastLocation
            val loc = Tasks.await(task, 3, TimeUnit.SECONDS) ?: return null
            Pair(loc.latitude, loc.longitude)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCollecte() {
        try {
            val pos = lastKnownLocation()
            val url = if (pos != null) "$BASE_URL?lat=${pos.first}&lon=${pos.second}" else BASE_URL
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                cachedJson = conn.inputStream.bufferedReader().use { it.readText() }
                lastFetchTime = System.currentTimeMillis()
                // Without this, the tile only re-renders at the next natural freshness tick or
                // when the user swipes away and back - the very first view (or any view started
                // before this fetch lands) would be stuck showing "Chargement..." indefinitely.
                TileService.getUpdater(this).requestUpdate(CollecteTileService::class.java)
            }
        } catch (_: Exception) {}
    }

    private data class CollecteTileData(val headline: String, val subline: String?)

    private fun parseCollecte(jsonStr: String?): CollecteTileData {
        if (jsonStr == null) {
            return CollecteTileData("Chargement...", null)
        }
        try {
            val obj = JSONObject(jsonStr)
            val collecte = obj.optJSONObject("collecte")
            val tonight = collecte?.optString("tonight", null)
            val next = collecte?.optString("next", null)
            return when {
                !tonight.isNullOrEmpty() -> CollecteTileData("Ce soir", tonight)
                !next.isNullOrEmpty() -> CollecteTileData("Prochaine", next)
                else -> CollecteTileData("Aucune collecte", null)
            }
        } catch (_: Exception) {
            return CollecteTileData("Erreur", null)
        }
    }
}
