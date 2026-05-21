import type { NotificationType, UserRole } from '../types'

export type NotificationTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

/**
 * PR97 — NotificationType 별 메타데이터 단일 정의.
 *
 *  - `label`       : 알림 카드와 설정 패널이 함께 쓰는 사용자-visible 라벨.
 *  - `tone`        : 뱃지 색.
 *  - `description` : 알림 설정 패널의 보조 설명 (옵션).
 *
 * 라우팅은 (type, targetType, targetId, viewerRole) 의 함수라 별도 helper [pathForNotification]
 * 가 담당. 본 객체에 path 함수를 직접 넣으면 모든 알림이 같은 인자를 받는 fan-out 이 되지 않고,
 * 호출처별 분기가 다시 흩어진다.
 */
interface NotificationMetaEntry {
  label: string
  tone: NotificationTone
  description?: string
}

const META: Record<NotificationType, NotificationMetaEntry> = {
  NEW_EVENT: {
    label: '새 이벤트',
    tone: 'primary',
    description: '구독한 채널이 새 이벤트를 열었을 때 알려드려요.',
  },
  NEW_POST: {
    label: '새 글',
    tone: 'neutral',
    description: '구독한 채널의 새 공지/소식을 알려드려요.',
  },
  NEW_COMMENT: {
    label: '새 댓글',
    tone: 'neutral',
    description: '내가 쓴 글에 댓글이 달리면 알려드려요.',
  },
  NEW_LIKE: {
    label: '좋아요',
    tone: 'neutral',
    description: '내가 쓴 글이 좋아요를 받으면 알려드려요.',
  },
  APPLICATION_APPROVED: {
    label: '기획자 승인',
    tone: 'success',
    description: '내가 낸 크리에이터 신청이 승인되면 알려드려요.',
  },
  APPLICATION_REJECTED: {
    label: '기획자 거절',
    tone: 'danger',
    description: '내가 낸 크리에이터 신청이 거절되면 알려드려요.',
  },
  PARTICIPATION_REQUESTED: {
    label: '참가 신청',
    tone: 'primary',
    description: '운영 중인 이벤트에 누군가 참가 신청을 하면 알려드려요.',
  },
  PARTICIPATION_APPROVED: {
    label: '신청 승인',
    tone: 'success',
    description: '내 참가 신청이 승인되면 알려드려요.',
  },
  PARTICIPATION_REJECTED: {
    label: '신청 거절',
    tone: 'danger',
    description: '내 참가 신청이 거절되면 알려드려요.',
  },
  PARTICIPATION_CANCELED: {
    label: '참가 취소',
    tone: 'neutral',
    description: '내가 운영하는 이벤트의 참가자가 본인 신청을 취소하면 알려드려요.',
  },
  TICKET_ISSUED: {
    label: '티켓 발급',
    tone: 'success',
    description: '내 티켓이 발급되면 알려드려요.',
  },
  TICKET_CHECKED_IN: {
    label: '체크인 완료',
    tone: 'neutral',
    description: '현장에서 내 티켓 체크인이 완료되면 알려드려요.',
  },
  CHANNEL_BANNED: {
    label: '채널 제재',
    tone: 'danger',
    description: '내가 운영하는 채널이 운영자에 의해 제재되면 알려드려요.',
  },
  CHANNEL_UNBANNED: {
    label: '채널 제재 해제',
    tone: 'success',
    description: '제재됐던 내 채널이 다시 활성화되면 알려드려요.',
  },
  REFUND_COMPLETED: {
    label: '환불 완료',
    tone: 'warning',
    description: '결제한 티켓의 환불이 완료되면 알려드려요.',
  },
  EVENT_ANNOUNCEMENT: {
    label: '이벤트 공지',
    tone: 'primary',
    description: '참가 확정된 이벤트의 운영 공지를 알려드려요.',
  },
}

/** 알 수 없는 type 에 사용할 안전한 fallback (서버에 새 enum 이 추가됐는데 frontend 가 따라가지 못한 경우). */
const UNKNOWN_META: NotificationMetaEntry = {
  label: '알림',
  tone: 'neutral',
}

export function getNotificationMeta(type: string): NotificationMetaEntry {
  return META[type as NotificationType] ?? UNKNOWN_META
}

export function getNotificationLabel(type: string): string {
  return getNotificationMeta(type).label
}

export function getNotificationTone(type: string): NotificationTone {
  return getNotificationMeta(type).tone
}

