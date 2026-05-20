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
 *    우회한다. 운영자가 ticketId + 사유(필수) + "REFUND" 확인 문구 입력 → confirm → backend POST.
 *
 * 입력 정책:
 *  - ticketId: 숫자 + 양수
 *  - reason: 1~500자 (backend 도 동일 가드)
 *  - confirmText: 정확히 "REFUND" (대소문자 구분, 앞뒤 공백 trim) — PR112
 *  - 세 필드 모두 통과해야 버튼 활성
 *
 * 결과 표시:
 *  - 마지막 처리 결과를 카드 하단에 표시. 성공 후 form 세 필드는 모두 비운다 (PR112) — 같은 ticket
 *    에 대한 우발적 재실행을 차단. 결과 카드만 남겨 운영자가 ticketId/금액/시각 등을 다시 확인.
 *  - 실패 시 form state 는 모두 유지 — 운영자가 원인 수정 후 재시도 가능.
 *
 * PR111 — UX 보강:
 *  - 정책(전액만 / USED 가능 / buyer 알림 미노출) 을 sidebar 노트 + textarea help 로 가시화하고
 *    textarea 와 `aria-describedby` 로 연결.
 *  - confirm 카피를 행위 명세 (전액 / 가드 우회 / audit 기록 / 알림 미노출) 중심으로 강화.
 *  - 결과 카드를 labeled grid + `Badge` 로 재배치 + 통화 / 시각 / provider 라벨 매핑.
 *  - 4xx / 5xx 별 사용자 친화 카피 (backend message 는 toast `message` 로 보존).
 *  - input/textarea 에 `id` + `htmlFor`, 결과 카드에 `role="status"` + `aria-live="polite"`.
 *
 * PR112 — 텍스트 확인 잠금:
 *  - reason 아래에 "REFUND" 확인 문구 입력 필드 추가. 정확히 일치할 때만 실행 버튼 활성.
 *  - confirm dialog 는 PR111 의 본문을 그대로 유지 (텍스트 확인이 들어왔으므로 dialog 추가 강화 X).
 *  - confirmText 는 클라이언트 잠금 — API payload 는 PR106 의 `{ reason }` 그대로.
 */
const CONFIRM_PHRASE = 'REFUND'

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

type RefundKind = 'FULL' | 'PARTIAL'

