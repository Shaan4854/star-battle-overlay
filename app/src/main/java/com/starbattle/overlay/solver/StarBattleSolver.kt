package com.starbattle.overlay.solver

import kotlin.math.abs

/**
 * A single coordinate on the puzzle board.
 */
data class Cell(val row: Int, val col: Int)

/**
 * Exact solver for the standard N x N Star Battle puzzle (one star per row, per column and per
 * coloured region, with stars never touching - not even diagonally).
 *
 * The search is a plain recursive backtracking CSP:
 *
 *  * one star is placed per row, so the depth of the recursion is exactly [n];
 *  * state is kept in three O(1) structures - a column bitmask, a "used region" flag array and
 *    the column chosen on the previous row (needed for the no-touching rule);
 *  * two forward checks prune dead branches early:
 *      1. every region that has not received a star yet must still have at least one reachable
 *         cell below the current row whose column is still free;
 *      2. the number of open regions can never exceed the number of free columns / free rows.
 *
 * Because exactly one star is placed per row and per column, stars can only ever touch along the
 * diagonal or along the vertical axis between two consecutive rows; checking `abs(col - prevCol)
 * <= 1` on the previous row therefore covers the complete "no touching" rule.
 *
 * The implementation is stateless, allocation-light and safe to call from any thread. It performs
 * no I/O, no logging and no dynamic dispatch, which keeps both CPU and memory pressure minimal.
 */
object StarBattleSolver {

    /**
     * Solves the puzzle.
     *
     * @param n board size (N x N).
     * @param regions row-major region identifiers, `regions[row][col]`. Any [Int] values are
     *        accepted; equal values mean "same region", the actual numbers are irrelevant.
     * @return the coordinates of the N placed stars in row order, or an empty list when the board
     *         is unsatisfiable.
     */
    fun solve(n: Int, regions: Array<IntArray>): List<Cell> =
        solveOrNull(n, regions) ?: emptyList()

    /**
     * Same as [solve] but distinguishes "no solution" (`null`) from "not solvable at all".
     */
    fun solveOrNull(n: Int, regions: Array<IntArray>): List<Cell>? {
        require(n >= 1) { "Board size must be >= 1, was $n" }
        require(regions.size == n) { "Expected $n rows, got ${regions.size}" }
        for (row in 0 until n) {
            require(regions[row].size == n) {
                "Row $row must have $n columns, had ${regions[row].size}"
            }
        }

        val normalized = normalize(n, regions)
        return Search(n, normalized).run()
    }

    /**
     * Convenience helper for callers that only care whether the board can be solved.
     */
    fun hasSolution(n: Int, regions: Array<IntArray>): Boolean =
        solveOrNull(n, regions) != null

    /**
     * Re-maps arbitrary region ids to a dense 0..R-1 range so the search can index arrays directly.
     */
    private fun normalize(n: Int, regions: Array<IntArray>): Array<IntArray> {
        val ids = HashMap<Int, Int>(n * n)
        var next = 0
        return Array(n) { row ->
            IntArray(n) { col ->
                val raw = regions[row][col]
                val existing = ids[raw]
                if (existing != null) existing else {
                    val assigned = next++
                    ids[raw] = assigned
                    assigned
                }
            }
        }
    }

    /**
     * One solver instance == one search. Holds the mutable state so the recursion takes no
     * parameters other than the row index and the previous row's column.
     */
    private class Search(
        private val n: Int,
        private val region: Array<IntArray>
    ) {
        private val regionCount: Int
        private val columnsUsed = BooleanArray(n)
        private val regionsUsed: BooleanArray
        private val regionCells: Array<IntArray>
        private val starColumnPerRow = IntArray(n) { -1 }

        init {
            var maxRegion = 0
            for (row in 0 until n) {
                for (col in 0 until n) {
                    val id = region[row][col]
                    if (id > maxRegion) maxRegion = id
                }
            }
            regionCount = maxRegion + 1
            regionsUsed = BooleanArray(regionCount)

            val counts = IntArray(regionCount)
            for (row in 0 until n) {
                for (col in 0 until n) {
                    counts[region[row][col]]++
                }
            }
            regionCells = Array(regionCount) { IntArray(counts[it]) }
            val fill = IntArray(regionCount)
            for (row in 0 until n) {
                for (col in 0 until n) {
                    val id = region[row][col]
                    regionCells[id][fill[id]++] = row * n + col
                }
            }
        }

        fun run(): List<Cell>? {
            val solved = placeStar(0, -1)
            if (!solved) return null
            return ArrayList<Cell>(n).apply {
                for (row in 0 until n) {
                    add(Cell(row, starColumnPerRow[row]))
                }
            }
        }

        /**
         * Tries every legal column for [row] and recurses into the next row.
         *
         * @param previousColumn column of the star placed on `row - 1`, or -1 for the first row.
         */
        private fun placeStar(row: Int, previousColumn: Int): Boolean {
            if (row == n) return true

            val regionOfRow = region[row]
            for (col in 0 until n) {
                if (columnsUsed[col]) continue
                // No touching: the star directly above, above-left or above-right is forbidden.
                if (previousColumn >= 0 && abs(col - previousColumn) <= 1) continue
                val regionId = regionOfRow[col]
                if (regionsUsed[regionId]) continue

                columnsUsed[col] = true
                regionsUsed[regionId] = true
                starColumnPerRow[row] = col

                if (forwardCheck(row + 1) && placeStar(row + 1, col)) return true

                starColumnPerRow[row] = -1
                regionsUsed[regionId] = false
                columnsUsed[col] = false
            }
            return false
        }

        /**
         * Cheap feasibility test for the remaining rows `nextRow .. n-1`.
         */
        private fun forwardCheck(nextRow: Int): Boolean {
            val rowsLeft = n - nextRow
            if (rowsLeft <= 0) return true

            var freeColumns = 0
            for (col in 0 until n) {
                if (!columnsUsed[col]) freeColumns++
            }
            if (freeColumns < rowsLeft) return false

            var openRegions = 0
            for (regionId in 0 until regionCount) {
                if (regionsUsed[regionId]) continue
                openRegions++
                if (openRegions > freeColumns || openRegions > rowsLeft) return false

                val cells = regionCells[regionId]
                var i = 0
                while (i < cells.size) {
                    val encoded = cells[i]
                    if (encoded / n >= nextRow && !columnsUsed[encoded % n]) {
                        reachable = true
                        break
                    }
                    i++
                }
                if (!reachable) return false
            }
            return true
        }
    }
}
