import { apiClient } from './client'
import type {
  Channel,
  ChannelCategory,
  ContentType,
  Event,
  PageResponse,
} from '../types'

/**
 * GET /api/v1/explore — 홈/Explore 통합 진입점.
 * 모든 필터 param 은 optional. 잘못된 enum 값은 백엔드에서 무시된다 (빈 결과 정책).
 *
 * 다중 필터 (PR45):
 *  - keyword: title/description LIKE
 *  - category / contentType: enum 매칭
 *  - location: 장소 LIKE
 *  - minFee / maxFee: 참가비 범위 (0 가능 — 무료 포함)
 *  - startFrom / startTo: 이벤트 시작 시각 범위 (ISO 8601 string)
 *  - excludeClosed: 종료된 이벤트 제외 (default true)
 *  - excludeFull: 정원 마감 이벤트 제외 (default false)
 */
export interface ExploreParams extends Record<string, string | number | boolean | undefined> {
  keyword?: string
  category?: ChannelCategory
  contentType?: ContentType
  location?: string
  minFee?: number
  maxFee?: number
  startFrom?: string
  startTo?: string
  excludeClosed?: boolean
  excludeFull?: boolean
  page?: number
  size?: number
}

export interface ExploreResponse {
  events: PageResponse<Event>
  channels: PageResponse<Channel>
}

export function explore(params: ExploreParams) {
  return apiClient.get<ExploreResponse>('/explore', params)
}

/**
 * GET /api/v1/search/popular — Redis sorted set 기반 7일 rolling 인기 검색어 top-N.
 * Explore 페이지의 추천 검색어 chip 영역에서 호출.
 */
export interface PopularKeyword {
  keyword: string
  score: number
}

export function getPopularSearches(limit = 10) {
  return apiClient.get<PopularKeyword[]>('/search/popular', { limit })
}
