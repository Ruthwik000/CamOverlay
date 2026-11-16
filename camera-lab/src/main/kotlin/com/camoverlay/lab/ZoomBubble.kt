package com.camoverlay.lab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.camoverlay.core.model.CameraCapability
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A floating, draggable zoom control that lives inside CameraLab's own window.
 *
 * It changes CameraLab's own camera only (through [onZoom]); it needs no hook and no overlay
 * permission. The slider range comes from the camera's CONTROL_ZOOM_RATIO_RANGE, so it goes up to
 * whatever the phone reports for the camera that is open (never a hard-coded maximum).
 *
 * Tap the bubble to open or close the panel; drag it to move it.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class ZoomBubble(
    context: Context,
    private val parent: FrameLayout,
    private val onZoom: (Float) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var zoomMin = 1f
    private var zoomMax = 1f
    private var current = 1f
    private var supported = false

    // Throttle: at most one camera update every 30 ms while dragging, always ending on the final value.
    private var lastSentAt = 0L
    private val sendFinal = Runnable { onZoom(current) }

    private val bubble = TextView(context).apply {
        text = "1.0x"
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(200, 30, 30, 30))
            setStroke(dp(2), Color.argb(220, 255, 170, 0))
        }
        elevation = dp(6).toFloat()
    }

    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.MONOSPACE
    }

    private val seek = SeekBar(context)

    private val quickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(220, 20, 20, 20))
        }
        elevation = dp(6).toFloat()
        visibility = View.GONE
        addView(label)
        addView(seek, LinearLayout.LayoutParams(dp(240), WRAP_CONTENT))
        addView(quickRow)
    }

    init {
        val size = dp(56)
        parent.addView(bubble, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        parent.addView(panel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        bubble.visibility = View.GONE

        // Start on the right edge, a third of the way down.
        parent.post {
            bubble.x = (parent.width - size - dp(12)).toFloat()
            bubble.y = parent.height / 3f
        }

        installDrag(context)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                setZoom(zoomMin + progress / 10f, fromSlider = true)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
    }

    /** Call with the open camera's capability. Shows the bubble and sizes the slider. */
    fun setCapability(cap: CameraCapability, appZoom: Float) {
        supported = cap.zoomSupported
        zoomMin = cap.zoomMin
        zoomMax = cap.zoomMax
        current = appZoom.coerceIn(zoomMin, zoomMax)

        seek.isEnabled = supported
        seek.max = ((zoomMax - zoomMin) * 10).roundToInt().coerceAtLeast(0)
        seek.progress = ((current - zoomMin) * 10).roundToInt()
        buildQuickButtons()
        refreshText()
        bubble.visibility = View.VISIBLE
    }

    /** Hide the bubble and panel, e.g. while the camera is closed. */
    fun hide() {
        bubble.visibility = View.GONE
        panel.visibility = View.GONE
    }

    private fun buildQuickButtons() {
        quickRow.removeAllViews()
        if (!supported) return
        val stops = linkedMapOf<String, Float>()
        stops["1x"] = 1f
        stops["2x"] = 2f
        stops["Max"] = zoomMax
        for ((name, value) in stops) {
            if (value < zoomMin - 0.001f || value > zoomMax + 0.001f) continue
            if (name == "2x" && abs(zoomMax - 2f) < 0.05f) continue // "Max" already covers it
            quickRow.addView(smallButton(name) { setZoom(value, fromSlider = false) })
        }
        quickRow.addView(smallButton("Reset") { setZoom(zoomMin.coerceAtLeast(1f).coerceAtMost(zoomMax), fromSlider = false) })
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(bubble.context).apply {
        this.text = text
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun setZoom(value: Float, fromSlider: Boolean) {
        current = value.coerceIn(zoomMin, zoomMax)
        if (!fromSlider) seek.progress = ((current - zoomMin) * 10).roundToInt()
        refreshText()

        bubble.removeCallbacks(sendFinal)
        val now = SystemClock.uptimeMillis()
        if (now - lastSentAt >= THROTTLE_MS) {
            lastSentAt = now
            onZoom(current)
        } else {
            bubble.postDelayed(sendFinal, THROTTLE_MS)
        }
    }

    private fun refreshText() {
        if (!supported) {
            bubble.text = "1x"
            label.text = "Zoom not supported on this camera"
            return
        }
        bubble.text = String.format(Locale.US, "%.1fx", current)
        label.text = String.format(Locale.US, "%.1fx  (max %.1fx)", current, zoomMax)
    }

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        panel.post { placePanel() }
    }

    /** Put the panel next to the bubble, on whichever side has room, inside the screen. */
    private fun placePanel() {
        val gap = dp(8)
        val leftSide = bubble.x + bubble.width / 2f > parent.width / 2f
        var x = if (leftSide) bubble.x - panel.width - gap else bubble.x + bubble.width + gap
        var y = bubble.y
        x = x.coerceIn(0f, (parent.width - panel.width).coerceAtLeast(0).toFloat())
        y = y.coerceIn(0f, (parent.height - panel.height).coerceAtLeast(0).toFloat())
        panel.x = x
        panel.y = y
    }

    private fun installDrag(context: Context) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false

        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = v.x
                    startY = v.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        v.x = (startX + dx).coerceIn(0f, (parent.width - v.width).toFloat())
                        v.y = (startY + dy).coerceIn(0f, (parent.height - v.height).toFloat())
                        if (panel.visibility == View.VISIBLE) placePanel()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) togglePanel()
                    true
                }

                else -> false
            }
        }
    }

    private companion object {
        const val THROTTLE_MS = 30L
    }
}
