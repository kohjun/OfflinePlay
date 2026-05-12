import { useState, type ReactNode } from 'react'
import { useAuth } from '../hooks/useAuth'
import { useNotificationStore } from '../stores/notificationStore'
import type { ChannelCategory, ContentType } from '../types'

interface HomePageProps {
  onNavigate: (path: string) => void
}

type AdTab = 'NEW' | '인기' | '추천'

const AD_TABS: AdTab[] = ['NEW', '인기', '추천']

interface AdCard {
  title: string
  subtitle: string
  cta: string
  accent: string
}

const AD_CARDS: Record<AdTab, AdCard> = {
  NEW: {
    title: '이번 주 신규 콘텐츠',
    subtitle: 'Contenido가 새로 공개한 예능 라인업을 만나보세요.',
    cta: '지금 보기',
    accent: 'linear-gradient(135deg, #5b5bf6 0%, #8c5bff 100%)',
  },
  '인기': {
    title: '지금 가장 핫한 챌린지',
    subtitle: '많은 참가자가 모이는 라이브 이벤트가 진행 중입니다.',
    cta: '참여하기',
    accent: 'linear-gradient(135deg, #ff5b88 0%, #ff8a5b 100%)',
  },
  '추천': {
    title: '에디터가 직접 고른 추천',
    subtitle: '시즌 베스트 콘텐츠와 기획자 인기 채널을 모았습니다.',
    cta: '둘러보기',
    accent: 'linear-gradient(135deg, #16a371 0%, #34c759 100%)',
  },
}

interface ContentTypeOption {
  key: ContentType
  label: string
  desc: string
}

const CONTENT_TYPES: ContentTypeOption[] = [
  { key: 'ORIGINAL', label: 'Original', desc: 'Contenido만의 콘텐츠' },
  { key: 'CLASSIC', label: 'Classic', desc: '누구나 아는 콘텐츠' },
  { key: 'SPECIAL', label: 'Special', desc: '새롭게 기획한 예능' },
]

interface CategoryOption {
  key: ChannelCategory
  label: string
  icon: ReactNode
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

// 9-category grid: 여행 / 연애 / 레이스 / 심리추리 / 서바이벌 / 음악 / 스포츠 / 요리 / 파티
const CATEGORIES: CategoryOption[] = [
  {
    key: 'TRAVEL',
    label: '여행',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M3 12h13.4l4 3.5L18.5 17H3z" />
        <path d="M6 8h11l3.5 2.5L18 12" />
        <path d="M6 19.5h10" />
      </svg>
    ),
  },
  {
    key: 'LOVE',
    label: '연애',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M12 19.5s-7-4.3-7-9.5a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.2-7 9.5-7 9.5z" />
      </svg>
    ),
  },
  {
    key: 'RACE',
    label: '레이스',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M5 19V5l4 1.6 4-1.6 4 1.6 4-1.6V14l-4 1.6-4-1.6-4 1.6-4-1.6" />
      </svg>
    ),
  },
  {
    key: 'PSYCHOLOGICAL',
    label: '심리추리',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="10.5" cy="12.5" r="5" />
        <path d="m14.5 16.5 4 4" />
        <path d="M9 11h3M9 13h3" />
      </svg>
    ),
  },
  {
    key: 'SURVIVAL',
    label: '서바이벌',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M12 20c3.3 0 6-2.4 6-5.5 0-2.4-1.4-4-2.5-5.2.4 1.6-.4 2.7-1.5 2.7-2 0-1-3.5-2-6-1.7 1.4-6 4.4-6 8.5C6 17.6 8.7 20 12 20z" />
      </svg>
    ),
  },
  {
    key: 'MUSIC',
    label: '음악',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M9 17V6l10-2v11" />
        <circle cx="7" cy="17" r="2.2" />
        <circle cx="17" cy="15" r="2.2" />
      </svg>
    ),
  },
  {
    key: 'SPORTS',
    label: '스포츠',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="12" cy="12" r="8" />
        <path d="m4.5 9 4 1.5L7 14.5l-2.4.4M19.5 9l-4 1.5L17 14.5l2.4.4M12 4v3.5l-3 2 1 3.5h4l1-3.5-3-2V4" />
      </svg>
    ),
  },
  {
    key: 'COOKING',
    label: '요리',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M5 11h14a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4z" />
        <path d="M9 11V8a3 3 0 0 1 6 0v3" />
        <path d="M5 19h14" />
      </svg>
    ),
  },
  {
    key: 'PARTY',
    label: '파티',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M3 20 15 8l1 1L4 21z" />
        <path d="M14 7c1.5-1.5 4-1.5 5.5 0" />
        <path d="M17 4c1 0 2 .7 2 2" />
        <path d="m9 12 3 3" />
      </svg>
    ),
  },
]

