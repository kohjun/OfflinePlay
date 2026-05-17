import type { Event, MyParticipation } from '../../types'
import { formatFee } from './eventDetailFormatters'

export interface EventDetailCtaConfig {
  label: string
  tone: 'primary' | 'secondary'
  disabled: boolean
}

interface EventDetailActionPanelProps {
  event: Event
  participation: MyParticipation | null
  cta: EventDetailCtaConfig
  isPaid: boolean
  submittingJoin: boolean
  onNavigate: (path: string) => void
  onApply: () => void
  onPaidApply: () => void
}

/**
 * PR84 — sticky bottom CTA dock. 비로그인이거나 일반 참가자에게만 노출된다는 조건은 parent
 * 에서 결정하므로 본 컴포넌트는 항상 그린다. APPROVED + 티켓 있음이면 "티켓 보기" 로
 * 분기, 그 외에는 cta 설정에 따른 버튼.
 */
export function EventDetailActionPanel({
  event,
  participation,
  cta,
  isPaid,
  submittingJoin,
  onNavigate,
  onApply,
  onPaidApply,
}: EventDetailActionPanelProps) {
  const status = participation?.status ?? null

  return (
    <div className="ct-event-sticky-cta">
      <div className="ct-event-sticky-meta">
        <span className="ct-event-sticky-fee">{formatFee(event.participationFee)}</span>
        <span className="ct-event-sticky-cap">
          {event.currentParticipants}/{event.maxParticipants}명 신청
        </span>
      </div>
      {status === 'APPROVED' && participation?.ticketId ? (
        <button
          type="button"
          className="button button-primary is-block ct-event-cta"
          onClick={() => onNavigate(`/tickets/${participation.ticketId}`)}
        >
          티켓 보기
        </button>
      ) : (
        <button
          type="button"
          className={`button button-${cta.tone} is-block ct-event-cta`}
          disabled={cta.disabled || submittingJoin}
          aria-busy={submittingJoin}
          onClick={isPaid ? onPaidApply : onApply}
        >
          {submittingJoin ? <span className="button-spinner" aria-hidden="true" /> : null}
          {submittingJoin ? '처리 중...' : cta.label}
        </button>
      )}
    </div>
  )
}
