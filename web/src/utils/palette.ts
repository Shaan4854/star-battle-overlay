import { ColorDef } from '../types'

export const DEFAULT_PALETTE: ColorDef[] = [
  { id: 0, name: 'Amber', hex: '#F59E0B', border: '#D97706' },
  { id: 1, name: 'Sky', hex: '#38BDF8', border: '#0284C7' },
  { id: 2, name: 'Emerald', hex: '#34D399', border: '#059669' },
  { id: 3, name: 'Rose', hex: '#FB7185', border: '#E11D48' },
  { id: 4, name: 'Violet', hex: '#A78BFA', border: '#7C3AED' },
  { id: 5, name: 'Orange', hex: '#FB923C', border: '#EA580C' },
  { id: 6, name: 'Lime', hex: '#A3E635', border: '#65A30D' },
  { id: 7, name: 'Fuchsia', hex: '#E879F9', border: '#C026D3' },
  { id: 8, name: 'Teal', hex: '#2DD4BF', border: '#0D9488' },
  { id: 9, name: 'Indigo', hex: '#818CF8', border: '#4F46E5' },
  { id: 10, name: 'Yellow', hex: '#FACC15', border: '#CA8A04' },
  { id: 11, name: 'Cyan', hex: '#22D3EE', border: '#0891B2' },
  { id: 12, name: 'Pink', hex: '#F472B6', border: '#DB2777' },
  { id: 13, name: 'Mint', hex: '#4ADE80', border: '#16A34A' },
]

export function getColorById(id: number, customPalette?: string[]): ColorDef {
  if (customPalette && customPalette[id]) {
    return {
      id,
      name: `Color ${id + 1}`,
      hex: customPalette[id],
      border: adjustColorBrightness(customPalette[id], -20)
    }
  }
  const index = Math.abs(id) % DEFAULT_PALETTE.length
  return DEFAULT_PALETTE[index]
}

function adjustColorBrightness(hex: string, percent: number): string {
  const num = parseInt(hex.replace('#', ''), 16)
  const amt = Math.round(2.55 * percent)
  const R = (num >> 16) + amt
  const G = (num >> 8 & 0x00FF) + amt
  const B = (num & 0x0000FF) + amt
  return '#' + (0x1000000 + (R < 255 ? R < 1 ? 0 : R : 255) * 0x10000 +
    (G < 255 ? G < 1 ? 0 : G : 255) * 0x100 +
    (B < 255 ? B < 1 ? 0 : B : 255)).toString(16).slice(1)
}
