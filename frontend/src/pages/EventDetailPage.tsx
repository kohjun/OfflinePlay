import { useEffect, useRef, useState, type ReactNode } from 'react'
import { RemainingProgress } from '../components/RemainingProgress'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { EventAnnouncementsSection } from './event-detail/EventAnnouncementsSection'
import { EventCommentsSection } from './event-detail/EventCommentsSection'
import { EventMannerSection } from './event-detail/EventMannerSection'
import {
  EventDetailActionPanel,
  type EventDetailCtaConfig,
} from './event-detail/EventDetailActionPanel'
import { EventDetailHeroSection } from './event-detail/EventDetailHeroSection'
import { EventOwnerPanel } from './event-detail/EventOwnerPanel'
import { EventReviewsSection } from './event-detail/EventReviewsSection'
import { formatFee, formatRange, stroke } from './event-detail/eventDetailFormatters'
import { useEventDetailActions } from './event-detail/hooks/useEventDetailActions'
import { useEventDetailData } from './event-detail/hooks/useEventDetailData'
import { useEventDetailReviews } from './event-detail/hooks/useEventDetailReviews'

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

/**
 * PR89 — fetch/effect/mutation 로직을 custom hook 3개로 분리한 후의 orchestrator.
 *  - [useEventDetailData]: event/participation/comments/applicants/checkIn + SSE refetch
 *  - [useEventDetailReviews]: 후기 summary/list/myReview + 작성/수정/삭제/신고
 *  - [useEventDetailActions]: 신청/결제/취소/승인/거절/댓글 등록 mutation
 * 본 컴포넌트는 라우트 파라미터를 받아 hook 들을 wiring 하고, cta 라벨 계산 + 섹션 조립만 담당한다.
 */
export function EventDetailPage({ channelId, eventId, onNavigate }: EventDetailPageProps) {
  const { user } = useAuth()

  const {
    event,
    loading,
    participation,
    comments,
    applicants,
    checkInSummary,
    isOwner,
    setParticipation,
    setComments,
    refreshApplicants,
    refreshEvent,
  } = useEventDetailData({ channelId, eventId })

  const {
    reviews,
    reviewSummary,
    myReview,
    showReviewForm,
    setShowReviewForm,
    submittingReview,
    handleReviewSubmit,
    handleReportReview,
    handleReviewDelete,
  } = useEventDetailReviews({ eventId })

  const {
    submittingJoin,
    reviewingId,
    commentDraft,
    setCommentDraft,
    submittingComment,
    handleApply,
    handlePaidApply,
    handleCancel,
    handleApprove,
    handleReject,
    handleSubmitComment,
  } = useEventDetailActions({
    eventId,
    event,
    participation,
    setParticipation,
    setComments,
    refreshApplicants,
    refreshEvent,
    onNavigate,
  })

  // PR91 — 잔여 자리 라이브 강조: event refetch 결과 currentParticipants 가 바뀌면
  // 1.5 초 highlight 를 켠다. 첫 로드(이전 값 없음) 에서는 트리거하지 않는다.
  // prefers-reduced-motion 환경에서는 CSS 가 animation 을 비활성화한다.
  const [remainingHighlight, setRemainingHighlight] = useState(false)
  const prevCurrentParticipantsRef = useRef<number | null>(null)
  useEffect(() => {
    if (!event) return
    const next = event.currentParticipants
    const prev = prevCurrentParticipantsRef.current
    prevCurrentParticipantsRef.current = next
    if (prev === null || prev === next) return
    setRemainingHighlight(true)
    const timer = window.setTimeout(() => setRemainingHighlight(false), 1500)
    return () => window.clearTimeout(timer)
  }, [event?.currentParticipants])

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
  const cta: EventDetailCtaConfig = (() => {
    if (status === 'PENDING') {
      return { label: '승인 대기 중', tone: 'secondary', disabled: true }
    }
    if (status === 'APPROVED') {
      return { label: '참가 확정', tone: 'secondary', disabled: true }
    }
    if (status === 'REJECTED') {
      return { label: '신청 거절됨', tone: 'secondary', disabled: true }
    }
    // none, CANCELED → 신청/결제 가능 (CLOSED/FULL 검사 후)
    if (isClosed) return { label: '종료된 이벤트', tone: 'primary', disabled: true }
    if (isFull) return { label: '정원 마감', tone: 'primary', disabled: true }
    if (isPaid) {
      return {
        label: `${event.participationFee.toLocaleString()}원 결제하고 참가하기`,
        tone: 'primary',
        disabled: false,
      }
    }
    return {
      label: status === 'CANCELED' ? '다시 신청하기' : '참가 신청하기',
      tone: 'primary',
      disabled: false,
    }
  })()

  return (
    <main className="page ct-detail-page ct-event-detail">
      <EventDetailHeroSection
        event={event}
        participation={participation}
        isFull={isFull}
        isClosed={isClosed}
        submittingJoin={submittingJoin}
        onBack={() => onNavigate(`/channels/${event.channelId}`)}
        onCancel={handleCancel}
      />

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
            highlight={remainingHighlight}
            liveLabel={`남은 자리 ${event.maxParticipants - event.currentParticipants}자리`}
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

      <EventReviewsSection
        reviewSummary={reviewSummary}
        reviews={reviews}
        myReview={myReview}
        showReviewForm={showReviewForm}
        submittingReview={submittingReview}
        participation={participation}
        user={user}
        isEventEnded={new Date(event.endAt).getTime() <= Date.now()}
        onShowForm={() => setShowReviewForm(true)}
        onHideForm={() => setShowReviewForm(false)}
        onSubmit={handleReviewSubmit}
        onDelete={handleReviewDelete}
        onReportReview={handleReportReview}
      />

      {isOwner ? (
        <EventOwnerPanel
          eventId={eventId}
          applicants={applicants}
          reviewingId={reviewingId}
          checkInSummary={checkInSummary}
          onNavigate={onNavigate}
          onApprove={handleApprove}
          onReject={handleReject}
        />
      ) : null}

      <EventAnnouncementsSection
        eventId={eventId}
        canWrite={isOwner || user?.role === 'ADMIN'}
        canRead={
          isOwner ||
          user?.role === 'ADMIN' ||
          participation?.status === 'APPROVED'
        }
      />

      <EventCommentsSection
        comments={comments}
        commentDraft={commentDraft}
        submittingComment={submittingComment}
        canAccessRoom={
          isOwner ||
          user?.role === 'ADMIN' ||
          participation?.status === 'APPROVED'
        }
        onDraftChange={setCommentDraft}
        onSubmit={handleSubmitComment}
      />

      {new Date(event.endAt).getTime() <= Date.now() ? (
        <EventMannerSection
          eventId={eventId}
          isOwner={isOwner}
          hostId={event.channelOwnerId}
          hostNickname={event.channelName ?? null}
          applicants={applicants}
          myParticipationStatus={participation?.status ?? null}
        />
      ) : null}

      {!isOwner && user?.role !== 'ADMIN' ? (
        <EventDetailActionPanel
          event={event}
          participation={participation}
          cta={cta}
          isPaid={isPaid}
          submittingJoin={submittingJoin}
          onNavigate={onNavigate}
          onApply={handleApply}
          onPaidApply={handlePaidApply}
        />
      ) : null}
    </main>
  )
}
