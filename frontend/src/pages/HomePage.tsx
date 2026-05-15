import { useEffect, useState, type ReactNode } from 'react'
import { explore } from '../api/explore'
import { useAuth } from '../hooks/useAuth'
import { useNotificationStore } from '../stores/notificationStore'
import type { ChannelCategory, ContentType, Event } from '../types'

interface HomePageProps {
  onNavigate: (path: string) => void
}

interface ContentTypeOption {
  key: ContentType
  label: string
}

const CONTENT_TYPES: ContentTypeOption[] = [
  { key: 'ORIGINAL', label: 'Original' },
  { key: 'CLASSIC', label: 'Classic' },
  { key: 'SPECIAL', label: 'Special' },
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

// 9 categories — wireframe 03 인기 카테고리 3×3 그리드.
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
        <circle cx="11" cy="11" r="6.4" />
        <path d="m15.7 15.7 4 4" />
      </svg>
    ),
  },
  {
    key: 'SURVIVAL',
    label: '서바이벌',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="m12 4 3 6h6l-5 4 2 7-6-4-6 4 2-7-5-4h6z" />
      </svg>
    ),
  },
  {
    key: 'MUSIC',
    label: '음악',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M9 18V7l11-2v11" />
        <circle cx="7" cy="18" r="2" />
        <circle cx="18" cy="16" r="2" />
      </svg>
    ),
  },
  {
    key: 'SPORTS',
    label: '스포츠',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <circle cx="12" cy="12" r="7.6" />
        <path d="M12 4v16M4 12h16M6 6l12 12M18 6 6 18" />
      </svg>
    ),
  },
  {
    key: 'COOKING',
    label: '요리',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M5 11h14l-1.5 8H6.5z" />
        <path d="M7 11a5 5 0 0 1 10 0" />
      </svg>
    ),
  },
  {
    key: 'PARTY',
    label: '파티',
    icon: (
      <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
        <path d="M4 20 14 8l2 2L6 22z" />
        <path d="M16 4v3M19 7h3M19 11v3M22 13h-3" />
      </svg>
    ),
  },
]

function formatRange(startAt: string) {
  const d = new Date(startAt)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${month}.${day} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function formatFee(fee: number) {
  return fee === 0 ? '무료' : `${(fee / 1000).toFixed(0)}K`
}

/**
 * 화면 03 — 홈 피드 (핸드오프 README §03).
 *
 * 정보 구조 (wireframe 그대로):
 *  - greeting: "안녕하세요, [이름]님 👋" + "이번 주말엔 뭐하고 놀까요?"
 *  - 검색 진입 (사용자가 검색하려면 ExplorePage 로)
 *  - "지금 추천하는 이벤트" 가로 캐러셀 (백엔드 explore API 의 최근 events)
 *  - 콘텐츠 유형 세그먼트 (Original/Classic/Special, pill, 활성 #FA5252) → /explore?type=
 *  - 인기 카테고리 3×3 그리드 → /explore?category=
 *
 * 제거: 광고 hero (NEW/인기/추천), "여름맞이 참가비 이벤트" 배너 — wireframe 에 없음.
 */
export function HomePage({ onNavigate }: HomePageProps) {
  const { user } = useAuth()
  const { unreadCount } = useNotificationStore()
  const [recommended, setRecommended] = useState<Event[]>([])
  const [recLoading, setRecLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setRecLoading(true)
    explore({ size: 8 })
      .then((res) => {
        if (alive) setRecommended(res.events.content)
      })
      .catch(() => {
        if (alive) setRecommended([])
      })
      .finally(() => {
        if (alive) setRecLoading(false)
      })
    return () => {
      alive = false
    }
  }, [])

  function handleCategoryClick(key: ChannelCategory) {
    onNavigate(`/explore?category=${key}`)
  }

  function handleContentTypeClick(key: ContentType) {
    onNavigate(`/explore?type=${key}`)
  }

  const userName = user?.nickname ?? '게스트'

  return (
    <main className="hf-page">
      <header className="hf-greeting">
        <div className="hf-greeting__text">
          <h1>안녕하세요, {userName}님 👋</h1>
          <p>이번 주말엔 뭐하고 놀까요?</p>
        </div>
        <button
          type="button"
          className="hf-bell"
          onClick={() => onNavigate('/notifications')}
          aria-label={`알림${unreadCount > 0 ? `, 안 읽음 ${unreadCount}건` : ''}`}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
            <path d="M6 16.5h12L17 14.7v-3.9a5 5 0 0 0-10 0v3.9z" />
            <path d="M10 19.2a2 2 0 0 0 4 0" />
          </svg>
          {unreadCount > 0 ? <span className="hf-bell__dot" aria-hidden="true" /> : null}
        </button>
      </header>

      <section className="hf-section">
        <div className="hf-section__head">
          <h2 className="hf-section__title">지금 추천하는 이벤트</h2>
          <button className="hf-section__more" type="button" onClick={() => onNavigate('/explore')}>
            더보기 ›
          </button>
        </div>
        <div className="hf-carousel" role="list">
          {recLoading ? (
            <div className="hf-carousel__placeholder">불러오는 중...</div>
          ) : recommended.length === 0 ? (
            <div className="hf-carousel__placeholder">아직 추천할 이벤트가 없어요.</div>
          ) : (
            recommended.map((ev) => (
              <button
                key={ev.id}
                type="button"
                role="listitem"
                className="hf-rec-card"
                onClick={() => onNavigate(`/events/${ev.id}`)}
              >
                <span className="hf-rec-card__img" aria-hidden="true">
                  {ev.mainImageUrl ? <img src={ev.mainImageUrl} alt="" loading="lazy" /> : null}
                  <span className="hf-rec-card__people">
                    <svg viewBox="0 0 24 24" {...stroke} aria-hidden="true">
                      <circle cx="9" cy="9" r="3" />
                      <path d="M3 19a6 6 0 0 1 12 0" />
                      <circle cx="17" cy="9" r="2.5" />
                      <path d="M14.5 19a5 5 0 0 1 7.5-4" />
                    </svg>
                    {ev.maxParticipants - ev.currentParticipants}/{ev.maxParticipants}
                  </span>
                </span>
                <strong className="hf-rec-card__title">{ev.title}</strong>
                <span className="hf-rec-card__meta">
                  {formatRange(ev.startAt)} · {formatFee(ev.participationFee)}
                </span>
              </button>
            ))
          )}
        </div>
      </section>

      <section className="hf-types" role="tablist" aria-label="콘텐츠 유형">
        {CONTENT_TYPES.map((ct) => (
          <button
            key={ct.key}
            type="button"
            role="tab"
            className="hf-types__cell"
            onClick={() => handleContentTypeClick(ct.key)}
          >
            {ct.label}
          </button>
        ))}
      </section>

      <section className="hf-section">
        <div className="hf-section__head">
          <h2 className="hf-section__title">인기 카테고리</h2>
          <button className="hf-section__more" type="button" onClick={() => onNavigate('/explore')}>
            전체 보기
          </button>
        </div>
        <div className="hf-cat-grid">
          {CATEGORIES.map((c) => (
            <button
              key={c.key}
              type="button"
              className="hf-cat-cell"
              onClick={() => handleCategoryClick(c.key)}
            >
              <span className="hf-cat-cell__icon">{c.icon}</span>
              <span className="hf-cat-cell__label">{c.label}</span>
            </button>
          ))}
        </div>
      </section>
    </main>
  )
}
