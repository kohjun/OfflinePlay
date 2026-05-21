import { useCallback, useEffect, useState } from 'react'
import {
  ensurePushPermission,
  getActivePushSubscription,
  getPushPermission,
  getPushSupportStatus,
  registerPushSubscription,
  unregisterPushSubscription,
  type PushSupportStatus,
} from '../api/push'
import { useToast } from '../hooks/useToast'

/**
 * PR139 — NotificationsPage 알림 설정 패널에 끼워 넣는 "브라우저 푸시 알림" 섹션.
 *
 *  - SW/PushManager 가 없거나 VAPID 키가 빠지면 비활성 안내만.
 *  - 권한이 denied 면 브라우저 설정에서 풀어야 한다는 안내.
 *  - 권한 prompt 는 사용자 액션(버튼 클릭) 안에서만 호출 (브라우저 정책).
 *  - 등록/해지는 idempotent — 이미 같은 endpoint 면 backend 가 credential 만 갱신.
 */
export function BrowserPushPanel() {
  const { showToast } = useToast()
  const [support, setSupport] = useState<PushSupportStatus>('unsupported')
  const [permission, setPermission] = useState<NotificationPermission | 'unsupported'>('default')
  const [subscribed, setSubscribed] = useState(false)
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)

  const refresh = useCallback(async () => {
    setSupport(getPushSupportStatus())
    setPermission(getPushPermission())
    try {
      const sub = await getActivePushSubscription()
      setSubscribed(sub !== null)
    } catch {
      setSubscribed(false)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function handleSubscribe() {
    if (working) return
    setWorking(true)
    try {
      const grant = await ensurePushPermission()
      setPermission(grant)
      if (grant !== 'granted') {
        showToast({
          title: '브라우저 알림 권한이 필요해요',
          message: grant === 'denied' ? '브라우저 설정에서 알림 권한을 허용해주세요.' : '권한 요청을 다시 시도해주세요.',
          tone: 'warning',
        })
        return
      }
      await registerPushSubscription()
      setSubscribed(true)
      showToast({ title: '브라우저 푸시 알림을 켰어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '푸시 알림 등록에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setWorking(false)
    }
  }

  async function handleUnsubscribe() {
    if (working) return
    setWorking(true)
    try {
      await unregisterPushSubscription()
      setSubscribed(false)
      showToast({ title: '브라우저 푸시 알림을 껐어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '푸시 알림 해지에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setWorking(false)
    }
  }

  // Unsupported / no key fallback — 사용자가 액션할 수 있는 게 없으니 안내만.
  if (support === 'unsupported') {
    return (
      <section className="notification-push" aria-label="브라우저 푸시 알림">
        <h3>브라우저 푸시 알림</h3>
        <p className="muted">이 브라우저에서는 푸시 알림을 사용할 수 없어요.</p>
      </section>
    )
  }
  if (support === 'no-vapid-key') {
    return (
      <section className="notification-push" aria-label="브라우저 푸시 알림">
        <h3>브라우저 푸시 알림</h3>
        <p className="muted">푸시 키가 아직 배포되지 않았어요. 잠시 후 다시 시도해주세요.</p>
      </section>
    )
  }
  if (loading) {
    return (
      <section className="notification-push" aria-label="브라우저 푸시 알림">
        <h3>브라우저 푸시 알림</h3>
        <p className="muted">상태를 확인하는 중…</p>
      </section>
    )
  }

  const denied = permission === 'denied'

  return (
    <section className="notification-push" aria-label="브라우저 푸시 알림">
      <h3>브라우저 푸시 알림</h3>
      <p className="muted">
        앱이 열려 있지 않아도 브라우저 알림으로 새 소식을 받아보세요. 같은 기기에서 한 번만 켜면 됩니다.
      </p>
      {denied ? (
        <p className="muted">
          알림 권한이 차단돼 있어요. 브라우저 주소창 옆 사이트 설정에서 알림을 허용으로 바꿔주세요.
        </p>
      ) : null}
      <div className="notification-push__actions">
        {subscribed ? (
          <button
            type="button"
            className="button button-secondary"
            onClick={handleUnsubscribe}
            disabled={working}
            aria-busy={working}
          >
            {working ? '해지 중…' : '푸시 알림 끄기'}
          </button>
        ) : (
          <button
            type="button"
            className="button button-primary"
            onClick={handleSubscribe}
            disabled={working || denied}
            aria-busy={working}
          >
            {working ? '등록 중…' : '푸시 알림 켜기'}
          </button>
        )}
      </div>
    </section>
  )
}
