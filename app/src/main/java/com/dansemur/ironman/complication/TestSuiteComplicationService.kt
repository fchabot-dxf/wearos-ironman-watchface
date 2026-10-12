package com.dansemur.ironman.complication

import android.net.Uri
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * TestSuiteComplicationService
 *
 * Parametric & live data source connecting directly to
 * https://progress-board.dansemur.workers.dev/watch
 */
class TestSuiteComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 86f,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder("86% Tests Passed").build()
                )
                    .setText(PlainComplicationText.Builder("86%").build())
                    .setTitle(PlainComplicationText.Builder("BSPLINE").build())
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("86%").build(),
                    contentDescription = PlainComplicationText.Builder("BSPLINE 86%").build()
                )
                    .setTitle(PlainComplicationText.Builder("BSPLINE").build())
                    .build()
            }
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val config = getSharedPreferences("test_config", MODE_PRIVATE)
        val endpointUrl = config.getString("status_url", DEFAULT_URL) ?: DEFAULT_URL

        val testData = fetchTestProgress(endpointUrl)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = testData.percent.toFloat(),
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder("${testData.project}: ${testData.percent}%").build()
                )
                    .setText(PlainComplicationText.Builder("${testData.percent}%").build())
                    .setTitle(PlainComplicationText.Builder(testData.project.uppercase()).build())
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("${testData.percent}%").build(),
                    contentDescription = PlainComplicationText.Builder("${testData.project}: ${testData.percent}%").build()
                )
                    .setTitle(PlainComplicationText.Builder(testData.project.uppercase()).build())
                    .build()
            }
            else -> getPreviewData(request.complicationType) ?: error("Unsupported type")
        }
    }

    private suspend fun fetchTestProgress(urlString: String): TestStatusResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val runs = json.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val run = runs.getJSONObject(0)
                    val project = run.optString("project", "BSPLINE")
                    val pct = run.optInt("pct", 86)
                    val eta = run.optString("eta", "")
                    TestStatusResult(project = project, percent = pct, eta = eta)
                } else {
                    TestStatusResult(project = "BSPLINE", percent = 86, eta = "-4M")
                }
            } else {
                TestStatusResult(project = "BSPLINE", percent = 86, eta = "-4M")
            }
        } catch (e: Exception) {
            TestStatusResult(project = "BSPLINE", percent = 86, eta = "-4M")
        }
    }

    data class TestStatusResult(
        val project: String,
        val percent: Int,
        val eta: String
    )

    companion object {
        const val DEFAULT_URL = "https://progress-board.dansemur.workers.dev/watch"
    }
}
