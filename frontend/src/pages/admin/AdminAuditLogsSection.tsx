import { useEffect, useState } from 'react'
import {
  exportArchivedModerationAuditLogs,
  exportModerationAuditLogs,
  getArchivedModerationAuditLog,
  getArchivedModerationAuditLogs,
  getModerationAuditLog,
  getModerationAuditLogs,
} from '../../api/admin'
import { Badge } from '../../components/Badge'
import { useAuth } from '../../hooks/useAuth'
import { useToast } from '../../hooks/useToast'
import type {
  ArchivedModerationAuditLog,
  ModerationAuditAction,
  ModerationAuditLog,
  ReportTargetType,
} from '../../types'

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

const TARGET_TYPE_LABEL: Record<ReportTargetType, string> = {
  CHANNEL: '채널',
  POST: '게시글',
  EVENT: '이벤트',
  COMMENT: '댓글',
  REVIEW: '후기',
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

const AUDIT_TARGET_TYPE_OPTIONS: ReportTargetType[] = ['CHANNEL', 'EVENT', 'POST', 'COMMENT', 'REVIEW']

const AUDIT_PAGE_SIZE = 20

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

function parsePositiveLong(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (trimmed === '') return undefined
  const n = Number(trimmed)
  return Number.isInteger(n) && n > 0 ? n : undefined
}

function parseAuditMode(raw?: string | null): 'MANUAL' | 'SCHEDULED' | null {
  if (!raw) return null
  try {
    const m = (JSON.parse(raw) as Record<string, unknown>)?.mode
    if (m === 'MANUAL' || m === 'SCHEDULED') return m
  } catch { /* not JSON */ }
  return null
}

function prettyAuditValue(raw: string | null | undefined): string {
  if (raw == null || raw === '') return ''
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'object' && parsed !== null) {
      return JSON.stringify(parsed, null, 2)
    }
  } catch { /* not JSON */ }
  return raw
}

