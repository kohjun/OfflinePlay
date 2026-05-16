import { FormEvent, useEffect, useRef, useState } from 'react'
import { createEvent } from '../api/events'
import { uploadFile } from '../api/files'
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

  // 프리뷰 상태 — 로컬 파일 선택 시 createObjectURL 결과, 또는 URL 입력 시 그 값.
  // previewError 는 onError 로 표시: 잘못된 URL/도메인/CORS 등으로 이미지가 안 뜨는 경우.
  // 파일 업로드 진행 중이면 uploading=true 로 버튼 비활성.
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [previewError, setPreviewError] = useState(false)
  const [uploading, setUploading] = useState(false)
  // 로컬 파일에서 만든 blob: URL — 컴포넌트 정리 시 revoke 하지 않으면 메모리 누수.
  const blobUrlRef = useRef<string | null>(null)
  useEffect(() => {
    return () => {
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current)
    }
  }, [])

  function handleUrlChange(value: string) {
    setMainImageUrl(value)
    setPreviewError(false)
    // URL 을 직접 입력했으면 이전에 만든 blob 미리보기는 더 이상 필요 없다.
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current)
      blobUrlRef.current = null
    }
  }

  async function handlePickFile(file: File) {
    setPreviewError(false)
    // 즉시 로컬 프리뷰 보여주기 — 업로드 성공 여부와 무관.
    if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current)
    blobUrlRef.current = URL.createObjectURL(file)
    setMainImageUrl(blobUrlRef.current)

    setUploading(true)
    try {
      // CONTENT_THUMBNAIL 디렉토리에 업로드 — 이벤트 대표 이미지에 가장 가까운 카테고리.
      const result = await uploadFile(file, 'CONTENT_THUMBNAIL')
      // 업로드 성공: blob 을 실제 S3 URL 로 교체. blob 은 정리.
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current)
      blobUrlRef.current = null
      setMainImageUrl(result.url)
      showToast({ title: '이미지가 업로드되었어요', tone: 'success' })
    } catch (err) {
      // 로컬 환경에선 가짜 AWS credentials 때문에 보통 실패한다. 사용자에게 명확히 안내.
      const msg = err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.'
      showToast({
        title: '이미지 업로드 실패',
        message: `${msg} — 운영 환경에서만 동작합니다. 이미지 URL 을 직접 입력해주세요.`,
        tone: 'danger',
      })
      // blob URL 은 그대로 두면 미리보기는 보이지만, 폼 제출 시 backend 가 거부할 것이다.
      // 사용자가 정리하도록 둔다 (강제 clear 하면 의도와 충돌).
    } finally {
      setUploading(false)
    }
  }

  // 로컬 blob 미리보기가 남은 채 폼 제출하면 backend 가 String URL 검증으로 거부할 가능성.
  // 제출 시점에 차단해 토스트로 안내한다.
  const isBlobPreview = mainImageUrl.startsWith('blob:')

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
    if (isBlobPreview) {
      showToast({
        title: '이미지가 아직 업로드되지 않았어요',
        message: '업로드 완료 후 다시 시도하거나, 이미지 URL 을 직접 입력해주세요.',
        tone: 'warning',
      })
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
        <fieldset className="ct-media-picker" aria-label="대표 이미지">
          <legend>대표 이미지</legend>
          <div
            className={`ct-media-preview${mainImageUrl ? ' has-image' : ''}${previewError ? ' has-error' : ''}`}
            aria-live="polite"
          >
            {mainImageUrl && !previewError ? (
              <img
                src={mainImageUrl}
                alt="대표 이미지 미리보기"
                onLoad={() => setPreviewError(false)}
                onError={() => setPreviewError(true)}
              />
            ) : (
              <div className="ct-media-preview__placeholder">
                <span className="ct-media-preview__icon" aria-hidden="true">🖼</span>
                <strong>
                  {previewError ? '이미지를 불러올 수 없어요' : '카드/상세에 표시될 이미지'}
                </strong>
                <span className="muted">
                  {previewError
                    ? 'URL 이 올바른지, 외부 도메인이 접근 가능한지 확인해주세요.'
                    : 'URL 을 붙여넣거나 파일을 선택하면 미리보기가 나와요 (16:9 권장)'}
                </span>
              </div>
            )}
            {uploading ? (
              <div className="ct-media-preview__uploading" role="status">
                <span className="button-spinner" aria-hidden="true" />
                <span>업로드 중...</span>
              </div>
            ) : null}
          </div>

          <div className="ct-media-picker__actions">
            <button
              type="button"
              className="button button-secondary"
              onClick={() => fileInputRef.current?.click()}
              disabled={uploading || submitting}
            >
              파일 선택
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              hidden
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) handlePickFile(file)
                // 같은 파일 다시 고를 수 있게 input 값을 리셋.
                e.target.value = ''
              }}
            />
            {mainImageUrl ? (
              <button
                type="button"
                className="button button-secondary"
                onClick={() => handleUrlChange('')}
                disabled={uploading || submitting}
              >
                지우기
              </button>
            ) : null}
          </div>

          <label className="ct-media-picker__url">
            <span className="muted">또는 이미지 URL 직접 입력</span>
            <input
              type="url"
              value={isBlobPreview ? '' : mainImageUrl}
              onChange={(e) => handleUrlChange(e.target.value)}
              placeholder="https://..."
              required={!mainImageUrl}
            />
          </label>
        </fieldset>
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
