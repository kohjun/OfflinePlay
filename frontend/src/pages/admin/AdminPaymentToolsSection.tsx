import { useState } from 'react'
import { forceRefundTicket } from '../../api/payments'
import { Badge } from '../../components/Badge'
import { useToast } from '../../hooks/useToast'
import type { AdminForcedRefundResponse, PaymentProvider } from '../../types'

/**
 * PR106 — ADMIN 운영 결제 도구.
 *
 * 현재 도구 1종:
 *  - **강제 환불** — USED / 시작 후 PAID 티켓을 전액 환불. 일반 환불 경로의 deadline/USED 가드를
 *    우회한다. 운영자가 ticketId + 사유(필수) 입력 → confirm → backend POST.
 *
 * 입력 정책:
 *  - ticketId: 숫자 + 양수
 *  - reason: 1~500자 (backend 도 동일 가드)
 *  - 두 필드 모두 채워야 버튼 활성
 *
 * 결과 표시:
 *  - 마지막 처리 결과를 카드 하단에 표시. 처리 후 form 은 리셋하지 않는다 — 운영자가 같은
 *    티켓에 대해 status 등을 다시 확인할 수 있게.
 *
 * PR111 — UX 보강:
 *  - 정책(전액만 / USED 가능 / buyer 알림 미노출) 을 sidebar 노트 + textarea help 로 가시화하고
 *    textarea 와 `aria-describedby` 로 연결.
 *  - confirm 카피를 행위 명세 (전액 / 가드 우회 / audit 기록 / 알림 미노출) 중심으로 강화.
 *    "REFUND" 입력 확인 같은 추가 잠금은 후속 PR 후보로 남김.
 *  - 결과 카드를 labeled grid + `Badge` 로 재배치 + 통화 / 시각 / provider 라벨 매핑.
 *  - 4xx / 5xx 별 사용자 친화 카피 (backend message 는 toast `message` 로 보존).
 *  - input/textarea 에 `id` + `htmlFor`, 결과 카드에 `role="status"` + `aria-live="polite"`.
 */
const PROVIDER_LABEL: Record<PaymentProvider, string> = {
  NONE: 'Mock (테스트)',
  TOSS: '토스페이먼츠',
  PORTONE: 'PortOne',
}

function formatCurrency(amount: number): string {
  return `₩${amount.toLocaleString('ko-KR')}`
}

function formatRefundedAt(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })
}

