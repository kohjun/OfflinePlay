import { useCallback, useEffect, useState } from 'react'
import { checkInTicket, getTicket } from '../api/tickets'
import { refundTicket } from '../api/payments'
import { Badge } from '../components/Badge'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import { notificationStore } from '../stores/notificationStore'
import type { TicketDetail, TicketStatus } from '../types'

interface TicketDetailPageProps {
  ticketId: number
  onNavigate: (path: string) => void
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

const STATUS_LABEL: Record<TicketStatus, string> = {
  PAID: '발급 완료',
  USED: '사용 완료',
  REFUNDED: '환불됨',
  CANCELED: '취소됨',
}

const STATUS_TONE: Record<TicketStatus, 'primary' | 'success' | 'danger' | 'neutral'> = {
  PAID: 'success',
  USED: 'neutral',
  REFUNDED: 'danger',
  CANCELED: 'neutral',
}

function formatDateTime(value: string) {
  const d = new Date(value)
  const yyyy = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${yyyy}.${month}.${day} ${hh}:${mm}`
}

function formatRange(startAt: string, endAt: string) {
  const start = new Date(startAt)
  const end = new Date(endAt)
  const sameDay =
    start.getFullYear() === end.getFullYear() &&
    start.getMonth() === end.getMonth() &&
    start.getDate() === end.getDate()
  if (sameDay) {
    const hh = String(end.getHours()).padStart(2, '0')
    const mm = String(end.getMinutes()).padStart(2, '0')
    return `${formatDateTime(startAt)} ~ ${hh}:${mm}`
  }
  return `${formatDateTime(startAt)} ~ ${formatDateTime(endAt)}`
}

function formatFee(fee: number) {
  return fee === 0 ? '무료' : `${fee.toLocaleString()}원`
}

export function TicketDetailPage({ ticketId, onNavigate }: TicketDetailPageProps) {
  const { showToast } = useToast()
  const { user } = useAuth()
  const [ticket, setTicket] = useState<TicketDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [forbidden, setForbidden] = useState(false)
  const [checkingIn, setCheckingIn] = useState(false)
  const [refunding, setRefunding] = useState(false)

  const refreshTicket = useCallback(async () => {
    try {
      const data = await getTicket(ticketId)
      setTicket(data)
    } catch {
      /* non-fatal */
    }
  }, [ticketId])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    setForbidden(false)
    getTicket(ticketId)
      .then((data) => {
        if (!alive) return
        setTicket(data)
      })
      .catch((err: unknown) => {
        if (!alive) return
        const status = (err as { status?: number } | null)?.status
        if (status === 403) {
          setForbidden(true)
        } else {
          setError(err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.')
        }
        setTicket(null)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [ticketId])

  // SSE 로 TICKET_CHECKED_IN 같은 이 티켓 관련 알림이 오면 자동 새로고침.
  useEffect(() => {
    return notificationStore.onIncoming((n) => {
      if (n.targetType === 'tickets' && n.targetId === ticketId) {
        refreshTicket()
      }
    })
  }, [ticketId, refreshTicket])

  function handleBack() {
    if (typeof window !== 'undefined' && window.history.length > 1) {
      window.history.back()
    } else {
      onNavigate('/my')
    }
  }

  async function handleCopyCode() {
    if (!ticket) return
    if (typeof navigator === 'undefined' || !navigator.clipboard) return
    try {
      await navigator.clipboard.writeText(ticket.checkInCode)
      showToast({ title: '체크인 코드를 복사했어요', tone: 'success' })
    } catch {
      showToast({ title: '복사에 실패했어요', message: '수기로 입력해주세요.', tone: 'warning' })
    }
  }

  /**
   * 환불 요청. PAID + (buyer 본인 or owner or ADMIN) 일 때만 버튼이 활성화되지만
   * 권한은 백엔드가 최종 판정하므로 UI 는 PAID 상태만 가드한다.
   *
   * 사유 입력은 `window.prompt` 로 간단히 받음. 빈 값이어도 백엔드가 "USER_REQUEST" 로 대체.
   * 백엔드 응답은 멱등(이미 REFUNDED 면 기존 정보 반환)이라 더블 클릭으로도 안전.
   */
  async function handleRefund() {
    if (!ticket || refunding) return
    if (!window.confirm('정말 환불을 진행할까요? 발급된 티켓이 환불 상태로 전환됩니다.')) return
    const reason = window.prompt('환불 사유 (선택)', '') ?? ''
    setRefunding(true)
    try {
      const result = await refundTicket(ticket.ticketId, { reason: reason || null })
      setTicket({ ...ticket, ticketStatus: result.ticketStatus })
      showToast({
        title: '환불이 완료되었어요',
        message: `${result.amount.toLocaleString()}원이 환불 처리되었습니다.`,
        tone: 'success',
      })
    } catch (err) {
      const status = (err as { status?: number } | null)?.status
      const msg = err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.'
      showToast({
        title: status === 403 ? '환불 권한이 없습니다' : '환불 처리에 실패했어요',
        message: msg,
        tone: 'danger',
      })
    } finally {
      setRefunding(false)
    }
  }

  async function handleCheckIn() {
    if (!ticket || checkingIn) return
    if (!window.confirm(`${ticket.buyerNickname}님 티켓을 체크인할까요?`)) return
    setCheckingIn(true)
    try {
      const updated = await checkInTicket(ticket.ticketId)
      setTicket(updated)
      showToast({ title: '체크인이 완료되었어요', tone: 'success' })
    } catch (err) {
      const status = (err as { status?: number } | null)?.status
      const message =
        status === 409
          ? '이미 사용했거나 사용할 수 없는 티켓입니다.'
          : status === 403
            ? '체크인 권한이 없어요.'
            : err instanceof Error
              ? err.message
              : '잠시 후 다시 시도해주세요.'
      showToast({ title: '체크인 실패', message, tone: 'danger' })
    } finally {
      setCheckingIn(false)
    }
  }

  if (loading) {
    return (
      <main className="page ct-ticket-page">
        <div className="ct-ticket-hero ct-ticket-hero-skeleton" aria-hidden="true" />
        <Skeleton lines={4} />
        <Skeleton lines={3} />
      </main>
    )
  }

  if (forbidden) {
    return (
      <main className="page empty-state ct-ticket-page">
        <h1>접근 권한이 없어요</h1>
        <p className="muted">본인 또는 관리자만 티켓을 열어볼 수 있습니다.</p>
        <button className="button button-primary is-block" type="button" onClick={() => onNavigate('/my')}>
          내 페이지로 가기
        </button>
      </main>
    )
  }

  if (!ticket || error) {
    return (
      <main className="page empty-state ct-ticket-page">
        <h1>티켓을 찾을 수 없어요</h1>
        <p className="muted">{error ?? '삭제되었거나 잘못된 링크일 수 있어요.'}</p>
        <button className="button button-primary is-block" type="button" onClick={() => onNavigate('/my')}>
          내 페이지로 가기
        </button>
      </main>
    )
  }

  const isUsable = ticket.ticketStatus === 'PAID'
  // viewer 가 buyer 가 아니고 CREATOR/ADMIN 이면 체크인 시도 가능. 실제 권한(channel owner/STAFF)은 서버가 검증.
  const isStaffViewer = Boolean(
    user && user.userId !== ticket.buyerId && (user.role === 'CREATOR' || user.role === 'ADMIN'),
  )
  const canCheckIn = isStaffViewer && isUsable

  return (
    <main className="page ct-ticket-page">
      <button type="button" className="ct-back-btn" onClick={handleBack} aria-label="뒤로">
        <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
          <path d="m15 5-7 7 7 7" />
        </svg>
      </button>

      <section className={`ct-ticket-hero ${isUsable ? '' : 'is-unusable'}`}>
        <div className="ct-ticket-hero-top">
          <span className="ct-ticket-eyebrow">CONTENIDO TICKET</span>
          <Badge tone={STATUS_TONE[ticket.ticketStatus]}>{STATUS_LABEL[ticket.ticketStatus]}</Badge>
        </div>
        <h1 className="ct-ticket-title">{ticket.eventTitle}</h1>
        <p className="ct-ticket-channel">{ticket.channelName}</p>
      </section>

      <section className={`ct-ticket-pass ${isUsable ? '' : 'is-unusable'}`} aria-label="체크인 코드">
        {!isUsable ? (
          <span
            className={`ct-ticket-stamp${
              ticket.ticketStatus === 'REFUNDED'
                ? ' is-refunded'
                : ticket.ticketStatus === 'CANCELED'
                  ? ' is-canceled'
                  : ''
            }`}
            aria-hidden="true"
          >
            {ticket.ticketStatus === 'REFUNDED'
              ? 'REFUNDED'
              : ticket.ticketStatus === 'CANCELED'
                ? 'VOID'
                : 'USED'}
          </span>
        ) : null}
        <div className="ct-ticket-qr" aria-hidden="true">
          <div className="ct-ticket-qr-grid">
            {Array.from({ length: 9 * 9 }).map((_, i) => {
              // checkInCode 의 byte 값을 9x9 셀의 채움 여부로 매핑한다 — 실제 QR 이 아니라 시각용.
              const code = ticket.checkInCode
              const ch = code.charCodeAt(i % code.length)
              const filled = (ch + i) % 3 !== 0
              return (
                <span
                  key={i}
                  className={`ct-ticket-qr-cell ${filled ? 'is-filled' : ''}`}
                />
              )
            })}
          </div>
        </div>
        <div className="ct-ticket-code">
          <span className="ct-ticket-code-label">체크인 코드</span>
          <code className="ct-ticket-code-value">{ticket.checkInCode}</code>
          <button type="button" className="ct-ticket-code-copy" onClick={handleCopyCode}>
            복사
          </button>
        </div>
        <p className="ct-ticket-hint muted">현장에서 스태프에게 이 코드를 보여주세요.</p>
        {!isUsable ? (
          <p className="ct-ticket-unusable" role="status">
            이미 사용했거나 더 이상 유효하지 않은 티켓입니다.
          </p>
        ) : null}
      </section>

      <section className="ct-ticket-info" aria-label="이벤트 정보">
        <ul>
          <li>
            <span className="ct-ticket-info-label">📅 일정</span>
            <span className="ct-ticket-info-value">{formatRange(ticket.startAt, ticket.endAt)}</span>
          </li>
          <li>
            <span className="ct-ticket-info-label">📍 장소</span>
            <span className="ct-ticket-info-value">{ticket.location}</span>
          </li>
          <li>
            <span className="ct-ticket-info-label">🎟 참가비</span>
            <span className="ct-ticket-info-value">{formatFee(ticket.participationFee)}</span>
          </li>
          <li>
            <span className="ct-ticket-info-label">👤 구매자</span>
            <span className="ct-ticket-info-value">{ticket.buyerNickname}</span>
          </li>
          <li>
            <span className="ct-ticket-info-label">🕒 발급</span>
            <span className="ct-ticket-info-value">{formatDateTime(ticket.purchasedAt)}</span>
          </li>
          {ticket.usedAt ? (
            <li>
              <span className="ct-ticket-info-label">✅ 체크인</span>
              <span className="ct-ticket-info-value">{formatDateTime(ticket.usedAt)}</span>
            </li>
          ) : null}
        </ul>
      </section>

      <section className="ct-ticket-actions">
        {canCheckIn ? (
          <button
            type="button"
            className="button button-primary is-block"
            onClick={handleCheckIn}
            disabled={checkingIn}
            aria-busy={checkingIn}
          >
            {checkingIn ? <span className="button-spinner" aria-hidden="true" /> : null}
            {checkingIn ? '처리 중...' : '체크인 처리'}
          </button>
        ) : null}
        <button
          type="button"
          className="button button-secondary is-block"
          onClick={() => onNavigate(`/events/${ticket.eventId}`)}
        >
          이벤트 상세 보기
        </button>
        {!isStaffViewer && ticket.ticketStatus === 'PAID' ? (
          <button
            type="button"
            className="button button-secondary is-block"
            onClick={handleRefund}
            disabled={refunding}
            aria-busy={refunding}
          >
            {refunding ? <span className="button-spinner" aria-hidden="true" /> : null}
            {refunding ? '환불 처리 중...' : '환불 요청'}
          </button>
        ) : null}
      </section>
    </main>
  )
}
