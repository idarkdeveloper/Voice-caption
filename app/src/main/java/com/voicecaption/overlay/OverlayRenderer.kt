package com.voicecaption.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.voicecaption.caption.CaptionFormatter
import com.voicecaption.core.Caption
import com.voicecaption.core.CaptionSettings

/**
 * Floating captions: two stacked plates in one TYPE_APPLICATION_OVERLAY window. The translation
 * plate is the main one; with "show original text" a smaller original-language plate sits above
 * it. The two plates move together when dragged.
 *
 * Threading: every public method may be called from any thread; work is marshalled to the main
 * thread (run inline when already there, otherwise posted to a main-looper [Handler]).
 *
 * Touches: the window is NOT_FOCUSABLE | NOT_TOUCH_MODAL and wraps the plates, so touches outside
 * them reach the app underneath. Dragging a plate moves the window vertically; the offset (distance
 * from the bottom edge) is kept in memory for the process lifetime and survives hide()/show() and
 * renderer re-creation. Persisting it to storage is a later task.
 */
class OverlayRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density

    // Main-thread state below.
    private var container: LinearLayout? = null
    private var sourcePlate: TextView? = null
    private var plate: TextView? = null // the translation plate
    private var attached = false
    private var alpha = OverlayMath.plateAlpha(CaptionSettings().opacity)
    private var debugLine: String? = null
    private var lastCaptions: List<Caption> = emptyList()
    private var lastSettings = CaptionSettings()

    /** Debug builds only: a small diagnostics line under the captions. Null removes it. */
    fun setDebugLine(text: String?) = onMain {
        debugLine = text
        render(lastCaptions, lastSettings)
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    /** Attaches the (initially empty, hidden) plates. No-op without overlay permission or if already shown. */
    fun show() = onMain {
        if (attached || !canDrawOverlays()) return@onMain
        if (container == null) createPlates()
        val window = container ?: return@onMain
        window.visibility = View.GONE
        refreshGeometry()
        try {
            windowManager.addView(window, layoutParams())
            attached = true
        } catch (_: RuntimeException) {
            // Permission revoked between check and add (SecurityException) or a bad window token
            // (BadTokenException): stay hidden rather than crash the service.
            attached = false
        }
    }

    /**
     * Shows the captions; nothing to show hides the plates but keeps the window attached.
     * Replaces any message from [showMessage]. Opacity and font size apply on every call.
     */
    fun render(captions: List<Caption>, settings: CaptionSettings) = onMain {
        val window = container ?: return@onMain
        val translation = plate ?: return@onMain
        val original = sourcePlate ?: return@onMain
        if (!attached) return@onMain
        lastCaptions = captions
        lastSettings = settings
        val lines = CaptionFormatter.plates(captions, settings.bilingual)
        val debug = debugLine
        val plateAlpha = OverlayMath.plateAlpha(settings.opacity)
        if (lines.isEmpty && debug == null) {
            original.visibility = View.GONE
            translation.visibility = View.GONE
            window.visibility = View.GONE
            return@onMain
        }
        refreshGeometry()

        if (lines.source.isEmpty()) {
            original.visibility = View.GONE
        } else {
            applyStyle(original, settings.fontSizeSp * SOURCE_LINE_SCALE, plateAlpha, settings, dimmed = true)
            original.maxLines = MAX_LINES
            original.text = lines.source.joinToString("\n")
            original.visibility = View.VISIBLE
        }

        when {
            lines.translation.isNotEmpty() -> {
                applyStyle(translation, settings.fontSizeSp.toFloat(), plateAlpha, settings)
                translation.maxLines = MAX_LINES + (if (debug != null) DEBUG_EXTRA_LINES else 0)
                val body = lines.translation.joinToString("\n")
                translation.text = if (debug == null) body else body + "\n" + debug
                translation.visibility = View.VISIBLE
            }
            debug != null -> {
                applyStyle(translation, DEBUG_SP.toFloat(), plateAlpha, settings)
                translation.maxLines = MAX_LINES
                translation.text = debug
                translation.visibility = View.VISIBLE
            }
            else -> translation.visibility = View.GONE
        }
        window.visibility = View.VISIBLE
    }

    /** Shows a plain status/error message on the plate until the next [render] with captions. */
    fun showMessage(text: String) = onMain {
        val window = container ?: return@onMain
        val view = plate ?: return@onMain
        if (!attached) return@onMain
        refreshGeometry()
        sourcePlate?.visibility = View.GONE
        view.maxLines = MAX_LINES
        view.text = text
        view.visibility = View.VISIBLE
        applyStyle(view, null, alpha, lastSettings)
        window.visibility = View.VISIBLE
    }

    /** Removes the window. Safe to call repeatedly. */
    fun hide() = onMain {
        val window = container ?: return@onMain
        if (!attached) return@onMain
        attached = false
        try {
            windowManager.removeView(window)
        } catch (_: IllegalArgumentException) {
            // View was already gone (e.g. window removed by the system); nothing to do.
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /**
     * Re-derives everything that depends on the CURRENT screen size (it changes on rotation and
     * folding): the plates' max width, and the dragged vertical offset, re-clamped to the screen
     * height. Called before every show/render so a stale landscape width never leaks into portrait.
     */
    private fun refreshGeometry() {
        val window = container ?: return
        val maxWidth = OverlayMath.maxPlateWidthPx(appContext.resources.displayMetrics.widthPixels)
        plate?.maxWidth = maxWidth
        sourcePlate?.maxWidth = maxWidth
        val lp = window.layoutParams as? WindowManager.LayoutParams ?: return
        if (!attached) return
        val clamped = clampedOffset(window, lp.y)
        if (clamped != lp.y) moveTo(window, clamped)
    }

    private fun clampedOffset(view: View, requested: Int): Int {
        val screenH = appContext.resources.displayMetrics.heightPixels
        // Keep clear of the status bar area at the top; y=0 is already above the gesture/nav area
        // because overlay windows are laid out inside the system bars.
        val max = screenH - view.height - dp(TOP_RESERVED_DP)
        return OverlayMath.clampOffsetY(requested, 0, max)
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun newPlate(window: LinearLayout): TextView {
        val view = TextView(appContext)
        view.setTextColor(Color.WHITE)
        view.gravity = Gravity.CENTER
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxLines = MAX_LINES
        view.setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.setOnTouchListener(DragListener(appContext, window) { requested -> moveTo(window, requested) })
        applyStyle(view, CaptionSettings().fontSizeSp.toFloat(), alpha, CaptionSettings())
        return view
    }

    private fun createPlates() {
        val window = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val original = newPlate(window).apply { visibility = View.GONE }
        val translation = newPlate(window)
        window.addView(
            original,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(PLATE_GAP_DP) },
        )
        window.addView(
            translation,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        container = window
        sourcePlate = original
        plate = translation
    }

    /** [fontSizeSp] null keeps the current size. [dimmed] draws the text slightly softer (original-language plate). */
    private fun applyStyle(view: TextView, fontSizeSp: Float?, newAlpha: Int, settings: CaptionSettings, dimmed: Boolean = false) {
        alpha = newAlpha
        if (fontSizeSp != null) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        val text = settings.textColor
        view.setTextColor(if (dimmed) (text and 0x00FFFFFF) or (DIMMED_TEXT_ALPHA shl 24) else text)
        view.background = GradientDrawable().apply {
            setColor(settings.plateColor)
            this.alpha = newAlpha
            cornerRadius = dp(CORNER_DP).toFloat()
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = bottomOffsetPx ?: dp(DEFAULT_BOTTOM_DP)
    }

    /** [requested] is the new requested distance from the bottom edge, in px. */
    private fun moveTo(window: View, requested: Int) {
        if (!attached) return
        val lp = window.layoutParams as? WindowManager.LayoutParams ?: return
        lp.y = clampedOffset(window, requested)
        bottomOffsetPx = lp.y
        try {
            windowManager.updateViewLayout(window, lp)
        } catch (_: IllegalArgumentException) {
            // View no longer attached.
        }
    }

    /** Reports vertical drags of a plate (moving the whole [window]) and forwards a tap to performClick() for accessibility. */
    private class DragListener(
        context: Context,
        private val window: View,
        private val onDrag: (Int) -> Unit,
    ) : View.OnTouchListener {
        private var startRawY = 0f
        private var startOffset = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startOffset = (window.layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startRawY
                    if (!dragging && kotlin.math.abs(dy) > slop) dragging = true
                    // Finger up (negative dy) raises the plates: offset from the bottom grows.
                    if (dragging) onDrag(startOffset - dy.toInt())
                }
                MotionEvent.ACTION_UP -> if (!dragging) v.performClick()
            }
            return true
        }
    }

    private companion object {
        const val PADDING_DP = 12
        const val CORNER_DP = 8
        const val PLATE_GAP_DP = 6
        const val DEFAULT_BOTTOM_DP = 48
        const val DEBUG_SP = 11
        const val DEBUG_EXTRA_LINES = 4
        const val TOP_RESERVED_DP = 48
        const val MAX_LINES = 5
        const val SOURCE_LINE_SCALE = 0.8f
        const val DIMMED_TEXT_ALPHA = 217 // 85 %

        /** Remembered drag position (px from bottom); null until the user drags. Main thread only. */
        var bottomOffsetPx: Int? = null
    }
}
