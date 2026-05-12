import { apiClient } from './client'

export type ChannelMemberRole = 'OWNER' | 'STAFF'

export interface ChannelMember {
  id: number
  userId: number
  nickname: string
  email: string
  role: ChannelMemberRole
  joinedAt: string
}

export function listChannelMembers(channelId: number) {
  return apiClient.get<ChannelMember[]>(`/channels/${channelId}/members`)
}

export function addChannelStaff(channelId: number, email: string) {
  return apiClient.post<ChannelMember>(`/channels/${channelId}/members/staff`, { email })
}

export function removeChannelMember(channelId: number, memberId: number) {
  return apiClient.delete<void>(`/channels/${channelId}/members/${memberId}`)
}
