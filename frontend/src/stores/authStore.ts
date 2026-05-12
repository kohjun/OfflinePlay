import { useSyncExternalStore } from 'react'
import type { User } from '../types'

interface AuthState {
  user: User | null
  loading: boolean
}

let state: AuthState = {
  user: null,
  loading: true,
}

const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

export const authStore = {
  getSnapshot: () => state,
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  setUser(user: User | null) {
    state = { ...state, user, loading: false }
    emit()
  },
  setLoading(loading: boolean) {
    state = { ...state, loading }
    emit()
  },
  reset() {
    state = { user: null, loading: false }
    emit()
  },
}

export function useAuthStore() {
  return useSyncExternalStore(authStore.subscribe, authStore.getSnapshot, authStore.getSnapshot)
}
