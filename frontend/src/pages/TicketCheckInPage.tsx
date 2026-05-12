import { FormEvent, useState } from 'react'
import { checkInByCode } from '../api/tickets'
import { ApiError } from '../api/client'
import { Badge } from '../components/Badge'
import { useToast } from '../hooks/useToast'
import type { TicketDetail, TicketStatus } from '../types'

interface TicketCheckInPageProps {
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

export function TicketCheckInPage({ onNavigate }: TicketCheckInPageProps) {
  const { showToast } = useToast()
  const [code, setCode] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [recent, setRecent] = useState<TicketDetail | null>(null)
  const [inlineError, setInlineError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    const trimmed = code.trim()
    if (!trimmed) {
      setInlineError('체크인 코드를 입력해주세요.')
      return
    }
    setInlineError(null)
    setSubmitting(true)
    try {
      const ticket = await checkInByCode(trimmed)
      setRecent(ticket)
      setCode('')
      showToast({ title: '체크인이 완료되었어요', message: ticket.buyerNickname, tone: 'success' })
    } catch (err) {
      const status = (err as ApiError | null)?.status
      const message = err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.'
      if (status === 400) {
        setInlineError('유효하지 않은 체크인 코드예요. 다시 확인해주세요.')
      } else if (status === 403) {
        setInlineError('체크인 권한이 없거나 본인 티켓은 처리할 수 없어요.')
      } else if (status === 409) {
        setInlineError('이미 사용했거나 사용할 수 없는 티켓이에요.')
      } else {
        showToast({ title: '체크인 실패', message, tone: 'danger' })
      }
    } finally {
      setSubmitting(false)
    }
  }

  async function handlePaste() {
    if (typeof navigator === 'undefined' || !navigator.clipboard?.readText) return
    try {
      const text = await navigator.clipboard.readText()
      setCode(text.trim())
    } catch {
      showToast({ title: '클립보드를 읽을 수 없어요', tone: 'warning' })
    }
  }

  return (
    <main className="page ct-check-in-page">
      <button
        type="button"
        className="ct-back-btn"
        onClick={() => onNavigate('/creator')}
        aria-label="뒤로"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
          <path d="m15 5-7 7 7 7" />
        </svg>
      </button>

      <header className="ct-check-in-header">
        <p className="eyebrow">Check-in</p>
        <h1>티켓 체크인</h1>
        <p className="muted">참가자에게 받은 체크인 코드를 입력해주세요.</p>
      </header>

      <form className="form-section ct-check-in-form" onSubmit={handleSubmit}>
        <label className="ct-check-in-field">
          체크인 코드
          <div className="ct-check-in-input-row">
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="CONTENIDO-12-34"
              inputMode="text"
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              aria-invalid={inlineError ? true : undefined}
            />
            <button type="button" className="button button-secondary" onClick={handlePaste}>
              붙여넣기
            </button>
          </div>
          {inlineError ? <span className="ct-form-error">{inlineError}</span> : null}
        </label>
        <button
          type="submit"
          className="button button-primary is-block"
          disabled={submitting}
          aria-busy={submitting}
        >
          {submitting ? <span className="button-spinner" aria-hidden="true" /> : null}
          {submitting ? '처리 중...' : '체크인 처리'}
        </button>
      </form>

      {recent ? (
        <section className="ct-check-in-result" aria-live="polite">
          <div className="ct-check-in-result-head">
            <strong>최근 체크인</strong>
            <Badge tone={STATUS_TONE[recent.ticketStatus]}>{STATUS_LABEL[recent.ticketStatus]}</Badge>
          </div>
          <h2 className="ct-check-in-result-title">{recent.eventTitle}</h2>
          <ul className="ct-check-in-result-meta">
            <li>
              <span aria-hidden="true">👤</span>
              <span>{recent.buyerNickname}</span>
            </li>
            <li>
              <span aria-hidden="true">📍</span>
              <span>{recent.location}</span>
            </li>
            {recent.usedAt ? (
              <li>
                <span aria-hidden="true">🕒</span>
                <span>체크인 {formatDateTime(recent.usedAt)}</span>
              </li>
            ) : null}
          </ul>
          <button
            type="button"
            className="button button-secondary is-block"
            onClick={() => onNavigate(`/tickets/${recent.ticketId}`)}
          >
            티켓 상세 보기
          </button>
        </section>
      ) : null}
    </main>
  )
}
