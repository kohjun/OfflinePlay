import { apiClient } from './client'
import type { Report, ReportTargetType } from '../types'

export interface CreateReportPayload {
  targetType: ReportTargetType
  targetId: number
  reason: string
}

/**
 * POST /api/v1/reports — 사용자 신고.
 *
 * 백엔드 가드 (PR48):
 *  - 대상 미존재 → 404 (ReportTargetNotFoundException)
 *  - 본인 글 신고 → 400 (SelfReportNotAllowedException)
 *  - 같은 reporter 의 같은 (targetType, targetId) 중복 → 409 (ReportAlreadyExistsException)
 *
 * 호출처는 ApiError.status 를 보고 친화 카피로 분기 (예: 409 = "이미 신고한 후기입니다").
 */
export function createReport(payload: CreateReportPayload) {
  return apiClient.post<Report>('/reports', payload)
}
