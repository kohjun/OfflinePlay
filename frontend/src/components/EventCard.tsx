import type { ContentType, Event, EventStatus } from '../types'
import { Badge } from './Badge'

interface EventCardProps {
  event: Event
  onOpen?: (channelId: number, eventId: number) => void
}

const STATUS_LABEL: Record<EventStatus, string> = {
  UPCOMING: '곧 시작',
  ONGOING: '진행 중',
  CLOSED: '종료',
}

const CONTENT_TYPE_LABEL: Record<ContentType, string> = {
  ORIGINAL: 'Original',
  CLASSIC: 'Classic',
  SPECIAL: 'Special',
}

function eventTone(status: EventStatus) {
  if (status === 'ONGOING') return 'success'
  if (status === 'UPCOMING') return 'primary'
  return 'neutral'
}

function formatFee(fee: number) {
  return fee === 0 ? '무료' : `${fee.toLocaleString()}원`
}

function formatStartAt(startAt: string) {
  const d = new Date(startAt)
  // 모바일에서는 분 단위까지만, 연도는 다음 해 이벤트가 아니면 생략
  const now = new Date()
  const sameYear = d.getFullYear() === now.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return sameYear ? `${month}.${day} ${hh}:${mm}` : `${d.getFullYear()}.${month}.${day} ${hh}:${mm}`
}

export function EventCard({ event, onOpen }: EventCardProps) {
  const starts = formatStartAt(event.startAt)
  const capacity = `${event.currentParticipants}/${event.maxParticipants}명`
  const fee = formatFee(event.participationFee)

  function handleOpen() {
    onOpen?.(event.channelId, event.id)
  }

  return (
    <article className="card event-card ct-event-card" onClick={handleOpen}>
      <button
        type="button"
        className="ct-event-card-media"
        onClick={(e) => {
          e.stopPropagation()
          handleOpen()
        }}
        aria-label={`${event.title} 상세 보기`}
      >
        {event.mainImageUrl ? (
          <img className="wide-thumb" src={event.mainImageUrl} alt="" />
        ) : (
          <span className="ct-event-card-placeholder">{event.title.slice(0, 1).toUpperCase()}</span>
        )}
        <Badge tone={eventTone(event.status)}>{STATUS_LABEL[event.status]}</Badge>
      </button>
      <div className="card-body">
        <div className="badge-row">
          <Badge tone="neutral">{event.channelName}</Badge>
          {event.contentType ? <Badge tone="primary">{CONTENT_TYPE_LABEL[event.contentType]}</Badge> : null}
        </div>
        <h3 className="ct-event-card-title">{event.title}</h3>
        <p className="event-card-desc">{event.description}</p>
        <ul className="ct-event-card-meta" aria-label="이벤트 정보">
          <li>
            <span aria-hidden="true">📅</span>
            <span>{starts}</span>
          </li>
          <li>
            <span aria-hidden="true">📍</span>
            <span>{event.location}</span>
          </li>
          <li>
            <span aria-hidden="true">👥</span>
            <span>{capacity}</span>
          </li>
          <li>
            <span aria-hidden="true">🎟</span>
            <span>{fee}</span>
          </li>
        </ul>
      </div>
    </article>
  )
}
