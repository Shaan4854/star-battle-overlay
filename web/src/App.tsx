import React, { useState, useEffect, useMemo, useCallback } from 'react'
import confetti from 'canvas-confetti'
import { Header } from './components/Header'
import { ImageUploader } from './components/ImageUploader'
import { Controls } from './components/Controls'
import { ColorPalette } from './components/ColorPalette'
import { GridBoard } from './components/GridBoard'
import { SolvedBoard } from './components/SolvedBoard'
import { PRESETS, createEmptyGrid } from './utils/presets'
import { solveQueens } from './utils/solver'
import { CellCoord, DetectionResult } from './types'

export const App: React.FC = () => {
  // Grid size
  const [n, setN] = useState<number>(8)

  // Grid state (n x n array of color IDs)
  const [grid, setGrid] = useState<number[][]>(() => PRESETS[0].grid)

  // Color picker state
  const [activeColor, setActiveColor] = useState<number>(0)
  const [toolMode, setToolMode] = useState<'brush' | 'bucket'>('brush')
  const [customPalette, setCustomPalette] = useState<string[] | undefined>(undefined)

  // Solver states
  const [solution, setSolution] = useState<CellCoord[] | null>(null)
  const [isSolving, setIsSolving] = useState<boolean>(false)
  const [unsolvable, setUnsolvable] = useState<boolean>(false)

  // Compute filled cell counts
  const cellCounts = useMemo(() => {
    const counts: Record<number, number> = {}
    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        const id = grid[r]?.[c]
        if (id !== undefined && id >= 0) {
          counts[id] = (counts[id] || 0) + 1
        }
      }
    }
    return counts
  }, [grid, n])

  const filledCount = useMemo(() => {
    return Object.values(cellCounts).reduce((a, b) => a + b, 0)
  }, [cellCounts])

  const totalCount = n * n

  // Solve the puzzle
  const handleSolve = useCallback(() => {
    setIsSolving(true)
    setUnsolvable(false)

    // Run solver asynchronously for smooth UI
    setTimeout(() => {
      const result = solveQueens(n, grid)
      setIsSolving(false)

      if (result) {
        setSolution(result)
        setUnsolvable(false)
        // Celebrate win!
        confetti({
          particleCount: 80,
          spread: 70,
          origin: { y: 0.6 }
        })
      } else {
        setSolution(null)
        setUnsolvable(true)
      }
    }, 40)
  }, [n, grid])

  // Automatically solve initial preset on mount
  useEffect(() => {
    const initialSol = solveQueens(8, PRESETS[0].grid)
    if (initialSol) setSolution(initialSol)
  }, [])

  // Handle grid detected from screenshot
  const handleGridDetected = (res: DetectionResult) => {
    setN(res.n)
    setGrid(res.grid)
    setCustomPalette(res.palette)
    setActiveColor(0)
    setSolution(null)
    setUnsolvable(false)

    // Auto-solve detected grid
    setTimeout(() => {
      const sol = solveQueens(res.n, res.grid)
      if (sol) {
        setSolution(sol)
        confetti({ particleCount: 100, spread: 80, origin: { y: 0.6 } })
      } else {
        setUnsolvable(true)
      }
    }, 100)
  }

  // Handle size change
  const handleSizeChange = (newSize: number) => {
    setN(newSize)
    setGrid(createEmptyGrid(newSize))
    setActiveColor(0)
    setSolution(null)
    setUnsolvable(false)
  }

  // Handle preset selection
  const handlePresetSelect = (presetId: string) => {
    const preset = PRESETS.find(p => p.id === presetId)
    if (!preset) return

    setN(preset.size)
    setGrid(preset.grid)
    setCustomPalette(undefined)
    setActiveColor(0)
    setUnsolvable(false)

    const sol = solveQueens(preset.size, preset.grid)
    setSolution(sol)
    if (sol) {
      confetti({ particleCount: 60, spread: 60, origin: { y: 0.6 } })
    }
  }

  // Handle clear
  const handleClear = () => {
    setGrid(createEmptyGrid(n))
    setSolution(null)
    setUnsolvable(false)
  }

  // Flood-fill algorithm for paint bucket tool
  const floodFill = (startR: number, startC: number, targetColor: number, replacementColor: number) => {
    if (targetColor === replacementColor) return
    const newGrid = grid.map(row => [...row])
    const queue: [number, number][] = [[startR, startC]]
    const visited = new Set<string>()

    while (queue.length > 0) {
      const [r, c] = queue.pop()!
      const key = `${r}-${c}`
      if (visited.has(key)) continue
      visited.add(key)

      if (r < 0 || r >= n || c < 0 || c >= n) continue
      if (newGrid[r][c] !== targetColor) continue

      newGrid[r][c] = replacementColor

      queue.push([r + 1, c])
      queue.push([r - 1, c])
      queue.push([r, c + 1])
      queue.push([r, c - 1])
    }

    setGrid(newGrid)
    setSolution(null)
    setUnsolvable(false)
  }

  // Handle cell click
  const handleCellClick = (r: number, c: number) => {
    if (toolMode === 'bucket') {
      const targetColor = grid[r][c]
      floodFill(r, c, targetColor, activeColor)
    } else {
      const newGrid = grid.map(row => [...row])
      newGrid[r][c] = activeColor
      setGrid(newGrid)
      setSolution(null)
      setUnsolvable(false)
    }
  }

  // Handle drag painting
  const handleCellPaint = (r: number, c: number) => {
    if (toolMode === 'brush' && grid[r][c] !== activeColor) {
      const newGrid = grid.map(row => [...row])
      newGrid[r][c] = activeColor
      setGrid(newGrid)
      setSolution(null)
      setUnsolvable(false)
    }
  }

  return (
    <div className="min-h-screen bg-[#0A0D14] flex flex-col font-sans">
      <Header />

      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 lg:p-8 space-y-6">
        {/* Screenshot Auto-Detection Card */}
        <ImageUploader onGridDetected={handleGridDetected} currentSize={n} />

        {/* Solver Controls */}
        <div className="glass-panel rounded-2xl p-4 sm:p-6 border border-white/10 shadow-xl space-y-5">
          <Controls
            n={n}
            onSizeChange={handleSizeChange}
            onPresetSelect={handlePresetSelect}
            onClear={handleClear}
            onSolve={handleSolve}
            isSolving={isSolving}
            filledCount={filledCount}
            totalCount={totalCount}
          />

          <ColorPalette
            n={n}
            activeColor={activeColor}
            onSelectColor={setActiveColor}
            toolMode={toolMode}
            onSelectToolMode={setToolMode}
            cellCounts={cellCounts}
            customPalette={customPalette}
          />
        </div>

        {/* Dual Boards: Starting Layout vs Solved Layout */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
          {/* Left Column: Starting Layout */}
          <div className="glass-panel rounded-2xl p-5 border border-white/10 shadow-xl flex flex-col items-center">
            <div className="flex items-center justify-between w-full mb-3 px-2">
              <h3 className="font-extrabold text-base text-white flex items-center space-x-2">
                <span>Starting Layout</span>
                <span className="text-xs font-semibold text-slate-400">({n}×{n})</span>
              </h3>
              <span className="text-xs text-amber-400 font-semibold">
                Click or drag to paint
              </span>
            </div>

            <GridBoard
              n={n}
              grid={grid}
              activeColor={activeColor}
              toolMode={toolMode}
              onCellClick={handleCellClick}
              onCellPaint={handleCellPaint}
              customPalette={customPalette}
            />
          </div>

          {/* Right Column: Solved Layout */}
          <div className="glass-panel rounded-2xl p-5 border border-white/10 shadow-xl flex flex-col items-center">
            <div className="flex items-center justify-between w-full mb-3 px-2">
              <h3 className="font-extrabold text-base text-white flex items-center space-x-2">
                <span>Solved Layout</span>
                {solution && (
                  <span className="text-xs font-bold text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/20">
                    {solution.length} Queens Placed
                  </span>
                )}
              </h3>
              <span className="text-xs text-slate-400 font-semibold">
                Hover queen to view row/col
              </span>
            </div>

            <SolvedBoard
              n={n}
              grid={grid}
              solution={solution}
              unsolvable={unsolvable}
              customPalette={customPalette}
            />
          </div>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-white/5 py-4 text-center text-xs text-slate-500">
        <p>
          LinkedIn Queens Game Solver • Built with pure recursive backtracking CSP &amp; HTML5 Canvas Vision.
        </p>
      </footer>
    </div>
  )
}
