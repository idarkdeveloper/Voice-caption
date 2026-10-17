package com.voicecaption.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.voicecaption.caption.CaptionFormatter
import com.voicecaption.core.Caption
import com.voicecaption.core.CaptionSettings

/**
 * Floating caption plate: one [TextView] in a TYPE_APPLICATION_OVERLAY window.
 *
 * Threading: every public method may be called from any thread; work is marshalled to the main
 * thread (run inline when already there, otherwise posted to a main-looper [Handler]).
 *
 * Touches: the window is NOT_FOCUSABLE | NOT_TOUCH_MODAL and wraps the plate, so touches outside the
 * plate reach the app underneath. Dragging the plate moves it vertically; the offset (distance from
 * the bottom edge) is kept in memory for the process lifetime and survives hide()/show() and
 * renderer re-creation. Persisting it to storage is a later task.
 */
class OverlayRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density

    // Main-thread state below.
    private var plate: TextView? = null
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

    /** Attaches the (initially empty, hidden) plate. No-op without overlay permission or if already shown. */
    fun show() = onMain {
        if (attached || !canDrawOverlays()) return@onMain
        val view = plate ?: createPlate().also { plate = it }
        view.visibility = View.GONE
        refreshGeometry(view)
        try {
            windowManager.addView(view, layoutParams())
            attached = true
        } catch (_: RuntimeException) {
            // Permission revoked between check and add (SecurityException) or a bad window token
            // (BadTokenException): stay hidden rather than crash the service.
            attached = false
        }
    }

    /**
     * Shows the formatted captions; an empty list hides the text but keeps the window attached.
     * Replaces any message from [showMessage]. Opacity and font size apply on every call.
     */
    fun render(captions: List<Caption>, settings: CaptionSettings) = onMain {
        val view = plate ?: return@onMain
        if (!attached) return@onMain
        lastCaptions = captions
        lastSettings = settings
        val lines = CaptionFormatter.lines(captions, settings.bilingual)
        val debug = debugLine
        if (lines.isEmpty() && debug != null) {
            refreshGeometry(view)
            applyStyle(view, DEBUG_SP, OverlayMath.plateAlpha(settings.opacity))
            view.maxLines = 5
            view.text = debug
            view.visibility = View.VISIBLE
            return@onMain
        }
        if (lines.isEmpty()) {
            view.text = ""
            view.visibility = View.GONE
            return@onMain
        }
        refreshGeometry(view)
        applyStyle(view, settings.fontSizeSp, OverlayMath.plateAlpha(settings.opacity))
        // Two captions' worth of lines: 2 normally, 4 when each caption is source + translation.
        view.maxLines = (if (settings.bilingual) MAX_LINES_BILINGUAL else MAX_LINES) + (if (debug != null) 4 else 0)
        val body = buildText(captions, lines, settings.bilingual)
        view.text = if (debug == null) body else SpannableStringBuilder(body).append('\n').append(debug)
        view.visibility = View.VISIBLE
    }

    /** Shows a plain status/error message on the plate until the next [render] with captions. */
    fun showMessage(text: String) = onMain {
        val view = plate ?: return@onMain
        if (!attached) return@onMain
        refreshGeometry(view)
        view.maxLines = MAX_LINES_BILINGUAL
        view.text = text
        view.visibility = View.VISIBLE
        applyStyle(view, null, alpha)
    }

    /** Removes the window. Safe to call repeatedly. */
    fun hide() = onMain {
        val view = plate ?: return@onMain
        if (!attached) return@onMain
        attached = false
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // View was already gone (e.g. window removed by the system); nothing to do.
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /**
     * Re-derives everything that depends on the CURRENT screen size (it changes on rotation and
     * folding): the plate's max width, and the dragged vertical offset, re-clamped to the screen
     * height. Called before every show/render so a stale landscape width never leaks into portrait.
     */
    private fun refreshGeometry(view: TextView) {
        view.maxWidth = OverlayMath.maxPlateWidthPx(appContext.resources.displayMetrics.widthPixels)
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (!attached) return
        val clamped = clampedOffset(view, lp.y)
        if (clamped != lp.y) moveTo(view, clamped)
    }

    private fun clampedOffset(view: View, requested: Int): Int {
        val screenH = appContext.resources.displayMetrics.heightPixels
        // Keep clear of the status bar area at the top; y=0 is already above the gesture/nav area
        // because overlay windows are laid out inside the system bars.
        val max = screenH - view.height - dp(TOP_RESERVED_DP)
        return OverlayMath.clampOffsetY(requested, 0, max)
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun createPlate(): TextView {
        val view = TextView(appContext)
        view.setTextColor(Color.WHITE)
        view.gravity = Gravity.CENTER
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxLines = MAX_LINES
        view.setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
        refreshGeometry(view)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.setOnTouchListener(DragListener(appContext) { requested -> moveTo(view, requested) })
        applyStyle(view, CaptionSettings().fontSizeSp, alpha)
        return view
    }

    /** [fontSizeSp] null keeps the current size. */
    private fun applyStyle(view: TextView, fontSizeSp: Int?, newAlpha: Int) {
        alpha = newAlpha
        if (fontSizeSp != null) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
        view.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            this.alpha = newAlpha
            cornerRadius = dp(CORNER_DP).toFloat()
        }
    }

    /** Lines come from [CaptionFormatter]; in bilingual mode the source line of a translated caption is 80%. */
    private fun buildText(captions: List<Caption>, lines: List<String>, bilingual: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        for (c in captions) {
            val twoLines = bilingual && c.translatedText != null
            val count = if (twoLines) 2 else 1
            for (k in 0 until count) {
                if (i >= lines.size) break
                if (sb.isNotEmpty()) sb.append('\n')
                val start = sb.length
                sb.append(lines[i++])
                if (twoLines && k == 0) {
                    sb.setSpan(RelativeSizeSpan(SOURCE_LINE_SCALE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
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
    private fun moveTo(view: View, requested: Int) {
        if (!attached) return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.y = clampedOffset(view, requested)
        bottomOffsetPx = lp.y
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
            // View no longer attached.
        }
    }

    /** Reports vertical drags of the plate and forwards a tap to performClick() for accessibility. */
    private class DragListener(
        context: Context,
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
                    startOffset = (v.layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startRawY
                    if (!dragging && kotlin.math.abs(dy) > slop) dragging = true
                    // Finger up (negative dy) raises the plate: offset from the bottom grows.
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
        const val DEFAULT_BOTTOM_DP = 48
        const val DEBUG_SP = 11
        const val TOP_RESERVED_DP = 48
        const val MAX_LINES = 5
        const val MAX_LINES_BILINGUAL = 8
        const val SOURCE_LINE_SCALE = 0.8f

        /** Remembered drag position (px from bottom); null until the user drags. Main thread only. */
        var bottomOffsetPx: Int? = null
    }
}
