import { useEffect, useState } from 'react'
import { getMyParticipations } from '../api/events'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { MyParticipationItem } from '../types'

interface CommunityPageProps {
  onNavigate: (path: string) => void
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

/**
 * PR161 — Community 탭이 이제 "내 이벤트룸" 입구. PR160 이벤트룸 채팅이 활성화된 이벤트만 노출.
 *
 *  - 참가 확정 (APPROVED) 된 이벤트 중 ticket 이 CANCELED/REFUNDED 가 아닌 row 만.
 *  - 카드 클릭 → EventDetailPage 의 이벤트룸 탭으로 직접 진입.
 *  - 비로그인 / 참가 확정 이벤트 0개 → empty state + explore 버튼.
 */
export function CommunityPage({ onNavigate }: CommunityPageProps) {
  const { isAuthenticated } = useAuth()
  const { showToast } = useToast()
  const [items, setItems] = useState<MyParticipationItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!isAuthenticated) {
      setLoading(false)
      return
    }
    let alive = true
    getMyParticipations({ page: 0, size: 20 })
      .then((res) => {
        if (!alive) return
        // 채팅 입장 가능한 row 만 노출: APPROVED + ticket 없음(무료) 또는 ticket 활성.
        setItems(
          res.content.filter((p) => {
            if (p.status !== 'APPROVED') return false
            if (p.ticketStatus == null) return true
            return p.ticketStatus !== 'CANCELED' && p.ticketStatus !== 'REFUNDED'
          }),
        )
      })
      .catch((err) => {
        showToast({
          title: '내 이벤트룸을 불러오지 못했어요',
          message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
          tone: 'warning',
        })
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [isAuthenticated, showToast])

  if (!isAuthenticated) {
    return (
      <main className="page empty-state">
        <h1>로그인하면 내 이벤트룸이 열려요</h1>
        <p className="muted">참가 확정된 이벤트의 채팅방에서 다른 참가자와 의사소통할 수 있어요.</p>
        <button className="button button-primary is-block" type="button" onClick={() => onNavigate('/')}>
          로그인 하러 가기
        </button>
      </main>
    )
  }

  return (
    <main className="page ct-community-page">
      <header className="page-header">
        <p className="eyebrow">Community</p>
        <h1>내 이벤트룸</h1>
        <span className="muted">참가 확정된 이벤트의 채팅방에 입장해 다른 참가자와 대화할 수 있어요.</span>
      </header>

      {loading ? (
        <Skeleton lines={3} />
      ) : items.length === 0 ? (
        <div className="empty-state">
          <span aria-hidden="true">
            <svg width="40" height="40" viewBox="0 0 24 24" {...stroke}>
              <path d="M4.5 8.5A2.5 2.5 0 0 1 7 6h7a2.5 2.5 0 0 1 2.5 2.5v4A2.5 2.5 0 0 1 14 15h-4l-3 3v-3H7A2.5 2.5 0 0 1 4.5 12.5z" />
              <path d="M11 5h6a2.5 2.5 0 0 1 2.5 2.5v4a2.5 2.5 0 0 1-1 2" />
            </svg>
          </span>
          <strong>아직 참가 확정된 이벤트가 없어요</strong>
          <p className="muted">이벤트에 신청 후 운영자 승인 (또는 결제 완료) 가 되면 채팅방이 열립니다.</p>
          <button className="button button-primary" type="button" onClick={() => onNavigate('/explore')}>
            이벤트 둘러보기
          </button>
        </div>
      ) : (
        <ul className="ct-community-rooms">
          {items.map((p) => (
            <li key={p.participationId}>
              <button
                type="button"
                className="card ct-community-room"
                onClick={() => onNavigate(`/events/${p.eventId}`)}
              >
                <div className="card-body stack">
                  <strong>{p.eventTitle}</strong>
                  <span className="muted">
                    {p.channelName} · {new Date(p.startAt).toLocaleDateString()}
                  </span>
                  <span className="muted">{p.location}</span>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
