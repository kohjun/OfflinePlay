import { useEffect, useRef, useState } from 'react'
import { confirmPayment } from '../api/payments'
import { useToast } from '../hooks/useToast'

interface PaymentSuccessPageProps {
  onNavigate: (path: string) => void
}

/**
 * `/payments/success` — Toss SDK 결제 성공 콜백 redirect 목적지.
 *
 * URL query: `paymentAttemptId`, `paymentKey`, `orderId`, `amount` (Toss 가 successUrl 에 append).
 * 마운트되자마자 백엔드 confirm 을 호출해 PG 측 결제 승인을 트리거하고,
 * 응답에 포함된 ticketId 로 `/tickets/{id}` 로 이동한다.
 *
 * confirm 이 멱등이라 사용자가 새로고침/뒤로가기로 두 번 들어와도 안전.
 */
export function PaymentSuccessPage({ onNavigate }: PaymentSuccessPageProps) {
  const { showToast } = useToast()
  const [status, setStatus] = useState<'loading' | 'done' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const hasRunRef = useRef(false)

  useEffect(() => {
    if (hasRunRef.current) return
    hasRunRef.current = true

    const params = new URLSearchParams(window.location.search)
    const paymentAttemptId = Number(params.get('paymentAttemptId'))
    const paymentKey = params.get('paymentKey') ?? ''
    const orderId = params.get('orderId') ?? ''
    const amount = Number(params.get('amount') ?? '0')

    if (!Number.isFinite(paymentAttemptId) || paymentAttemptId <= 0 || !paymentKey || !orderId || !amount) {
      setErrorMessage('결제 결과 정보가 올바르지 않습니다.')
      setStatus('error')
      return
    }

    confirmPayment(paymentAttemptId, { paymentKey, orderId, amount })
      .then((response) => {
        showToast({
          title: '결제가 완료되었어요',
          message: `티켓이 발급되었습니다 (${amount.toLocaleString()}원)`,
          tone: 'success',
        })
        setStatus('done')
        if (response.ticketId) {
          onNavigate(`/tickets/${response.ticketId}`)
        } else {
          onNavigate('/my')
        }
      })
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : '결제 승인에 실패했습니다.'
        setErrorMessage(msg)
        setStatus('error')
        showToast({ title: '결제 승인 실패', message: msg, tone: 'danger' })
      })
  }, [onNavigate, showToast])

  if (status === 'loading') {
    return (
      <main className="page empty-state">
        <h1>결제 확인 중...</h1>
        <p className="muted">잠시만 기다려주세요. 티켓을 발급하고 있어요.</p>
      </main>
    )
  }

  if (status === 'error') {
    return (
      <main className="page empty-state">
        <h1>결제 확인에 실패했어요</h1>
        <p className="muted">{errorMessage ?? '잠시 후 다시 시도해주세요.'}</p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate('/')}
        >
          홈으로 돌아가기
        </button>
      </main>
    )
  }

  return null
}

interface PaymentFailPageProps {
  onNavigate: (path: string) => void
}

/**
 * `/payments/fail` — Toss SDK 결제 실패/취소 콜백 redirect 목적지.
 *
 * URL query: `code`, `message`, `orderId` (Toss 가 append).
 * PaymentAttempt 자체는 백엔드의 webhook FAILED 또는 별도 cleanup 으로 정리된다.
 * 본 페이지는 사용자에게 사유를 보여주고 다시 시도/뒤로가기 옵션을 제공한다.
 */
export function PaymentFailPage({ onNavigate }: PaymentFailPageProps) {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code') ?? 'PAY_FAILED'
  const message = params.get('message') ?? '결제가 완료되지 않았어요.'

  return (
    <main className="page empty-state">
      <h1>결제가 완료되지 않았어요</h1>
      <p className="muted">{message}</p>
      <p className="muted" style={{ fontSize: '0.85em' }}>code: {code}</p>
      <button
        type="button"
        className="button button-primary is-block"
        onClick={() => window.history.back()}
      >
        이전 이벤트로 돌아가기
      </button>
      <button
        type="button"
        className="button button-secondary is-block"
        onClick={() => onNavigate('/')}
      >
        홈으로
      </button>
    </main>
  )
}
