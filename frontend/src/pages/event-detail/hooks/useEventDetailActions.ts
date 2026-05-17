import { useState } from 'react'
import {
  applyEvent,
  approveParticipation,
  cancelEventApplication,
  postEventComment,
  rejectParticipation,
} from '../../../api/events'
import { useToast } from '../../../hooks/useToast'
import type { Event, EventComment, MyParticipation } from '../../../types'

interface UseEventDetailActionsParams {
  eventId: number
  event: Event | null
  participation: MyParticipation | null
  setParticipation: React.Dispatch<React.SetStateAction<MyParticipation | null>>
  setComments: React.Dispatch<React.SetStateAction<EventComment[]>>
  refreshApplicants: () => Promise<void>
  refreshEvent: () => Promise<void>
  onNavigate: (path: string) => void
}

interface UseEventDetailActionsResult {
  submittingJoin: boolean
  reviewingId: number | null
  commentDraft: string
  setCommentDraft: React.Dispatch<React.SetStateAction<string>>
  submittingComment: boolean
  handleApply: () => Promise<void>
  handlePaidApply: () => void
  handleCancel: () => Promise<void>
  handleApprove: (participationId: number) => Promise<void>
  handleReject: (participationId: number) => Promise<void>
  handleSubmitComment: () => Promise<void>
}

/**
 * PR89 — EventDetailPage 의 mutation 핸들러를 분리한 hook.
 *
 * 동작은 PR84 EventDetailPage 와 동일하다 (mechanical extraction):
 *  - 신청/결제/취소/승인/거절/댓글 등록 mutation
 *  - toast message / confirm dialog / status 분기 / 가드 (submittingJoin/reviewingId) 모두 그대로 보존
 *  - 결제 이벤트의 취소 confirm 메시지는 PR75 변경분 유지 (paid+APPROVED 분기)
 *
 * 데이터 hook 이 소유한 setParticipation/setComments/refresh* 를 통해 상태를 갱신한다.
 * 본 hook 은 자체 state(submittingJoin, reviewingId, commentDraft, submittingComment) 만 보유.
 */
export function useEventDetailActions({
  eventId,
  event,
  participation,
  setParticipation,
  setComments,
  refreshApplicants,
  refreshEvent,
  onNavigate,
}: UseEventDetailActionsParams): UseEventDetailActionsResult {
  const { showToast } = useToast()

  const [submittingJoin, setSubmittingJoin] = useState(false)
  const [reviewingId, setReviewingId] = useState<number | null>(null)
  const [commentDraft, setCommentDraft] = useState('')
  const [submittingComment, setSubmittingComment] = useState(false)

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

  return {
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
  }
}
