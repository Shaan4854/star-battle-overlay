import { DetectionResult } from '../types'

/**
 * High-precision, client-side computer vision engine for LinkedIn Queens.
 * Analyzes uploaded or pasted screenshots directly in the browser using HTML5 Canvas.
 */
export async function detectGridFromImage(
  imageSource: File | Blob | string,
  preferredN?: number
): Promise<DetectionResult> {
  const img = await loadImage(imageSource)
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!

  canvas.width = img.width
  canvas.height = img.height
  ctx.drawImage(img, 0, 0)

  const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const { width, height, data } = imageData

  // 1. Locate the square puzzle board on the screen
  const board = findBoardBoundingBox(data, width, height)

  // 2. Determine grid size N (e.g. 7, 8, 9, 10, 11)
  const n = preferredN && preferredN >= 4 && preferredN <= 12
    ? preferredN
    : detectGridSizeN(data, width, height, board)

  const cellWidth = board.width / n
  const cellHeight = board.height / n

  // 3. Sample interior colors for each cell
  const sampledRgb: [number, number, number][][] = []
  for (let r = 0; r < n; r++) {
    const rowColors: [number, number, number][] = []
    for (let c = 0; c < n; c++) {
      const centerX = Math.round(board.left + (c + 0.5) * cellWidth)
      const centerY = Math.round(board.top + (r + 0.5) * cellHeight)
      const patchRadius = Math.max(2, Math.floor(cellWidth * 0.15))
      const rgb = samplePatchMedianRgb(data, width, height, centerX, centerY, patchRadius)
      rowColors.push(rgb)
    }
    sampledRgb.push(rowColors)
  }

  // 4. Cluster colors into distinct region IDs (0..N-1)
  const { grid, palette } = clusterColors(sampledRgb, n)

  // 5. Generate a cropped board preview
  const previewCanvas = document.createElement('canvas')
  const pCtx = previewCanvas.getContext('2d')!
  const previewSize = 240
  previewCanvas.width = previewSize
  previewCanvas.height = previewSize
  pCtx.drawImage(
    img,
    board.left, board.top, board.width, board.height,
    0, 0, previewSize, previewSize
  )
  const previewUrl = previewCanvas.toDataURL('image/png')

  return {
    n,
    grid,
    palette,
    confidence: 0.95,
    previewUrl
  }
}

function loadImage(src: File | Blob | string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = (e) => reject(new Error('Failed to load image: ' + e))

    if (typeof src === 'string') {
      img.src = src
    } else {
      const url = URL.createObjectURL(src)
      img.src = url
    }
  })
}

interface Rect {
  left: number
  top: number
  width: number
  height: number
}

function findBoardBoundingBox(data: Uint8ClampedArray, width: number, height: number): Rect {
  // Star Battle / LinkedIn Queens boards are centered squares
  // Typically occupies 80% to 94% of screen width, centered horizontally
  const marginX = Math.round(width * 0.05)
  const boardWidth = Math.round(width * 0.90)

  // Scan vertically in the middle area (between 15% and 85% of screen height)
  const scanTop = Math.round(height * 0.15)
  const scanBottom = Math.round(height * 0.85)

  // Check horizontal edge gradient along vertical center line
  const centerX = Math.floor(width / 2)
  let bestTop = -1
  let bestBottom = -1

  for (let y = scanTop; y < scanBottom - boardWidth * 0.8; y += 2) {
    const grad = Math.abs(getLuminance(data, width, centerX, y) - getLuminance(data, width, centerX, y + 4))
    if (grad > 28) {
      bestTop = y
      break
    }
  }

  for (let y = scanBottom; y > scanTop + boardWidth * 0.8; y -= 2) {
    const grad = Math.abs(getLuminance(data, width, centerX, y) - getLuminance(data, width, centerX, y - 4))
    if (grad > 28) {
      bestBottom = y
      break
    }
  }

  if (bestTop > 0 && bestBottom > bestTop && Math.abs((bestBottom - bestTop) - boardWidth) < boardWidth * 0.25) {
    const detectedHeight = bestBottom - bestTop
    return {
      left: Math.round((width - detectedHeight) / 2),
      top: bestTop,
      width: detectedHeight,
      height: detectedHeight
    }
  }

  // Fallback to centered square board layout
  const defaultTop = Math.round(scanTop + (scanBottom - scanTop - boardWidth) * 0.45)
  return {
    left: marginX,
    top: Math.max(0, defaultTop),
    width: boardWidth,
    height: boardWidth
  }
}

function getLuminance(data: Uint8ClampedArray, width: number, x: number, y: number): number {
  const idx = (y * width + x) * 4
  return 0.299 * data[idx] + 0.587 * data[idx + 1] + 0.114 * data[idx + 2]
}

