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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProgressBoardTileService
 *
 * Wear OS Tile displaying the latest single screenshot from the live progress board
 * with runner name, pass/fail status, and progress indicator.
 */
class ProgressBoardTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        // Column containing header, screenshot, and footer status
        val contentColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                // Header: Project & Runner info
                LayoutElementBuilders.Text.Builder()
                    .setText("BSPLINE · 86%")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(13f))
                            .setColor(argb(0xFF38BDF8.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()
            )
            .addContent(
                // Screenshot Hero Image (Aspect Ratio 4:3 fit)
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(SCREENSHOT_ID)
                    .setWidth(dp(180f))
                    .setHeight(dp(110f))
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build()
            )
            .addContent(
                // Footer: Active Seat & Test Pass Ratio
                LayoutElementBuilders.Text.Builder()
                    .setText("Seat D · 18/21 pass")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(0xFF94A3B8.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()

        root.addContent(contentColumn)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(System.currentTimeMillis().toString())
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(root.build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .setFreshnessIntervalMillis(60_000) // Refresh every minute
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resourcesBuilder = ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)

        val imageBytes = fetchLatestScreenshotBytes()

        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val inlineImage = ResourceBuilders.InlineImageResource.Builder()
                .setData(imageBytes)
                .setWidthPx(360)
                .setHeightPx(220)
                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                .build()

            val imageResource = ResourceBuilders.ImageResource.Builder()
                .setInlineResource(inlineImage)
                .build()

            resourcesBuilder.addIdToImageMapping(SCREENSHOT_ID, imageResource)
        }

        return Futures.immediateFuture(resourcesBuilder.build())
    }

    private fun fetchLatestScreenshotBytes(): ByteArray? {
        return try {
            // 1. Fetch JSON from progress board watch endpoint
            val apiUrl = URL(WATCH_API_URL)
            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val projects = json.optJSONArray("projects") ?: return null

            // Find the most recent screenshot between DDCS and BSPLINE
            var latestShotUrl: String? = null
            var maxTimestamp: Long = 0

            for (i in 0 until projects.length()) {
                val p = projects.getJSONObject(i)
                val shot = p.optJSONObject("shot")
                if (shot != null) {
                    val shotAt = shot.optLong("at", 0)
                    if (shotAt >= maxTimestamp) {
                        maxTimestamp = shotAt
                        latestShotUrl = shot.optString("thumb", shot.optString("full", ""))
                    }
                }
            }

            if (latestShotUrl.isNullOrEmpty()) return null

            // 2. Download and resize image to optimal watch resolution
            val imgUrl = URL(latestShotUrl)
            val imgConn = (imgUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            if (imgConn.responseCode != 200) return null

            val originalBitmap = BitmapFactory.decodeStream(imgConn.inputStream) ?: return null
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 360, 220, true)

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val SCREENSHOT_ID = "latest_screenshot"
        const val WATCH_API_URL = "https://progress-board.dansemur.workers.dev/watch"
    }
}
