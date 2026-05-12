import { apiClient } from './client'
import type {
  Channel,
  ChannelCategory,
  ChannelPayload,
  ChannelPost,
  PageResponse,
} from '../types'

export function getChannelsByCategory(
  category: ChannelCategory,
  params?: { page?: number; size?: number },
) {
  return apiClient.get<PageResponse<Channel>>(`/channels/category/${category}`, params)
}

export function getMySubscriptions(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<Channel>>('/channels/my/subscriptions', params)
}

export function getChannel(id: number) {
  return apiClient.get<Channel>(`/channels/${id}`)
}

export function createChannel(payload: ChannelPayload) {
  return apiClient.post<Channel>('/channels', payload)
}

export function subscribeChannel(id: number) {
  return apiClient.post<void>(`/channels/${id}/subscribe`)
}

export function unsubscribeChannel(id: number) {
  return apiClient.delete<void>(`/channels/${id}/subscribe`)
}

export function getChannelPosts(id: number, params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<ChannelPost>>(`/channels/${id}/posts`, params)
}

/** POST /api/v1/channels/{id}/posts — 채널 owner 만 가능. title/content 필수. */
export interface ChannelPostPayload {
  title: string
  content: string
  thumbnailUrl?: string
}

export function createChannelPost(channelId: number, payload: ChannelPostPayload) {
  return apiClient.post<ChannelPost>(`/channels/${channelId}/posts`, payload)
}

/** PATCH /api/v1/channels/{channelId}/posts/{postId} — 작성자 본인 또는 ADMIN 만. */
export interface ChannelPostUpdatePayload {
  title?: string
  content?: string
  thumbnailUrl?: string
}

export function updateChannelPost(channelId: number, postId: number, payload: ChannelPostUpdatePayload) {
  return apiClient.patch<ChannelPost>(`/channels/${channelId}/posts/${postId}`, payload)
}

/** DELETE /api/v1/channels/{channelId}/posts/{postId} — soft delete. */
export function deleteChannelPost(channelId: number, postId: number) {
  return apiClient.delete<void>(`/channels/${channelId}/posts/${postId}`)
}
