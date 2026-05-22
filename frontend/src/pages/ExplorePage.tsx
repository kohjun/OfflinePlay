import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { explore, getPopularSearches, type PopularKeyword } from '../api/explore'
import {
  subscribeChannel,
  unsubscribeChannel,
} from '../api/channels'
import { ChannelCard } from '../components/ChannelCard'
import { EventCard } from '../components/EventCard'
import { RecommendationStrip } from '../components/RecommendationStrip'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { Channel, ChannelCategory, ContentType, Event } from '../types'

interface ExplorePageProps {
  onNavigate: (path: string) => void
}

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

interface CategoryOption {
  value: ChannelCategory
  label: string
}

const CATEGORIES: CategoryOption[] = [
  { value: 'TRAVEL', label: '여행' },
  { value: 'LOVE', label: '연애' },
  { value: 'RACE', label: '레이스' },
  { value: 'PSYCHOLOGICAL', label: '심리추리' },
  { value: 'SURVIVAL', label: '서바이벌' },
  { value: 'MUSIC', label: '음악' },
  { value: 'SPORTS', label: '스포츠' },
  { value: 'COOKING', label: '요리' },
  { value: 'PARTY', label: '파티' },
]

const CATEGORY_VALUES = CATEGORIES.map((c) => c.value)

interface ContentTypeOption {
  value: ContentType
  label: string
}

const CONTENT_TYPES: ContentTypeOption[] = [
  { value: 'ORIGINAL', label: 'Original' },
  { value: 'CLASSIC', label: 'Classic' },
  { value: 'SPECIAL', label: 'Special' },
]

const CONTENT_TYPE_VALUES = CONTENT_TYPES.map((c) => c.value)

const PAGE_SIZE = 20

type ResultTab = 'events' | 'channels'

// PR45 — 가격 / 일정 preset chip. preset 만 노출하고 backend 에는 명시적 minFee/maxFee/startFrom/startTo 로 변환.
type FeePreset = 'ALL' | 'FREE' | 'PAID'
type DatePreset = 'ALL' | 'TODAY' | 'WEEK' | 'MONTH'

const FEE_PRESETS: Array<{ value: FeePreset; label: string }> = [
  { value: 'ALL', label: '가격 전체' },
  { value: 'FREE', label: '무료' },
  { value: 'PAID', label: '유료' },
]

const DATE_PRESETS: Array<{ value: DatePreset; label: string }> = [
  { value: 'ALL', label: '일정 전체' },
  { value: 'TODAY', label: '오늘' },
  { value: 'WEEK', label: '이번 주' },
  { value: 'MONTH', label: '이번 달' },
]

function feeRange(preset: FeePreset): { minFee?: number; maxFee?: number } {
  // 무료 = 0..0, 유료 = 1.. (상한 없음), 전체 = undefined
  if (preset === 'FREE') return { minFee: 0, maxFee: 0 }
  if (preset === 'PAID') return { minFee: 1 }
  return {}
}

function dateRange(preset: DatePreset): { startFrom?: string; startTo?: string } {
  if (preset === 'ALL') return {}
  const now = new Date()
  const startFrom = now.toISOString()
  const end = new Date(now)
  if (preset === 'TODAY') {
    end.setHours(23, 59, 59, 999)
  } else if (preset === 'WEEK') {
    // 이번 주 일요일 23:59:59 (월=1 ~ 일=0). 일요일이면 그 날 끝.
    const day = end.getDay()
    const diff = day === 0 ? 0 : 7 - day
    end.setDate(end.getDate() + diff)
    end.setHours(23, 59, 59, 999)
  } else if (preset === 'MONTH') {
    end.setMonth(end.getMonth() + 1, 0)  // 다음 달 0일 = 이번 달 말일
    end.setHours(23, 59, 59, 999)
  }
  return { startFrom, startTo: end.toISOString() }
}

interface Filters {
  keyword: string
  category: ChannelCategory | null
  contentType: ContentType | null
  fee: FeePreset
  date: DatePreset
}

