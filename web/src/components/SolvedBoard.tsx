import React, { useState } from 'react'
import { Crown, Sparkles, Copy, Check, Download, AlertCircle } from 'lucide-react'
import { CellCoord } from '../types'
import { getColorById } from '../utils/palette'

interface SolvedBoardProps {
  n: number
  grid: number[][]
  solution: CellCoord[] | null
  unsolvable: boolean
  customPalette?: string[]
}

export const SolvedBoard: React.FC<SolvedBoardProps> = ({
  n,
  grid,
  solution,
  unsolvable,
  customPalette
}) => {
  const [copied, setCopied] = useState(false)
  const [hoveredQueen, setHoveredQueen] = useState<CellCoord | null>(null)

  // Fast set lookup for placed queens
  const queenPositions = new Set<string>()
  if (solution) {
    for (const q of solution) {
      queenPositions.add(`${q.row}-${q.col}`)
    }
  }

  const handleCopySolution = () => {
    if (!solution) return
    const text = solution.map(q => `Row ${q.row + 1}, Col ${q.col + 1}`).join('\n')
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleDownloadImage = () => {
    if (!solution) return
    const canvas = document.createElement('canvas')
    const size = 600
    canvas.width = size
    canvas.height = size
    const ctx = canvas.getContext('2d')!
    const cellSize = size / n

    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        const colorId = grid[r][c]
        const color = getColorById(colorId, customPalette)
        ctx.fillStyle = color.hex
        ctx.fillRect(c * cellSize, r * cellSize, cellSize, cellSize)
        ctx.strokeStyle = '#0A0D14'
        ctx.lineWidth = 2
        ctx.strokeRect(c * cellSize, r * cellSize, cellSize, cellSize)

        if (queenPositions.has(`${r}-${c}`)) {
          ctx.fillStyle = '#000000'
          ctx.font = `bold ${Math.round(cellSize * 0.55)}px sans-serif`
          ctx.textAlign = 'center'
          ctx.textBaseline = 'middle'
          ctx.fillText('👑', (c + 0.5) * cellSize, (r + 0.5) * cellSize)
        }
      }
    }

    const a = document.createElement('a')
    a.download = `queens-solution-${n}x${n}.png`
    a.href = canvas.toDataURL('image/png')
    a.click()
  }

  return (
    <div className="flex flex-col items-center">
      <div className="relative">
        {/* The Grid */}
        <div
          className="grid gap-[1px] p-2 rounded-2xl bg-black/60 border-2 border-white/10 shadow-2xl overflow-hidden"
          style={{
            gridTemplateColumns: `repeat(${n}, minmax(0, 1fr))`,
            width: 'min(86vw, 420px)',
            height: 'min(86vw, 420px)',
          }}
        >
          {grid.map((row, r) =>
            row.map((colorId, c) => {
              const hasColor = colorId >= 0
              const colorDef = hasColor ? getColorById(colorId, customPalette) : null
              const isQueen = queenPositions.has(`${r}-${c}`)

              // Highlight interactions
              const isSameRow = hoveredQueen && hoveredQueen.row === r
              const isSameCol = hoveredQueen && hoveredQueen.col === c
              const isForbidden = hoveredQueen &&
                Math.abs(hoveredQueen.row - r) <= 1 &&
                Math.abs(hoveredQueen.col - c) <= 1 &&
                !isQueen

              // Region borders
              const isTopDiff = r > 0 && grid[r - 1][c] !== colorId
              const isBottomDiff = r < n - 1 && grid[r + 1][c] !== colorId
              const isLeftDiff = c > 0 && grid[r][c - 1] !== colorId
              const isRightDiff = c < n - 1 && grid[r][c + 1] !== colorId

              return (
                <div
                  key={`${r}-${c}`}
                  onMouseEnter={() => isQueen && setHoveredQueen({ row: r, col: c })}
                  onMouseLeave={() => setHoveredQueen(null)}
                  className={`relative flex items-center justify-center transition-all ${
                    !hasColor ? 'bg-slate-800/40' : ''
                  } ${isSameRow || isSameCol ? 'brightness-110' : ''}`}
                  style={{
                    backgroundColor: colorDef ? colorDef.hex : undefined,
                    borderTopWidth: isTopDiff ? '2.5px' : '0.5px',
                    borderBottomWidth: isBottomDiff ? '2.5px' : '0.5px',
                    borderLeftWidth: isLeftDiff ? '2.5px' : '0.5px',
                    borderRightWidth: isRightDiff ? '2.5px' : '0.5px',
                    borderColor: isTopDiff || isBottomDiff || isLeftDiff || isRightDiff ? '#0A0D14' : 'rgba(255,255,255,0.1)'
                  }}
                >
                  {isQueen && (
                    <div className="relative animate-in zoom-in-50 duration-300">
                      <div className="w-7 h-7 sm:w-8 sm:h-8 rounded-full bg-black/80 flex items-center justify-center shadow-lg border border-amber-400/80">
                        <Crown className="w-4 h-4 sm:w-5 sm:h-5 text-amber-300 fill-amber-300 drop-shadow-[0_0_8px_rgba(255,215,0,0.8)]" />
                      </div>
                    </div>
                  )}

                  {isForbidden && (
                    <span className="text-red-500/60 text-xs font-bold font-mono">
                      ✕
                    </span>
                  )}
                </div>
              )
            })
          )}
        </div>

        {/* Overlay states: Unsolvable or Empty */}
        {unsolvable && (
          <div className="absolute inset-0 bg-black/85 backdrop-blur-sm rounded-2xl flex flex-col items-center justify-center p-6 text-center">
            <AlertCircle className="w-10 h-10 text-rose-400 mb-2" />
            <h4 className="font-bold text-base text-white">No Valid Solution</h4>
            <p className="text-xs text-slate-300 mt-1 max-w-xs">
              This layout configuration contradicts Queens rules. Please check your colors or auto-detect from screenshot.
            </p>
          </div>
        )}

        {!solution && !unsolvable && (
          <div className="absolute inset-0 bg-black/70 backdrop-blur-xs rounded-2xl flex flex-col items-center justify-center p-6 text-center">
            <Sparkles className="w-8 h-8 text-amber-400 mb-2 animate-pulse" />
            <p className="text-xs font-bold text-slate-200">
              Ready to Solve
            </p>
            <p className="text-[11px] text-slate-400 mt-0.5">
              Paste screenshot or fill starting layout, then click Solve!
            </p>
          </div>
        )}
      </div>

      {/* Solution Actions */}
      {solution && (
        <div className="flex items-center space-x-2 mt-3">
          <button
            onClick={handleCopySolution}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-white/5 hover:bg-white/10 text-slate-300 border border-white/10 transition"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Copied Coordinates!' : 'Copy Solution'}</span>
          </button>
          <button
            onClick={handleDownloadImage}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-white/5 hover:bg-white/10 text-slate-300 border border-white/10 transition"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Download PNG</span>
          </button>
        </div>
      )}
    </div>
  )
}
