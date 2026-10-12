package com.dansemur.ironman.complication

import android.net.Uri
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        // Asynchronously check for Cloud OTA updates without blocking complication render
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                com.dansemur.ironman.ota.SilentSelfUpdater.checkForUpdate(applicationContext)
            } catch (e: Exception) {
                // Ignore OTA check errors during complication render
            }
        }

        val config = getSharedPreferences("test_config", MODE_PRIVATE)
        val endpointUrl = config.getString("status_url", DEFAULT_URL) ?: DEFAULT_URL

        val testData = fetchTestProgress(endpointUrl)

        val newsList = listOf(
            "ESPACE: ARTEMIS VISE LE PÔLE SUD LUNAIRE",
            "QC: ACCÉLÉRATEUR QUANTIQUE À SHERBROOKE",
            "SCI: RECORD MONDIAL DE FUSION NUCLÉAIRE",
            "ESPACE: WEBB DÉCOUVRE UNE GALAXIE PRIMORDIALE",
            "QC: MONTRÉAL CONFIRME SON LEADERSHIP EN IA",
            "SCI: VACCIN À ARNm CONTRE LE CANCER EN PHASE 3",
            "ESPACE: PERSEVERANCE DÉTECTE DES MINÉRAUX SUR MARS",
            "QC: HYDRO-QUÉBEC ANNONCE NOUVEAU PARC ÉOLIEN"
        )
        val currentSlot = (System.currentTimeMillis() / (5 * 60 * 1000L)) % newsList.size
        val newsHeadline = newsList[currentSlot.toInt()]

        android.util.Log.d("TestSuiteComplication", "onComplicationRequest type=${request.complicationType} testDataActive=${testData?.hasActiveRun}")

        if (testData == null || !testData.hasActiveRun) {
            return when (request.complicationType) {
                ComplicationType.SHORT_TEXT -> {
                    ShortTextComplicationData.Builder(
                        text = PlainComplicationText.Builder(newsHeadline).build(),
                        contentDescription = PlainComplicationText.Builder(newsHeadline).build()
                    )
                        .setTitle(PlainComplicationText.Builder("INFO").build())
                        .build()
                }
                ComplicationType.RANGED_VALUE -> {
                    RangedValueComplicationData.Builder(
                        value = 0f,
                        min = 0f,
                        max = 100f,
                        contentDescription = PlainComplicationText.Builder(newsHeadline).build()
                    )
                        .setText(PlainComplicationText.Builder(newsHeadline).build())
                        .setTitle(PlainComplicationText.Builder("INFO").build())
                        .build()
                }
                else -> NoDataComplicationData()
            }
        }

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
                    text = PlainComplicationText.Builder("${testData.project.uppercase()}: ${testData.percent}%").build(),
                    contentDescription = PlainComplicationText.Builder("${testData.project}: ${testData.percent}%").build()
                )
                    .setTitle(PlainComplicationText.Builder(testData.project.uppercase()).build())
                    .build()
            }
            else -> NoDataComplicationData()
        }
    }

    private suspend fun fetchTestProgress(urlString: String): TestStatusResult? = withContext(Dispatchers.IO) {
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
                val runs = json.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val run = runs.getJSONObject(0)
                    val project = run.optString("project", "")
                    val pct = run.optInt("pct", -1)
                    val eta = run.optString("eta", "")
                    if (project.isNotEmpty() && pct >= 0) {
                        TestStatusResult(project = project, percent = pct, eta = eta, hasActiveRun = true)
                    } else {
                        TestStatusResult(project = "", percent = 0, eta = "", hasActiveRun = false)
                    }
                } else {
                    // No test suite currently running -> Turn off complication / return empty!
                    TestStatusResult(project = "", percent = 0, eta = "", hasActiveRun = false)
                }
            } else {
                TestStatusResult(project = "", percent = 0, eta = "", hasActiveRun = false)
            }
        } catch (e: Exception) {
            TestStatusResult(project = "", percent = 0, eta = "", hasActiveRun = false)
        }
    }

    data class TestStatusResult(
        val project: String,
        val percent: Int,
        val eta: String,
        val hasActiveRun: Boolean
    )

    companion object {
        const val DEFAULT_URL = "https://progress-board.dansemur.workers.dev/watch"
    }
}
