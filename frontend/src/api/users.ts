import { apiClient } from './client'
import type { User } from '../types'

/**
 * PATCH /api/v1/users/me — 닉네임/전화번호 부분 업데이트.
 * 필드는 모두 optional. 응답은 갱신된 UserProfileResponse.
 */
export interface UpdateProfilePayload {
  nickname?: string
  phoneNumber?: string
}

export function updateMyProfile(payload: UpdateProfilePayload) {
  return apiClient.patch<User>('/users/me', payload)
}

/**
 * PATCH /api/v1/users/me/password — 비밀번호 변경.
 *  - currentPassword 가 틀리면 401 (InvalidCredentialsException)
 *  - newPassword 는 8~20자 (서버 validation)
 */
export interface ChangePasswordPayload {
  currentPassword: string
  newPassword: string
}

export function changeMyPassword(payload: ChangePasswordPayload) {
  return apiClient.patch<void>('/users/me/password', payload)
}
