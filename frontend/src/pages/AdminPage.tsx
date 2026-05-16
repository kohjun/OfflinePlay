import { useEffect, useState } from 'react'
import {
  approveCreatorApplication,
  banChannelForModeration,
  dismissReport,
  exportModerationAuditLogs,
  getAdminChannels,
  getCreatorApplications,
  getModerationAuditLog,
  getModerationAuditLogs,
  getModerationQueue,
  getModerationStats,
  getModerationThresholds,
  getReports,
  hideModerationTarget,
  rejectCreatorApplication,
  resolveReport,
  unbanChannelForModeration,
  unhideModerationTarget,
  updateModerationThresholds,
} from '../api/admin'
import {
  approveReportAppeal,
  getAdminReportAppeals,
  rejectReportAppeal,
} from '../api/reportAppeals'
import { Badge } from '../components/Badge'
import { useAuth } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type {
  AdminModerationPriority,
  AdminModerationQueueItem,
  AdminModerationStats,
  Channel,
  CreatorApplication,
  ModerationAuditAction,
  ModerationAuditLog,
  ModerationThreshold,
  Report,
  ReportAppeal,
  ReportTargetType,
  UpdateModerationThresholdsRequest,
} from '../types'

const PRIORITY_LABEL: Record<AdminModerationPriority, string> = {
  HIGH: '우선',
  MEDIUM: '주의',
  LOW: '관찰',
}

const PRIORITY_TONE: Record<AdminModerationPriority, 'danger' | 'warning' | 'neutral'> = {
  HIGH: 'danger',
  MEDIUM: 'warning',
  LOW: 'neutral',
}

type ReportFilter = 'ALL' | ReportTargetType

const REPORT_FILTERS: Array<{ value: ReportFilter; label: string }> = [
  { value: 'ALL', label: '전체' },
  { value: 'REVIEW', label: '후기' },
  { value: 'COMMENT', label: '댓글' },
  { value: 'POST', label: '게시글' },
  { value: 'EVENT', label: '이벤트' },
  { value: 'CHANNEL', label: '채널' },
]

const TARGET_TYPE_LABEL: Record<ReportTargetType, string> = {
  CHANNEL: '채널',
  POST: '게시글',
  EVENT: '이벤트',
  COMMENT: '댓글',
  REVIEW: '후기',
}

const AUDIT_ACTION_LABEL: Record<ModerationAuditAction, string> = {
  THRESHOLD_UPDATED: '임계치 변경',
  TARGET_HIDDEN: '수동 숨김',
  TARGET_UNHIDDEN: '숨김 해제',
  CHANNEL_BANNED: '채널 제재',
  CHANNEL_UNBANNED: '채널 제재 해제',
  APPEAL_APPROVED: '이의 제기 승인',
  APPEAL_REJECTED: '이의 제기 거절',
  REPORT_RESOLVED: '신고 해결',
  REPORT_DISMISSED: '신고 기각',
}

const AUDIT_ACTION_TONE: Record<ModerationAuditAction, 'danger' | 'warning' | 'success' | 'neutral' | 'primary'> = {
  THRESHOLD_UPDATED: 'primary',
  TARGET_HIDDEN: 'danger',
  TARGET_UNHIDDEN: 'success',
  CHANNEL_BANNED: 'danger',
  CHANNEL_UNBANNED: 'success',
  APPEAL_APPROVED: 'success',
  APPEAL_REJECTED: 'warning',
  REPORT_RESOLVED: 'success',
  REPORT_DISMISSED: 'neutral',
}

/**
 * PR62 — audit log 필터 폼 상태. 입력 중에는 빈 문자열을 유지하고, API 호출 시점에만
 * undefined 로 변환해서 backend 로 보낸다 (controlled input 유지).
 */
interface AuditFiltersForm {
  action: ModerationAuditAction | ''
  targetType: ReportTargetType | ''
  targetId: string
  actorId: string
  from: string
  to: string
}

const EMPTY_AUDIT_FILTERS: AuditFiltersForm = {
  action: '',
  targetType: '',
  targetId: '',
  actorId: '',
  from: '',
  to: '',
}

const AUDIT_ACTION_OPTIONS: ModerationAuditAction[] = [
  'TARGET_HIDDEN',
  'TARGET_UNHIDDEN',
  'CHANNEL_BANNED',
  'CHANNEL_UNBANNED',
  'APPEAL_APPROVED',
  'APPEAL_REJECTED',
  'REPORT_RESOLVED',
  'REPORT_DISMISSED',
  'THRESHOLD_UPDATED',
]

const AUDIT_TARGET_TYPE_OPTIONS: ReportTargetType[] = [
  'CHANNEL',
  'EVENT',
  'POST',
  'COMMENT',
  'REVIEW',
]

const AUDIT_PAGE_SIZE = 20

/** input 문자열을 양의 Long 으로 변환. 빈/비숫자/0 이하 는 undefined → backend 무시. */
function parsePositiveLong(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (trimmed === '') return undefined
  const n = Number(trimmed)
  return Number.isInteger(n) && n > 0 ? n : undefined
}

/**
 * audit 의 before/after 는 service 가 JSON 또는 raw string 으로 저장. UI 표시 시 JSON 이면
 * 2-space indent 로 pretty-print 하고, 그 외에는 원문 그대로. 안전하게 try-catch.
 */
function prettyAuditValue(raw: string | null | undefined): string {
  if (raw == null || raw === '') return ''
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'object' && parsed !== null) {
      return JSON.stringify(parsed, null, 2)
    }
  } catch {
    /* not JSON — fall through */
  }
  return raw
}

/**
 * PR57 — 운영 지표 line chart. 외부 라이브러리 없이 inline SVG polyline 으로 3선:
 *   - 신고 (coral)
 *   - 자동 + 수동 숨김 합 (보라)
 *   - 이의 제기 제출 (회색)
 * 모든 시리즈를 정규화해 같은 0..1 범위로 그린 뒤 위/아래 padding 으로 표시.
 */
