import { useEffect, useState } from 'react'
import {
  executeAuditLogArchive,
  getAuditLogArchivePreview,
  getAuditLogRetentionPolicy,
  getAuditLogRetentionScheduler,
  updateAuditLogRetentionScheduler,
} from '../../api/admin'
import { useAuth } from '../../hooks/useAuth'
import { useToast } from '../../hooks/useToast'
import type { AuditLogArchivePreview, AuditLogRetentionPolicy, AuditLogRetentionScheduler } from '../../types'

function parsePositiveLong(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (trimmed === '') return undefined
  const n = Number(trimmed)
  return Number.isInteger(n) && n > 0 ? n : undefined
}

export function AdminRetentionSection() {
  const { user } = useAuth()
  const { showToast } = useToast()
  const [retentionPolicy, setRetentionPolicy] = useState<AuditLogRetentionPolicy | null>(null)
  const [retentionDraft, setRetentionDraft] = useState('')
  const [retentionLoading, setRetentionLoading] = useState(false)
  const [retentionError, setRetentionError] = useState<string | null>(null)
  const [archivePreview, setArchivePreview] = useState<AuditLogArchivePreview | null>(null)
  const [archivePreviewLoading, setArchivePreviewLoading] = useState(false)
  const [archiveExecuting, setArchiveExecuting] = useState(false)
  const [archiveConfirmText, setArchiveConfirmText] = useState('')
  const [schedulerSettings, setSchedulerSettings] = useState<AuditLogRetentionScheduler | null>(null)
  const [schedulerSaving, setSchedulerSaving] = useState(false)
  const [schedulerCronDraft, setSchedulerCronDraft] = useState('')

  useEffect(() => {
    if (user?.role !== 'ADMIN') return
    Promise.all([getAuditLogRetentionPolicy(), getAuditLogArchivePreview(), getAuditLogRetentionScheduler()])
      .then(([retentionRes, archivePreviewRes, schedulerRes]) => {
        setRetentionPolicy(retentionRes)
        setRetentionDraft(String(retentionRes.retentionDays))
        setArchivePreview(archivePreviewRes)
        setSchedulerSettings(schedulerRes)
        setSchedulerCronDraft(schedulerRes.cron)
      })
      .catch((error) => {
        showToast({
          title: '보존 정책 데이터를 불러오지 못했어요',
          message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
          tone: 'danger',
        })
      })
  }, [showToast, user?.role])

  async function handleRetentionPreview() {
    const raw = retentionDraft.trim()
    let override: number | undefined
    if (raw !== '') {
      const n = Number(raw)
      if (!Number.isInteger(n) || n < 30 || n > 3650) {
        showToast({ title: '보존 기간은 30~3650일 사이여야 해요', message: '비워두면 기본값(365)으로 계산합니다.', tone: 'warning' })
        return
      }
      override = n
    }
    setRetentionLoading(true)
    setRetentionError(null)
    try {
      const res = await getAuditLogRetentionPolicy(override)
      setRetentionPolicy(res)
      setRetentionDraft(String(res.retentionDays))
    } catch (error) {
      setRetentionError(error instanceof Error ? error.message : '계산에 실패했어요.')
    } finally {
      setRetentionLoading(false)
    }
  }

  async function handleArchivePreview() {
    const override = parsePositiveLong(retentionDraft)
    setArchivePreviewLoading(true)
    setRetentionError(null)
    try {
      const res = await getAuditLogArchivePreview(override)
      setArchivePreview(res)
    } catch (error) {
      setRetentionError(error instanceof Error ? error.message : '미리보기 실패.')
    } finally {
      setArchivePreviewLoading(false)
    }
  }

  async function handleArchiveExecute() {
    if (!archivePreview || archivePreview.willArchiveCount <= 0) return
    if (archiveConfirmText !== 'ARCHIVE') {
      showToast({ title: '확인 텍스트를 입력해주세요', message: 'ARCHIVE 를 그대로 입력하면 실행됩니다.', tone: 'warning' })
      return
    }
    setArchiveExecuting(true)
    try {
      const result = await executeAuditLogArchive({
        retentionDays: archivePreview.retentionDays,
        expectedCutoffAt: archivePreview.cutoffAt,
        expectedCandidateCount: archivePreview.candidateCount,
        confirmText: archiveConfirmText,
      })
      showToast({
        title: `${result.archivedCount}건을 아카이브했어요`,
        message:
          result.remainingCandidateCount > 0
            ? `남은 후보 ${result.remainingCandidateCount}건. 미리보기를 다시 받아 진행하세요.`
            : '대상이 모두 아카이브됐어요.',
        tone: 'success',
      })
      setArchiveConfirmText('')
      const [nextPreview, nextRetention] = await Promise.all([
        getAuditLogArchivePreview(archivePreview.retentionDays),
        getAuditLogRetentionPolicy(archivePreview.retentionDays),
      ])
      setArchivePreview(nextPreview)
      setRetentionPolicy(nextRetention)
    } catch (error) {
      const status =
        error && typeof error === 'object' && 'status' in error ? Number((error as { status?: number }).status) : 0
      const title =
        status === 409 ? '미리보기 결과가 변경됐어요' : status === 400 ? '요청을 확인해주세요' : '아카이브 실행에 실패했어요'
      showToast({ title, message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.', tone: 'danger' })
      if (status === 409) await handleArchivePreview()
    } finally {
      setArchiveExecuting(false)
    }
  }

  async function handleSchedulerToggle(nextEnabled: boolean) {
    if (schedulerSaving) return
    setSchedulerSaving(true)
    try {
      const updated = await updateAuditLogRetentionScheduler({ enabled: nextEnabled })
      setSchedulerSettings(updated)
      setSchedulerCronDraft(updated.cron)
      showToast({
        title: nextEnabled ? '아카이브 스케줄러를 켰어요' : '아카이브 스케줄러를 껐어요',
        message: nextEnabled
          ? `매일 ${updated.cron} 시간대에 자동 archive 가 실행됩니다 (스케줄이 즉시 반영되었어요).`
          : '자동 archive 가 멈췄어요. 수동 archive 는 계속 가능합니다.',
        tone: nextEnabled ? 'success' : 'info',
      })
    } catch (error) {
      showToast({
        title: '스케줄러 설정에 실패했어요',
        message: error instanceof Error ? error.message : '잠시 후 다시 시도해주세요.',
        tone: 'danger',
      })
    } finally {
      setSchedulerSaving(false)
    }
  }

  async function handleSchedulerCronSave() {
    if (schedulerSaving) return
    const trimmed = schedulerCronDraft.trim()
    if (trimmed === '') {
      showToast({ title: 'cron 표현식을 입력해 주세요', message: '예: "0 30 3 * * *" (매일 새벽 3시 30분).', tone: 'warning' })
      return
    }
    if (schedulerSettings && trimmed === schedulerSettings.cron) {
      showToast({ title: '바뀐 값이 없어요', message: '현재 cron 과 동일합니다.', tone: 'info' })
      return
    }
    setSchedulerSaving(true)
    try {
      const updated = await updateAuditLogRetentionScheduler({ cron: trimmed })
      setSchedulerSettings(updated)
      setSchedulerCronDraft(updated.cron)
      showToast({
        title: 'cron 을 저장했어요',
        message: `이제부터 "${updated.cron}" 로 동작합니다 (스케줄이 즉시 반영되었어요).`,
        tone: 'success',
      })
    } catch (error) {
      showToast({
        title: 'cron 저장에 실패했어요',
        message: error instanceof Error ? error.message : 'cron 표현식 형식을 확인해 주세요 (예: 0 30 3 * * *).',
        tone: 'danger',
      })
    } finally {
      setSchedulerSaving(false)
    }
  }

  if (!retentionPolicy) return null

  return (
    <section className="section">
      <div className="section-heading">
        <h2>감사 로그 보존 정책</h2>
        <span className="muted">dry-run</span>
      </div>
      <p className="muted" style={{ marginBottom: '12px' }}>
        이번 화면은 삭제하지 않고 대상 개수만 계산합니다. 실제 정리/아카이브는 후속 PR.
      </p>
      <div className="ct-retention-grid">
        <div className="ct-retention-cell">
          <span className="muted">현재 적용 보존 기간</span>
          <strong>{retentionPolicy.retentionDays}일</strong>
        </div>
        <div className="ct-retention-cell">
          <span className="muted">cutoffAt (이전이 삭제 대상)</span>
          <strong>{new Date(retentionPolicy.cutoffAt).toLocaleString()}</strong>
        </div>
        <div className="ct-retention-cell">
          <span className="muted">삭제 대상 예상 개수</span>
          <strong>{retentionPolicy.dryRunDeletableCount.toLocaleString()}건</strong>
        </div>
        <div className="ct-retention-cell">
          <span className="muted">가장 오래된 로그</span>
          <strong>
            {retentionPolicy.oldestAuditLogCreatedAt
              ? new Date(retentionPolicy.oldestAuditLogCreatedAt).toLocaleString()
              : '없음'}
          </strong>
        </div>
        <div className="ct-retention-cell">
          <span className="muted">가장 최근 로그</span>
          <strong>
            {retentionPolicy.newestAuditLogCreatedAt
              ? new Date(retentionPolicy.newestAuditLogCreatedAt).toLocaleString()
              : '없음'}
          </strong>
        </div>
        <label className="ct-retention-cell">
          <span className="muted">
            보존 일수 (override, {retentionPolicy.minimumRetentionDays}~{retentionPolicy.maximumRetentionDays})
          </span>
          <input
            type="number"
            min={retentionPolicy.minimumRetentionDays}
            max={retentionPolicy.maximumRetentionDays}
            step={1}
            inputMode="numeric"
            placeholder={String(retentionPolicy.retentionDays)}
            value={retentionDraft}
            onChange={(e) => setRetentionDraft(e.target.value)}
            disabled={retentionLoading}
          />
        </label>
      </div>
      {retentionError ? <p className="muted" role="alert">계산 실패: {retentionError}</p> : null}
      <div className="admin-actions" style={{ marginTop: '12px' }}>
        <button type="button" className="button button-primary" onClick={handleRetentionPreview} disabled={retentionLoading}>
          {retentionLoading ? '계산 중…' : '미리 계산'}
        </button>
        <button type="button" className="button button-secondary" onClick={handleArchivePreview} disabled={archivePreviewLoading}>
          {archivePreviewLoading ? '아카이브 미리보기 중…' : '아카이브 미리보기'}
        </button>
      </div>

      {archivePreview ? (
        <div className="ct-archive-panel">
          <p className="muted" style={{ marginBottom: '8px' }}>
            삭제가 아니라 별도 아카이브 테이블로 이동합니다. 한 번에 최대{' '}
            {archivePreview.archiveLimit.toLocaleString()}건.
          </p>
          <div className="ct-archive-summary">
            <span>
              대상 후보 <strong>{archivePreview.candidateCount.toLocaleString()}</strong>건 / 이번에 옮길 수 있는 양{' '}
              <strong>{archivePreview.willArchiveCount.toLocaleString()}</strong>건
            </span>
            <span className="muted">cutoffAt: {new Date(archivePreview.cutoffAt).toLocaleString()}</span>
          </div>
          <label className="ct-audit-filter" style={{ marginTop: '10px' }}>
            <span>확인 텍스트 (정확히 <code>ARCHIVE</code> 입력)</span>
            <input
              type="text"
              inputMode="text"
              maxLength={16}
              autoCapitalize="characters"
              placeholder="ARCHIVE"
              value={archiveConfirmText}
              onChange={(e) => setArchiveConfirmText(e.target.value)}
              disabled={archiveExecuting || archivePreview.willArchiveCount <= 0}
            />
          </label>
          <div className="admin-actions" style={{ marginTop: '10px' }}>
            <button
              type="button"
              className="button button-primary"
              onClick={handleArchiveExecute}
              disabled={archiveExecuting || archivePreview.willArchiveCount <= 0 || archiveConfirmText !== 'ARCHIVE'}
              title="archive table 로 이동 (hard delete 아님)"
            >
              {archiveExecuting
                ? '아카이브 중…'
                : archivePreview.willArchiveCount <= 0
                ? '대상 없음'
                : `${archivePreview.willArchiveCount.toLocaleString()}건 아카이브 실행`}
            </button>
          </div>
        </div>
      ) : null}

      {schedulerSettings ? (
        <div className="ct-archive-panel" style={{ marginTop: '12px' }}>
          <p className="muted" style={{ marginBottom: '8px' }}>
            자동 아카이브 스케줄러 — 기본 OFF. 수동 아카이브와 같은 로직을 사용하며,
            <strong> hard delete 는 발생하지 않습니다.</strong> cron 변경은 저장 즉시 반영됩니다.
          </p>
          <div className="ct-archive-summary">
            <span>
              상태: <strong>{schedulerSettings.enabled ? '켜짐 (매일 자동 archive)' : '꺼짐 (수동만)'}</strong>
            </span>
            <span className="muted">
              runtime: <strong>{schedulerSettings.runtimeScheduled ? '예약 실행 중' : '예약 없음'}</strong>
            </span>
            <span className="muted">
              cron: <code>{schedulerSettings.cron}</code>
              {!schedulerSettings.enabled ? <span> — 스케줄러가 꺼져 있어 실행되지 않습니다.</span> : null}
            </span>
            {schedulerSettings.lastRescheduledAt ? (
              <span className="muted">마지막 재등록: {new Date(schedulerSettings.lastRescheduledAt).toLocaleString()}</span>
            ) : null}
            {schedulerSettings.updatedBy != null ? (
              <span className="muted">
                마지막 변경 ADMIN #{schedulerSettings.updatedBy} · {new Date(schedulerSettings.updatedAt).toLocaleString()}
              </span>
            ) : (
              <span className="muted">아직 토글한 ADMIN 이 없어 자동 archive 가 동작하지 않아요.</span>
            )}
          </div>
          <div className="admin-actions" style={{ marginTop: '10px', flexWrap: 'wrap', gap: '8px' }}>
            <button
              type="button"
              className={`button ${schedulerSettings.enabled ? 'button-secondary' : 'button-primary'}`}
              onClick={() => handleSchedulerToggle(!schedulerSettings.enabled)}
              disabled={schedulerSaving}
              title="자동 아카이브 스케줄러 ON/OFF"
            >
              {schedulerSaving ? '저장 중…' : schedulerSettings.enabled ? '스케줄러 끄기' : '스케줄러 켜기'}
            </button>
          </div>
          <div className="admin-actions" style={{ marginTop: '10px', flexWrap: 'wrap', gap: '8px', alignItems: 'center' }}>
            <label htmlFor="scheduler-cron-input" className="muted">
              cron <span className="muted">(Spring 6-field · 기본: 매일 03:30 KST)</span>
            </label>
            <input
              id="scheduler-cron-input"
              type="text"
              value={schedulerCronDraft}
              onChange={(e) => setSchedulerCronDraft(e.target.value)}
              placeholder="0 30 3 * * *"
              maxLength={64}
              disabled={schedulerSaving}
              style={{ minWidth: '200px', flex: '1 1 auto' }}
            />
            <button
              type="button"
              className="button button-secondary"
              onClick={handleSchedulerCronSave}
              disabled={schedulerSaving || schedulerCronDraft.trim() === '' || schedulerCronDraft.trim() === schedulerSettings.cron}
              title="저장하면 즉시 반영됩니다"
            >
              cron 저장
            </button>
            <span className="muted" style={{ fontSize: '0.8em' }}>저장하면 즉시 반영됩니다.</span>
          </div>
        </div>
      ) : null}
    </section>
  )
}
