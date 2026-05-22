import { apiClient } from './client'

/**
 * PR153 — Creator Studio 매출/환불 분석 API.
 *  - from / to 는 ISO LocalDateTime ("2026-05-01T00:00:00"). 둘 다 비우면 전체 기간.
 */

export interface CreatorEventAnalytics {
  eventId: number
  eventTitle: string
  grossRevenue: number
  refundedAmount: number
  netRevenue: number
  partialRefundAmount: number
  fullRefundCount: number
  paidAttemptCount: number
}

export interface CreatorChannelAnalytics {
  channelId: number
  from: string | null
  to: string | null
  grossRevenue: number
  refundedAmount: number
  netRevenue: number
  partialRefundAmount: number
  fullRefundCount: number
  paidAttemptCount: number
  events: CreatorEventAnalytics[]
}

export function getChannelAnalytics(
  channelId: number,
  params?: { from?: string; to?: string },
) {
  return apiClient.get<CreatorChannelAnalytics>(
    `/creator/channels/${channelId}/analytics`,
    params,
  )
}
