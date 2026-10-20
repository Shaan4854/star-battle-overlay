export interface CellCoord {
  row: number
  col: number
}

export interface ColorDef {
  id: number
  name: string
  hex: string
  border: string
}

export interface Preset {
  id: string
  name: string
  size: number
  grid: number[][]
}

export interface DetectionResult {
  n: number
  grid: number[][]
  palette: string[]
  confidence: number
  previewUrl?: string
}

export type SolverMode = 'idle' | 'solving' | 'solved' | 'no-solution'
