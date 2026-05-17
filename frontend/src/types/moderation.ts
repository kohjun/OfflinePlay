/** Backend ReportTargetType enum 과 1:1. CONTENT/USER 는 더 이상 사용하지 않음 (PR48). */
export type ReportTargetType = 'CHANNEL' | 'POST' | 'EVENT' | 'COMMENT' | 'REVIEW'

export interface Report {
  id: number
  reporterNickname: string
  targetType: ReportTargetType
  targetId: number
  reason: string
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED'
  createdAt: string
  /** PR48 — Admin 응답에서만 채워짐. 대상이 삭제됐거나 createReport 응답이면 null. */
  targetPreview?: string | null
  /** PR48 — REVIEW 일 때만 채워짐. 빠른 별점 확인용. */
  targetRating?: number | null
  /** PR51 — 대상이 현재 자동 숨김(hide)된 상태인지. Admin 응답에서만 의미 있음. */
  targetHidden?: boolean
  /**
   * PR51 — 위 targetHidden 이 true 인 경우, 그 hide 가 신고 누적 자동 처리인지.
   * 현 PR 에선 자동 hide 만 존재하므로 targetHidden 과 사실상 동치이지만, 후속 PR(수동 hide/appeal
   * 복구) 에서 의미가 갈라진다.
   */
  autoModerated?: boolean
}

/** PR52 — 자동 숨김 대상에 대한 이의 제기 상태. */
export type ReportAppealStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** PR53 — Creator Studio "숨김 처리된 콘텐츠" row 의 appeal 상태 (NONE 포함). */
export type AppealStatusView = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'

/**
 * PR54 — ADMIN 수동 hide/unhide 응답. 동일한 정보가 신고 카드/appeal 큐 row 갱신에 사용된다.
 * latestAppealStatus 는 (targetType, targetId) 기준 최신 appeal 의 상태 — requester 무관.
 */
export interface AdminModerationTarget {
  targetType: ReportTargetType
  targetId: number
  targetTitle: string
  targetPreview: string
  hidden: boolean
  hiddenAt?: string | null
  hiddenReason?: string | null
  pendingReportCount: number
  latestAppealStatus?: ReportAppealStatus | null
}

/** PR55 — 통합 moderation queue 우선순위. */
export type AdminModerationPriority = 'HIGH' | 'MEDIUM' | 'LOW'

/** PR57 — Analytics 시계열 granularity. day 만 지원. */
export type AdminModerationGranularity = 'DAY'

/** PR57 — 위험 채널 등급. */
export type ChannelRiskLevel = 'WATCH' | 'RISK'

/** PR57 — 한 bucket(=하루) 의 운영 집계. */
export interface AdminModerationStatsPoint {
  date: string // ISO date (yyyy-MM-dd)
  reportCount: number
  autoHideCount: number
  manualHideCount: number
  appealSubmittedCount: number
  appealApprovedCount: number
  appealRejectedCount: number
}

/** PR57 — 위험 채널 row. hiddenCount >= 5 이면 RISK, 1~4 면 WATCH. */
export interface AdminRiskyChannel {
  channelId: number
  channelName: string
  ownerNickname: string
  hiddenCount: number
  pendingReportCount?: number | null
  riskLevel: ChannelRiskLevel
}

/** PR57 — 운영 지표 응답. series 시계열 + totals 합계 + riskyChannels Top 5. */
export interface AdminModerationStats {
  from: string
  to: string
  granularity: AdminModerationGranularity
  series: AdminModerationStatsPoint[]
  totals: AdminModerationStatsPoint
  riskyChannels: AdminRiskyChannel[]
}

/**
 * PR58 — 채널 제재/해제 응답.
 * cascade*Count 는 본 호출에서 "새로 숨김 처리한" row 수 — 이미 hidden 이던 row 는 제외.
 */
export interface AdminChannelBan {
  channelId: number
  channelName: string
  isActive: boolean
  hidden: boolean
  hiddenAt?: string | null
  hiddenReason?: string | null
  cascadedEventCount: number
  cascadedPostCount: number
  cascadedReviewCount: number
}

/**
 * PR60 — 자동 hide 임계치 단건. 5개 targetType 모두 GET 응답에 포함.
 */
export interface ModerationThreshold {
  targetType: ReportTargetType
  threshold: number
}

/**
 * PR61 — 운영 감사 로그. ADMIN 의 강한 운영 액션 (수동 hide/unhide, 채널 ban/unban,
 * appeal 처리, threshold 변경 등) 을 append-only 로 기록.
 */
export type ModerationAuditAction =
  | 'THRESHOLD_UPDATED'
  | 'TARGET_HIDDEN'
  | 'TARGET_UNHIDDEN'
  | 'CHANNEL_BANNED'
  | 'CHANNEL_UNBANNED'
  | 'APPEAL_APPROVED'
  | 'APPEAL_REJECTED'
  | 'REPORT_RESOLVED'
  | 'REPORT_DISMISSED'

export interface ModerationAuditLog {
  id: number
  actorId: number
  actorNickname: string
  /** PR71 — true iff actor is the system actor (scheduler / automated jobs). */
  actorSystem?: boolean
  action: ModerationAuditAction
  targetType: ReportTargetType | null
  targetId: number | null
  beforeValue: string | null
  afterValue: string | null
  reason: string | null
  createdAt: string
}