export function HomePage({ onNavigate }: HomePageProps) {
  const { user } = useAuth()
  const { unreadCount } = useNotificationStore()
  const [adTab, setAdTab] = useState<AdTab>('NEW')
  const [keyword, setKeyword] = useState('')

  const activeAd = AD_CARDS[adTab]
  const greeting = user ? `${user.nickname}님, 오늘 어떤 콘텐츠를 즐겨볼까요?` : '오늘 어떤 콘텐츠를 즐겨볼까요?'

  function handleCategoryClick(key: ChannelCategory) {
    onNavigate(`/explore?category=${key}`)
  }

  function handleSearchSubmit(event: React.FormEvent) {
    event.preventDefault()
    const trimmed = keyword.trim()
    if (!trimmed) {
      onNavigate('/explore')
      return
    }
    onNavigate(`/explore?keyword=${encodeURIComponent(trimmed)}`)
  }

  function handleAdClick() {
    onNavigate('/explore')
  }

  return (
    <main className="ct-home">
      <header className="ct-top-nav">
        <button className="ct-brand" type="button" onClick={() => onNavigate('/')} aria-label="CONTENIDO 홈">
          CONTENIDO
        </button>
        <div className="ct-top-actions">
          <button
            className="ct-icon-btn"
            type="button"
            onClick={() => onNavigate('/notifications')}
            aria-label={`알림${unreadCount > 0 ? `, 안 읽음 ${unreadCount}건` : ''}`}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
              <path d="M6 16.5h12L17 14.7v-3.9a5 5 0 0 0-10 0v3.9z" />
              <path d="M10 19.2a2 2 0 0 0 4 0" />
            </svg>
            {unreadCount > 0 ? <span className="ct-icon-dot" aria-hidden="true" /> : null}
          </button>
          <button
            className="ct-icon-btn"
            type="button"
            onClick={() => onNavigate('/my')}
            aria-label="찜 목록"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
              <path d="M12 19.5s-7-4.3-7-9.5a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.2-7 9.5-7 9.5z" />
            </svg>
          </button>
        </div>
      </header>

      <p className="ct-home-greeting" aria-live="polite">{greeting}</p>

      <form className="ct-search" onSubmit={handleSearchSubmit} role="search">
        <span className="ct-search-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" {...stroke}>
            <circle cx="11" cy="11" r="6.4" />
            <path d="m15.7 15.7 4 4" />
          </svg>
        </span>
        <input
          type="search"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder="콘텐츠, 채널 검색..."
          aria-label="검색"
          autoComplete="off"
        />
        <button className="ct-filter-btn" type="button" aria-label="필터">
          <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
            <path d="M4 6h16M7 12h10M10 18h4" />
          </svg>
        </button>
      </form>

      <section className="ct-section">
        <div className="ct-section-head">
          <h2 className="ct-section-title">광고</h2>
          <div className="ct-ad-tabs" role="tablist" aria-label="광고 카테고리">
            {AD_TABS.map((tab) => (
              <button
                key={tab}
                role="tab"
                type="button"
                aria-selected={adTab === tab}
                className={`ct-ad-tab ${adTab === tab ? 'is-active' : ''}`}
                onClick={() => setAdTab(tab)}
              >
                {tab}
              </button>
            ))}
          </div>
        </div>
        <article className="ct-ad-hero" style={{ background: activeAd.accent }}>
          <div className="ct-ad-hero-body">
            <span className="ct-ad-hero-tag">{adTab}</span>
            <strong className="ct-ad-hero-title">{activeAd.title}</strong>
            <p className="ct-ad-hero-sub">{activeAd.subtitle}</p>
            <button type="button" className="ct-ad-hero-cta" onClick={handleAdClick}>
              {activeAd.cta}
              <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
                <path d="M5 12h14" />
                <path d="m13 5 7 7-7 7" />
              </svg>
            </button>
          </div>
          <div className="ct-ad-hero-art" aria-hidden="true" />
        </article>
      </section>

      <aside className="ct-banner" role="note">
        <div className="ct-banner-body">
          <strong>여름맞이 참가비 이벤트</strong>
          <span>전 카테고리 이벤트 최대 30% 할인 · 이번 주말까지</span>
        </div>
        <span className="ct-banner-tag">EVENT</span>
      </aside>

      <section className="ct-section">
        <div className="ct-content-types" role="list">
          {CONTENT_TYPES.map((ct) => (
            <button
              key={ct.key}
              type="button"
              role="listitem"
              className="ct-content-type"
              onClick={() => onNavigate(`/explore?type=${ct.key}`)}
            >
              <strong>{ct.label}</strong>
              <span>{ct.desc}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="ct-section">
        <div className="ct-section-head">
          <h2 className="ct-section-title">인기 카테고리</h2>
          <button className="ct-link-btn" type="button" onClick={() => onNavigate('/explore')}>
            전체 보기
          </button>
        </div>
        <div className="ct-category-grid">
          {CATEGORIES.map((c) => (
            <button
              key={c.key}
              type="button"
              className="ct-category-card"
              onClick={() => handleCategoryClick(c.key)}
            >
              <span className="ct-category-icon">{c.icon}</span>
              <span className="ct-category-label">{c.label}</span>
            </button>
          ))}
        </div>
      </section>

    </main>
  )
}