/**
 * URL query 와 (구버전 호환을 위한) sessionStorage 둘 다에서 필터를 읽는다.
 * URL query 우선. sessionStorage 값은 읽고 즉시 제거한다.
 */
function readFiltersFromUrl(): Filters {
  const search = typeof window !== 'undefined' ? new URLSearchParams(window.location.search) : new URLSearchParams()

  let keyword = search.get('keyword')?.trim() ?? ''
  let category = search.get('category')
  const contentType = search.get('type') ?? search.get('contentType')

  try {
    if (!keyword) {
      const saved = window.sessionStorage.getItem('explore.keyword')
      if (saved) {
        keyword = saved
        window.sessionStorage.removeItem('explore.keyword')
      }
    }
    if (!category) {
      const saved = window.sessionStorage.getItem('explore.category')
      if (saved) {
        category = saved
        window.sessionStorage.removeItem('explore.category')
      }
    }
  } catch {
    /* sessionStorage 사용 불가 — non-fatal */
  }

  const feeRaw = (search.get('fee') ?? 'ALL') as FeePreset
  const dateRaw = (search.get('date') ?? 'ALL') as DatePreset
  const fee: FeePreset = (['ALL', 'FREE', 'PAID'] as FeePreset[]).includes(feeRaw) ? feeRaw : 'ALL'
  const date: DatePreset = (['ALL', 'TODAY', 'WEEK', 'MONTH'] as DatePreset[]).includes(dateRaw) ? dateRaw : 'ALL'

  return {
    keyword,
    category: CATEGORY_VALUES.includes(category as ChannelCategory) ? (category as ChannelCategory) : null,
    contentType: CONTENT_TYPE_VALUES.includes(contentType as ContentType) ? (contentType as ContentType) : null,
    fee,
    date,
  }
}

