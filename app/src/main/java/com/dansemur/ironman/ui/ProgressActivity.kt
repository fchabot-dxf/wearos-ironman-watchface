package com.dansemur.ironman.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import com.dansemur.ironman.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProgressActivity
 *
 * Full-screen Wear OS interactive Progress Board dashboard with screenshots,
 * test suite passes, and sprint status.
 */
class ProgressActivity : Activity() {

    private lateinit var scrollView: ScrollView
    private lateinit var projectsContainer: LinearLayout

    companion object {
        private const val WATCH_URL = "https://progress-board.dansemur.workers.dev/watch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)

        scrollView = findViewById(R.id.scrollView)
        projectsContainer = findViewById(R.id.projectsContainer)

        loadProgress()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEventCompat.AXIS_SCROLL) * 90f
            scrollView.smoothScrollBy(0, delta.toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun loadProgress() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(WATCH_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("projects")
                    if (arr != null && arr.length() > 0) {
                        withContext(Dispatchers.Main) {
                            projectsContainer.removeAllViews()
                            for (i in 0 until arr.length()) {
                                val p = arr.getJSONObject(i)
                                val name = p.optString("name", "Project")
                                val progress = p.optInt("pct", 0)
                                val passed = p.optInt("passed", 0)
                                val total = p.optInt("total", 0)
                                val runner = p.optString("runner", "")
                                val shot = p.optJSONObject("shot")
                                val shotThumb = shot?.optString("thumb", shot.optString("full", "")) ?: ""

                                val card = createProjectCard(name, progress, passed, total, runner, shotThumb)
                                projectsContainer.addView(card)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun createProjectCard(
        name: String,
        pct: Int,
        passed: Int,
        total: Int,
        runner: String,
        shotThumb: String
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(16, 14, 16, 14)
        }

        // Header: Name + Pct
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvName = TextView(this).apply {
            text = name.uppercase()
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvName)

        val tvPct = TextView(this).apply {
            text = "$pct%"
            textSize = 13f
            setTextColor(Color.parseColor("#10B981"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(tvPct)
        card.addView(header)

        // Subtitle: Runner & Tests
        val tvSub = TextView(this).apply {
            val passText = if (total > 0) "$passed/$total pass" else "Tests passés"
            text = if (runner.isNotEmpty()) "$runner · $passText" else passText
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 8)
        }
        card.addView(tvSub)

        // Screenshot thumbnail if available
        if (shotThumb.isNotEmpty()) {
            val ivShot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    200
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#0F172A"))
            }
            card.addView(ivShot)

            // Async download thumbnail
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imgConn = URL(shotThumb).openConnection() as HttpURLConnection
                    imgConn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                    imgConn.connectTimeout = 4000
                    imgConn.readTimeout = 4000
                    if (imgConn.responseCode == 200) {
                        val bytes = imgConn.inputStream.use { it.readBytes() }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            withContext(Dispatchers.Main) {
                                ivShot.setImageBitmap(bmp)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return card
    }
}
