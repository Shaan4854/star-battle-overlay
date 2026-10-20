import React from 'react'
import { Paintbrush, PaintBucket } from 'lucide-react'
import { getColorById } from '../utils/palette'

interface ColorPaletteProps {
  n: number
  activeColor: number
  onSelectColor: (colorId: number) => void
  toolMode: 'brush' | 'bucket'
  onSelectToolMode: (mode: 'brush' | 'bucket') => void
  cellCounts: Record<number, number>
  customPalette?: string[]
}

export const ColorPalette: React.FC<ColorPaletteProps> = ({
  n,
  activeColor,
  onSelectColor,
  toolMode,
  onSelectToolMode,
  cellCounts,
  customPalette
}) => {
  return (
    <div className="flex flex-col space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider">
          Color Regions ({n})
        </span>

        {/* Tool Mode Toggle */}
        <div className="inline-flex rounded-lg bg-black/40 p-0.5 border border-white/10">
          <button
            onClick={() => onSelectToolMode('brush')}
            className={`flex items-center space-x-1 px-2.5 py-1 rounded-md text-xs font-semibold transition ${
              toolMode === 'brush'
                ? 'bg-amber-400 text-black shadow-sm'
                : 'text-slate-400 hover:text-white'
            }`}
            title="Paint Brush (Click or drag individual cells)"
          >
            <Paintbrush className="w-3 h-3" />
            <span>Brush</span>
          </button>
          <button
            onClick={() => onSelectToolMode('bucket')}
            className={`flex items-center space-x-1 px-2.5 py-1 rounded-md text-xs font-semibold transition ${
              toolMode === 'bucket'
                ? 'bg-amber-400 text-black shadow-sm'
                : 'text-slate-400 hover:text-white'
            }`}
            title="Paint Bucket (Flood-fill entire connected region)"
          >
            <PaintBucket className="w-3 h-3" />
            <span>Bucket</span>
          </button>
        </div>
      </div>

      {/* Palette Swatches */}
      <div className="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 gap-2">
        {Array.from({ length: n }).map((_, idx) => {
          const color = getColorById(idx, customPalette)
          const isSelected = activeColor === idx
          const count = cellCounts[idx] || 0

          return (
            <button
              key={idx}
              onClick={() => onSelectColor(idx)}
              className={`relative flex flex-col items-center justify-center p-1.5 rounded-xl transition border-2 ${
                isSelected
                  ? 'border-white scale-105 shadow-md shadow-black/50 ring-2 ring-amber-400/50'
                  : 'border-transparent hover:border-white/30 hover:scale-102'
              }`}
              style={{ backgroundColor: color.hex }}
              title={`Color ${idx + 1}: ${count} cells assigned`}
            >
              <span className="text-[10px] font-extrabold text-black/90 drop-shadow-sm">
                #{idx + 1}
              </span>
              <span className="text-[9px] font-semibold text-black/70 bg-white/40 px-1 rounded-full mt-0.5">
                {count}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
