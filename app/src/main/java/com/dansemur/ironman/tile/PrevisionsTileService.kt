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
 * PrevisionsTileService
 *
 * Wear OS Tile displaying current weather, feels-like, max/min, rain probability,
 * and upcoming 3-hour forecast cards from Environment Canada GEM model.
 */
class PrevisionsTileService : TileService() {

    companion object {
        private const val WEATHER_URL = "https://progress-board.dansemur.workers.dev/watch/weather"
        @Volatile
        private var cachedJson: String? = null
        @Volatile
        private var lastFetchTime = 0L
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // Trigger background refresh if stale (> 10 min)
        if (System.currentTimeMillis() - lastFetchTime > 600_000L || cachedJson == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchWeather()
            }
        }

        val tileData = parseWeather(cachedJson)

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        val mainColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // 1. Header: Location & Condition
        mainColumn.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(tileData.location.uppercase() + " · " + tileData.condition.uppercase())
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFF38BDF8.toInt())) // Indiglo cyan
                        .build()
                )
                .build()
        )

        mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())

        // 2. Hero: Big Temperature + Feels Like
        val heroRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("${tileData.temp}°")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(42f))
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("ressenti ${tileData.feelsLike}°")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFF94A3B8.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()
        mainColumn.addContent(heroRow)

        mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())

        // 3. Stats Row: Max / Min / Pluie %
        val statsRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Max ${tileData.tempMax}°  Min ${tileData.tempMin}°  ·  Pluie ${tileData.pop}%")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(0xFF38BDF8.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()
        mainColumn.addContent(statsRow)

        mainColumn.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

        // 4. Mini Hourly Strip: 3 upcoming hours
        val hourlyRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        for (h in tileData.hourly3) {
            val hourBox = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(h.hour)
                        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10f)).setColor(argb(0xFF94A3B8.toInt())).build())
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText("${h.temp}°")
                        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(12f)).setColor(argb(0xFFFFFFFF.toInt())).build())
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText("${h.pop}%")
                        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(9f)).setColor(argb(0xFF38BDF8.toInt())).build())
                        .build()
                )
                .build()

            hourlyRow.addContent(hourBox)
            hourlyRow.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(16f)).build())
        }
        mainColumn.addContent(hourlyRow.build())

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

    private fun fetchWeather() {
        try {
            val conn = URL(WEATHER_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                cachedJson = conn.inputStream.bufferedReader().use { it.readText() }
                lastFetchTime = System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }

    private data class HourlyItem(val hour: String, val temp: Int, val pop: Int)
    private data class WeatherTileData(
        val location: String,
        val condition: String,
        val temp: Int,
        val feelsLike: Int,
        val tempMax: Int,
        val tempMin: Int,
        val pop: Int,
        val hourly3: List<HourlyItem>
    )

    private fun parseWeather(jsonStr: String?): WeatherTileData {
        if (jsonStr == null) {
            return WeatherTileData(
                location = "Montréal",
                condition = "Chargement...",
                temp = 15,
                feelsLike = 16,
                tempMax = 17,
                tempMin = 7,
                pop = 100,
                hourly3 = listOf(
                    HourlyItem("18h", 10, 100),
                    HourlyItem("19h", 10, 100),
                    HourlyItem("20h", 9, 100)
                )
            )
        }
        try {
            val obj = JSONObject(jsonStr)
            val cur = obj.optJSONObject("current")
            val loc = obj.optJSONObject("location")?.optString("name", "Montréal") ?: "Montréal"
            val temp = cur?.optInt("temp", 15) ?: 15
            val feelsLike = cur?.optInt("feelsLike", temp) ?: temp
            val cond = cur?.optString("conditionFr", "Variable") ?: "Variable"

            val dailyArr = obj.optJSONArray("daily")
            var tempMax = temp
            var tempMin = temp
            var pop = 0
            if (dailyArr != null && dailyArr.length() > 0) {
                val d0 = dailyArr.getJSONObject(0)
                tempMax = d0.optInt("tempMax", temp)
                tempMin = d0.optInt("tempMin", temp)
                pop = d0.optInt("pop", 0)
            }

            val hourlyArr = obj.optJSONArray("hourly")
            val hList = mutableListOf<HourlyItem>()
            if (hourlyArr != null) {
                for (i in 0 until minOf(3, hourlyArr.length())) {
                    val hObj = hourlyArr.getJSONObject(i)
                    val hour = hObj.optString("hour", "").replace(":00", "h")
                    hList.add(HourlyItem(hour, hObj.optInt("temp", 0), hObj.optInt("pop", 0)))
                }
            }

            return WeatherTileData(loc, cond, temp, feelsLike, tempMax, tempMin, pop, hList)
        } catch (_: Exception) {
            return WeatherTileData("Montréal", "Ensoleillé", 16, 16, 18, 10, 10, emptyList())
        }
    }
}
