import { useEffect, useMemo, useState } from 'react'
import { BottomTabBar } from './components/BottomTabBar'
import { ErrorBoundary } from './components/ErrorBoundary'
import { Toast } from './components/Toast'
import { useAuth } from './hooks/useAuth'
import { useNotificationStream } from './hooks/useNotificationStream'
import { AdminPage } from './pages/AdminPage'
import { ChannelDetailPage } from './pages/ChannelDetailPage'
import { CommunityPage } from './pages/CommunityPage'
import { CreatorApplyPage } from './pages/CreatorApplyPage'
import { CreatorDashboardPage } from './pages/CreatorDashboardPage'
import { EventCreatePage } from './pages/EventCreatePage'
import { EventDetailPage } from './pages/EventDetailPage'
import { EventEditPage } from './pages/EventEditPage'
import { ExplorePage } from './pages/ExplorePage'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { MyPage } from './pages/MyPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { OnboardingPage } from './pages/OnboardingPage'
import { PaymentPage } from './pages/PaymentPage'
import { PaymentFailPage, PaymentSuccessPage } from './pages/PaymentResultPages'
import { PlayPage } from './pages/PlayPage'
import { ProfilePage } from './pages/ProfilePage'
import { ProfileViewPage } from './pages/ProfileViewPage'
import { TicketCheckInPage } from './pages/TicketCheckInPage'
import { TicketDetailPage } from './pages/TicketDetailPage'

const ONBOARDED_FLAG = 'contenido-onboarded'

function parseId(pathname: string, prefix: string) {
  const value = pathname.replace(prefix, '').split('/')[0]
  const id = Number(value)
  return Number.isFinite(id) ? id : null
}

