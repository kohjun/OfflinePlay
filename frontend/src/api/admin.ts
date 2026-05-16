import { apiClient } from './client'
import type { Channel, CreatorApplication, PageResponse, Report, User } from '../types'

// TODO(PR-spec-alignment): backend does not expose /admin/stats; derive stats client-side
// or add a backend endpoint in a follow-up PR.
export interface AdminStats {
  userCount: number
  creatorCount: number
  channelCount: number
  pendingApplications: number
}

export function getUsers(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<User>>('/admin/users', params)
}

export function getCreatorApplications(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<CreatorApplication>>('/admin/creator/applications', params)
}

export function approveCreatorApplication(id: number) {
  return apiClient.patch<void>(`/admin/creator/applications/${id}/approve`)
}

export function rejectCreatorApplication(id: number, rejectReason?: string) {
  return apiClient.patch<void>(`/admin/creator/applications/${id}/reject`, { rejectReason })
}

export function getAdminChannels(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<Channel>>('/admin/channels', params)
}

export function banChannel(id: number) {
  return apiClient.patch<Channel>(`/admin/channels/${id}/ban`)
}

export function banUser(id: number) {
  return apiClient.patch<User>(`/admin/users/${id}/ban`)
}

export function getReports(params?: { page?: number; size?: number; targetType?: string }) {
  return apiClient.get<PageResponse<Report>>('/admin/reports', params)
}

export function resolveReport(id: number) {
  return apiClient.patch<Report>(`/admin/reports/${id}/resolve`)
}

export function dismissReport(id: number) {
  return apiClient.patch<Report>(`/admin/reports/${id}/dismiss`)
}
