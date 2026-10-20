package com.starbattle.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * High-performance, transparent overlay view that renders animated glowing target reticles
 * on top of the detected Star Battle / Queens solution coordinates.
 *
 * Characteristics:
 * - 100% Touch Pass-Through: Rejects all touch events so the game underneath receives full input.
 * - Zero Idle CPU: When no targets are displayed, the animator is completely stopped.
 * - Glassmorphism / Cyberpunk Visuals: Renders multi-layered glowing neon emerald rings
 *   with pulsing radial waves and high-precision center reticles.
 */
class OverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Target solution points in screen pixel coordinates
    private val targets = ArrayList<PointF>()
    private var puzzleBounds: RectF? = null

    // Animation progress [0.0f, 1.0f]
    private var pulseFraction = 0f
    private var pulseAnimator: ValueAnimator? = null

    // Cached paints for zero allocation in onDraw()
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.parseColor("#00FF88")
    }

    private val glowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3300FF88")
    }

    private val coreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00FF88")
    }

    private val coreCenterWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.WHITE
    }

    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#6600E5FF")
    }

    private val density = resources.displayMetrics.density
    private val baseTargetRadius = 14f * density

    init {
        // Transparent background
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Absolute touch pass-through. Any touch directed at this view is rejected,
     * ensuring the system delivers it directly to the window below.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    /**
     * Set target coordinates and start the glowing animation loop.
     */
    fun setTargetCoordinates(points: List<PointF>, bounds: RectF? = null) {
        targets.clear()
        targets.addAll(points)
        puzzleBounds = bounds

        if (targets.isNotEmpty()) {
            startPulseAnimation()
        } else {
            stopPulseAnimation()
        }
        invalidate()
    }

    /**
     * Clears all targets and terminates animation to drop CPU usage to strictly 0.0%.
     */
    fun clearTargets() {
        targets.clear()
        puzzleBounds = null
        stopPulseAnimation()
        invalidate()
    }

    private fun startPulseAnimation() {
        if (pulseAnimator != null && pulseAnimator!!.isRunning) return

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseFraction = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFraction = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (targets.isEmpty()) return

        // 1. Draw subtle board boundary highlight if available
        puzzleBounds?.let { rect ->
            canvas.drawRoundRect(rect, 8f * density, 8f * density, gridBorderPaint)
        }

        // 2. Draw animated targets
        // Dynamic radii and alphas computed from pulseFraction
        val outerRadius = baseTargetRadius + (10f * density * pulseFraction)
        val outerAlpha = (180 - (80 * pulseFraction)).toInt().coerceIn(30, 255)
        val fillAlpha = (60 - (30 * pulseFraction)).toInt().coerceIn(10, 100)

        glowOuterPaint.alpha = outerAlpha
        glowFillPaint.alpha = fillAlpha

        val coreRadius = 6f * density
        val centerDotRadius = 2.5f * density
        val reticleCrossLength = 5f * density

        for (i in targets.indices) {
            val pt = targets[i]
            val x = pt.x
            val y = pt.y

            // Soft outer pulsing aura fill
            canvas.drawCircle(x, y, outerRadius, glowFillPaint)

            // Outer crisp glowing ring
            canvas.drawCircle(x, y, outerRadius, glowOuterPaint)

            // Mid ring
            val midRadius = baseTargetRadius * 0.75f
            glowOuterPaint.alpha = (outerAlpha * 0.7f).toInt()
            canvas.drawCircle(x, y, midRadius, glowOuterPaint)

            // Solid neon green core dot
            canvas.drawCircle(x, y, coreRadius, coreDotPaint)

            // Precision bright white center
            canvas.drawCircle(x, y, centerDotRadius, coreCenterWhitePaint)

            // Crosshair tick marks for tactical gaming HUD feel
            canvas.drawLine(x - outerRadius, y, x - outerRadius + reticleCrossLength, y, reticlePaint)
            canvas.drawLine(x + outerRadius - reticleCrossLength, y, x + outerRadius, y, reticlePaint)
            canvas.drawLine(x, y - outerRadius, x, y - outerRadius + reticleCrossLength, reticlePaint)
            canvas.drawLine(x, y + outerRadius - reticleCrossLength, x, y + outerRadius, reticlePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulseAnimation()
    }
}
