import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { getMyParticipations } from '../api/events'
import { getMyReportAppeals } from '../api/reportAppeals'
import { Badge } from '../components/Badge'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import { notificationStore } from '../stores/notificationStore'
import { useCoalescedRefresh } from '../hooks/useCoalescedRefresh'
import type {
  EventParticipationStatus,
  MyParticipationItem,
  ReportAppeal,
  ReportTargetType,
  TicketStatus,
  UserRole,
} from '../types'

const APPEAL_TARGET_LABEL: Record<ReportTargetType, string> = {
  CHANNEL: '채널',
  POST: '게시글',
  EVENT: '이벤트',
  COMMENT: '댓글',
  REVIEW: '후기',
}

interface MyPageProps {
  onNavigate: (path: string) => void
}

type MyTab = 'requests' | 'orders'
type OrderFilter = 'ALL' | 'PAID' | 'USED' | 'REFUNDED' | 'CANCELED'

const ORDER_FILTERS: Array<{ value: OrderFilter; label: string }> = [
  { value: 'ALL', label: '전체' },
  { value: 'PAID', label: '결제완료' },
  { value: 'USED', label: '사용완료' },
  { value: 'REFUNDED', label: '환불됨' },
  { value: 'CANCELED', label: '취소됨' },
]

const ROLE_LABEL: Record<UserRole, string> = {
  PARTICIPANT: '참가자',
  CREATOR: '기획자',
  ADMIN: '관리자',
}

const STATUS_LABEL: Record<EventParticipationStatus, string> = {
  PENDING: '승인 대기',
  APPROVED: '참가 확정',
  REJECTED: '거절됨',
  CANCELED: '취소됨',
}

const STATUS_TONE: Record<EventParticipationStatus, 'primary' | 'success' | 'danger' | 'neutral'> = {
  PENDING: 'primary',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELED: 'neutral',
}

const TICKET_STATUS_LABEL: Record<TicketStatus, string> = {
  PAID: '발급 완료',
  USED: '사용 완료',
  REFUNDED: '환불됨',
  CANCELED: '취소됨',
}

interface ActionEntry {
  title: string
  description: string
  href?: string
  preview?: boolean
  previewMessage?: string
}

function buildEntries(role: UserRole | undefined): ActionEntry[] {
  const notifications: ActionEntry = {
    title: '알림',
    description: '구독한 채널의 새 이벤트와 공지가 도착하면 알려드려요.',
    href: '/notifications',
  }

  if (role === 'PARTICIPANT') {
    return [
      {
        title: '기획자 신청하기',
        description: '직접 채널을 만들고 이벤트를 기획해보세요.',
        href: '/creator/apply',
      },
      notifications,
      {
        title: '구독한 채널',
        description: '관심 채널을 모아 새 이벤트 알림을 받습니다.',
        href: '/explore',
      },
      {
        title: '찜 목록',
        description: '관심 이벤트와 채널을 모아 둡니다.',
        preview: true,
        previewMessage: '찜하기 기능은 곧 만나요.',
      },
    ]
  }

  if (role === 'CREATOR') {
    return [
      {
        title: '기획자 스튜디오',
        description: '내 채널 관리와 이벤트 기획을 한 곳에서.',
        href: '/creator',
      },
      notifications,
      {
        title: '내 채널',
        description: '내가 운영 중인 채널의 상세 페이지로 이동합니다.',
        href: '/creator',
      },
      {
        title: '이벤트 관리',
        description: '현재 진행 중이거나 예정된 이벤트를 관리해요.',
        href: '/creator',
      },
      {
        title: '신청자 관리',
        description: '이벤트별 참가 신청을 검토하고 승인합니다.',
        preview: true,
        previewMessage: '이벤트 상세에서 신청자 관리 섹션을 이용해주세요.',
      },
    ]
  }

  if (role === 'ADMIN') {
    return [
      {
        title: '관리자 콘솔',
        description: '기획자 신청 검수와 신고 처리.',
        href: '/admin',
      },
      notifications,
    ]
  }

  return [notifications]
}

