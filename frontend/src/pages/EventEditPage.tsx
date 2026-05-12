import { FormEvent, useEffect, useState } from 'react'
import { getEventById, updateEvent, type UpdateEventPayload } from '../api/events'
import { ApiError } from '../api/client'
import { Skeleton } from '../components/Skeleton'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { ContentType, Event } from '../types'

interface EventEditPageProps {
  eventId: number
  onNavigate: (path: string) => void
}

interface ContentTypeOption {
  value: ContentType
  label: string
  desc: string
}

const CONTENT_TYPES: ContentTypeOption[] = [
  { value: 'ORIGINAL', label: 'Original', desc: 'Contenido만의 콘텐츠' },
  { value: 'CLASSIC', label: 'Classic', desc: '누구나 아는 콘텐츠' },
  { value: 'SPECIAL', label: 'Special', desc: '새롭게 기획한 예능' },
]

function splitLocalDateTime(value: string): { date: string; time: string } {
  // 백엔드가 LocalDateTime 을 ISO 형식 (yyyy-MM-ddTHH:mm:ss) 으로 내려준다고 가정.
  const m = value.match(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/)
  if (m) return { date: m[1], time: m[2] }
  return { date: '', time: '' }
}

function toLocalDateTime(date: string, time: string): string {
  return `${date}T${time}:00`
}

