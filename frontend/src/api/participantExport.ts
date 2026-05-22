import { apiClient } from './client'

/**
 * PR154 — 신청자 CSV export. blob 응답 → 사용자 다운로드 트리거.
 *
 *  - 권한: 채널 owner / STAFF / ADMIN. 그 외는 backend 가 403.
 *  - response 는 `text/csv; charset=UTF-8` + `Content-Disposition: attachment`.
 *  - UTF-8 BOM 으로 Excel 호환.
 *  - 호출 시 backend 가 moderation_audit_logs 에 `PARTICIPANT_EXPORTED` 한 줄 기록 — 개인정보 export 추적.
 */
export async function downloadParticipantCsv(eventId: number): Promise<void> {
  const blob = await apiClient.getBlob(`/creator/events/${eventId}/participants/export`)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  a.download = `event-${eventId}-participants-${ts}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
