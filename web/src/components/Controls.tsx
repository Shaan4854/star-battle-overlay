import React from 'react'
import { Zap, RotateCcw } from 'lucide-react'
import { PRESETS } from '../utils/presets'

interface ControlsProps {
  n: number
  onSizeChange: (size: number) => void
  onPresetSelect: (presetId: string) => void
  onClear: () => void
  onSolve: () => void
  isSolving: boolean
  filledCount: number
  totalCount: number
}

export const Controls: React.FC<ControlsProps> = ({
  n,
  onSizeChange,
  onPresetSelect,
  onClear,
  onSolve,
  isSolving,
  filledCount,
  totalCount
}) => {
  const isComplete = filledCount === totalCount

  return (
    <div className="space-y-4">
      {/* Row 1: Size Selector & Preset Selector */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        {/* Size Selection */}
        <div className="flex items-center space-x-2">
          <span className="text-xs font-bold text-slate-300">Select Size:</span>
          <div className="flex items-center space-x-1 overflow-x-auto py-1">
            {[4, 5, 6, 7, 8, 9, 10, 11, 12].map((size) => (
              <button
                key={size}
                onClick={() => onSizeChange(size)}
                className={`px-2.5 py-1 rounded-lg text-xs font-bold transition ${
                  n === size
                    ? 'bg-amber-400 text-black shadow-md'
                    : 'bg-white/5 hover:bg-white/10 text-slate-300 border border-white/5'
                }`}
              >
                {size}
              </button>
            ))}
          </div>
        </div>

        {/* Preset Selector */}
        <div className="flex items-center space-x-1.5 flex-wrap">
          <span className="text-xs font-bold text-slate-400 mr-1">Presets:</span>
          {PRESETS.map((preset, idx) => (
            <button
              key={preset.id}
              onClick={() => onPresetSelect(preset.id)}
              className="px-2.5 py-1 rounded-lg text-xs font-semibold bg-white/5 hover:bg-white/10 text-slate-300 border border-white/10 transition"
              title={`${preset.name} (${preset.size}x${preset.size})`}
            >
              {idx + 1}
            </button>
          ))}
          <button
            onClick={onClear}
            className="flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-semibold bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 border border-rose-500/20 transition"
            title="Clear all grid cells"
          >
            <RotateCcw className="w-3 h-3" />
            <span>Clear</span>
          </button>
        </div>
      </div>

      {/* Row 2: Fill Progress & Primary Solve Action */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 p-3 rounded-xl bg-white/[0.02] border border-white/5">
        <div>
          <div className="flex items-center space-x-2">
            <span className="text-xs font-bold text-slate-200">Starting Layout:</span>
            <span
              className={`text-xs font-mono font-bold ${
                isComplete ? 'text-emerald-400' : 'text-amber-400'
              }`}
            >
              {filledCount} / {totalCount} cells filled
            </span>
          </div>
          <div className="w-48 h-1.5 bg-black/40 rounded-full overflow-hidden mt-1.5 border border-white/5">
            <div
              className={`h-full transition-all duration-300 ${
                isComplete ? 'bg-emerald-400' : 'bg-amber-400'
              }`}
              style={{ width: `${(filledCount / totalCount) * 100}%` }}
            />
          </div>
        </div>

        {/* Big Action Button */}
        <button
          onClick={onSolve}
          disabled={isSolving}
          className={`flex items-center justify-center space-x-2 px-6 py-2.5 rounded-xl font-extrabold text-sm tracking-wide transition shadow-lg ${
            isComplete
              ? 'bg-gradient-to-r from-emerald-400 to-cyan-400 text-black hover:opacity-95 shadow-emerald-500/20 active:scale-98'
              : 'bg-gradient-to-r from-amber-400 to-yellow-300 text-black hover:opacity-95 shadow-amber-500/20 active:scale-98'
          }`}
        >
          <Zap className="w-4 h-4 fill-black" />
          <span>{isSolving ? 'SOLVING...' : 'SOLVE PUZZLE'}</span>
        </button>
      </div>
    </div>
  )
}
