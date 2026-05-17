import { useEffect, useState, type FormEvent } from 'react'
import { getEventById, getMyParticipation } from '../api/events'
import { confirmPayment, preparePayment } from '../api/payments'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import { loadTossPayments, tossClientKey } from '../utils/toss'
import type { Event, MyParticipation } from '../types'

interface PaymentPageProps {
  eventId: number
  onNavigate: (path: string) => void
}

type Method = 'card' | 'kakao' | 'naver' | 'toss'

interface MethodOption {
  key: Method
  label: string
  bg: string
  fg: string
  initial: string
}

const METHODS: MethodOption[] = [
  { key: 'card', label: '카드 결제', bg: '#F4F4F5', fg: '#1A1A1A', initial: 'C' },
  { key: 'kakao', label: '카카오페이', bg: '#FEE500', fg: '#191919', initial: 'K' },
  { key: 'naver', label: '네이버페이', bg: '#03C75A', fg: '#FFFFFF', initial: 'N' },
  { key: 'toss', label: '토스페이', bg: '#0064FF', fg: '#FFFFFF', initial: 'T' },
]

interface Attendee {
  id: number
  name: string
  phone: string
  email: string
  birth: string
}

const MAX_ATTENDEES = 6

function formatFee(fee: number) {
  return fee === 0 ? '무료' : `₩${fee.toLocaleString()}`
}

/**
 * 화면 08 — 결제 / 환불정책 동의 (핸드오프 README §08).
 *
 * 정보 구조 (wireframe 그대로):
 *  - 참가자 정보 폼 (이름/연락처/이메일/생년월일) + "+ 참가자 추가" 최대 6명
 *  - 결제 수단 라디오 4종 (카드 / 카카오 / 네이버 / 토스)
 *  - 환불정책 동의 체크박스
 *  - 결제 금액 요약 카드
 *  - BottomCtaDock + "결제하기" CTA
 *
 * 비즈니스 단순화 (PR47 단계):
 *  - 다인 참가/결제 수단 선택은 시각만 (백엔드 미지원). 실제 결제는 단일 결제 흐름.
 *  - 환불정책 미동의 시 CTA 비활성.
 *  - 결제 흐름: preparePayment → (Toss SDK 또는 mock) confirmPayment → /tickets/{id}.
 *  - Confirm Bottom Sheet 는 후속 미세 작업으로 두고, window.confirm 으로 일단 처리.
 */