/**
 * PR99 — 알림 설정 패널의 카테고리 묶음 토글 정의.
 *
 *  - `all` 은 별도 bundle 로 보지 않고 호출처가 모든 NotificationType 을 직접 사용 (모든 type 의
 *    합집합이라 한 곳에서 계산하기 쉽다 — UI 의 "전체 켜기/끄기" 가 이를 활용).
 *  - 나머지 5 bundle 은 NotificationType 을 빠짐없이 분할(partition) — 한 type 이 두 bundle 에
 *    동시에 속하지 않는다. 새 enum 이 추가되면 이 정의도 함께 갱신해 모든 type 이 최소 하나의
 *    bundle 에 속하도록 유지한다.
 *  - 컨벤션: bundle 이 "끄기" 면 해당 type 들을 enabled=false 로 PATCH, "켜기" 면 enabled=true.
 */
export type NotificationPreferenceBundleId = 'participation' | 'payment' | 'content' | 'moderation' | 'system'

interface NotificationPreferenceBundle {
  id: NotificationPreferenceBundleId
  label: string
  /** 토글 버튼에 노출할 짧은 설명 (선택). UI 가 사용. */
  description?: string
  types: readonly NotificationType[]
}

export const NOTIFICATION_PREFERENCE_BUNDLES: readonly NotificationPreferenceBundle[] = [
  {
    id: 'participation',
    label: '참가 관련',
    description: '신청 / 승인 / 거절 / 취소 / 티켓 발급·체크인',
    types: [
      'PARTICIPATION_REQUESTED',
      'PARTICIPATION_APPROVED',
      'PARTICIPATION_REJECTED',
      'PARTICIPATION_CANCELED',
      'TICKET_ISSUED',
      'TICKET_CHECKED_IN',
    ],
  },
  {
    id: 'payment',
    label: '결제 관련',
    description: '환불 완료 같은 결제 흐름 알림',
    types: ['REFUND_COMPLETED'],
  },
  {
    id: 'content',
    label: '콘텐츠 관련',
    description: '구독 채널의 새 이벤트 / 새 글 / 댓글 / 좋아요 / 이벤트 공지',
    types: ['NEW_EVENT', 'NEW_POST', 'NEW_COMMENT', 'NEW_LIKE', 'EVENT_ANNOUNCEMENT'],
  },
  {
    id: 'moderation',
    label: '운영 알림',
    description: '운영자가 내 채널을 제재했을 때',
    types: ['CHANNEL_BANNED'],
  },
  {
    id: 'system',
    label: '시스템 알림',
    description: '크리에이터 신청 결과 / 채널 제재 해제',
    types: ['APPLICATION_APPROVED', 'APPLICATION_REJECTED', 'CHANNEL_UNBANNED'],
  },
] as const

export function getNotificationPreferenceBundleTypes(
  bundleId: NotificationPreferenceBundleId,
): readonly NotificationType[] {
  return NOTIFICATION_PREFERENCE_BUNDLES.find((b) => b.id === bundleId)?.types ?? []
}

export function getNotificationPreferenceBundleLabel(
  bundleId: NotificationPreferenceBundleId,
): string {
  return NOTIFICATION_PREFERENCE_BUNDLES.find((b) => b.id === bundleId)?.label ?? bundleId
}

/**
 * PR97 — 알림 → 라우팅 규칙 단일 정의. 모르는 (type, targetType) 조합은 null 반환 → 호출처가
 * "읽음 처리만" 으로 폴백.
 *
 * 정책 (기존 NotificationsPage.pathForTarget 동작 유지):
 *  - targetType="events"             → /events/{id}
 *  - targetType="channels" + NEW_POST → /channels/{id}?tab=posts
 *  - targetType="channels" + CHANNEL_BANNED → role-aware (/creator 또는 /my)
 *  - targetType="channels" 기타       → /channels/{id}
 *  - targetType="tickets"            → /tickets/{id}
 *  - targetType="creator-applications" → role-aware (/admin 또는 /my)
 */
export function pathForNotification(
  targetType: string,
  targetId: number,
  type: string,
  viewerRole?: UserRole,
): string | null {
  switch (targetType) {
    case 'events':
      return `/events/${targetId}`
    case 'channels':
      if (type === 'NEW_POST') return `/channels/${targetId}?tab=posts`
      if (type === 'CHANNEL_BANNED') {
        return viewerRole === 'CREATOR' || viewerRole === 'ADMIN' ? '/creator' : '/my'
      }
      return `/channels/${targetId}`
    case 'tickets':
      return `/tickets/${targetId}`
    case 'creator-applications':
      return viewerRole === 'ADMIN' ? '/admin' : '/my'
    default:
      return null
  }
}
