import { CellCoord } from '../types'

/**
 * Pure CSP recursive backtracking solver for Star Battle / LinkedIn Queens.
 *
 * Rules:
 * 1. Exactly 1 Queen per row.
 * 2. Exactly 1 Queen per column.
 * 3. Exactly 1 Queen per color region.
 * 4. No two Queens can touch orthogonally or diagonally (all 8 surrounding cells are forbidden).
 */
export function solveQueens(n: number, grid: number[][]): CellCoord[] | null {
  if (n <= 0 || grid.length !== n) return null
  for (const row of grid) {
    if (row.length !== n) return null
  }

  // Normalize region IDs to 0..regionCount-1
  const regionMap = new Map<number, number>()
  let nextRegionId = 0
  const normalizedGrid: number[][] = []

  for (let r = 0; r < n; r++) {
    const rowNormalized: number[] = []
    for (let c = 0; c < n; c++) {
      const rawId = grid[r][c]
      if (!regionMap.has(rawId)) {
        regionMap.set(rawId, nextRegionId++)
      }
      rowNormalized.push(regionMap.get(rawId)!)
    }
    normalizedGrid.push(rowNormalized)
  }

  const regionCount = regionMap.size
  // If regionCount < n, by pigeonhole principle, at least one region must have 2 queens (violating 1 queen per region)
  if (regionCount < n) return null

  const usedCols = new Uint8Array(n)
  const usedRegions = new Uint8Array(regionCount)
  const solutionCols = new Int32Array(n)

  function backtrack(row: number): boolean {
    if (row === n) return true

    const regionRow = normalizedGrid[row]
    const prevCol = row > 0 ? solutionCols[row - 1] : -99

    for (let col = 0; col < n; col++) {
      // 1. Column constraint
      if (usedCols[col] === 1) continue

      // 2. Adjacency constraint with previous row
      if (row > 0 && Math.abs(prevCol - col) <= 1) continue

      // 3. Region constraint
      const reg = regionRow[col]
      if (usedRegions[reg] === 1) continue

      // Place Queen
      usedCols[col] = 1
      usedRegions[reg] = 1
      solutionCols[row] = col

      if (backtrack(row + 1)) return true

      // Backtrack
      usedCols[col] = 0
      usedRegions[reg] = 0
    }

    return false
  }

  const solved = backtrack(0)
  if (!solved) return null

  const solution: CellCoord[] = []
  for (let r = 0; r < n; r++) {
    solution.push({ row: r, col: solutionCols[r] })
  }
  return solution
}

/**
 * Validates whether a placement strictly satisfies all Queens rules.
 */
export function verifyQueensSolution(
  n: number,
  grid: number[][],
  solution: CellCoord[]
): { valid: boolean; error?: string } {
  if (solution.length !== n) {
    return { valid: false, error: `Expected ${n} queens, got ${solution.length}` }
  }

  const rows = new Set<number>()
  const cols = new Set<number>()
  const regions = new Set<number>()

  for (let i = 0; i < solution.length; i++) {
    const { row, col } = solution[i]
    if (row < 0 || row >= n || col < 0 || col >= n) {
      return { valid: false, error: `Coordinates (${row}, ${col}) out of bounds` }
    }

    if (rows.has(row)) return { valid: false, error: `Row ${row + 1} has multiple queens` }
    if (cols.has(col)) return { valid: false, error: `Column ${col + 1} has multiple queens` }

    const reg = grid[row][col]
    if (regions.has(reg)) return { valid: false, error: `Region ${reg} has multiple queens` }

    rows.add(row)
    cols.add(col)
    regions.add(reg)

    for (let j = i + 1; j < solution.length; j++) {
      const q2 = solution[j]
      if (Math.abs(row - q2.row) <= 1 && Math.abs(col - q2.col) <= 1) {
        return { valid: false, error: `Queens at (${row + 1},${col + 1}) and (${q2.row + 1},${q2.col + 1}) are touching` }
      }
    }
  }

  return { valid: true }
}
