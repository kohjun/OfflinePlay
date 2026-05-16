import { apiClient } from './client'
import type {
  CreatorApplication,
  CreatorModerationHiddenItem,
  CreatorStudioResponse,
} from '../types'

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

/**
 * GET /api/v1/creator/moderation/hidden
 *
 * 작성자/소유자 본인이 권한을 가진 자동 숨김 콘텐츠 목록 (PR53).
 *  - REVIEW/COMMENT/POST: author 본인 콘텐츠
 *  - EVENT/CHANNEL: 본인이 channel.owner 인 콘텐츠
 * 다른 사용자 콘텐츠는 backend 가 author/owner 필터로 차단하므로 응답에 절대 섞이지 않는다.
 * 각 row 의 appealStatus 가 NONE/REJECTED 면 이의 제기 CTA 노출 가능.
 */
export function getCreatorHiddenContent() {
  return apiClient.get<CreatorModerationHiddenItem[]>('/creator/moderation/hidden')
}
