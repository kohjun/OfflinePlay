import { useEffect, useRef, useState } from 'react'
import { approveCreatorApplication, getAdminChannels, getCreatorApplications, rejectCreatorApplication } from '../api/admin'
import { Badge } from '../components/Badge'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { Channel, CreatorApplication } from '../types'
import { AdminAppealsSection } from './admin/AdminAppealsSection'
import { AdminAuditLogsSection } from './admin/AdminAuditLogsSection'
import { AdminModerationOverviewSection } from './admin/AdminModerationOverviewSection'
import { AdminReportsSection } from './admin/AdminReportsSection'
import { AdminRetentionSection } from './admin/AdminRetentionSection'

type AdminTab = 'overview' | 'reports' | 'appeals' | 'audit' | 'retention'

const TAB_LABELS: Record<AdminTab, string> = {
  overview: '운영 현황',
  reports: '신고',
  appeals: '이의 제기',
  audit: '감사 로그',
  retention: '보존 정책',
}

const ALL_TABS: AdminTab[] = ['overview', 'reports', 'appeals', 'audit', 'retention']

function readTabFromUrl(): AdminTab {
  const param = new URLSearchParams(window.location.search).get('tab')
  if (param && ALL_TABS.includes(param as AdminTab)) return param as AdminTab
  return 'overview'
}

export function AdminPage() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [activeTab, setActiveTab] = useState<AdminTab>(readTabFromUrl)
  const [mountedTabs, setMountedTabs] = useState<Set<AdminTab>>(() => new Set([readTabFromUrl()]))
  const [applications, setApplications] = useState<CreatorApplication[]>([])
  const [channels, setChannels] = useState<Channel[]>([])
  const isFirstRender = useRef(true)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    params.set('tab', activeTab)
    const url = `${window.location.pathname}?${params.toString()}`
    if (isFirstRender.current) {
      window.history.replaceState({ tab: activeTab }, '', url)
      isFirstRender.current = false
    } else {
      window.history.pushState({ tab: activeTab }, '', url)
    }
  }, [activeTab])

  useEffect(() => {
    function handlePopState() {
      const tab = readTabFromUrl()
      setActiveTab(tab)
      setMountedTabs((prev) => {
        if (prev.has(tab)) return prev
        const next = new Set(prev)
        next.add(tab)
        return next
      })
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  function handleTabChange(tab: AdminTab) {
    setActiveTab(tab)
    setMountedTabs((prev) => {
      if (prev.has(tab)) return prev
      const next = new Set(prev)
      next.add(tab)
      return next
    })
  }

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    Promise.all([getCreatorApplications({ size: 20 }), getAdminChannels({ size: 5 })])
      .then(([applicationPage, channelPage]) => {
        setApplications(applicationPage.content)
        setChannels(channelPage.content)
      })
      .catch((error) => {
        showToast({
          title: '관리자 데이터를 불러오지 못했어요',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
  }, [showToast, user?.role])

  async function handleReview(id: number, status: 'APPROVED' | 'REJECTED') {
    try {
      if (status === 'APPROVED') await approveCreatorApplication(id)
      else await rejectCreatorApplication(id)
      setApplications((items) => items.filter((item) => item.id !== id))
      showToast({
        title: status === 'APPROVED' ? '기획자 신청을 승인했어요' : '기획자 신청을 거절했어요',
        tone: 'success',
      })
    } catch (error) {
      showToast({
        title: '신청 처리에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  if (user?.role !== 'ADMIN') {
    return (
      <main className="page empty-state">
        <h1>관리자 전용 화면이에요</h1>
        <p>CONTENIDO 관리자에게만 보이는 화면입니다.</p>
      </main>
    )
  }

  return (
    <main className="page">
      <section className="page-header">
        <div>
          <p className="eyebrow">Admin</p>
          <h1>CONTENIDO 운영 콘솔</h1>
        </div>
      </section>

      <div className="ct-chip-row" role="tablist" aria-label="운영 콘솔 탭" style={{ marginBottom: '8px' }}>
        {ALL_TABS.map((tab) => (
          <button
            key={tab}
            id={`admin-tab-${tab}`}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            aria-controls={`admin-panel-${tab}`}
            className={`chip${activeTab === tab ? ' is-active' : ''}`}
            onClick={() => handleTabChange(tab)}
          >
            {TAB_LABELS[tab]}
          </button>
        ))}
      </div>

      {mountedTabs.has('overview') ? (
        <div id="admin-panel-overview" role="tabpanel" aria-labelledby="admin-tab-overview" style={{ display: activeTab === 'overview' ? undefined : 'none' }}>
          <AdminModerationOverviewSection />
        </div>
      ) : null}

      {mountedTabs.has('reports') ? (
        <div id="admin-panel-reports" role="tabpanel" aria-labelledby="admin-tab-reports" style={{ display: activeTab === 'reports' ? undefined : 'none' }}>
          <AdminReportsSection />
        </div>
      ) : null}

      {mountedTabs.has('appeals') ? (
        <div id="admin-panel-appeals" role="tabpanel" aria-labelledby="admin-tab-appeals" style={{ display: activeTab === 'appeals' ? undefined : 'none' }}>
          <AdminAppealsSection />
        </div>
      ) : null}

      {mountedTabs.has('audit') ? (
        <div id="admin-panel-audit" role="tabpanel" aria-labelledby="admin-tab-audit" style={{ display: activeTab === 'audit' ? undefined : 'none' }}>
          <AdminAuditLogsSection />
        </div>
      ) : null}

      {mountedTabs.has('retention') ? (
        <div id="admin-panel-retention" role="tabpanel" aria-labelledby="admin-tab-retention" style={{ display: activeTab === 'retention' ? undefined : 'none' }}>
          <AdminRetentionSection />
        </div>
      ) : null}

      <section className="section">
        <div className="section-heading">
          <h2>기획자 신청</h2>
        </div>
        <div className="stack">
          {applications.length === 0 ? (
            <p className="muted">대기 중인 신청이 없어요.</p>
          ) : (
            applications.map((application) => (
              <article className="card admin-card" key={application.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="warning">{application.status}</Badge>
                  </div>
                  <p>{application.reason}</p>
                  {application.portfolioUrl ? (
                    <a href={application.portfolioUrl}>{application.portfolioUrl}</a>
                  ) : null}
                </div>
                <div className="admin-actions">
                  <button className="button button-secondary" onClick={() => handleReview(application.id, 'REJECTED')} type="button">거절</button>
                  <button className="button button-primary" onClick={() => handleReview(application.id, 'APPROVED')} type="button">승인</button>
                </div>
              </article>
            ))
          )}
        </div>
      </section>

      <section className="section">
        <div className="section-heading">
          <h2>Recent channels</h2>
        </div>
        <div className="stack">
          {channels.length === 0 ? (
            <p className="muted">No channels yet.</p>
          ) : (
            channels.map((channel) => (
              <article className="card admin-card" key={channel.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="primary">{channel.categoryDisplayName}</Badge>
                  </div>
                  <strong>{channel.name}</strong>
                  <p>{channel.description}</p>
                  <div className="meta-row">
                    <span>by {channel.ownerNickname}</span>
                    <span>{channel.subscriberCount.toLocaleString()} subscribers</span>
                  </div>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
    </main>
  )
}
