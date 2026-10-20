import React, { useState, useEffect, useRef } from 'react'
import { Upload, Clipboard, Camera, Sparkles, Check, AlertCircle, Loader2 } from 'lucide-react'
import { detectGridFromImage } from '../utils/imageDetector'
import { DetectionResult } from '../types'

interface ImageUploaderProps {
  onGridDetected: (result: DetectionResult) => void
  currentSize: number
}

export const ImageUploader: React.FC<ImageUploaderProps> = ({ onGridDetected, currentSize }) => {
  const [isDragging, setIsDragging] = useState(false)
  const [isProcessing, setIsProcessing] = useState(false)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [statusType, setStatusType] = useState<'success' | 'error' | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Global Ctrl+V / Paste listener
  useEffect(() => {
    const handlePaste = async (e: ClipboardEvent) => {
      const items = e.clipboardData?.items
      if (!items) return

      for (let i = 0; i < items.length; i++) {
        if (items[i].type.startsWith('image/')) {
          const blob = items[i].getAsFile()
          if (blob) {
            e.preventDefault()
            await processImage(blob, 'Pasted screenshot')
            break
          }
        }
      }
    }

    window.addEventListener('paste', handlePaste)
    return () => window.removeEventListener('paste', handlePaste)
  }, [currentSize])

  const processImage = async (file: File | Blob, label: string) => {
    setIsProcessing(true)
    setStatusMessage(`Analyzing ${label}...`)
    setStatusType(null)

    try {
      const result = await detectGridFromImage(file, currentSize)
      setPreviewUrl(result.previewUrl || null)
      setStatusMessage(`Detected ${result.n}×${result.n} grid with ${result.palette.length} color regions!`)
      setStatusType('success')
      onGridDetected(result)
    } catch (err) {
      console.error(err)
      setStatusMessage('Could not extract board. Try adjusting your screenshot crop.')
      setStatusType('error')
    } finally {
      setIsProcessing(false)
    }
  }

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(true)
  }

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
  }

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      const file = e.dataTransfer.files[0]
      if (file.type.startsWith('image/')) {
        await processImage(file, file.name)
      }
    }
  }

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0]
      await processImage(file, file.name)
      e.target.value = ''
    }
  }

  return (
    <div className="glass-card rounded-2xl p-4 sm:p-5 border border-white/10">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center space-x-2">
          <div className="w-7 h-7 rounded-lg bg-emerald-500/20 text-emerald-400 flex items-center justify-center">
            <Sparkles className="w-4 h-4" />
          </div>
          <h2 className="font-bold text-sm sm:text-base text-white">
            Auto-Detect from Screenshot
          </h2>
        </div>
        <span className="text-[11px] font-semibold text-slate-400 bg-white/5 px-2.5 py-1 rounded-full border border-white/5">
          Press <kbd className="font-mono text-amber-300 bg-black/40 px-1 py-0.5 rounded">Ctrl + V</kbd> to Paste
        </span>
      </div>

      {/* Dropzone */}
      <div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
        className={`relative flex flex-col sm:flex-row items-center justify-center p-4 sm:p-6 rounded-xl border-2 border-dashed cursor-pointer transition ${
          isDragging
            ? 'border-emerald-400 bg-emerald-500/10'
            : 'border-white/10 hover:border-white/20 bg-white/[0.02] hover:bg-white/[0.04]'
        }`}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={handleFileChange}
        />

        {isProcessing ? (
          <div className="flex flex-col items-center py-2 space-y-2">
            <Loader2 className="w-8 h-8 text-emerald-400 animate-spin" />
            <p className="text-xs font-semibold text-emerald-300">
              Locating board &amp; extracting colors...
            </p>
          </div>
        ) : previewUrl ? (
          <div className="flex items-center space-x-4 w-full">
            <img
              src={previewUrl}
              alt="Detected Board"
              className="w-16 h-16 sm:w-20 sm:h-20 rounded-lg object-cover border border-emerald-400/50 shadow-md shadow-emerald-500/10"
            />
            <div className="flex-1">
              <div className="flex items-center space-x-1.5 text-emerald-400 text-xs font-bold mb-0.5">
                <Check className="w-4 h-4" />
                <span>Screenshot Analyzed</span>
              </div>
              <p className="text-xs text-slate-300">
                Colors and grid populated! Click or drag to upload another image.
              </p>
            </div>
          </div>
        ) : (
          <div className="flex flex-col sm:flex-row items-center sm:space-x-4 text-center sm:text-left">
            <div className="w-12 h-12 rounded-xl bg-white/5 flex items-center justify-center text-slate-300 mb-2 sm:mb-0">
              <Upload className="w-6 h-6 text-emerald-400" />
            </div>
            <div>
              <p className="text-xs sm:text-sm font-semibold text-slate-200">
                Drop your LinkedIn Queens screenshot here, or click to browse
              </p>
              <div className="flex items-center justify-center sm:justify-start space-x-3 text-[11px] text-slate-400 mt-1">
                <span className="inline-flex items-center">
                  <Clipboard className="w-3 h-3 mr-1 text-amber-400" /> Clipboard Paste
                </span>
                <span>•</span>
                <span className="inline-flex items-center">
                  <Camera className="w-3 h-3 mr-1 text-sky-400" /> Camera &amp; Gallery
                </span>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Status banner */}
      {statusMessage && (
        <div
          className={`mt-2.5 px-3 py-1.5 rounded-lg text-xs flex items-center space-x-2 ${
            statusType === 'success'
              ? 'bg-emerald-500/10 text-emerald-300 border border-emerald-500/20'
              : statusType === 'error'
              ? 'bg-rose-500/10 text-rose-300 border border-rose-500/20'
              : 'bg-white/5 text-slate-300 border border-white/5'
          }`}
        >
          {statusType === 'success' && <Check className="w-3.5 h-3.5 shrink-0" />}
          {statusType === 'error' && <AlertCircle className="w-3.5 h-3.5 shrink-0" />}
          <span>{statusMessage}</span>
        </div>
      )}
    </div>
  )
}
