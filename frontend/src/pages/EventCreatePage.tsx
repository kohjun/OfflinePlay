import { FormEvent, useState } from 'react'
import { createEvent } from '../api/events'
import { useToast } from '../hooks/useToast'
import type { ContentType, EventPayload } from '../types'

interface EventCreatePageProps {
  channelId: number
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

/**
 * Combines a date input (YYYY-MM-DD) and a time input (HH:mm) into an
 * ISO-like LocalDateTime string the backend will parse (`YYYY-MM-DDTHH:mm:00`).
 */
function toLocalDateTime(date: string, time: string): string {
  return `${date}T${time}:00`
}

export function EventCreatePage({ channelId, onNavigate }: EventCreatePageProps) {
  const { showToast } = useToast()
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

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()

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

    // Future 제약 — 백엔드가 시작 시간을 Future 로 검증하므로 클라이언트도 미리 차단.
    if (new Date(startAt).getTime() <= Date.now()) {
      showToast({
        title: '시작 시간은 지금 이후여야 해요',
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
    if (!Number.isFinite(fee) || fee < 0) {
      showToast({ title: '참가비는 0원 이상이어야 해요', tone: 'warning' })
      return
    }

    const payload: EventPayload = {
      title: title.trim(),
      description: description.trim(),
      location: location.trim(),
      mainImageUrl: mainImageUrl.trim(),
      startAt,
      endAt,
      maxParticipants: max,
      participationFee: fee,
      refundPolicy: refundPolicy.trim(),
      detailContent: detailContent.trim(),
      contentType,
    }

    setSubmitting(true)
    try {
      const created = await createEvent(channelId, payload)
      showToast({ title: '이벤트가 등록되었어요', tone: 'success' })
      onNavigate(`/events/${created.id}`)
    } catch (error) {
      showToast({
        title: '이벤트 등록에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="page">
      <section className="page-header">
        <div>
          <p className="eyebrow">이벤트 등록</p>
          <h1>새 이벤트 공고</h1>
        </div>
      </section>
      <form className="form-stack event-form" onSubmit={handleSubmit}>
        <label>
          제목
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
            maxLength={100}
            placeholder="예: 한강 야간 보트 데이트"
          />
        </label>
        <label>
          짧은 소개 (목록/카드에 표시)
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            required
            maxLength={200}
            placeholder="참가자가 한눈에 알 수 있는 한 줄 소개"
          />
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
          <input
            value={location}
            onChange={(e) => setLocation(e.target.value)}
            required
            placeholder="예: 서울 한강공원 잠원지구"
          />
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
              min={1}
              value={maxParticipants}
              onChange={(e) => setMaxParticipants(e.target.value)}
              required
            />
          </label>
          <label>
            참가비 (원, 무료는 0)
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
          <textarea
            value={detailContent}
            onChange={(e) => setDetailContent(e.target.value)}
            required
            rows={6}
            placeholder="진행 방식, 준비물, 모임 흐름 등 자세한 설명"
          />
        </label>
        <label>
          환불 정책
          <textarea
            value={refundPolicy}
            onChange={(e) => setRefundPolicy(e.target.value)}
            required
            rows={4}
            placeholder="예: 시작 24시간 전까지 전액 환불, 이후 환불 불가"
          />
        </label>
        <div className="form-actions">
          <button
            type="button"
            className="button button-secondary"
            onClick={() => onNavigate(`/channels/${channelId}`)}
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
            {submitting ? '등록 중...' : '이벤트 등록'}
          </button>
        </div>
      </form>
    </main>
  )
}
