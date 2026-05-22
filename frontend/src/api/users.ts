import { apiClient } from './client'
import type { User, UserRole } from '../types'

/**
 * PR144 — 공개 프로필 가시성. PRIVATE 면 공개 응답에서 bio/avatar/region 모두 숨김.
 */
export type ProfileVisibility = 'PUBLIC' | 'MEMBERS' | 'PRIVATE'

export interface PublicProfileResponse {
  userId: number
  nickname: string
  role: UserRole
  avatarUrl: string | null
  bio: string | null
  regionSido: string | null
  regionSigungu: string | null
  visibility: ProfileVisibility
  joinedAt: string
}

export interface MyProfileResponse extends Omit<PublicProfileResponse, 'visibility'> {
  visibility: ProfileVisibility
  updatedAt: string | null
}

export interface UpdateMyProfileRequest {
  bio?: string
  avatarUrl?: string
  regionSido?: string
  regionSigungu?: string
  visibility?: ProfileVisibility
}

export function getPublicProfile(userId: number) {
  return apiClient.get<PublicProfileResponse>(`/users/${userId}/profile`)
}

export function getMyExtendedProfile() {
  return apiClient.get<MyProfileResponse>('/users/me/profile')
}

export function updateMyExtendedProfile(request: UpdateMyProfileRequest) {
  return apiClient.patch<MyProfileResponse>('/users/me/profile', request)
}

/**
 * PR145 — 사용자 신뢰 요약. 기존 이벤트/참가/티켓/후기 데이터 즉시 집계.
 */
export interface TrustSummaryResponse {
  userId: number
  hostedEventCount: number
  participatedEventCount: number
  checkedInCount: number
  reviewCount: number
  averageEventRatingAsHost: number | null
}

export function getTrustSummary(userId: number) {
  return apiClient.get<TrustSummaryResponse>(`/users/${userId}/trust-summary`)
}

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
