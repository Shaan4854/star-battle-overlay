import React from 'react'
import { X, CheckCircle2, AlertTriangle, Crown } from 'lucide-react'

interface RulesModalProps {
  onClose: () => void
}

export const RulesModal: React.FC<RulesModalProps> = ({ onClose }) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
      <div className="glass-panel w-full max-w-lg rounded-2xl border border-white/10 shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/10 bg-white/5">
          <div className="flex items-center space-x-2">
            <Crown className="w-5 h-5 text-amber-400" />
            <h3 className="font-bold text-base text-white">LinkedIn Queens Rules</h3>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-white/10 transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content */}
        <div className="p-6 space-y-4 text-sm text-slate-300">
          <p className="text-slate-200">
            Your goal is to place exactly <strong>one Queen</strong> in each row, column, and color region on an <strong>N × N</strong> board.
          </p>

          <div className="space-y-3">
            <div className="flex items-start space-x-3 p-3 rounded-xl bg-white/5 border border-white/5">
              <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
              <div>
                <strong className="text-white">One Queen Per Row:</strong>
                <p className="text-xs text-slate-400 mt-0.5">Every row must have exactly 1 queen.</p>
              </div>
            </div>

            <div className="flex items-start space-x-3 p-3 rounded-xl bg-white/5 border border-white/5">
              <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
              <div>
                <strong className="text-white">One Queen Per Column:</strong>
                <p className="text-xs text-slate-400 mt-0.5">Every column must have exactly 1 queen.</p>
              </div>
            </div>

            <div className="flex items-start space-x-3 p-3 rounded-xl bg-white/5 border border-white/5">
              <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
              <div>
                <strong className="text-white">One Queen Per Color Region:</strong>
                <p className="text-xs text-slate-400 mt-0.5">Each colored area must contain exactly 1 queen.</p>
              </div>
            </div>

            <div className="flex items-start space-x-3 p-3 rounded-xl bg-amber-500/10 border border-amber-500/20">
              <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
              <div>
                <strong className="text-amber-200">No Touching (Even Diagonally!):</strong>
                <p className="text-xs text-slate-400 mt-0.5">
                  No two queens can touch horizontally, vertically, or diagonally. All 8 surrounding adjacent squares are forbidden.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="px-6 py-3 border-t border-white/10 bg-white/5 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-xl text-xs font-bold bg-amber-400 hover:bg-amber-300 text-black transition"
          >
            Got it, Let's Play!
          </button>
        </div>
      </div>
    </div>
  )
}
