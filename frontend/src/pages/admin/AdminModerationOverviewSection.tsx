import { useEffect, useState } from 'react'
import {
  banChannelForModeration,
  dismissReport,
  getModerationActorStats,
  getModerationQueue,
  getModerationStats,
  getModerationThresholds,
  hideModerationTarget,
  resolveReport,
  unbanChannelForModeration,
  unhideModerationTarget,
  updateModerationThresholds,
} from '../../api/admin'
import { approveReportAppeal, rejectReportAppeal } from '../../api/reportAppeals'
import { Badge } from '../../components/Badge'
import { useAuth } from '../../hooks/useAuth'
import { useToast } from '../../hooks/useToast'
import type {
  AdminModerationActorStats,
  AdminModerationPriority,
  AdminModerationQueueItem,
  AdminModerationStats,
  ModerationThreshold,
  ReportTargetType,
  UpdateModerationThresholdsRequest,
} from '../../types'

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

const TARGET_TYPE_LABEL: Record<ReportTargetType, string> = {
  CHANNEL: '채널',
  POST: '게시글',
  EVENT: '이벤트',
  COMMENT: '댓글',
  REVIEW: '후기',
}

function ModerationStatsChart({ points }: { points: AdminModerationStats['series'] }) {
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

export function AdminModerationOverviewSection() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [stats, setStats] = useState<AdminModerationStats | null>(null)
  const [queue, setQueue] = useState<AdminModerationQueueItem[]>([])
  const [thresholds, setThresholds] = useState<ModerationThreshold[]>([])
  const [thresholdDraft, setThresholdDraft] = useState<Record<ReportTargetType, string>>({
    REVIEW: '',
    COMMENT: '',
    POST: '',
    EVENT: '',
    CHANNEL: '',
  })
  const [thresholdSaving, setThresholdSaving] = useState(false)
  const [initialLoading, setInitialLoading] = useState(true)
  // PR93 — 운영자 활동 요약 (기본 30일 / Top 10). overview 의 보조 카드라 초기 로드 실패는
  // overview 전체를 막지 않고 자체 fallback("불러올 수 없어요") 으로 다룬다.
  const [actorStats, setActorStats] = useState<AdminModerationActorStats | null>(null)
  const [actorStatsLoading, setActorStatsLoading] = useState(true)
  const [actorStatsError, setActorStatsError] = useState(false)

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    Promise.all([getModerationStats(), getModerationQueue({ size: 30 }), getModerationThresholds()])
      .then(([statsRes, queuePage, thresholdsRes]) => {
        setStats(statsRes)
        setQueue(queuePage.content)
        setThresholds(thresholdsRes)
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
          title: '운영 데이터를 불러오지 못했어요',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
      .finally(() => setInitialLoading(false))
  }, [showToast, user?.role])

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    setActorStatsLoading(true)
    setActorStatsError(false)
    getModerationActorStats()
      .then((res) => setActorStats(res))
      .catch(() => setActorStatsError(true))
      .finally(() => setActorStatsLoading(false))
  }, [user?.role])

  async function refreshQueue() {
    try {
      const queuePage = await getModerationQueue({ size: 30 })
      setQueue(queuePage.content)
    } catch { /* non-fatal */ }
  }

  async function handleBanChannel(channelId: number, channelName: string) {
    const reason = window.prompt(`"${channelName}" 채널 제재 사유를 입력해주세요 (필수, 최대 255자)`)
    if (reason === null) return
    if (reason.trim().length === 0) {
      showToast({ title: '제재 사유를 입력해주세요', tone: 'warning' })
      return
    }
    if (!window.confirm(`"${channelName}" 채널과 소속 이벤트/공지/후기를 모두 숨김 처리할까요?`)) return
    try {
      const result = await banChannelForModeration(channelId, reason.trim())
      const cascadeMsg = [
        result.cascadedEventCount > 0 ? `이벤트 ${result.cascadedEventCount}건` : null,
        result.cascadedPostCount > 0 ? `공지 ${result.cascadedPostCount}건` : null,
        result.cascadedReviewCount > 0 ? `후기 ${result.cascadedReviewCount}건` : null,
      ].filter((s): s is string => s !== null).join(' / ') || '없음'
      showToast({ title: '채널을 제재하고 관련 콘텐츠를 숨겼어요', message: `cascade: ${cascadeMsg}`, tone: 'success' })
      const statsRes = await getModerationStats()
      setStats(statsRes)
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error ? Number((error as { status?: number }).status) : 0
      const title =
        status === 409 ? '이미 제재된 채널이에요' : status === 404 ? '채널을 찾을 수 없어요' : '채널 제재에 실패했어요'
      showToast({ title, tone: 'danger' })
    }
  }

  async function handleUnbanChannel(channelId: number, channelName: string) {
    if (!window.confirm(`"${channelName}" 채널 제재를 해제할까요?`)) return
    try {
      await unbanChannelForModeration(channelId)
      showToast({ title: '채널 제재를 해제했어요', tone: 'success' })
      const statsRes = await getModerationStats()
      setStats(statsRes)
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error ? Number((error as { status?: number }).status) : 0
      const title =
        status === 400 || status === 409
          ? '제재되지 않은 채널이에요'
          : status === 404
          ? '채널을 찾을 수 없어요'
          : '제재 해제에 실패했어요'
      showToast({ title, tone: 'danger' })
    }
  }

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
        error && typeof error === 'object' && 'status' in error ? Number((error as { status?: number }).status) : 0
      showToast({ title: status === 409 ? '이미 숨김 처리된 대상이에요' : '숨김 처리에 실패했어요', tone: 'danger' })
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
        error && typeof error === 'object' && 'status' in error ? Number((error as { status?: number }).status) : 0
      showToast({
        title: status === 400 || status === 409 ? '숨김 처리되지 않은 대상이에요' : '숨김 해제에 실패했어요',
        tone: 'danger',
      })
    }
  }

  async function handleQueueResolveReport(item: AdminModerationQueueItem, action: 'RESOLVED' | 'DISMISSED') {
    if (item.latestReportId == null) return
    try {
      action === 'RESOLVED' ? await resolveReport(item.latestReportId) : await dismissReport(item.latestReportId)
      showToast({ title: action === 'RESOLVED' ? '신고를 해결 처리했어요' : '신고를 기각했어요', tone: 'success' })
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

  if (initialLoading) {
    return <p className="muted" style={{ padding: '16px' }} aria-live="polite">불러오는 중…</p>
  }

  return (
    <>
      {stats ? (
        <section className="section">
          <div className="section-heading">
            <h2>운영 지표</h2>
            <span className="muted">최근 30일</span>
          </div>
          <div className="badge-row" style={{ gap: '12px', flexWrap: 'wrap', marginBottom: '12px' }}>
            <span className="muted">총 신고 {stats.totals.reportCount}건</span>
            <span className="muted">자동 숨김 {stats.totals.autoHideCount}건</span>
            <span className="muted">수동 숨김 {stats.totals.manualHideCount}건</span>
            <span className="muted">이의 제기 {stats.totals.appealSubmittedCount}건</span>
            <span className="muted">승인 {stats.totals.appealApprovedCount}건</span>
            <span className="muted">거절 {stats.totals.appealRejectedCount}건</span>
          </div>
          <ModerationStatsChart points={stats.series} />
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
                  <div className="admin-actions">
                    <button type="button" className="button button-secondary" onClick={() => handleUnbanChannel(ch.channelId, ch.channelName)}>
                      제재 해제
                    </button>
                    <button type="button" className="button button-primary" onClick={() => handleBanChannel(ch.channelId, ch.channelName)}>
                      채널 제재
                    </button>
                  </div>
                </article>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      {thresholds.length > 0 ? (
        <section className="section">
          <div className="section-heading">
            <h2>자동 숨김 임계치</h2>
            <span className="muted">1~100 / 변경 즉시 적용</span>
          </div>
          <p className="muted" style={{ marginBottom: '12px' }}>
            PENDING 신고가 임계치에 도달하면 해당 콘텐츠를 자동으로 숨김 처리해요.
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
                  onChange={(e) => setThresholdDraft((prev) => ({ ...prev, [type]: e.target.value }))}
                  disabled={thresholdSaving}
                />
              </label>
            ))}
          </div>
          <div className="admin-actions" style={{ marginTop: '12px' }}>
            <button type="button" className="button button-primary" onClick={handleSaveThresholds} disabled={thresholdSaving}>
              {thresholdSaving ? '저장 중…' : '임계치 저장'}
            </button>
          </div>
        </section>
      ) : null}

      <section className="section">
        <div className="section-heading">
          <h2>운영자 활동</h2>
          <span className="muted">최근 30일 · Top 10</span>
        </div>
        {actorStatsLoading ? (
          <p className="muted">불러오는 중…</p>
        ) : actorStatsError ? (
          <p className="muted">운영자 활동 데이터를 불러오지 못했어요.</p>
        ) : !actorStats || actorStats.items.length === 0 ? (
          <p className="muted">지난 30일 동안 운영자 활동이 없어요.</p>
        ) : (
          <ul className="stack ct-admin-actor-stats">
            {actorStats.items.map((row) => (
              <article className="card admin-card" key={row.actorId}>
                <div>
                  <div className="badge-row">
                    {row.actorSystem ? <Badge tone="neutral">System</Badge> : null}
                    <strong>{row.actorNickname}</strong>
                    <span className="muted">#{row.actorId}</span>
                  </div>
                  <div className="meta-row" style={{ flexWrap: 'wrap', gap: '6px 12px' }}>
                    <span>총 {row.totalActionCount}건</span>
                    {row.hideCount > 0 ? <span>숨김 {row.hideCount}</span> : null}
                    {row.unhideCount > 0 ? <span>숨김 해제 {row.unhideCount}</span> : null}
                    {row.channelBanCount > 0 ? <span>채널 제재 {row.channelBanCount}</span> : null}
                    {row.channelUnbanCount > 0 ? <span>제재 해제 {row.channelUnbanCount}</span> : null}
                    {row.appealDecisionCount > 0 ? <span>이의 처리 {row.appealDecisionCount}</span> : null}
                    {row.reportDecisionCount > 0 ? <span>신고 처리 {row.reportDecisionCount}</span> : null}
                    {row.forcedRefundCount > 0 ? <span>강제 환불 {row.forcedRefundCount}</span> : null}
                    {row.thresholdUpdateCount > 0 ? <span>임계치 변경 {row.thresholdUpdateCount}</span> : null}
                    {row.archiveCount > 0 ? <span>아카이브 {row.archiveCount}</span> : null}
                  </div>
                </div>
              </article>
            ))}
          </ul>
        )}
      </section>

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
                    <p className="muted">"{item.targetPreview}"</p>
                    {item.hidden && item.hiddenReason ? <p className="muted">숨김 사유: {item.hiddenReason}</p> : null}
                    {item.latestReportReason ? <p><strong>최근 신고:</strong> {item.latestReportReason}</p> : null}
                    {item.latestAppealReason ? <p><strong>최근 이의 제기:</strong> {item.latestAppealReason}</p> : null}
                    <div className="meta-row">
                      <span>신고 누적 {item.pendingReportCount}건</span>
                      <span>#{item.targetId}</span>
                    </div>
                  </div>
                  <div className="admin-actions">
                    {item.hidden ? (
                      <button className="button button-secondary" onClick={() => handleQueueUnhide(item)} type="button">숨김 해제</button>
                    ) : (
                      <button className="button button-secondary" onClick={() => handleQueueHide(item)} type="button">숨김</button>
                    )}
                    {appealPending ? (
                      <>
                        <button className="button button-secondary" onClick={() => handleQueueRejectAppeal(item)} type="button">이의 제기 거절</button>
                        <button className="button button-primary" onClick={() => handleQueueApproveAppeal(item)} type="button">이의 제기 승인</button>
                      </>
                    ) : null}
                    {item.latestReportId != null && !appealPending ? (
                      <>
                        <button className="button button-secondary" onClick={() => handleQueueResolveReport(item, 'DISMISSED')} type="button">신고 기각</button>
                        <button className="button button-primary" onClick={() => handleQueueResolveReport(item, 'RESOLVED')} type="button">신고 해결</button>
                      </>
                    ) : null}
                  </div>
                </article>
              )
            })
          )}
        </div>
      </section>
    </>
  )
}
