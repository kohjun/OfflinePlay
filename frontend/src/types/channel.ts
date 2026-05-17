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

export interface ChannelPayload {
  name: string
  description: string
  category: ChannelCategory
  thumbnailUrl?: string
}

export interface ChannelPost {
  id: number
  channelId: number
  title: string
  content: string
  authorNickname: string
  createdAt: string
}
