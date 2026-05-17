import { CommentForm } from '../../components/CommentForm'
import type { EventComment } from '../../types'

interface EventCommentsSectionProps {
  comments: EventComment[]
  commentDraft: string
  submittingComment: boolean
  onDraftChange: (next: string) => void
  onSubmit: () => void
}

/**
 * PR84 — 이벤트 토크(댓글) 섹션. 댓글 작성 form + 목록.
 */
export function EventCommentsSection({
  comments,
  commentDraft,
  submittingComment,
  onDraftChange,
  onSubmit,
}: EventCommentsSectionProps) {
  return (
    <section className="ct-event-section">
      <div className="section-heading">
        <h2 className="ct-event-section-title">이벤트 토크</h2>
        <span className="muted">{comments.length}</span>
      </div>
      <CommentForm
        value={commentDraft}
        onChange={onDraftChange}
        onSubmit={onSubmit}
        submitting={submittingComment}
        placeholder="참가자들과 이야기 나눠보세요"
      />
      <div className="stack">
        {comments.length === 0 ? (
          <p className="muted ct-event-empty-talk">아직 첫 메시지를 기다리고 있어요.</p>
        ) : (
          comments.map((comment) => (
            <article key={comment.id} className="card">
              <div className="card-body">
                <div className="card-heading-row">
                  <strong>{comment.authorNickname}</strong>
                  <span className="muted">{new Date(comment.createdAt).toLocaleString()}</span>
                </div>
                <p>{comment.content}</p>
              </div>
            </article>
          ))
        )}
      </div>
    </section>
  )
}
