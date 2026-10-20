package com.starbattle.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Robust Native Android Computer Vision engine for Star Battle / LinkedIn Queens.
 * Works with both live MediaProjection frames and gallery screenshot images.
 */
object GridDetector {

    data class DetectionResult(
        val n: Int,
        val boardBounds: RectF,
        val regionMatrix: Array<IntArray>,
        val cellWidth: Float,
        val cellHeight: Float,
        val sampledColors: List<Int>
    )

    fun detectAndParse(bitmap: Bitmap, preferredN: Int = 0): DetectionResult? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        // 1. Locate the square puzzle board on the screen
        val boardBounds = findBoardBounds(bitmap)

        val boardWidth = boardBounds.width()
        val boardHeight = boardBounds.height()
        if (boardWidth < 150 || boardHeight < 150) return null

        // 2. Determine grid size N
        val n = if (preferredN in 4..12) {
            preferredN
        } else {
            detectGridDimensionN(bitmap, boardBounds) ?: 8
        }

        val cellWidth = boardWidth / n
        val cellHeight = boardHeight / n

        // 3. Sample center median colors for each cell (r, c)
        val sampledColors = Array(n) { IntArray(n) }
        val patchRadius = (cellWidth * 0.18f).roundToInt().coerceIn(3, 12)

        for (r in 0 until n) {
            for (c in 0 until n) {
                val cx = (boardBounds.left + (c + 0.5f) * cellWidth).roundToInt().coerceIn(0, width - 1)
                val cy = (boardBounds.top + (r + 0.5f) * cellHeight).roundToInt().coerceIn(0, height - 1)
                sampledColors[r][c] = samplePatchMedianRgb(bitmap, cx, cy, patchRadius)
            }
        }

        // 4. Cluster colors into exactly N distinct region IDs
        val (regionMatrix, paletteList) = clusterColorsIntoRegions(n, sampledColors)

        return DetectionResult(
            n = n,
            boardBounds = boardBounds,
            regionMatrix = regionMatrix,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            sampledColors = paletteList
        )
    }

    private fun findBoardBounds(bitmap: Bitmap): RectF {
        val width = bitmap.width
        val height = bitmap.height

        // Most LinkedIn Queens boards occupy 85% to 92% of screen width, centered horizontally
        val defaultBoardWidth = width * 0.90f
        val defaultLeft = (width - defaultBoardWidth) / 2f
        val defaultTop = (height - defaultBoardWidth) * 0.44f
        val defaultRect = RectF(defaultLeft, defaultTop, defaultLeft + defaultBoardWidth, defaultTop + defaultBoardWidth)

        // Scan vertically along the center column for color changes
        val sampleX = width / 2
        val scanMinY = (height * 0.15f).toInt()
        val scanMaxY = (height * 0.85f).toInt()

        var detectedTop = -1
        var detectedBottom = -1

        for (y in scanMinY until (scanMaxY - defaultBoardWidth.toInt())) {
            val grad = verticalGradient(bitmap, sampleX, y)
            if (grad > 32.0) {
                detectedTop = y
                break
            }
        }

        for (y in scanMaxY downTo (scanMinY + defaultBoardWidth.toInt())) {
            val grad = verticalGradient(bitmap, sampleX, y)
            if (grad > 32.0) {
                detectedBottom = y
                break
            }
        }

        if (detectedTop > 0 && detectedBottom > detectedTop) {
            val detectedHeight = (detectedBottom - detectedTop).toFloat()
            if (abs(detectedHeight - defaultBoardWidth) < (defaultBoardWidth * 0.22f)) {
                val detectedLeft = (width - detectedHeight) / 2f
                return RectF(detectedLeft, detectedTop.toFloat(), detectedLeft + detectedHeight, detectedBottom.toFloat())
            }
        }

        return defaultRect
    }

    private fun verticalGradient(bitmap: Bitmap, x: Int, y: Int): Double {
        if (y <= 1 || y >= bitmap.height - 2) return 0.0
        val p1 = bitmap.getPixel(x, y - 2)
        val p2 = bitmap.getPixel(x, y + 2)
        return colorDistance(p1, p2)
    }

    private fun detectGridDimensionN(bitmap: Bitmap, board: RectF): Int? {
        val boardWidth = board.width()
        val boardHeight = board.height()
        val sampleY = (board.top + boardHeight * 0.5f).toInt().coerceIn(0, bitmap.height - 1)

        var bestN = 8
        var maxScore = -1.0

        for (candidateN in listOf(8, 7, 9, 10, 6, 11, 12)) {
            val step = boardWidth / candidateN
            var lineScore = 0.0
            for (i in 1 until candidateN) {
                val bx = (board.left + i * step).toInt().coerceIn(1, bitmap.width - 2)
                val p1 = bitmap.getPixel(bx - 1, sampleY)
                val p2 = bitmap.getPixel(bx + 1, sampleY)
                lineScore += colorDistance(p1, p2)
            }
            val avg = lineScore / (candidateN - 1)
            if (avg > maxScore) {
                maxScore = avg
                bestN = candidateN
            }
        }

        return bestN
    }

    private fun samplePatchMedianRgb(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Int {
        val rList = ArrayList<Int>()
        val gList = ArrayList<Int>()
        val bList = ArrayList<Int>()

        val minX = (cx - radius).coerceAtLeast(0)
        val maxX = (cx + radius).coerceAtMost(bitmap.width - 1)
        val minY = (cy - radius).coerceAtLeast(0)
        val maxY = (cy + radius).coerceAtMost(bitmap.height - 1)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val p = bitmap.getPixel(x, y)
                rList.add(Color.red(p))
                gList.add(Color.green(p))
                bList.add(Color.blue(p))
            }
        }

        if (rList.isEmpty()) return Color.WHITE

        rList.sort()
        gList.sort()
        bList.sort()

        val mid = rList.size / 2
        return Color.rgb(rList[mid], gList[mid], bList[mid])
    }

    private fun clusterColorsIntoRegions(n: Int, sampledColors: Array<IntArray>): Pair<Array<IntArray>, List<Int>> {
        val clusterCenters = ArrayList<Int>()
        val matrix = Array(n) { IntArray(n) }
        val threshold = 35.0

        for (r in 0 until n) {
            for (c in 0 until n) {
                val color = sampledColors[r][c]
                var matched = -1
                var minDist = Double.MAX_VALUE

                for (i in clusterCenters.indices) {
                    val dist = colorDistance(color, clusterCenters[i])
                    if (dist < threshold && dist < minDist) {
                        minDist = dist
                        matched = i
                    }
                }

                if (matched != -1) {
                    matrix[r][c] = matched
                } else {
                    val newIdx = clusterCenters.size
                    clusterCenters.add(color)
                    matrix[r][c] = newIdx
                }
            }
        }

        // Merge closest clusters until clusterCenters.size <= n
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

        return Pair(matrix, clusterCenters)
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}
