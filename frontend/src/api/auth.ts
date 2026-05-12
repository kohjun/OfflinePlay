import { apiClient, tokenStorage } from './client'
import type {
  LoginPayload,
  SignupPayload,
  SignupResponse,
  TokenResponse,
  User,
} from '../types'

async function persistTokens(tokens: TokenResponse) {
  tokenStorage.setPair(tokens.accessToken, tokens.refreshToken)
}

/**
 * Login flow: exchange credentials for tokens, store them, then fetch the profile.
 * Returns the authenticated user.
 */
export async function login(payload: LoginPayload): Promise<User> {
  const tokens = await apiClient.post<TokenResponse>('/auth/login', payload)
  await persistTokens(tokens)
  return getMe()
}

/**
 * Signup flow: register the account, then auto-login with the same credentials
 * so the user lands authenticated. Backend signup does not return tokens.
 */
export async function signup(payload: SignupPayload): Promise<User> {
  await apiClient.post<SignupResponse>('/auth/signup', payload)
  return login({ email: payload.email, password: payload.password })
}

export function getMe() {
  return apiClient.get<User>('/users/me')
}

/**
 * Logout: best-effort server call, then always clear local tokens.
 */
export async function logout(): Promise<void> {
  try {
    await apiClient.post<void>('/auth/logout')
  } catch {
    // ignore server-side errors; local state must still be cleared
  } finally {
    tokenStorage.clear()
  }
}
