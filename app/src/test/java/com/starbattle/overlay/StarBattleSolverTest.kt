package com.starbattle.overlay

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StarBattleSolverTest {

    @Test
    fun testSolve4x4ValidGrid() {
        // Regions setup such that solution (0,1), (1,3), (2,0), (3,2) is valid:
        val regions = arrayOf(
            intArrayOf(0, 0, 1, 1),
            intArrayOf(0, 0, 1, 1),
            intArrayOf(2, 2, 3, 3),
            intArrayOf(2, 2, 3, 3)
        )

        val solution = StarBattleSolver.solve(4, regions)
        assertNotNull("Solution should exist for valid 4x4 puzzle", solution)
        assertEquals(4, solution!!.size)

        verifySolutionConstraints(4, regions, solution)
    }

    @Test
    fun testSolve8x8QueensPuzzle() {
        // A realistic 8x8 region layout (like LinkedIn Queens)
        val regions = arrayOf(
            intArrayOf(0, 0, 0, 1, 1, 1, 2, 2),
            intArrayOf(0, 0, 0, 1, 1, 1, 2, 2),
            intArrayOf(3, 3, 3, 3, 4, 4, 2, 2),
            intArrayOf(3, 5, 5, 4, 4, 4, 4, 2),
            intArrayOf(3, 5, 5, 5, 6, 6, 6, 6),
            intArrayOf(7, 7, 5, 5, 6, 6, 6, 6),
            intArrayOf(7, 7, 7, 7, 7, 6, 6, 6),
            intArrayOf(7, 7, 7, 7, 7, 7, 6, 6)
        )

        val solution = StarBattleSolver.solve(8, regions)
        assertNotNull("Should find a solution for standard 8x8 layout", solution)
        assertEquals(8, solution!!.size)

        verifySolutionConstraints(8, regions, solution)
    }

    @Test
    fun testUnsolvableGridReturnsNull() {
        // 4x4 where all cells in row 0 are the same region as row 1, etc., leading to contradiction
        val impossibleRegions = arrayOf(
            intArrayOf(0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0),
            intArrayOf(1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1)
        )

        val solution = StarBattleSolver.solve(4, impossibleRegions)
        assertNull("Impossible region setup should return null", solution)
    }

    private fun verifySolutionConstraints(n: Int, regions: Array<IntArray>, solution: List<Pair<Int, Int>>) {
        val rows = HashSet<Int>()
        val cols = HashSet<Int>()
        val seenRegions = HashSet<Int>()

        for (i in solution.indices) {
            val (r, c) = solution[i]
            assertTrue("Row $r should be in 0 until $n", r in 0 until n)
            assertTrue("Col $c should be in 0 until $n", c in 0 until n)

            // 1. One star per row & column
            assertTrue("Row $r must be unique", rows.add(r))
            assertTrue("Col $c must be unique", cols.add(c))

            // 2. One star per region
            val reg = regions[r][c]
            assertTrue("Region $reg must be unique", seenRegions.add(reg))

            // 3. Adjacency: cannot touch any other star
            for (j in i + 1 until solution.size) {
                val (r2, c2) = solution[j]
                val touches = abs(r - r2) <= 1 && abs(c - c2) <= 1
                assertFalse("Stars at ($r,$c) and ($r2,$c2) cannot touch orthogonally or diagonally", touches)
            }
        }
    }
}
