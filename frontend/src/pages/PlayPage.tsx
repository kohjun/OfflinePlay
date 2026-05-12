import type { ReactNode } from 'react'

interface PlayPageProps {
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
    title: 'GPS 미션',
    description: '도시 곳곳에 숨겨진 미션을 위치 기반으로 수행합니다.',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M12 21s-6.5-5-6.5-10.5a6.5 6.5 0 1 1 13 0C18.5 16 12 21 12 21z" />
        <circle cx="12" cy="10.5" r="2.4" />
      </svg>
    ),
  },
  {
    title: '라이브 투표',
    description: '이벤트 현장에서 미션 결과나 우승팀을 실시간 투표로 정합니다.',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4 14V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8" />
        <path d="M3 14h18" />
        <path d="m9 18 3 3 3-3" />
      </svg>
    ),
  },
  {
    title: '즉석 매칭',
    description: '지금 시작할 수 있는 이벤트를 골라 빠르게 합류합니다.',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="12" cy="12" r="8.4" />
        <path d="M10 9.5v5l4-2.5z" fill="currentColor" stroke="none" />
      </svg>
    ),
  },
]

/**
 * 중앙 Play 탭 placeholder. 실시간 이벤트(GPS/투표/매칭)가 들어올 자리.
 */
export function PlayPage({ onNavigate }: PlayPageProps) {
  return (
    <main className="ct-placeholder-page">
      <section className="ct-placeholder-hero">
        <span className="ct-placeholder-hero-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5.5v13l11-6.5z" />
          </svg>
        </span>
        <h1>지금 시작하는 Play</h1>
        <p>실시간으로 진행되는 이벤트와 즉석 매칭, 위치 기반 미션이 들어올 자리입니다.</p>
      </section>

      <section className="ct-preview-list" aria-label="준비 중인 Play 기능">
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
          이벤트 둘러보기
        </button>
      </div>
    </main>
  )
}
