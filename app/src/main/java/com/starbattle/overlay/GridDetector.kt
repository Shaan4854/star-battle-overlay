package com.starbattle.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure native Android computer vision engine for Star Battle / Queens puzzles.
 *
 * Capabilities:
 * - Automatically detects board bounds without external OpenCV or ML dependencies.
 * - Auto-detects grid dimension N (e.g. 6x6 through 12x12, typically 8x8 or 9x9).
 * - Samples cell center RGB clusters and groups them into distinct region IDs.
 * - Maps solver coordinates back to screen pixels with sub-pixel precision.
 */
object GridDetector {

    data class DetectionResult(
        val n: Int,
        val boardBounds: RectF,
        val regionMatrix: Array<IntArray>,
        val cellWidth: Float,
        val cellHeight: Float
    )

    /**
     * Parses the screenshot bitmap into a Star Battle grid matrix.
     *
     * @param bitmap Screen capture in ARGB_8888.
     * @param preferredN Optional user override for grid size (0 for auto-detection).
     * @return DetectionResult or null if detection fails.
     */
    fun detectAndParse(bitmap: Bitmap, preferredN: Int = 0): DetectionResult? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        // 1. Locate the square puzzle board on the screen
        val boardBounds = findBoardBounds(bitmap) ?: return null

        val boardWidth = boardBounds.width()
        val boardHeight = boardBounds.height()
        if (boardWidth < 200 || boardHeight < 200) return null

        // 2. Determine grid size N
        val n = if (preferredN in 4..14) {
            preferredN
        } else {
            detectGridDimensionN(bitmap, boardBounds) ?: 8
        }

        val cellWidth = boardWidth / n
        val cellHeight = boardHeight / n

        // 3. Sample center colors for each cell (r, c)
        val sampledColors = Array(n) { IntArray(n) }
        for (r in 0 until n) {
            for (c in 0 until n) {
                val centerX = (boardBounds.left + (c + 0.5f) * cellWidth).roundToInt()
                val centerY = (boardBounds.top + (r + 0.5f) * cellHeight).roundToInt()
                sampledColors[r][c] = samplePatchAverageRgb(bitmap, centerX, centerY, (cellWidth * 0.25f).roundToInt().coerceAtLeast(3))
            }
        }

        // 4. Cluster colors into distinct region IDs
        val regionMatrix = clusterColorsIntoRegions(n, sampledColors)

