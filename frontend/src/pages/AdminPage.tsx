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
import type { Channel, CreatorApplication, Report, ReportTargetType } from '../types'

type ReportFilter = 'ALL' | ReportTargetType

const REPORT_FILTERS: Array<{ value: ReportFilter; label: string }> = [
  { value: 'ALL', label: '전체' },
  { value: 'REVIEW', label: '후기' },
  { value: 'COMMENT', label: '댓글' },
  { value: 'POST', label: '게시글' },
  { value: 'EVENT', label: '이벤트' },
  { value: 'CHANNEL', label: '채널' },
]

const TARGET_TYPE_LABEL: Record<ReportTargetType, string> = {
  CHANNEL: '채널',
  POST: '게시글',
  EVENT: '이벤트',
  COMMENT: '댓글',
  REVIEW: '후기',
}

export function AdminPage() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [applications, setApplications] = useState<CreatorApplication[]>([])
  const [channels, setChannels] = useState<Channel[]>([])
  const [reports, setReports] = useState<Report[]>([])
  const [reportFilter, setReportFilter] = useState<ReportFilter>('ALL')

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
          title: '관리자 데이터를 불러오지 못했어요',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
  }, [showToast, user?.role])

  // PR48 — 신고 필터 변경 시 해당 targetType 만 다시 받는다.
  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    const params: Parameters<typeof getReports>[0] =
      reportFilter === 'ALL' ? { size: 20 } : { size: 20, targetType: reportFilter }
    getReports(params)
      .then((page) => setReports(page.content))
      .catch(() => {
        /* non-fatal — 초기 로드 toast 가 이미 떴거나 일시 오류 */
      })
  }, [reportFilter, user?.role])

  async function handleResolveReport(id: number, action: 'RESOLVED' | 'DISMISSED') {
    try {
      const updated = action === 'RESOLVED' ? await resolveReport(id) : await dismissReport(id)
      setReports((items) => items.map((item) => (item.id === id ? updated : item)))
      showToast({ title: action === 'RESOLVED' ? '신고를 해결 처리했어요' : '신고를 기각했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '신고 처리에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

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
      {/* TODO(PR-spec-alignment): /admin/stats not yet exposed by backend. */}
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
                  <button
                    className="button button-secondary"
                    onClick={() => handleReview(application.id, 'REJECTED')}
                    type="button"
                  >
                    거절
                  </button>
                  <button
                    className="button button-primary"
                    onClick={() => handleReview(application.id, 'APPROVED')}
                    type="button"
                  >
                    승인
                  </button>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
      <section className="section">
        <div className="section-heading">
          <h2>신고 목록</h2>
        </div>
        <div className="ct-chip-row" role="tablist" aria-label="신고 타입 필터">
          {REPORT_FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              role="tab"
              aria-selected={reportFilter === f.value}
              className={`chip ${reportFilter === f.value ? 'is-active' : ''}`}
              onClick={() => setReportFilter(f.value)}
            >
              {f.label}
            </button>
          ))}
        </div>
        <div className="stack">
          {reports.length === 0 ? (
            <p className="muted">처리 대기 중인 신고가 없어요.</p>
          ) : (
            reports.map((report) => (
              <article className="card admin-card" key={report.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="danger">{TARGET_TYPE_LABEL[report.targetType]}</Badge>
                    <Badge
                      tone={
                        report.status === 'RESOLVED'
                          ? 'success'
                          : report.status === 'DISMISSED'
                          ? 'neutral'
                          : 'warning'
                      }
                    >
                      {report.status === 'RESOLVED'
                        ? '해결됨'
                        : report.status === 'DISMISSED'
                        ? '기각됨'
                        : '대기 중'}
                    </Badge>
                    {/* PR48: REVIEW 신고일 때 별점 칩 */}
                    {report.targetType === 'REVIEW' && report.targetRating != null ? (
                      <span className="ct-rating-chip" aria-label={`대상 별점 ${report.targetRating}`}>
                        <span aria-hidden="true">★</span>
                        <strong>{report.targetRating}</strong>
                      </span>
                    ) : null}
                  </div>
                  <p>
                    <strong>사유:</strong> {report.reason}
                  </p>
                  {report.targetPreview ? (
                    <p className="ct-report-target-preview muted">
                      대상: “{report.targetPreview}”
                    </p>
                  ) : (
                    <p className="muted">대상이 이미 삭제되었거나 비공개 처리됐어요.</p>
                  )}
                  <div className="meta-row">
                    <span>신고자: {report.reporterNickname}</span>
                    <span>{new Date(report.createdAt).toLocaleString()}</span>
                    <span>#{report.targetId}</span>
                  </div>
                </div>
                {report.status === 'PENDING' ? (
                  <div className="admin-actions">
                    <button
                      className="button button-secondary"
                      onClick={() => handleResolveReport(report.id, 'DISMISSED')}
                      type="button"
                    >
                      기각
                    </button>
                    <button
                      className="button button-primary"
                      onClick={() => handleResolveReport(report.id, 'RESOLVED')}
                      type="button"
                    >
                      해결 처리
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
