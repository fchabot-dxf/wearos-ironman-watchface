package com.dansemur.ironman.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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
 * NewsActivity
 *
 * Full-screen Wear OS interactive news reader with rotary crown knob scrolling.
 */
class NewsActivity : Activity() {

    private lateinit var scrollView: ScrollView
    private lateinit var storiesContainer: LinearLayout

    companion object {
        private const val NEWS_URL = "https://progress-board.dansemur.workers.dev/watch/news"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)

        scrollView = findViewById(R.id.scrollView)
        storiesContainer = findViewById(R.id.storiesContainer)

        loadNews()
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

    private fun loadNews() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(NEWS_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "IronmanWatch/1.0 (Android; WearOS)")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("stories")
                    if (arr != null && arr.length() > 0) {
                        withContext(Dispatchers.Main) {
                            storiesContainer.removeAllViews()
                            for (i in 0 until arr.length()) {
                                val s = arr.getJSONObject(i)
                                val title = s.optString("rawTitle", "")
                                val desc = s.optString("rawDesc", "")
                                val card = createNewsCard(i + 1, title, desc)
                                storiesContainer.addView(card)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun createNewsCard(num: Int, title: String, desc: String): View {
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

        val tvNum = TextView(this).apply {
            text = "ARTICLE #$num"
            textSize = 10f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        card.addView(tvNum)

        val tvTitle = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 4, 0, 6)
        }
        card.addView(tvTitle)

        if (desc.isNotEmpty()) {
            val tvDesc = TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setLineSpacing(2f, 1f)
            }
            card.addView(tvDesc)
        }

        return card
    }
}