export function AdminAuditLogsSection() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [auditTab, setAuditTab] = useState<'active' | 'archived'>('active')

  const [auditLogs, setAuditLogs] = useState<ModerationAuditLog[]>([])
  const [auditFilters, setAuditFilters] = useState<AuditFiltersForm>(EMPTY_AUDIT_FILTERS)
  const [auditPage, setAuditPage] = useState(0)
  const [auditTotalPages, setAuditTotalPages] = useState(0)
  const [auditIsLast, setAuditIsLast] = useState(true)
  const [auditLoading, setAuditLoading] = useState(false)
  const [auditError, setAuditError] = useState<string | null>(null)
  const [expandedAuditIds, setExpandedAuditIds] = useState<Set<number>>(new Set())
  const [auditDetailCache, setAuditDetailCache] = useState<Record<number, ModerationAuditLog>>({})
  const [auditDetailLoading, setAuditDetailLoading] = useState<Set<number>>(new Set())
  const [auditDetailErrors, setAuditDetailErrors] = useState<Record<number, string>>({})
  const [auditExporting, setAuditExporting] = useState(false)

  const [archivedLogs, setArchivedLogs] = useState<ArchivedModerationAuditLog[]>([])
  const [archivedPage, setArchivedPage] = useState(0)
  const [archivedTotalPages, setArchivedTotalPages] = useState(0)
  const [archivedIsLast, setArchivedIsLast] = useState(true)
  const [archivedLoading, setArchivedLoading] = useState(false)
  const [archivedError, setArchivedError] = useState<string | null>(null)
  const [expandedArchivedIds, setExpandedArchivedIds] = useState<Set<number>>(new Set())
  const [archivedDetailCache, setArchivedDetailCache] = useState<Record<number, ArchivedModerationAuditLog>>({})
  const [archivedDetailLoading, setArchivedDetailLoading] = useState<Set<number>>(new Set())
  const [archivedDetailErrors, setArchivedDetailErrors] = useState<Record<number, string>>({})
  const [archivedExporting, setArchivedExporting] = useState(false)

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    if (auditTab !== 'active') return
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
      .finally(() => { if (alive) setAuditLoading(false) })
    return () => { alive = false }
  }, [
    user?.role, auditTab, auditPage,
    auditFilters.action, auditFilters.targetType, auditFilters.targetId,
    auditFilters.actorId, auditFilters.from, auditFilters.to,
  ])

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    if (auditTab !== 'archived') return
    let alive = true
    setArchivedLoading(true)
    setArchivedError(null)
    const params: Parameters<typeof getArchivedModerationAuditLogs>[0] = {
      page: archivedPage,
      size: AUDIT_PAGE_SIZE,
      action: auditFilters.action || undefined,
      targetType: auditFilters.targetType || undefined,
      targetId: parsePositiveLong(auditFilters.targetId),
      actorId: parsePositiveLong(auditFilters.actorId),
      from: auditFilters.from.trim() || undefined,
      to: auditFilters.to.trim() || undefined,
    }
    getArchivedModerationAuditLogs(params)
      .then((page) => {
        if (!alive) return
        setArchivedLogs(page.content)
        setArchivedTotalPages(page.totalPages)
        setArchivedIsLast(page.isLast)
      })
      .catch((error) => {
        if (!alive) return
        setArchivedError(error instanceof Error ? error.message : '불러오지 못했어요.')
      })
      .finally(() => { if (alive) setArchivedLoading(false) })
    return () => { alive = false }
  }, [
    user?.role, auditTab, archivedPage,
    auditFilters.action, auditFilters.targetType, auditFilters.targetId,
    auditFilters.actorId, auditFilters.from, auditFilters.to,
  ])

  async function handleAuditExpand(id: number) {
    if (expandedAuditIds.has(id)) {
      setExpandedAuditIds((prev) => { const next = new Set(prev); next.delete(id); return next })
      return
    }
    setExpandedAuditIds((prev) => { const next = new Set(prev); next.add(id); return next })
    if (auditDetailCache[id] || auditDetailLoading.has(id)) return
    setAuditDetailLoading((prev) => { const next = new Set(prev); next.add(id); return next })
    try {
      const detail = await getModerationAuditLog(id)
      setAuditDetailCache((prev) => ({ ...prev, [id]: detail }))
      setAuditDetailErrors((prev) => {
        if (!(id in prev)) return prev
        const next = { ...prev }; delete next[id]; return next
      })
    } catch (error) {
      setAuditDetailErrors((prev) => ({
        ...prev,
        [id]: error instanceof Error ? error.message : '상세를 불러오지 못했어요.',
      }))
    } finally {
      setAuditDetailLoading((prev) => { const next = new Set(prev); next.delete(id); return next })
    }
  }

  async function handleArchivedExpand(originalId: number) {
    if (expandedArchivedIds.has(originalId)) {
      setExpandedArchivedIds((prev) => { const next = new Set(prev); next.delete(originalId); return next })
      return
    }
    setExpandedArchivedIds((prev) => { const next = new Set(prev); next.add(originalId); return next })
    if (archivedDetailCache[originalId] || archivedDetailLoading.has(originalId)) return
    setArchivedDetailLoading((prev) => { const next = new Set(prev); next.add(originalId); return next })
    try {
      const detail = await getArchivedModerationAuditLog(originalId)
      setArchivedDetailCache((prev) => ({ ...prev, [originalId]: detail }))
      setArchivedDetailErrors((prev) => {
        if (!(originalId in prev)) return prev
        const next = { ...prev }; delete next[originalId]; return next
      })
    } catch (error) {
      setArchivedDetailErrors((prev) => ({
        ...prev,
        [originalId]: error instanceof Error ? error.message : '상세를 불러오지 못했어요.',
      }))
    } finally {
      setArchivedDetailLoading((prev) => { const next = new Set(prev); next.delete(originalId); return next })
    }
  }

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

  async function handleExportArchivedAuditLogs() {
    if (archivedExporting) return
    setArchivedExporting(true)
    try {
      const blob = await exportArchivedModerationAuditLogs({
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
      link.download = 'moderation-audit-logs-archive.csv'
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      showToast({
        title: '아카이브 내보내기에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setArchivedExporting(false)
    }
  }

  return (
    <section className="section">
      <div className="section-heading">
        <h2>운영 감사 로그</h2>
        <span className="muted">
          {auditTab === 'active'
            ? auditTotalPages > 0 ? `${auditPage + 1} / ${auditTotalPages} 페이지` : '결과 없음'
            : archivedTotalPages > 0
            ? `${archivedPage + 1} / ${archivedTotalPages} 페이지 · 읽기 전용`
            : '결과 없음 · 읽기 전용'}
        </span>
      </div>

      <div className="badge-row" style={{ gap: '6px', marginBottom: '12px' }}>
        <button type="button" className={`chip${auditTab === 'active' ? ' is-active' : ''}`} onClick={() => setAuditTab('active')}>
          현재 로그
        </button>
        <button type="button" className={`chip${auditTab === 'archived' ? ' is-active' : ''}`} onClick={() => setAuditTab('archived')}>
          아카이브
        </button>
      </div>

      <div className="ct-audit-filters">
        <label className="ct-audit-filter">
          <span>액션</span>
          <select
            value={auditFilters.action}
            onChange={(e) => {
              setAuditFilters((prev) => ({ ...prev, action: e.target.value as ModerationAuditAction | '' }))
              setAuditPage(0); setArchivedPage(0)
            }}
          >
            <option value="">전체</option>
            {AUDIT_ACTION_OPTIONS.map((a) => <option key={a} value={a}>{AUDIT_ACTION_LABEL[a]}</option>)}
          </select>
        </label>
        <label className="ct-audit-filter">
          <span>대상 종류</span>
          <select
            value={auditFilters.targetType}
            onChange={(e) => {
              setAuditFilters((prev) => ({ ...prev, targetType: e.target.value as ReportTargetType | '' }))
              setAuditPage(0); setArchivedPage(0)
            }}
          >
            <option value="">전체</option>
            {AUDIT_TARGET_TYPE_OPTIONS.map((t) => <option key={t} value={t}>{TARGET_TYPE_LABEL[t]}</option>)}
          </select>
        </label>
        <label className="ct-audit-filter">
          <span>대상 ID</span>
          <input
            type="number" inputMode="numeric" min={1} step={1} placeholder="예: 50"
            value={auditFilters.targetId}
            onChange={(e) => { setAuditFilters((prev) => ({ ...prev, targetId: e.target.value })); setAuditPage(0); setArchivedPage(0) }}
          />
        </label>
        <label className="ct-audit-filter">
          <span>운영자 ID</span>
          <input
            type="number" inputMode="numeric" min={1} step={1} placeholder="예: 7"
            value={auditFilters.actorId}
            onChange={(e) => { setAuditFilters((prev) => ({ ...prev, actorId: e.target.value })); setAuditPage(0); setArchivedPage(0) }}
          />
        </label>
        <label className="ct-audit-filter">
          <span>From</span>
          <input
            type="date" value={auditFilters.from}
            onChange={(e) => { setAuditFilters((prev) => ({ ...prev, from: e.target.value })); setAuditPage(0); setArchivedPage(0) }}
          />
        </label>
        <label className="ct-audit-filter">
          <span>To</span>
          <input
            type="date" value={auditFilters.to}
            onChange={(e) => { setAuditFilters((prev) => ({ ...prev, to: e.target.value })); setAuditPage(0); setArchivedPage(0) }}
          />
        </label>
      </div>

      <div className="admin-actions" style={{ marginBottom: '12px' }}>
        <button
          type="button" className="button button-secondary"
          onClick={() => { setAuditFilters(EMPTY_AUDIT_FILTERS); setAuditPage(0); setArchivedPage(0) }}
          disabled={auditTab === 'active' ? auditLoading : archivedLoading}
        >
          필터 초기화
        </button>
        {auditTab === 'active' ? (
          <button
            type="button" className="button button-secondary"
            onClick={handleExportAuditLogs}
            disabled={auditExporting || auditLoading}
            title="현재 필터 조건의 감사 로그 (최대 1000건) 를 CSV 로 내보냅니다."
          >
            {auditExporting ? '내보내는 중…' : 'CSV 내보내기'}
          </button>
        ) : (
          <button
            type="button" className="button button-secondary"
            onClick={handleExportArchivedAuditLogs}
            disabled={archivedExporting || archivedLoading}
            title="현재 필터 조건의 아카이브 (최대 1000건) 를 CSV 로 내보냅니다."
          >
            {archivedExporting ? '내보내는 중…' : '아카이브 CSV 내보내기'}
          </button>
        )}
      </div>

      {auditTab === 'active' && auditError ? <p className="muted" role="alert">불러오기 실패: {auditError}</p> : null}
      {auditTab === 'archived' && archivedError ? <p className="muted" role="alert">불러오기 실패: {archivedError}</p> : null}

      {auditTab === 'active' ? (
        auditLoading ? (
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
              const display = detail ?? log
              const isSystemActor = log.actorSystem || log.actorNickname === 'System'
              const auditMode = parseAuditMode(log.afterValue) ?? parseAuditMode(log.beforeValue)
              return (
                <article className="card admin-card" key={log.id}>
                  <div>
                    <div className="badge-row">
                      <Badge tone={AUDIT_ACTION_TONE[log.action]}>{AUDIT_ACTION_LABEL[log.action]}</Badge>
                      {log.targetType ? <Badge tone="neutral">{TARGET_TYPE_LABEL[log.targetType]}</Badge> : null}
                      {log.targetId != null ? <span className="muted">#{log.targetId}</span> : null}
                      {auditMode ? (
                        <Badge tone={auditMode === 'SCHEDULED' ? 'neutral' : 'warning'}>
                          {auditMode === 'SCHEDULED' ? '자동' : '수동'}
                        </Badge>
                      ) : null}
                    </div>
                    <strong>
                      {isSystemActor ? <Badge tone="neutral">System</Badge> : null}{' '}
                      {log.actorNickname} <span className="muted">(#{log.actorId})</span>
                    </strong>
                    {log.reason ? <p className="muted">사유: {log.reason}</p> : null}
                    {hasDetail && !expanded ? (
                      <>
                        {log.beforeValue ? <p className="muted ct-audit-snippet">before: {log.beforeValue}</p> : null}
                        {log.afterValue ? <p className="muted ct-audit-snippet">after: {log.afterValue}</p> : null}
                      </>
                    ) : null}
                    {hasDetail && expanded ? (
                      <div className="ct-audit-detail">
                        {detailLoading && !detail ? <span className="muted">상세 불러오는 중…</span> : null}
                        {detailError ? <span className="muted" role="alert">상세 조회 실패: {detailError}</span> : null}
                        {display.beforeValue ? (
                          <><span className="muted">before</span><pre className="ct-audit-detail-pre">{prettyAuditValue(display.beforeValue)}</pre></>
                        ) : null}
                        {display.afterValue ? (
                          <><span className="muted">after</span><pre className="ct-audit-detail-pre">{prettyAuditValue(display.afterValue)}</pre></>
                        ) : null}
                      </div>
                    ) : null}
                    <span className="muted">{new Date(log.createdAt).toLocaleString()}</span>
                  </div>
                  {hasDetail ? (
                    <div className="admin-actions">
                      <button type="button" className="button button-secondary" onClick={() => handleAuditExpand(log.id)} disabled={detailLoading}>
                        {expanded ? '접기' : detailLoading ? '불러오는 중…' : '상세'}
                      </button>
                    </div>
                  ) : null}
                </article>
              )
            })}
          </ul>
        )
      ) : archivedLoading ? (
        <p className="muted">불러오는 중…</p>
      ) : archivedLogs.length === 0 ? (
        <p className="muted">조건에 맞는 아카이브가 없어요.</p>
      ) : (
        <ul className="stack">
          {archivedLogs.map((log) => {
            const expanded = expandedArchivedIds.has(log.originalId)
            const hasDetail = !!(log.beforeValue || log.afterValue)
            const detail = archivedDetailCache[log.originalId]
            const detailLoading = archivedDetailLoading.has(log.originalId)
            const detailError = archivedDetailErrors[log.originalId]
            const display = detail ?? log
            return (
              <article className="card admin-card" key={log.originalId}>
                <div>
                  <div className="badge-row">
                    <Badge tone={AUDIT_ACTION_TONE[log.action]}>{AUDIT_ACTION_LABEL[log.action]}</Badge>
                    {log.targetType ? <Badge tone="neutral">{TARGET_TYPE_LABEL[log.targetType]}</Badge> : null}
                    {log.targetId != null ? <span className="muted">#{log.targetId}</span> : null}
                    <Badge tone="neutral">읽기 전용</Badge>
                  </div>
                  <strong>
                    {log.actorNicknameSnapshot} <span className="muted">(#{log.actorId})</span>
                  </strong>
                  {log.reason ? <p className="muted">사유: {log.reason}</p> : null}
                  {hasDetail && !expanded ? (
                    <>
                      {log.beforeValue ? <p className="muted ct-audit-snippet">before: {log.beforeValue}</p> : null}
                      {log.afterValue ? <p className="muted ct-audit-snippet">after: {log.afterValue}</p> : null}
                    </>
                  ) : null}
                  {hasDetail && expanded ? (
                    <div className="ct-audit-detail">
                      {detailLoading && !detail ? <span className="muted">상세 불러오는 중…</span> : null}
                      {detailError ? <span className="muted" role="alert">상세 조회 실패: {detailError}</span> : null}
                      {display.beforeValue ? (
                        <><span className="muted">before</span><pre className="ct-audit-detail-pre">{prettyAuditValue(display.beforeValue)}</pre></>
                      ) : null}
                      {display.afterValue ? (
                        <><span className="muted">after</span><pre className="ct-audit-detail-pre">{prettyAuditValue(display.afterValue)}</pre></>
                      ) : null}
                    </div>
                  ) : null}
                  <span className="muted">
                    원본: {new Date(log.originalCreatedAt).toLocaleString()} · 아카이브:{' '}
                    {new Date(log.archivedAt).toLocaleString()} (#{log.archivedBy})
                  </span>
                </div>
                {hasDetail ? (
                  <div className="admin-actions">
                    <button type="button" className="button button-secondary" onClick={() => handleArchivedExpand(log.originalId)} disabled={detailLoading}>
                      {expanded ? '접기' : detailLoading ? '불러오는 중…' : '상세'}
                    </button>
                  </div>
                ) : null}
              </article>
            )
          })}
        </ul>
      )}

      {auditTab === 'active' && auditTotalPages > 1 ? (
        <div className="admin-actions" style={{ marginTop: '12px', justifyContent: 'space-between' }}>
          <button type="button" className="button button-secondary" onClick={() => setAuditPage((p) => Math.max(0, p - 1))} disabled={auditPage === 0 || auditLoading}>이전</button>
          <button type="button" className="button button-secondary" onClick={() => setAuditPage((p) => p + 1)} disabled={auditIsLast || auditLoading}>다음</button>
        </div>
      ) : null}
      {auditTab === 'archived' && archivedTotalPages > 1 ? (
        <div className="admin-actions" style={{ marginTop: '12px', justifyContent: 'space-between' }}>
          <button type="button" className="button button-secondary" onClick={() => setArchivedPage((p) => Math.max(0, p - 1))} disabled={archivedPage === 0 || archivedLoading}>이전</button>
          <button type="button" className="button button-secondary" onClick={() => setArchivedPage((p) => p + 1)} disabled={archivedIsLast || archivedLoading}>다음</button>
        </div>
      ) : null}
    </section>
  )
}
