import { useEffect, useState } from 'react'
import { approveReportAppeal, getAdminReportAppeals, rejectReportAppeal } from '../../api/reportAppeals'
import { Badge } from '../../components/Badge'
import { useAuth } from '../../hooks/useAuth'
import { useToast } from '../../hooks/useToast'
import type { ReportAppeal, ReportTargetType } from '../../types'

const TARGET_TYPE_LABEL: Record<ReportTargetType, string> = {
  REVIEW: '후기',
  COMMENT: '댓글',
  POST: '공지',
  EVENT: '이벤트',
  CHANNEL: '채널',
}

export function AdminAppealsSection() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [appeals, setAppeals] = useState<ReportAppeal[]>([])

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    getAdminReportAppeals({ size: 20, status: 'PENDING' })
      .then((page) => setAppeals(page.content))
      .catch(() => {})
  }, [user?.role])

  async function handleApproveAppeal(id: number) {
    try {
      const updated = await approveReportAppeal(id)
      setAppeals((items) => items.filter((item) => item.id !== id))
      showToast({ title: '숨김을 해제했어요', tone: 'success' })
      void updated
    } catch (error) {
      showToast({
        title: '이의 제기 승인에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  async function handleRejectAppeal(id: number) {
    const rejectReason = window.prompt('거절 사유를 입력해주세요 (선택)')
    if (rejectReason === null) return
    try {
      await rejectReportAppeal(id, { rejectReason: rejectReason || null })
      setAppeals((items) => items.filter((item) => item.id !== id))
      showToast({ title: '이의 제기를 거절했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '이의 제기 거절에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  return (
    <section className="section">
      <div className="section-heading">
        <h2>이의 제기 큐</h2>
      </div>
      <div className="stack">
        {appeals.length === 0 ? (
          <p className="muted">처리 대기 중인 이의 제기가 없어요.</p>
        ) : (
          appeals.map((appeal) => (
            <article className="card admin-card" key={appeal.id}>
              <div>
                <div className="badge-row">
                  <Badge tone="danger">{TARGET_TYPE_LABEL[appeal.targetType]}</Badge>
                  <Badge tone="warning">이의 제기 대기</Badge>
                  {appeal.targetHidden === false ? (
                    <Badge tone="success">이미 해제됨</Badge>
                  ) : null}
                </div>
                <p>
                  <strong>사유:</strong> {appeal.reason}
                </p>
                {appeal.targetPreview ? (
                  <p className="ct-report-target-preview muted">
                    대상: "{appeal.targetPreview}"
                  </p>
                ) : (
                  <p className="muted">대상이 이미 삭제되었거나 비공개 처리됐어요.</p>
                )}
                <div className="meta-row">
                  <span>신청자: {appeal.requesterNickname}</span>
                  <span>{new Date(appeal.createdAt).toLocaleString()}</span>
                  <span>#{appeal.targetId}</span>
                </div>
              </div>
              <div className="admin-actions">
                <button
                  className="button button-secondary"
                  onClick={() => handleRejectAppeal(appeal.id)}
                  type="button"
                >
                  거절
                </button>
                <button
                  className="button button-primary"
                  onClick={() => handleApproveAppeal(appeal.id)}
                  type="button"
                >
                  승인 (숨김 해제)
                </button>
              </div>
            </article>
          ))
        )}
      </div>
    </section>
  )
}
