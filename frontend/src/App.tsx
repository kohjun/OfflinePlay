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
import { PaymentFailPage, PaymentSuccessPage } from './pages/PaymentResultPages'
import { PlayPage } from './pages/PlayPage'
import { ProfilePage } from './pages/ProfilePage'
import { TicketCheckInPage } from './pages/TicketCheckInPage'
import { TicketDetailPage } from './pages/TicketDetailPage'

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
