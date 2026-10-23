import type { CSSProperties, PointerEvent, ReactNode } from 'react'
import type { HAEntity } from '../api'
import type { NormalizedViews } from '../types'
import { isViewId } from '../types'
import CalendarSection from './CalendarSection'
import ChatPanel, { type UiMsg } from './ChatPanel'
import HAPanel from './HAPanel'
import HATile from './HATile'
import PushPanel from './PushPanel'

/**
 * The content of one board tile (the web's winNode): the view's live
 * pane, or the HA tile bound to its entity. The Android counterpart is
 * the port's TileContent/HaContent (TabletBoardTile.kt).
 */
export function BoardTileContent({
  id,
  views,
  chatMsgs,
  onAppendChat,
  entities,
  haOff,
  onService,
}: {
  id: string
  views: NormalizedViews
  chatMsgs: UiMsg[]
  onAppendChat: (m: Omit<UiMsg, 'id'>) => void
  entities: Record<string, HAEntity>
  haOff: boolean
  onService: () => void
}) {
  if (isViewId(id)) {
    switch (id) {
      case 'calendar':
        return <CalendarSection mode={views.calendar_mode} />
      case 'chat':
        return <ChatPanel msgs={chatMsgs} onAppend={onAppendChat} />
      case 'home':
        return <HAPanel />
      default:
        return <PushPanel />
    }
  }
  const ht = views.ha_tiles.find((t) => t.id === id)
  if (!ht) return null
  return (
    <HATile
      tile={ht}
      entity={entities[ht.entity] ?? null}
      haOff={haOff}
      onService={onService}
    />
  )
}

/**
 * One board tile's chrome (the web's `.tile` card): the edit-mode row
 * (the move label, the drag grip, the settings and remove buttons), the
 * tile's content, and the corner resize handle. The cell placement and
 * the drag/preview state come from the board (App), which owns the
 * pointer math; the tile's content is the caller's node (the web's
 * winNode). The Android counterpart is the port's BoardTile
 * (TabletBoardTile.kt).
 */
interface BoardTileProps {
  id: string
  /** The tile's display name (the board's tileName). */
  name: string
  /** The tile fills the board (the web's data-max). */
  maxed: boolean
  /** A drag/resize is in flight on this tile. */
  dragging: boolean
  /** The live drop would be refused (the danger outline). */
  refused: boolean
  /** The tile's size step (the board's views.sizes entry). */
  size?: string
  /** The board's effective cell placement (undefined while maxed/narrow). */
  style?: CSSProperties
  editMode: boolean
  /** A board write in flight: the remove control is disabled. */
  savingSize: boolean
  onGripDown: (e: PointerEvent<HTMLSpanElement>) => void
  onResizeDown: (e: PointerEvent<HTMLButtonElement>) => void
  onEdit: () => void
  onRemove: () => void
  children: ReactNode
}

export default function BoardTile({
  id,
  name,
  maxed,
  dragging,
  refused,
  size,
  style,
  editMode,
  savingSize,
  onGripDown,
  onResizeDown,
  onEdit,
  onRemove,
  children,
}: BoardTileProps) {
  return (
    <div
      className={[
        'tile',
        maxed ? 'maxed' : '',
        dragging ? 'dragging' : '',
        dragging && refused ? 'preview-invalid' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      data-win={id}
      data-size={size}
      style={style}
    >
      {editMode && (
        <div
          className="tile-edit"
          role="group"
          aria-label={`${name} tile controls`}
        >
          <span className="tile-edit-label">Move</span>
          <span
            className="tile-grip"
            title="Drag to move this tile"
            onPointerDown={onGripDown}
          >
            ⠿
          </span>
          <button
            type="button"
            className="tile-edit-open"
            title={`Edit ${name} settings`}
            aria-label={`Edit ${name} tile settings`}
            onClick={onEdit}
          >
            ✎
          </button>
          <button
            type="button"
            className="tile-remove"
            title="Remove this tile from the board"
            aria-label={`Remove ${name} tile`}
            disabled={savingSize}
            onClick={onRemove}
          >
            ✕
          </button>
        </div>
      )}
      {children}
      {editMode && (
        <button
          type="button"
          className="tile-resize"
          title="Drag to resize (width and height)"
          aria-label={`resize ${name}`}
          onPointerDown={onResizeDown}
        />
      )}
    </div>
  )
}
