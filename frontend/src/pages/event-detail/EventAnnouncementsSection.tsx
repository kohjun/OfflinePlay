import { useCallback, useEffect, useState } from 'react'
import {
  createEventAnnouncement,
  getEventAnnouncements,
  type EventAnnouncement,
} from '../../api/eventAnnouncements'
import { useToast } from '../../hooks/useToast'

interface EventAnnouncementsSectionProps {
  eventId: number
  /** owner / STAFF / ADMIN 여부 — true 면 "공지 보내기" 폼 노출. */
  canWrite: boolean
  /** APPROVED 참가자 / owner / STAFF / ADMIN — 위 권한이 없으면 backend 가 403 으로 막는다. */
  canRead: boolean
}

/**
 * PR141 — 이벤트 상세 페이지의 공지 섹션.
 *  - canRead 면 목록 fetch + 표시.
 *  - canWrite 면 작성 폼 추가. 발송 후 목록 refetch.
 *  - canRead=false 이면 섹션을 렌더링하지 않는다 (권한 없는 사용자에게는 보이지 않음).
 */
export function EventAnnouncementsSection({
  eventId,
  canWrite,
  canRead,
}: EventAnnouncementsSectionProps) {
  const { showToast } = useToast()
  const [items, setItems] = useState<EventAnnouncement[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const refresh = useCallback(() => {
    setLoading(true)
    setError(false)
    getEventAnnouncements(eventId)
      .then((rows) => setItems(rows ?? []))
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [eventId])

  useEffect(() => {
    if (!canRead) {
      setLoading(false)
      return
    }
    refresh()
  }, [canRead, refresh])

  if (!canRead) return null

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return
    const t = title.trim()
    const c = content.trim()
    if (t.length === 0 || c.length === 0) {
      showToast({ title: '공지 제목과 내용을 입력해주세요.', tone: 'warning' })
      return
    }
    setSubmitting(true)
    try {
      const created = await createEventAnnouncement(eventId, { title: t, content: c })
      setItems((prev) => [created, ...prev])
      setTitle('')
      setContent('')
      showToast({ title: '공지를 발송했어요', tone: 'success' })
    } catch (err) {
      showToast({
        title: '공지 발송에 실패했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="ct-event-section" aria-label="이벤트 공지">
      <h2 className="ct-event-section-title">공지</h2>

      {canWrite ? (
        <form className="card event-announcement-form" onSubmit={handleSubmit}>
          <div className="card-body stack">
            <label className="field">
              <span className="muted">제목</span>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="예: 공연 시작 시간 변경 안내"
                maxLength={200}
                disabled={submitting}
              />
            </label>
            <label className="field">
              <span className="muted">내용</span>
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="참가자에게 전달할 공지 내용을 입력해주세요."
                maxLength={5000}
                rows={4}
                disabled={submitting}
              />
            </label>
            <div className="event-announcement-form__actions">
              <button
                type="submit"
                className="button button-primary"
                disabled={submitting}
                aria-busy={submitting}
              >
                {submitting ? '발송 중…' : '공지 보내기'}
              </button>
            </div>
          </div>
        </form>
      ) : null}

      {loading ? (
        <p className="muted">불러오는 중…</p>
      ) : error ? (
        <div className="card">
          <div className="card-body stack">
            <p className="muted">공지를 불러오지 못했어요.</p>
            <button type="button" className="button button-secondary" onClick={refresh}>
              다시 시도
            </button>
          </div>
        </div>
      ) : items.length === 0 ? (
        <p className="muted">아직 공지가 없습니다.</p>
      ) : (
        <ul className="event-announcement-list">
          {items.map((a) => (
            <li key={a.id} className="card event-announcement-item">
              <div className="card-body stack">
                <strong>{a.title}</strong>
                <p className="ct-event-section-text">{a.content}</p>
                <span className="muted">
                  {a.authorNickname} · {new Date(a.createdAt).toLocaleString()}
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