function ModerationStatsChart({
  points,
}: {
  points: AdminModerationStats['series']
}) {
  if (points.length === 0) {
    return <p className="muted">기간 내 데이터가 없어요.</p>
  }
  const width = 520
  const height = 120
  const padX = 8
  const padY = 8
  const innerW = width - padX * 2
  const innerH = height - padY * 2

  const reportSeries = points.map((p) => p.reportCount)
  const hideSeries = points.map((p) => p.autoHideCount + p.manualHideCount)
  const appealSeries = points.map((p) => p.appealSubmittedCount)
  const max = Math.max(1, ...reportSeries, ...hideSeries, ...appealSeries)

  const toPath = (series: number[]): string =>
    series
      .map((v, i) => {
        const x = padX + (points.length === 1 ? innerW / 2 : (innerW * i) / (points.length - 1))
        const y = padY + innerH - (v / max) * innerH
        return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
      })
      .join(' ')

  return (
    <div className="ct-admin-stats-chart" role="img" aria-label="최근 30일 운영 지표 추세">
      <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} preserveAspectRatio="none">
        {/* 축 line (subtle) */}
        <line x1={padX} y1={height - padY} x2={width - padX} y2={height - padY} stroke="#E5E7EB" strokeWidth="1" />
        <path d={toPath(reportSeries)} fill="none" stroke="#FA5252" strokeWidth="2" />
        <path d={toPath(hideSeries)} fill="none" stroke="#7C3AED" strokeWidth="2" />
        <path d={toPath(appealSeries)} fill="none" stroke="#9CA3AF" strokeWidth="2" />
      </svg>
      <div className="badge-row" style={{ gap: '12px', marginTop: '6px' }}>
        <span className="muted" style={{ color: '#FA5252' }}>신고</span>
        <span className="muted" style={{ color: '#7C3AED' }}>숨김</span>
        <span className="muted" style={{ color: '#9CA3AF' }}>이의 제기</span>
        <span className="muted">최대 {max}</span>
      </div>
    </div>
  )
}

