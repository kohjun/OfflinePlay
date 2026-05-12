import { useCallback, useEffect } from 'react'
import * as authApi from '../api/auth'
import { setUnauthorizedHandler, tokenStorage } from '../api/client'
import { authStore, useAuthStore } from '../stores/authStore'
import type { LoginPayload, SignupPayload } from '../types'

// Single global handler — registered once at module load so every page that
// uses useAuth shares the same reset behavior on 401.
setUnauthorizedHandler(() => {
  authStore.reset()
})

let bootstrapped = false

export function useAuth() {
  const { user, loading } = useAuthStore()

  useEffect(() => {
    if (bootstrapped) return
    bootstrapped = true

    // No `alive` guard: authStore is module-level, so we want the in-flight
    // getMe() to land in the store even if this component unmounts (StrictMode
    // double-invoke would otherwise leave loading=true forever, since the
    // module flag blocks the remount from re-issuing the request).
    if (!tokenStorage.get()) {
      authStore.reset()
      return
    }

    authApi
      .getMe()
      .then((currentUser) => authStore.setUser(currentUser))
      .catch(() => authStore.reset())
  }, [])

  const login = useCallback(async (payload: LoginPayload) => {
    const me = await authApi.login(payload)
    authStore.setUser(me)
    return me
  }, [])

  const signup = useCallback(async (payload: SignupPayload) => {
    const me = await authApi.signup(payload)
    authStore.setUser(me)
    return me
  }, [])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      authStore.reset()
    }
  }, [])

  return { user, loading, isAuthenticated: Boolean(user), login, signup, logout }
}