function buildSearch(
  keyword: string,
  category: ChannelCategory | null,
  contentType: ContentType | null,
  fee: FeePreset,
  date: DatePreset,
): string {
  const params = new URLSearchParams()
  if (keyword) params.set('keyword', keyword)
  if (category) params.set('category', category)
  if (contentType) params.set('type', contentType)
  if (fee !== 'ALL') params.set('fee', fee)
  if (date !== 'ALL') params.set('date', date)
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export function ExplorePage({ onNavigate }: ExplorePageProps) {
  const { showToast } = useToast()
  const { isAuthenticated } = useAuth()

  // 마운트 시 초기값(URL/sessionStorage). 이후 popstate 또는 사용자 액션으로만 변경된다.
  const initial = useState(readFiltersFromUrl)[0]
  const [keywordInput, setKeywordInput] = useState<string>(initial.keyword)
  const [activeKeyword, setActiveKeyword] = useState<string>(initial.keyword)
  const [category, setCategory] = useState<ChannelCategory | null>(initial.category)
  const [contentType, setContentType] = useState<ContentType | null>(initial.contentType)
  const [feePreset, setFeePreset] = useState<FeePreset>(initial.fee)
  const [datePreset, setDatePreset] = useState<DatePreset>(initial.date)
  const [tab, setTab] = useState<ResultTab>('events')

  // PR45: 인기 검색어 chip — 첫 마운트 시 1회만 fetch.
  const [popular, setPopular] = useState<PopularKeyword[]>([])
  useEffect(() => {
    let alive = true
    getPopularSearches(8)
      .then((data) => {
        if (alive) setPopular(data)
      })
      .catch(() => {
        // 비활성/redis 미가용 등의 케이스 — 정적 fallback 은 empty state 에서 사용.
        if (alive) setPopular([])
      })
    return () => {
      alive = false
    }
  }, [])

  // 결과: 탭별로 page/items/hasMore 를 따로 관리한다.
  const [events, setEvents] = useState<Event[]>([])
  const [eventsPage, setEventsPage] = useState(0)
  const [eventsHasMore, setEventsHasMore] = useState(false)
  const [channels, setChannels] = useState<Channel[]>([])
  const [channelsPage, setChannelsPage] = useState(0)
  const [channelsHasMore, setChannelsHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // popstate 로 들어온 변경은 URL 을 다시 push 하면 안 된다 — 무한 루프 방지.
  const skipNextUrlSync = useRef(false)

  // 첫 페이지 fetch — 필터 변경 시 호출. 페이지를 0 으로 리셋한다.
  const fetchFirstPage = useCallback(
    async (
      kw: string,
      cat: ChannelCategory | null,
      ct: ContentType | null,
      fee: FeePreset,
      date: DatePreset,
    ) => {
      setLoading(true)
      setError(null)
      try {
        const { minFee, maxFee } = feeRange(fee)
        const { startFrom, startTo } = dateRange(date)
        const result = await explore({
          keyword: kw || undefined,
          category: cat ?? undefined,
          contentType: ct ?? undefined,
          minFee,
          maxFee,
          startFrom,
          startTo,
          page: 0,
          size: PAGE_SIZE,
        })
        setEvents(result.events.content)
        setEventsPage(result.events.currentPage)
        setEventsHasMore(!result.events.isLast)
        setChannels(result.channels.content)
        setChannelsPage(result.channels.currentPage)
        setChannelsHasMore(!result.channels.isLast)
      } catch (err) {
        setEvents([])
        setChannels([])
        setEventsHasMore(false)
        setChannelsHasMore(false)
        setError(err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.')
      } finally {
        setLoading(false)
      }
    },
    [],
  )

  // 마운트 시 URL 정규화만 — sessionStorage 마이그레이션 흔적도 URL 로 반영.
  useEffect(() => {
    const desired = `/explore${buildSearch(initial.keyword, initial.category, initial.contentType, initial.fee, initial.date)}`
    if (window.location.pathname + window.location.search !== desired) {
      window.history.replaceState({}, '', desired)
    }
    // 초기 fetch 는 아래의 필터 useEffect 가 처리한다 (deps 가 initial.* 와 같아 1회 실행됨).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 필터 변경 → URL push + 첫 페이지 재조회. 마운트 시에도 1회 실행되어 초기 로드 역할도 한다.
  // popstate 로 인한 변경은 URL 을 다시 push 하지 않도록 skip 한다.
  useEffect(() => {
    const shouldPush = !skipNextUrlSync.current
    if (skipNextUrlSync.current) {
      skipNextUrlSync.current = false
    }
    if (shouldPush) {
      const next = `/explore${buildSearch(activeKeyword, category, contentType, feePreset, datePreset)}`
      if (window.location.pathname + window.location.search !== next) {
        window.history.pushState({}, '', next)
      }
    }
    fetchFirstPage(activeKeyword, category, contentType, feePreset, datePreset)
  }, [activeKeyword, category, contentType, feePreset, datePreset, fetchFirstPage])

  // popstate (브라우저 뒤로/앞으로) → URL 에서 필터 재구성 후 state 동기화.
  useEffect(() => {
    function handlePopState() {
      const next = readFiltersFromUrl()
      skipNextUrlSync.current = true
      setKeywordInput(next.keyword)
      setActiveKeyword(next.keyword)
      setCategory(next.category)
      setContentType(next.contentType)
      setFeePreset(next.fee)
      setDatePreset(next.date)
      fetchFirstPage(next.keyword, next.category, next.contentType, next.fee, next.date)
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [fetchFirstPage])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setActiveKeyword(keywordInput.trim())
  }

  function handleClearKeyword() {
    setKeywordInput('')
    setActiveKeyword('')
  }

  function handleToggleCategory(value: ChannelCategory) {
    setCategory((current) => (current === value ? null : value))
  }

  function handleToggleContentType(value: ContentType) {
    setContentType((current) => (current === value ? null : value))
  }

  function handleResetFilters() {
    setKeywordInput('')
    setActiveKeyword('')
    setCategory(null)
    setContentType(null)
    setFeePreset('ALL')
    setDatePreset('ALL')
  }

  async function handleLoadMore() {
    if (loadingMore) return
    const isEvents = tab === 'events'
    if (isEvents ? !eventsHasMore : !channelsHasMore) return
    const nextPage = (isEvents ? eventsPage : channelsPage) + 1
    setLoadingMore(true)
    try {
      const { minFee, maxFee } = feeRange(feePreset)
      const { startFrom, startTo } = dateRange(datePreset)
      const result = await explore({
        keyword: activeKeyword || undefined,
        category: category ?? undefined,
        contentType: contentType ?? undefined,
        minFee,
        maxFee,
        startFrom,
        startTo,
        page: nextPage,
        size: PAGE_SIZE,
      })
      if (isEvents) {
        // dedupe by id (이미 가지고 있는 항목이 다음 페이지에 다시 등장하는 케이스 방지)
        setEvents((prev) => {
          const ids = new Set(prev.map((e) => e.id))
          const fresh = result.events.content.filter((e) => !ids.has(e.id))
          return [...prev, ...fresh]
        })
        setEventsPage(result.events.currentPage)
        setEventsHasMore(!result.events.isLast)
      } else {
        setChannels((prev) => {
          const ids = new Set(prev.map((c) => c.id))
          const fresh = result.channels.content.filter((c) => !ids.has(c.id))
          return [...prev, ...fresh]
        })
        setChannelsPage(result.channels.currentPage)
        setChannelsHasMore(!result.channels.isLast)
      }
    } catch (err) {
      showToast({
        title: '더 불러오지 못했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      })
    } finally {
      setLoadingMore(false)
    }
  }

  async function handleToggleSubscribe(channel: Channel) {
    const wasSubscribed = channel.isSubscribed === true
    setChannels((items) =>
      items.map((item) =>
        item.id === channel.id
          ? {
              ...item,
              isSubscribed: !wasSubscribed,
              subscriberCount: item.subscriberCount + (wasSubscribed ? -1 : 1),
            }
          : item,
      ),
    )
    try {
      if (wasSubscribed) await unsubscribeChannel(channel.id)
      else await subscribeChannel(channel.id)
    } catch (err) {
      setChannels((items) =>
        items.map((item) =>
          item.id === channel.id
            ? {
                ...item,
                isSubscribed: wasSubscribed,
                subscriberCount: item.subscriberCount + (wasSubscribed ? 1 : -1),
              }
            : item,
        ),
      )
      showToast({
        title: '구독 상태가 반영되지 않았습니다',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  const totalCount = events.length + channels.length
  const hasAnyFilter = Boolean(
    activeKeyword || category || contentType || feePreset !== 'ALL' || datePreset !== 'ALL',
  )
  const tabHasMore = tab === 'events' ? eventsHasMore : channelsHasMore

  function applyPopular(keyword: string) {
    setKeywordInput(keyword)
    setActiveKeyword(keyword)
  }

  return (
    <main className="page ct-explore-page">
      <header className="ct-explore-header">
        <p className="eyebrow">Explore</p>
        <h1 className="ct-explore-title">관심 채널과 이벤트 찾기</h1>
      </header>

      <RecommendationStrip
        onOpen={(channelId, eventId) =>
          onNavigate(`/channels/${channelId}/events/${eventId}`)
        }
        isAuthenticated={isAuthenticated}
      />

      <form className="ct-search ct-explore-search" onSubmit={handleSubmit} role="search">
        <span className="ct-search-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" {...stroke}>
            <circle cx="11" cy="11" r="6.4" />
            <path d="m15.7 15.7 4 4" />
          </svg>
        </span>
        <input
          type="search"
          value={keywordInput}
          onChange={(event) => setKeywordInput(event.target.value)}
          placeholder="채널, 이벤트 검색..."
          aria-label="검색"
          autoComplete="off"
        />
        <button className="ct-filter-btn" type="submit" aria-label="검색 실행">
          <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
            <path d="m5 12 5 5L20 7" />
          </svg>
        </button>
      </form>

      {(activeKeyword || category || contentType || feePreset !== 'ALL' || datePreset !== 'ALL') ? (
        <div className="ct-explore-context" role="status">
          {activeKeyword ? (
            <span className="ct-explore-context-chip">
              검색어: <strong>{activeKeyword}</strong>
              <button
                type="button"
                className="ct-explore-context-clear"
                onClick={handleClearKeyword}
                aria-label="검색어 지우기"
              >
                ×
              </button>
            </span>
          ) : null}
          {category ? (
            <span className="ct-explore-context-chip">
              카테고리: <strong>{CATEGORIES.find((c) => c.value === category)?.label ?? category}</strong>
              <button
                type="button"
                className="ct-explore-context-clear"
                onClick={() => setCategory(null)}
                aria-label="카테고리 지우기"
              >
                ×
              </button>
            </span>
          ) : null}
          {contentType ? (
            <span className="ct-explore-context-chip">
              유형: <strong>{CONTENT_TYPES.find((c) => c.value === contentType)?.label ?? contentType}</strong>
              <button
                type="button"
                className="ct-explore-context-clear"
                onClick={() => setContentType(null)}
                aria-label="콘텐츠 유형 지우기"
              >
                ×
              </button>
            </span>
          ) : null}
          {feePreset !== 'ALL' ? (
            <span className="ct-explore-context-chip">
              가격: <strong>{FEE_PRESETS.find((f) => f.value === feePreset)?.label}</strong>
              <button
                type="button"
                className="ct-explore-context-clear"
                onClick={() => setFeePreset('ALL')}
                aria-label="가격 필터 지우기"
              >
                ×
              </button>
            </span>
          ) : null}
          {datePreset !== 'ALL' ? (
            <span className="ct-explore-context-chip">
              일정: <strong>{DATE_PRESETS.find((d) => d.value === datePreset)?.label}</strong>
              <button
                type="button"
                className="ct-explore-context-clear"
                onClick={() => setDatePreset('ALL')}
                aria-label="일정 필터 지우기"
              >
                ×
              </button>
            </span>
          ) : null}
        </div>
      ) : null}

      {popular.length > 0 && !activeKeyword ? (
        <section className="ct-explore-popular" aria-label="인기 검색어">
          <span className="muted">인기 검색어</span>
          <div className="ct-explore-popular-chips">
            {popular.map((p, idx) => (
              <button
                key={p.keyword}
                type="button"
                className="chip ct-explore-popular-chip"
                onClick={() => applyPopular(p.keyword)}
              >
                <span className="ct-explore-popular-rank">{idx + 1}</span>
                {p.keyword}
              </button>
            ))}
          </div>
        </section>
      ) : null}

      <nav className="ct-explore-types" role="tablist" aria-label="콘텐츠 유형">
        {CONTENT_TYPES.map((ct) => (
          <button
            key={ct.value}
            type="button"
            role="tab"
            aria-selected={contentType === ct.value}
            className={`ct-explore-type ${contentType === ct.value ? 'is-active' : ''}`}
            onClick={() => handleToggleContentType(ct.value)}
          >
            {ct.label}
          </button>
        ))}
      </nav>

      <nav className="ct-chip-row" role="tablist" aria-label="가격대 / 일정">
        {FEE_PRESETS.map((f) => (
          <button
            key={f.value}
            type="button"
            role="tab"
            aria-selected={feePreset === f.value}
            className={`chip ${feePreset === f.value ? 'is-active' : ''}`}
            onClick={() => setFeePreset(f.value)}
          >
            {f.label}
          </button>
        ))}
        {DATE_PRESETS.map((d) => (
          <button
            key={d.value}
            type="button"
            role="tab"
            aria-selected={datePreset === d.value}
            className={`chip ${datePreset === d.value ? 'is-active' : ''}`}
            onClick={() => setDatePreset(d.value)}
          >
            {d.label}
          </button>
        ))}
      </nav>

      <nav className="ct-chip-row" role="tablist" aria-label="카테고리">
        {CATEGORIES.map((c) => (
          <button
            key={c.value}
            type="button"
            role="tab"
            aria-selected={category === c.value}
            className={`chip ${category === c.value ? 'is-active' : ''}`}
            onClick={() => handleToggleCategory(c.value)}
          >
            {c.label}
          </button>
        ))}
      </nav>

      <div className="ct-explore-tabs" role="tablist" aria-label="결과 탭">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'events'}
          className={`ct-explore-tab ${tab === 'events' ? 'is-active' : ''}`}
          onClick={() => setTab('events')}
        >
          이벤트 <span className="ct-explore-tab-count">{loading ? '…' : events.length}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'channels'}
          className={`ct-explore-tab ${tab === 'channels' ? 'is-active' : ''}`}
          onClick={() => setTab('channels')}
        >
          채널 <span className="ct-explore-tab-count">{loading ? '…' : channels.length}</span>
        </button>
      </div>

      <section className="section ct-explore-results">
        {loading ? (
          <div className="stack" aria-hidden="true">
            <Skeleton lines={4} />
            <Skeleton lines={4} />
            <Skeleton lines={4} />
          </div>
        ) : error ? (
          <div className="ct-explore-empty">
            <span aria-hidden="true">⚠️</span>
            <strong>검색 결과를 불러오지 못했어요</strong>
            <span className="muted">{error}</span>
            <button
              type="button"
              className="button button-primary"
              onClick={() => fetchFirstPage(activeKeyword, category, contentType, feePreset, datePreset)}
            >
              다시 시도
            </button>
          </div>
        ) : totalCount === 0 ? (
          <div className="ct-explore-empty">
            <span aria-hidden="true">🔍</span>
            <strong>조건에 맞는 결과가 없어요</strong>
            <span className="muted">
              {hasAnyFilter ? '다른 키워드나 카테고리를 시도해보세요.' : '관심 카테고리를 선택해보세요.'}
            </span>
            {hasAnyFilter ? (
              <div className="ct-explore-empty-suggestions" aria-label="추천 검색어">
                <span className="muted">함께 찾아본 검색어</span>
                <div className="ct-explore-empty-chips">
                  {(popular.length > 0
                    ? popular.slice(0, 4).map((p) => p.keyword)
                    : ['주말 모임', '러닝 크루', '와인 클래스', '보드게임']
                  ).map((kw) => (
                    <button
                      key={kw}
                      type="button"
                      className="chip"
                      onClick={() => applyPopular(kw)}
                    >
                      {kw}
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
            <div className="ct-explore-empty-actions">
              {hasAnyFilter ? (
                <button type="button" className="button button-secondary" onClick={handleResetFilters}>
                  필터 초기화
                </button>
              ) : null}
              <button type="button" className="button button-primary" onClick={() => onNavigate('/')}>
                홈으로 가기
              </button>
            </div>
          </div>
        ) : tab === 'events' ? (
          events.length === 0 ? (
            <div className="ct-explore-empty">
              <span aria-hidden="true">🎬</span>
              <strong>조건에 맞는 이벤트가 없어요</strong>
              <span className="muted">채널 탭에서 다른 결과를 확인해보세요.</span>
            </div>
          ) : (
            <div className="stack">
              {events.map((event) => (
                <EventCard
                  key={event.id}
                  event={event}
                  onOpen={(_, eid) => onNavigate(`/events/${eid}`)}
                />
              ))}
            </div>
          )
        ) : channels.length === 0 ? (
          <div className="ct-explore-empty">
            <span aria-hidden="true">📺</span>
            <strong>조건에 맞는 채널이 없어요</strong>
            <span className="muted">이벤트 탭에서 다른 결과를 확인해보세요.</span>
          </div>
        ) : (
          <div className="stack">
            {channels.map((channel) => (
              <ChannelCard
                key={channel.id}
                channel={channel}
                onOpen={(id) => onNavigate(`/channels/${id}`)}
                onToggleSubscribe={handleToggleSubscribe}
              />
            ))}
          </div>
        )}

        {!loading && !error && tabHasMore ? (
          <div className="load-more-row">
            <button
              type="button"
              className="button button-secondary"
              onClick={handleLoadMore}
              disabled={loadingMore}
              aria-busy={loadingMore}
            >
              {loadingMore ? <span className="button-spinner" aria-hidden="true" /> : null}
              {loadingMore ? '불러오는 중...' : '더 보기'}
            </button>
          </div>
        ) : null}
      </section>
    </main>
  )
}
