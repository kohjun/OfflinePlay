import { useEffect, useState } from 'react'
import {
  dismissReport,
  getReports,
  hideModerationTarget,
  resolveReport,
  unhideModerationTarget,
} from '../../api/admin'
import { Badge } from '../../components/Badge'
import { useAuth } from '../../hooks/useAuth'
import { useToast } from '../../hooks/useToast'
import type { Report, ReportTargetType } from '../../types'

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

export function AdminReportsSection() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [reports, setReports] = useState<Report[]>([])
  const [reportFilter, setReportFilter] = useState<ReportFilter>('ALL')

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    const params: Parameters<typeof getReports>[0] =
      reportFilter === 'ALL' ? { size: 20 } : { size: 20, targetType: reportFilter }
    getReports(params)
      .then((page) => setReports(page.content))
      .catch(() => {})
  }, [reportFilter, user?.role])

  async function handleManualHide(targetType: ReportTargetType, targetId: number) {
    const reason = window.prompt('숨김 사유를 입력해주세요 (필수, 최대 255자)')
    if (reason === null) return
    if (reason.trim().length === 0) {
      showToast({ title: '숨김 사유를 입력해주세요', tone: 'warning' })
      return
    }
    try {
      await hideModerationTarget(targetType, targetId, reason.trim())
      setReports((items) =>
        items.map((r) =>
          r.targetType === targetType && r.targetId === targetId
            ? { ...r, targetHidden: true, autoModerated: false }
            : r,
        ),
      )
      showToast({ title: '대상을 숨김 처리했어요', tone: 'success' })
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 409 ? '이미 숨김 처리된 대상이에요' : status === 404 ? '대상을 찾을 수 없어요' : '숨김 처리에 실패했어요'
      showToast({ title, message: error instanceof Error ? error.message : undefined, tone: 'danger' })
    }
  }

  async function handleManualUnhide(targetType: ReportTargetType, targetId: number) {
    if (!window.confirm('숨김을 해제할까요? 관련 이의 제기는 별도로 처리해주세요.')) return
    try {
      await unhideModerationTarget(targetType, targetId)
      setReports((items) =>
        items.map((r) =>
          r.targetType === targetType && r.targetId === targetId
            ? { ...r, targetHidden: false, autoModerated: false }
            : r,
        ),
      )
      showToast({ title: '숨김을 해제했어요', tone: 'success' })
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 400 || status === 409
          ? '숨김 처리되지 않은 대상이에요'
          : status === 404
          ? '대상을 찾을 수 없어요'
          : '숨김 해제에 실패했어요'
      showToast({ title, message: error instanceof Error ? error.message : undefined, tone: 'danger' })
    }
  }

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

  return (
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
                    {report.status === 'RESOLVED' ? '해결됨' : report.status === 'DISMISSED' ? '기각됨' : '대기 중'}
                  </Badge>
                  {report.targetType === 'REVIEW' && report.targetRating != null ? (
                    <span className="ct-rating-chip" aria-label={`대상 별점 ${report.targetRating}`}>
                      <span aria-hidden="true">★</span>
                      <strong>{report.targetRating}</strong>
                    </span>
                  ) : null}
                  {report.targetHidden ? (
                    <Badge tone={report.autoModerated ? 'warning' : 'neutral'}>
                      {report.autoModerated ? '자동 숨김' : '숨김'}
                    </Badge>
                  ) : null}
                </div>
                <p>
                  <strong>사유:</strong> {report.reason}
                </p>
                {report.targetPreview ? (
                  <p className="ct-report-target-preview muted">대상: "{report.targetPreview}"</p>
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
                  {report.targetHidden ? (
                    <button
                      className="button button-secondary"
                      onClick={() => handleManualUnhide(report.targetType, report.targetId)}
                      type="button"
                    >
                      숨김 해제
                    </button>
                  ) : (
                    <button
                      className="button button-secondary"
                      onClick={() => handleManualHide(report.targetType, report.targetId)}
                      type="button"
                    >
                      숨김
                    </button>
                  )}
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
  )
}
