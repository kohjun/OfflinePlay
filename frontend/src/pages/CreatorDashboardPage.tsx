import { FormEvent, useEffect, useState } from 'react'
import { createChannel } from '../api/channels'
import { getCreatorStudio } from '../api/creator'
import { Badge } from '../components/Badge'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type {
  ChannelCategory,
  CreatorStudioEvent,
  CreatorStudioResponse,
  EventStatus,
} from '../types'

const CATEGORY_OPTIONS: { value: ChannelCategory; label: string }[] = [
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

const EVENT_STATUS_LABEL: Record<EventStatus, string> = {
  UPCOMING: '곧 시작',
  ONGOING: '진행 중',
  CLOSED: '종료',
}

const EVENT_STATUS_TONE: Record<EventStatus, 'primary' | 'success' | 'neutral'> = {
  UPCOMING: 'primary',
  ONGOING: 'success',
  CLOSED: 'neutral',
}

interface CreatorDashboardPageProps {
  onNavigate: (path: string) => void
}

function formatStartAt(value: string) {
  const d = new Date(value)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${month}.${day} ${hh}:${mm}`
}

export function CreatorDashboardPage({ onNavigate }: CreatorDashboardPageProps) {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [studio, setStudio] = useState<CreatorStudioResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<ChannelCategory>('TRAVEL')
  const [creatingChannel, setCreatingChannel] = useState(false)

  const role = user?.role

  useEffect(() => {
    if (role !== 'CREATOR' && role !== 'ADMIN') {
      setStudio(null)
      return
    }
    let cancelled = false
    setLoading(true)
    getCreatorStudio()
      .then((data) => {
        if (!cancelled) setStudio(data)
      })
      .catch(() => {
        if (!cancelled) setStudio(null)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [role])

  async function refreshStudio() {
    try {
      const data = await getCreatorStudio()
      setStudio(data)
    } catch {
      // ignore — page will retain stale state
    }
  }

  async function handleCreateChannel(event: FormEvent) {
    event.preventDefault()
    if (creatingChannel) return
    setCreatingChannel(true)
    try {
      const channel = await createChannel({ name, description, category })
      showToast({ title: '채널이 생성되었어요', message: channel.name, tone: 'success' })
      setName('')
      setDescription('')
      setCategory('TRAVEL')
      await refreshStudio()
    } catch (error) {
      showToast({
        title: '채널 생성 실패',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setCreatingChannel(false)
    }
  }

  // ── A. PARTICIPANT → 기획자 신청 안내 ────────────────────────────────────
  if (role === 'PARTICIPANT') {
    return (
      <main className="page ct-studio-page">
        <section className="ct-studio-apply-hero">
          <span className="ct-studio-apply-eyebrow">Become a creator</span>
          <h1>나만의 채널을 열어보세요</h1>
          <p className="muted">
            CONTENIDO 에서는 누구나 자기만의 채널을 만들고 오프라인 이벤트를 기획할 수 있어요.
            기획자로 승인되면 채널을 만들고 참가자를 모을 수 있습니다.
          </p>
          <button
            className="button button-primary"
            onClick={() => onNavigate('/creator/apply')}
            type="button"
          >
            기획자 신청하기
          </button>
        </section>
      </main>
    )
  }

  // ── 로딩 ─────────────────────────────────────────────────────────────────
  if (loading && !studio) {
    return (
      <main className="page ct-studio-page">
        <section className="ct-studio-hero ct-studio-hero-skeleton">
          <span className="ct-studio-skeleton-line is-eyebrow" />
          <span className="ct-studio-skeleton-line is-title" />
          <span className="ct-studio-skeleton-line is-desc" />
        </section>
        <section className="ct-studio-summary">
          <span className="ct-studio-skeleton-tile" />
          <span className="ct-studio-skeleton-tile" />
          <span className="ct-studio-skeleton-tile" />
          <span className="ct-studio-skeleton-tile" />
        </section>
      </main>
    )
  }

  const channel = studio?.channel ?? null
  const events: CreatorStudioEvent[] = studio?.events ?? []
  const summary = studio?.summary ?? {
    totalEvents: 0,
    pendingApplicants: 0,
    approvedParticipants: 0,
    subscriberCount: 0,
  }

  // ── B. CREATOR/ADMIN + 채널 없음 ─────────────────────────────────────────
  if (!channel) {
    const isAdmin = role === 'ADMIN'
    return (
      <main className="page ct-studio-page">
        <section className="ct-studio-hero ct-studio-hero-empty">
          <span className="ct-studio-eyebrow">Creator studio</span>
          <h1>아직 채널이 없어요</h1>
          <p className="muted">
            채널은 기획자 본인의 팀/브랜드 공간입니다. 채널을 만들면 그 안에서 이벤트를 등록하고
            구독자에게 알림을 보낼 수 있어요.
          </p>
        </section>

        {isAdmin ? (
          <section className="ct-studio-admin-note">
            <strong>관리자 계정입니다.</strong>
            <span className="muted">
              관리자는 본인 명의의 채널을 따로 운영하지 않는 것이 보통이에요. 운영 모니터링이
              필요하다면 관리자 페이지를 이용해주세요.
            </span>
          </section>
        ) : (
          <section className="form-section ct-studio-create">
            <div>
              <h2>채널 만들기</h2>
              <p className="muted">팀명 또는 브랜드명으로 채널을 열 수 있어요.</p>
            </div>
            <form className="form-stack" onSubmit={handleCreateChannel}>
              <label>
                채널 이름
                <input
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="예: 모야 트래블 클럽"
                  required
                />
              </label>
              <label>
                카테고리
                <select
                  value={category}
                  onChange={(event) => setCategory(event.target.value as ChannelCategory)}
                >
                  {CATEGORY_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                채널 소개
                <textarea
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  placeholder="어떤 이벤트를 기획할 채널인지 한두 줄로 소개해주세요."
                  required
                />
              </label>
              <button className="button button-primary" type="submit" disabled={creatingChannel}>
                {creatingChannel ? <span className="button-spinner" aria-hidden="true" /> : null}
                채널 생성
              </button>
            </form>
          </section>
        )}
      </main>
    )
  }

  // ── C. CREATOR/ADMIN + 채널 있음 ─────────────────────────────────────────
  return (
    <main className="page ct-studio-page">
      <section className="ct-studio-hero">
        <div className="ct-studio-hero-top">
          <span className="ct-studio-eyebrow">Creator studio</span>
          <Badge tone="primary">{channel.categoryDisplayName}</Badge>
        </div>
        <h1 className="ct-studio-hero-title">{channel.name}</h1>
        <p className="muted ct-studio-hero-desc">{channel.description}</p>
        <div className="ct-studio-hero-meta">
          <span>
            <strong>{channel.subscriberCount.toLocaleString()}</strong> 구독자
          </span>
          <span className="ct-studio-dot" aria-hidden="true">·</span>
          <span>운영자 {channel.ownerNickname}</span>
        </div>
        <div className="ct-studio-hero-actions">
          <button
            className="button button-secondary"
            type="button"
            onClick={() => onNavigate(`/channels/${channel.id}`)}
          >
            채널 상세 보기
          </button>
          <button
            className="button button-primary"
            type="button"
            onClick={() => onNavigate(`/channels/${channel.id}/events/new`)}
          >
            + 새 이벤트 만들기
          </button>
        </div>
        <button
          type="button"
          className="button button-secondary is-block ct-studio-checkin-cta"
          onClick={() => onNavigate('/check-in')}
        >
          🎟 티켓 체크인 (코드 입력)
        </button>
      </section>

      <section className="ct-studio-growth" aria-label="구독자 성장">
        <div className="ct-studio-growth-head">
          <span className="ct-studio-growth-eyebrow">구독자 성장</span>
          <strong>최근 7일</strong>
        </div>
        <svg
          className="ct-studio-growth-chart"
          viewBox="0 0 320 120"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <defs>
            <linearGradient id="ct-studio-growth-fill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#FA5252" stopOpacity="0.28" />
              <stop offset="100%" stopColor="#FA5252" stopOpacity="0" />
            </linearGradient>
          </defs>
          {/* 정적 폴리라인 — 실데이터 연결은 PR50. */}
          <polyline
            fill="url(#ct-studio-growth-fill)"
            stroke="none"
            points="0,90 50,80 100,72 150,68 200,50 250,42 320,26 320,120 0,120"
          />
          <polyline
            fill="none"
            stroke="#FA5252"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            points="0,90 50,80 100,72 150,68 200,50 250,42 320,26"
          />
          {[0, 50, 100, 150, 200, 250, 320].map((x, i) => (
            <circle
              key={x}
              cx={x}
              cy={[90, 80, 72, 68, 50, 42, 26][i]}
              r="3"
              fill="#FA5252"
            />
          ))}
        </svg>
        <div className="ct-studio-growth-foot">
          <span>월</span>
          <span>화</span>
          <span>수</span>
          <span>목</span>
          <span>금</span>
          <span>토</span>
          <span>일</span>
        </div>
      </section>

      <section className="ct-studio-summary">
        <div className="ct-studio-tile">
          <span>이벤트</span>
          <strong>{summary.totalEvents}</strong>
        </div>
        <div className={`ct-studio-tile${summary.pendingApplicants > 0 ? ' is-attention' : ''}`}>
          <span>승인 대기</span>
          <strong>{summary.pendingApplicants}</strong>
        </div>
        <div className="ct-studio-tile">
          <span>확정 참가자</span>
          <strong>{summary.approvedParticipants}</strong>
        </div>
        <div className="ct-studio-tile">
          <span>구독자</span>
          <strong>{summary.subscriberCount.toLocaleString()}</strong>
        </div>
      </section>

      <section className="ct-studio-events">
        <div className="ct-studio-events-head">
          <h2 className="ct-studio-section-title">이벤트 관리</h2>
          <span className="muted">{events.length}건</span>
        </div>

        {events.length === 0 ? (
          <div className="ct-studio-events-empty">
            <span aria-hidden="true">🎬</span>
            <strong>아직 등록한 이벤트가 없어요</strong>
            <span className="muted">
              채널 상세 페이지에서 첫 이벤트를 만들어 참가자를 모아보세요.
            </span>
            <button
              type="button"
              className="button button-primary"
              onClick={() => onNavigate(`/channels/${channel.id}/events/new`)}
            >
              새 이벤트 만들기
            </button>
          </div>
        ) : (
          <ul className="ct-studio-event-list">
            {events.map((e) => (
              <li key={e.id} className="ct-studio-event-card">
                <div className="ct-studio-event-head">
                  <Badge tone={EVENT_STATUS_TONE[e.status]}>{EVENT_STATUS_LABEL[e.status]}</Badge>
                  {e.pendingCount > 0 ? (
                    <span className="ct-studio-pending-badge">승인 대기 {e.pendingCount}</span>
                  ) : null}
                </div>
                <h3 className="ct-studio-event-title">{e.title}</h3>
                <ul className="ct-studio-event-meta">
                  <li>
                    <span aria-hidden="true">📅</span>
                    <span>{formatStartAt(e.startAt)}</span>
                  </li>
                  <li>
                    <span aria-hidden="true">📍</span>
                    <span>{e.location}</span>
                  </li>
                  <li>
                    <span aria-hidden="true">👥</span>
                    <span>
                      {e.currentParticipants}/{e.maxParticipants}명
                    </span>
                  </li>
                </ul>
                <div className="ct-studio-event-actions">
                  <button
                    type="button"
                    className="button button-secondary"
                    onClick={() => onNavigate(`/events/${e.id}`)}
                  >
                    상세 보기
                  </button>
                  <button
                    type="button"
                    className="button button-primary"
                    onClick={() => onNavigate(`/events/${e.id}#applicants`)}
                  >
                    신청자 관리
                    {e.pendingCount > 0 ? <span className="ct-studio-action-count">{e.pendingCount}</span> : null}
                  </button>
                  <button
                    type="button"
                    className="button button-secondary"
                    onClick={() => onNavigate(`/events/${e.id}#check-ins`)}
                  >
                    체크인 현황
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  )
}
