import { Badge } from '../../components/Badge'
import type { Event, MyParticipation } from '../../types'
import {
  CONTENT_TYPE_LABEL,
  PARTICIPATION_LABEL,
  PARTICIPATION_TONE,
  STATUS_LABEL,
  statusTone,
  stroke,
} from './eventDetailFormatters'

interface EventDetailHeroSectionProps {
  event: Event
  participation: MyParticipation | null
  isFull: boolean
  isClosed: boolean
  submittingJoin: boolean
  onBack: () => void
  onCancel: () => void
}

/**
 * PR84 — EventDetailPage 의 hero/header 영역만 떼어낸 presentational 컴포넌트.
 * 상태 변경/네트워크 호출은 모두 parent 가 소유. 여기서는 props 와 콜백만 받아 그린다.
 *
 * - 뒤로가기 버튼 (back → channel detail)
 * - 메인 이미지 + SOLD OUT 스탬프
 * - badge row + 제목 + 요약
 * - 본인 participation 상태 row (취소 버튼 / 환불 안내 등)
 */
export function EventDetailHeroSection({
  event,
  participation,
  isFull,
  isClosed,
  submittingJoin,
  onBack,
  onCancel,
}: EventDetailHeroSectionProps) {
  const status = participation?.status ?? null

  return (
    <>
      <button
        type="button"
        className="ct-back-btn"
        onClick={onBack}
        aria-label="뒤로"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
          <path d="m15 5-7 7 7 7" />
        </svg>
      </button>

      <section className={`ct-event-hero${isFull && !isClosed ? ' is-soldout' : ''}`}>
        {event.mainImageUrl ? (
          <img src={event.mainImageUrl} alt="" onError={(e) => (e.currentTarget.style.display = 'none')} />
        ) : (
          <div className="ct-event-hero-placeholder" aria-hidden="true">
            {event.title.slice(0, 1).toUpperCase()}
          </div>
        )}
        {isFull && !isClosed ? (
          <span className="ct-event-soldout" aria-label="정원 마감">SOLD OUT</span>
        ) : null}
      </section>

      <header className="ct-event-head">
        <div className="badge-row">
          <Badge tone={statusTone(event.status)}>{STATUS_LABEL[event.status]}</Badge>
          {event.contentType ? <Badge tone="primary">{CONTENT_TYPE_LABEL[event.contentType]}</Badge> : null}
          <Badge tone="neutral">{event.channelName}</Badge>
          {/* PR47: hero 에 별점 칩 — 후기 1건 이상일 때만. 0건은 아래 후기 섹션 summary 에 위임. */}
          {event.averageRating != null && (event.reviewCount ?? 0) > 0 ? (
            <span className="ct-rating-chip" aria-label={`평균 별점 ${event.averageRating.toFixed(1)}, 후기 ${event.reviewCount}건`}>
              <span aria-hidden="true">★</span>
              <strong>{event.averageRating.toFixed(1)}</strong>
              <span className="muted">({event.reviewCount})</span>
            </span>
          ) : null}
        </div>
        <h1 className="ct-event-title">{event.title}</h1>
        <p className="ct-event-summary">{event.description}</p>
        {status && status !== 'CANCELED' ? (
          <div className="ct-my-participation" role="status">
            <Badge tone={PARTICIPATION_TONE[status]}>{PARTICIPATION_LABEL[status]}</Badge>
            {status === 'REJECTED' && participation?.rejectReason ? (
              <span className="muted">사유: {participation.rejectReason}</span>
            ) : null}
            {status === 'PENDING' ? (
              <button
                type="button"
                className="text-button"
                onClick={onCancel}
                disabled={submittingJoin}
              >
                {submittingJoin ? '취소 중...' : '신청 취소'}
              </button>
            ) : null}
            {status === 'APPROVED' &&
            new Date(event.startAt).getTime() > Date.now() &&
            participation?.ticketStatus !== 'USED' &&
            participation?.ticketStatus !== 'REFUNDED' &&
            participation?.ticketStatus !== 'CANCELED' ? (
              event.participationFee > 0 ? (
                <span className="muted">취소·환불은 티켓 페이지에서 진행해주세요</span>
              ) : (
                <button
                  type="button"
                  className="text-button"
                  onClick={onCancel}
                  disabled={submittingJoin}
                >
                  {submittingJoin ? '취소 중...' : '참가 취소'}
                </button>
              )
            ) : null}
          </div>
        ) : null}
      </header>
    </>
  )
}
