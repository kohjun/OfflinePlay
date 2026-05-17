import { useState } from 'react'
import { forceRefundTicket } from '../../api/payments'
import { useToast } from '../../hooks/useToast'
import type { AdminForcedRefundResponse } from '../../types'

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
 */
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
      `티켓 #${ticketIdNum} 을 강제 환불합니다.\nUSED 티켓도 전액 환불됩니다. 계속할까요?`
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
          ? '환불할 수 없는 상태에요'
          : status === 404
            ? '티켓을 찾을 수 없어요'
            : status === 403
              ? '권한이 없어요'
              : status === 502
                ? 'PG 처리에 실패했어요'
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
      <p className="muted" style={{ marginBottom: '12px' }}>
        체크인 완료(USED) / 이벤트 시작 후 / 노쇼 보상 등 일반 환불 경로로 처리할 수 없는 케이스를
        전액 환불합니다. 사유는 감사 로그에 기록됩니다. 부분 환불은 지원하지 않습니다.
      </p>
      <div className="stack" style={{ gap: '12px' }}>
        <label className="form-field">
          <span>티켓 ID</span>
          <input
            type="number"
            inputMode="numeric"
            min={1}
            value={ticketIdInput}
            onChange={(e) => setTicketIdInput(e.target.value)}
            disabled={submitting}
            placeholder="예: 12345"
          />
        </label>
        <label className="form-field">
          <span>환불 사유 (필수, 최대 500자)</span>
          <textarea
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={submitting}
            placeholder="예: 행사 취소 보상, 노쇼 환불 등"
          />
          <span className="muted">{reasonTrimmed.length} / 500</span>
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
        <article className="card admin-card" style={{ marginTop: '16px' }}>
          <div>
            <strong>마지막 처리 결과</strong>
            <div className="meta-row" style={{ flexWrap: 'wrap', gap: '6px 12px' }}>
              <span>티켓 #{lastResult.ticketId}</span>
              <span>상태: {lastResult.ticketStatus}</span>
              <span>금액: {lastResult.amount.toLocaleString()}원</span>
              <span>PG: {lastResult.provider}</span>
              <span>처리: {lastResult.refundedAt}</span>
            </div>
            <p className="muted">사유: {lastResult.refundReason}</p>
          </div>
        </article>
      ) : null}
    </section>
  )
}
