import { useEffect, useRef, useState } from 'react'
import {
  getNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '../api/notifications'
import { Badge } from '../components/Badge'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import {
  notificationStore,
  useNotificationStore,
  type StreamStatus,
} from '../stores/notificationStore'
import type { UserRole } from '../types'

const STATUS_LABEL: Record<StreamStatus, string> = {
  connecting: '연결 중',
  connected: '실시간 연결됨',
  disconnected: '실시간 연결 끊김',
}

const TYPE_LABEL: Record<string, string> = {
  NEW_EVENT: '새 이벤트',
  NEW_POST: '새 글',
  NEW_COMMENT: '새 댓글',
  NEW_LIKE: '좋아요',
  APPLICATION_APPROVED: '기획자 승인',
  APPLICATION_REJECTED: '기획자 거절',
  PARTICIPATION_REQUESTED: '참가 신청',
  PARTICIPATION_APPROVED: '신청 승인',
  PARTICIPATION_REJECTED: '신청 거절',
  PARTICIPATION_CANCELED: '참가 취소',
  TICKET_ISSUED: '티켓 발급',
  TICKET_CHECKED_IN: '체크인 완료',
}

type NotificationTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

const TYPE_TONE: Record<string, NotificationTone> = {
  NEW_EVENT: 'primary',
  NEW_POST: 'neutral',
  NEW_COMMENT: 'neutral',
  NEW_LIKE: 'neutral',
  APPLICATION_APPROVED: 'success',
  APPLICATION_REJECTED: 'danger',
  PARTICIPATION_REQUESTED: 'primary',
  PARTICIPATION_APPROVED: 'success',
  PARTICIPATION_REJECTED: 'danger',
  PARTICIPATION_CANCELED: 'neutral',
  TICKET_ISSUED: 'success',
  TICKET_CHECKED_IN: 'neutral',
}

const PAGE_SIZE = 20

interface NotificationsPageProps {
  onNavigate: (path: string) => void
}

/**
 * 알림 targetType + type -> 이동 경로 변환. 모르는 타입은 null 을 반환해 카드 클릭이
 * 읽음 처리만 하게 한다.
 *
 * creator-applications 는 보는 사람의 역할에 따라 의미가 다르다:
 *  - ADMIN 은 관리자 콘솔에서 신청을 검수한다 → /admin
 *  - 그 외(PARTICIPANT/CREATOR) 는 자기 신청 결과를 MY 에서 확인 → /my
 *
 * NEW_POST 는 targetType="channels" 인데 채널 상세의 공지 탭으로 직행해야 하므로
 * `?tab=posts` 쿼리를 붙인다.
 */
function pathForTarget(
  targetType: string,
  targetId: number,
  type: string,
  viewerRole?: UserRole,
): string | null {
  switch (targetType) {
    case 'events':
      return `/events/${targetId}`
    case 'channels':
      return type === 'NEW_POST' ? `/channels/${targetId}?tab=posts` : `/channels/${targetId}`
    case 'tickets':
      return `/tickets/${targetId}`
    case 'creator-applications':
      return viewerRole === 'ADMIN' ? '/admin' : '/my'
    default:
      return null
  }
}

export function NotificationsPage({ onNavigate }: NotificationsPageProps) {
  const { showToast } = useToast()
  const { user } = useAuth()
  const { items, unreadCount, streamStatus } = useNotificationStore()
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)

  useEffect(() => {
    let alive = true
    getNotifications({ page: 0, size: PAGE_SIZE })
      .then((response) => {
        if (!alive) return
        notificationStore.setItems(response.content)
        setPage(response.currentPage)
        setHasMore(!response.isLast)
      })
      .catch((error) => {
        showToast({
          title: '알림을 불러오지 못했어요',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'warning',
        })
      })
      .finally(() => {
        if (alive) setLoading(false)
      })

    return () => {
      alive = false
    }
  }, [showToast])

  // SSE 로 수신된 새 알림은 App 레벨 스트림이 store 에 prepend 한다. 페이지에서는
  // 토스트만 띄워 사용자 시선을 끌어준다.
  //
  // 같은 (targetType, targetId) 알림이 5초 내 여러 번 오면 토스트는 한 번만.
  // 예: owner 가 승인 → 거의 동시에 PARTICIPATION_APPROVED + TICKET_ISSUED 두 알림이
  //     같은 target 으로 도착하는 케이스에서 화면이 토스트 두 개로 시끄러워지는 것을 막는다.
  const toastDedupeRef = useRef<Map<string, number>>(new Map())
  useEffect(() => {
    return notificationStore.onIncoming((incoming) => {
      const key =
        incoming.targetType && incoming.targetId != null
          ? `${incoming.targetType}:${incoming.targetId}`
          : null
      if (key) {
        const now = Date.now()
        const lastAt = toastDedupeRef.current.get(key)
        if (lastAt != null && now - lastAt < 5000) return
        toastDedupeRef.current.set(key, now)
      }
      showToast({ title: incoming.title, message: incoming.message, tone: 'info' })
    })
  }, [showToast])

  function handleRead(id: number, wasRead: boolean) {
    if (wasRead) return // 이미 읽은 상태면 굳이 서버 호출하지 않는다
    // optimistic: 일단 읽음 처리, 실패 시 unread 로 복구하고 조용한 toast.
    notificationStore.markRead(id)
    markNotificationRead(id).catch(() => {
      notificationStore.markUnread(id)
      showToast({
        title: '읽음 처리에 실패했어요',
        message: '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      })
    })
  }

  function handleClick(id: number, targetType: string, targetId: number, type: string, wasRead: boolean) {
    handleRead(id, wasRead)
    const next = pathForTarget(targetType, targetId, type, user?.role)
    if (next) onNavigate(next)
  }

  async function handleReadAll() {
    // optimistic — 실패해도 다음 진입 시 서버 상태로 정렬됨.
    notificationStore.markAllRead()
    try {
      await markAllNotificationsRead()
    } catch (error) {
      showToast({
        title: '전체 읽음 처리에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      })
    }
  }

  async function handleLoadMore() {
    if (loadingMore || !hasMore) return
    setLoadingMore(true)
    try {
      const next = page + 1
      const response = await getNotifications({ page: next, size: PAGE_SIZE })
      // appendItems dedupes by id, so an SSE-prepended notification that also
      // appears in the next page payload won't be duplicated.
      notificationStore.appendItems(response.content)
      setPage(response.currentPage)
      setHasMore(!response.isLast)
    } catch (error) {
      showToast({
        title: '더 불러오지 못했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'warning',
      })
    } finally {
      setLoadingMore(false)
    }
  }

  return (
    <main className="page">
      <section className="page-header">
        <div>
          <p className="eyebrow">Notifications</p>
          <h1>{unreadCount > 0 ? `안 읽은 알림 ${unreadCount}건` : '모두 확인했어요'}</h1>
          <span className={`stream-status stream-status-${streamStatus}`}>
            <span className="stream-status-dot" aria-hidden="true" />
            {STATUS_LABEL[streamStatus]}
          </span>
        </div>
        <button className="button button-secondary" onClick={handleReadAll} disabled={unreadCount === 0} type="button">
          모두 읽음
        </button>
      </section>
      {loading ? (
        <Skeleton lines={5} />
      ) : (
        <>
          <section className="stack">
            {items.map((item) => {
              const typeLabel = TYPE_LABEL[item.type] ?? item.type
              const typeTone = TYPE_TONE[item.type] ?? 'neutral'
              const navigable = pathForTarget(item.targetType, item.targetId, item.type, user?.role) !== null
              return (
                <button
                  key={item.id}
                  className={`notification-item ${item.isRead ? '' : 'is-unread'}${navigable ? ' is-navigable' : ''}`}
                  onClick={() => handleClick(item.id, item.targetType, item.targetId, item.type, item.isRead)}
                  type="button"
                  aria-label={navigable ? `${item.title} — 자세히 보기` : item.title}
                >
                  <div>
                    <div className="card-heading-row">
                      <strong>{item.title}</strong>
                      {!item.isRead ? <Badge tone="primary">New</Badge> : null}
                    </div>
                    <div className="notification-type-row">
                      <Badge tone={typeTone}>{typeLabel}</Badge>
                      {navigable ? (
                        <span className="notification-cta-hint" aria-hidden="true">자세히 →</span>
                      ) : null}
                    </div>
                    <p>{item.message}</p>
                    <span className="muted">{new Date(item.createdAt).toLocaleString()}</span>
                  </div>
                </button>
              )
            })}
          </section>
          {hasMore ? (
            <div className="load-more-row">
              <button
                className="button button-secondary"
                onClick={handleLoadMore}
                disabled={loadingMore}
                type="button"
              >
                {loadingMore ? '불러오는 중...' : '더 보기'}
              </button>
            </div>
          ) : null}
        </>
      )}
    </main>
  )
}
