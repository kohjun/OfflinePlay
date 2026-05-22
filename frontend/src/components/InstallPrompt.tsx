import { useEffect, useState } from 'react'

/**
 * Chrome / Edge / Android Chrome 이 install 시점 전에 발사하는 `beforeinstallprompt` event.
 * lib.dom 표준에 아직 없으므로 작은 인터페이스로 직접 정의.
 */
interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[]
  prompt(): Promise<void>
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>
}

const DISMISS_KEY = 'contenido.installPromptDismissedAt'
const REMINDER_DAYS = 14

/**
 * PR156 — "앱처럼 설치하기" CTA.
 *
 *  - `beforeinstallprompt` event 를 캐치한 뒤에만 노출. iOS Safari 는 이 event 가 없으므로 본 컴포넌트는
 *    무음 — Safari 사용자에게는 별도 안내 (App Store-like 카드) 가 필요하면 후속 PR.
 *  - "나중에" 클릭 시 localStorage 에 dismissedAt 저장 — 14일간 다시 보여주지 않음.
 *  - 이미 standalone (앱처럼 실행 중) 인 경우 노출 안 함.
 */
export function InstallPrompt() {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null)
  const [installing, setInstalling] = useState(false)

  useEffect(() => {
    if (typeof window === 'undefined') return

    // standalone 모드 (이미 설치된 앱으로 실행) 면 안 보임.
    const isStandalone =
      window.matchMedia('(display-mode: standalone)').matches ||
      // iOS Safari 의 navigator.standalone (비표준)
      (navigator as unknown as { standalone?: boolean }).standalone === true
    if (isStandalone) return

    // 최근 14일 안에 dismissed 한 적이 있으면 노출 안 함.
    const dismissedAt = window.localStorage.getItem(DISMISS_KEY)
    if (dismissedAt) {
      const ts = Number(dismissedAt)
      if (Number.isFinite(ts) && Date.now() - ts < REMINDER_DAYS * 24 * 60 * 60 * 1000) {
        return
      }
    }

    function handle(e: Event) {
      // Chrome / Edge / Android Chrome 의 표준. 기본 mini-infobar 를 막고 우리 UI 로 표시.
      e.preventDefault()
      setDeferred(e as BeforeInstallPromptEvent)
    }

    window.addEventListener('beforeinstallprompt', handle)
    return () => window.removeEventListener('beforeinstallprompt', handle)
  }, [])

  if (!deferred) return null

  async function handleInstall() {
    if (!deferred || installing) return
    setInstalling(true)
    try {
      await deferred.prompt()
      const outcome = await deferred.userChoice
      if (outcome.outcome === 'accepted' || outcome.outcome === 'dismissed') {
        // 어떤 결과든 같은 deferred event 는 재사용 불가 — 숨김.
        setDeferred(null)
      }
    } finally {
      setInstalling(false)
    }
  }

  function handleDismiss() {
    window.localStorage.setItem(DISMISS_KEY, String(Date.now()))
    setDeferred(null)
  }

  return (
    <aside className="install-prompt" role="region" aria-label="앱 설치 안내">
      <div className="install-prompt__body">
        <strong>앱처럼 설치하기</strong>
        <p className="muted">
          홈 화면에 추가하면 push 알림이 더 안정적으로 도착하고, 상단 주소창 없이 모임에만 집중할 수 있어요.
        </p>
      </div>
      <div className="install-prompt__actions">
        <button
          type="button"
          className="button button-tertiary"
          onClick={handleDismiss}
          disabled={installing}
        >
          나중에
        </button>
        <button
          type="button"
          className="button button-primary"
          onClick={handleInstall}
          disabled={installing}
          aria-busy={installing}
        >
          {installing ? '설치 중…' : '설치하기'}
        </button>
      </div>
    </aside>
  )
}
