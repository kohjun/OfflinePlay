export type UserRole = 'PARTICIPANT' | 'CREATOR' | 'ADMIN'

/** Mirrors backend UserProfileResponse. */
export interface User {
  userId: number
  email: string
  nickname: string
  phoneNumber: string
  role: UserRole
  createdAt: string
}

export interface LoginPayload {
  email: string
  password: string
}

/**
 * 가입 시 사용자가 선택할 수 있는 역할.
 *  - PARTICIPANT : 참가자(기본값)
 *  - CREATOR     : 기획자 — 가입 직후 채널 생성 가능
 *
 * ADMIN 은 자가 발급할 수 없으므로 이 타입에 포함하지 않는다.
 */
export type SignupRole = 'PARTICIPANT' | 'CREATOR'

export interface SignupPayload extends LoginPayload {
  nickname: string
  phoneNumber: string
  role?: SignupRole
}

/** Mirrors backend TokenResponse. */
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

/** Mirrors backend SignupResponse. */
export interface SignupResponse {
  userId: number
  email: string
  nickname: string
}
