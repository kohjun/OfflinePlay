import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createEventAnnouncement,
  getEventAnnouncements,
  markEventAnnouncementAsRead,
  setEventAnnouncementPinned,
  type EventAnnouncement,
} from '../../api/eventAnnouncements'
import { uploadFile } from '../../api/files'
import { Badge } from '../../components/Badge'
import { useToast } from '../../hooks/useToast'

const MAX_IMAGES = 3

interface EventAnnouncementsSectionProps {
  eventId: number
  /** owner / STAFF / ADMIN 여부 — true 면 "공지 보내기" 폼 + pin 토글 노출. */
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
  /** PR151 — 펼침 상태별 read 자동 처리. */
  const [expandedIds, setExpandedIds] = useState<Set<number>>(() => new Set())
  /** PR152 — 작성 form 에 첨부된 이미지 url. 최대 MAX_IMAGES 장. */
  const [draftImageUrls, setDraftImageUrls] = useState<string[]>([])
  const [uploadingImage, setUploadingImage] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

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
      const created = await createEventAnnouncement(eventId, {
        title: t,
        content: c,
        imageUrls: draftImageUrls.length > 0 ? draftImageUrls : undefined,
      })
      setItems((prev) => [created, ...prev])
      setTitle('')
      setContent('')
      setDraftImageUrls([])
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
            <div className="event-announcement-form__images">
              <div className="card-heading-row">
                <strong>첨부 이미지 (선택)</strong>
                <span className="muted">{draftImageUrls.length}/{MAX_IMAGES}</span>
              </div>
              <div className="event-announcement-form__image-row">
                {draftImageUrls.map((url, idx) => (
                  <div key={url + idx} className="event-announcement-form__image-thumb">
                    <img src={url} alt="" />
                    <button
                      type="button"
                      className="button button-tertiary"
                      onClick={() => removeDraftImage(idx)}
                      disabled={submitting}
                    >
                      삭제
                    </button>
                  </div>
                ))}
                {draftImageUrls.length < MAX_IMAGES ? (
                  <button
                    type="button"
                    className="button button-secondary"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={submitting || uploadingImage}
                    aria-busy={uploadingImage}
                  >
                    {uploadingImage ? '업로드 중…' : '이미지 추가'}
                  </button>
                ) : null}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  hidden
                  onChange={handlePickImage}
                />
              </div>
            </div>
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
          {items.map((a) => {
            const expanded = expandedIds.has(a.id)
            return (
              <li
                key={a.id}
                className={`card event-announcement-item${a.pinned ? ' is-pinned' : ''}${
                  !a.read ? ' is-unread' : ''
                }`}
              >
                <div className="card-body stack">
                  <div className="card-heading-row">
                    <strong>{a.title}</strong>
                    <div className="event-announcement-item__badges">
                      {a.pinned ? <Badge tone="primary">고정</Badge> : null}
                      {!a.read ? <Badge tone="danger">새 공지</Badge> : null}
                    </div>
                  </div>
                  {expanded ? (
                    <>
                      <p className="ct-event-section-text">{a.content}</p>
                      {a.imageUrls.length > 0 ? (
                        <div
                          className={`event-announcement-images event-announcement-images--${a.imageUrls.length}`}
                        >
                          {a.imageUrls.map((url) => (
                            <a key={url} href={url} target="_blank" rel="noreferrer">
                              <img src={url} alt="" loading="lazy" />
                            </a>
                          ))}
                        </div>
                      ) : null}
                    </>
                  ) : null}
                  <span className="muted">
                    {a.authorNickname} · {new Date(a.createdAt).toLocaleString()}
                  </span>
                  <div className="event-announcement-item__actions">
                    <button
                      type="button"
                      className="button button-tertiary"
                      onClick={() => handleToggleExpand(a)}
                    >
                      {expanded ? '접기' : '본문 보기'}
                    </button>
                    {canWrite ? (
                      <button
                        type="button"
                        className="button button-secondary"
                        onClick={() => handleTogglePin(a)}
                      >
                        {a.pinned ? '고정 해제' : '상단 고정'}
                      </button>
                    ) : null}
                  </div>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )

  /** PR152 — 이미지 picker → S3 upload → draft list 에 url append. */
  async function handlePickImage(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (draftImageUrls.length >= MAX_IMAGES) {
      showToast({ title: `이미지는 최대 ${MAX_IMAGES}장까지 첨부할 수 있어요.`, tone: 'warning' })
      return
    }
    setUploadingImage(true)
    try {
      const uploaded = await uploadFile(file, 'POST')
      setDraftImageUrls((prev) => [...prev, uploaded.url].slice(0, MAX_IMAGES))
    } catch (err) {
      showToast({
        title: '이미지 업로드에 실패했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setUploadingImage(false)
    }
  }

  function removeDraftImage(index: number) {
    setDraftImageUrls((prev) => prev.filter((_, i) => i !== index))
  }

  /** PR151 — 펼치면 read 자동 POST + UI optimistic mark. 접기는 read 변경 없음. */
  async function handleToggleExpand(a: EventAnnouncement) {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(a.id)) next.delete(a.id)
      else next.add(a.id)
      return next
    })
    if (a.read) return
    setItems((prev) => prev.map((row) => (row.id === a.id ? { ...row, read: true } : row)))
    try {
      await markEventAnnouncementAsRead(eventId, a.id)
    } catch {
      // 실패 시 optimistic 만 적용. 다음 마운트 시 새 fetch.
    }
  }

  /** PR151 — pin 토글. 같은 이벤트 다른 pinned 는 backend 가 해제 — frontend 는 optimistic 반영. */
  async function handleTogglePin(a: EventAnnouncement) {
    const next = !a.pinned
    setItems((prev) =>
      prev.map((row) => ({
        ...row,
        pinned: row.id === a.id ? next : next ? false : row.pinned,
      })),
    )
    try {
      const updated = await setEventAnnouncementPinned(eventId, a.id, next)
      setItems((prev) =>
        prev.map((row) => (row.id === a.id ? { ...row, pinned: updated.pinned } : row)),
      )
      showToast({
        title: next ? '공지를 상단에 고정했어요' : '공지 고정을 해제했어요',
        tone: 'success',
      })
    } catch (err) {
      showToast({
        title: '공지 고정 변경에 실패했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
      refresh()
    }
  }
}
