// The shared board-layout golden vectors
// (frontend/test-fixtures/board-layout/): the same
// input/expected-output pairs the Go (backend/config/board_vectors_test.go)
// and Android (BoardVectorTest.kt) suites assert against the same domain.
// The fixtures live at the repository root, outside this package's build
// context; they are only imported from this test file (never from app
// code), so the app bundle never contains them. (See the fixture
// directory's README for the per-file contract and which suite asserts
// which group.)
import autoLayoutJson from '../test-fixtures/board-layout/auto-layout.json'
import geometryJson from '../test-fixtures/board-layout/geometry.json'
import gridJson from '../test-fixtures/board-layout/grid.json'
import haJson from '../test-fixtures/board-layout/ha-tiles.json'
import stepJson from '../test-fixtures/board-layout/step-vocabulary.json'
import viewsJson from '../test-fixtures/board-layout/views.json'

import { describe, expect, it } from 'vitest'
import type { BoardViews, HABoardTile, TileSize } from './types'
import {
  TILE_SIZE_LABELS,
  isHATileId,
  nextHATileId,
  normalizeGeometry,
  normalizeGrid,
  normalizeHATiles,
  normalizeViews,
  tileName,
} from './utils'
import { autoLayout, stepWidth } from './tileGrid'

interface GridVector {
  name: string
  input: { cols: number; rows: number } | null
  expected: { cols: number; rows: number }
}

describe('grid vectors (board-layout/grid.json)', () => {
  const f = gridJson as { vectors: GridVector[] }
  it.each(f.vectors)('$name', (v) => {
    expect(normalizeGrid(v.input)).toEqual(v.expected)
  })
})

interface Cell {
  x: number
  y: number
  w: number
  h: number
}
interface GeometryVector {
  name: string
  cols: number
  rows: number
  order: string[]
  ids: string[]
  raw: Record<string, unknown>
  expected: Record<string, Cell>
}

describe('geometry vectors (board-layout/geometry.json)', () => {
  const f = geometryJson as unknown as { vectors: GeometryVector[] }
  it.each(f.vectors)('$name', (v) => {
    expect(normalizeGeometry(v.raw, v.order, new Set(v.ids), v.cols, v.rows)).toEqual(v.expected)
  })
})

interface ViewsVector {
  name: string
  input: { grid?: { cols: number; rows: number }; views?: BoardViews }
  expected: { grid: { cols: number; rows: number }; views: BoardViews }
}

describe('views vectors (board-layout/views.json)', () => {
  const f = viewsJson as unknown as { vectors: ViewsVector[] }
  it.each(f.vectors)('$name', (v) => {
    const grid = normalizeGrid(v.input.grid ?? null)
    const got = normalizeViews(v.input.views ?? null, grid)
    expect(grid).toEqual(v.expected.grid)
    expect(got).toEqual(v.expected.views)
  })
})

interface HAVectorFile {
  id_shape: { id: string; valid: boolean }[]
  normalize: { name: string; input: HABoardTile[]; expected: HABoardTile[] }[]
  next_id: { name: string; tiles: HABoardTile[]; expected: string }[]
  display_names: { name: string; id: string; tile: HABoardTile | null; expected: string }[]
}

describe('HA tile vectors (board-layout/ha-tiles.json)', () => {
  const f = haJson as HAVectorFile

  it.each(f.id_shape)('$id is $valid', (v) => {
    expect(isHATileId(v.id)).toBe(v.valid)
  })

  describe('normalizeHATiles', () => {
    it.each(f.normalize)('$name', (v) => {
      expect(normalizeHATiles(v.input)).toEqual(v.expected)
    })
  })

  describe('nextHATileId', () => {
    it.each(f.next_id)('$name', (v) => {
      expect(nextHATileId(v.tiles)).toBe(v.expected)
    })
  })

  describe('tileName (the display-name copy; converges on the server no-trim rule)', () => {
    it.each(f.display_names)('$name', (v) => {
      const views: BoardViews = {
        order: [],
        hidden: [],
        calendar_mode: 'month',
        sizes: {},
        ha_tiles: v.tile ? [v.tile] : [],
      }
      expect(tileName(v.id, views)).toBe(v.expected)
    })
  })
})

interface StepVectorFile {
  steps: { step: string; label: string; fraction: { num: number; den: number } }[]
  rejects: string[]
  widths: { name?: string; size: string | null; cols: number; expected: number }[]
}

describe('step vocabulary vectors (board-layout/step-vocabulary.json)', () => {
  const f = stepJson as StepVectorFile

  it.each(f.steps)('$step is labeled "$label"', (v) => {
    expect(TILE_SIZE_LABELS[v.step as TileSize]).toBe(v.label)
  })

  it('the vocabulary is exactly the four steps', () => {
    expect(Object.keys(TILE_SIZE_LABELS).sort()).toEqual(
      f.steps.map((s) => s.step).sort(),
    )
    for (const r of f.rejects) {
      expect(TILE_SIZE_LABELS[r as keyof typeof TILE_SIZE_LABELS]).toBeUndefined()
    }
  })

  describe('stepWidth', () => {
    it.each(f.widths)('$name / $size on $cols cols', (v) => {
      expect(stepWidth(v.size ?? undefined, v.cols)).toBe(v.expected)
    })
  })
})

interface AutoLayoutVector {
  name: string
  cols: number
  order: string[]
  sizes: Record<string, string>
  fixed: Record<string, Cell>
  expected: Record<string, Cell>
}

describe('auto-layout vectors (board-layout/auto-layout.json)', () => {
  const f = autoLayoutJson as unknown as { vectors: AutoLayoutVector[] }
  it.each(f.vectors)('$name', (v) => {
    expect(autoLayout(v.order, v.sizes, v.fixed, v.cols)).toEqual(v.expected)
  })
})
