import { apiClient } from './client'
import type {
  AdminModerationPriority,
  AdminModerationQueueItem,
  AdminModerationTarget,
  Channel,
  CreatorApplication,
  PageResponse,
  Report,
  ReportTargetType,
  User,
} from '../types'

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

/**
 * PATCH /api/v1/admin/moderation/{targetType}/{targetId}/hide
 *
 * PR54 — ADMIN 수동 hide. reason 필수, 최대 255자. 이미 hidden 이면 409
 * (TargetAlreadyHiddenException). 본 호출은 관련 PENDING appeal 을 자동 reject 하지 않는다.
 */
export function hideModerationTarget(
  targetType: ReportTargetType,
  targetId: number,
  reason: string,
) {
  return apiClient.patch<AdminModerationTarget>(
    `/admin/moderation/${targetType}/${targetId}/hide`,
    { reason },
  )
}

/**
 * PATCH /api/v1/admin/moderation/{targetType}/{targetId}/unhide
 *
 * PR54 — ADMIN 수동 unhide. hidden 이 아니면 409 (TargetNotHiddenException).
 * 본 호출은 관련 PENDING appeal 을 자동 approve 하지 않는다.
 */
export function unhideModerationTarget(targetType: ReportTargetType, targetId: number) {
  return apiClient.patch<AdminModerationTarget>(
    `/admin/moderation/${targetType}/${targetId}/unhide`,
  )
}

/**
 * GET /api/v1/admin/moderation/queue
 *
 * PR55 — 통합 moderation queue. PENDING report / hidden 콘텐츠 / PENDING appeal 3개 source 를
 * (targetType, targetId) 키로 merge 한 페이지. priority desc + 최근 activity desc 정렬.
 */
export function getModerationQueue(params?: {
  page?: number
  size?: number
  targetType?: ReportTargetType
  hidden?: boolean
  priority?: AdminModerationPriority
}) {
  return apiClient.get<PageResponse<AdminModerationQueueItem>>('/admin/moderation/queue', params)
}
