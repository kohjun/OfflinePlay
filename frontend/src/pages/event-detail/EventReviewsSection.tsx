import { ReviewForm } from '../../components/ReviewForm'
import type { EventReviewSummary, Review } from '../../api/reviews'
import type { MyParticipation, User } from '../../types'

interface EventReviewsSectionProps {
  reviewSummary: EventReviewSummary
  reviews: Review[]
  myReview: Review | null
  showReviewForm: boolean
  submittingReview: boolean
  participation: MyParticipation | null
  user: User | null
  /**
   * PR139 — 이벤트 종료 여부. `event.endAt < now` 일 때 true. 후기는 종료 후에만 작성 가능
   * (체크인 완료자 한정). 본 컴포넌트는 시간 비교를 직접 하지 않고 props 로 받는다 — 페이지
   * 가 한 곳에서 계산해 일관된 시각을 보장.
   */
  isEventEnded: boolean
  onShowForm: () => void
  onHideForm: () => void
  onSubmit: (rating: number, content: string) => Promise<void> | void
  onDelete: () => void
  onReportReview: (review: Review) => void
}

/**
 * PR84 — 후기 섹션. summary chip + 작성 CTA + 내 후기 카드 + 작성 폼 + 다른 사람 후기 목록.
 * 상태/네트워크는 parent. 본 컴포넌트는 콜백만 호출한다.
 */
export function EventReviewsSection({
  reviewSummary,
  reviews,
  myReview,
  showReviewForm,
  submittingReview,
  participation,
  user,
  isEventEnded,
  onShowForm,
  onHideForm,
  onSubmit,
  onDelete,
  onReportReview,
}: EventReviewsSectionProps) {
  // PR139 — 후기 작성 자격 분기 헬퍼.
  const hasUsedTicket = participation?.ticketStatus === 'USED'
  const canWrite = isEventEnded && hasUsedTicket && !myReview && !showReviewForm
  // 작성 자격은 없지만 안내 카피를 보여줘야 하는 경우 (티켓 보유자가 아직 못 쓰는 상태).
  const shouldShowWaitCopy =
    user != null && hasUsedTicket && !isEventEnded && !myReview && !showReviewForm
  return (
    <section className="ct-event-section ct-reviews-section">
      <div className="section-heading">
        <h2 className="ct-event-section-title">후기</h2>
        {reviewSummary.reviewCount > 0 ? (
          <span className="ct-reviews-summary" aria-label={`평균 별점 ${reviewSummary.averageRating?.toFixed(1)}점, 후기 ${reviewSummary.reviewCount}건`}>
            <span className="ct-reviews-summary__star" aria-hidden="true">★</span>
            <strong>{reviewSummary.averageRating?.toFixed(1) ?? '—'}</strong>
            <span className="muted">({reviewSummary.reviewCount})</span>
          </span>
        ) : (
          <span className="muted">아직 후기가 없어요</span>
        )}
      </div>

      {/* PR139 — 본인이 USED 티켓 + 이벤트 종료 + 아직 후기 미작성이면 작성 CTA. */}
      {canWrite ? (
        <button
          type="button"
          className="button button-primary"
          onClick={onShowForm}
        >
          후기 남기기
        </button>
      ) : null}

      {/* 티켓은 있지만 이벤트가 아직 끝나지 않은 경우 — 언제 가능해지는지 카피로 알린다. */}
      {shouldShowWaitCopy ? (
        <p className="muted" role="status">
          후기는 이벤트가 끝난 뒤에 작성할 수 있어요.
        </p>
      ) : null}

      {/* 본인 후기가 이미 있으면 카드 + 수정/삭제 버튼. showReviewForm 인 경우 폼 우선. */}
      {myReview && !showReviewForm ? (
        <article className="card ct-review-card ct-review-card--mine">
          <div className="card-body">
            <div className="ct-review-card__head">
              <div className="ct-review-card__author">
                <span className="ct-review-card__stars" aria-hidden="true">
                  {'★'.repeat(myReview.rating)}
                  <span className="ct-review-card__stars-empty">{'★'.repeat(5 - myReview.rating)}</span>
                </span>
                <span className="muted">내 후기</span>
              </div>
              <div className="ct-review-card__actions">
                <button
                  type="button"
                  className="text-button"
                  onClick={onShowForm}
                >
                  수정
                </button>
                <button
                  type="button"
                  className="text-button text-button--danger"
                  onClick={onDelete}
                >
                  삭제
                </button>
              </div>
            </div>
            <p className="ct-review-card__content">{myReview.content}</p>
          </div>
        </article>
      ) : null}

      {showReviewForm ? (
        <ReviewForm
          initialRating={myReview?.rating ?? 0}
          initialContent={myReview?.content ?? ''}
          submitting={submittingReview}
          onSubmit={onSubmit}
          onCancel={onHideForm}
        />
      ) : null}

      {/* 다른 사람들의 후기 — 본인 후기는 위에서 별도 노출하므로 목록에서 제외. */}
      {reviews.length > 0 ? (
        <ul className="ct-review-list">
          {reviews
            .filter((r) => r.id !== myReview?.id)
            .map((r) => (
              <li key={r.id} className="card ct-review-card">
                <div className="card-body">
                  <div className="ct-review-card__head">
                    <div className="ct-review-card__author">
                      <span className="ct-review-card__stars" aria-hidden="true">
                        {'★'.repeat(r.rating)}
                        <span className="ct-review-card__stars-empty">{'★'.repeat(5 - r.rating)}</span>
                      </span>
                      <span className="muted">{r.authorNickname}</span>
                    </div>
                    <div className="ct-review-card__actions">
                      <span className="ct-review-card__date muted">
                        {new Date(r.createdAt).toLocaleDateString()}
                      </span>
                      {/* PR48: 본인 글이 아닐 때만 신고 버튼 노출. user 가 없으면(비로그인) 숨김. */}
                      {user && user.userId !== r.authorId ? (
                        <button
                          type="button"
                          className="text-button text-button--danger"
                          onClick={() => onReportReview(r)}
                          aria-label="이 후기 신고"
                        >
                          신고
                        </button>
                      ) : null}
                    </div>
                  </div>
                  <p className="ct-review-card__content">{r.content}</p>
                </div>
              </li>
            ))}
        </ul>
      ) : null}
    </section>
  )
}
