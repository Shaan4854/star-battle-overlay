package com.starbattle.overlay

import kotlin.math.abs

/**
 * High-performance, zero-allocation-in-loop Constraint Satisfaction Problem (CSP) solver
 * for Star Battle / Queens puzzles.
 *
 * Rules:
 * 1. Exactly 1 star per row.
 * 2. Exactly 1 star per column.
 * 3. Exactly 1 star per color region.
 * 4. Stars cannot touch each other orthogonally or diagonally (all 8 surrounding cells forbidden).
 */
object StarBattleSolver {

    /**
     * Solves the Star Battle puzzle using pure recursive backtracking CSP.
     *
     * @param n The grid dimension (N x N).
     * @param regions NxN matrix of integer region identifiers.
     * @return List of exact (row, col) coordinates of placed stars, or null if no solution exists.
     */
    fun solve(n: Int, regions: Array<IntArray>): List<Pair<Int, Int>>? {
        if (n <= 0 || regions.size != n) return null
        for (row in regions) {
            if (row.size != n) return null
        }

        // Map arbitrary region IDs (e.g. RGB color values or sparse IDs) to contiguous 0..regionCount-1
        val regionIdMap = HashMap<Int, Int>(n * 2)
        var nextId = 0
        val normalizedRegions = Array(n) { r ->
            IntArray(n) { c ->
                val rawId = regions[r][c]
                regionIdMap.getOrPut(rawId) { nextId++ }
            }
        }

        val regionCount = regionIdMap.size
        // In standard 1-star Queens, if regionCount < n, we cannot have 1 queen per region
        if (regionCount < n) {
            // Fallback: If vision grouped two distinct regions into one, still enforce no two queens in same region
            // but allow solving if valid
        }

        // Fast primitive boolean arrays for O(1) state checks
        val usedCols = BooleanArray(n)
        val usedRegions = BooleanArray(regionCount)
        val solutionCols = IntArray(n)

        /**
         * Pure recursive backtracking algorithm.
         * Row by row placement naturally satisfies the 1-star-per-row constraint.
         */
        fun backtrack(row: Int): Boolean {
            if (row == n) {
                return true
            }

            val regionRow = normalizedRegions[row]
            val prevCol = if (row > 0) solutionCols[row - 1] else -99

            for (col in 0 until n) {
                // 1. Column constraint
                if (usedCols[col]) continue

                // 2. Adjacency constraint: cannot touch orthogonally or diagonally.
                // Since rows are placed sequentially, any item on row-1 within distance <= 1 is forbidden.
                // Items on row-2 or higher cannot touch vertically (distance >= 2).
                if (row > 0 && abs(prevCol - col) <= 1) continue

                // 3. Region constraint
                val region = regionRow[col]
                if (usedRegions[region]) continue

                // Place star
                usedCols[col] = true
                usedRegions[region] = true
                solutionCols[row] = col

                // Recurse to next row
                if (backtrack(row + 1)) {
                    return true
                }

                // Backtrack (undo move)
                usedCols[col] = false
                usedRegions[region] = false
            }

            return false
        }

        val solved = backtrack(0)
        if (!solved) return null

        val result = ArrayList<Pair<Int, Int>>(n)
        for (r in 0 until n) {
            result.add(Pair(r, solutionCols[r]))
        }
        return result
    }

    /**
     * Optional generalized solver for K-star Star Battle puzzles (e.g., 2 stars per row/col/region).
     */
    fun solveKStar(n: Int, regions: Array<IntArray>, starsPerUnit: Int = 1): List<Pair<Int, Int>>? {
        if (starsPerUnit == 1) return solve(n, regions)
        if (n <= 0 || regions.size != n) return null

        val regionIdMap = HashMap<Int, Int>(n * 2)
        var nextId = 0
        val normalizedRegions = Array(n) { r ->
            IntArray(n) { c ->
                regionIdMap.getOrPut(regions[r][c]) { nextId++ }
            }
        }
        val regionCount = regionIdMap.size
        val colCounts = IntArray(n)
        val rowCounts = IntArray(n)
        val regionCounts = IntArray(regionCount)
        val placedStars = mutableListOf<Pair<Int, Int>>()

        fun isAdjacentToAny(r: Int, c: Int): Boolean {
            for (p in placedStars) {
                if (abs(p.first - r) <= 1 && abs(p.second - c) <= 1) {
                    return true
                }
            }
            return false
        }

        fun backtrack(cellIndex: Int): Boolean {
            val totalStarsNeeded = n * starsPerUnit
            if (placedStars.size == totalStarsNeeded) {
                // Verify all counts are exactly starsPerUnit
                for (count in rowCounts) if (count != starsPerUnit) return false
                for (count in colCounts) if (count != starsPerUnit) return false
                for (count in regionCounts) if (count != starsPerUnit) return false
                return true
            }

            if (cellIndex >= n * n) return false

            val r = cellIndex / n
            val c = cellIndex % n
            val region = normalizedRegions[r][c]

            // Remaining cells in this row check:
            val remainingInRow = n - c
            val neededInRow = starsPerUnit - rowCounts[r]
            if (remainingInRow < neededInRow) return false

            // Try placing star at (r, c)
            if (rowCounts[r] < starsPerUnit &&
                colCounts[c] < starsPerUnit &&
                regionCounts[region] < starsPerUnit &&
                !isAdjacentToAny(r, c)
            ) {
                rowCounts[r]++
                colCounts[c]++
                regionCounts[region]++
                placedStars.add(Pair(r, c))

                if (backtrack(cellIndex + 1)) return true

                placedStars.removeAt(placedStars.size - 1)
                rowCounts[r]--
                colCounts[c]--
                regionCounts[region]--
            }

            // Try skipping (r, c)
            return backtrack(cellIndex + 1)
        }

        val success = backtrack(0)
        return if (success) placedStars else null
    }
}