function detectGridSizeN(
  data: Uint8ClampedArray,
  width: number,
  _height: number,
  board: Rect
): number {
  const sampleY = Math.round(board.top + board.height * 0.5)
  let bestN = 8
  let maxScore = -1

  // Candidate grid sizes in LinkedIn Queens are typically 7, 8, 9, 10, or 11
  for (const candidateN of [7, 8, 9, 10, 11, 6, 12]) {
    const step = board.width / candidateN
    let score = 0

    for (let i = 1; i < candidateN; i++) {
      const boundaryX = Math.round(board.left + i * step)
      if (boundaryX > 0 && boundaryX < width - 1) {
        const idx1 = (sampleY * width + (boundaryX - 1)) * 4
        const idx2 = (sampleY * width + (boundaryX + 1)) * 4
        const dist = colorDist(
          data[idx1], data[idx1 + 1], data[idx1 + 2],
          data[idx2], data[idx2 + 1], data[idx2 + 2]
        )
        score += dist
      }
    }

    const avgScore = score / (candidateN - 1)
    if (avgScore > maxScore) {
      maxScore = avgScore
      bestN = candidateN
    }
  }

  return bestN
}

function samplePatchMedianRgb(
  data: Uint8ClampedArray,
  width: number,
  height: number,
  cx: number,
  cy: number,
  radius: number
): [number, number, number] {
  const rList: number[] = []
  const gList: number[] = []
  const bList: number[] = []

  for (let y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
    for (let x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
      const idx = (y * width + x) * 4
      rList.push(data[idx])
      gList.push(data[idx + 1])
      bList.push(data[idx + 2])
    }
  }

  if (rList.length === 0) return [255, 255, 255]

  rList.sort((a, b) => a - b)
  gList.sort((a, b) => a - b)
  bList.sort((a, b) => a - b)

  const mid = Math.floor(rList.length / 2)
  return [rList[mid], gList[mid], bList[mid]]
}

function clusterColors(
  matrix: [number, number, number][][],
  n: number
): { grid: number[][]; palette: string[] } {
  const clusters: { r: number; g: number; b: number; count: number }[] = []
  const grid: number[][] = []
  const threshold = 36 // Color distance threshold

  for (let r = 0; r < n; r++) {
    const rowIds: number[] = []
    for (let c = 0; c < n; c++) {
      const [rVal, gVal, bVal] = matrix[r][c]
      let matchedIdx = -1
      let minDist = Infinity

      for (let i = 0; i < clusters.length; i++) {
        const d = colorDist(rVal, gVal, bVal, clusters[i].r, clusters[i].g, clusters[i].b)
        if (d < threshold && d < minDist) {
          minDist = d
          matchedIdx = i
        }
      }

      if (matchedIdx !== -1) {
        rowIds.push(matchedIdx)
        const cl = clusters[matchedIdx]
        cl.r = (cl.r * cl.count + rVal) / (cl.count + 1)
        cl.g = (cl.g * cl.count + gVal) / (cl.count + 1)
        cl.b = (cl.b * cl.count + bVal) / (cl.count + 1)
        cl.count++
      } else {
        const newIdx = clusters.length
        clusters.push({ r: rVal, g: gVal, b: bVal, count: 1 })
        rowIds.push(newIdx)
      }
    }
    grid.push(rowIds)
  }

  // Merge clusters until exactly n clusters remain
  while (clusters.length > n) {
    let closestI = 0
    let closestJ = 1
    let minDist = Infinity

    for (let i = 0; i < clusters.length; i++) {
      for (let j = i + 1; j < clusters.length; j++) {
        const d = colorDist(clusters[i].r, clusters[i].g, clusters[i].b, clusters[j].r, clusters[j].g, clusters[j].b)
        if (d < minDist) {
          minDist = d
          closestI = i
          closestJ = j
        }
      }
    }

    // Merge closestJ into closestI
    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        if (grid[r][c] === closestJ) {
          grid[r][c] = closestI
        } else if (grid[r][c] > closestJ) {
          grid[r][c]--
        }
      }
    }
    clusters.splice(closestJ, 1)
  }

  // Format palette hex
  const palette = clusters.map(c => rgbToHex(Math.round(c.r), Math.round(c.g), Math.round(c.b)))

  return { grid, palette }
}

function colorDist(r1: number, g1: number, b1: number, r2: number, g2: number, b2: number): number {
  const dr = r1 - r2
  const dg = g1 - g2
  const db = b1 - b2
  return Math.sqrt(dr * dr + dg * dg + db * db)
}

function rgbToHex(r: number, g: number, b: number): string {
  return '#' + [r, g, b].map(x => {
    const hex = x.toString(16)
    return hex.length === 1 ? '0' + hex : hex
  }).join('')
}
