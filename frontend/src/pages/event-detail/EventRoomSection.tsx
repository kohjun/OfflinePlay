import { useEffect, useState } from 'react'
import { getEventUnreadAnnouncementCount } from '../../api/eventAnnouncements'
import { Badge } from '../../components/Badge'
import type { EventApplicant, EventComment } from '../../types'
import { EventAnnouncementsSection } from './EventAnnouncementsSection'
import { EventCommentsSection } from './EventCommentsSection'

interface EventRoomSectionProps {
  eventId: number
  /** 사용자가 room 에 접근 가능한지 (`isOwner || ADMIN || participation.status === 'APPROVED'`). */
  canAccessRoom: boolean
  /** 공지 작성 가능 (owner / STAFF / ADMIN). */
  canWriteAnnouncement: boolean
  /** 공지 읽기 가능 (위 + APPROVED 참가자). 사실상 canAccessRoom 과 거의 동일하지만, 본 PR 은
   *  EventDetailPage 가 명시적으로 전달해 정책 추적성을 유지한다. */
  canReadAnnouncement: boolean

  // EventCommentsSection 으로 그대로 forward.
  comments: EventComment[]
  commentDraft: string
  submittingComment: boolean
  onDraftChange: (next: string) => void
  onSubmit: () => void

  /** 참가자 탭 표시용. owner/STAFF/ADMIN 만 의미가 있고, 일반 참가자는 본인 + 정원 정보만 보일 수도 있다. */
  applicants: EventApplicant[]
  approvedCount: number
  maxParticipants: number
}

type RoomTab = 'announcements' | 'talk' | 'participants'

const TABS: { id: RoomTab; label: string }[] = [
  { id: 'announcements', label: '공지' },
  { id: 'talk', label: '대화' },
  { id: 'participants', label: '참가자' },
]

/**
 * PR150 — 이벤트룸 hub. 기존 announcement + comment 섹션을 단일 tabbed 영역으로 묶고
 * 참가자 탭을 새로 추가한다. **새 backend endpoint 없음** — Promise.all 없이 자식 컴포넌트들이
 * 각자 fetch 하고 부모는 권한 분기 + tab 상태만 책임진다.
 *
 *  - 권한이 없으면 섹션 자체가 hidden — owner / ADMIN / APPROVED 참가자만 진입.
 *  - 공지 / 대화 탭의 실제 구현은 기존 컴포넌트 재사용 (EventAnnouncementsSection / EventCommentsSection).
 *  - 참가자 탭: APPROVED 만 노출 + 본 row 의 운영자 액션 (승인/거절) 은 EventOwnerPanel 의 책임으로 분리 유지.
 *  - `unreadAnnouncementCount` placeholder 는 PR151 에서 채운다.
 */
export function EventRoomSection({
  eventId,
  canAccessRoom,
  canWriteAnnouncement,
  canReadAnnouncement,
  comments,
  commentDraft,
  submittingComment,
  onDraftChange,
  onSubmit,
  applicants,
  approvedCount,
  maxParticipants,
}: EventRoomSectionProps) {
  const [tab, setTab] = useState<RoomTab>('announcements')
  const [unreadCount, setUnreadCount] = useState(0)

  // PR151 — unread 공지 카운트. 권한 있는 사용자만 호출.
  useEffect(() => {
    if (!canReadAnnouncement) return
    let alive = true
    getEventUnreadAnnouncementCount(eventId)
      .then((res) => {
        if (alive) setUnreadCount(res.unreadCount)
      })
      .catch(() => {
        if (alive) setUnreadCount(0)
      })
    return () => {
      alive = false
    }
  }, [eventId, canReadAnnouncement, tab])

  if (!canAccessRoom) return null

  const approvedApplicants = applicants.filter((a) => a.status === 'APPROVED')

  return (
    <section className="ct-event-section ct-event-room" aria-label="이벤트룸">
      <header className="ct-event-room__header">
        <h2 className="ct-event-section-title">이벤트룸</h2>
        <span className="muted">{approvedCount}/{maxParticipants}명</span>
      </header>

      <nav className="ct-event-room__tabs" role="tablist" aria-label="이벤트룸 탭">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={`tab-chip${tab === t.id ? ' is-active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
            {t.id === 'announcements' && unreadCount > 0 ? (
              <Badge tone="danger">{unreadCount}</Badge>
            ) : null}
          </button>
        ))}
      </nav>

      <div className="ct-event-room__panel" role="tabpanel">
        {tab === 'announcements' ? (
          <EventAnnouncementsSection
            eventId={eventId}
            canWrite={canWriteAnnouncement}
            canRead={canReadAnnouncement}
          />
        ) : tab === 'talk' ? (
          <EventCommentsSection
            comments={comments}
            commentDraft={commentDraft}
            submittingComment={submittingComment}
            canAccessRoom={canAccessRoom}
            onDraftChange={onDraftChange}
            onSubmit={onSubmit}
          />
        ) : (
          <div className="ct-event-room__participants">
            <p className="muted">
              참가가 확정된 {approvedApplicants.length}명이 함께해요. 최대 {maxParticipants}명까지 모집해요.
            </p>
            {approvedApplicants.length === 0 ? (
              <p className="muted">아직 참가 확정자가 없습니다.</p>
            ) : (
              <ul className="ct-event-room__participant-list">
                {approvedApplicants.map((a) => (
                  <li key={a.id} className="ct-event-room__participant-row">
                    <strong>{a.nickname}</strong>
                    <span className="muted">{new Date(a.joinedAt).toLocaleDateString()} 신청</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </section>
  )
}
