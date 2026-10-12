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

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // 1. Header: Live Radar label
        column.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("RADAR DIRECT · QC SUD")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFF38BDF8.toInt())) // Indiglo cyan
                        .build()
                )
                .build()
        )

        column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())

        // 2. Radar Map (Circular / rounded frame)
        column.addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(RADAR_RES_ID)
                .setWidth(dp(150f))
                .setHeight(dp(130f))
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                .build()
        )

        column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())

        // 3. Footer: Subtitle / instruction
        column.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("Pluie en direct · ECCC GeoMet")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(10f))
                        .setColor(argb(0xFF94A3B8.toInt()))
                        .build()
                )
                .build()
        )

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
            .setFreshnessIntervalMillis(300_000L) // 5 min freshness
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)

        val bytes = cachedRadarBytes
        if (bytes != null && bytes.isNotEmpty()) {
            resources.addIdToImageMapping(
                RADAR_RES_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(bytes)
                            .setWidthPx(300)
                            .setHeightPx(260)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                            .build()
                    )
                    .build()
            )
        }

        return Futures.immediateFuture(resources.build())
    }

    private fun fetchRadar() {
        try {
            val conn = URL(RADAR_URL).openConnection() as HttpURLConnection
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
