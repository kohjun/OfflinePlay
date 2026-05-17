import type {
  ContentType,
  EventParticipationStatus,
  EventStatus,
} from '../../types'

export const STATUS_LABEL: Record<EventStatus, string> = {
  UPCOMING: '곧 시작',
  ONGOING: '진행 중',
  CLOSED: '종료',
}

export const CONTENT_TYPE_LABEL: Record<ContentType, string> = {
  ORIGINAL: 'Original',
  CLASSIC: 'Classic',
  SPECIAL: 'Special',
}

export const PARTICIPATION_LABEL: Record<EventParticipationStatus, string> = {
  PENDING: '승인 대기 중',
  APPROVED: '참가 확정',
  REJECTED: '신청 거절됨',
  CANCELED: '신청 취소',
}

export const PARTICIPATION_TONE: Record<
  EventParticipationStatus,
  'primary' | 'success' | 'danger' | 'neutral'
> = {
  PENDING: 'primary',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELED: 'neutral',
}

export const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
}

export function statusTone(status: EventStatus) {
  if (status === 'ONGOING') return 'success'
  if (status === 'UPCOMING') return 'primary'
  return 'neutral'
}

export function formatFee(fee: number) {
  return fee === 0 ? '무료' : `${fee.toLocaleString()}원`
}

export function formatDateTime(value: string) {
  const d = new Date(value)
  const yyyy = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${yyyy}.${month}.${day} ${hh}:${mm}`
}

export function formatRange(startAt: string, endAt: string) {
  const start = new Date(startAt)
  const end = new Date(endAt)
  const sameDay =
    start.getFullYear() === end.getFullYear() &&
    start.getMonth() === end.getMonth() &&
    start.getDate() === end.getDate()
  if (sameDay) {
    const hh = String(end.getHours()).padStart(2, '0')
    const mm = String(end.getMinutes()).padStart(2, '0')
    return `${formatDateTime(startAt)} ~ ${hh}:${mm}`
  }
  return `${formatDateTime(startAt)} ~ ${formatDateTime(endAt)}`
}
