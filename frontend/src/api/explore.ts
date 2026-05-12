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
 * keyword/category/contentType 은 모두 optional. 잘못된 enum 값은 백엔드에서 무시된다 (빈 결과 정책).
 */
export interface ExploreParams extends Record<string, string | number | undefined> {
  keyword?: string
  category?: ChannelCategory
  contentType?: ContentType
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
