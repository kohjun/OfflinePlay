import { useCallback, useEffect, useState } from 'react'
import {
  getRecommendations,
  type RecommendationSegment,
  type RecommendedEvent,
} from '../api/recommendations'
import { EventCard } from './EventCard'

interface RecommendationStripProps {
  /** 카드 클릭 시 라우팅 (ExplorePage 가 전달). */
  onOpen: (channelId: number, eventId: number) => void
  /** 로그인 상태 — 비로그인이면 "추천" 탭 라벨이 "인기" 로 alias. */
  isAuthenticated: boolean
}

interface SegmentTab {
  id: RecommendationSegment
  label: string
}

const TABS_AUTH: SegmentTab[] = [
  { id: 'RECOMMENDED', label: '추천' },
  { id: 'POPULAR', label: '인기' },
  { id: 'CLOSING_SOON', label: '마감 임박' },
  { id: 'LATEST', label: '신규' },
]

const TABS_GUEST: SegmentTab[] = [
  { id: 'POPULAR', label: '인기' },
  { id: 'CLOSING_SOON', label: '마감 임박' },
  { id: 'LATEST', label: '신규' },
]

/**
 * PR148 — ExplorePage 상단의 추천 strip. 4 segment 탭 + 가로 스크롤 카드.
 *  - 첫 마운트 시 default segment fetch. 탭 클릭 시 segment 변경 + refetch.
 *  - reasonCodes 는 EventCard 가 직접 chip 으로 표시 (PR149 가 상위 우선순위 cut).
 */
export function RecommendationStrip({ onOpen, isAuthenticated }: RecommendationStripProps) {
  const tabs = isAuthenticated ? TABS_AUTH : TABS_GUEST
  const [segment, setSegment] = useState<RecommendationSegment>(tabs[0].id)
  const [items, setItems] = useState<RecommendedEvent[]>([])
  const [loading, setLoading] = useState(true)

  const fetchSegment = useCallback((next: RecommendationSegment) => {
    setLoading(true)
    getRecommendations({ segment: next, size: 10 })
      .then((res) => setItems(res.items))
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    fetchSegment(segment)
  }, [segment, fetchSegment])

  return (
    <section className="recommendation-strip" aria-label="추천 이벤트">
      <header className="recommendation-strip__tabs">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            className={`tab-chip${segment === t.id ? ' is-active' : ''}`}
            onClick={() => setSegment(t.id)}
            aria-pressed={segment === t.id}
          >
            {t.label}
          </button>
        ))}
      </header>
      {loading ? (
        <p className="muted">불러오는 중…</p>
      ) : items.length === 0 ? (
        <p className="muted">표시할 추천이 아직 없어요.</p>
      ) : (
        <div className="recommendation-strip__scroller">
          {items.map((row) => (
            <EventCard
              key={row.event.id}
              event={row.event}
              onOpen={onOpen}
              reasonCodes={row.reasonCodes}
            />
          ))}
        </div>
      )}
    </section>
  )
}
