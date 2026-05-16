export type UserRole = 'PARTICIPANT' | 'CREATOR' | 'ADMIN'

/** Mirrors backend UserProfileResponse. */
export interface User {
  userId: number
  email: string
  nickname: string
  phoneNumber: string
  role: UserRole
  createdAt: string
}

/**
 * CONTENIDO 콘텐츠 카테고리. 홈 화면 3x3 그리드와 일대일 매핑.
 * 백엔드 ChannelCategory enum과 동기화되어야 한다.
 */
export type ChannelCategory =
  | 'TRAVEL'
  | 'LOVE'
  | 'RACE'
  | 'PSYCHOLOGICAL'
  | 'SURVIVAL'
  | 'MUSIC'
  | 'SPORTS'
  | 'COOKING'
  | 'PARTY'

/** 이벤트(콘텐츠) 유형. 홈 화면 콘텐츠 유형 섹션과 매핑. */
export type ContentType = 'ORIGINAL' | 'CLASSIC' | 'SPECIAL'

export interface Channel {
  id: number
  name: string
  description: string
  category: ChannelCategory
  categoryDisplayName: string
  thumbnailUrl?: string
  ownerId: number
  ownerNickname: string
  subscriberCount: number
  createdAt: string
  /** present only on detail responses */
  isSubscribed?: boolean
  /** PR47 — 채널 내 모든 이벤트 후기 평균. 후기 0건이면 null. */
  averageRating?: number | null
  reviewCount?: number
}

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

/** Mirrors backend TicketStatus. PG 미연동이라 PAID 만 발급되며 USED/REFUNDED/CANCELED 는 향후 상태. */
export type TicketStatus = 'PAID' | 'USED' | 'REFUNDED' | 'CANCELED'

/**
 * Mirrors backend TicketDetailResponse — 참가자 티켓 패스/QR 화면.
 *
 *  - checkInCode : MVP 단계 결정형 문자열. 실제 보안 QR (서명/만료) 은 후속 과제.
 */
export interface TicketDetail {
  ticketId: number
  ticketStatus: TicketStatus
  eventId: number
  eventTitle: string
  channelId: number
  channelName: string
  mainImageUrl: string
  startAt: string
  endAt: string
  location: string
  participationFee: number
  buyerId: number
  buyerNickname: string
  purchasedAt: string
  checkInCode: string
  /** PAID → USED 로 전환된 시각. USED 가 아니면 null. */
  usedAt?: string | null
}

/**
 * Creator Studio 운영 홈 응답.
 *  - channel : 기획자의 채널. 아직 만들지 않았다면 null.
 *  - events  : 채널 산하 이벤트 (startAt 내림차순) + 신청 상태별 카운트.
 *  - summary : hero 아래 4-tile 요약.
 */
export interface CreatorStudioChannel {
  id: number
  name: string
  description: string
  category: ChannelCategory
  categoryDisplayName: string
  thumbnailUrl: string | null
  subscriberCount: number
  ownerNickname: string
}

export interface CreatorStudioEvent {
  id: number
  title: string
  status: EventStatus
  startAt: string
  location: string
  mainImageUrl: string
  currentParticipants: number
  maxParticipants: number
  pendingCount: number
  approvedCount: number
  rejectedCount: number
  canceledCount: number
}

export interface CreatorStudioSummary {
  totalEvents: number
  pendingApplicants: number
  approvedParticipants: number
  subscriberCount: number
}

export interface CreatorStudioResponse {
  channel: CreatorStudioChannel | null
  events: CreatorStudioEvent[]
  summary: CreatorStudioSummary
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

export interface Content {
  id: number
  title: string
  description: string
  thumbnailUrl?: string
  creatorNickname: string
  viewCount: number
  createdAt: string
}

export interface Notification {
  id: number
  type: string
  title: string
  message: string
  targetType: string
  targetId: number
  isRead: boolean
  createdAt: string
}

export interface CreatorApplication {
  id: number
  reason: string
  portfolioUrl?: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  createdAt: string
  reviewedAt?: string
}

export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

/** Mirrors backend PageResponse. */
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  currentPage: number
  size: number
  isFirst: boolean
  isLast: boolean
}

export interface LoginPayload {
  email: string
  password: string
}

/**
 * 가입 시 사용자가 선택할 수 있는 역할.
 *  - PARTICIPANT : 참가자(기본값)
 *  - CREATOR     : 기획자 — 가입 직후 채널 생성 가능
 *
 * ADMIN 은 자가 발급할 수 없으므로 이 타입에 포함하지 않는다.
 */
export type SignupRole = 'PARTICIPANT' | 'CREATOR'

export interface SignupPayload extends LoginPayload {
  nickname: string
  phoneNumber: string
  role?: SignupRole
}

