import { useState } from 'react'
import { downloadParticipantCsv } from '../../api/participantExport'
import { Badge } from '../../components/Badge'
import type { EventCheckInSummary } from '../../api/tickets'
import { useToast } from '../../hooks/useToast'
import type { EventApplicant } from '../../types'
import {
  PARTICIPATION_LABEL,
  PARTICIPATION_TONE,
  formatDateTime,
} from './eventDetailFormatters'

interface EventOwnerPanelProps {
  eventId: number
  applicants: EventApplicant[]
  reviewingId: number | null
  checkInSummary: EventCheckInSummary | null
  onNavigate: (path: string) => void
  onApprove: (participationId: number) => void
  onReject: (participationId: number) => void
}

/**
 * PR84 — owner 전용 패널: 이벤트 수정 버튼 + 신청자 관리 + 체크인 현황.
 * checkInSummary 가 아직 로드되지 않으면 체크인 섹션은 mount 되지 않는다(기존 동작 유지).
 */
export function EventOwnerPanel({
  eventId,
  applicants,
  reviewingId,
  checkInSummary,
  onNavigate,
  onApprove,
  onReject,
}: EventOwnerPanelProps) {
  const { showToast } = useToast()
  const pendingApplicants = applicants.filter((a) => a.status === 'PENDING').length
  const [exporting, setExporting] = useState(false)

  async function handleExport() {
    if (exporting) return
    setExporting(true)
    try {
      await downloadParticipantCsv(eventId)
      showToast({ title: '신청자 CSV 를 내려받았어요', tone: 'success' })
    } catch (err) {
      showToast({
        title: 'CSV 내보내기에 실패했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setExporting(false)
    }
  }

  return (
    <>
      <section className="ct-event-section ct-event-owner-actions">
        <button
          type="button"
          className="button button-secondary is-block"
          onClick={() => onNavigate(`/events/${eventId}/edit`)}
        >
          이벤트 수정
        </button>
      </section>

      <section id="applicants" className="ct-event-section ct-applicants-section">
        <div className="section-heading">
          <h2 className="ct-event-section-title">
            신청자 관리 {applicants.length > 0 ? `(${applicants.length})` : ''}
          </h2>
          <div className="ct-applicants-actions">
            {pendingApplicants > 0 ? (
              <span className="badge badge-primary">대기 {pendingApplicants}</span>
            ) : null}
            <button
              type="button"
              className="button button-tertiary"
              onClick={handleExport}
              disabled={exporting || applicants.length === 0}
              aria-busy={exporting}
              title="신청자 목록을 CSV 로 내보내요. 개인정보 보호를 위해 전화번호는 마스킹됩니다."
            >
              {exporting ? '내보내는 중…' : 'CSV 내보내기'}
            </button>
          </div>
        </div>
        {applicants.length === 0 ? (
          <div className="ct-applicants-empty">
            <span aria-hidden="true">📭</span>
            <strong>아직 신청자가 없어요</strong>
            <span className="muted">참가 신청이 들어오면 여기에서 승인/거절할 수 있어요.</span>
          </div>
        ) : (
          <ul className="ct-applicants-list">
            {applicants.map((a) => {
              const isReviewing = reviewingId === a.id
              return (
                <li key={a.id} className="ct-applicant-card">
                  <div className="ct-applicant-head">
                    <div className="ct-applicant-avatar" aria-hidden="true">
                      {a.nickname.slice(0, 1).toUpperCase()}
                    </div>
                    <div className="ct-applicant-meta">
                      <strong>{a.nickname}</strong>
                      <span className="muted">{formatDateTime(a.joinedAt)} 신청</span>
                    </div>
                    <Badge tone={PARTICIPATION_TONE[a.status]}>{PARTICIPATION_LABEL[a.status]}</Badge>
                  </div>
                  {a.rejectReason ? (
                    <p className="ct-applicant-reason">거절 사유: {a.rejectReason}</p>
                  ) : null}
                  {a.status === 'PENDING' ? (
                    <div className="ct-applicant-actions">
                      <button
                        type="button"
                        className="button button-secondary"
                        onClick={() => onReject(a.id)}
                        disabled={isReviewing}
                        aria-busy={isReviewing}
                      >
                        거절
                      </button>
                      <button
                        type="button"
                        className="button button-primary"
                        onClick={() => onApprove(a.id)}
                        disabled={isReviewing}
                        aria-busy={isReviewing}
                      >
                        {isReviewing ? <span className="button-spinner" aria-hidden="true" /> : null}
                        승인
                      </button>
                    </div>
                  ) : null}
                  {a.status === 'APPROVED' && a.ticketId ? (
                    <div className="ct-applicant-actions ct-applicant-actions-single">
                      <button
                        type="button"
                        className="button button-secondary"
                        onClick={() => onNavigate(`/tickets/${a.ticketId}`)}
                      >
                        티켓 확인
                      </button>
                    </div>
                  ) : null}
                </li>
              )
            })}
          </ul>
        )}
      </section>

      {checkInSummary ? (
        <section id="check-ins" className="ct-event-section ct-checkin-section">
          <div className="section-heading">
            <h2 className="ct-event-section-title">체크인 현황</h2>
            <button
              type="button"
              className="text-button"
              onClick={() => onNavigate('/check-in')}
            >
              코드 체크인 →
            </button>
          </div>

          <div className="ct-checkin-summary">
            <div className="ct-checkin-tile">
              <span>발급</span>
              <strong>{checkInSummary.issuedCount}</strong>
            </div>
            <div className="ct-checkin-tile ct-checkin-tile-success">
              <span>체크인</span>
              <strong>{checkInSummary.checkedInCount}</strong>
            </div>
            <div className="ct-checkin-tile">
              <span>미입장</span>
              <strong>{checkInSummary.notCheckedInCount}</strong>
            </div>
          </div>

          {checkInSummary.tickets.length === 0 ? (
            <p className="muted ct-checkin-empty">아직 발급된 티켓이 없어요.</p>
          ) : (
            <ul className="ct-checkin-list">
              {checkInSummary.tickets.map((t) => (
                <li key={t.ticketId} className="ct-checkin-item">
                  <div className="ct-checkin-item-meta">
                    <strong>{t.buyerNickname}</strong>
                    <span className="muted">티켓 #{t.ticketId}</span>
                  </div>
                  <Badge
                    tone={
                      t.status === 'USED'
                        ? 'success'
                        : t.status === 'PAID'
                          ? 'primary'
                          : t.status === 'CANCELED' || t.status === 'REFUNDED'
                            ? 'danger'
                            : 'neutral'
                    }
                  >
                    {t.status === 'USED'
                      ? '체크인'
                      : t.status === 'PAID'
                        ? '미입장'
                        : t.status === 'CANCELED'
                          ? '취소'
                          : t.status === 'REFUNDED'
                            ? '환불'
                            : t.status}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
    </>
  )
}
