import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import {
  applyEvent,
  approveParticipation,
  cancelEventApplication,
  getEvent,
  getEventById,
  getEventComments,
  getMyParticipation,
  listEventApplicants,
  postEventComment,
  rejectParticipation,
} from '../api/events'
import { getEventCheckIns, type EventCheckInSummary } from '../api/tickets'
import {
  createReview,
  deleteReview,
  getEventReviews,
  getEventReviewSummary,
  getMyReview,
  updateReview,
  type EventReviewSummary,
  type Review,
} from '../api/reviews'
import { createReport } from '../api/reports'
import { RemainingProgress } from '../components/RemainingProgress'
import { ReviewForm } from '../components/ReviewForm'
import { notificationStore } from '../stores/notificationStore'
import { Badge } from '../components/Badge'
import { CommentForm } from '../components/CommentForm'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type {
  ContentType,
  Event,
  EventApplicant,
  EventComment,
  EventParticipationStatus,
  EventStatus,
  MyParticipation,
} from '../types'

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

const PARTICIPATION_LABEL: Record<EventParticipationStatus, string> = {
  PENDING: '승인 대기 중',
  APPROVED: '참가 확정',
  REJECTED: '신청 거절됨',
  CANCELED: '신청 취소',
}

const PARTICIPATION_TONE: Record<EventParticipationStatus, 'primary' | 'success' | 'danger' | 'neutral'> = {
  PENDING: 'primary',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELED: 'neutral',
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

function statusTone(status: EventStatus) {
  if (status === 'ONGOING') return 'success'
  if (status === 'UPCOMING') return 'primary'
  return 'neutral'
}

function formatFee(fee: number) {
  return fee === 0 ? '무료' : `${fee.toLocaleString()}원`
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

interface MetaTileProps {
  label: string
  value: string
  icon: ReactNode
}

function MetaTile({ label, value, icon }: MetaTileProps) {
  return (
    <div className="ct-event-meta-tile">
      <span className="ct-event-meta-icon" aria-hidden="true">{icon}</span>
      <span className="ct-event-meta-label">{label}</span>
      <strong className="ct-event-meta-value">{value}</strong>
    </div>
  )
}

interface EventDetailPageProps {
  /**
   * 이벤트가 속한 채널 id. /channels/{cid}/events/{eid} 에서 진입할 때만 채워진다.
   * flat /events/{eid} (알림/Studio) 진입에서는 undefined 이고, 응답의 event.channelId 로 채운다.
   */
  channelId?: number
  eventId: number
  onNavigate: (path: string) => void
}

export function EventDetailPage({ channelId, eventId, onNavigate }: EventDetailPageProps) {
  const { showToast } = useToast()
  const { user } = useAuth()
  const [event, setEvent] = useState<Event | null>(null)
  const [loading, setLoading] = useState(true)
  const [participation, setParticipation] = useState<MyParticipation | null>(null)
  const [comments, setComments] = useState<EventComment[]>([])
  const [commentDraft, setCommentDraft] = useState('')
  const [submittingComment, setSubmittingComment] = useState(false)
  const [submittingJoin, setSubmittingJoin] = useState(false)
  const [applicants, setApplicants] = useState<EventApplicant[]>([])
  const [reviewingId, setReviewingId] = useState<number | null>(null)
  const [checkInSummary, setCheckInSummary] = useState<EventCheckInSummary | null>(null)
  // PR46 — 후기/별점 상태
  const [reviews, setReviews] = useState<Review[]>([])
  const [reviewSummary, setReviewSummary] = useState<EventReviewSummary>({
    averageRating: null,
    reviewCount: 0,
  })
  const [myReview, setMyReview] = useState<Review | null>(null)
  const [showReviewForm, setShowReviewForm] = useState(false)
  const [submittingReview, setSubmittingReview] = useState(false)

  // ── 초기 로드: 이벤트 + 본인 신청 상태 + 댓글 ────────────────────────────────
  useEffect(() => {
    let alive = true
    const eventFetch = channelId != null ? getEvent(channelId, eventId) : getEventById(eventId)
    Promise.all([
      eventFetch,
      getEventComments(eventId, { size: 20 }),
      getMyParticipation(eventId).catch(() => null as MyParticipation | null),
    ])
      .then(([eventResult, commentPage, myPart]) => {
        if (!alive) return
        setEvent(eventResult)
        setParticipation(myPart)
        setComments(commentPage.content)
      })
      .catch((error) => {
        showToast({
          title: '이벤트를 불러오지 못했습니다',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
      .finally(() => {
        if (alive) setLoading(false)
      })

    return () => {
      alive = false
    }
  }, [channelId, eventId, showToast])

  // ── 후기/별점 로드 (인증/비인증 모두) ────────────────────────────────────────
  // summary 와 list 는 비로그인도 접근 가능. myReview 는 로그인 시에만 호출.
  useEffect(() => {
    let alive = true
    Promise.all([
      getEventReviewSummary(eventId).catch(() => ({ averageRating: null, reviewCount: 0 } as EventReviewSummary)),
      getEventReviews(eventId, { size: 20 }).catch(() => null),
    ]).then(([summary, listPage]) => {
      if (!alive) return
      setReviewSummary(summary)
      if (listPage) setReviews(listPage.content)
    })
    // 로그인 사용자만 본인 후기 조회 — 미존재면 null.
    if (user) {
      getMyReview(eventId)
        .then((mine) => {
          if (alive) setMyReview(mine)
        })
        .catch(() => {
          /* USED 티켓 없거나 미작성 — non-fatal */
        })
    }
    return () => {
      alive = false
    }
  }, [eventId, user])

  // 후기 작성/수정/삭제 핸들러
  async function handleReviewSubmit(rating: number, content: string) {
    if (submittingReview) return
    setSubmittingReview(true)
    try {
      const saved = myReview
        ? await updateReview(myReview.id, { rating, content })
        : await createReview(eventId, { rating, content })
      setMyReview(saved)
      setShowReviewForm(false)
      // 목록과 summary 도 다시 받아온다 (낙관 갱신 대신 단순 refetch).
      const [summary, listPage] = await Promise.all([
        getEventReviewSummary(eventId),
        getEventReviews(eventId, { size: 20 }),
      ])
      setReviewSummary(summary)
      setReviews(listPage.content)
      showToast({ title: myReview ? '후기를 수정했어요' : '후기가 등록되었어요', tone: 'success' })
    } catch (error) {
      const status = (error as { status?: number } | null)?.status
      const title =
        status === 403
          ? '체크인 완료자만 후기를 쓸 수 있어요'
          : status === 409
            ? '이미 후기를 작성했어요'
            : '후기 저장에 실패했어요'
      showToast({
        title,
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmittingReview(false)
    }
  }

  /**
   * PR48 — 후기 신고. 본인 글은 카드에서 버튼을 숨기므로 여기까지 오면 타인 글.
   * 비로그인은 진입 자체가 안 되지만 (button 안 보임), 방어선으로 user 가드 추가.
   * 사유는 prompt() 로 받는다 — 모달은 작은 컴포넌트 과설계 금지 원칙에 맞춰 보류.
   */
  async function handleReportReview(review: Review) {
    if (!user) {
      showToast({ title: '로그인이 필요해요', tone: 'warning' })
      return
    }
    const reason = window.prompt('신고 사유를 간단히 적어주세요. (500자 이내)', '')
    if (reason == null) return
    const trimmed = reason.trim()
    if (trimmed.length === 0) {
      showToast({ title: '신고 사유를 입력해주세요', tone: 'warning' })
      return
    }
    try {
      await createReport({ targetType: 'REVIEW', targetId: review.id, reason: trimmed })
      showToast({ title: '신고가 접수되었습니다', tone: 'success' })
    } catch (error) {
      const status = (error as { status?: number } | null)?.status
      const title =
        status === 409
          ? '이미 신고한 후기입니다'
          : status === 400
            ? '본인이 작성한 후기는 신고할 수 없어요'
            : status === 404
              ? '후기를 찾을 수 없어요'
              : '신고 처리에 실패했어요'
      showToast({
        title,
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  async function handleReviewDelete() {
    if (!myReview) return
    if (!window.confirm('후기를 삭제할까요? 삭제하면 복구할 수 없어요.')) return
    try {
      await deleteReview(myReview.id)
      setMyReview(null)
      setShowReviewForm(false)
      const [summary, listPage] = await Promise.all([
        getEventReviewSummary(eventId),
        getEventReviews(eventId, { size: 20 }),
      ])
      setReviewSummary(summary)
      setReviews(listPage.content)
      showToast({ title: '후기를 삭제했어요', tone: 'info' })
    } catch (error) {
      showToast({
        title: '삭제에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  // ── owner 면 신청자 목록 + 체크인 현황도 로드 ───────────────────────────────
  const isOwner = Boolean(event && user && event.channelOwnerId === user.userId)
  // ADMIN/STAFF 도 체크인 현황을 볼 수 있어야 하지만, 현재는 isOwner 시점에만 로드.
  // STAFF UI 가 채널 상세에서만 노출되므로 EventDetail 에서는 owner 우선 노출.
  useEffect(() => {
    if (!isOwner) return
    let alive = true
    listEventApplicants(eventId)
      .then((list) => {
        if (alive) setApplicants(list)
      })
      .catch(() => {
        if (alive) setApplicants([])
      })
    getEventCheckIns(eventId)
      .then((summary) => {
        if (alive) setCheckInSummary(summary)
      })
      .catch(() => {
        if (alive) setCheckInSummary(null)
      })
    return () => {
      alive = false
    }
  }, [isOwner, eventId])

  // Studio 버튼이 /events/{eid}#applicants 또는 #check-ins 로 진입할 때 해당 섹션으로 스크롤.
  useEffect(() => {
    if (!isOwner || loading) return
    if (typeof window === 'undefined') return
    const hash = window.location.hash
    const targetId = hash === '#applicants' ? 'applicants' : hash === '#check-ins' ? 'check-ins' : null
    if (!targetId) return
    // 체크인 섹션은 checkInSummary 로딩이 끝나야 mount 되므로 다음 tick 에 시도.
    const tick = window.setTimeout(() => {
      const el = document.getElementById(targetId)
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 0)
    return () => window.clearTimeout(tick)
  }, [isOwner, loading, checkInSummary])

  const refreshApplicants = useCallback(async () => {
    try {
      const list = await listEventApplicants(eventId)
      setApplicants(list)
    } catch {
      /* non-fatal */
    }
  }, [eventId])

  const refreshCheckInSummary = useCallback(async () => {
    try {
      const summary = await getEventCheckIns(eventId)
      setCheckInSummary(summary)
    } catch {
      /* non-fatal */
    }
  }, [eventId])

  const refreshMyParticipation = useCallback(async () => {
    try {
      const myPart = await getMyParticipation(eventId).catch(() => null as MyParticipation | null)
      setParticipation(myPart)
    } catch {
      /* non-fatal */
    }
  }, [eventId])

  // 항상 최신 event 를 읽을 수 있도록 ref 에 거울. refreshEvent 가 stable callback 이면서도
  // 최신 channelId 를 쓸 수 있게 해준다.
  const eventRef = useRef<Event | null>(null)
  useEffect(() => {
    eventRef.current = event
  }, [event])

  const refreshEvent = useCallback(async () => {
    try {
      // 처음 로드한 channelId 가 없을 수 있으니 (flat 라우트 진입) 응답에서 받은 event.channelId 를 우선 사용한다.
      const cid = eventRef.current?.channelId ?? channelId
      const fresh = cid != null ? await getEvent(cid, eventId) : await getEventById(eventId)
      setEvent(fresh)
    } catch {
      /* non-fatal */
    }
  }, [channelId, eventId])

  // SSE 알림이 짧은 시간에 여러 번 와도 refetch 는 한 번만. 300ms 디바운스로 묶는다.
  // (예: 승인 → 티켓 발급이 백엔드에서 거의 동시에 두 알림으로 도달하는 케이스)
  const refreshTimerRef = useRef<number | null>(null)
  const refreshFlagsRef = useRef({ event: false, my: false, applicants: false, checkIn: false })

  const scheduleRefresh = useCallback(
    (flags: Partial<{ event: boolean; my: boolean; applicants: boolean; checkIn: boolean }>) => {
      if (flags.event) refreshFlagsRef.current.event = true
      if (flags.my) refreshFlagsRef.current.my = true
      if (flags.applicants) refreshFlagsRef.current.applicants = true
      if (flags.checkIn) refreshFlagsRef.current.checkIn = true
      if (refreshTimerRef.current != null) return
      refreshTimerRef.current = window.setTimeout(() => {
        refreshTimerRef.current = null
        const f = refreshFlagsRef.current
        refreshFlagsRef.current = { event: false, my: false, applicants: false, checkIn: false }
        if (f.event) refreshEvent()
        if (f.my) refreshMyParticipation()
        if (f.applicants) refreshApplicants()
        if (f.checkIn) refreshCheckInSummary()
      }, 300)
    },
    [refreshApplicants, refreshCheckInSummary, refreshEvent, refreshMyParticipation],
  )

  // 언마운트 시 펜딩 타이머 정리.
  useEffect(() => {
    return () => {
      if (refreshTimerRef.current != null) {
        window.clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
    }
  }, [])

  // SSE 알림 수신 시 이 이벤트에 관련된 데이터만 refetch (이벤트 본문 포함 — 정원/상태 변화 반영).
  useEffect(() => {
    return notificationStore.onIncoming((n) => {
      // target 이 같은 이벤트가 아니면 무시.
      const isSameEvent = n.targetType === 'events' && n.targetId === eventId
      const isSameTicket =
        n.targetType === 'tickets' && participation?.ticketId === n.targetId
      if (!isSameEvent && !isSameTicket) return

      if (n.type === 'PARTICIPATION_APPROVED' || n.type === 'PARTICIPATION_REJECTED') {
        // 승인/거절 → 본인 상태 + 정원(승인 시) 갱신.
        scheduleRefresh({ event: true, my: true, applicants: isOwner })
      } else if (n.type === 'PARTICIPATION_REQUESTED' || n.type === 'PARTICIPATION_CANCELED') {
        // 새 신청/취소 → owner 화면의 신청자/체크인/정원 갱신.
        scheduleRefresh({ event: true, applicants: isOwner, checkIn: isOwner })
      } else if (n.type === 'TICKET_ISSUED' || n.type === 'TICKET_CHECKED_IN') {
        // 티켓 발급/체크인 → 본인 티켓 + owner 체크인 보드 + 이벤트 본문(체크인 카운트 등).
        scheduleRefresh({ event: true, my: true, checkIn: isOwner })
      }
    })
  }, [eventId, isOwner, participation?.ticketId, scheduleRefresh])

  async function handleSubmitComment() {
    const trimmed = commentDraft.trim()
    if (!trimmed) return
    setSubmittingComment(true)
    try {
      const created = await postEventComment(eventId, trimmed)
      setComments((items) => [created, ...items])
      setCommentDraft('')
    } catch (error) {
      showToast({
        title: '댓글 등록 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmittingComment(false)
    }
  }

  async function handleApply() {
    if (submittingJoin) return
    setSubmittingJoin(true)
    try {
      const next = await applyEvent(eventId)
      setParticipation(next)
      showToast({ title: '참가 신청이 접수되었습니다', message: '기획자 승인을 기다려주세요.', tone: 'success' })
    } catch (error) {
      showToast({
        title: '신청 처리에 실패했습니다',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmittingJoin(false)
    }
  }

  /**
   * 유료 이벤트 결제 흐름.
   *
   * PR47 부터: 결제 페이지(`/events/{eventId}/payment`) 로 라우팅. 결제 페이지가
   * 참가자 정보 폼 + 결제 수단 선택 + 환불정책 동의를 받고 prepare → confirm 호출.
   * 기존 EventDetailPage 내부의 mock confirm fallback 은 결제 페이지로 이관됨.
   */
  function handlePaidApply() {
    onNavigate(`/events/${eventId}/payment`)
  }

  async function handleCancel() {
    if (submittingJoin) return
    const isApproved = participation?.status === 'APPROVED'
    const isPaidEvent = (event?.participationFee ?? 0) > 0
    const confirmMsg = isPaidEvent && isApproved
      ? '참가를 취소할까요? 발급된 티켓이 취소되며 환불 정책에 따라 처리됩니다.'
      : '참가를 취소할까요? 발급된 티켓도 함께 취소됩니다.'
    if (isApproved && !window.confirm(confirmMsg)) return
    setSubmittingJoin(true)
    try {
      const next = await cancelEventApplication(eventId)
      setParticipation(next)
      // 정원 카운트도 즉시 반영해주면 UX 가 더 자연스럽지만, 다음 fetch 또는 refresh 로 보정됨.
      showToast({ title: isApproved ? '참가가 취소되었어요' : '신청이 취소되었어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '취소 처리 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmittingJoin(false)
    }
  }

  async function handleApprove(participationId: number) {
    if (reviewingId !== null) return
    setReviewingId(participationId)
    try {
      await approveParticipation(eventId, participationId)
      showToast({ title: '참가를 승인했습니다', tone: 'success' })
      await Promise.all([refreshApplicants(), refreshEvent()])
    } catch (error) {
      showToast({
        title: '승인 처리 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setReviewingId(null)
    }
  }

  async function handleReject(participationId: number) {
    if (reviewingId !== null) return
    const reasonInput = window.prompt('거절 사유를 입력해주세요 (선택)', '') ?? null
    if (reasonInput === null) return // 사용자가 취소
    setReviewingId(participationId)
    try {
      await rejectParticipation(eventId, participationId, reasonInput || null)
      showToast({ title: '참가를 거절했습니다', tone: 'success' })
      await refreshApplicants()
    } catch (error) {
      showToast({
        title: '거절 처리 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setReviewingId(null)
    }
  }

  if (loading) {
    return (
      <main className="page ct-detail-page">
        <div className="ct-event-hero ct-event-hero-skeleton" aria-hidden="true" />
        <Skeleton lines={4} />
        <Skeleton lines={3} />
      </main>
    )
  }

  if (!event) {
    return (
      <main className="page empty-state">
        <h1>이벤트를 찾을 수 없습니다</h1>
        <p className="muted">삭제되었거나 잘못된 링크일 수 있어요.</p>
        <button className="button button-primary is-block" onClick={() => onNavigate('/')} type="button">
          홈으로 돌아가기
        </button>
      </main>
    )
  }

  // CTA 상태 결정
  const isClosed = event.status === 'CLOSED'
  const isFull = event.currentParticipants >= event.maxParticipants
  const status = participation?.status ?? null
  const isPaid = event.participationFee > 0

  // 유료 이벤트는 prepare+confirm 흐름, 무료는 기존 신청 흐름.
  // CTA 라벨도 분기되며 onClick 은 아래 render 영역에서 isPaid 기준으로 선택한다.
  const cta = (() => {
    if (status === 'PENDING') {
      return { label: '승인 대기 중', tone: 'secondary' as const, disabled: true }
    }
    if (status === 'APPROVED') {
      return { label: '참가 확정', tone: 'secondary' as const, disabled: true }
    }
    if (status === 'REJECTED') {
      return { label: '신청 거절됨', tone: 'secondary' as const, disabled: true }
    }
    // none, CANCELED → 신청/결제 가능 (CLOSED/FULL 검사 후)
    if (isClosed) return { label: '종료된 이벤트', tone: 'primary' as const, disabled: true }
    if (isFull) return { label: '정원 마감', tone: 'primary' as const, disabled: true }
    if (isPaid) {
      return {
        label: `${event.participationFee.toLocaleString()}원 결제하고 참가하기`,
        tone: 'primary' as const,
        disabled: false,
      }
    }
    return {
      label: status === 'CANCELED' ? '다시 신청하기' : '참가 신청하기',
      tone: 'primary' as const,
      disabled: false,
    }
  })()

  const pendingApplicants = applicants.filter((a) => a.status === 'PENDING').length

  return (
    <main className="page ct-detail-page ct-event-detail">
      <button
        type="button"
        className="ct-back-btn"
        onClick={() => onNavigate(`/channels/${event.channelId}`)}
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
                onClick={handleCancel}
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
                  onClick={handleCancel}
                  disabled={submittingJoin}
                >
                  {submittingJoin ? '취소 중...' : '참가 취소'}
                </button>
              )
            ) : null}
          </div>
        ) : null}
      </header>

      <section className="ct-event-meta-grid" aria-label="이벤트 기본 정보">
        <MetaTile
          label="일정"
          value={formatRange(event.startAt, event.endAt)}
          icon={
            <svg viewBox="0 0 24 24" {...stroke}>
              <rect x="3.5" y="5.5" width="17" height="14" rx="2" />
              <path d="M3.5 10h17M8 3.5v3M16 3.5v3" />
            </svg>
          }
        />
        <MetaTile
          label="장소"
          value={event.location}
          icon={
            <svg viewBox="0 0 24 24" {...stroke}>
              <path d="M12 21s-7-5-7-11a7 7 0 1 1 14 0c0 6-7 11-7 11z" />
              <circle cx="12" cy="10" r="2.6" />
            </svg>
          }
        />
        <MetaTile
          label="참가비"
          value={formatFee(event.participationFee)}
          icon={
            <svg viewBox="0 0 24 24" {...stroke}>
              <rect x="3.5" y="6.5" width="17" height="11" rx="2" />
              <circle cx="12" cy="12" r="2.4" />
            </svg>
          }
        />
        <MetaTile
          label="인원"
          value={`${event.currentParticipants} / ${event.maxParticipants}명`}
          icon={
            <svg viewBox="0 0 24 24" {...stroke}>
              <circle cx="9" cy="9" r="3" />
              <path d="M3 19a6 6 0 0 1 12 0" />
              <circle cx="17" cy="8" r="2.4" />
              <path d="M15 19a5 5 0 0 1 6 0" />
            </svg>
          }
        />
      </section>

      {/* 잔여 자리 강조 row (wireframe 07 §남은 자리 row 특별 처리). */}
      {event.status !== 'CLOSED' ? (
        <section className="ed-remaining" aria-label="잔여 자리">
          <div className="ed-remaining__head">
            <span className="ed-remaining__label">남은 자리</span>
            <span className="ed-remaining__live" aria-hidden="true">
              <span className="ed-remaining__live-dot" />
              실시간
            </span>
          </div>
          <RemainingProgress
            remaining={event.maxParticipants - event.currentParticipants}
            capacity={event.maxParticipants}
          />
        </section>
      ) : null}

      <section className="ct-event-section">
        <h2 className="ct-event-section-title">이벤트 소개</h2>
        <article className="card">
          <div className="card-body">
            <p className="ct-event-section-text">{event.detailContent}</p>
          </div>
        </article>
      </section>

      <section className="ct-event-section">
        <h2 className="ct-event-section-title">환불 정책</h2>
        <article className="card">
          <div className="card-body">
            <p className="ct-event-section-text">{event.refundPolicy}</p>
          </div>
        </article>
      </section>

      <section className="ct-event-section ct-reviews-section">
        <div className="section-heading">
          <h2 className="ct-event-section-title">후기</h2>
          {reviewSummary.reviewCount > 0 ? (
            <span className="ct-reviews-summary" aria-label={`평균 별점 ${reviewSummary.averageRating?.toFixed(1)}점, 후기 ${reviewSummary.reviewCount}건`}>
              <span className="ct-reviews-summary__star" aria-hidden="true">★</span>
              <strong>{reviewSummary.averageRating?.toFixed(1) ?? '—'}</strong>
              <span className="muted">({reviewSummary.reviewCount})</span>
            </span>
          ) : (
            <span className="muted">아직 후기가 없어요</span>
          )}
        </div>

        {/* 본인이 USED 티켓 보유 (= ticketStatus === 'USED') 이고 아직 후기 미작성이면 작성 CTA. */}
        {participation?.ticketStatus === 'USED' && !myReview && !showReviewForm ? (
          <button
            type="button"
            className="button button-primary"
            onClick={() => setShowReviewForm(true)}
          >
            후기 남기기
          </button>
        ) : null}

        {/* 본인 후기가 이미 있으면 카드 + 수정/삭제 버튼. showReviewForm 인 경우 폼 우선. */}
        {myReview && !showReviewForm ? (
          <article className="card ct-review-card ct-review-card--mine">
            <div className="card-body">
              <div className="ct-review-card__head">
                <div className="ct-review-card__author">
                  <span className="ct-review-card__stars" aria-hidden="true">
                    {'★'.repeat(myReview.rating)}
                    <span className="ct-review-card__stars-empty">{'★'.repeat(5 - myReview.rating)}</span>
                  </span>
                  <span className="muted">내 후기</span>
                </div>
                <div className="ct-review-card__actions">
                  <button
                    type="button"
                    className="text-button"
                    onClick={() => setShowReviewForm(true)}
                  >
                    수정
                  </button>
                  <button
                    type="button"
                    className="text-button text-button--danger"
                    onClick={handleReviewDelete}
                  >
                    삭제
                  </button>
                </div>
              </div>
              <p className="ct-review-card__content">{myReview.content}</p>
            </div>
          </article>
        ) : null}

        {showReviewForm ? (
          <ReviewForm
            initialRating={myReview?.rating ?? 0}
            initialContent={myReview?.content ?? ''}
            submitting={submittingReview}
            onSubmit={handleReviewSubmit}
            onCancel={() => setShowReviewForm(false)}
          />
        ) : null}

        {/* 다른 사람들의 후기 — 본인 후기는 위에서 별도 노출하므로 목록에서 제외. */}
        {reviews.length > 0 ? (
          <ul className="ct-review-list">
            {reviews
              .filter((r) => r.id !== myReview?.id)
              .map((r) => (
                <li key={r.id} className="card ct-review-card">
                  <div className="card-body">
                    <div className="ct-review-card__head">
                      <div className="ct-review-card__author">
                        <span className="ct-review-card__stars" aria-hidden="true">
                          {'★'.repeat(r.rating)}
                          <span className="ct-review-card__stars-empty">{'★'.repeat(5 - r.rating)}</span>
                        </span>
                        <span className="muted">{r.authorNickname}</span>
                      </div>
                      <div className="ct-review-card__actions">
                        <span className="ct-review-card__date muted">
                          {new Date(r.createdAt).toLocaleDateString()}
                        </span>
                        {/* PR48: 본인 글이 아닐 때만 신고 버튼 노출. user 가 없으면(비로그인) 숨김. */}
                        {user && user.userId !== r.authorId ? (
                          <button
                            type="button"
                            className="text-button text-button--danger"
                            onClick={() => handleReportReview(r)}
                            aria-label="이 후기 신고"
                          >
                            신고
                          </button>
                        ) : null}
                      </div>
                    </div>
                    <p className="ct-review-card__content">{r.content}</p>
                  </div>
                </li>
              ))}
          </ul>
        ) : null}
      </section>

      {isOwner ? (
        <section className="ct-event-section ct-event-owner-actions">
          <button
            type="button"
            className="button button-secondary is-block"
            onClick={() => onNavigate(`/events/${eventId}/edit`)}
          >
            이벤트 수정
          </button>
        </section>
      ) : null}

      {isOwner ? (
        <section id="applicants" className="ct-event-section ct-applicants-section">
          <div className="section-heading">
            <h2 className="ct-event-section-title">
              신청자 관리 {applicants.length > 0 ? `(${applicants.length})` : ''}
            </h2>
            {pendingApplicants > 0 ? (
              <span className="badge badge-primary">대기 {pendingApplicants}</span>
            ) : null}
          </div>
          {applicants.length === 0 ? (
            <div className="ct-applicants-empty">
              <span aria-hidden="true">📭</span>
              <strong>아직 신청자가 없어요</strong>
              <span className="muted">참가 신청이 들어오면 여기에서 승인/거절할 수 있어요.</span>
            </div>
          ) : (
            <ul className="ct-applicants-list">
              {applicants.map((a) => {
                const isReviewing = reviewingId === a.id
                return (
                  <li key={a.id} className="ct-applicant-card">
                    <div className="ct-applicant-head">
                      <div className="ct-applicant-avatar" aria-hidden="true">
                        {a.nickname.slice(0, 1).toUpperCase()}
                      </div>
                      <div className="ct-applicant-meta">
                        <strong>{a.nickname}</strong>
                        <span className="muted">{formatDateTime(a.joinedAt)} 신청</span>
                      </div>
                      <Badge tone={PARTICIPATION_TONE[a.status]}>{PARTICIPATION_LABEL[a.status]}</Badge>
                    </div>
                    {a.rejectReason ? (
                      <p className="ct-applicant-reason">거절 사유: {a.rejectReason}</p>
                    ) : null}
                    {a.status === 'PENDING' ? (
                      <div className="ct-applicant-actions">
                        <button
                          type="button"
                          className="button button-secondary"
                          onClick={() => handleReject(a.id)}
                          disabled={isReviewing}
                          aria-busy={isReviewing}
                        >
                          거절
                        </button>
                        <button
                          type="button"
                          className="button button-primary"
                          onClick={() => handleApprove(a.id)}
                          disabled={isReviewing}
                          aria-busy={isReviewing}
                        >
                          {isReviewing ? <span className="button-spinner" aria-hidden="true" /> : null}
                          승인
                        </button>
                      </div>
                    ) : null}
                    {a.status === 'APPROVED' && a.ticketId ? (
                      <div className="ct-applicant-actions ct-applicant-actions-single">
                        <button
                          type="button"
                          className="button button-secondary"
                          onClick={() => onNavigate(`/tickets/${a.ticketId}`)}
                        >
                          티켓 확인
                        </button>
                      </div>
                    ) : null}
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      ) : null}

      {isOwner && checkInSummary ? (
        <section id="check-ins" className="ct-event-section ct-checkin-section">
          <div className="section-heading">
            <h2 className="ct-event-section-title">체크인 현황</h2>
            <button
              type="button"
              className="text-button"
              onClick={() => onNavigate('/check-in')}
            >
              코드 체크인 →
            </button>
          </div>

          <div className="ct-checkin-summary">
            <div className="ct-checkin-tile">
              <span>발급</span>
              <strong>{checkInSummary.issuedCount}</strong>
            </div>
            <div className="ct-checkin-tile ct-checkin-tile-success">
              <span>체크인</span>
              <strong>{checkInSummary.checkedInCount}</strong>
            </div>
            <div className="ct-checkin-tile">
              <span>미입장</span>
              <strong>{checkInSummary.notCheckedInCount}</strong>
            </div>
          </div>

          {checkInSummary.tickets.length === 0 ? (
            <p className="muted ct-checkin-empty">아직 발급된 티켓이 없어요.</p>
          ) : (
            <ul className="ct-checkin-list">
              {checkInSummary.tickets.map((t) => (
                <li key={t.ticketId} className="ct-checkin-item">
                  <div className="ct-checkin-item-meta">
                    <strong>{t.buyerNickname}</strong>
                    <span className="muted">티켓 #{t.ticketId}</span>
                  </div>
                  <Badge
                    tone={
                      t.status === 'USED'
                        ? 'success'
                        : t.status === 'PAID'
                          ? 'primary'
                          : t.status === 'CANCELED' || t.status === 'REFUNDED'
                            ? 'danger'
                            : 'neutral'
                    }
                  >
                    {t.status === 'USED'
                      ? '체크인'
                      : t.status === 'PAID'
                        ? '미입장'
                        : t.status === 'CANCELED'
                          ? '취소'
                          : t.status === 'REFUNDED'
                            ? '환불'
                            : t.status}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      <section className="ct-event-section">
        <div className="section-heading">
          <h2 className="ct-event-section-title">이벤트 토크</h2>
          <span className="muted">{comments.length}</span>
        </div>
        <CommentForm
          value={commentDraft}
          onChange={setCommentDraft}
          onSubmit={handleSubmitComment}
          submitting={submittingComment}
          placeholder="참가자들과 이야기 나눠보세요"
        />
        <div className="stack">
          {comments.length === 0 ? (
            <p className="muted ct-event-empty-talk">아직 첫 메시지를 기다리고 있어요.</p>
          ) : (
            comments.map((comment) => (
              <article key={comment.id} className="card">
                <div className="card-body">
                  <div className="card-heading-row">
                    <strong>{comment.authorNickname}</strong>
                    <span className="muted">{new Date(comment.createdAt).toLocaleString()}</span>
                  </div>
                  <p>{comment.content}</p>
                </div>
              </article>
            ))
          )}
        </div>
      </section>

      {!isOwner && user?.role !== 'ADMIN' ? (
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
              onClick={isPaid ? handlePaidApply : handleApply}
            >
              {submittingJoin ? <span className="button-spinner" aria-hidden="true" /> : null}
              {submittingJoin ? '처리 중...' : cta.label}
            </button>
          )}
        </div>
      ) : null}
    </main>
  )
}
