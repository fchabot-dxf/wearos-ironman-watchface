package com.dansemur.ironman.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.dansemur.ironman.R

/**
 * WeatherRadarActivity
 *
 * Full-screen Wear OS interactive activity displaying:
 * 1. Live Environment Canada GeoMet 1km radar map.
 * 2. 24-hour hourly forecast cards.
 * 3. 7-day daily forecast cards.
 * With smooth rotary crown knob scrolling support for Pixel Watch.
 */
class WeatherRadarActivity : Activity() {

    private lateinit var scrollView: ScrollView
    private lateinit var tvLocation: TextView
    private lateinit var tvCurrentTemp: TextView
    private lateinit var tvCondition: TextView
    private lateinit var radarImageView: ImageView
    private lateinit var tvRadarStatus: TextView
    private lateinit var hourlyContainer: LinearLayout
    private lateinit var dailyContainer: LinearLayout

    companion object {
        private const val WEATHER_URL = "https://progress-board.dansemur.workers.dev/watch/weather"
        private const val RADAR_URL = "https://progress-board.dansemur.workers.dev/watch/radar"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_radar)

        scrollView = findViewById(R.id.scrollView)
        tvLocation = findViewById(R.id.tvLocation)
        tvCurrentTemp = findViewById(R.id.tvCurrentTemp)
        tvCondition = findViewById(R.id.tvCondition)
        radarImageView = findViewById(R.id.radarImageView)
        tvRadarStatus = findViewById(R.id.tvRadarStatus)
        hourlyContainer = findViewById(R.id.hourlyContainer)
        dailyContainer = findViewById(R.id.dailyContainer)

        // Enable rotary crown focus for smooth knob scrolling on Pixel Watch
        scrollView.isFocusable = true
        scrollView.isFocusableInTouchMode = true
        scrollView.requestFocus()

        loadData()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Handle Pixel Watch rotating crown
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEventCompat.AXIS_SCROLL) * 48f
            scrollView.smoothScrollBy(0, delta.toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun loadData() {
        // 1. Fetch Radar
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(RADAR_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        radarImageView.setImageBitmap(bmp)
                        tvRadarStatus.text = "ECCC GeoMet · Direct"
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fetch Forecasts
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(WEATHER_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    withContext(Dispatchers.Main) {
                        populateWeather(jsonStr)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun populateWeather(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val cur = root.optJSONObject("current")
            val loc = root.optJSONObject("location")?.optString("name", "Montréal") ?: "Montréal"
            val temp = cur?.optInt("temp", 0) ?: 0
            val feelsLike = cur?.optInt("feelsLike", temp) ?: temp
            val cond = cur?.optString("conditionFr", "") ?: ""

            tvLocation.text = loc.uppercase() + " · QC"
            tvCurrentTemp.text = "$temp°"
            tvCondition.text = "$cond · ressenti $feelsLike°"

            // Populate Hourly (Next 24h)
            hourlyContainer.removeAllViews()
            val hourlyArr = root.optJSONArray("hourly")
            if (hourlyArr != null) {
                for (i in 0 until minOf(24, hourlyArr.length())) {
                    val hObj = hourlyArr.getJSONObject(i)
                    val hour = hObj.optString("hour", "").replace(":00", "h")
                    val hTemp = hObj.optInt("temp", 0)
                    val hPop = hObj.optInt("pop", 0)
                    val hCond = hObj.optString("conditionFr", "")

                    val row = createCardRow(
                        left = hour,
                        mid = hCond,
                        right = "$hTemp°",
                        sub = if (hPop > 0) "$hPop% pluie" else ""
                    )
                    hourlyContainer.addView(row)
                }
            }

            // Populate Daily (7 days)
            dailyContainer.removeAllViews()
            val dailyArr = root.optJSONArray("daily")
            if (dailyArr != null) {
                for (i in 0 until dailyArr.length()) {
                    val dObj = dailyArr.getJSONObject(i)
                    val day = dObj.optString("dayFr", "")
                    val max = dObj.optInt("tempMax", 0)
                    val min = dObj.optInt("tempMin", 0)
                    val pop = dObj.optInt("pop", 0)
                    val dCond = dObj.optString("conditionFr", "")

                    val row = createCardRow(
                        left = day,
                        mid = dCond,
                        right = "$max° / $min°",
                        sub = if (pop > 0) "$pop% pluie" else ""
                    )
                    dailyContainer.addView(row)
                }
            }
        } catch (_: Exception) {}
    }

    private fun createCardRow(left: String, mid: String, right: String, sub: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 14)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvLeft = TextView(this).apply {
            text = left
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            width = 130
        }

        val midLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvMid = TextView(this).apply {
            text = mid
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        midLayout.addView(tvMid)

        if (sub.isNotEmpty()) {
            val tvSub = TextView(this).apply {
                text = sub
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
            }
            midLayout.addView(tvSub)
        }

        val tvRight = TextView(this).apply {
            text = right
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.END
        }

        row.addView(tvLeft)
        row.addView(midLayout)
        row.addView(tvRight)

        return row
    }
}
