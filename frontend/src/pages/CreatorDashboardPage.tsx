import { FormEvent, useEffect, useState } from 'react'
import { createChannel } from '../api/channels'
import { getCreatorHiddenContent, getCreatorStudio } from '../api/creator'
import { createReportAppeal } from '../api/reportAppeals'
import { Badge } from '../components/Badge'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import { notificationStore } from '../stores/notificationStore'
import type {
  ChannelCategory,
  CreatorModerationHiddenItem,
  CreatorStudioEvent,
  CreatorStudioResponse,
  EventStatus,
  ReportTargetType,
} from '../types'

const HIDDEN_TARGET_LABEL: Record<ReportTargetType, string> = {
  CHANNEL: '채널',
  POST: '게시글',
  EVENT: '이벤트',
  COMMENT: '댓글',
  REVIEW: '후기',
}

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
  // PR53 — 본인 권한의 자동 숨김 콘텐츠 목록.
  const [hidden, setHidden] = useState<CreatorModerationHiddenItem[]>([])

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

  // PR53 — 본인 자동 숨김 콘텐츠 로드. CREATOR/ADMIN 외에도 일반 PARTICIPANT 의 hidden
  // REVIEW/COMMENT 가 있을 수 있으나, 본 페이지는 CREATOR/ADMIN 전용 경로라 동일 권한군만
  // 데이터를 받는다. 일반 사용자 진입은 MyPage 또는 후속 PR 에서.
  useEffect(() => {
    if (role !== 'CREATOR' && role !== 'ADMIN') {
      setHidden([])
      return
    }
    let cancelled = false
    getCreatorHiddenContent()
      .then((items) => {
        if (!cancelled) setHidden(items)
      })
      .catch(() => {
        if (!cancelled) setHidden([])
      })
    return () => {
      cancelled = true
    }
  }, [role])

  async function handleAppeal(item: CreatorModerationHiddenItem) {
    const reason = window.prompt(
      `이의 제기 사유를 입력해주세요\n(대상: ${HIDDEN_TARGET_LABEL[item.targetType]} #${item.targetId})`,
    )
    if (reason === null) return
    if (reason.trim().length === 0) {
      showToast({ title: '사유를 입력해주세요', tone: 'warning' })
      return
    }
    try {
      const appeal = await createReportAppeal({
        targetType: item.targetType,
        targetId: item.targetId,
        reason: reason.trim(),
      })
      setHidden((items) =>
        items.map((row) =>
          row.targetType === item.targetType && row.targetId === item.targetId
            ? { ...row, appealStatus: 'PENDING', appealId: appeal.id }
            : row,
        ),
      )
      showToast({ title: '이의 제기가 접수되었어요', tone: 'success' })
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const message = error instanceof Error ? error.message : ''
      // PR56 — 409 cooldown 과 409 PENDING 중복을 메시지로 구분한다.
      const isCooldown = status === 409 && message.includes('7일')
      const title =
        isCooldown
          ? '최근 거절된 이의 제기는 7일 뒤 다시 신청할 수 있어요'
          : status === 409
          ? '이미 검토 대기 중입니다'
          : status === 403
          ? '본인 콘텐츠만 이의 제기할 수 있어요'
          : status === 400
          ? '숨김 처리된 대상만 이의 제기할 수 있어요'
          : '이의 제기에 실패했어요'
      showToast({
        title,
        message: isCooldown ? undefined : message || undefined,
        tone: 'danger',
      })
    }
  }

  // 참가 신청/취소/티켓 발급 알림이 오면 studio 를 다시 받아와 summary tile 의
  // pendingApplicants / approvedParticipants 카운트를 즉시 반영. 이벤트별로 따로
  // 받지 않고 묶음 갱신 — 대시보드는 본인 채널 전체 합계라 부분 patch 가 의미 없다.
  useEffect(() => {
    if (role !== 'CREATOR' && role !== 'ADMIN') return
    const RELEVANT = new Set([
      'PARTICIPATION_REQUESTED',
      'PARTICIPATION_CANCELED',
      'PARTICIPATION_APPROVED',
      'PARTICIPATION_REJECTED',
      'TICKET_ISSUED',
      'TICKET_CHECKED_IN',
    ])
    return notificationStore.onIncoming((n) => {
      if (RELEVANT.has(n.type)) refreshStudio()
    })
  }, [role])

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
            className="ct-studio-growth-line"
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
              className="ct-studio-growth-dot"
              cx={x}
              cy={[90, 80, 72, 68, 50, 42, 26][i]}
              r="3"
              fill="#FA5252"
              style={{ animationDelay: `${280 + i * 80}ms` }}
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

      {/* PR53 — 본인 권한의 자동 숨김 콘텐츠 + 이의 제기 CTA. APPROVED 는 backend 가 unhide
          하므로 다음 로드부터 응답에서 빠지지만, 같은 페이지 세션에서 처리된 경우 row 가 남아 있으면
          "복구됨" 라벨로 표시. */}
      <section className="ct-studio-events" aria-label="숨김 처리된 콘텐츠">
        <div className="ct-studio-events-head">
          <h2 className="ct-studio-section-title">숨김 처리된 콘텐츠</h2>
          <span className="muted">{hidden.length}건</span>
        </div>
        {hidden.length === 0 ? (
          <p className="muted">자동 숨김된 내 콘텐츠가 없어요.</p>
        ) : (
          <ul className="stack">
            {hidden.map((item) => {
              const canAppeal = item.appealStatus === 'NONE' || item.appealStatus === 'REJECTED'
              const statusLabel =
                item.appealStatus === 'PENDING'
                  ? '검토 대기 중'
                  : item.appealStatus === 'APPROVED'
                  ? '복구됨'
                  : item.appealStatus === 'REJECTED'
                  ? '이의 제기 거절됨'
                  : null
              const statusTone =
                item.appealStatus === 'PENDING'
                  ? 'warning'
                  : item.appealStatus === 'APPROVED'
                  ? 'success'
                  : item.appealStatus === 'REJECTED'
                  ? 'neutral'
                  : 'neutral'
              return (
                <article
                  className="card"
                  key={`${item.targetType}-${item.targetId}`}
                >
                  <div className="badge-row">
                    <Badge tone="danger">{HIDDEN_TARGET_LABEL[item.targetType]}</Badge>
                    <Badge tone="warning">자동 숨김</Badge>
                    {statusLabel ? <Badge tone={statusTone}>{statusLabel}</Badge> : null}
                  </div>
                  <strong>{item.targetTitle}</strong>
                  <p className="muted">“{item.targetPreview}”</p>
                  {item.hiddenReason ? (
                    <p className="muted">숨김 사유: {item.hiddenReason}</p>
                  ) : null}
                  <div className="meta-row">
                    <span>신고 누적 {item.pendingReportCount}건</span>
                    <span>{new Date(item.hiddenAt).toLocaleString()}</span>
                    <span>#{item.targetId}</span>
                  </div>
                  {canAppeal ? (
                    <div className="admin-actions">
                      <button
                        type="button"
                        className="button button-primary"
                        onClick={() => handleAppeal(item)}
                      >
                        이의 제기
                      </button>
                    </div>
                  ) : null}
                </article>
              )
            })}
          </ul>
        )}
      </section>
    </main>
  )
}
