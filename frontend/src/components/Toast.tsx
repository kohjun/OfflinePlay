import { useToast } from '../hooks/useToast'

export function Toast() {
  const { toasts, removeToast } = useToast()

  return (
    <div className="toast-region" aria-live="polite" aria-label="Notifications">
      {toasts.map((toast) => (
        <button
          key={toast.id}
          className={`toast toast-${toast.tone ?? 'info'}`}
          onClick={() => removeToast(toast.id)}
          type="button"
        >
          <strong>{toast.title}</strong>
          {toast.message ? <span>{toast.message}</span> : null}
        </button>
      ))}
    </div>
  )
}
