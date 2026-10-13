package com.dansemur.ironman.tile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * RadarTileService
 *
 * Wear OS Tile displaying a live Environment Canada GeoMet 1km radar composite.
 */
class RadarTileService : TileService() {

    companion object {
        private const val RADAR_RES_ID = "radar_image"
        private const val RADAR_URL = "https://progress-board.dansemur.workers.dev/watch/radar"
        @Volatile
        private var cachedRadarBytes: ByteArray? = null
        @Volatile
        private var lastFetchTime = 0L
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // Trigger background refresh if stale (> 5 min)
        if (System.currentTimeMillis() - lastFetchTime > 300_000L || cachedRadarBytes == null) {
            CoroutineScope(Dispatchers.IO).launch {
                fetchRadar()
            }
        }

        val launchIntentAction = androidx.wear.protolayout.ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                androidx.wear.protolayout.ActionBuilders.AndroidActivity.Builder()
                    .setPackageName("com.dansemur.ironman")
                    .setClassName("com.dansemur.ironman.ui.WeatherRadarActivity")
                    .build()
            )
            .build()

        val clickable = androidx.wear.protolayout.ModifiersBuilders.Clickable.Builder()
            .setOnClick(launchIntentAction)
            .setId("open_weather_act")
            .build()

        val rootModifiers = androidx.wear.protolayout.ModifiersBuilders.Modifiers.Builder()
            .setClickable(clickable)
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(rootModifiers)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        // 1. Edge-to-edge full-screen circular radar map
        val mapImage = LayoutElementBuilders.Image.Builder()
            .setResourceId(RADAR_RES_ID)
            .setWidth(expand())
            .setHeight(expand())
            .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FILL_BOUNDS)
            .build()
        root.addContent(mapImage)

        // 2. Floating overlay text (Top title & bottom prompt)
        val overlay = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(16f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("RADAR DIRECT · QC")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFF38BDF8.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(expand()).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Toucher pour ouvrir ➔")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(10f))
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(16f)).build())
            .build()
        root.addContent(overlay)

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
            .setFreshnessIntervalMillis(180_000L) // 3 min freshness
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)

        val compositedBytes = compositeMapWithRadar(cachedRadarBytes)
        if (compositedBytes.isNotEmpty()) {
            resources.addIdToImageMapping(
                RADAR_RES_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(compositedBytes)
                            .setWidthPx(450)
                            .setHeightPx(450)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                            .build()
                    )
                    .build()
            )
        }

        return Futures.immediateFuture(resources.build())
    }

    private fun compositeMapWithRadar(radarBytes: ByteArray?): ByteArray {
        try {
            val baseBitmap = BitmapFactory.decodeResource(resources, com.dansemur.ironman.R.drawable.radar_basemap)
            if (baseBitmap != null) {
                val outBitmap = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(outBitmap)
                canvas.drawBitmap(baseBitmap, null, android.graphics.Rect(0, 0, 450, 450), null)

                if (radarBytes != null && radarBytes.isNotEmpty()) {
                    val radarBitmap = BitmapFactory.decodeByteArray(radarBytes, 0, radarBytes.size)
                    if (radarBitmap != null) {
                        val paint = android.graphics.Paint().apply {
                            alpha = 195 // ~76% opacity for smooth basemap transparency
                            isAntiAlias = true
                            isFilterBitmap = true
                        }
                        canvas.drawBitmap(radarBitmap, null, android.graphics.Rect(0, 0, 450, 450), paint)
                    }
                }

                // Draw user location position dot at center (225, 225)
                val dotBorderPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                val dotInnerPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#38BDF8")
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(225f, 225f, 8f, dotBorderPaint)
                canvas.drawCircle(225f, 225f, 6f, dotInnerPaint)

                val stream = ByteArrayOutputStream()
                outBitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                val result = stream.toByteArray()
                if (result.isNotEmpty()) return result
            }
        } catch (_: Exception) {}

        return getFallbackRadarBytes()
    }

    private fun getFallbackRadarBytes(): ByteArray {
        return try {
            val opt = BitmapFactory.Options()
            val bmp = BitmapFactory.decodeResource(resources, com.dansemur.ironman.R.drawable.radar_basemap, opt)
            if (bmp != null) {
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
                stream.toByteArray()
            } else {
                ByteArray(0)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun fetchRadar() {
        try {
            val conn = URL(RADAR_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    cachedRadarBytes = bytes
                    lastFetchTime = System.currentTimeMillis()
                }
            }
        } catch (_: Exception) {}
    }
}
