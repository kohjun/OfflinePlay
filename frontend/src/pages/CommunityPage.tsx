import type { ReactNode } from 'react'

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

interface PreviewItem {
  title: string
  description: string
  icon: ReactNode
}

const PREVIEW_ITEMS: PreviewItem[] = [
  {
    title: '이벤트별 채팅방',
    description: '같은 이벤트에 참가한 사람들과 일정·만남을 조율합니다.',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4.5 8.5A2.5 2.5 0 0 1 7 6h7a2.5 2.5 0 0 1 2.5 2.5v4A2.5 2.5 0 0 1 14 15h-4l-3 3v-3H7A2.5 2.5 0 0 1 4.5 12.5z" />
        <path d="M11 5h6a2.5 2.5 0 0 1 2.5 2.5v4a2.5 2.5 0 0 1-1 2" />
      </svg>
    ),
  },
  {
    title: '채널 토론방',
    description: '구독한 채널의 공지·이벤트 회고를 함께 이야기합니다.',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="9" cy="11" r="3.4" />
        <path d="M15 7.5a3 3 0 1 1 0 6" />
        <path d="M3 19a6 6 0 0 1 12 0" />
        <path d="M14 19a5 5 0 0 1 7 0" />
      </svg>
    ),
  },
  {
    title: '라이브 응원',
    description: '진행 중인 이벤트를 실시간으로 응원하고 반응을 남깁니다.',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M5 14c0-4 3-7 7-7s7 3 7 7" />
        <path d="M3.5 13.5h17" />
        <path d="M7 17.5 12 21l5-3.5" />
      </svg>
    ),
  },
]

/**
 * 커뮤니티 탭 placeholder. 채팅/토론방/라이브 응원이 들어올 자리를 미리 표시한다.
 * 현재는 진입 가능한 액션이 없으므로 미리보기 카드만 노출한다.
 */
export function CommunityPage({ onNavigate }: CommunityPageProps) {
  return (
    <main className="ct-placeholder-page">
      <section className="ct-placeholder-hero">
        <span className="ct-placeholder-hero-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" {...stroke}>
            <path d="M4.5 8.5A2.5 2.5 0 0 1 7 6h7a2.5 2.5 0 0 1 2.5 2.5v4A2.5 2.5 0 0 1 14 15h-4l-3 3v-3H7A2.5 2.5 0 0 1 4.5 12.5z" />
            <path d="M11 5h6a2.5 2.5 0 0 1 2.5 2.5v4a2.5 2.5 0 0 1-1 2" />
          </svg>
        </span>
        <h1>커뮤니티는 곧 열려요</h1>
        <p>같은 이벤트에 참여한 사람들과 모이고, 채널 단위의 토론까지 한 곳에서 가능해집니다.</p>
      </section>

      <section className="ct-preview-list" aria-label="준비 중인 기능">
        {PREVIEW_ITEMS.map((item) => (
          <article key={item.title} className="ct-preview-card">
            <span className="ct-preview-card-icon" aria-hidden="true">{item.icon}</span>
            <strong>{item.title}</strong>
            <span>{item.description}</span>
            <span className="action-tag">준비 중</span>
          </article>
        ))}
      </section>

      <div className="ct-empty-actions">
        <button className="button button-primary" type="button" onClick={() => onNavigate('/explore')}>
          채널 둘러보기
        </button>
        <button className="button button-secondary" type="button" onClick={() => onNavigate('/notifications')}>
          알림 보기
        </button>
      </div>
    </main>
  )
}
