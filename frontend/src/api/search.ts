import { apiClient } from './client'
import type { PageResponse } from '../types'

type SearchQueryValue = string | number | undefined

// Search responses use string IDs and a different shape from Channel/Content.
// These types match SearchChannelResponse/SearchContentResponse on the backend.
export interface SearchChannelHit {
  id: string
  name: string
  category: string
  categoryDisplayName: string
  ownerNickname: string
  subscriberCount: number
  thumbnailUrl?: string
}

export interface SearchContentHit {
  id: string
  sourceType: 'EVENT' | 'POST'
  channelId: number
  channelName: string
  category: string
  title: string
  description: string
  authorNickname: string
  viewCount: number
  likeCount: number
  createdAt: string
}

export interface SearchChannelParams extends Record<string, SearchQueryValue> {
  keyword: string
  category?: string
  page?: number
  size?: number
  sortBy?: 'relevance' | 'subscriberCount'
}

export interface SearchContentParams extends Record<string, SearchQueryValue> {
  keyword: string
  category?: string
  sourceType?: 'EVENT' | 'POST'
  page?: number
  size?: number
  sortBy?: 'relevance' | 'viewCount' | 'likeCount'
}

export function searchChannels(params: SearchChannelParams) {
  return apiClient.get<PageResponse<SearchChannelHit>>('/search/channels', params)
}

export function searchContents(params: SearchContentParams) {
  return apiClient.get<PageResponse<SearchContentHit>>('/search/contents', params)
}
