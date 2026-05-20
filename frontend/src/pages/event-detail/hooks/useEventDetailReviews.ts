import { useEffect, useState } from 'react'
import {
  createReview,
  deleteReview,
  getEventReviews,
  getEventReviewSummary,
  getMyReview,
  updateReview,
  type EventReviewSummary,
  type Review,
} from '../../../api/reviews'
import { createReport } from '../../../api/reports'
import { useAuth } from '../../../hooks/useAuth'
import { useToast } from '../../../hooks/useToast'

interface UseEventDetailReviewsParams {
  eventId: number
}

interface UseEventDetailReviewsResult {
  reviews: Review[]
  reviewSummary: EventReviewSummary
  myReview: Review | null
  showReviewForm: boolean
  setShowReviewForm: React.Dispatch<React.SetStateAction<boolean>>
  submittingReview: boolean
  handleReviewSubmit: (rating: number, content: string) => Promise<void>
  handleReportReview: (review: Review) => Promise<void>
  handleReviewDelete: () => Promise<void>
}

/**
 * PR89 — EventDetailPage 의 후기/별점 상태와 mutation 을 분리한 hook.
 *
 * 동작은 PR84 EventDetailPage 와 동일하다 (mechanical extraction):
 *  - 마운트 시 review summary + 목록 + (로그인 시) myReview 병렬 fetch
 *  - 작성/수정 후 summary 와 list 단순 refetch
 *  - 삭제 후 동일하게 refetch + myReview null
 *  - 신고는 prompt 사유 입력 후 createReport 호출, 상태 변화 없음 (toast 만)
 *
 * 호출처가 review 외 상태(participation, comments 등) 와 무관하므로 self-contained.
 */
export function useEventDetailReviews({
  eventId,
}: UseEventDetailReviewsParams): UseEventDetailReviewsResult {
  const { showToast } = useToast()
  const { user } = useAuth()

  const [reviews, setReviews] = useState<Review[]>([])
  const [reviewSummary, setReviewSummary] = useState<EventReviewSummary>({
    averageRating: null,
    reviewCount: 0,
  })
  const [myReview, setMyReview] = useState<Review | null>(null)
  const [showReviewForm, setShowReviewForm] = useState(false)
  const [submittingReview, setSubmittingReview] = useState(false)

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
    const wasUpdating = !!myReview
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
      showToast({ title: wasUpdating ? '후기를 수정했어요' : '후기가 등록되었어요', tone: 'success' })
    } catch (error) {
      const status = (error as { status?: number } | null)?.status
      const apiMessage = error instanceof Error ? error.message : ''
      // PR139 — 새 ReviewBeforeEventEndedException (403) 과 기존 USED 가드 (403) 를 메시지로 구분.
      const isBeforeEventEnded = apiMessage.includes('끝난 뒤에')
      const title =
        status === 403
          ? isBeforeEventEnded
            ? '이벤트가 끝난 뒤에 후기를 쓸 수 있어요'
            : '체크인 완료자만 후기를 쓸 수 있어요'
          : status === 409
            ? '이미 후기를 작성했어요'
            : status === 404
              ? wasUpdating
                ? '수정할 후기를 찾을 수 없어요 — 새로 작성해주세요'
                : '후기를 찾을 수 없어요'
              : '후기 저장에 실패했어요'
      // 수정 시 404 → backend 의 review 가 사라진 상태. 로컬 state 도 비워서 다음 시도부터 작성 모드로.
      if (wasUpdating && status === 404) {
        setMyReview(null)
        setShowReviewForm(false)
      }
      showToast({
        title,
        message: apiMessage || '잠시 후 다시 시도해주세요.',
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

  return {
    reviews,
    reviewSummary,
    myReview,
    showReviewForm,
    setShowReviewForm,
    submittingReview,
    handleReviewSubmit,
    handleReportReview,
    handleReviewDelete,
  }
}