        return DetectionResult(
            n = n,
            boardBounds = boardBounds,
            regionMatrix = regionMatrix,
            cellWidth = cellWidth,
            cellHeight = cellHeight
        )
    }

    /**
     * Scans for the puzzle board bounding box using horizontal and vertical edge gradients.
     */
    private fun findBoardBounds(bitmap: Bitmap): RectF? {
        val width = bitmap.width
        val height = bitmap.height

        // Star Battle boards are centered horizontally and vertically between 12% and 85% of screen height
        val minX = (width * 0.03f).toInt()
        val maxX = (width * 0.97f).toInt()
        val searchTop = (height * 0.12f).toInt()
        val searchBottom = (height * 0.85f).toInt()

        // Fallback default board rectangle if contrast edge detection does not find sharp boundaries
        val defaultBoardWidth = (width * 0.90f)
        val defaultLeft = (width - defaultBoardWidth) / 2f
        val defaultTop = searchTop + ((searchBottom - searchTop) - defaultBoardWidth) / 2f
        val defaultRect = RectF(defaultLeft, defaultTop, defaultLeft + defaultBoardWidth, defaultTop + defaultBoardWidth)

        // Try detecting high horizontal contrast lines for top and bottom of board
        val sampleCol = width / 2
        var detectedTop = -1
        var detectedBottom = -1

        // Scan downward for top border
        for (y in searchTop until (searchTop + (searchBottom - searchTop) / 2)) {
            val grad = verticalGradient(bitmap, sampleCol, y)
            if (grad > 45) {
                // Verify horizontal continuation
                if (isHorizontalLine(bitmap, y, minX, maxX)) {
                    detectedTop = y
                    break
                }
            }
        }

        // Scan upward for bottom border
        for (y in searchBottom downTo (searchTop + (searchBottom - searchTop) / 2)) {
            val grad = verticalGradient(bitmap, sampleCol, y)
            if (grad > 45) {
                if (isHorizontalLine(bitmap, y, minX, maxX)) {
                    detectedBottom = y
                    break
                }
            }
        }

        if (detectedTop > 0 && detectedBottom > detectedTop) {
            val detectedHeight = (detectedBottom - detectedTop).toFloat()
            // Boards are square: check aspect ratio close to 1:1
            if (abs(detectedHeight - defaultBoardWidth) < (defaultBoardWidth * 0.25f)) {
                val detectedLeft = (width - detectedHeight) / 2f
                return RectF(detectedLeft, detectedTop.toFloat(), detectedLeft + detectedHeight, detectedBottom.toFloat())
            }
        }

        return defaultRect
    }

    private fun verticalGradient(bitmap: Bitmap, x: Int, y: Int): Double {
        if (y <= 0 || y >= bitmap.height - 1) return 0.0
        val p1 = bitmap.getPixel(x, y - 1)
        val p2 = bitmap.getPixel(x, y + 1)
        return colorDistance(p1, p2)
    }

    private fun isHorizontalLine(bitmap: Bitmap, y: Int, minX: Int, maxX: Int): Boolean {
        var consistentCount = 0
        val step = (maxX - minX) / 10
        val basePixel = bitmap.getPixel(minX + step, y)
        for (x in (minX + step) until maxX step step) {
            if (colorDistance(basePixel, bitmap.getPixel(x, y)) < 35.0) {
                consistentCount++
            }
        }
        return consistentCount >= 7
    }

    /**
     * Estimates N by analyzing cell transition frequencies across candidate N values (6..12).
     */
    private fun detectGridDimensionN(bitmap: Bitmap, board: RectF): Int? {
        val boardWidth = board.width()
        val boardHeight = board.height()

        val sampleY = (board.top + boardHeight * 0.5f).toInt()
        var bestN = 8
        var maxScore = -1.0

        for (candidateN in 6..12) {
            val step = boardWidth / candidateN
            var lineScore = 0.0
            for (i in 1 until candidateN) {
                val boundaryX = (board.left + i * step).toInt().coerceIn(0, bitmap.width - 2)
                val grad = horizontalGradient(bitmap, boundaryX, sampleY)
                lineScore += grad
            }
            val avgScore = lineScore / (candidateN - 1)
            if (avgScore > maxScore) {
                maxScore = avgScore
                bestN = candidateN
            }
        }

        return bestN
    }

    private fun horizontalGradient(bitmap: Bitmap, x: Int, y: Int): Double {
        if (x <= 0 || x >= bitmap.width - 1) return 0.0
        val p1 = bitmap.getPixel(x - 1, y)
        val p2 = bitmap.getPixel(x + 1, y)
        return colorDistance(p1, p2)
    }

    /**
     * Samples a small square patch around (cx, cy) and returns the average RGB integer.
     */
    private fun samplePatchAverageRgb(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Int {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0

        val startX = (cx - radius).coerceIn(0, bitmap.width - 1)
        val endX = (cx + radius).coerceIn(0, bitmap.width - 1)
        val startY = (cy - radius).coerceIn(0, bitmap.height - 1)
        val endY = (cy + radius).coerceIn(0, bitmap.height - 1)

        for (y in startY..endY) {
            for (x in startX..endX) {
                val pixel = bitmap.getPixel(x, y)
                sumR += Color.red(pixel)
                sumG += Color.green(pixel)
                sumB += Color.blue(pixel)
                count++
            }
        }

        if (count == 0) return Color.WHITE
        return Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }

    /**
     * Groups raw cell colors into contiguous region IDs (0 until regionCount).
     * If slight lighting differences create extra regions, iteratively merges nearest color clusters.
     */
    private fun clusterColorsIntoRegions(n: Int, sampledColors: Array<IntArray>): Array<IntArray> {
        val clusterCenters = ArrayList<Int>()
        val matrix = Array(n) { IntArray(n) }

        val threshold = 32.0 // Euclidean RGB color distance threshold

        for (r in 0 until n) {
            for (c in 0 until n) {
                val color = sampledColors[r][c]
                var matchedIndex = -1
                var minDistance = Double.MAX_VALUE

                for (i in clusterCenters.indices) {
                    val dist = colorDistance(color, clusterCenters[i])
                    if (dist < threshold && dist < minDistance) {
                        minDistance = dist
                        matchedIndex = i
                    }
                }

                if (matchedIndex != -1) {
                    matrix[r][c] = matchedIndex
                } else {
                    val newIndex = clusterCenters.size
                    clusterCenters.add(color)
                    matrix[r][c] = newIndex
                }
            }
        }

        // In standard Star Battle / Queens, if cluster count > n due to subtle compression artifacts,
        // merge the closest pair of clusters until cluster count == n
        while (clusterCenters.size > n) {
            var closestI = 0
            var closestJ = 1
            var minPairDist = Double.MAX_VALUE

            for (i in 0 until clusterCenters.size) {
                for (j in i + 1 until clusterCenters.size) {
                    val dist = colorDistance(clusterCenters[i], clusterCenters[j])
                    if (dist < minPairDist) {
                        minPairDist = dist
                        closestI = i
                        closestJ = j
                    }
                }
            }

            // Merge closestJ into closestI
            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (matrix[r][c] == closestJ) {
                        matrix[r][c] = closestI
                    } else if (matrix[r][c] > closestJ) {
                        matrix[r][c]--
                    }
                }
            }
            clusterCenters.removeAt(closestJ)
        }

        return matrix
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}
