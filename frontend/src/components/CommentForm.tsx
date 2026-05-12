import { FormEvent } from 'react'

interface CommentFormProps {
  value: string
  onChange: (value: string) => void
  onSubmit: () => void
  submitting?: boolean
  maxLength?: number
  placeholder?: string
}

export function CommentForm({
  value,
  onChange,
  onSubmit,
  submitting = false,
  maxLength = 500,
  placeholder = 'Leave a comment',
}: CommentFormProps) {
  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!value.trim() || submitting) return
    onSubmit()
  }

  return (
    <form className="comment-form" onSubmit={handleSubmit}>
      <div className="comment-input-row">
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          maxLength={maxLength}
          aria-label="Comment"
        />
        <button
          className="button button-primary"
          disabled={submitting || !value.trim()}
          type="submit"
        >
          {submitting ? '...' : 'Send'}
        </button>
      </div>
    </form>
  )
}