const PreviewIcon: ReactNode = (
  <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7.5v5l3 2" />
  </svg>
)

function formatStartAt(value: string) {
  const d = new Date(value)
  const now = new Date()
  const sameYear = d.getFullYear() === now.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return sameYear ? `${month}.${day} ${hh}:${mm}` : `${d.getFullYear()}.${month}.${day} ${hh}:${mm}`
}

function formatFee(fee: number) {
  return fee === 0 ? '무료' : `${fee.toLocaleString()}원`
}

interface ParticipationCardProps {
  item: MyParticipationItem
  onOpen: (channelId: number, eventId: number) => void
  onOpenTicket: (ticketId: number) => void
}

function ParticipationCard({ item, onOpen, onOpenTicket }: ParticipationCardProps) {
  const isApproved = item.status === 'APPROVED'
  const initial = item.eventTitle.slice(0, 1).toUpperCase()
  // 환불/취소된 티켓이 있으면 "티켓 보기" 버튼은 숨기지만(아래) 카드 자체엔 결제 내역을 노출.
  const hasTicket = isApproved && item.ticketId != null
  // PR77 — 티켓이 REFUNDED/CANCELED 면 "참가 확정" 뱃지가 오해를 일으키므로 ticket 상태로 덮어쓴다.
  const isTerminalTicket =
    item.ticketStatus === 'REFUNDED' || item.ticketStatus === 'CANCELED'
  const statusTone = isTerminalTicket
    ? item.ticketStatus === 'REFUNDED'
      ? 'danger'
      : 'neutral'
    : STATUS_TONE[item.status]
  const statusLabel = isTerminalTicket
    ? TICKET_STATUS_LABEL[item.ticketStatus as 'REFUNDED' | 'CANCELED']
    : STATUS_LABEL[item.status]
  const canViewTicket = hasTicket && !isTerminalTicket

  return (
    <div className={`ct-my-app-card ${isApproved && !isTerminalTicket ? 'is-approved' : ''}`}>
      <button
        type="button"
        className="ct-my-app-card-main"
        onClick={() => onOpen(item.channelId, item.eventId)}
        aria-label={`${item.eventTitle} 상세 보기`}
      >
        <div className="ct-my-app-card-thumb" aria-hidden="true">
          {item.mainImageUrl ? (
            <img
              src={item.mainImageUrl}
              alt=""
              onError={(e) => (e.currentTarget.style.display = 'none')}
            />
          ) : (
            <span className="ct-my-app-card-initial">{initial}</span>
          )}
        </div>
        <div className="ct-my-app-card-body">
          <div className="ct-my-app-card-tags">
            <Badge tone="neutral">{item.channelName}</Badge>
            <Badge tone={statusTone}>{statusLabel}</Badge>
          </div>
          <strong className="ct-my-app-card-title">{item.eventTitle}</strong>
          <ul className="ct-my-app-card-meta">
            <li>
              <span aria-hidden="true">📅</span>
              <span>{formatStartAt(item.startAt)}</span>
            </li>
            <li>
              <span aria-hidden="true">📍</span>
              <span>{item.location}</span>
            </li>
            <li>
              <span aria-hidden="true">🎟</span>
              <span>{formatFee(item.participationFee)}</span>
            </li>
          </ul>
          {hasTicket ? (
            <div className="ct-my-app-card-ticket">
              <span>티켓 #{item.ticketId}</span>
              <span>{item.ticketStatus ? TICKET_STATUS_LABEL[item.ticketStatus] : '발급 완료'}</span>
            </div>
          ) : null}
          {item.paymentAttemptId != null ? (
            <dl className="ct-my-app-card-order">
              <div>
                <dt>주문번호</dt>
                <dd className="ct-my-app-card-order-id" title={item.orderId ?? ''}>
                  {item.orderId ? item.orderId.slice(0, 8) + '…' : `#${item.paymentAttemptId}`}
                </dd>
              </div>
              {item.paidAmount != null ? (
                <div>
                  <dt>결제 금액</dt>
                  <dd>{`${item.paidAmount.toLocaleString()}원`}</dd>
                </div>
              ) : null}
              {item.paymentProvider && item.paymentProvider !== 'NONE' ? (
                <div>
                  <dt>결제 수단</dt>
                  <dd>{item.paymentProvider === 'TOSS' ? '토스' : '테스트'}</dd>
                </div>
              ) : null}
            </dl>
          ) : null}
          {item.status === 'REJECTED' && item.rejectReason ? (
            <p className="ct-my-app-card-reason">사유: {item.rejectReason}</p>
          ) : null}
        </div>
      </button>
      {canViewTicket ? (
        <button
          type="button"
          className="button button-primary ct-my-app-card-ticket-btn"
          onClick={() => onOpenTicket(item.ticketId as number)}
        >
          티켓 보기
        </button>
      ) : hasTicket ? (
        <button
          type="button"
          className="button button-secondary ct-my-app-card-ticket-btn"
          onClick={() => onOpenTicket(item.ticketId as number)}
        >
          영수증 보기
        </button>
      ) : null}
    </div>
  )
}