export function PaymentPage({ eventId, onNavigate }: PaymentPageProps) {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [event, setEvent] = useState<Event | null>(null)
  // PR82 — 결제 페이지 진입 시 현재 참가/티켓 상태를 미리 가져와 backend
  // validatePrepareable 가드를 친화적으로 우회. null = 아직 신청 안 함.
  const [participation, setParticipation] = useState<MyParticipation | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [attendees, setAttendees] = useState<Attendee[]>([
    {
      id: 0,
      name: user?.nickname ?? '',
      phone: user?.phoneNumber ?? '',
      email: user?.email ?? '',
      birth: '',
    },
  ])
  const [method, setMethod] = useState<Method>('card')
  const [agreed, setAgreed] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    Promise.all([
      getEventById(eventId),
      // participation 조회 실패는 치명적이지 않다 — 그냥 가드 없이 결제 폼 보여줌.
      getMyParticipation(eventId).catch(() => null),
    ])
      .then(([ev, part]) => {
        if (!alive) return
        setEvent(ev)
        setParticipation(part)
      })
      .catch((err: unknown) => {
        if (!alive) return
        setError(err instanceof Error ? err.message : '이벤트를 불러올 수 없어요.')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [eventId])

  function updateAttendee(idx: number, patch: Partial<Attendee>) {
    setAttendees((prev) => prev.map((a, i) => (i === idx ? { ...a, ...patch } : a)))
  }

  function addAttendee() {
    if (attendees.length >= MAX_ATTENDEES) return
    setAttendees((prev) => [
      ...prev,
      { id: Date.now(), name: '', phone: '', email: '', birth: '' },
    ])
  }

  function removeAttendee(idx: number) {
    if (idx === 0) return // 본인은 제거 불가
    setAttendees((prev) => prev.filter((_, i) => i !== idx))
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!event || submitting) return
    if (!agreed) {
      showToast({ title: '환불정책 동의가 필요해요', tone: 'warning' })
      return
    }
    if (!window.confirm(`[${event.title}]\n₩${event.participationFee.toLocaleString()} 결제하시겠어요?`)) return

    setSubmitting(true)
    try {
      const prep = await preparePayment(eventId)
      const clientKey = tossClientKey()
      if (clientKey) {
        const tossPayments = await loadTossPayments(clientKey)
        await tossPayments.requestPayment('카드', {
          amount: prep.amount,
          orderId: prep.idempotencyKey,
          orderName: prep.orderName,
          successUrl: `${window.location.origin}/payments/success?paymentAttemptId=${prep.paymentAttemptId}&eventId=${eventId}`,
          failUrl: `${window.location.origin}/payments/fail?paymentAttemptId=${prep.paymentAttemptId}&eventId=${eventId}`,
        })
        return
      }
      // Mock fallback — sandbox 키 없는 환경
      const result = await confirmPayment(prep.paymentAttemptId, {
        paymentKey: `sandbox-mock-${prep.idempotencyKey}`,
        orderId: prep.idempotencyKey,
        amount: prep.amount,
      })
      showToast({
        title: '결제가 완료되었어요',
        message: `티켓이 발급되었습니다 (₩${prep.amount.toLocaleString()})`,
        tone: 'success',
      })
      if (result.ticketId) {
        onNavigate(`/tickets/${result.ticketId}`)
      } else {
        onNavigate('/my')
      }
    } catch (err: unknown) {
      showToast({
        title: '결제에 실패했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <main className="pay-page">
        <div className="pay-appbar">
          <button type="button" className="pay-appbar__back" onClick={() => window.history.back()} aria-label="뒤로">
            ‹
          </button>
          <span className="pay-appbar__title">티켓 구매</span>
        </div>
        <p className="pay-page__loading">결제 정보를 불러오는 중...</p>
      </main>
    )
  }

  if (error || !event) {
    return (
      <main className="page empty-state">
        <h1>결제를 시작할 수 없어요</h1>
        <p className="muted">{error ?? '이벤트 정보를 찾을 수 없어요.'}</p>
        <button type="button" className="button button-primary is-block" onClick={() => onNavigate('/')}>
          홈으로
        </button>
      </main>
    )
  }

  // PR82 — 결제 진입 가드. backend validatePrepareable 가 어차피 막을 케이스를
  // 토스트 대신 친화적 안내 페이지로 먼저 차단. REFUNDED / CANCELED 티켓은 새 결제
  // 가능하므로 가드에서 제외.
  const liveTicketStatus =
    participation?.ticketStatus === 'PAID' || participation?.ticketStatus === 'USED'
      ? participation.ticketStatus
      : null
  const isClosedEvent = event.status === 'CLOSED'
  const isStartedEvent = new Date(event.startAt).getTime() <= Date.now()
  const isFreeEvent = event.participationFee <= 0
  const isOwner = user?.userId != null && event.channelOwnerId === user.userId

  if (liveTicketStatus && participation?.ticketId != null) {
    return (
      <main className="page empty-state">
        <h1>이미 결제된 티켓이 있어요</h1>
        <p className="muted">
          {liveTicketStatus === 'USED' ? '이미 사용한 티켓이에요.' : '이전 결제로 발급된 티켓을 확인해주세요.'}
        </p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate(`/tickets/${participation.ticketId}`)}
        >
          티켓 보기
        </button>
        <button
          type="button"
          className="button button-secondary is-block"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  if (participation?.status === 'PENDING') {
    return (
      <main className="page empty-state">
        <h1>승인 대기 중인 신청이에요</h1>
        <p className="muted">기획자 승인을 기다리는 중이라 결제를 진행할 수 없어요.</p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  if (participation?.status === 'REJECTED') {
    return (
      <main className="page empty-state">
        <h1>승인이 거절된 신청이에요</h1>
        <p className="muted">기획자가 신청을 거절해 결제를 진행할 수 없어요.</p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  if (isOwner) {
    return (
      <main className="page empty-state">
        <h1>본인이 운영하는 이벤트예요</h1>
        <p className="muted">기획자는 본인 이벤트에 결제로 참가할 수 없어요.</p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  if (isClosedEvent || isStartedEvent) {
    return (
      <main className="page empty-state">
        <h1>{isClosedEvent ? '종료된 이벤트예요' : '이미 시작된 이벤트예요'}</h1>
        <p className="muted">
          {isClosedEvent ? '이벤트가 종료되어 결제를 진행할 수 없어요.' : '이벤트 시작 시각이 지나 결제를 진행할 수 없어요.'}
        </p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  if (isFreeEvent) {
    return (
      <main className="page empty-state">
        <h1>무료 이벤트예요</h1>
        <p className="muted">참가비가 없는 이벤트는 결제 없이 이벤트 상세에서 바로 신청할 수 있어요.</p>
        <button
          type="button"
          className="button button-primary is-block"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  const fee = event.participationFee
  const total = fee // 수수료는 0원으로 가정 (백엔드 미지원)

  return (
    <main className="pay-page">
      <div className="pay-appbar">
        <button type="button" className="pay-appbar__back" onClick={() => window.history.back()} aria-label="뒤로">
          ‹
        </button>
        <span className="pay-appbar__title">티켓 구매</span>
      </div>

      <form className="pay-form" onSubmit={handleSubmit}>
        {/* 1. 참가자 정보 */}
        <section className="pay-section">
          <header className="pay-section__head">
            <h2>참가자 정보</h2>
            <span className="pay-section__step">1 / 3</span>
          </header>
          {attendees.map((a, idx) => (
            <div key={a.id} className="pay-attendee">
              {idx > 0 ? (
                <div className="pay-attendee__head">
                  <span>동행 {idx}</span>
                  <button type="button" className="pay-attendee__remove" onClick={() => removeAttendee(idx)}>
                    제거
                  </button>
                </div>
              ) : (
                <div className="pay-attendee__head">
                  <span>본인</span>
                </div>
              )}
              <label className="pay-field">
                <span>이름</span>
                <input
                  value={a.name}
                  onChange={(e) => updateAttendee(idx, { name: e.target.value })}
                  required
                  placeholder="홍길동"
                />
              </label>
              <label className="pay-field">
                <span>연락처</span>
                <input
                  type="tel"
                  value={a.phone}
                  onChange={(e) => updateAttendee(idx, { phone: e.target.value })}
                  required
                  placeholder="010-1234-5678"
                />
              </label>
              <label className="pay-field">
                <span>이메일</span>
                <input
                  type="email"
                  value={a.email}
                  onChange={(e) => updateAttendee(idx, { email: e.target.value })}
                  required
                  placeholder="you@example.com"
                />
              </label>
              <label className="pay-field">
                <span>생년월일</span>
                <input
                  type="date"
                  value={a.birth}
                  onChange={(e) => updateAttendee(idx, { birth: e.target.value })}
                />
              </label>
            </div>
          ))}
          {attendees.length < MAX_ATTENDEES ? (
            <button type="button" className="pay-add" onClick={addAttendee}>
              + 참가자 추가 (최대 {MAX_ATTENDEES}명)
            </button>
          ) : null}
        </section>

        {/* 2. 결제 수단 (시각만) */}
        <section className="pay-section">
          <header className="pay-section__head">
            <h2>결제 수단</h2>
            <span className="pay-section__step">2 / 3</span>
          </header>
          <div className="pay-methods" role="radiogroup" aria-label="결제 수단 선택">
            {METHODS.map((m) => (
              <label key={m.key} className={`pay-method${method === m.key ? ' is-active' : ''}`}>
                <input
                  type="radio"
                  name="method"
                  checked={method === m.key}
                  onChange={() => setMethod(m.key)}
                />
                <span className="pay-method__radio" aria-hidden="true" />
                <span className="pay-method__logo" style={{ background: m.bg, color: m.fg }}>
                  {m.initial}
                </span>
                <span className="pay-method__label">{m.label}</span>
              </label>
            ))}
          </div>
          <label className="pay-agree">
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
            <span>
              환불정책을 확인했으며, 이에 동의합니다.{' '}
              <button type="button" className="pay-agree__view" onClick={() => onNavigate(`/events/${eventId}`)}>
                환불정책 보기 ›
              </button>
            </span>
          </label>
        </section>

        {/* 3. 결제 금액 요약 */}
        <section className="pay-section">
          <header className="pay-section__head">
            <h2>결제 금액</h2>
            <span className="pay-section__step">3 / 3</span>
          </header>
          <div className="pay-summary">
            <div className="pay-summary__row">
              <span>티켓 금액</span>
              <strong>{formatFee(fee)}</strong>
            </div>
            <div className="pay-summary__row">
              <span>수수료</span>
              <strong>₩0</strong>
            </div>
            <div className="pay-summary__row pay-summary__row--total">
              <span>총 결제 금액</span>
              <strong>{formatFee(total)}</strong>
            </div>
          </div>
        </section>
      </form>

      <nav className="cta-dock">
        <button
          type="button"
          className="button button-primary is-block"
          style={{ height: 56 }}
          onClick={handleSubmit}
          disabled={!agreed || submitting}
          aria-busy={submitting}
        >
          {submitting ? <span className="button-spinner" aria-hidden="true" /> : null}
          {submitting ? '결제 중...' : `${formatFee(total)} 결제하기`}
        </button>
      </nav>
    </main>
  )
}
