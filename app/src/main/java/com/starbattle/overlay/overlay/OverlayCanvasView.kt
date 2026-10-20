package com.starbattle.overlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.starbattle.overlay.solver.Cell
import kotlin.math.min

/**
 * Geometry of one solved board, expressed in the pixel space of the captured screen frame.

 * [gridLeft] / [gridTop] are the top-left corner of the board, [cellWidth] / [cellHeight] the size
 * of one cell and [frameWidth] / [frameHeight] the size of the frame the values were measured on
 * (which is the full display, because MediaProjection mirrors the display).
 */
data class OverlayTargets(
    val cells: List<Cell>,
    val gridLeft: Float,
    val gridTop: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val frameWidth: Int,
    val frameHeight: Int
)

/**
 * Full-screen, fully transparent, touch-transparent canvas that paints a green ring on every star
 * of the solution.

 * Design constraints honoured here:

 *  * **Zero idle CPU.** There is no animation, no Choreographer callback, no timer and no
 *    `invalidate()` loop. `onDraw` runs only when the window is created or when new targets are
 *    pushed, then the surface stays untouched. `onDraw` allocates nothing.
 *  * **Touch pass-through.** The window is added with `FLAG_NOT_TOUCHABLE`, which removes it from
 *    input dispatch entirely, so every tap, drag and gesture lands on the game underneath. The
 *    view is also not clickable/focusable and installs no touch listener.
 *  * **Overlay window.** `TYPE_APPLICATION_OVERLAY` (API 26+), `FLAG_NOT_FOCUSABLE` (never steals
 *    key focus / IME) and `FLAG_LAYOUT_IN_SCREEN` (laid out over the whole display, so the captured
 *    frame coordinates map 1:1 onto the view).

 * Coordinates are stored normalised (0..1 of the captured frame) so that layout changes - e.g. the
 * window being re-measured after a rotation - re-project the rings without any extra computation.
 */
class OverlayCanvasView(context: Context) : View(context) {

    companion object {
        private const val TAG = "StarBattleOverlay"

        /** Ring colour: vivid green, opaque, high contrast on every pastel puzzle palette. */
        private const val RING_COLOR = 0xFF00E676.toInt()

        /** Soft halo behind the ring - drawn as one extra circle, still a single draw pass. */
        private const val GLOW_COLOR = 0x3300E676

        /** Bright centre dot so the exact cell centre is unambiguous. */
        private const val DOT_COLOR = 0xFFB9F6CA.toInt()

        /** Ring radius as a fraction of the smaller cell edge. */
        private const val RADIUS_FRACTION = 0.30f
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val density = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GLOW_COLOR
        isDither = true
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = RING_COLOR
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DOT_COLOR
    }

    private var targets: OverlayTargets? = null

    private var normalizedCenters = FloatArray(0)

    private var radiusFractionX = 0f
    private var radiusFractionY = 0f

    private var attached = false

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        // A plain background would defeat the point of a transparent overlay.
        setBackgroundColor(Color.TRANSPARENT)
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /**
     * Replaces the stars that should be drawn. Safe to call from any thread.
     */
    fun setTargets(newTargets: OverlayTargets) {
        runOnMain {
            targets = newTargets
            rebuildGeometry()
            invalidate()
        }
    }

    /**
     * Adds this view to the [WindowManager] as a non-touchable application overlay.
     * No-op (and a log line) when `SYSTEM_ALERT_WINDOW` has not been granted.
     */
    fun show() {
        runOnMain {
            if (attached) return@runOnMain
            if (!Settings.canDrawOverlays(context)) {
                Log.e(TAG, "SYSTEM_ALERT_WINDOW is not granted - cannot show the overlay")
                return@runOnMain
            }
            val params = buildLayoutParams()
            try {
                windowManager.addView(this, params)
                attached = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to attach the overlay window", t)
            }
        }
    }

    /**
     * Removes the overlay window again. Safe to call multiple times.
     */
    fun hide() {
        runOnMain {
            if (!attached) return@runOnMain
            try {
                windowManager.removeViewImmediate(this)
            } catch (t: Throwable) {
                Log.w(TAG, "Overlay window was already detached", t)
            } finally {
                attached = false
            }
        }
    }

    /**
     * `true` while the overlay window is attached to the [WindowManager].
     */
    val isShowing: Boolean
        get() = attached

    // ---------------------------------------------------------------------------------------
    // View
    // ---------------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Nothing to recompute: centres are normalised, they are multiplied by the current size
        // inside onDraw. The surface is redrawn by the framework anyway after a size change.
    }

    override fun onDraw(canvas: Canvas) {
        val flat = normalizedCenters
        if (flat.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val radius = min(radiusFractionX * viewWidth, radiusFractionY * viewHeight)
        if (radius <= 0f) return

        val glowRadius = radius * 1.18f
        val dotRadius = radius * 0.22f

        var i = 0
        while (i < flat.size) {
            val cx = flat[i] * viewWidth
            val cy = flat[i + 1] * viewHeight
            canvas.drawCircle(cx, cy, glowRadius, glowPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            i += 2
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun rebuildGeometry() {
        val spec = targets
        if (spec == null || spec.cells.isEmpty() || spec.frameWidth <= 0 || spec.frameHeight <= 0) {
            normalizedCenters = FloatArray(0)
            radiusFractionX = 0f
            radiusFractionY = 0f
            return
        }

        val frameWidth = spec.frameWidth.toFloat()
        val frameHeight = spec.frameHeight.toFloat()
        val flat = FloatArray(spec.cells.size * 2)
        var index = 0
        for (cell in spec.cells) {
            val centreX = spec.gridLeft + (cell.col + 0.5f) * spec.cellWidth
            val centreY = spec.gridTop + (cell.row + 0.5f) * spec.cellHeight
            flat[index++] = (centreX / frameWidth).coerceIn(0f, 1f)
            flat[index++] = (centreY / frameHeight).coerceIn(0f, 1f)
        }
        normalizedCenters = flat

        val radius = min(spec.cellWidth, spec.cellHeight) * RADIUS_FRACTION
        radiusFractionX = radius / frameWidth
        radiusFractionY = radius / frameHeight
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        params.alpha = 1f
        params.title = "StarBattleOverlay"
        // No entry animation: the rings are static, animating the window would cost a frame.
        params.windowAnimations = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Draw inside the cutout as well, otherwise the top rows can be offset.
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        return params
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
