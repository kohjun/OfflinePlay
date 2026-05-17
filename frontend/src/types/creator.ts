import type { ChannelCategory } from './channel'
import type { EventStatus } from './event'

export interface CreatorApplication {
  id: number
  reason: string
  portfolioUrl?: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  createdAt: string
  reviewedAt?: string
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
