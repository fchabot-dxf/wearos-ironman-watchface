package com.dansemur.ironman.tile

import androidx.concurrent.futures.ResolvingFuture
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.ProgressIndicator
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

/**
 * TestSuiteTileService
 *
 * Full swipeable dashboard card showing your automated test status.
 */
class TestSuiteTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return serviceScope.future {
            // Read configuration / cached data
            val passed = 48
            val total = 50
            val percent = 96
            val suiteName = "E2E Playwright Suite"

            val layout = PrimaryLayout.Builder(requestParams.deviceConfiguration)
                .setPrimaryLabelTextContent(
                    Text.Builder(this@TestSuiteTileService, suiteName)
                        .setTypography(androidx.wear.protolayout.material.Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(argb(0xFF94A3B8.toInt()))
                        .build()
                )
                .setContent(
                    Column.Builder()
                        .setWidth(expand())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(
                            Text.Builder(this@TestSuiteTileService, "$percent%")
                                .setTypography(androidx.wear.protolayout.material.Typography.TYPOGRAPHY_DISPLAY1)
                                .setColor(argb(0xFF38BDF8.toInt()))
                                .build()
                        )
                        .addContent(
                            Spacer.Builder().setHeight(dp(4f)).build()
                        )
                        .addContent(
                            Text.Builder(this@TestSuiteTileService, "$passed / $total Passed")
                                .setTypography(androidx.wear.protolayout.material.Typography.TYPOGRAPHY_TITLE3)
                                .setColor(argb(0xFFF8FAFC.toInt()))
                                .build()
                        )
                        .build()
                )
                .setSecondaryLabelTextContent(
                    Text.Builder(this@TestSuiteTileService, "Last run: 5m ago")
                        .setTypography(androidx.wear.protolayout.material.Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(argb(0xFF64748B.toInt()))
                        .build()
                )
                .build()

            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder()
                                        .setRoot(layout)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .setFreshnessIntervalMillis(60_000) // update every minute
                .build()
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return serviceScope.future {
            ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build()
        }
    }
}
