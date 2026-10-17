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
import android.widget.TextView
import com.voicecaption.caption.CaptionFormatter
import com.voicecaption.core.Caption
import com.voicecaption.core.CaptionSettings

/**
 * Floating captions: two plates, each in its own TYPE_APPLICATION_OVERLAY window. The translation
 * plate is the main one; with "show original text" a smaller original-language plate sits above it.
 *
 * Moving: each plate is dragged vertically on its own. Until the original plate has been dragged it
 * stays stacked just above the translation plate and follows it (also when the translation grows to
 * more lines); after that it keeps the position the user gave it. [resetPositions] puts both back.
 *
 * Threading: every public method may be called from any thread; work is marshalled to the main
 * thread (run inline when already there, otherwise posted to a main-looper [Handler]).
 *
 * Touches: the windows are NOT_FOCUSABLE | NOT_TOUCH_MODAL and wrap their plates, so touches outside
 * a plate reach the app underneath. The offsets (distance from the bottom edge) are kept in memory
 * for the process lifetime and survive hide()/show() and renderer re-creation. Persisting them to
 * storage is a later task.
 */
class OverlayRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density

    // Main-thread state below.
    private var sourcePlate: TextView? = null // original language, above
    private var plate: TextView? = null // translation, the main plate
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
        if (plate == null) createPlates()
        val translation = plate ?: return@onMain
        val original = sourcePlate ?: return@onMain
        translation.visibility = View.GONE
        original.visibility = View.GONE
        try {
            windowManager.addView(translation, layoutParams(translationOffsetPx ?: dp(DEFAULT_BOTTOM_DP)))
            windowManager.addView(original, layoutParams(originalOffsetPx ?: dp(DEFAULT_BOTTOM_DP)))
            attached = true
        } catch (_: RuntimeException) {
            // Permission revoked between check and add (SecurityException) or a bad window token
            // (BadTokenException): stay hidden rather than crash the service.
            removeBoth()
            attached = false
        }
        refreshGeometry()
    }

    /**
     * Shows the captions; nothing to show hides the plates but keeps the windows attached.
     * Replaces any message from [showMessage]. Opacity and font size apply on every call.
     */
    fun render(captions: List<Caption>, settings: CaptionSettings) = onMain {
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
        restack()
    }

    /** Shows a plain status/error message on the translation plate until the next [render] with captions. */
    fun showMessage(text: String) = onMain {
        val view = plate ?: return@onMain
        if (!attached) return@onMain
        refreshGeometry()
        sourcePlate?.visibility = View.GONE
        view.maxLines = MAX_LINES
        view.text = text
        view.visibility = View.VISIBLE
        applyStyle(view, null, alpha, lastSettings)
        restack()
    }

    /** Removes both windows. Safe to call repeatedly. */
    fun hide() = onMain {
        if (!attached) return@onMain
        attached = false
        removeBoth()
    }

    private fun removeBoth() {
        for (view in listOfNotNull(plate, sourcePlate)) {
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // View was already gone (e.g. window removed by the system); nothing to do.
            }
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /**
     * Re-derives everything that depends on the CURRENT screen size (it changes on rotation and
     * folding): the plates' max width, and each dragged vertical offset, re-clamped to the screen
     * height. Called before every render so a stale landscape width never leaks into portrait.
     */
    private fun refreshGeometry() {
        val maxWidth = OverlayMath.maxPlateWidthPx(appContext.resources.displayMetrics.widthPixels)
        plate?.maxWidth = maxWidth
        sourcePlate?.maxWidth = maxWidth
        if (!attached) return
        // Apply the remembered positions (or the defaults after a reset), clamped to this screen.
        // Clamping never overwrites the remembered value, so rotating back restores it.
        plate?.let { placeAt(it, translationOffsetPx ?: dp(DEFAULT_BOTTOM_DP)) }
        originalOffsetPx?.let { offset -> sourcePlate?.let { placeAt(it, offset) } }
    }

    /** Moves [view]'s window to [offsetPx] (clamped) without recording it as a user choice. */
    private fun placeAt(view: View, offsetPx: Int) {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val clamped = clampedOffset(view, offsetPx)
        if (clamped != lp.y) {
            lp.y = clamped
            updateLayout(view, lp)
        }
    }

    /**
     * Keeps the original plate directly above the translation plate while the user has not placed it
     * somewhere else. Cheap; called after every change that can alter the translation plate's size.
     */
    private fun restack() {
        if (!attached || originalOffsetPx != null) return
        val translation = plate ?: return
        val original = sourcePlate ?: return
        val tlp = translation.layoutParams as? WindowManager.LayoutParams ?: return
        val olp = original.layoutParams as? WindowManager.LayoutParams ?: return
        val height = if (translation.visibility == View.VISIBLE) translation.height else 0
        val wanted = clampedOffset(original, OverlayMath.stackedOffset(tlp.y, height, dp(PLATE_GAP_DP)))
        if (wanted != olp.y) {
            olp.y = wanted
            updateLayout(original, olp)
        }
    }

    private fun clampedOffset(view: View, requested: Int): Int {
        val screenH = appContext.resources.displayMetrics.heightPixels
        // Keep clear of the status bar area at the top; y=0 is already above the gesture/nav area
        // because overlay windows are laid out inside the system bars.
        val max = screenH - view.height - dp(TOP_RESERVED_DP)
        return OverlayMath.clampOffsetY(requested, 0, max)
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun createPlates() {
        val original = newPlate(isOriginal = true).apply { visibility = View.GONE }
        val translation = newPlate(isOriginal = false)
        // The translation plate changes size with its text; the original plate follows it.
        translation.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> restack() }
        sourcePlate = original
        plate = translation
    }

    private fun newPlate(isOriginal: Boolean): TextView {
        val view = TextView(appContext)
        view.setTextColor(Color.WHITE)
        view.gravity = Gravity.CENTER
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxLines = MAX_LINES
        view.setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.setOnTouchListener(DragListener(appContext) { requested -> moveTo(view, requested, isOriginal) })
        applyStyle(view, CaptionSettings().fontSizeSp.toFloat(), alpha, CaptionSettings())
        return view
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

    private fun layoutParams(offsetPx: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = offsetPx
    }

    /** [requested] is the new requested distance from the bottom edge, in px, for this plate only. */
    private fun moveTo(view: View, requested: Int, isOriginal: Boolean) {
        if (!attached) return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.y = clampedOffset(view, requested)
        if (isOriginal) originalOffsetPx = lp.y else translationOffsetPx = lp.y
        updateLayout(view, lp)
        if (!isOriginal) restack()
    }

    private fun updateLayout(view: View, lp: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: IllegalArgumentException) {
            // View no longer attached.
        }
    }

    /** Reports vertical drags of one plate and forwards a tap to performClick() for accessibility. */
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

    companion object {
        private const val PADDING_DP = 12
        private const val CORNER_DP = 8
        private const val PLATE_GAP_DP = 6
        private const val DEFAULT_BOTTOM_DP = 48
        private const val DEBUG_SP = 11
        private const val DEBUG_EXTRA_LINES = 4
        private const val TOP_RESERVED_DP = 48
        private const val MAX_LINES = 5
        private const val SOURCE_LINE_SCALE = 0.8f
        private const val DIMMED_TEXT_ALPHA = 217 // 85 %

        /** Remembered drag positions (px from bottom); null until the user drags that plate. Main thread only. */
        private var translationOffsetPx: Int? = null
        private var originalOffsetPx: Int? = null

        /**
         * Forgets both dragged positions: the translation plate returns to the default spot and the
         * original plate stacks above it again. Takes effect on the next caption.
         */
        fun resetPositions() {
            translationOffsetPx = null
            originalOffsetPx = null
        }
    }
}
