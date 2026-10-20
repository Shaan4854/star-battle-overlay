import React, { useState } from 'react'
import { getColorById } from '../utils/palette'

interface GridBoardProps {
  n: number
  grid: number[][]
  activeColor: number
  toolMode: 'brush' | 'bucket'
  onCellClick: (row: number, col: number) => void
  onCellPaint: (row: number, col: number) => void
  customPalette?: string[]
}

export const GridBoard: React.FC<GridBoardProps> = ({
  n,
  grid,
  toolMode,
  onCellClick,
  onCellPaint,
  customPalette
}) => {
  const [isMouseDown, setIsMouseDown] = useState(false)

  const handleMouseDown = (r: number, c: number) => {
    setIsMouseDown(true)
    onCellClick(r, c)
  }

  const handleMouseEnter = (r: number, c: number) => {
    if (isMouseDown && toolMode === 'brush') {
      onCellPaint(r, c)
    }
  }

  const handleMouseUp = () => {
    setIsMouseDown(false)
  }

  return (
    <div
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      className="select-none flex flex-col items-center"
    >
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

            // Region border calculation (thick border between different color regions)
            const isTopDiff = r > 0 && grid[r - 1][c] !== colorId
            const isBottomDiff = r < n - 1 && grid[r + 1][c] !== colorId
            const isLeftDiff = c > 0 && grid[r][c - 1] !== colorId
            const isRightDiff = c < n - 1 && grid[r][c + 1] !== colorId

            return (
              <div
                key={`${r}-${c}`}
                onMouseDown={() => handleMouseDown(r, c)}
                onMouseEnter={() => handleMouseEnter(r, c)}
                className={`relative flex items-center justify-center cursor-pointer transition-colors duration-100 ${
                  !hasColor ? 'bg-slate-800/60 hover:bg-slate-700/80' : ''
                }`}
                style={{
                  backgroundColor: colorDef ? colorDef.hex : undefined,
                  borderTopWidth: isTopDiff ? '2.5px' : '0.5px',
                  borderBottomWidth: isBottomDiff ? '2.5px' : '0.5px',
                  borderLeftWidth: isLeftDiff ? '2.5px' : '0.5px',
                  borderRightWidth: isRightDiff ? '2.5px' : '0.5px',
                  borderColor: isTopDiff || isBottomDiff || isLeftDiff || isRightDiff ? '#0A0D14' : 'rgba(255,255,255,0.1)'
                }}
              >
                {!hasColor && (
                  <span className="text-[10px] text-slate-500 font-mono">
                    •
                  </span>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
