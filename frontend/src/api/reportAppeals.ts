import { apiClient } from './client'
import type { PageResponse, ReportAppeal, ReportTargetType } from '../types'

export interface CreateReportAppealPayload {
  targetType: ReportTargetType
  targetId: number
  reason: string
}

export interface ReviewReportAppealPayload {
  rejectReason?: string | null
}

/**
 * POST /api/v1/report-appeals — 자동 숨김 대상에 대한 이의 제기 (PR52).
 *
 * 백엔드 가드:
 *  - 대상 미존재 → 404 (ReportTargetNotFoundException)
 *  - 대상이 hidden 이 아님 → 400 (TargetNotHiddenException)
 *  - 본인이 작성/소유 아님 → 403 (AppealNotAllowedException)
 *  - 같은 (requester, target) PENDING 중복 → 409 (AppealAlreadyExistsException)
 */
export function createReportAppeal(payload: CreateReportAppealPayload) {
  return apiClient.post<ReportAppeal>('/report-appeals', payload)
}

/** GET /api/v1/report-appeals/my — 본인 appeal 목록, 최신순. */
export function getMyReportAppeals(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<ReportAppeal>>('/report-appeals/my', params)
}

/** GET /api/v1/admin/report-appeals — ADMIN appeal 큐. status 필터 옵션. */
export function getAdminReportAppeals(params?: { page?: number; size?: number; status?: string }) {
  return apiClient.get<PageResponse<ReportAppeal>>('/admin/report-appeals', params)
}

/** PATCH /api/v1/admin/report-appeals/{id}/approve — 대상 unhide + APPROVED. */
export function approveReportAppeal(id: number) {
  return apiClient.patch<ReportAppeal>(`/admin/report-appeals/${id}/approve`)
}

/** PATCH /api/v1/admin/report-appeals/{id}/reject — hidden 유지 + REJECTED + rejectReason. */
export function rejectReportAppeal(id: number, payload?: ReviewReportAppealPayload) {
  return apiClient.patch<ReportAppeal>(`/admin/report-appeals/${id}/reject`, payload)
}