export function AdminPage() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [applications, setApplications] = useState<CreatorApplication[]>([])
  const [channels, setChannels] = useState<Channel[]>([])
  const [reports, setReports] = useState<Report[]>([])
  const [reportFilter, setReportFilter] = useState<ReportFilter>('ALL')
  // PR52 — 자동 숨김 대상에 대한 이의 제기 큐.
  const [appeals, setAppeals] = useState<ReportAppeal[]>([])
  // PR55 — 통합 moderation queue. 신고/appeal/hidden 3 source 가 한 row 로 merge 된다.
  const [queue, setQueue] = useState<AdminModerationQueueItem[]>([])
  // PR57 — 운영 지표 (최근 30일 default).
  const [stats, setStats] = useState<AdminModerationStats | null>(null)
  // PR61 — 운영 감사 로그. PR62 에서 다축 필터 + 페이지네이션 + 본문 확장 추가.
  const [auditLogs, setAuditLogs] = useState<ModerationAuditLog[]>([])
  const [auditFilters, setAuditFilters] = useState<AuditFiltersForm>(EMPTY_AUDIT_FILTERS)
  const [auditPage, setAuditPage] = useState(0)
  const [auditTotalPages, setAuditTotalPages] = useState(0)
  const [auditIsLast, setAuditIsLast] = useState(true)
  const [auditLoading, setAuditLoading] = useState(false)
  const [auditError, setAuditError] = useState<string | null>(null)
  const [expandedAuditIds, setExpandedAuditIds] = useState<Set<number>>(new Set())
  // PR63 — 단건 상세 fetch 결과 캐시 (Map<id, log>) + 진행 중 id set + 에러 메시지.
  const [auditDetailCache, setAuditDetailCache] = useState<Record<number, ModerationAuditLog>>({})
  const [auditDetailLoading, setAuditDetailLoading] = useState<Set<number>>(new Set())
  const [auditDetailErrors, setAuditDetailErrors] = useState<Record<number, string>>({})
  const [auditExporting, setAuditExporting] = useState(false)
  // PR60 — 자동 hide 임계치. ADMIN 이 운영 지표를 본 뒤 직접 조정.
  const [thresholds, setThresholds] = useState<ModerationThreshold[]>([])
  // 입력 중간 상태 — 빈 문자열도 허용해 typing UX 유지. submit 시 number 변환 + 1..100 검증.
  const [thresholdDraft, setThresholdDraft] = useState<Record<ReportTargetType, string>>({
    REVIEW: '',
    COMMENT: '',
    POST: '',
    EVENT: '',
    CHANNEL: '',
  })
  const [thresholdSaving, setThresholdSaving] = useState(false)

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    Promise.all([
      getCreatorApplications({ size: 20 }),
      getAdminChannels({ size: 5 }),
      getReports({ size: 20 }),
      getAdminReportAppeals({ size: 20, status: 'PENDING' }),
      getModerationQueue({ size: 30 }),
      getModerationStats(),
      getModerationThresholds(),
      getModerationAuditLogs({ size: AUDIT_PAGE_SIZE }),
    ])
      .then(([applicationPage, channelPage, reportPage, appealPage, queuePage, statsRes, thresholdsRes, auditPageRes]) => {
        setApplications(applicationPage.content)
        setChannels(channelPage.content)
        setReports(reportPage.content)
        setAppeals(appealPage.content)
        setQueue(queuePage.content)
        setStats(statsRes)
        setThresholds(thresholdsRes)
        setAuditLogs(auditPageRes.content)
        setAuditTotalPages(auditPageRes.totalPages)
        setAuditIsLast(auditPageRes.isLast)
        setThresholdDraft({
          REVIEW: String(thresholdsRes.find((t) => t.targetType === 'REVIEW')?.threshold ?? ''),
          COMMENT: String(thresholdsRes.find((t) => t.targetType === 'COMMENT')?.threshold ?? ''),
          POST: String(thresholdsRes.find((t) => t.targetType === 'POST')?.threshold ?? ''),
          EVENT: String(thresholdsRes.find((t) => t.targetType === 'EVENT')?.threshold ?? ''),
          CHANNEL: String(thresholdsRes.find((t) => t.targetType === 'CHANNEL')?.threshold ?? ''),
        })
      })
      .catch((error) => {
        showToast({
          title: '관리자 데이터를 불러오지 못했어요',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
  }, [showToast, user?.role])

  // PR48 — 신고 필터 변경 시 해당 targetType 만 다시 받는다.
  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    const params: Parameters<typeof getReports>[0] =
      reportFilter === 'ALL' ? { size: 20 } : { size: 20, targetType: reportFilter }
    getReports(params)
      .then((page) => setReports(page.content))
      .catch(() => {
        /* non-fatal — 초기 로드 toast 가 이미 떴거나 일시 오류 */
      })
  }, [reportFilter, user?.role])

  // PR62 — audit 필터/페이지 변경 시 다시 받는다. 빈 문자열은 undefined 로 변환해
  // backend 가 해당 필드를 무시하게 한다. targetId/actorId 는 number 변환 실패 시 보내지 않음
  // (validation 토스트는 reset 버튼 외에는 굳이 띄우지 않음 — 사용자가 입력 중일 수 있음).
  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    let alive = true
    setAuditLoading(true)
    setAuditError(null)
    const params: Parameters<typeof getModerationAuditLogs>[0] = {
      page: auditPage,
      size: AUDIT_PAGE_SIZE,
      action: auditFilters.action || undefined,
      targetType: auditFilters.targetType || undefined,
      targetId: parsePositiveLong(auditFilters.targetId),
      actorId: parsePositiveLong(auditFilters.actorId),
      from: auditFilters.from.trim() || undefined,
      to: auditFilters.to.trim() || undefined,
    }
    getModerationAuditLogs(params)
      .then((page) => {
        if (!alive) return
        setAuditLogs(page.content)
        setAuditTotalPages(page.totalPages)
        setAuditIsLast(page.isLast)
      })
      .catch((error) => {
        if (!alive) return
        setAuditError(error instanceof Error ? error.message : '불러오지 못했어요.')
      })
      .finally(() => {
        if (alive) setAuditLoading(false)
      })
    return () => {
      alive = false
    }
  }, [
    user?.role,
    auditPage,
    auditFilters.action,
    auditFilters.targetType,
    auditFilters.targetId,
    auditFilters.actorId,
    auditFilters.from,
    auditFilters.to,
  ])

  async function handleApproveAppeal(id: number) {
    try {
      const updated = await approveReportAppeal(id)
      setAppeals((items) => items.filter((item) => item.id !== id))
      showToast({ title: '숨김을 해제했어요', tone: 'success' })
      void updated
    } catch (error) {
      showToast({
        title: '이의 제기 승인에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  async function handleRejectAppeal(id: number) {
    const rejectReason = window.prompt('거절 사유를 입력해주세요 (선택)')
    if (rejectReason === null) return // 취소
    try {
      await rejectReportAppeal(id, { rejectReason: rejectReason || null })
      setAppeals((items) => items.filter((item) => item.id !== id))
      showToast({ title: '이의 제기를 거절했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '이의 제기 거절에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  // PR58 — 위험 채널 카드의 ban 액션. 성공 시 stats 만 다시 받아 risky list 갱신.
  async function handleBanChannel(channelId: number, channelName: string) {
    const reason = window.prompt(`"${channelName}" 채널 제재 사유를 입력해주세요 (필수, 최대 255자)`)
    if (reason === null) return
    if (reason.trim().length === 0) {
      showToast({ title: '제재 사유를 입력해주세요', tone: 'warning' })
      return
    }
    if (!window.confirm(
      `"${channelName}" 채널과 소속 이벤트/공지/후기를 모두 숨김 처리할까요? 관련 이의 제기는 별도 처리해주세요.`,
    )) return
    try {
      const result = await banChannelForModeration(channelId, reason.trim())
      const cascadeMsg = [
        result.cascadedEventCount > 0 ? `이벤트 ${result.cascadedEventCount}건` : null,
        result.cascadedPostCount > 0 ? `공지 ${result.cascadedPostCount}건` : null,
        result.cascadedReviewCount > 0 ? `후기 ${result.cascadedReviewCount}건` : null,
      ].filter((s): s is string => s !== null).join(' / ') || '없음'
      showToast({
        title: '채널을 제재하고 관련 콘텐츠를 숨겼어요',
        message: `cascade: ${cascadeMsg}`,
        tone: 'success',
      })
      // stats 다시 받아서 risky 목록에서 갱신.
      const statsRes = await getModerationStats()
      setStats(statsRes)
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 409 ? '이미 제재된 채널이에요' : status === 404 ? '채널을 찾을 수 없어요' : '채널 제재에 실패했어요'
      showToast({ title, tone: 'danger' })
    }
  }

  async function handleUnbanChannel(channelId: number, channelName: string) {
    if (!window.confirm(`"${channelName}" 채널 제재를 해제할까요? 소속 콘텐츠는 자동 복구되지 않습니다.`)) return
    try {
      await unbanChannelForModeration(channelId)
      showToast({ title: '채널 제재를 해제했어요', tone: 'success' })
      const statsRes = await getModerationStats()
      setStats(statsRes)
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 400 || status === 409 ? '제재되지 않은 채널이에요' : status === 404 ? '채널을 찾을 수 없어요' : '제재 해제에 실패했어요'
      showToast({ title, tone: 'danger' })
    }
  }

  // PR60 — 자동 hide 임계치 부분 갱신. 빈 칸이거나 현재 값과 같으면 해당 필드는 보내지 않는다.
  // 1..100 범위 클라이언트 검증 — backend 도 @Valid 로 막지만 UX 토스트를 친화적으로.
  async function handleSaveThresholds() {
    const current: Record<ReportTargetType, number | undefined> = {
      REVIEW: thresholds.find((t) => t.targetType === 'REVIEW')?.threshold,
      COMMENT: thresholds.find((t) => t.targetType === 'COMMENT')?.threshold,
      POST: thresholds.find((t) => t.targetType === 'POST')?.threshold,
      EVENT: thresholds.find((t) => t.targetType === 'EVENT')?.threshold,
      CHANNEL: thresholds.find((t) => t.targetType === 'CHANNEL')?.threshold,
    }
    const request: UpdateModerationThresholdsRequest = {}
    const keys: Array<{ key: keyof UpdateModerationThresholdsRequest; type: ReportTargetType }> = [
      { key: 'review', type: 'REVIEW' },
      { key: 'comment', type: 'COMMENT' },
      { key: 'post', type: 'POST' },
      { key: 'event', type: 'EVENT' },
      { key: 'channel', type: 'CHANNEL' },
    ]
    for (const { key, type } of keys) {
      const raw = thresholdDraft[type].trim()
      if (raw === '') continue
      const num = Number(raw)
      if (!Number.isInteger(num) || num < 1 || num > 100) {
        showToast({
          title: '임계치는 1~100 사이의 정수여야 해요',
          message: `${TARGET_TYPE_LABEL[type]} 값을 확인해주세요.`,
          tone: 'warning',
        })
        return
      }
      if (num !== current[type]) request[key] = num
    }
    if (Object.keys(request).length === 0) {
      showToast({ title: '변경된 임계치가 없어요', tone: 'info' })
      return
    }
    setThresholdSaving(true)
    try {
      const updated = await updateModerationThresholds(request)
      setThresholds(updated)
      setThresholdDraft({
        REVIEW: String(updated.find((t) => t.targetType === 'REVIEW')?.threshold ?? ''),
        COMMENT: String(updated.find((t) => t.targetType === 'COMMENT')?.threshold ?? ''),
        POST: String(updated.find((t) => t.targetType === 'POST')?.threshold ?? ''),
        EVENT: String(updated.find((t) => t.targetType === 'EVENT')?.threshold ?? ''),
        CHANNEL: String(updated.find((t) => t.targetType === 'CHANNEL')?.threshold ?? ''),
      })
      showToast({ title: '임계치를 갱신했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '임계치 저장에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setThresholdSaving(false)
    }
  }

  // PR63 — 감사 로그 row 별 상세 토글. 이미 펼쳐져 있으면 접고, 닫혀 있으면 detail API 를 1회만
  // fetch 해 cache 에 저장. 캐시가 있으면 즉시 펼친다. detail 실패는 row-level 에러로 표시하고
  // expanded 상태는 유지해 사용자가 사유를 볼 수 있게 한다.
  async function handleAuditExpand(id: number) {
    if (expandedAuditIds.has(id)) {
      setExpandedAuditIds((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
      return
    }
    setExpandedAuditIds((prev) => {
      const next = new Set(prev)
      next.add(id)
      return next
    })
    if (auditDetailCache[id] || auditDetailLoading.has(id)) return
    setAuditDetailLoading((prev) => {
      const next = new Set(prev)
      next.add(id)
      return next
    })
    try {
      const detail = await getModerationAuditLog(id)
      setAuditDetailCache((prev) => ({ ...prev, [id]: detail }))
      setAuditDetailErrors((prev) => {
        if (!(id in prev)) return prev
        const next = { ...prev }
        delete next[id]
        return next
      })
    } catch (error) {
      setAuditDetailErrors((prev) => ({
        ...prev,
        [id]: error instanceof Error ? error.message : '상세를 불러오지 못했어요.',
      }))
    } finally {
      setAuditDetailLoading((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
    }
  }

  // PR63 — 현재 필터 그대로 CSV export. Blob 받아 a.download 으로 브라우저 다운로드 트리거.
  // 빈 응답이면 backend 가 헤더만 보내므로 파일은 거의 비어 있다 — 사용자가 의도한 동작.
  async function handleExportAuditLogs() {
    if (auditExporting) return
    setAuditExporting(true)
    try {
      const blob = await exportModerationAuditLogs({
        action: auditFilters.action || undefined,
        targetType: auditFilters.targetType || undefined,
        targetId: parsePositiveLong(auditFilters.targetId),
        actorId: parsePositiveLong(auditFilters.actorId),
        from: auditFilters.from.trim() || undefined,
        to: auditFilters.to.trim() || undefined,
      })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = 'moderation-audit-logs.csv'
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      showToast({
        title: '감사 로그 내보내기에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setAuditExporting(false)
    }
  }

  // PR55 — 통합 큐의 row 변경 시 queue 만 다시 받아 동기화. 신고/appeal 섹션은 자체 갱신.
  async function refreshQueue() {
    try {
      const queuePage = await getModerationQueue({ size: 30 })
      setQueue(queuePage.content)
    } catch {
      /* non-fatal */
    }
  }

  async function handleQueueHide(item: AdminModerationQueueItem) {
    const reason = window.prompt('숨김 사유를 입력해주세요 (필수, 최대 255자)')
    if (reason === null) return
    if (reason.trim().length === 0) {
      showToast({ title: '숨김 사유를 입력해주세요', tone: 'warning' })
      return
    }
    try {
      await hideModerationTarget(item.targetType, item.targetId, reason.trim())
      showToast({ title: '대상을 숨김 처리했어요', tone: 'success' })
      await refreshQueue()
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 409 ? '이미 숨김 처리된 대상이에요' : '숨김 처리에 실패했어요'
      showToast({ title, tone: 'danger' })
    }
  }

  async function handleQueueUnhide(item: AdminModerationQueueItem) {
    if (!window.confirm('숨김을 해제할까요? 관련 이의 제기는 별도로 처리해주세요.')) return
    try {
      await unhideModerationTarget(item.targetType, item.targetId)
      showToast({ title: '숨김을 해제했어요', tone: 'success' })
      await refreshQueue()
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 400 || status === 409
          ? '숨김 처리되지 않은 대상이에요'
          : '숨김 해제에 실패했어요'
      showToast({ title, tone: 'danger' })
    }
  }

  async function handleQueueResolveReport(item: AdminModerationQueueItem, action: 'RESOLVED' | 'DISMISSED') {
    if (item.latestReportId == null) return
    try {
      action === 'RESOLVED'
        ? await resolveReport(item.latestReportId)
        : await dismissReport(item.latestReportId)
      showToast({
        title: action === 'RESOLVED' ? '신고를 해결 처리했어요' : '신고를 기각했어요',
        tone: 'success',
      })
      await refreshQueue()
    } catch (error) {
      showToast({
        title: '신고 처리에 실패했어요',
        message: error instanceof Error ? error.message : undefined,
        tone: 'danger',
      })
    }
  }

  async function handleQueueApproveAppeal(item: AdminModerationQueueItem) {
    if (item.latestAppealId == null) return
    try {
      await approveReportAppeal(item.latestAppealId)
      showToast({ title: '숨김을 해제했어요', tone: 'success' })
      await refreshQueue()
    } catch (error) {
      showToast({
        title: '이의 제기 승인에 실패했어요',
        message: error instanceof Error ? error.message : undefined,
        tone: 'danger',
      })
    }
  }

  async function handleQueueRejectAppeal(item: AdminModerationQueueItem) {
    if (item.latestAppealId == null) return
    const rejectReason = window.prompt('거절 사유를 입력해주세요 (선택)')
    if (rejectReason === null) return
    try {
      await rejectReportAppeal(item.latestAppealId, { rejectReason: rejectReason || null })
      showToast({ title: '이의 제기를 거절했어요', tone: 'success' })
      await refreshQueue()
    } catch (error) {
      showToast({
        title: '이의 제기 거절에 실패했어요',
        message: error instanceof Error ? error.message : undefined,
        tone: 'danger',
      })
    }
  }

  // PR54 — 신고 카드에서 직접 hide/unhide. 신고 row 들의 targetHidden 을 즉시 갱신해
  // "자동 숨김" badge / 버튼 모양이 그 즉시 바뀌게 한다.
  async function handleManualHide(targetType: ReportTargetType, targetId: number) {
    const reason = window.prompt('숨김 사유를 입력해주세요 (필수, 최대 255자)')
    if (reason === null) return
    if (reason.trim().length === 0) {
      showToast({ title: '숨김 사유를 입력해주세요', tone: 'warning' })
      return
    }
    try {
      await hideModerationTarget(targetType, targetId, reason.trim())
      setReports((items) =>
        items.map((r) =>
          r.targetType === targetType && r.targetId === targetId
            ? { ...r, targetHidden: true, autoModerated: false }
            : r,
        ),
      )
      showToast({ title: '대상을 숨김 처리했어요', tone: 'success' })
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 409
          ? '이미 숨김 처리된 대상이에요'
          : status === 404
          ? '대상을 찾을 수 없어요'
          : '숨김 처리에 실패했어요'
      showToast({
        title,
        message: error instanceof Error ? error.message : undefined,
        tone: 'danger',
      })
    }
  }

  async function handleManualUnhide(targetType: ReportTargetType, targetId: number) {
    if (!window.confirm('숨김을 해제할까요? 관련 이의 제기는 별도로 처리해주세요.')) return
    try {
      await unhideModerationTarget(targetType, targetId)
      setReports((items) =>
        items.map((r) =>
          r.targetType === targetType && r.targetId === targetId
            ? { ...r, targetHidden: false, autoModerated: false }
            : r,
        ),
      )
      showToast({ title: '숨김을 해제했어요', tone: 'success' })
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: number }).status)
          : 0
      const title =
        status === 400 || status === 409
          ? '숨김 처리되지 않은 대상이에요'
          : status === 404
          ? '대상을 찾을 수 없어요'
          : '숨김 해제에 실패했어요'
      showToast({
        title,
        message: error instanceof Error ? error.message : undefined,
        tone: 'danger',
      })
    }
  }

  async function handleResolveReport(id: number, action: 'RESOLVED' | 'DISMISSED') {
    try {
      const updated = action === 'RESOLVED' ? await resolveReport(id) : await dismissReport(id)
      setReports((items) => items.map((item) => (item.id === id ? updated : item)))
      showToast({ title: action === 'RESOLVED' ? '신고를 해결 처리했어요' : '신고를 기각했어요', tone: 'success' })
    } catch (error) {
      showToast({
        title: '신고 처리에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  async function handleReview(id: number, status: 'APPROVED' | 'REJECTED') {
    try {
      if (status === 'APPROVED') await approveCreatorApplication(id)
      else await rejectCreatorApplication(id)
      setApplications((items) => items.filter((item) => item.id !== id))
      showToast({
        title: status === 'APPROVED' ? '기획자 신청을 승인했어요' : '기획자 신청을 거절했어요',
        tone: 'success',
      })
    } catch (error) {
      showToast({
        title: '신청 처리에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    }
  }

  if (user?.role !== 'ADMIN') {
    return (
      <main className="page empty-state">
        <h1>관리자 전용 화면이에요</h1>
        <p>CONTENIDO 관리자에게만 보이는 화면입니다.</p>
      </main>
    )
  }

  return (
    <main className="page">
      <section className="page-header">
        <div>
          <p className="eyebrow">Admin</p>
          <h1>CONTENIDO 운영 콘솔</h1>
        </div>
      </section>
      {/* PR57 — 운영 지표. 최근 30일 시계열 + totals + 위험 채널 Top 5.
          차트는 외부 라이브러리 없이 inline SVG polyline 으로 그린다. */}
      {stats ? (
        <section className="section">
          <div className="section-heading">
            <h2>운영 지표</h2>
            <span className="muted">최근 30일</span>
          </div>
          {/* totals — 6 칸 카드 그리드. */}
          <div
            className="badge-row"
            style={{ gap: '12px', flexWrap: 'wrap', marginBottom: '12px' }}
          >
            <span className="muted">총 신고 {stats.totals.reportCount}건</span>
            <span className="muted">자동 숨김 {stats.totals.autoHideCount}건</span>
            <span className="muted">수동 숨김 {stats.totals.manualHideCount}건</span>
            <span className="muted">이의 제기 {stats.totals.appealSubmittedCount}건</span>
            <span className="muted">승인 {stats.totals.appealApprovedCount}건</span>
            <span className="muted">거절 {stats.totals.appealRejectedCount}건</span>
          </div>
          {/* 시계열 라인 차트 — reports + hidden + appeals 3선. SVG polyline. */}
          <ModerationStatsChart points={stats.series} />
          {/* 위험 채널 Top N. */}
          <div className="section-heading" style={{ marginTop: '16px' }}>
            <h3>위험 채널</h3>
            <span className="muted">{stats.riskyChannels.length}건</span>
          </div>
          {stats.riskyChannels.length === 0 ? (
            <p className="muted">위험 신호가 있는 채널이 없어요.</p>
          ) : (
            <ul className="stack">
              {stats.riskyChannels.map((ch) => (
                <article className="card admin-card" key={ch.channelId}>
                  <div>
                    <div className="badge-row">
                      <Badge tone={ch.riskLevel === 'RISK' ? 'danger' : 'warning'}>
                        {ch.riskLevel === 'RISK' ? '위험' : '관찰'}
                      </Badge>
                      <span className="muted">채널 #{ch.channelId}</span>
                    </div>
                    <strong>{ch.channelName}</strong>
                    <div className="meta-row">
                      <span>owner: {ch.ownerNickname}</span>
                      <span>숨김 콘텐츠 {ch.hiddenCount}건</span>
                    </div>
                  </div>
                  {/* PR58 — 채널 제재 / 해제. RISK 등급에 더 강조하지만 WATCH 도 노출 */}
                  <div className="admin-actions">
                    <button
                      type="button"
                      className="button button-secondary"
                      onClick={() => handleUnbanChannel(ch.channelId, ch.channelName)}
                    >
                      제재 해제
                    </button>
                    <button
                      type="button"
                      className="button button-primary"
                      onClick={() => handleBanChannel(ch.channelId, ch.channelName)}
                    >
                      채널 제재
                    </button>
                  </div>
                </article>
              ))}
            </ul>
          )}
        </section>
      ) : null}
      {/* PR60 — 자동 hide 임계치 조정. 5개 targetType 의 PENDING 신고 누적 임계치를 운영 중 변경.
          변경 즉시 다음 신고부터 적용. 기존 hidden 상태는 retroactive 재계산되지 않는다. */}
      {thresholds.length > 0 ? (
        <section className="section">
          <div className="section-heading">
            <h2>자동 숨김 임계치</h2>
            <span className="muted">1~100 / 변경 즉시 적용</span>
          </div>
          <p className="muted" style={{ marginBottom: '12px' }}>
            PENDING 신고가 임계치에 도달하면 해당 콘텐츠를 자동으로 숨김 처리해요. 기존에 이미
            숨김 처리된 항목은 임계치를 낮춰도 다시 재계산되지 않아요.
          </p>
          <div className="ct-admin-thresholds">
            {(['REVIEW', 'COMMENT', 'POST', 'EVENT', 'CHANNEL'] as ReportTargetType[]).map((type) => (
              <label key={type} className="ct-admin-threshold-field">
                <span>{TARGET_TYPE_LABEL[type]}</span>
                <input
                  type="number"
                  min={1}
                  max={100}
                  step={1}
                  inputMode="numeric"
                  value={thresholdDraft[type]}
                  onChange={(e) =>
                    setThresholdDraft((prev) => ({ ...prev, [type]: e.target.value }))
                  }
                  disabled={thresholdSaving}
                />
              </label>
            ))}
          </div>
          <div className="admin-actions" style={{ marginTop: '12px' }}>
            <button
              type="button"
              className="button button-primary"
              onClick={handleSaveThresholds}
              disabled={thresholdSaving}
            >
              {thresholdSaving ? '저장 중…' : '임계치 저장'}
            </button>
          </div>
        </section>
      ) : null}
      {/* PR61 신설, PR62 에서 다축 필터 + 페이지네이션 + before/after 확장, PR63 에서 CSV export + 단건 상세 fetch. */}
      <section className="section">
        <div className="section-heading">
          <h2>운영 감사 로그</h2>
          <span className="muted">
            {auditTotalPages > 0 ? `${auditPage + 1} / ${auditTotalPages} 페이지` : '결과 없음'}
          </span>
        </div>
        <div className="ct-audit-filters">
          <label className="ct-audit-filter">
            <span>액션</span>
            <select
              value={auditFilters.action}
              onChange={(e) => {
                const next = e.target.value as ModerationAuditAction | ''
                setAuditFilters((prev) => ({ ...prev, action: next }))
                setAuditPage(0)
              }}
            >
              <option value="">전체</option>
              {AUDIT_ACTION_OPTIONS.map((a) => (
                <option key={a} value={a}>{AUDIT_ACTION_LABEL[a]}</option>
              ))}
            </select>
          </label>
          <label className="ct-audit-filter">
            <span>대상 종류</span>
            <select
              value={auditFilters.targetType}
              onChange={(e) => {
                const next = e.target.value as ReportTargetType | ''
                setAuditFilters((prev) => ({ ...prev, targetType: next }))
                setAuditPage(0)
              }}
            >
              <option value="">전체</option>
              {AUDIT_TARGET_TYPE_OPTIONS.map((t) => (
                <option key={t} value={t}>{TARGET_TYPE_LABEL[t]}</option>
              ))}
            </select>
          </label>
          <label className="ct-audit-filter">
            <span>대상 ID</span>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              placeholder="예: 50"
              value={auditFilters.targetId}
              onChange={(e) => {
                const v = e.target.value
                setAuditFilters((prev) => ({ ...prev, targetId: v }))
                setAuditPage(0)
              }}
            />
          </label>
          <label className="ct-audit-filter">
            <span>운영자 ID</span>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              placeholder="예: 7"
              value={auditFilters.actorId}
              onChange={(e) => {
                const v = e.target.value
                setAuditFilters((prev) => ({ ...prev, actorId: v }))
                setAuditPage(0)
              }}
            />
          </label>
          <label className="ct-audit-filter">
            <span>From</span>
            <input
              type="date"
              value={auditFilters.from}
              onChange={(e) => {
                const v = e.target.value
                setAuditFilters((prev) => ({ ...prev, from: v }))
                setAuditPage(0)
              }}
            />
          </label>
          <label className="ct-audit-filter">
            <span>To</span>
            <input
              type="date"
              value={auditFilters.to}
              onChange={(e) => {
                const v = e.target.value
                setAuditFilters((prev) => ({ ...prev, to: v }))
                setAuditPage(0)
              }}
            />
          </label>
        </div>
        <div className="admin-actions" style={{ marginBottom: '12px' }}>
          <button
            type="button"
            className="button button-secondary"
            onClick={() => {
              setAuditFilters(EMPTY_AUDIT_FILTERS)
              setAuditPage(0)
            }}
            disabled={auditLoading}
          >
            필터 초기화
          </button>
          {/* PR63 — 현재 필터 그대로 CSV (최대 1000건) 다운로드. 결과가 비어 있어도 헤더만 들어간
              CSV 가 다운로드되므로 disabled 는 진행 중일 때만. */}
          <button
            type="button"
            className="button button-secondary"
            onClick={handleExportAuditLogs}
            disabled={auditExporting || auditLoading}
            title="현재 필터 조건의 감사 로그 (최대 1000건) 를 CSV 로 내보냅니다."
          >
            {auditExporting ? '내보내는 중…' : 'CSV 내보내기'}
          </button>
        </div>
        {auditError ? (
          <p className="muted" role="alert">불러오기 실패: {auditError}</p>
        ) : null}
        {auditLoading ? (
          <p className="muted">불러오는 중…</p>
        ) : auditLogs.length === 0 ? (
          <p className="muted">조건에 맞는 감사 로그가 없어요.</p>
        ) : (
          <ul className="stack">
            {auditLogs.map((log) => {
              const expanded = expandedAuditIds.has(log.id)
              const hasDetail = !!(log.beforeValue || log.afterValue)
              const detail = auditDetailCache[log.id]
              const detailLoading = auditDetailLoading.has(log.id)
              const detailError = auditDetailErrors[log.id]
              // 펼친 상태에서는 detail (fresh fetch) 우선, 캐시 미스면 list row 데이터로 fallback.
              const display = detail ?? log
              return (
                <article className="card admin-card" key={log.id}>
                  <div>
                    <div className="badge-row">
                      <Badge tone={AUDIT_ACTION_TONE[log.action]}>{AUDIT_ACTION_LABEL[log.action]}</Badge>
                      {log.targetType ? (
                        <Badge tone="neutral">{TARGET_TYPE_LABEL[log.targetType]}</Badge>
                      ) : null}
                      {log.targetId != null ? (
                        <span className="muted">#{log.targetId}</span>
                      ) : null}
                    </div>
                    <strong>{log.actorNickname} <span className="muted">(#{log.actorId})</span></strong>
                    {log.reason ? <p className="muted">사유: {log.reason}</p> : null}
                    {hasDetail && !expanded ? (
                      <>
                        {log.beforeValue ? (
                          <p className="muted ct-audit-snippet">before: {log.beforeValue}</p>
                        ) : null}
                        {log.afterValue ? (
                          <p className="muted ct-audit-snippet">after: {log.afterValue}</p>
                        ) : null}
                      </>
                    ) : null}
                    {hasDetail && expanded ? (
                      <div className="ct-audit-detail">
                        {detailLoading && !detail ? (
                          <span className="muted">상세 불러오는 중…</span>
                        ) : null}
                        {detailError ? (
                          <span className="muted" role="alert">상세 조회 실패: {detailError}</span>
                        ) : null}
                        {display.beforeValue ? (
                          <>
                            <span className="muted">before</span>
                            <pre className="ct-audit-detail-pre">{prettyAuditValue(display.beforeValue)}</pre>
                          </>
                        ) : null}
                        {display.afterValue ? (
                          <>
                            <span className="muted">after</span>
                            <pre className="ct-audit-detail-pre">{prettyAuditValue(display.afterValue)}</pre>
                          </>
                        ) : null}
                      </div>
                    ) : null}
                    <span className="muted">{new Date(log.createdAt).toLocaleString()}</span>
                  </div>
                  {hasDetail ? (
                    <div className="admin-actions">
                      <button
                        type="button"
                        className="button button-secondary"
                        onClick={() => handleAuditExpand(log.id)}
                        disabled={detailLoading}
                      >
                        {expanded ? '접기' : detailLoading ? '불러오는 중…' : '상세'}
                      </button>
                    </div>
                  ) : null}
                </article>
              )
            })}
          </ul>
        )}
        {auditTotalPages > 1 ? (
          <div className="admin-actions" style={{ marginTop: '12px', justifyContent: 'space-between' }}>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => setAuditPage((p) => Math.max(0, p - 1))}
              disabled={auditPage === 0 || auditLoading}
            >
              이전
            </button>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => setAuditPage((p) => p + 1)}
              disabled={auditIsLast || auditLoading}
            >
              다음
            </button>
          </div>
        ) : null}
      </section>
      {/* PR55 — 통합 운영 큐. 신고/appeal/hidden 3 source 가 priority 순으로 합쳐진다.
          상세 신고/appeal 섹션은 아래에 그대로 유지되어 전체 목록 조회/필터링용으로 남는다. */}
      <section className="section">
        <div className="section-heading">
          <h2>운영 큐</h2>
          <span className="muted">{queue.length}건</span>
        </div>
        <div className="stack">
          {queue.length === 0 ? (
            <p className="muted">처리 대기 중인 항목이 없어요.</p>
          ) : (
            queue.map((item) => {
              const key = `${item.targetType}-${item.targetId}`
              const appealPending = item.latestAppealStatus === 'PENDING'
              return (
                <article className="card admin-card" key={key}>
                  <div>
                    <div className="badge-row">
                      <Badge tone={PRIORITY_TONE[item.priority]}>{PRIORITY_LABEL[item.priority]}</Badge>
                      <Badge tone="danger">{TARGET_TYPE_LABEL[item.targetType]}</Badge>
                      {item.hidden ? <Badge tone="warning">자동/수동 숨김</Badge> : null}
                      {appealPending ? <Badge tone="warning">검토 대기</Badge> : null}
                    </div>
                    <strong>{item.targetTitle}</strong>
                    <p className="muted">“{item.targetPreview}”</p>
                    {item.hidden && item.hiddenReason ? (
                      <p className="muted">숨김 사유: {item.hiddenReason}</p>
                    ) : null}
                    {item.latestReportReason ? (
                      <p>
                        <strong>최근 신고:</strong> {item.latestReportReason}
                      </p>
                    ) : null}
                    {item.latestAppealReason ? (
                      <p>
                        <strong>최근 이의 제기:</strong> {item.latestAppealReason}
                      </p>
                    ) : null}
                    <div className="meta-row">
                      <span>신고 누적 {item.pendingReportCount}건</span>
                      <span>#{item.targetId}</span>
                    </div>
                  </div>
                  <div className="admin-actions">
                    {item.hidden ? (
                      <button
                        className="button button-secondary"
                        onClick={() => handleQueueUnhide(item)}
                        type="button"
                      >
                        숨김 해제
                      </button>
                    ) : (
                      <button
                        className="button button-secondary"
                        onClick={() => handleQueueHide(item)}
                        type="button"
                      >
                        숨김
                      </button>
                    )}
                    {appealPending ? (
                      <>
                        <button
                          className="button button-secondary"
                          onClick={() => handleQueueRejectAppeal(item)}
                          type="button"
                        >
                          이의 제기 거절
                        </button>
                        <button
                          className="button button-primary"
                          onClick={() => handleQueueApproveAppeal(item)}
                          type="button"
                        >
                          이의 제기 승인
                        </button>
                      </>
                    ) : null}
                    {item.latestReportId != null && !appealPending ? (
                      <>
                        <button
                          className="button button-secondary"
                          onClick={() => handleQueueResolveReport(item, 'DISMISSED')}
                          type="button"
                        >
                          신고 기각
                        </button>
                        <button
                          className="button button-primary"
                          onClick={() => handleQueueResolveReport(item, 'RESOLVED')}
                          type="button"
                        >
                          신고 해결
                        </button>
                      </>
                    ) : null}
                  </div>
                </article>
              )
            })
          )}
        </div>
      </section>
      <section className="section">
        <div className="section-heading">
          <h2>기획자 신청</h2>
        </div>
        <div className="stack">
          {applications.length === 0 ? (
            <p className="muted">대기 중인 신청이 없어요.</p>
          ) : (
            applications.map((application) => (
              <article className="card admin-card" key={application.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="warning">{application.status}</Badge>
                  </div>
                  <p>{application.reason}</p>
                  {application.portfolioUrl ? (
                    <a href={application.portfolioUrl}>{application.portfolioUrl}</a>
                  ) : null}
                </div>
                <div className="admin-actions">
                  <button
                    className="button button-secondary"
                    onClick={() => handleReview(application.id, 'REJECTED')}
                    type="button"
                  >
                    거절
                  </button>
                  <button
                    className="button button-primary"
                    onClick={() => handleReview(application.id, 'APPROVED')}
                    type="button"
                  >
                    승인
                  </button>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
      <section className="section">
        <div className="section-heading">
          <h2>신고 목록</h2>
        </div>
        <div className="ct-chip-row" role="tablist" aria-label="신고 타입 필터">
          {REPORT_FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              role="tab"
              aria-selected={reportFilter === f.value}
              className={`chip ${reportFilter === f.value ? 'is-active' : ''}`}
              onClick={() => setReportFilter(f.value)}
            >
              {f.label}
            </button>
          ))}
        </div>
        <div className="stack">
          {reports.length === 0 ? (
            <p className="muted">처리 대기 중인 신고가 없어요.</p>
          ) : (
            reports.map((report) => (
              <article className="card admin-card" key={report.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="danger">{TARGET_TYPE_LABEL[report.targetType]}</Badge>
                    <Badge
                      tone={
                        report.status === 'RESOLVED'
                          ? 'success'
                          : report.status === 'DISMISSED'
                          ? 'neutral'
                          : 'warning'
                      }
                    >
                      {report.status === 'RESOLVED'
                        ? '해결됨'
                        : report.status === 'DISMISSED'
                        ? '기각됨'
                        : '대기 중'}
                    </Badge>
                    {/* PR48: REVIEW 신고일 때 별점 칩 */}
                    {report.targetType === 'REVIEW' && report.targetRating != null ? (
                      <span className="ct-rating-chip" aria-label={`대상 별점 ${report.targetRating}`}>
                        <span aria-hidden="true">★</span>
                        <strong>{report.targetRating}</strong>
                      </span>
                    ) : null}
                    {/* PR51: 자동 숨김 badge — autoModerated 면 강조, 단순 hide 면 neutral */}
                    {report.targetHidden ? (
                      <Badge tone={report.autoModerated ? 'warning' : 'neutral'}>
                        {report.autoModerated ? '자동 숨김' : '숨김'}
                      </Badge>
                    ) : null}
                  </div>
                  <p>
                    <strong>사유:</strong> {report.reason}
                  </p>
                  {report.targetPreview ? (
                    <p className="ct-report-target-preview muted">
                      대상: “{report.targetPreview}”
                    </p>
                  ) : (
                    <p className="muted">대상이 이미 삭제되었거나 비공개 처리됐어요.</p>
                  )}
                  <div className="meta-row">
                    <span>신고자: {report.reporterNickname}</span>
                    <span>{new Date(report.createdAt).toLocaleString()}</span>
                    <span>#{report.targetId}</span>
                  </div>
                </div>
                {report.status === 'PENDING' ? (
                  <div className="admin-actions">
                    {/* PR54 — 수동 hide/unhide. resolve/dismiss 와 별개 흐름. */}
                    {report.targetHidden ? (
                      <button
                        className="button button-secondary"
                        onClick={() => handleManualUnhide(report.targetType, report.targetId)}
                        type="button"
                      >
                        숨김 해제
                      </button>
                    ) : (
                      <button
                        className="button button-secondary"
                        onClick={() => handleManualHide(report.targetType, report.targetId)}
                        type="button"
                      >
                        숨김
                      </button>
                    )}
                    <button
                      className="button button-secondary"
                      onClick={() => handleResolveReport(report.id, 'DISMISSED')}
                      type="button"
                    >
                      기각
                    </button>
                    <button
                      className="button button-primary"
                      onClick={() => handleResolveReport(report.id, 'RESOLVED')}
                      type="button"
                    >
                      해결 처리
                    </button>
                  </div>
                ) : null}
              </article>
            ))
          )}
        </div>
      </section>
      {/* PR52 — 자동 숨김 대상에 대한 이의 제기 큐. PENDING 만 노출. */}
      <section className="section">
        <div className="section-heading">
          <h2>이의 제기 큐</h2>
        </div>
        <div className="stack">
          {appeals.length === 0 ? (
            <p className="muted">처리 대기 중인 이의 제기가 없어요.</p>
          ) : (
            appeals.map((appeal) => (
              <article className="card admin-card" key={appeal.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="danger">{TARGET_TYPE_LABEL[appeal.targetType]}</Badge>
                    <Badge tone="warning">이의 제기 대기</Badge>
                    {appeal.targetHidden === false ? (
                      <Badge tone="success">이미 해제됨</Badge>
                    ) : null}
                  </div>
                  <p>
                    <strong>사유:</strong> {appeal.reason}
                  </p>
                  {appeal.targetPreview ? (
                    <p className="ct-report-target-preview muted">
                      대상: “{appeal.targetPreview}”
                    </p>
                  ) : (
                    <p className="muted">대상이 이미 삭제되었거나 비공개 처리됐어요.</p>
                  )}
                  <div className="meta-row">
                    <span>신청자: {appeal.requesterNickname}</span>
                    <span>{new Date(appeal.createdAt).toLocaleString()}</span>
                    <span>#{appeal.targetId}</span>
                  </div>
                </div>
                <div className="admin-actions">
                  <button
                    className="button button-secondary"
                    onClick={() => handleRejectAppeal(appeal.id)}
                    type="button"
                  >
                    거절
                  </button>
                  <button
                    className="button button-primary"
                    onClick={() => handleApproveAppeal(appeal.id)}
                    type="button"
                  >
                    승인 (숨김 해제)
                  </button>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
      <section className="section">
        <div className="section-heading">
          <h2>Recent channels</h2>
        </div>
        <div className="stack">
          {channels.length === 0 ? (
            <p className="muted">No channels yet.</p>
          ) : (
            channels.map((channel) => (
              <article className="card admin-card" key={channel.id}>
                <div>
                  <div className="badge-row">
                    <Badge tone="primary">{channel.categoryDisplayName}</Badge>
                  </div>
                  <strong>{channel.name}</strong>
                  <p>{channel.description}</p>
                  <div className="meta-row">
                    <span>by {channel.ownerNickname}</span>
                    <span>{channel.subscriberCount.toLocaleString()} subscribers</span>
                  </div>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
    </main>
  )
}