/** Mirrors backend TokenResponse. */
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

/** Mirrors backend SignupResponse. */
export interface SignupResponse {
  userId: number
  email: string
  nickname: string
}

export interface ChannelPayload {
  name: string
  description: string
  category: ChannelCategory
  thumbnailUrl?: string
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

export interface ContentPayload {
  title: string
  description: string
  thumbnailUrl?: string
}

export interface ToastMessage {
  id: number
  title: string
  message?: string
  tone?: 'success' | 'warning' | 'danger' | 'info'
}

export interface EventComment {
  id: number
  eventId: number
  authorNickname: string
  content: string
  createdAt: string
}

export interface ChannelPost {
  id: number
  channelId: number
  title: string
  content: string
  authorNickname: string
  createdAt: string
}

/** Backend ReportTargetType enum 과 1:1. CONTENT/USER 는 더 이상 사용하지 않음 (PR48). */
export type ReportTargetType = 'CHANNEL' | 'POST' | 'EVENT' | 'COMMENT' | 'REVIEW'

export interface Report {
  id: number
  reporterNickname: string
  targetType: ReportTargetType
  targetId: number
  reason: string
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED'
  createdAt: string
  /** PR48 — Admin 응답에서만 채워짐. 대상이 삭제됐거나 createReport 응답이면 null. */
  targetPreview?: string | null
  /** PR48 — REVIEW 일 때만 채워짐. 빠른 별점 확인용. */
  targetRating?: number | null
  /** PR51 — 대상이 현재 자동 숨김(hide)된 상태인지. Admin 응답에서만 의미 있음. */
  targetHidden?: boolean
  /**
   * PR51 — 위 targetHidden 이 true 인 경우, 그 hide 가 신고 누적 자동 처리인지.
   * 현 PR 에선 자동 hide 만 존재하므로 targetHidden 과 사실상 동치이지만, 후속 PR(수동 hide/appeal
   * 복구) 에서 의미가 갈라진다.
   */
  autoModerated?: boolean
}

/** PR52 — 자동 숨김 대상에 대한 이의 제기 상태. */
export type ReportAppealStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface ReportAppeal {
  id: number
  targetType: ReportTargetType
  targetId: number
  requesterId: number
  requesterNickname: string
  reason: string
  status: ReportAppealStatus
  rejectReason?: string | null
  createdAt: string
  reviewedAt?: string | null
  /** Admin 큐 응답에서만 채워짐. 본인 응답은 null. */
  targetPreview?: string | null
  /** 응답 생성 시점의 대상 hidden 여부. ADMIN 이 승인하면 false 로 바뀐다. */
  targetHidden?: boolean
}

/** Mirrors backend PaymentStatus. */
export type PaymentStatus = 'READY' | 'PAID' | 'FAILED' | 'CANCELED'

/** Mirrors backend PaymentProvider. NONE = PR39 단계 (실제 PG 미연동). */
export type PaymentProvider = 'NONE' | 'TOSS' | 'PORTONE'

/**
 * Mirrors backend PaymentPrepareResponse.
 * idempotencyKey 를 PG SDK 호출 시 orderId 로 그대로 전달한다.
 */
export interface PaymentPrepareResponse {
  paymentAttemptId: number
  eventId: number
  amount: number
  orderName: string
  idempotencyKey: string
  status: PaymentStatus
}

/**
 * Mirrors backend PaymentConfirmRequest. PG SDK 콜백으로 받은 paymentKey 와
 * orderId/amount 를 그대로 전달해 백엔드가 PG 에 confirm 호출하게 한다.
 */
export interface PaymentConfirmRequest {
  paymentKey: string
  orderId: string
  amount: number
}

/**
 * Mirrors backend PaymentConfirmResponse.
 *  - ticketId 는 PAID 가 아니면 null.
 *  - approvedAt 은 PG 가 알려준 승인 시각(ISO-8601), MockPaymentGateway 환경에선 null.
 */
export interface PaymentConfirmResponse {
  paymentAttemptId: number
  status: PaymentStatus
  provider: PaymentProvider
  amount: number
  ticketId: number | null
  providerPaymentKey: string | null
  approvedAt: string | null
}

/** Mirrors backend RefundTicketRequest. reason 은 빈 값일 수 있으며 서버가 USER_REQUEST 로 대체. */
export interface RefundTicketRequest {
  reason?: string | null
}

/** Mirrors backend RefundTicketResponse. */
export interface RefundTicketResponse {
  ticketId: number
  ticketStatus: TicketStatus
  paymentAttemptId: number
  provider: PaymentProvider
  amount: number
  refundedAt: string
  providerPaymentKey: string | null
}