export function AdminPaymentToolsSection() {
  const { showToast } = useToast()
  const [ticketIdInput, setTicketIdInput] = useState('')
  const [reason, setReason] = useState('')
  const [confirmText, setConfirmText] = useState('')
  const [refundKind, setRefundKind] = useState<RefundKind>('FULL')
  const [amountInput, setAmountInput] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [lastResult, setLastResult] = useState<AdminForcedRefundResponse | null>(null)

  const ticketIdNum = Number(ticketIdInput)
  const ticketIdValid = ticketIdInput.trim().length > 0 && Number.isInteger(ticketIdNum) && ticketIdNum > 0
  const reasonTrimmed = reason.trim()
  const reasonValid = reasonTrimmed.length >= 1 && reasonTrimmed.length <= 500
  const confirmTextTrimmed = confirmText.trim()
  const confirmValid = confirmTextTrimmed === CONFIRM_PHRASE
  const confirmInvalid = confirmText.length > 0 && !confirmValid
  // PR134 — refundKind=PARTIAL 이면 amount 1 이상 정수 필요. FULL 이면 amount input 자체가 비활성.
  const amountNum = Number(amountInput)
  const amountValid =
    refundKind === 'FULL' ||
    (amountInput.trim().length > 0 && Number.isInteger(amountNum) && amountNum >= 1)
  const amountInvalid = refundKind === 'PARTIAL' && amountInput.length > 0 && !amountValid
  const canSubmit = ticketIdValid && reasonValid && confirmValid && amountValid && !submitting

  async function handleSubmit() {
    if (!canSubmit) return
    const partialAmount = refundKind === 'PARTIAL' ? amountNum : undefined
    const confirmed = window.confirm(
      [
        `티켓 #${ticketIdNum} 을 강제 환불합니다.`,
        '',
        refundKind === 'FULL'
          ? '· 환불 방식: 남은 환불 가능액 전액 (참가 취소 / 정원 복구 가능)'
          : `· 환불 방식: 부분 환불 ${formatCurrency(amountNum)} (참가 상태 / 정원 유지)`,
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
      const result = await forceRefundTicket(ticketIdNum, reasonTrimmed, partialAmount)
      setLastResult(result)
      setTicketIdInput('')
      setReason('')
      setConfirmText('')
      setRefundKind('FULL')
      setAmountInput('')
      showToast({ title: '강제 환불 처리 완료', tone: 'success' })
    } catch (error) {
      const status = (error as { status?: number } | null)?.status
      const title =
        status === 400
          ? '환불 금액을 확인해주세요.'
          : status === 409
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
        <li>전액 환불은 참가 취소 / 정원 복구로 cascade 됩니다 — 일반 환불과 동일.</li>
        <li>부분 강제 환불은 참가 상태 / 정원이 유지됩니다 (티켓은 PARTIALLY_REFUNDED).</li>
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
        <fieldset className="form-field" aria-describedby="admin-forced-refund-kind-help">
          <legend>환불 방식</legend>
          <label className="radio">
            <input
              type="radio"
              name="refund-kind"
              value="FULL"
              checked={refundKind === 'FULL'}
              onChange={() => { setRefundKind('FULL'); setAmountInput('') }}
              disabled={submitting}
            />
            <span>남은 환불 가능액 전액</span>
          </label>
          <label className="radio">
            <input
              type="radio"
              name="refund-kind"
              value="PARTIAL"
              checked={refundKind === 'PARTIAL'}
              onChange={() => setRefundKind('PARTIAL')}
              disabled={submitting}
            />
            <span>금액 지정 (부분 환불)</span>
          </label>
          <span id="admin-forced-refund-kind-help" className="muted">
            전액 환불은 참가 취소 / 정원 복구로 cascade 됩니다. 부분 환불은 참가 상태와 정원이 유지됩니다.
          </span>
        </fieldset>
        {refundKind === 'PARTIAL' ? (
          <label className="form-field" htmlFor="admin-forced-refund-amount">
            <span>환불 금액 (원)</span>
            <input
              id="admin-forced-refund-amount"
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              value={amountInput}
              onChange={(e) => setAmountInput(e.target.value)}
              disabled={submitting}
              aria-describedby="admin-forced-refund-amount-help"
              aria-invalid={amountInvalid || undefined}
              placeholder="예: 5000"
            />
            <span id="admin-forced-refund-amount-help" className="muted">
              1원 이상의 정수. 남은 환불 가능액을 초과하면 backend 가 400 으로 반려합니다.
            </span>
          </label>
        ) : null}
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
        <label className="form-field" htmlFor="admin-forced-refund-confirm">
          <span>확인 문구</span>
          <input
            id="admin-forced-refund-confirm"
            type="text"
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            value={confirmText}
            onChange={(e) => setConfirmText(e.target.value)}
            disabled={submitting}
            aria-describedby="admin-forced-refund-confirm-help"
            aria-invalid={confirmInvalid || undefined}
            placeholder={CONFIRM_PHRASE}
          />
          <span id="admin-forced-refund-confirm-help" className="muted">
            강제 환불을 실행하려면 <code>{CONFIRM_PHRASE}</code> 를 정확히 입력하세요 (대소문자 구분).
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
            <Badge tone={lastResult.ticketStatus === 'REFUNDED' ? 'success' : 'warning'}>
              {lastResult.ticketStatus}
            </Badge>
            {lastResult.fullRefund != null ? (
              <Badge tone={lastResult.fullRefund ? 'success' : 'warning'}>
                {lastResult.fullRefund ? '전액 환불' : '부분 환불'}
              </Badge>
            ) : null}
          </div>
          <div className="ct-forced-refund-result__grid">
            <div>
              <span>티켓 ID</span>
              <strong>#{lastResult.ticketId}</strong>
            </div>
            <div>
              <span>결제 총액</span>
              <strong>{formatCurrency(lastResult.amount)}</strong>
            </div>
            {lastResult.refundedAmount != null ? (
              <div>
                <span>누적 환불액</span>
                <strong>{formatCurrency(lastResult.refundedAmount)}</strong>
              </div>
            ) : null}
            {lastResult.remainingRefundableAmount != null ? (
              <div>
                <span>남은 환불 가능액</span>
                <strong>{formatCurrency(lastResult.remainingRefundableAmount)}</strong>
              </div>
            ) : null}
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
