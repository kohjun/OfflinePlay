import { apiClient } from './client'
import type { CreatorApplication, CreatorStudioResponse } from '../types'

export function applyForCreator(payload: { reason: string; portfolioUrl?: string }) {
  return apiClient.post<void>('/creator/apply', payload)
}

export function getMyCreatorApplication() {
  return apiClient.get<CreatorApplication | null>('/creator/my-application')
}

/**
 * GET /api/v1/creator/studio
 *
 * 기획자 운영 홈 묶음 응답. 채널이 없으면 channel === null, events === [].
 * MY → "기획자 스튜디오" 진입에서 사용한다.
 */
export function getCreatorStudio() {
  return apiClient.get<CreatorStudioResponse>('/creator/studio')
}
