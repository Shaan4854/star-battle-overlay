package com.starbattle.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Interactive custom View for Star Battle / LinkedIn Queens grids.
 *
 * Supports two display modes:
 * - STARTING_LAYOUT: Displays color regions, allows tap and drag to paint cells.
 * - SOLVED_LAYOUT: Displays color regions PLUS gold crowns on solved queen coordinates.
 */
class InteractiveGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        STARTING_LAYOUT,
        SOLVED_LAYOUT
    }

    var mode: Mode = Mode.STARTING_LAYOUT
        set(value) {
            field = value
            invalidate()
        }

    var n: Int = 8
        set(value) {
            field = value.coerceIn(4, 12)
            initGrid()
            invalidate()
        }

    // Grid data: n x n matrix of color IDs (-1 for unassigned)
    var grid: Array<IntArray> = Array(8) { IntArray(8) { -1 } }
        private set

    // Solved queen positions: set of "row,col"
    private val queenPositions = HashSet<String>()

    // Color palette mapping (Color ID -> Color int)
    val palette = intArrayOf(
        Color.parseColor("#F59E0B"), // 0: Amber
        Color.parseColor("#38BDF8"), // 1: Sky
        Color.parseColor("#34D399"), // 2: Emerald
        Color.parseColor("#FB7185"), // 3: Rose
        Color.parseColor("#A78BFA"), // 4: Violet
        Color.parseColor("#FB923C"), // 5: Orange
        Color.parseColor("#A3E635"), // 6: Lime
        Color.parseColor("#E879F9"), // 7: Fuchsia
        Color.parseColor("#2DD4BF"), // 8: Teal
        Color.parseColor("#818CF8"), // 9: Indigo
        Color.parseColor("#FACC15"), // 10: Yellow
        Color.parseColor("#22D3EE"), // 11: Cyan
        Color.parseColor("#F472B6"), // 12: Pink
        Color.parseColor("#4ADE80")  // 13: Mint
    )

    var activeColorId: Int = 0
    var onCellChangedListener: ((row: Int, col: Int, colorId: Int) -> Unit)? = null

    // Paints
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thinBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.parseColor("#20FFFFFF")
    }
    private val thickBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.parseColor("#0A0D14")
    }
    private val crownHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9000000")
    }
    private val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#FFD700")
        isFakeBoldText = true
    }
    private val emptyCellDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4094A3B8")
    }

    private val density = resources.displayMetrics.density
    private var cellSize = 0f
    private var boardLeft = 0f
    private var boardTop = 0f

    init {
        initGrid()
    }

    private fun initGrid() {
        grid = Array(n) { IntArray(n) { -1 } }
        queenPositions.clear()
    }

    fun setGridData(newN: Int, newGrid: Array<IntArray>) {
        n = newN
        grid = Array(newN) { r ->
            IntArray(newN) { c ->
                if (r < newGrid.size && c < newGrid[r].size) newGrid[r][c] else -1
            }
        }
        queenPositions.clear()
        invalidate()
    }

    fun setSolution(solution: List<Pair<Int, Int>>?) {
        queenPositions.clear()
        solution?.forEach { (r, c) ->
            queenPositions.add("$r,$c")
        }
        invalidate()
    }

    fun clearGrid() {
        for (r in 0 until n) {
            for (c in 0 until n) {
                grid[r][c] = -1
            }
        }
        queenPositions.clear()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(width, if (height > 0) height else width)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boardSize = min(w, h) - (16f * density)
        cellSize = boardSize / n
        boardLeft = (w - boardSize) / 2f
        boardTop = (h - boardSize) / 2f
        crownPaint.textSize = cellSize * 0.52f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != Mode.STARTING_LAYOUT) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val col = ((event.x - boardLeft) / cellSize).toInt()
                val row = ((event.y - boardTop) / cellSize).toInt()

                if (row in 0 until n && col in 0 until n) {
                    if (grid[row][col] != activeColorId) {
                        grid[row][col] = activeColorId
                        onCellChangedListener?.invoke(row, col, activeColorId)
                        invalidate()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0) return

        // 1. Draw Cells and Backgrounds
        for (r in 0 until n) {
            for (c in 0 until n) {
                val left = boardLeft + c * cellSize
                val top = boardTop + r * cellSize
                val right = left + cellSize
                val bottom = top + cellSize
                val colorId = grid[r][c]

                if (colorId >= 0) {
                    val color = palette[colorId % palette.size]
                    cellPaint.color = color
                    canvas.drawRect(left, top, right, bottom, cellPaint)
                } else {
                    cellPaint.color = Color.parseColor("#151A28")
                    canvas.drawRect(left, top, right, bottom, cellPaint)
                    canvas.drawCircle(left + cellSize / 2f, top + cellSize / 2f, 2.5f * density, emptyCellDotPaint)
                }

                // Internal thin border
                canvas.drawRect(left, top, right, bottom, thinBorderPaint)
            }
        }

        // 2. Draw Thick Region Boundaries (between different color regions, authentic to LinkedIn Queens)
        for (r in 0 until n) {
            for (c in 0 until n) {
                val left = boardLeft + c * cellSize
                val top = boardTop + r * cellSize
                val right = left + cellSize
                val bottom = top + cellSize
                val colorId = grid[r][c]

                // Top border
                if (r == 0 || grid[r - 1][c] != colorId) {
                    canvas.drawLine(left, top, right, top, thickBorderPaint)
                }
                // Bottom border
                if (r == n - 1 || grid[r + 1][c] != colorId) {
                    canvas.drawLine(left, bottom, right, bottom, thickBorderPaint)
                }
                // Left border
                if (c == 0 || grid[r][c - 1] != colorId) {
                    canvas.drawLine(left, top, left, bottom, thickBorderPaint)
                }
                // Right border
                if (c == n - 1 || grid[r][c + 1] != colorId) {
                    canvas.drawLine(right, top, right, bottom, thickBorderPaint)
                }
            }
        }

        // 3. Draw Queens on Solved Layout Mode
        if (mode == Mode.SOLVED_LAYOUT) {
            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (queenPositions.contains("$r,$c")) {
                        val cx = boardLeft + (c + 0.5f) * cellSize
                        val cy = boardTop + (r + 0.5f) * cellSize

                        // Dark glowing circular backing
                        canvas.drawCircle(cx, cy, cellSize * 0.38f, crownHaloPaint)

                        // Crown emoji / icon
                        val textY = cy - ((crownPaint.descent() + crownPaint.ascent()) / 2)
                        canvas.drawText("👑", cx, textY, crownPaint)
                    }
                }
            }
        }
    }
}