export default function App() {
  const { loading, isAuthenticated } = useAuth()
  const [path, setPath] = useState(window.location.pathname)
  // 인증된 사용자에 한해 SSE 연결을 항상 유지 — 어느 화면이든 알림 수신 가능.
  useNotificationStream()

  useEffect(() => {
    const handlePop = () => setPath(window.location.pathname)
    window.addEventListener('popstate', handlePop)
    return () => window.removeEventListener('popstate', handlePop)
  }, [])

  // PR139 — Service worker (Web Push) 등록.
  //   - https / localhost 에서만 등록 시도. file:// 또는 IP-only 호스트에서는 SW unsupported.
  //   - 사용자가 구독을 누르기 전이라도 워커는 미리 install 되어 push event 를 받을 준비를 한다.
  //   - 실패는 조용히 무시 — 푸시는 폴백되어도 SSE 가 살아 있다.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
    const isSecure = window.isSecureContext || window.location.hostname === 'localhost'
    if (!isSecure) return
    navigator.serviceWorker.register('/sw.js').catch(() => {
      /* ignore — push 미지원 환경 */
    })
  }, [])

  // PR139 — service worker 가 notificationclick 시 보내는 navigate 메시지를 router 로 연결.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
    const handler = (event: MessageEvent) => {
      const data = event.data as { source?: string; type?: string; url?: string } | null
      if (!data || data.source !== 'contenido-sw' || data.type !== 'navigate' || !data.url) return
      navigate(data.url)
    }
    navigator.serviceWorker.addEventListener('message', handler)
    return () => navigator.serviceWorker.removeEventListener('message', handler)
  }, [])

  function navigate(nextPath: string) {
    window.history.pushState({}, '', nextPath)
    // 라우트 매칭은 pathname 기준이라 query/hash 는 떼고 저장한다.
    // (페이지가 자체적으로 query/hash 를 읽을 때는 window.location 을 직접 본다.)
    const pathnameOnly = nextPath.split('?')[0].split('#')[0]
    setPath(pathnameOnly)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const page = useMemo(() => {
    // /channels/{cid}/events/new
    const newEvent = path.match(/^\/channels\/(\d+)\/events\/new$/)
    if (newEvent) {
      return <EventCreatePage channelId={Number(newEvent[1])} onNavigate={navigate} />
    }

    // /channels/{cid}/events/{eid}
    const nestedEvent = path.match(/^\/channels\/(\d+)\/events\/(\d+)/)
    if (nestedEvent) {
      const cid = Number(nestedEvent[1])
      const eid = Number(nestedEvent[2])
      return <EventDetailPage channelId={cid} eventId={eid} onNavigate={navigate} />
    }

    // /events/{eid}/edit — owner/admin 만 진입. 권한은 페이지가 체크.
    const editEvent = path.match(/^\/events\/(\d+)\/edit/)
    if (editEvent) {
      return <EventEditPage eventId={Number(editEvent[1])} onNavigate={navigate} />
    }

    // /events/{eid}/payment — 결제 페이지 (PR47, 화면 08)
    const payEvent = path.match(/^\/events\/(\d+)\/payment/)
    if (payEvent) {
      return <PaymentPage eventId={Number(payEvent[1])} onNavigate={navigate} />
    }

    // /events/{eid} — 알림/Studio 진입용 flat 라우트. channelId 는 응답에서 채운다.
    const flatEvent = path.match(/^\/events\/(\d+)/)
    if (flatEvent) {
      return <EventDetailPage eventId={Number(flatEvent[1])} onNavigate={navigate} />
    }

    // /tickets/{tid} — 참가자 티켓 패스/QR 화면
    const ticket = path.match(/^\/tickets\/(\d+)/)
    if (ticket) {
      return <TicketDetailPage ticketId={Number(ticket[1])} onNavigate={navigate} />
    }

    // /check-in — 스태프 체크인 코드 입력 화면
    if (path === '/check-in') return <TicketCheckInPage onNavigate={navigate} />

    // /payments/success — Toss SDK 결제 성공 콜백 (paymentAttemptId/paymentKey/orderId/amount query)
    if (path === '/payments/success') return <PaymentSuccessPage onNavigate={navigate} />
    // /payments/fail — Toss SDK 결제 실패/취소 콜백 (code/message query)
    if (path === '/payments/fail') return <PaymentFailPage onNavigate={navigate} />

    if (path.startsWith('/channels/')) {
      const id = parseId(path, '/channels/')
      return id ? <ChannelDetailPage channelId={id} onNavigate={navigate} /> : <ExplorePage onNavigate={navigate} />
    }

    if (path === '/explore') return <ExplorePage onNavigate={navigate} />
    if (path === '/notifications') return <NotificationsPage onNavigate={navigate} />

    if (path === '/creator/apply') return <CreatorApplyPage />
    if (path === '/creator') return <CreatorDashboardPage onNavigate={navigate} />
    if (path === '/admin') return <AdminPage />
    if (path === '/community') return <CommunityPage onNavigate={navigate} />
    if (path === '/play') return <PlayPage onNavigate={navigate} />
    if (path === '/my') return <MyPage onNavigate={navigate} />
    if (path === '/profile') return <ProfilePage onNavigate={navigate} />

    // PR144 — /users/{userId} 공개 프로필 페이지
    const publicProfile = path.match(/^\/users\/(\d+)/)
    if (publicProfile) {
      return <ProfileViewPage userId={Number(publicProfile[1])} onNavigate={navigate} />
    }

    return <HomePage onNavigate={navigate} />
  }, [path])

  if (loading) {
    return (
      <div className="app-loading">
        <strong>CONTENIDO</strong>
        <span>Loading...</span>
      </div>
    )
  }

  if (!isAuthenticated) {
    // 비인증 사용자는 항상 onboarding 또는 login 만 노출.
    // localStorage 의 ONBOARDED_FLAG 가 없고 사용자가 명시적으로 /login 으로 가지 않은
    // 첫 방문이면 onboarding 부터.
    const hasSeenOnboarding =
      typeof window !== 'undefined' && window.localStorage.getItem(ONBOARDED_FLAG) === '1'
    const wantsLogin = path === '/login'

    if (!hasSeenOnboarding && !wantsLogin) {
      return (
        <>
          <OnboardingPage
            onStart={() => {
              window.localStorage.setItem(ONBOARDED_FLAG, '1')
              navigate('/login')
            }}
            onLogin={() => {
              window.localStorage.setItem(ONBOARDED_FLAG, '1')
              navigate('/login')
            }}
          />
          <Toast />
        </>
      )
    }
    return (
      <>
        <LoginPage onDone={() => navigate('/')} />
        <Toast />
      </>
    )
  }

  return (
    <div className="app-shell">
      <ErrorBoundary>{page}</ErrorBoundary>
      <BottomTabBar currentPath={path} onNavigate={navigate} />
      <Toast />
    </div>
  )
}