export function EventEditPage({ eventId, onNavigate }: EventEditPageProps) {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [event, setEvent] = useState<Event | null>(null)
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [location, setLocation] = useState('')
  const [date, setDate] = useState('')
  const [startTime, setStartTime] = useState('')
  const [endTime, setEndTime] = useState('')
  const [maxParticipants, setMaxParticipants] = useState('10')
  const [participationFee, setParticipationFee] = useState('0')
  const [mainImageUrl, setMainImageUrl] = useState('')
  const [detailContent, setDetailContent] = useState('')
  const [refundPolicy, setRefundPolicy] = useState('')
  const [contentType, setContentType] = useState<ContentType>('SPECIAL')
  const [submitting, setSubmitting] = useState(false)

  // 초기 로드 — flat /events/{id} 사용. 권한이 없으면 EventDetail 로 보낸다.
  useEffect(() => {
    let alive = true
    setLoading(true)
    setForbidden(false)
    getEventById(eventId)
      .then((ev) => {
        if (!alive) return
        setEvent(ev)
        setTitle(ev.title)
        setDescription(ev.description)
        setLocation(ev.location)
        const start = splitLocalDateTime(ev.startAt)
        setDate(start.date)
        setStartTime(start.time)
        setEndTime(splitLocalDateTime(ev.endAt).time)
        setMaxParticipants(String(ev.maxParticipants))
        setParticipationFee(String(ev.participationFee))
        setMainImageUrl(ev.mainImageUrl)
        setDetailContent(ev.detailContent)
        setRefundPolicy(ev.refundPolicy)
        if (ev.contentType) setContentType(ev.contentType)
      })
      .catch((err: unknown) => {
        if (!alive) return
        const status = (err as ApiError | null)?.status
        if (status === 404) {
          setEvent(null)
        }
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [eventId])

  // 권한 가드 — 로드 후 owner/ADMIN 이 아니면 forbidden.
  useEffect(() => {
    if (loading || !event || !user) return
    const isOwner = event.channelOwnerId === user.userId
    const isAdmin = user.role === 'ADMIN'
    if (!isOwner && !isAdmin) setForbidden(true)
  }, [loading, event, user])

  async function handleSubmit(formEvent: FormEvent) {
    formEvent.preventDefault()
    if (!event || submitting) return

    if (!title.trim()) {
      showToast({ title: '제목을 입력해주세요', tone: 'warning' })
      return
    }
    if (!description.trim()) {
      showToast({ title: '짧은 소개를 입력해주세요', tone: 'warning' })
      return
    }

    const startAt = toLocalDateTime(date, startTime)
    const endAt = toLocalDateTime(date, endTime)
    if (!(startAt < endAt)) {
      showToast({
        title: '시간 범위가 올바르지 않아요',
        message: '종료 시간은 시작 시간 이후여야 합니다.',
        tone: 'warning',
      })
      return
    }

    const max = Number(maxParticipants)
    const fee = Number(participationFee)
    if (!Number.isFinite(max) || max < 1) {
      showToast({ title: '참가 인원은 1명 이상이어야 해요', tone: 'warning' })
      return
    }
    if (max < event.currentParticipants) {
      showToast({
        title: `현재 ${event.currentParticipants}명이 참가 중이에요`,
        message: '현재 참가자 수보다 적게 정원을 줄일 수 없습니다.',
        tone: 'warning',
      })
      return
    }
    if (!Number.isFinite(fee) || fee < 0) {
      showToast({ title: '참가비는 0원 이상이어야 해요', tone: 'warning' })
      return
    }

    // 변경된 필드만 payload 에 담아 서버 차원의 안전 정책(특히 참가비)을 정확히 작동시킨다.
    const payload: UpdateEventPayload = {}
    if (title.trim() !== event.title) payload.title = title.trim()
    if (description.trim() !== event.description) payload.description = description.trim()
    if (location.trim() !== event.location) payload.location = location.trim()
    if (mainImageUrl.trim() !== event.mainImageUrl) payload.mainImageUrl = mainImageUrl.trim()
    if (refundPolicy.trim() !== event.refundPolicy) payload.refundPolicy = refundPolicy.trim()
    if (detailContent.trim() !== event.detailContent) payload.detailContent = detailContent.trim()
    if (contentType !== event.contentType) payload.contentType = contentType
    if (max !== event.maxParticipants) payload.maxParticipants = max
    if (fee !== event.participationFee) payload.participationFee = fee
    if (startAt !== event.startAt.slice(0, 16) + ':00' && startAt !== event.startAt) payload.startAt = startAt
    if (endAt !== event.endAt.slice(0, 16) + ':00' && endAt !== event.endAt) payload.endAt = endAt

    if (Object.keys(payload).length === 0) {
      showToast({ title: '변경된 내용이 없어요', tone: 'info' })
      return
    }

    setSubmitting(true)
    try {
      await updateEvent(eventId, payload)
      showToast({ title: '이벤트가 수정되었어요', tone: 'success' })
      onNavigate(`/events/${eventId}`)
    } catch (error) {
      const status = (error as ApiError | null)?.status
      const message = error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.'
      if (status === 409) {
        showToast({ title: '수정할 수 없는 변경이에요', message, tone: 'danger' })
      } else if (status === 403) {
        showToast({ title: '수정 권한이 없어요', tone: 'danger' })
      } else {
        showToast({ title: '이벤트 수정 실패', message, tone: 'danger' })
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <main className="page">
        <Skeleton lines={6} />
      </main>
    )
  }

  if (!event) {
    return (
      <main className="page empty-state">
        <h1>이벤트를 찾을 수 없어요</h1>
        <button className="button button-primary is-block" type="button" onClick={() => onNavigate('/creator')}>
          기획자 스튜디오로
        </button>
      </main>
    )
  }

  if (forbidden) {
    return (
      <main className="page empty-state">
        <h1>수정 권한이 없어요</h1>
        <p className="muted">이벤트를 만든 채널 운영자만 수정할 수 있어요.</p>
        <button
          className="button button-primary is-block"
          type="button"
          onClick={() => onNavigate(`/events/${eventId}`)}
        >
          이벤트 상세로
        </button>
      </main>
    )
  }

  return (
    <main className="page">
      <section className="page-header">
        <div>
          <p className="eyebrow">이벤트 수정</p>
          <h1>{event.title}</h1>
        </div>
      </section>
      <form className="form-stack event-form" onSubmit={handleSubmit}>
        <label>
          제목
          <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={100} />
        </label>
        <label>
          짧은 소개
          <input value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={200} />
        </label>

        <fieldset className="ct-content-type-picker" aria-label="콘텐츠 유형">
          <legend>콘텐츠 유형</legend>
          <div className="ct-content-type-picker-row">
            {CONTENT_TYPES.map((ct) => (
              <button
                key={ct.value}
                type="button"
                className={`ct-content-type-option ${contentType === ct.value ? 'is-active' : ''}`}
                onClick={() => setContentType(ct.value)}
                aria-pressed={contentType === ct.value}
              >
                <strong>{ct.label}</strong>
                <span>{ct.desc}</span>
              </button>
            ))}
          </div>
        </fieldset>

        <label>
          장소
          <input value={location} onChange={(e) => setLocation(e.target.value)} required />
        </label>
        <div className="field-row">
          <label>
            날짜
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
          </label>
          <label>
            시작 시간
            <input type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} required />
          </label>
          <label>
            종료 시간
            <input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} required />
          </label>
        </div>
        <div className="field-row">
          <label>
            참가 인원
            <input
              type="number"
              min={Math.max(1, event.currentParticipants)}
              value={maxParticipants}
              onChange={(e) => setMaxParticipants(e.target.value)}
              required
            />
          </label>
          <label>
            참가비 (원)
            <input
              type="number"
              min={0}
              value={participationFee}
              onChange={(e) => setParticipationFee(e.target.value)}
              required
            />
          </label>
        </div>
        <label>
          대표 이미지 URL
          <input
            type="url"
            value={mainImageUrl}
            onChange={(e) => setMainImageUrl(e.target.value)}
            placeholder="https://..."
            required
          />
        </label>
        <label>
          이벤트 상세 내용
          <textarea value={detailContent} onChange={(e) => setDetailContent(e.target.value)} required rows={6} />
        </label>
        <label>
          환불 정책
          <textarea value={refundPolicy} onChange={(e) => setRefundPolicy(e.target.value)} required rows={4} />
        </label>
        <div className="form-actions">
          <button
            type="button"
            className="button button-secondary"
            onClick={() => onNavigate(`/events/${eventId}`)}
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
            {submitting ? <span className="button-spinner" aria-hidden="true" /> : null}
            {submitting ? '저장 중...' : '변경 저장'}
          </button>
        </div>
      </form>
    </main>
  )
}
