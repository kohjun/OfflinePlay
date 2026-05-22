import { useState } from 'react'
import { MannerFeedbackForm } from '../../components/MannerFeedbackForm'
import type { EventApplicant } from '../../types'

interface EventMannerSectionProps {
  eventId: number
  isOwner: boolean
  /** APPROVED 참가자 본인 시 호스트 닉네임/ID. owner 시점에선 무관. */
  hostId: number | null
  hostNickname: string | null
  /** Owner 시점에서 평가할 수 있는 APPROVED 참가자 목록. */
  applicants: EventApplicant[]
  /** 참가 본인의 status (`APPROVED` 일 때만 host 평가 CTA 노출). */
  myParticipationStatus: string | null
}

/**
 * PR146 — 종료된 이벤트 detail 페이지의 "매너 평가" 영역.
 *
 *  - 참가자 (APPROVED) → 호스트 평가 1회.
 *  - 호스트 → 참가자별 평가 1회씩. (host UI: applicants 카드에 "매너 평가" 버튼이 inline form 노출.)
 *
 * 중복 작성은 backend 가 409 반환 → form 의 onError 토스트로 안내.
 * 본 PR 은 "내가 이미 평가했는가" 를 frontend 가 사전 조회하지 않는다 — 다음 PR 에서 보조.
 */
export function EventMannerSection({
  eventId,
  isOwner,
  hostId,
  hostNickname,
  applicants,
  myParticipationStatus,
}: EventMannerSectionProps) {
  // 참가자 본인이 호스트 평가 form 을 열었는지.
  const [participantFormOpen, setParticipantFormOpen] = useState(false)
  // 호스트가 평가 form 을 연 참가자 id.
  const [openApplicantId, setOpenApplicantId] = useState<number | null>(null)

  const canRateHost = !isOwner && myParticipationStatus === 'APPROVED' && hostId != null && hostNickname != null
  const approvedApplicants = applicants.filter((a) => a.status === 'APPROVED')
  const canRateParticipants = isOwner && approvedApplicants.length > 0

  if (!canRateHost && !canRateParticipants) return null

  return (
    <section className="ct-event-section" aria-label="매너 평가">
      <h2 className="ct-event-section-title">매너 평가</h2>
      <p className="muted">행사가 끝났어요. 함께한 사람에게 매너 평가를 남겨주세요. 한 사람당 한 번만 작성할 수 있어요.</p>

      {canRateHost ? (
        participantFormOpen ? (
          <div className="card">
            <div className="card-body">
              <strong>{hostNickname}님 평가</strong>
              <MannerFeedbackForm
                eventId={eventId}
                revieweeId={hostId!}
                revieweeNickname={hostNickname!}
                onSubmitted={() => setParticipantFormOpen(false)}
                onCancel={() => setParticipantFormOpen(false)}
              />
            </div>
          </div>
        ) : (
          <button
            type="button"
            className="button button-primary"
            onClick={() => setParticipantFormOpen(true)}
          >
            호스트 매너 평가하기
          </button>
        )
      ) : null}

      {canRateParticipants ? (
        <ul className="stack manner-rate-list">
          {approvedApplicants.map((a) => (
            <li key={a.participantId} className="card">
              <div className="card-body stack">
                <div className="card-heading-row">
                  <strong>{a.nickname}</strong>
                  {openApplicantId === a.participantId ? null : (
                    <button
                      type="button"
                      className="button button-secondary"
                      onClick={() => setOpenApplicantId(a.participantId)}
                    >
                      매너 평가
                    </button>
                  )}
                </div>
                {openApplicantId === a.participantId ? (
                  <MannerFeedbackForm
                    eventId={eventId}
                    revieweeId={a.participantId}
                    revieweeNickname={a.nickname}
                    onSubmitted={() => setOpenApplicantId(null)}
                    onCancel={() => setOpenApplicantId(null)}
                  />
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}
