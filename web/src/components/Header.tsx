import React, { useState } from 'react'
import { Sparkles, HelpCircle, ExternalLink, Github, Crown } from 'lucide-react'
import { RulesModal } from './RulesModal'

export const Header: React.FC = () => {
  const [showRules, setShowRules] = useState(false)

  return (
    <>
      <header className="glass-panel sticky top-0 z-30 border-b border-white/10 px-4 py-3 sm:px-6">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-amber-500 to-yellow-300 flex items-center justify-center shadow-lg shadow-amber-500/20">
              <Crown className="w-6 h-6 text-black" />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h1 className="font-extrabold text-lg sm:text-xl tracking-tight text-white">
                  LinkedIn Queens <span className="text-amber-400">Solver</span>
                </h1>
                <span className="hidden sm:inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                  <Sparkles className="w-2.5 h-2.5 mr-1" /> AUTO-DETECT
                </span>
              </div>
              <p className="text-xs text-slate-400 hidden sm:block">
                Paste or drop your screenshot — instant color &amp; region extraction
              </p>
            </div>
          </div>

          <div className="flex items-center space-x-2 sm:space-x-3">
            <button
              onClick={() => setShowRules(true)}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-white/5 hover:bg-white/10 text-slate-300 border border-white/10 transition"
            >
              <HelpCircle className="w-3.5 h-3.5 text-amber-400" />
              <span>Rules</span>
            </button>

            <a
              href="https://www.linkedin.com/games/queens/"
              target="_blank"
              rel="noreferrer"
              className="hidden md:inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 border border-blue-500/20 transition"
            >
              <span>Play Queens</span>
              <ExternalLink className="w-3.5 h-3.5" />
            </a>

            <a
              href="https://github.com/Shaan4854/star-battle-overlay"
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center justify-center p-2 rounded-lg bg-white/5 hover:bg-white/10 text-slate-300 border border-white/10 transition"
              title="GitHub Repository"
            >
              <Github className="w-4 h-4" />
            </a>
          </div>
        </div>
      </header>

      {showRules && <RulesModal onClose={() => setShowRules(false)} />}
    </>
  )
}
