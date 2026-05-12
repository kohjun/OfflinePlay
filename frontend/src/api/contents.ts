import { apiClient } from './client'
import type { Content, ContentPayload, PageResponse } from '../types'

// TODO(PR-spec-alignment): backend /contents has no channelId filter today.
// If channel-scoped content listing is needed, add a backend endpoint and a separate helper.
export function getContents(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<Content>>('/contents', params)
}

export function getContent(id: number) {
  return apiClient.get<Content>(`/contents/${id}`)
}

export function createContent(payload: ContentPayload) {
  return apiClient.post<Content>('/contents', payload)
}