/**
 * PR64 — audit log retention dry-run 응답. 실제 삭제는 발생하지 않음.
 *  - retentionDays : 적용된 보존 기간 (운영자가 override 했거나 default 365)
 *  - minimum/maximumRetentionDays : 허용 범위 (30~3650, UI 표시용)
 *  - cutoffAt : now - retentionDays. 이 시각 이전 row 가 삭제 대상
 *  - dryRunDeletableCount : cutoffAt 이전 row 수 (count only)
 *  - oldest/newest : 현재 audit log 의 가장 오래된/최근 createdAt. row 0 이면 null
 */
export interface AuditLogRetentionPolicy {
  retentionDays: number
  minimumRetentionDays: number
  maximumRetentionDays: number
  cutoffAt: string
  dryRunDeletableCount: number
  oldestAuditLogCreatedAt: string | null
  newestAuditLogCreatedAt: string | null
}

/**
 * PR66 — archive 실행 전 미리보기. willArchiveCount = min(candidateCount, archiveLimit).
 */
export interface AuditLogArchivePreview {
  retentionDays: number
  cutoffAt: string
  candidateCount: number
  archiveLimit: number
  willArchiveCount: number
  oldestAuditLogCreatedAt: string | null
  newestAuditLogCreatedAt: string | null
}

/**
 * PR66 — archive 실행 요청. confirmText 는 정확히 'ARCHIVE'. preview 의 cutoffAt /
 * candidateCount 를 그대로 echo 해서 server 가 stale 가드.
 */
export interface ExecuteAuditLogArchiveRequest {
  retentionDays?: number
  expectedCutoffAt: string
  expectedCandidateCount: number
  confirmText: string
}

/** PR66 — archive 실행 결과. */
export interface AuditLogArchiveResult {
  archivedCount: number
  cutoffAt: string
  remainingCandidateCount: number
}

/**
 * PR67 — archive 테이블의 단건. active [ModerationAuditLog] 와 닮았지만:
 *  - `originalId` 가 PK 역할 (active 의 id).
 *  - `actorNicknameSnapshot` 은 archive 시점에 박힌 nickname.
 *  - `archivedAt` / `archivedBy` 추가.
 */
export interface ArchivedModerationAuditLog {
  originalId: number
  actorId: number
  actorNicknameSnapshot: string
  action: ModerationAuditAction
  targetType: ReportTargetType | null
  targetId: number | null
  beforeValue: string | null
  afterValue: string | null
  reason: string | null
  originalCreatedAt: string
  archivedAt: string
  archivedBy: number
}

/**
 * PR68 — audit log retention scheduler 현재 설정. 기본 enabled=false.
 * PR70 — runtime 등록 상태 (runtimeScheduled / lastRescheduledAt) 추가.
 *   - runtimeScheduled : 현재 프로세스에 살아있는 schedule future 가 있는지.
 *   - lastRescheduledAt : 마지막 reschedule 시각 (ISO string). 한 번도 등록 안 되면 null.
 */
export interface AuditLogRetentionScheduler {
  enabled: boolean
  cron: string
  updatedBy: number | null
  updatedAt: string
  runtimeScheduled?: boolean
  lastRescheduledAt?: string | null
}

/** PR68 — scheduler 부분 갱신. enabled / cron 둘 다 optional. */
export interface UpdateAuditLogRetentionSchedulerRequest {
  enabled?: boolean
  cron?: string
}

/**
 * PR60 — partial update. null/undefined 필드는 변경하지 않음. 1..100 범위.
 */
export interface UpdateModerationThresholdsRequest {
  review?: number
  comment?: number
  post?: number
  event?: number
  channel?: number
}

/**
 * PR55 — 신고 / appeal / hidden 3개 source 를 (targetType, targetId) 키로 merge 한 통합 row.
 * 운영자가 한 페이지에서 우선순위 순으로 처리할 수 있게 모든 컨텍스트를 동봉한다.
 */
export interface AdminModerationQueueItem {
  targetType: ReportTargetType
  targetId: number
  targetTitle: string
  targetPreview: string
  hidden: boolean
  hiddenAt?: string | null
  hiddenReason?: string | null
  pendingReportCount: number
  latestReportId?: number | null
  latestReportReason?: string | null
  latestReportCreatedAt?: string | null
  latestAppealId?: number | null
  latestAppealStatus?: ReportAppealStatus | null
  latestAppealReason?: string | null
  latestAppealCreatedAt?: string | null
  priority: AdminModerationPriority
}

/**
 * PR53 — 작성자/소유자가 본인 권한의 자동 숨김 콘텐츠를 한눈에 보는 row.
 * backend 가 5개 도메인 (REVIEW/COMMENT/POST/EVENT/CHANNEL) 을 hiddenAt 내림차순으로 통합.
 */
export interface CreatorModerationHiddenItem {
  targetType: ReportTargetType
  targetId: number
  targetTitle: string
  targetPreview: string
  hiddenAt: string
  hiddenReason?: string | null
  pendingReportCount: number
  appealStatus: AppealStatusView
  appealId?: number | null
}

export interface ReportAppeal {
  id: number
  targetType: ReportTargetType
  targetId: number
  requesterId: number
  requesterNickname: string
  reason: string
  status: ReportAppealStatus
  rejectReason?: string | null
  createdAt: string
  reviewedAt?: string | null
  /** Admin 큐 응답에서만 채워짐. 본인 응답은 null. */
  targetPreview?: string | null
  /** 응답 생성 시점의 대상 hidden 여부. ADMIN 이 승인하면 false 로 바뀐다. */
  targetHidden?: boolean
}
