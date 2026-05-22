import { FormEvent, useState } from 'react'
import { createMannerFeedback } from '../api/users'
import { useToast } from '../hooks/useToast'

interface MannerFeedbackFormProps {
  eventId: number
  revieweeId: number
  revieweeNickname: string
  onSubmitted: () => void
  onCancel: () => void
}

/**
 * PR146 — 매너 평가 입력 폼 (modal/inline).
 *
 *  - rating: 1~5 (필수)
 *  - tags  : 운영 정의 chip multi-select. set 은 frontend 가 고정.
 *  - comment: optional 500자.
 *
 * 운영 정의 태그 set 은 본 컴포넌트가 단일 source — 새 태그 추가 시 backend 와 함께 갱신.
 */
const TAG_OPTIONS: { slug: string; label: string }[] = [
  { slug: 'FRIENDLY', label: '친절해요' },
  { slug: 'PUNCTUAL', label: '시간 약속을 잘 지켜요' },
  { slug: 'POLITE', label: '매너가 좋아요' },
  { slug: 'COMMUNICATIVE', label: '소통이 원활해요' },
  { slug: 'PREPARED', label: '준비가 철저해요' },
]

export function MannerFeedbackForm({
  eventId,
  revieweeId,
  revieweeNickname,
  onSubmitted,
  onCancel,
}: MannerFeedbackFormProps) {
  const { showToast } = useToast()
  const [rating, setRating] = useState(5)
  const [tags, setTags] = useState<Set<string>>(() => new Set())
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)

  function toggleTag(slug: string) {
    setTags((prev) => {
      const next = new Set(prev)
      if (next.has(slug)) next.delete(slug)
      else next.add(slug)
      return next
    })
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (submitting) return
    if (rating < 1 || rating > 5) {
      showToast({ title: '평점은 1~5 사이여야 해요', tone: 'warning' })
      return
    }
    setSubmitting(true)
    try {
      await createMannerFeedback(eventId, {
        revieweeId,
        rating,
        tags: Array.from(tags),
        comment: comment.trim() || undefined,
      })
      showToast({ title: `${revieweeNickname}님께 매너 평가를 남겼어요`, tone: 'success' })
      onSubmitted()
    } catch (error) {
      showToast({
        title: '매너 평가 저장에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="form-stack manner-feedback-form" onSubmit={handleSubmit}>
      <label>
        평점
        <select
          value={rating}
          onChange={(e) => setRating(Number(e.target.value))}
          disabled={submitting}
        >
          <option value={5}>★★★★★ — 최고</option>
          <option value={4}>★★★★ — 좋아요</option>
          <option value={3}>★★★ — 보통</option>
          <option value={2}>★★ — 아쉬워요</option>
          <option value={1}>★ — 별로예요</option>
        </select>
      </label>
      <fieldset className="manner-tag-grid">
        <legend className="muted">잘했던 부분 (복수 선택)</legend>
        {TAG_OPTIONS.map((t) => (
          <label key={t.slug} className={`manner-tag-chip${tags.has(t.slug) ? ' is-selected' : ''}`}>
            <input
              type="checkbox"
              checked={tags.has(t.slug)}
              onChange={() => toggleTag(t.slug)}
              disabled={submitting}
            />
            {t.label}
          </label>
        ))}
      </fieldset>
      <label>
        한 줄 코멘트 (선택)
        <textarea
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          maxLength={500}
          rows={3}
          placeholder="다른 참가자가 참고할 수 있게 한 줄 남겨주세요."
          disabled={submitting}
        />
      </label>
      <div className="ct-profile-edit-actions">
        <button
          type="button"
          className="button button-secondary"
          onClick={onCancel}
          disabled={submitting}
        >
          취소
        </button>
        <button
          type="submit"
          className="button button-primary"
          disabled={submitting}
          aria-busy={submitting}
        >
          {submitting ? '제출 중…' : '평가 보내기'}
        </button>
      </div>
    </form>
  )
}
