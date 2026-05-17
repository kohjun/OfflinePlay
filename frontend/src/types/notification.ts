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

/**
 * PR95 — backend `NotificationType` enum 과 1:1. 추후 enum 이 추가되면 여기도 함께 갱신해
 * preferences UI 가 누락 없이 노출하도록 한다.
 */
export type NotificationType =
  | 'NEW_EVENT'
  | 'NEW_POST'
  | 'NEW_COMMENT'
  | 'NEW_LIKE'
  | 'APPLICATION_APPROVED'
  | 'APPLICATION_REJECTED'
  | 'PARTICIPATION_REQUESTED'
  | 'PARTICIPATION_APPROVED'
  | 'PARTICIPATION_REJECTED'
  | 'PARTICIPATION_CANCELED'
  | 'TICKET_ISSUED'
  | 'TICKET_CHECKED_IN'
  | 'CHANNEL_BANNED'
  | 'CHANNEL_UNBANNED'
  | 'REFUND_COMPLETED'

/**
 * PR95 — 사용자별 NotificationType 수신 선호. backend 응답은 모든 type 을 반환하며 row 가 없는
 * type 은 enabled=true 로 채워진 상태.
 */
export interface NotificationPreference {
  type: NotificationType
  enabled: boolean
}

/** PR95 — 부분 갱신 요청. request 에 없는 type 은 backend 가 기존 값을 유지한다. */
export interface UpdateNotificationPreferencesRequest {
  preferences: Array<{ type: NotificationType; enabled: boolean }>
}
