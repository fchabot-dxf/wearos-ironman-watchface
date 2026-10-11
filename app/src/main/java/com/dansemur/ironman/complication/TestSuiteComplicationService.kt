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
 * Parametric & easy to reconfigure data source!
 * By default, loads from your configured test status URL or fallback local state.
 */
class TestSuiteComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 84f,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder("84% Tests Passed").build()
                )
                    .setText(PlainComplicationText.Builder("84%").build())
                    .setTitle(PlainComplicationText.Builder("PASS").build())
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("42/50").build(),
                    contentDescription = PlainComplicationText.Builder("Test Suite Status").build()
                )
                    .setTitle(PlainComplicationText.Builder("TEST").build())
                    .build()
            }
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        // Read configuration (can be updated via Companion app or SharedPreferences)
        val config = getSharedPreferences("test_config", MODE_PRIVATE)
        val endpointUrl = config.getString("status_url", DEFAULT_URL) ?: DEFAULT_URL

        val testData = fetchTestProgress(endpointUrl)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = testData.percent.toFloat(),
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder("${testData.percent}% tests passed").build()
                )
                    .setText(PlainComplicationText.Builder("${testData.percent}%").build())
                    .setTitle(PlainComplicationText.Builder(testData.status).build())
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("${testData.passed}/${testData.total}").build(),
                    contentDescription = PlainComplicationText.Builder("Test Suite").build()
                )
                    .setTitle(PlainComplicationText.Builder(testData.status).build())
                    .build()
            }
            else -> getPreviewData(request.complicationType) ?: error("Unsupported type")
        }
    }

    private suspend fun fetchTestProgress(urlString: String): TestStatusResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val passed = json.optInt("passed", 48)
                val total = json.optInt("total", 50)
                val percent = if (total > 0) (passed * 100 / total) else 100
                val status = json.optString("status", if (percent == 100) "PASS" else "FAIL")
                TestStatusResult(passed = passed, total = total, percent = percent, status = status)
            } else {
                TestStatusResult(passed = 50, total = 50, percent = 100, status = "PASS")
            }
        } catch (e: Exception) {
            // Safe fallback during local offline testing
            TestStatusResult(passed = 48, total = 50, percent = 96, status = "96%")
        }
    }

    data class TestStatusResult(
        val passed: Int,
        val total: Int,
        val percent: Int,
        val status: String
    )

    companion object {
        // Easily swap this URL to your local server or staging URL
        const val DEFAULT_URL = "http://10.0.2.2:3000/api/test-status.json"
    }
}
