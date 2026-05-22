import { apiClient } from './client'
import type { Event } from '../types'

/**
 * PR148 — 추천 응답 segment + reason chip 묶음.
 *  - segment 는 backend 의 RecommendationSegment enum 값 그대로.
 *  - reasonCodes 는 우선순위 desc 정렬 — PR149 가 상위 1-2개만 chip 노출.
 */
export type RecommendationSegment = 'RECOMMENDED' | 'POPULAR' | 'CLOSING_SOON' | 'LATEST'

export interface RecommendedEvent {
  event: Event
  score: number
  reasonCodes: string[]
}

export interface RecommendationsResponse {
  segment: string
  items: RecommendedEvent[]
}

export function getRecommendations(params?: {
  segment?: RecommendationSegment
  size?: number
}) {
  return apiClient.get<RecommendationsResponse>('/recommendations/events', params)
}
