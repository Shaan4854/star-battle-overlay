package com.starbattle.overlay

object Presets {

    data class PresetItem(val id: Int, val name: String, val size: Int, val grid: Array<IntArray>)

    val PRESET_LIST = listOf(
        PresetItem(
            id = 1,
            name = "Preset 1 (8x8)",
            size = 8,
            grid = arrayOf(
                intArrayOf(0, 0, 0, 1, 1, 1, 2, 2),
                intArrayOf(0, 0, 0, 1, 1, 1, 2, 2),
                intArrayOf(3, 3, 3, 3, 4, 4, 2, 2),
                intArrayOf(3, 5, 5, 4, 4, 4, 4, 2),
                intArrayOf(3, 5, 5, 5, 6, 6, 6, 6),
                intArrayOf(7, 7, 5, 5, 6, 6, 6, 6),
                intArrayOf(7, 7, 7, 7, 7, 6, 6, 6),
                intArrayOf(7, 7, 7, 7, 7, 7, 6, 6)
            )
        ),
        PresetItem(
            id = 2,
            name = "Preset 2 (8x8)",
            size = 8,
            grid = arrayOf(
                intArrayOf(0, 0, 1, 1, 1, 2, 2, 2),
                intArrayOf(0, 0, 0, 1, 1, 2, 2, 2),
                intArrayOf(3, 3, 0, 0, 1, 4, 4, 2),
                intArrayOf(3, 3, 3, 0, 4, 4, 4, 4),
                intArrayOf(5, 5, 3, 0, 6, 6, 4, 4),
                intArrayOf(5, 5, 5, 6, 6, 6, 6, 4),
                intArrayOf(5, 7, 7, 7, 6, 6, 6, 4),
                intArrayOf(7, 7, 7, 7, 7, 6, 6, 4)
            )
        ),
        PresetItem(
            id = 3,
            name = "Preset 3 (9x9)",
            size = 9,
            grid = arrayOf(
                intArrayOf(0, 0, 0, 1, 1, 2, 2, 2, 2),
                intArrayOf(0, 0, 0, 1, 1, 1, 2, 2, 2),
                intArrayOf(3, 3, 0, 0, 1, 4, 4, 2, 2),
                intArrayOf(3, 3, 3, 0, 4, 4, 4, 4, 2),
                intArrayOf(5, 5, 3, 0, 6, 6, 4, 4, 2),
                intArrayOf(5, 5, 5, 6, 6, 6, 6, 4, 7),
                intArrayOf(5, 8, 8, 8, 6, 6, 6, 7, 7),
                intArrayOf(8, 8, 8, 8, 8, 6, 7, 7, 7),
                intArrayOf(8, 8, 8, 8, 8, 8, 7, 7, 7)
            )
        ),
        PresetItem(
            id = 4,
            name = "Preset 4 (7x7)",
            size = 7,
            grid = arrayOf(
                intArrayOf(0, 0, 1, 1, 2, 2, 2),
                intArrayOf(0, 0, 0, 1, 2, 2, 2),
                intArrayOf(3, 3, 0, 1, 4, 4, 2),
                intArrayOf(3, 3, 3, 4, 4, 4, 4),
                intArrayOf(5, 5, 3, 6, 6, 4, 4),
                intArrayOf(5, 5, 5, 6, 6, 6, 6),
                intArrayOf(5, 5, 5, 5, 6, 6, 6)
            )
        ),
        PresetItem(
            id = 5,
            name = "Preset 5 (10x10)",
            size = 10,
            grid = arrayOf(
                intArrayOf(0, 0, 0, 1, 1, 2, 2, 2, 2, 3),
                intArrayOf(0, 0, 0, 1, 1, 1, 2, 2, 3, 3),
                intArrayOf(4, 4, 0, 0, 1, 5, 5, 2, 3, 3),
                intArrayOf(4, 4, 4, 0, 5, 5, 5, 5, 3, 3),
                intArrayOf(6, 6, 4, 0, 7, 7, 5, 5, 3, 3),
                intArrayOf(6, 6, 6, 7, 7, 7, 7, 5, 8, 8),
                intArrayOf(6, 9, 9, 9, 7, 7, 7, 8, 8, 8),
                intArrayOf(9, 9, 9, 9, 9, 7, 8, 8, 8, 8),
                intArrayOf(9, 9, 9, 9, 9, 9, 8, 8, 8, 8),
                intArrayOf(9, 9, 9, 9, 9, 9, 9, 8, 8, 8)
            )
        )
    )
}
