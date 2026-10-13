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
import kotlinx.coroutines.isActive
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

    private lateinit var timelineContainer: View
    private lateinit var tvTimelineBadge: TextView
    private lateinit var timelineTrack: LinearLayout
    private val timelinePips = mutableListOf<View>()

    data class RadarFrame(
        val time: String,
        val relative: String,
        val deltaMins: Int,
        val bitmap: android.graphics.Bitmap
    )

    private val frameList = mutableListOf<RadarFrame>()
    private var activeFrameIdx = 0
    private var isPaused = false
    private var animationJob: kotlinx.coroutines.Job? = null
    private var manualScrubJob: kotlinx.coroutines.Job? = null

    private var currentZoom = 9
    private lateinit var radarBaseMap: ImageView
    private lateinit var btnZoomToggle: TextView
    private lateinit var topBarLayout: View
    private lateinit var tvScrollPrompt: View

    companion object {
        private const val WEATHER_URL = "https://progress-board.dansemur.workers.dev/watch/weather"
        private const val BASE_FRAMES_URL = "https://progress-board.dansemur.workers.dev/watch/radar/frames"
        private const val BASE_RADAR_URL = "https://progress-board.dansemur.workers.dev/watch/radar"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_weather_radar)

        scrollView = findViewById(R.id.scrollView)
        tvLocation = findViewById(R.id.tvLocation)
        tvCurrentTemp = findViewById(R.id.tvCurrentTemp)
        tvCondition = findViewById(R.id.tvCondition)
        radarBaseMap = findViewById(R.id.radarBaseMap)
        radarImageView = findViewById(R.id.radarImageView)
        tvRadarStatus = findViewById(R.id.tvRadarStatus)
        btnZoomToggle = findViewById(R.id.btnZoomToggle)
        topBarLayout = findViewById(R.id.topBarLayout)
        tvScrollPrompt = findViewById(R.id.tvScrollPrompt)
        hourlyContainer = findViewById(R.id.hourlyContainer)
        dailyContainer = findViewById(R.id.dailyContainer)

        timelineContainer = findViewById(R.id.timelineContainer)
        tvTimelineBadge = findViewById(R.id.tvTimelineBadge)
        timelineTrack = findViewById(R.id.timelineTrack)

        val fullScreenSpacer = findViewById<View>(R.id.fullScreenSpacer)
        fullScreenSpacer.layoutParams.height = resources.displayMetrics.heightPixels

        // Setup Zoom button toggle: 9 -> 8 -> 10 -> 9
        btnZoomToggle.setOnClickListener {
            currentZoom = when (currentZoom) {
                9 -> 8
                8 -> 10
                else -> 9
            }
            applyZoom(currentZoom)
        }

        // Tapping timeline bar toggles Play / Pause
        timelineContainer.setOnClickListener {
            togglePlayPause()
        }

        // Tapping radar sides steps backwards / forwards; center toggles Play / Pause
        radarImageView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val width = v.width.toFloat()
                val x = event.x
                if (x < width * 0.35f) {
                    stepFrame(-1)
                    return@setOnTouchListener true
                } else if (x > width * 0.65f) {
                    stepFrame(1)
                    return@setOnTouchListener true
                } else {
                    togglePlayPause()
                    return@setOnTouchListener true
                }
            }
            false
        }

        // Fade overlays as user scrolls down to reveal forecast
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val alpha = (1f - (scrollY / 180f)).coerceIn(0f, 1f)
            topBarLayout.alpha = alpha
            topBarLayout.visibility = if (alpha <= 0.05f) View.GONE else View.VISIBLE
            timelineContainer.alpha = alpha
            timelineContainer.visibility = if (alpha <= 0.05f) View.GONE else View.VISIBLE
            tvScrollPrompt.alpha = alpha
            tvScrollPrompt.visibility = if (alpha <= 0.05f) View.GONE else View.VISIBLE
        }

        // Enable rotary crown focus for smooth knob scrolling on Pixel Watch
        scrollView.isFocusable = true
        scrollView.isFocusableInTouchMode = true
        scrollView.requestFocus()

        applyZoom(currentZoom)
        loadForecast()

        // Background Silent OTA Check (instant update check on app launch)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.dansemur.ironman.ota.SilentSelfUpdater.checkForUpdate(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("WeatherRadar", "OTA check failed: ${e.message}")
            }
        }
    }

    private fun applyZoom(zoom: Int) {
        btnZoomToggle.text = "Z$zoom 🔍"
        val basemapRes = when (zoom) {
            8 -> R.drawable.basemap_z8
            10 -> R.drawable.basemap_z10
            else -> R.drawable.basemap_z9
        }
        radarBaseMap.setImageResource(basemapRes)
        loadRadarData(zoom)
    }

    override fun onDestroy() {
        super.onDestroy()
        animationJob?.cancel()
        manualScrubJob?.cancel()
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

    private fun createPipDrawable(isActive: Boolean, isLatest: Boolean, isPlayed: Boolean = false): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = 6f * resources.displayMetrics.density
        when {
            isActive && isLatest -> d.setColor(Color.parseColor("#34D399")) // Emerald green for Live / Direct
            isActive -> d.setColor(Color.parseColor("#38BDF8")) // Indiglo cyan active playback cursor
            isPlayed -> d.setColor(Color.parseColor("#0284C7")) // Deep cyan already played history
            isLatest -> d.setColor(Color.parseColor("#065F46")) // Dark emerald future direct
            else -> d.setColor(Color.parseColor("#1E293B")) // Slate-800 track pip
        }
        return d
    }

    private fun buildTimelineTrack() {
        timelineTrack.removeAllViews()
        timelinePips.clear()
        val count = frameList.size
        val density = resources.displayMetrics.density

        for (i in 0 until count) {
            val pip = View(this).apply {
                val lp = LinearLayout.LayoutParams(
                    (8 * density).toInt(),
                    (4 * density).toInt()
                ).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                layoutParams = lp
                background = createPipDrawable(false, isLatest = (i == count - 1), isPlayed = false)
                isClickable = true
                setOnClickListener {
                    jumpToFrame(i)
                }
            }
            timelinePips.add(pip)
            timelineTrack.addView(pip)
        }
    }

    private fun updateTimelineCursor(activeIdx: Int) {
        val count = timelinePips.size
        val density = resources.displayMetrics.density

        for (i in 0 until count) {
            val pip = timelinePips[i]
            val isActive = (i == activeIdx)
            val isLatest = (i == count - 1)
            val isPlayed = (i < activeIdx)
            val lp = pip.layoutParams as LinearLayout.LayoutParams

            if (isActive) {
                // Elongate cursor to 18dp glowing pill
                lp.width = (18 * density).toInt()
                lp.height = (6 * density).toInt()
                pip.background = createPipDrawable(isActive = true, isLatest = isLatest)
            } else {
                // Inactive / played: 8dp by 4dp
                lp.width = (8 * density).toInt()
                lp.height = (4 * density).toInt()
                pip.background = createPipDrawable(isActive = false, isLatest = isLatest, isPlayed = isPlayed)
            }
            pip.layoutParams = lp
        }

        if (activeIdx in frameList.indices) {
            val f = frameList[activeIdx]
            val relText = if (f.deltaMins == 0 || f.relative == "DIRECT") "EN DIRECT" else f.relative
            val pausePrefix = if (isPaused) "⏸ " else ""
            tvTimelineBadge.text = "$pausePrefix${f.time} · $relText"
            val badgeColor = if (f.deltaMins == 0 || f.relative == "DIRECT") Color.parseColor("#34D399") else Color.parseColor("#38BDF8")
            tvTimelineBadge.setTextColor(badgeColor)
        }
    }

    private fun displayFrame(idx: Int) {
        if (frameList.isEmpty()) return
        activeFrameIdx = idx.coerceIn(0, frameList.size - 1)
        val frame = frameList[activeFrameIdx]
        radarImageView.setImageBitmap(frame.bitmap)
        updateTimelineCursor(activeFrameIdx)
        val isLatest = (activeFrameIdx == frameList.size - 1)
        tvRadarStatus.text = "Radar 1h · ${if (isLatest) "Direct" else "Boucle"}"
    }

    private fun jumpToFrame(idx: Int) {
        displayFrame(idx)
        pauseTemporarily()
    }

    private fun stepFrame(direction: Int) {
        if (frameList.isEmpty()) return
        val next = (activeFrameIdx + direction + frameList.size) % frameList.size
        displayFrame(next)
        pauseTemporarily()
    }

    private fun pauseTemporarily() {
        isPaused = true
        animationJob?.cancel()
        manualScrubJob?.cancel()
        manualScrubJob = CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(4500L) // pause for 4.5 seconds on scrubbed frame
            isPaused = false
            startRadarAnimation()
        }
    }

    private fun togglePlayPause() {
        if (isPaused) {
            isPaused = false
            manualScrubJob?.cancel()
            startRadarAnimation()
        } else {
            isPaused = true
            animationJob?.cancel()
            updateTimelineCursor(activeFrameIdx)
        }
    }

    private fun loadRadarData(zoom: Int) {
        animationJob?.cancel()
        manualScrubJob?.cancel()
        isPaused = false
        radarImageView.alpha = 0.80f
        tvTimelineBadge.text = "Radar 1h · Chargement..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Request 10 frames = 1 hour of radar history!
                val framesUrl = "$BASE_FRAMES_URL?zoom=$zoom&count=10"
                val conn = URL(framesUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("frames")
                    if (arr != null && arr.length() > 0) {
                        val downloaded = mutableListOf<RadarFrame>()
                        for (i in 0 until arr.length()) {
                            val f = arr.getJSONObject(i)
                            val timeStr = f.optString("time", "")
                            val relStr = f.optString("relative", "")
                            val deltaMins = f.optInt("deltaMins", 0)
                            val tileUrl = f.optString("tileUrl", "")
                            try {
                                val tConn = URL(tileUrl).openConnection() as HttpURLConnection
                                tConn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                                tConn.connectTimeout = 4000
                                tConn.readTimeout = 4000
                                if (tConn.responseCode == 200) {
                                    val b = tConn.inputStream.use { it.readBytes() }
                                    val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                                    if (bmp != null) {
                                        downloaded.add(RadarFrame(timeStr, relStr, deltaMins, bmp))
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        if (downloaded.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                frameList.clear()
                                frameList.addAll(downloaded)
                                buildTimelineTrack()
                                activeFrameIdx = frameList.size - 1 // Start on latest frame
                                displayFrame(activeFrameIdx)
                                kotlinx.coroutines.delay(1200L)
                                startRadarAnimation()
                            }
                            return@launch
                        }
                    }
                }
            } catch (_: Exception) {}

            // Fallback direct frame
            try {
                val radarUrl = "$BASE_RADAR_URL?zoom=$zoom"
                val conn = URL(radarUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        radarImageView.setImageBitmap(bmp)
                        tvRadarStatus.text = "ECCC GeoMet · Direct"
                        tvTimelineBadge.text = "ECCC GeoMet · Direct"
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadForecast() {
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

    private fun startRadarAnimation() {
        animationJob?.cancel()
        if (isPaused || frameList.isEmpty()) return

        animationJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && frameList.isNotEmpty() && !isPaused) {
                displayFrame(activeFrameIdx)
                val isLatest = (activeFrameIdx == frameList.size - 1)
                activeFrameIdx = (activeFrameIdx + 1) % frameList.size
                kotlinx.coroutines.delay(if (isLatest) 1400L else 450L)
            }
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

            // Populate Hourly (Next 8h in horizontal carousel)
            hourlyContainer.removeAllViews()
            val hourlyArr = root.optJSONArray("hourly")
            if (hourlyArr != null) {
                val count = minOf(8, hourlyArr.length())
                for (i in 0 until count) {
                    val hObj = hourlyArr.getJSONObject(i)
                    val hour = hObj.optString("hour", "").replace(":00", "h")
                    val hTemp = hObj.optInt("temp", 0)
                    val hPop = hObj.optInt("pop", 0)

                    val card = createHourlyCard(hour, hTemp, hPop)
                    hourlyContainer.addView(card)
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

    private fun createHourlyCard(hour: String, temp: Int, pop: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                120, // width in px
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 0)
            }
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(8, 12, 8, 12)
        }

        val tvH = TextView(this).apply {
            text = hour
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val tvT = TextView(this).apply {
            text = "$temp°"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        val tvP = TextView(this).apply {
            text = if (pop > 0) "$pop%" else "0%"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            gravity = Gravity.CENTER
        }

        card.addView(tvH)
        card.addView(tvT)
        card.addView(tvP)
        return card
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