export function AdminPaymentToolsSection() {
  const { showToast } = useToast()
  const [ticketIdInput, setTicketIdInput] = useState('')
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [lastResult, setLastResult] = useState<AdminForcedRefundResponse | null>(null)

  const ticketIdNum = Number(ticketIdInput)
  const ticketIdValid = ticketIdInput.trim().length > 0 && Number.isInteger(ticketIdNum) && ticketIdNum > 0
  const reasonTrimmed = reason.trim()
  const reasonValid = reasonTrimmed.length >= 1 && reasonTrimmed.length <= 500
  const canSubmit = ticketIdValid && reasonValid && !submitting

  async function handleSubmit() {
    if (!canSubmit) return
    const confirmed = window.confirm(
      [
        `티켓 #${ticketIdNum} 을 강제 환불합니다.`,
        '',
        '· 전액 환불만 가능합니다 (부분 환불 미지원).',
        '· USED / 시작 이후 티켓도 처리됩니다.',
        '· 처리 내역은 감사 로그(audit log)에 기록됩니다.',
        '· 사용자 알림 메시지에는 운영 사유가 노출되지 않습니다.',
        '',
        '계속할까요?',
      ].join('\n'),
    )
    if (!confirmed) return
    setSubmitting(true)
    try {
      const result = await forceRefundTicket(ticketIdNum, reasonTrimmed)
      setLastResult(result)
      showToast({ title: '강제 환불 처리 완료', tone: 'success' })
    } catch (error) {
      const status = (error as { status?: number } | null)?.status
      const title =
        status === 409
          ? '이미 환불되었거나 환불할 수 없는 티켓입니다.'
          : status === 404
            ? '티켓을 찾을 수 없습니다.'
            : status === 403
              ? 'ADMIN 권한이 필요합니다.'
              : status === 502
                ? 'PG 환불 처리에 실패했습니다.'
                : '강제 환불 실패'
      showToast({
        title,
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="section" aria-labelledby="admin-payment-tools-title">
      <div className="section-heading">
        <h2 id="admin-payment-tools-title">운영 결제 도구</h2>
        <span className="muted">강제 환불</span>
      </div>
      <p className="muted" id="admin-forced-refund-intro" style={{ marginBottom: '6px' }}>
        체크인 완료(USED) / 이벤트 시작 후 / 노쇼 보상 등 일반 환불 경로로 처리할 수 없는 케이스를
        전액 환불합니다.
      </p>
      <ul className="ct-forced-refund-notice muted">
        <li>전액 환불만 가능합니다 (부분 환불 미구현).</li>
        <li>USED 티켓 / 시작 이후 PAID 티켓도 처리됩니다 — 일반 환불 가드를 우회합니다.</li>
        <li>처리 내역은 감사 로그(audit log)에 기록됩니다.</li>
      </ul>
      <div className="stack" style={{ gap: '12px' }}>
        <label className="form-field" htmlFor="admin-forced-refund-ticket-id">
          <span>티켓 ID</span>
          <input
            id="admin-forced-refund-ticket-id"
            type="number"
            inputMode="numeric"
            min={1}
            value={ticketIdInput}
            onChange={(e) => setTicketIdInput(e.target.value)}
            disabled={submitting}
            placeholder="예: 12345"
          />
        </label>
        <label className="form-field" htmlFor="admin-forced-refund-reason">
          <span>환불 사유 (필수, 최대 500자)</span>
          <textarea
            id="admin-forced-refund-reason"
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={submitting}
            aria-describedby="admin-forced-refund-reason-help admin-forced-refund-reason-count"
            placeholder="예: 행사 취소 보상, 노쇼 환불 등"
          />
          <span id="admin-forced-refund-reason-help" className="muted">
            운영 사유는 감사 로그(audit log)에 기록되고 사용자 알림에는 노출되지 않습니다.
          </span>
          <span id="admin-forced-refund-reason-count" className="muted">
            {reasonTrimmed.length} / 500
          </span>
        </label>
        <div className="admin-actions">
          <button
            type="button"
            className="button button-primary"
            onClick={handleSubmit}
            disabled={!canSubmit}
            aria-busy={submitting}
          >
            {submitting ? '처리 중…' : '강제 환불 실행'}
          </button>
        </div>
      </div>

      {lastResult ? (
        <article
          className="card admin-card ct-forced-refund-result"
          role="status"
          aria-live="polite"
          style={{ marginTop: '16px' }}
        >
          <div className="ct-forced-refund-result__head">
            <strong>마지막 처리 결과</strong>
            <Badge tone={lastResult.ticketStatus === 'REFUNDED' ? 'success' : 'neutral'}>
              {lastResult.ticketStatus}
            </Badge>
          </div>
          <div className="ct-forced-refund-result__grid">
            <div>
              <span>티켓 ID</span>
              <strong>#{lastResult.ticketId}</strong>
            </div>
            <div>
              <span>환불 금액</span>
              <strong>{formatCurrency(lastResult.amount)}</strong>
            </div>
            <div>
              <span>결제 수단</span>
              <strong>{PROVIDER_LABEL[lastResult.provider] ?? lastResult.provider}</strong>
            </div>
            <div>
              <span>결제 시도 ID</span>
              <strong>#{lastResult.paymentAttemptId}</strong>
            </div>
            <div>
              <span>처리 시각</span>
              <strong>{formatRefundedAt(lastResult.refundedAt)}</strong>
            </div>
            <div>
              <span>PG 결제 키</span>
              <strong className="ct-audit-snippet">{lastResult.providerPaymentKey ?? '—'}</strong>
            </div>
          </div>
          <div className="ct-forced-refund-result__reason">
            <span className="muted">운영 사유 (감사 로그 기록)</span>
            <p>{lastResult.refundReason}</p>
          </div>
        </article>
      ) : null}
    </section>
  )
}
