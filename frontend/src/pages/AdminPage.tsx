import { useEffect, useState } from 'react'
import {
  approveCreatorApplication,
  dismissReport,
  getAdminChannels,
  getCreatorApplications,
  getReports,
  rejectCreatorApplication,
  resolveReport,
} from '../api/admin'
import { Badge } from '../components/Badge'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { Channel, CreatorApplication, Report } from '../types'

export function AdminPage() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [applications, setApplications] = useState<CreatorApplication[]>([])
  const [channels, setChannels] = useState<Channel[]>([])
  const [reports, setReports] = useState<Report[]>([])

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    Promise.all([
      getCreatorApplications({ size: 20 }),
      getAdminChannels({ size: 5 }),
      getReports({ size: 20 }),
    ])
      .then(([applicationPage, channelPage, reportPage]) => {
        setApplications(applicationPage.content)
        setChannels(channelPage.content)
        setReports(reportPage.content)
      })
      .catch((error) => {
        showToast({
          title: 'Admin data could not load',
          message: error instanceof Error ? error.message : 'Please try again.',
          tone: 'danger',
        })
      })
  }, [showToast, user?.role])

  async function handleResolveReport(id: number, action: 'RESOLVED' | 'DISMISSED') {
    try {
      const updated = action === 'RESOLVED' ? await resolveReport(id) : await dismissReport(id)
      setReports((items) => items.map((item) => (item.id === id ? updated : item)))
      showToast({ title: action === 'RESOLVED' ? 'Report resolved' : 'Report dismissed', tone: 'success' })
    } catch (error) {
      showToast({
        title: 'Could not update report',
        message: error instanceof Error ? error.message : 'Please try again.',
        tone: 'danger',
      })
    }
  }

  async function handleReview(id: number, status: 'APPROVED' | 'REJECTED') {
    try {
      if (status === 'APPROVED') await approveCreatorApplication(id)
      else await rejectCreatorApplication(id)
      setApplications((items) => items.filter((item) => item.id !== id))
      showToast({ title: `Application ${status.toLowerCase()}`, tone: 'success' })
    } catch (error) {
      showToast({
        title: 'Could not update application',
        message: error instanceof Error ? error.message : 'Please try again.',
        tone: 'danger',
      })
    }
  }

  if (user?.role !== 'ADMIN') {
    return (
      <main className="page empty-state">
        <h1>Admin access required</h1>
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
      {/* TODO(PR-spec-alignment): /admin/stats not yet exposed by backend. */}
      <section className="section">
        <div className="section-heading">
          <h2>Creator applications</h2>
        </div>
        <div className="stack">
          {applications.length === 0 ? (
            <p className="muted">No pending applications.</p>
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
                  <button
                    className="button button-secondary"
                    onClick={() => handleReview(application.id, 'REJECTED')}
                    type="button"
                  >
                    Reject
                  </button>
                  <button
                    className="button button-primary"
                    onClick={() => handleReview(application.id, 'APPROVED')}
                    type="button"
                  >
                    Approve
                  </button>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
      <section className="section">
        <div className="section-heading">
          <h2>Reports</h2>
        </div>
        <div className="stack">
          {reports.length === 0 ? (
            <p className="muted">No pending reports.</p>
          ) : (
            reports.map((report) => (
              <article className="card admin-card" key={report.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="danger">{report.targetType}</Badge>
                    <Badge
                      tone={
                        report.status === 'RESOLVED'
                          ? 'success'
                          : report.status === 'DISMISSED'
                          ? 'neutral'
                          : 'warning'
                      }
                    >
                      {report.status}
                    </Badge>
                  </div>
                  <p>{report.reason}</p>
                  <div className="meta-row">
                    <span>by {report.reporterNickname}</span>
                    <span>{new Date(report.createdAt).toLocaleString()}</span>
                  </div>
                </div>
                {report.status === 'PENDING' ? (
                  <div className="admin-actions">
                    <button
                      className="button button-secondary"
                      onClick={() => handleResolveReport(report.id, 'DISMISSED')}
                      type="button"
                    >
                      Dismiss
                    </button>
                    <button
                      className="button button-primary"
                      onClick={() => handleResolveReport(report.id, 'RESOLVED')}
                      type="button"
                    >
                      Resolve
                    </button>
                  </div>
                ) : null}
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
