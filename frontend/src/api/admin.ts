import { apiClient } from './client'
import type {
  AdminChannelBan,
  AdminModerationGranularity,
  AdminModerationPriority,
  AdminModerationQueueItem,
  AdminModerationStats,
  AdminModerationTarget,
  Channel,
  CreatorApplication,
  ModerationAuditAction,
  ModerationAuditLog,
  ModerationThreshold,
  PageResponse,
  Report,
  ReportTargetType,
  UpdateModerationThresholdsRequest,
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

/**
 * GET /api/v1/admin/moderation/stats
 *
 * PR57 — 운영 지표. default 기간 = 최근 30일 (backend 측 기본값).
 * granularity 는 day 만 지원. 위험 채널 Top 5 동봉.
 */
export function getModerationStats(params?: {
  from?: string
  to?: string
  granularity?: AdminModerationGranularity
}) {
  return apiClient.get<AdminModerationStats>('/admin/moderation/stats', params)
}

/**
 * PATCH /api/v1/admin/moderation/channels/{channelId}/ban
 *
 * PR58 — 채널 제재. reason 필수, 255자. 채널 + 소속 events/posts/reviews cascade hide.
 * 이미 hidden 인 채널이면 409. 응답의 cascade*Count 는 "본 호출에서 새로 숨긴" row 수.
 * 본 호출은 관련 PENDING appeal 을 자동 reject 하지 않는다 (운영자가 appeal 큐에서 별도 처리).
 */
export function banChannelForModeration(channelId: number, reason: string) {
  return apiClient.patch<AdminChannelBan>(
    `/admin/moderation/channels/${channelId}/ban`,
    { reason },
  )
}

/**
 * PATCH /api/v1/admin/moderation/channels/{channelId}/unban
 *
 * PR58 — 채널 제재 해제. 소속 콘텐츠는 자동 unhide 되지 않는다 — 개별 콘텐츠는
 * unhideModerationTarget 으로 따로 처리.
 */
export function unbanChannelForModeration(channelId: number) {
  return apiClient.patch<AdminChannelBan>(`/admin/moderation/channels/${channelId}/unban`)
}

/**
 * GET /api/v1/admin/moderation/thresholds
 *
 * PR60 — 자동 hide 임계치 5종 조회. DB row 없으면 backend 가 default 로 채워 반환.
 */
export function getModerationThresholds() {
  return apiClient.get<ModerationThreshold[]>('/admin/moderation/thresholds')
}

/**
 * PATCH /api/v1/admin/moderation/thresholds
 *
 * PR60 — partial update. 변경하지 않을 필드는 보내지 않으면 됨. 1..100 범위. 변경 즉시 다음
 * 신고부터 적용되며, 기존 hidden 상태는 retroactive 재계산되지 않는다.
 */
export function updateModerationThresholds(request: UpdateModerationThresholdsRequest) {
  return apiClient.patch<ModerationThreshold[]>('/admin/moderation/thresholds', request)
}

/**
 * GET /api/v1/admin/moderation/audit-logs
 *
 * PR61 신설, PR62 에서 actorId / from / to 필터 추가.
 * - action / targetType / targetId / actorId: 정확 일치 (모두 optional, AND).
 * - from / to: ISO datetime (`2026-05-17T08:30:00`) 또는 date-only (`2026-05-17`).
 *   date-only 는 backend 가 from=00:00, to=23:59:59.999999999 로 확장.
 * - 정렬은 createdAt DESC 고정.
 */
export function getModerationAuditLogs(params?: {
  page?: number
  size?: number
  action?: ModerationAuditAction
  targetType?: ReportTargetType
  targetId?: number
  actorId?: number
  from?: string
  to?: string
}) {
  return apiClient.get<PageResponse<ModerationAuditLog>>('/admin/moderation/audit-logs', params)
}
