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
import kotlin.math.roundToInt

/**
 * MeteoTileService
 *
 * Wear OS Tile mirroring the dashboard's "Météo" tab: current temperature
 * and condition. Calls Open-Meteo directly (no key needed), with the same
 * params MeteoPanel.tsx uses, trimmed to "current" only - the tile needs
 * right now, not the dashboard's full 7-day/hourly forecast.
 */
class MeteoTileService : TileService() {

    companion object {
        private const val DEFAULT_LAT = 45.5588
        private const val DEFAULT_LON = -73.5282
        @Volatile
        private var cachedJson: String? = null
        @Volatile
        private var lastFetchTime = 0L

        // Same short French labels as MeteoPanel.tsx's WEATHER_CODES (Open-Meteo WMO codes).
        private val WEATHER_LABELS = mapOf(
            0 to "Ciel dégagé", 1 to "Plutôt dégagé", 2 to "Partiellement nuageux", 3 to "Couvert",
            45 to "Brouillard", 48 to "Brouillard givrant",
            51 to "Bruine légère", 53 to "Bruine", 55 to "Bruine forte",
            61 to "Pluie légère", 63 to "Pluie", 65 to "Pluie forte",
            71 to "Neige légère", 73 to "Neige", 75 to "Neige forte",
            80 to "Averses", 82 to "Averses fortes", 85 to "Averses de neige",
            95 to "Orage",
        )
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        if (System.currentTimeMillis() - lastFetchTime > 600_000L || cachedJson == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchMeteo()
            }
        }

        val data = parseMeteo(cachedJson)

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
                .setText("MÉTÉO")
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

    private fun fetchMeteo() {
        try {
            val pos = lastKnownLocation()
            val lat = pos?.first ?: DEFAULT_LAT
            val lon = pos?.second ?: DEFAULT_LON
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m,is_day" +
                "&forecast_hours=1&forecast_days=1&timezone=America%2FToronto"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                cachedJson = conn.inputStream.bufferedReader().use { it.readText() }
                lastFetchTime = System.currentTimeMillis()
                // Without this, the tile only re-renders at the next natural freshness tick or
                // when the user swipes away and back - the very first view (or any view started
                // before this fetch lands) would be stuck showing "Chargement..." indefinitely.
                TileService.getUpdater(this).requestUpdate(MeteoTileService::class.java)
            }
        } catch (_: Exception) {}
    }

    private data class MeteoTileData(val headline: String, val subline: String?)

    private fun parseMeteo(jsonStr: String?): MeteoTileData {
        if (jsonStr == null) {
            return MeteoTileData("Chargement...", null)
        }
        try {
            val obj = JSONObject(jsonStr)
            val current = obj.optJSONObject("current") ?: return MeteoTileData("Erreur", null)
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val apparent = current.optDouble("apparent_temperature", Double.NaN)
            val code = current.optInt("weather_code", -1)

            if (temp.isNaN()) return MeteoTileData("Erreur", null)

            val headline = "${temp.roundToInt()}°C"
            val condition = WEATHER_LABELS[code]
            val felt = if (!apparent.isNaN()) "Ressenti ${apparent.roundToInt()}°" else null
            val subline = when {
                condition != null && felt != null -> "$condition · $felt"
                condition != null -> condition
                felt != null -> felt
                else -> null
            }

            return MeteoTileData(headline, subline)
        } catch (_: Exception) {
            return MeteoTileData("Erreur", null)
        }
    }
}
