import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getEvent,
  getEventById,
  getEventComments,
  getMyParticipation,
  listEventApplicants,
} from '../../../api/events'
import { getEventCheckIns, type EventCheckInSummary } from '../../../api/tickets'
import { useAuth } from '../../../hooks/useAuth'
import { useCoalescedRefresh } from '../../../hooks/useCoalescedRefresh'
import { useToast } from '../../../hooks/useToast'
import { notificationStore } from '../../../stores/notificationStore'
import type {
  Event,
  EventApplicant,
  EventComment,
  MyParticipation,
} from '../../../types'

interface UseEventDetailDataParams {
  /** /channels/{cid}/events/{eid} 에서 진입할 때만 전달. flat /events/{eid} 에서는 undefined. */
  channelId?: number
  eventId: number
}

interface UseEventDetailDataResult {
  event: Event | null
  loading: boolean
  participation: MyParticipation | null
  comments: EventComment[]
  applicants: EventApplicant[]
  checkInSummary: EventCheckInSummary | null
  isOwner: boolean
  setParticipation: React.Dispatch<React.SetStateAction<MyParticipation | null>>
  setComments: React.Dispatch<React.SetStateAction<EventComment[]>>
  refreshEvent: () => Promise<void>
  refreshMyParticipation: () => Promise<void>
  refreshApplicants: () => Promise<void>
  refreshCheckInSummary: () => Promise<void>
}

/**
 * PR89 — EventDetailPage 의 데이터 fetch + SSE refresh + hash scroll 책임을 분리한 hook.
 *
 * 동작은 PR84 EventDetailPage 와 동일하다 (mechanical extraction):
 *  - 마운트 시 event + comments + my participation 병렬 fetch
 *  - 비로그인은 my participation null fallback
 *  - 후속 onIncoming 알림(`PARTICIPATION_*` / `TICKET_*` / `REFUND_COMPLETED`) 에 따라
 *    debounced refetch (300ms) 트리거
 *  - isOwner 인 경우 applicants + check-in summary 추가 fetch
 *  - URL hash 가 `#applicants` / `#check-ins` 면 mount 후 해당 섹션으로 스크롤
 *
 * mutation 핸들러(apply/cancel/approve/reject/comment 등) 는 [useEventDetailActions]
 * 가 담당하고, 본 hook 이 노출한 setParticipation/setComments/refresh* 를 통해 상태를
 * 동기화한다. 리뷰 상태는 [useEventDetailReviews] 가 별도로 소유한다.
 */
export function useEventDetailData({
  channelId,
  eventId,
}: UseEventDetailDataParams): UseEventDetailDataResult {
  const { showToast } = useToast()
  const { user } = useAuth()

  const [event, setEvent] = useState<Event | null>(null)
  const [loading, setLoading] = useState(true)
  const [participation, setParticipation] = useState<MyParticipation | null>(null)
  const [comments, setComments] = useState<EventComment[]>([])
  const [applicants, setApplicants] = useState<EventApplicant[]>([])
  const [checkInSummary, setCheckInSummary] = useState<EventCheckInSummary | null>(null)

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
  //
  // PR92 — 타이머/cleanup 메커니즘은 [useCoalescedRefresh] 에 위임하고, "어느 필드를 refetch
  // 할지" 의 flag 병합은 본 hook 만의 정책이라 ref 로 유지한다.
  const refreshFlagsRef = useRef({ event: false, my: false, applicants: false, checkIn: false })

  const { scheduleRefresh: flushPendingRefresh } = useCoalescedRefresh(
    useCallback(() => {
      const f = refreshFlagsRef.current
      refreshFlagsRef.current = { event: false, my: false, applicants: false, checkIn: false }
      if (f.event) refreshEvent()
      if (f.my) refreshMyParticipation()
      if (f.applicants) refreshApplicants()
      if (f.checkIn) refreshCheckInSummary()
    }, [refreshApplicants, refreshCheckInSummary, refreshEvent, refreshMyParticipation]),
  )

  const scheduleRefresh = useCallback(
    (flags: Partial<{ event: boolean; my: boolean; applicants: boolean; checkIn: boolean }>) => {
      if (flags.event) refreshFlagsRef.current.event = true
      if (flags.my) refreshFlagsRef.current.my = true
      if (flags.applicants) refreshFlagsRef.current.applicants = true
      if (flags.checkIn) refreshFlagsRef.current.checkIn = true
      flushPendingRefresh('event-detail')
    },
    [flushPendingRefresh],
  )

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
      } else if (n.type === 'REFUND_COMPLETED') {
        // PR83 — 환불 완료 → 본인 participation 이 CANCELED 로 바뀌고 정원도 -1. event 본문도
        // 즉시 갱신해 CTA 가 "참가 신청하기" / "다시 신청하기" 로 복귀하도록.
        scheduleRefresh({ event: true, my: true, checkIn: isOwner })
      }
    })
  }, [eventId, isOwner, participation?.ticketId, scheduleRefresh])

  return {
    event,
    loading,
    participation,
    comments,
    applicants,
    checkInSummary,
    isOwner,
    setParticipation,
    setComments,
    refreshEvent,
    refreshMyParticipation,
    refreshApplicants,
    refreshCheckInSummary,
  }
}