export function MyPage({ onNavigate }: MyPageProps) {
  const { user } = useAuth()
  const { showToast } = useToast()
  const roleLabel = ROLE_LABEL[(user?.role ?? 'PARTICIPANT') as UserRole] ?? '참가자'
  const entries = buildEntries(user?.role)

  const [items, setItems] = useState<MyParticipationItem[]>([])
  const [loadingItems, setLoadingItems] = useState(true)
  const [loadError, setLoadError] = useState(false)
  // PR52 — 내 이의 제기 내역. 자동 숨김된 콘텐츠가 일반 목록에서 빠지므로 작성자가
  // 본인 appeal 상태를 한눈에 보기 위함. 실제 hidden 콘텐츠 CTA 진입은 후속 PR.
  const [appeals, setAppeals] = useState<ReportAppeal[]>([])
  // PR44: "신청" (전체 신청/참가 흐름) vs "결제" (티켓 + 결제 정보 위주) 탭 분리.
  const [tab, setTab] = useState<MyTab>('requests')
  const [orderFilter, setOrderFilter] = useState<OrderFilter>('ALL')

  const loadItems = useCallback(async () => {
    try {
      const page = await getMyParticipations({ size: 20 })
      setItems(page.content)
      setLoadError(false)
    } catch {
      setItems([])
      setLoadError(true)
    }
  }, [])

  // SSE 알림 수신 시 내 신청/티켓 목록을 새로고침. 짧은 시간에 여러 알림이 도착해도
  // (예: 승인 → 티켓 발급) refetch 는 한 번으로 묶는다 (PR92).
  const { scheduleRefresh: scheduleListRefresh } = useCoalescedRefresh(loadItems)
  useEffect(() => {
    const relevant = new Set([
      'PARTICIPATION_APPROVED',
      'PARTICIPATION_REJECTED',
      'TICKET_ISSUED',
      'TICKET_CHECKED_IN',
      // PR83 — 환불 완료 시 결제 탭의 영수증/상태 뱃지가 즉시 REFUNDED 로 갱신되도록.
      'REFUND_COMPLETED',
    ])
    return notificationStore.onIncoming((n) => {
      if (relevant.has(n.type)) scheduleListRefresh(n.type)
    })
  }, [scheduleListRefresh])

  useEffect(() => {
    let alive = true
    setLoadingItems(true)
    setLoadError(false)
    getMyParticipations({ size: 20 })
      .then((page) => {
        if (!alive) return
        setItems(page.content)
      })
      .catch(() => {
        if (!alive) return
        setItems([])
        setLoadError(true)
      })
      .finally(() => {
        if (alive) setLoadingItems(false)
      })
    return () => {
      alive = false
    }
  }, [])

  // PR52 — 내 이의 제기 목록 (비로그인 또는 빈 결과면 섹션 자체 렌더 X).
  useEffect(() => {
    let alive = true
    getMyReportAppeals({ size: 20 })
      .then((page) => {
        if (alive) setAppeals(page.content)
      })
      .catch(() => {
        if (alive) setAppeals([])
      })
    return () => {
      alive = false
    }
  }, [])

  function handleEntryClick(entry: ActionEntry) {
    if (entry.preview) {
      showToast({
        title: '곧 만나요',
        message: entry.previewMessage ?? '이 기능은 준비 중입니다.',
        tone: 'info',
      })
      return
    }
    if (entry.href) onNavigate(entry.href)
  }

  function openEvent(channelId: number, eventId: number) {
    onNavigate(`/channels/${channelId}/events/${eventId}`)
  }

  function openTicket(ticketId: number) {
    onNavigate(`/tickets/${ticketId}`)
  }

  const sectionTitle = '내 신청 / 티켓'

  // 통계 카드용 — 백엔드가 노출하는 값만 채우고 나머지는 dash 로 둔다.
  const approvedCount = items.filter((it) => it.status === 'APPROVED').length

  // PR44 결제 탭: 실제 결제(paymentAttemptId)가 묶인 항목만 추리고, 사용자가 고른 상태로 한 번 더 필터.
  // 무료 티켓은 "신청" 탭에서 확인 — 결제 탭은 영수증/결제수단을 보여주는 곳이라 결제 없는 무료 티켓이 섞이면 헷갈린다.
  const orderItems = items.filter((it) => it.paymentAttemptId != null)
  const visibleOrderItems = orderItems.filter((it) => {
    if (orderFilter === 'ALL') return true
    return it.ticketStatus === orderFilter
  })

  return (
    <main className="page ct-my-page">
      <section className="ct-my-hero">
        <div className="ct-my-avatar" aria-hidden="true">
          {(user?.nickname ?? 'C').slice(0, 1).toUpperCase()}
        </div>
        <div className="ct-my-meta">
          <strong>{user?.nickname ?? 'CONTENIDO'}</strong>
          <span className="muted">{roleLabel}</span>
        </div>
      </section>

      <section className="ct-my-stats" aria-label="활동 요약">
        <div className="ct-my-stat">
          <strong>{loadingItems ? '—' : approvedCount}</strong>
          <span>참여한 이벤트</span>
        </div>
        <div className="ct-my-stat">
          <strong>—</strong>
          <span>구독 채널</span>
        </div>
        <div className="ct-my-stat">
          <strong>—</strong>
          <span>후기</span>
        </div>
      </section>

      <section className="ct-my-participations" aria-label={sectionTitle}>
        <div className="ct-my-tabs" role="tablist" aria-label="MY 내역 탭">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'requests'}
            className={`ct-my-tab ${tab === 'requests' ? 'is-active' : ''}`}
            onClick={() => setTab('requests')}
          >
            신청 <span className="ct-my-tab-count">{items.length}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'orders'}
            className={`ct-my-tab ${tab === 'orders' ? 'is-active' : ''}`}
            onClick={() => setTab('orders')}
          >
            결제 <span className="ct-my-tab-count">{orderItems.length}</span>
          </button>
        </div>

        {tab === 'orders' ? (
          <div className="ct-my-order-filter" role="tablist" aria-label="결제 상태 필터">
            {ORDER_FILTERS.map((f) => (
              <button
                key={f.value}
                type="button"
                role="tab"
                aria-selected={orderFilter === f.value}
                className={`chip ${orderFilter === f.value ? 'is-active' : ''}`}
                onClick={() => setOrderFilter(f.value)}
              >
                {f.label}
              </button>
            ))}
          </div>
        ) : null}

        {loadingItems ? (
          <div className="stack" aria-hidden="true">
            <Skeleton lines={4} />
            <Skeleton lines={4} />
          </div>
        ) : loadError ? (
          <div className="ct-my-empty">
            <span aria-hidden="true">⚠️</span>
            <strong>{tab === 'orders' ? '결제 내역을 불러오지 못했어요' : '신청 내역을 불러오지 못했어요'}</strong>
            <span className="muted">잠시 후 다시 시도해주세요.</span>
          </div>
        ) : tab === 'requests' ? (
          items.length === 0 ? (
            <div className="ct-my-empty">
              <span aria-hidden="true">🎟</span>
              <strong>아직 신청한 이벤트가 없어요</strong>
              <span className="muted">관심 있는 이벤트를 둘러보고 신청해보세요.</span>
              <button
                type="button"
                className="button button-primary"
                onClick={() => onNavigate('/explore')}
              >
                이벤트 둘러보기
              </button>
            </div>
          ) : (
            <div className="stack">
              {items.map((item) => (
                <ParticipationCard
                  key={item.participationId}
                  item={item}
                  onOpen={openEvent}
                  onOpenTicket={openTicket}
                />
              ))}
            </div>
          )
        ) : visibleOrderItems.length === 0 ? (
          <div className="ct-my-empty">
            <span aria-hidden="true">🧾</span>
            <strong>
              {orderFilter === 'ALL'
                ? '아직 결제 내역이 없어요'
                : '해당 상태의 결제가 없어요'}
            </strong>
            <span className="muted">
              {orderFilter === 'ALL'
                ? '유료 이벤트를 결제하면 여기에 영수증이 모여요.'
                : '다른 상태 필터를 눌러보세요.'}
            </span>
            {orderFilter === 'ALL' ? (
              <button
                type="button"
                className="button button-primary"
                onClick={() => onNavigate('/explore')}
              >
                이벤트 둘러보기
              </button>
            ) : null}
          </div>
        ) : (
          <div className="stack">
            {visibleOrderItems.map((item) => (
              <ParticipationCard
                key={item.participationId}
                item={item}
                onOpen={openEvent}
                onOpenTicket={openTicket}
              />
            ))}
          </div>
        )}
      </section>

      {/* PR52 — 내 이의 제기 내역. 결과가 없으면 섹션 자체 렌더 X. */}
      {appeals.length > 0 ? (
        <section className="section stack" aria-label="내 이의 제기 내역">
          <div className="section-heading">
            <h2>내 이의 제기</h2>
          </div>
          {appeals.map((appeal) => {
            const tone =
              appeal.status === 'APPROVED'
                ? 'success'
                : appeal.status === 'REJECTED'
                ? 'neutral'
                : 'warning'
            const statusLabel =
              appeal.status === 'APPROVED'
                ? '숨김 해제됨'
                : appeal.status === 'REJECTED'
                ? '거절됨'
                : '검토 대기'
            return (
              <article className="card" key={appeal.id}>
                <div className="badge-row">
                  <Badge tone="danger">{APPEAL_TARGET_LABEL[appeal.targetType]}</Badge>
                  <Badge tone={tone}>{statusLabel}</Badge>
                </div>
                <p>
                  <strong>사유:</strong> {appeal.reason}
                </p>
                {appeal.status === 'REJECTED' && appeal.rejectReason ? (
                  <p className="muted">거절 사유: {appeal.rejectReason}</p>
                ) : null}
                <div className="meta-row">
                  <span>#{appeal.targetId}</span>
                  <span>{new Date(appeal.createdAt).toLocaleString()}</span>
                </div>
              </article>
            )
          })}
        </section>
      ) : null}

      <section className="section stack" aria-label="MY 메뉴">
        {entries.map((entry) => (
          <button
            key={entry.title}
            type="button"
            className={`card action-card ${entry.preview ? 'is-preview' : ''}`}
            onClick={() => handleEntryClick(entry)}
            aria-disabled={entry.preview ? true : undefined}
          >
            <strong>{entry.title}</strong>
            <span className="muted">{entry.description}</span>
            {entry.preview ? (
              <span className="action-tag" aria-label="준비 중">
                <span className="action-tag-icon" aria-hidden="true">{PreviewIcon}</span>
                준비 중
              </span>
            ) : null}
          </button>
        ))}
      </section>
    </main>
  )
}
