import type { TicketStatus } from './ticket'

/** 이벤트(콘텐츠) 유형. 홈 화면 콘텐츠 유형 섹션과 매핑. */
export type ContentType = 'ORIGINAL' | 'CLASSIC' | 'SPECIAL'

export type EventStatus = 'UPCOMING' | 'ONGOING' | 'CLOSED'

/** Mirrors backend EventResponse. */
export interface Event {
  id: number
  channelId: number
  channelName: string
  /** Channel owner(기획자) user id — used to decide whether to show the applicant panel. */
  channelOwnerId: number
  title: string
  description: string
  location: string
  mainImageUrl: string
  startAt: string
  endAt: string
  maxParticipants: number
  currentParticipants: number
  participationFee: number
  refundPolicy: string
  detailContent: string
  status: EventStatus
  /** Backend may return null for legacy rows; new events always set this. */
  contentType?: ContentType | null
  createdAt: string
  /** Backend may add later; currently optimistic from client side. */
  isJoined?: boolean
  /** PR46 — 후기 집계. 후기 0건이면 averageRating=null, reviewCount=0. */
  averageRating?: number | null
  reviewCount?: number
}

/**
 * 이벤트 참가 신청 상태. 백엔드 ParticipationStatus enum과 동기화.
 *  - PENDING  : 신청 완료, 기획자 승인 대기
 *  - APPROVED : 승인 완료, 참가 확정
 *  - REJECTED : 거절됨 (rejectReason 포함 가능)
 *  - CANCELED : 본인이 PENDING 단계에서 취소
 */
export type EventParticipationStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED'

/** Mirrors backend ParticipationResponse — 참가자 본인 상태. */
export interface MyParticipation {
  id: number
  eventId: number
  status: EventParticipationStatus
  joinedAt: string
  reviewedAt: string | null
  rejectReason: string | null
  /** APPROVED 이고 무료 티켓이 발급된 경우에만 채워진다. */
  ticketId?: number | null
  ticketStatus?: TicketStatus | null
}

/** Mirrors backend ParticipationApplicantResponse — 기획자가 보는 신청자 카드. */
export interface EventApplicant {
  id: number
  participantId: number
  nickname: string
  status: EventParticipationStatus
  joinedAt: string
  reviewedAt: string | null
  rejectReason: string | null
  /** APPROVED 이고 무료 티켓이 발급된 경우에만 채워진다. */
  ticketId?: number | null
  ticketStatus?: TicketStatus | null
}

/**
 * Mirrors backend MyParticipationItemResponse — MY 페이지 "내 신청/티켓" 한 행.
 * 이벤트 + 참가 상태 + (있다면) 가장 최근 티켓 + (있다면) 결제 정보가 묶여 내려온다.
 *
 * 결제 필드는 PR44 에서 추가됨 — 무료 티켓 / 결제 미연결 케이스는 모두 null.
 */
export interface MyParticipationItem {
  participationId: number
  eventId: number
  eventTitle: string
  channelId: number
  channelName: string
  mainImageUrl: string
  startAt: string
  location: string
  participationFee: number
  status: EventParticipationStatus
  requestedAt: string
  reviewedAt: string | null
  rejectReason: string | null
  ticketId: number | null
  ticketStatus: TicketStatus | null
  paymentAttemptId: number | null
  orderId: string | null
  paidAmount: number | null
  paymentProvider: 'NONE' | 'TOSS' | 'MOCK' | null
}

/** Mirrors backend CreateEventRequest. */
export interface EventPayload {
  title: string
  description: string
  location: string
  mainImageUrl: string
  startAt: string
  endAt: string
  maxParticipants: number
  participationFee: number
  refundPolicy: string
  detailContent: string
  contentType?: ContentType
}

export interface EventComment {
  id: number
  eventId: number
  authorNickname: string
  content: string
  createdAt: string
}
