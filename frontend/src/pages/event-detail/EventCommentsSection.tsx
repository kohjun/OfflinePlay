import { CommentForm } from '../../components/CommentForm'
import type { EventComment } from '../../types'

interface EventCommentsSectionProps {
  comments: EventComment[]
  commentDraft: string
  submittingComment: boolean
  /**
   * PR140 — 본 사용자가 이벤트 룸에 들어올 수 있는지. APPROVED 참가자 / 채널 owner / ADMIN
   * 셋 중 하나면 true. 그 외는 false — 댓글 form 대신 안내 카피를 노출한다.
   * 미로그인 사용자도 false.
   */
  canAccessRoom: boolean
  onDraftChange: (next: string) => void
  onSubmit: () => void
}

/**
 * PR84 → PR140 — "이벤트 토크" 를 "이벤트 룸" 으로 개편. 참가 확정자 / 운영자 / ADMIN 만
 * 글을 쓸 수 있는 비공개 대화 공간. backend `TargetType.EVENT` 댓글을 그대로 재사용하며
 * 권한 가드는 backend `CommentService.requireEventRoomMember` 와 frontend `canAccessRoom`
 * 양쪽에 둔다 — 서버가 최종 권한, 클라이언트는 UX 가이드.
 */
export function EventCommentsSection({
  comments,
  commentDraft,
  submittingComment,
  canAccessRoom,
  onDraftChange,
  onSubmit,
}: EventCommentsSectionProps) {
  return (
    <section className="ct-event-section">
      <div className="section-heading">
        <h2 className="ct-event-section-title">이벤트 룸</h2>
        <span className="muted">{comments.length}</span>
      </div>
      {canAccessRoom ? (
        <CommentForm
          value={commentDraft}
          onChange={onDraftChange}
          onSubmit={onSubmit}
          submitting={submittingComment}
          placeholder="참가자들과 이야기 나눠보세요"
        />
      ) : (
        <p className="muted" role="status">
          참가가 확정된 후 운영자와 참가자가 함께 쓰는 비공개 룸이에요. 신청이 승인되면 입장할 수 있어요.
        </p>
      )}
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
