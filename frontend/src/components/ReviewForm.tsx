import { useState } from 'react'

interface ReviewFormProps {
  /** 수정 모드 진입 시 초기값. 없으면 작성 모드. */
  initialRating?: number
  initialContent?: string
  submitting?: boolean
  onSubmit: (rating: number, content: string) => void
  onCancel?: () => void
}

/**
 * 별점 1~5 + 본문 입력 폼 — 작성/수정 공용.
 *
 *  - 별 5 개 button. hover/focus 에 따라 채워지는 정도가 미리보기로 갱신.
 *  - rating=0 또는 content 가 비어 있으면 제출 비활성.
 *  - submitting=true 면 버튼이 스피너로 전환.
 *  - 핸드오프 톤: coral border + 1.5px solid + body em 14px.
 */
export function ReviewForm({
  initialRating = 0,
  initialContent = '',
  submitting = false,
  onSubmit,
  onCancel,
}: ReviewFormProps) {
  const [rating, setRating] = useState<number>(initialRating)
  const [hoverRating, setHoverRating] = useState<number>(0)
  const [content, setContent] = useState<string>(initialContent)

  const displayRating = hoverRating || rating
  const canSubmit = rating >= 1 && content.trim().length > 0 && !submitting

  return (
    <form
      className="ct-review-form"
      onSubmit={(e) => {
        e.preventDefault()
        if (!canSubmit) return
        onSubmit(rating, content.trim())
      }}
    >
      <div className="ct-review-form__stars" role="radiogroup" aria-label="별점 선택">
        {[1, 2, 3, 4, 5].map((star) => (
          <button
            key={star}
            type="button"
            role="radio"
            aria-checked={rating === star}
            aria-label={`별 ${star}점`}
            className={`ct-review-form__star ${star <= displayRating ? 'is-active' : ''}`}
            onClick={() => setRating(star)}
            onMouseEnter={() => setHoverRating(star)}
            onMouseLeave={() => setHoverRating(0)}
            disabled={submitting}
          >
            ★
          </button>
        ))}
        <span className="ct-review-form__rating-label muted">
          {rating > 0 ? `${rating}점` : '별점을 선택하세요'}
        </span>
      </div>

      <textarea
        className="ct-review-form__textarea"
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder="다른 참가자에게 도움이 될 후기를 남겨주세요. (1000자 이내)"
        rows={4}
        maxLength={1000}
        disabled={submitting}
        required
      />
      <div className="ct-review-form__hint muted" aria-live="polite">
        {content.length} / 1000
      </div>

      <div className="ct-review-form__actions">
        {onCancel ? (
          <button
            type="button"
            className="button button-secondary"
            onClick={onCancel}
            disabled={submitting}
          >
            취소
          </button>
        ) : null}
        <button
          type="submit"
          className="button button-primary"
          disabled={!canSubmit}
          aria-busy={submitting}
        >
          {submitting ? <span className="button-spinner" aria-hidden="true" /> : null}
          {submitting ? '저장 중...' : initialRating ? '수정 완료' : '후기 등록'}
        </button>
      </div>
    </form>
  )
}
