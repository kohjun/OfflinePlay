import { useCallback, useSyncExternalStore } from 'react'
import type { ToastMessage } from '../types'

const TOAST_DURATION_MS = 3000

let toasts: ToastMessage[] = []
let nextToastId = 1
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function getSnapshot() {
  return toasts
}

export function useToast() {
  const items = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  const removeToast = useCallback((id: number) => {
    toasts = toasts.filter((toast) => toast.id !== id)
    emit()
  }, [])

  const showToast = useCallback(
    (toast: Omit<ToastMessage, 'id'>) => {
      const id = nextToastId
      nextToastId += 1
      toasts = [...toasts, { ...toast, id }]
      emit()
      window.setTimeout(() => removeToast(id), TOAST_DURATION_MS)
      return id
    },
    [removeToast],
  )

  return { toasts: items, showToast, removeToast }
}
