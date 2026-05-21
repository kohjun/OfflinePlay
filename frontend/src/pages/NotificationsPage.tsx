import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getNotificationPreferences,
  getNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationPreferences,
} from '../api/notifications'
import { Badge } from '../components/Badge'
import { BrowserPushPanel } from '../components/BrowserPushPanel'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import {
  notificationStore,
  useNotificationStore,
  type StreamStatus,
} from '../stores/notificationStore'
import type { NotificationPreference, NotificationType, UserRole } from '../types'
import {
  getNotificationLabel,
  getNotificationTone,
  NOTIFICATION_PREFERENCE_BUNDLES,
  pathForNotification,
  type NotificationPreferenceBundleId,
} from '../utils/notificationMeta'

const STATUS_LABEL: Record<StreamStatus, string> = {
  connecting: '연결 중',
  connected: '실시간 연결됨',
  disconnected: '실시간 연결 끊김',
}

// PR97 — NotificationType 별 라벨/tone/path 는 utils/notificationMeta.ts 로 통합.
// 본 페이지와 알림 설정 패널이 동일 정의를 공유한다.

const PAGE_SIZE = 20

/**
 * PR104 — preference 의 마지막 저장 시각을 사용자 친화적으로. 새 라이브러리 없이 minute/hour/day
 * 단위만 표현하고, 7일 이상은 로컬 날짜로 fallback. updatedAt 이 없거나 잘못된 값이면 빈 문자열 →
 * 호출처가 "기본값" 대체 텍스트를 띄운다.
 */
function formatRelativeTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Math.max(0, Date.now() - t)
  const m = Math.floor(diff / 60_000)
  if (m < 1) return '방금 전'
  if (m < 60) return `${m}분 전`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}시간 전`
  const d = Math.floor(h / 24)
  if (d < 7) return `${d}일 전`
  return new Date(iso).toLocaleDateString()
}

interface NotificationsPageProps {
  onNavigate: (path: string) => void
}

// PR97 — pathForTarget 는 utils/notificationMeta.pathForNotification 으로 이동.
// 기존 라우팅 규칙은 그대로 유지 (events / channels(NEW_POST/CHANNEL_BANNED 분기) / tickets /
// creator-applications) — 변경 없는 mechanical extraction.

export function NotificationsPage({ onNavigate }: NotificationsPageProps) {
  const { showToast } = useToast()
  const { user } = useAuth()
  const { items, unreadCount, streamStatus } = useNotificationStore()
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  // PR96 — 알림 설정 패널. 기본 닫힘. 처음 열 때만 backend 에서 fetch.
  const [prefsOpen, setPrefsOpen] = useState(false)
  const [preferences, setPreferences] = useState<NotificationPreference[] | null>(null)
  const [prefsLoading, setPrefsLoading] = useState(false)
  const [prefsError, setPrefsError] = useState(false)
  const [savingTypes, setSavingTypes] = useState<Set<NotificationType>>(() => new Set())
  // PR101 — quick mute (카드의 "이 유형 끄기") 중 type 들 + 5초 undo banner state.
  const [quickMuting, setQuickMuting] = useState<Set<NotificationType>>(() => new Set())
  const [undoMute, setUndoMute] = useState<{ type: NotificationType; label: string } | null>(null)
  const undoTimerRef = useRef<number | null>(null)

  // 컴포넌트 unmount 시 펜딩 undo 타이머 정리.
  useEffect(() => {
    return () => {
      if (undoTimerRef.current != null) {
        window.clearTimeout(undoTimerRef.current)
        undoTimerRef.current = null
      }
    }
  }, [])

  function clearUndoTimer() {
    if (undoTimerRef.current != null) {
      window.clearTimeout(undoTimerRef.current)
      undoTimerRef.current = null
    }
  }

  const loadPreferences = useCallback(() => {
    setPrefsLoading(true)
    setPrefsError(false)
    getNotificationPreferences()
      .then((res) => setPreferences(res))
      .catch(() => setPrefsError(true))
      .finally(() => setPrefsLoading(false))
  }, [])

  function handleOpenPreferences() {
    setPrefsOpen((prev) => {
      const next = !prev
      if (next && preferences === null) loadPreferences()
      return next
    })
  }

  /**
   * 토글 즉시 PATCH. 실패하면 해당 type 만 이전 값으로 rollback + error toast.
   * 같은 type 이 saving 중이면 추가 클릭 무시.
   */
  async function handleTogglePreference(type: NotificationType, nextEnabled: boolean) {
    if (savingTypes.has(type)) return
    const prev = preferences
    if (!prev) return
    // optimistic update
    setPreferences(prev.map((p) => (p.type === type ? { ...p, enabled: nextEnabled } : p)))
    setSavingTypes((s) => {
      const next = new Set(s)
      next.add(type)
      return next
    })
    try {
      const saved = await updateNotificationPreferences({
        preferences: [{ type, enabled: nextEnabled }],
      })
      setPreferences(saved)
      showToast({ title: '알림 수신 설정을 저장했어요', tone: 'success' })
    } catch (error) {
      // rollback
      setPreferences(prev)
      showToast({
        title: '설정 저장에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSavingTypes((s) => {
        const next = new Set(s)
        next.delete(type)
        return next
      })
    }
  }

  /**
   * PR99 — 카테고리/전체 묶음 토글. types 의 enabled 값을 한 번의 PATCH 로 갱신.
   *  - optimistic: 해당 types 를 모두 nextEnabled 로 표시
   *  - saving: 해당 types 를 savingTypes 에 추가해 개별 checkbox 와 다른 bundle 버튼도 disabled
   *  - 실패: 이전 preferences snapshot 으로 rollback + error toast
   */
  async function handleBundleToggle(types: readonly NotificationType[], nextEnabled: boolean) {
    if (types.length === 0) return
    const prev = preferences
    if (!prev) return
    // 진행 중인 type 이 하나라도 있으면 무시 (bundle 끼리 충돌 방지).
    if (types.some((t) => savingTypes.has(t))) return

    const wanted = new Set(types)
    setPreferences(prev.map((p) => (wanted.has(p.type) ? { ...p, enabled: nextEnabled } : p)))
    setSavingTypes((s) => {
      const next = new Set(s)
      types.forEach((t) => next.add(t))
      return next
    })
    try {
      const saved = await updateNotificationPreferences({
        preferences: types.map((t) => ({ type: t, enabled: nextEnabled })),
      })
      setPreferences(saved)
      showToast({ title: '알림 수신 설정을 저장했어요', tone: 'success' })
    } catch (error) {
      setPreferences(prev)
      showToast({
        title: '설정 저장에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSavingTypes((s) => {
        const next = new Set(s)
        types.forEach((t) => next.delete(t))
        return next
      })
    }
  }

  /**
   * PR101 — 알림 카드의 "이 유형 끄기" quick mute.
   *
   *  - 해당 type 한 건만 enabled=false 로 PATCH.
   *  - preferences 가 이미 로드돼 있으면 (패널 열려 있던 적 있음) state 동기화 — 패널의
   *    체크박스가 즉시 false 로 반영된다. 로드 안 됐으면 그대로 둔다 (다음에 패널을 열면
   *    backend 에서 최신 상태로 fetch).
   *  - 성공 시 5초 undo banner 를 띄움. 이미 다른 undo 가 떠 있으면 새 type 으로 교체.
   *  - 실패 시 preferences snapshot rollback + error toast.
   */
  async function handleQuickMute(type: NotificationType, label: string) {
    if (quickMuting.has(type)) return
    const prevPrefs = preferences
    if (prevPrefs) {
      setPreferences(prevPrefs.map((p) => (p.type === type ? { ...p, enabled: false } : p)))
    }
    setQuickMuting((s) => {
      const next = new Set(s)
      next.add(type)
      return next
    })
    try {
      const saved = await updateNotificationPreferences({
        preferences: [{ type, enabled: false }],
      })
      // 패널이 한 번이라도 열렸어서 preferences 가 로드된 상태라면 최신 응답으로 동기화.
      // 한 번도 안 열렸으면 다음에 열 때 fresh fetch 되므로 그대로 둔다.
      if (prevPrefs) setPreferences(saved)
      showToast({ title: `${label} 알림을 껐어요`, tone: 'success' })
      // undo banner 띄우기. 기존 펜딩이 있으면 교체.
      clearUndoTimer()
      setUndoMute({ type, label })
      undoTimerRef.current = window.setTimeout(() => {
        undoTimerRef.current = null
        setUndoMute((curr) => (curr?.type === type ? null : curr))
      }, 5000)
    } catch (error) {
      if (prevPrefs) setPreferences(prevPrefs)
      showToast({
        title: '설정 저장에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setQuickMuting((s) => {
        const next = new Set(s)
        next.delete(type)
        return next
      })
    }
  }

  /**
   * PR101 — undo banner 의 "다시 켜기" 액션. 같은 type 을 enabled=true 로 PATCH.
   *  - undo banner 는 즉시 사라지고, 진행 중 가드는 quickMuting 재사용.
   *  - preferences 가 로드돼 있으면 true 로 동기화. 안 됐으면 그대로.
   */
  async function handleUndoMute() {
    const current = undoMute
    if (!current) return
    if (quickMuting.has(current.type)) return
    clearUndoTimer()
    setUndoMute(null)
    const prevPrefs = preferences
    if (prevPrefs) {
      setPreferences(prevPrefs.map((p) => (p.type === current.type ? { ...p, enabled: true } : p)))
    }
    setQuickMuting((s) => {
      const next = new Set(s)
      next.add(current.type)
      return next
    })
    try {
      const saved = await updateNotificationPreferences({
        preferences: [{ type: current.type, enabled: true }],
      })
      if (prevPrefs) setPreferences(saved)
      showToast({ title: `${current.label} 알림을 다시 켰어요`, tone: 'success' })
    } catch (error) {
      if (prevPrefs) setPreferences(prevPrefs)
      showToast({
        title: '되돌리기에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setQuickMuting((s) => {
        const next = new Set(s)
        next.delete(current.type)
        return next
      })
    }
  }

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
    const next = pathForNotification(targetType, targetId, type, user?.role)
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
        <div className="notif-header-actions">
          <button
            type="button"
            className="button button-tertiary"
            onClick={handleOpenPreferences}
            aria-expanded={prefsOpen}
            aria-controls="notification-preferences-panel"
          >
            {prefsOpen ? '알림 설정 닫기' : '알림 설정'}
          </button>
          <button className="button button-secondary" onClick={handleReadAll} disabled={unreadCount === 0} type="button">
            모두 읽음
          </button>
        </div>
      </section>

      {prefsOpen ? (
        <section
          className="notification-preferences"
          id="notification-preferences-panel"
          aria-labelledby="notification-preferences-title"
        >
          <h2 id="notification-preferences-title" className="notification-preferences__title">
            알림 종류별 수신 설정
          </h2>
          <p className="muted notification-preferences__hint">
            끈 알림은 알림 목록에 표시되지 않고 실시간 알림도 도착하지 않아요. 다시 켜면 이후에 도착하는 알림부터 표시됩니다.
          </p>
          <BrowserPushPanel />
          {prefsLoading ? (
            <p className="muted">불러오는 중…</p>
          ) : prefsError ? (
            <div className="notification-preferences__error">
              <p className="muted">설정을 불러오지 못했어요.</p>
              <button type="button" className="button button-secondary" onClick={loadPreferences}>
                다시 시도
              </button>
            </div>
          ) : preferences ? (
            <>
              {(() => {
                // PR99 — 묶음 토글 영역. 각 bundle 의 현재 상태에 따라 "끄기" vs "켜기" 라벨 분기.
                // 모든 type 이 enabled=true 면 "끄기" 버튼, 하나라도 false 면 "켜기" 버튼.
                const prefByType = new Map(preferences.map((p) => [p.type, p.enabled] as const))
                const allTypes: NotificationType[] = preferences.map((p) => p.type)
                const allEnabled = preferences.every((p) => p.enabled)
                const allSaving = allTypes.length > 0 && allTypes.every((t) => savingTypes.has(t))
                return (
                  <section
                    className="notification-preferences__bundles"
                    aria-label="알림 묶음 토글"
                  >
                    <div className="notification-preferences__bundle-row">
                      <strong>전체 알림</strong>
                      <button
                        type="button"
                        className="button button-secondary"
                        disabled={allSaving}
                        aria-busy={allSaving}
                        onClick={() => handleBundleToggle(allTypes, !allEnabled)}
                      >
                        {allEnabled ? '전체 끄기' : '전체 켜기'}
                      </button>
                    </div>
                    {NOTIFICATION_PREFERENCE_BUNDLES.map((bundle) => {
                      const types = bundle.types
                      const bundleEnabled = types.every((t) => prefByType.get(t) ?? true)
                      const bundleSaving = types.some((t) => savingTypes.has(t))
                      const id: NotificationPreferenceBundleId = bundle.id
                      return (
                        <div key={id} className="notification-preferences__bundle-row">
                          <div className="notification-preferences__bundle-label">
                            <strong>{bundle.label}</strong>
                            {bundle.description ? (
                              <span className="muted">{bundle.description}</span>
                            ) : null}
                          </div>
                          <button
                            type="button"
                            className="button button-secondary"
                            disabled={bundleSaving}
                            aria-busy={bundleSaving}
                            onClick={() => handleBundleToggle(types, !bundleEnabled)}
                          >
                            {bundleEnabled ? `${bundle.label} 끄기` : `${bundle.label} 켜기`}
                          </button>
                        </div>
                      )
                    })}
                  </section>
                )
              })()}
              <ul className="notification-preferences__list">
                {preferences.map((pref) => {
                  const id = `notif-pref-${pref.type}`
                  const label = getNotificationLabel(pref.type)
                  const saving = savingTypes.has(pref.type)
                  const relative = formatRelativeTime(pref.updatedAt)
                  return (
                    <li key={pref.type} className="notification-preferences__item">
                      <label htmlFor={id} className="notification-preferences__label">
                        <span className="notification-preferences__label-main">
                          <span>{label}</span>
                          <span className="muted notification-preferences__updated">
                            {relative ? `마지막 저장: ${relative}` : '기본값'}
                          </span>
                        </span>
                        <input
                          id={id}
                          type="checkbox"
                          checked={pref.enabled}
                          disabled={saving}
                          aria-busy={saving}
                          onChange={(e) => handleTogglePreference(pref.type, e.target.checked)}
                        />
                      </label>
                    </li>
                  )
                })}
              </ul>
            </>
          ) : null}
        </section>
      ) : null}

      {loading ? (
        <Skeleton lines={5} />
      ) : items.length === 0 ? (
        <div className="ct-notif-empty">
          <span className="ct-notif-empty__icon" aria-hidden="true">🔔</span>
          <strong>받은 알림이 없어요</strong>
          <span className="muted">
            관심 채널을 구독하면 새 이벤트와 공지가 도착했을 때 바로 알려드려요.
          </span>
          <button
            type="button"
            className="button button-primary"
            onClick={() => onNavigate('/explore')}
          >
            채널 둘러보기
          </button>
        </div>
      ) : (
        <>
          {undoMute ? (
            <div className="notification-undo" role="status" aria-live="polite">
              <span>{undoMute.label} 알림을 껐어요. 실수였다면 5초 안에 되돌릴 수 있어요.</span>
              <button
                type="button"
                className="button button-tertiary notification-undo__action"
                onClick={handleUndoMute}
                disabled={quickMuting.has(undoMute.type)}
                aria-busy={quickMuting.has(undoMute.type)}
              >
                되돌리기
              </button>
            </div>
          ) : null}
          <section className="stack">
            {items.map((item) => {
              const typeLabel = getNotificationLabel(item.type)
              const typeTone = getNotificationTone(item.type)
              const navigable = pathForNotification(item.targetType, item.targetId, item.type, user?.role) !== null
              // PR101 — preferences 가 로드된 상태에서 해당 type 이 이미 false 면 "꺼짐" 표시.
              // 로드 안 됐으면(null) "이 유형 끄기" 버튼을 보이고 클릭 시 바로 PATCH.
              const itemType = item.type as NotificationType
              const muted =
                preferences != null && preferences.some((p) => p.type === itemType && !p.enabled)
              const muting = quickMuting.has(itemType)
              return (
                <div key={item.id} className="notification-row">
                  <button
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
                  {muted ? (
                    <span className="notification-mute notification-mute--off" aria-label={`${typeLabel} 알림 꺼짐`}>꺼짐</span>
                  ) : (
                    <button
                      type="button"
                      className="notification-mute button button-tertiary"
                      onClick={() => handleQuickMute(itemType, typeLabel)}
                      disabled={muting}
                      aria-busy={muting}
                      aria-label={`${typeLabel} 알림 끄기`}
                    >
                      {muting ? '처리 중…' : '이 유형 끄기'}
                    </button>
                  )}
                </div>
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
