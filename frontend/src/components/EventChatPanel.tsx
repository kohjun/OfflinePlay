import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  canEnterEventChat,
  getEventChatHistory,
  sendEventChatMessage,
  type EventChatMessage,
} from '../api/eventChat'
import { ApiError } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import { chatStore } from '../stores/chatStore'
import { Badge } from './Badge'

interface EventChatPanelProps {
  eventId: number
  /** 운영자 (owner / STAFF / ADMIN) 여부. true 면 "공지로 보내기" 체크박스 노출. */
  isOperator: boolean
  /** 사용자가 입장 가능한지 (PR160 권한 가드 결과). false 면 안내 카피. */
  canAccessRoom: boolean
}

/**
 * PR161 — 이벤트룸 채팅 panel (카카오톡 스타일).
 *
 *  - 마운트 시 권한 가드 확인 + 최근 50건 history fetch.
 *  - SSE `event-chat` event 로 새 메시지 실시간 수신 (chatStore 구독).
 *  - 운영자 "공지로 보내기" 체크박스 → push 알림 동반.
 *  - 입력은 multi-line textarea (Enter = 전송, Shift+Enter = 줄바꿈).
 */
export function EventChatPanel({ eventId, isOperator, canAccessRoom }: EventChatPanelProps) {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [messages, setMessages] = useState<EventChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [isAnnouncement, setIsAnnouncement] = useState(false)
  const [loading, setLoading] = useState(true)
  const [accessError, setAccessError] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)

  // 입장 가드 + 초기 history fetch.
  useEffect(() => {
    if (!canAccessRoom) {
      setLoading(false)
      return
    }
    let alive = true
    setLoading(true)
    setAccessError(null)
    canEnterEventChat(eventId)
      .then(() => getEventChatHistory(eventId, { size: 50 }))
      .then((history) => {
        if (!alive) return
        setMessages(history.items)
      })
      .catch((err) => {
        if (!alive) return
        if (err instanceof ApiError && err.status === 403) {
          setAccessError('참가 확정자만 채팅방에 입장할 수 있어요.')
        } else {
          setAccessError(err instanceof Error ? err.message : '채팅을 불러오지 못했어요.')
        }
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [eventId, canAccessRoom])

  // SSE 실시간 수신 — 본 룸의 메시지만 골라 append.
  useEffect(() => {
    if (!canAccessRoom) return
    return chatStore.subscribe((message) => {
      if (message.eventId !== eventId) return
      setMessages((prev) => {
        // 중복 (예: 본인 echo + REST 응답 둘 다 도착) 방지.
        if (prev.some((m) => m.id === message.id)) return prev
        return [...prev, message]
      })
    })
  }, [eventId, canAccessRoom])

  // 새 메시지 도착 시 스크롤 하단 고정.
  useEffect(() => {
    if (!scrollRef.current) return
    scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [messages.length])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    const content = draft.trim()
    if (content.length === 0) return
    setSubmitting(true)
    try {
      const sent = await sendEventChatMessage(eventId, {
        content,
        isAnnouncement: isOperator && isAnnouncement,
      })
      // optimistic append — SSE 가 같은 id 로 와도 dedupe 됨.
      setMessages((prev) => (prev.some((m) => m.id === sent.id) ? prev : [...prev, sent]))
      setDraft('')
      setIsAnnouncement(false)
      if (sent.isAnnouncement) {
        showToast({ title: '공지 메시지를 보냈어요', message: '참가자에게 푸시 알림도 발송됩니다.', tone: 'success' })
      }
    } catch (err) {
      showToast({
        title: '메시지를 보내지 못했어요',
        message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      const form = e.currentTarget.form
      if (form) form.requestSubmit()
    }
  }

  if (!canAccessRoom) {
    return (
      <div className="event-chat event-chat--locked">
        <p className="muted">참가가 확정된 후에 채팅방이 열려요. 신청이 승인되거나 결제가 완료되면 입장할 수 있습니다.</p>
      </div>
    )
  }

  if (loading) {
    return <p className="muted">채팅을 불러오는 중…</p>
  }

  if (accessError) {
    return (
      <div className="event-chat event-chat--locked">
        <p className="muted">{accessError}</p>
      </div>
    )
  }

  return (
    <div className="event-chat">
      <div className="event-chat__messages" ref={scrollRef}>
        {messages.length === 0 ? (
          <p className="muted">아직 첫 메시지를 기다리고 있어요. 가벼운 인사로 시작해보세요.</p>
        ) : (
          messages.map((m) => {
            const mine = user?.userId === m.senderId
            return (
              <article
                key={m.id}
                className={`event-chat__message${mine ? ' is-mine' : ''}${
                  m.isAnnouncement ? ' is-announcement' : ''
                }`}
              >
                <header className="event-chat__message-head">
                  <strong>{m.senderNickname}</strong>
                  {m.isAnnouncement ? <Badge tone="primary">공지</Badge> : null}
                  <span className="muted">{new Date(m.createdAt).toLocaleTimeString()}</span>
                </header>
                <p>{m.content}</p>
              </article>
            )
          })
        )}
      </div>
      <form className="event-chat__form" onSubmit={handleSubmit}>
        {isOperator ? (
          <label className="event-chat__announcement-toggle">
            <input
              type="checkbox"
              checked={isAnnouncement}
              onChange={(e) => setIsAnnouncement(e.target.checked)}
              disabled={submitting}
            />
            공지로 보내기 (참가자에게 푸시 알림)
          </label>
        ) : null}
        <div className="event-chat__input-row">
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={isAnnouncement ? '공지 내용을 입력해 주세요…' : '메시지를 입력하세요…'}
            maxLength={500}
            rows={2}
            disabled={submitting}
          />
          <button
            type="submit"
            className={`button ${isAnnouncement ? 'button-primary' : 'button-secondary'}`}
            disabled={submitting || draft.trim().length === 0}
            aria-busy={submitting}
          >
            {submitting ? '전송 중…' : isAnnouncement ? '공지 전송' : '전송'}
          </button>
        </div>
      </form>
    </div>
  )
}
