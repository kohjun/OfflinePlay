# CONTENIDO — 플랫폼 아키텍처 (현재 구조 스냅샷)

본 문서는 PR90 시점 (2026-05-17) 의 백엔드/프론트엔드 구조와 결제·환불·재신청·moderation·audit/archive·scheduler 흐름을 한 곳에 모은 운영용 지도다. 다음 작업자가 어디서 무엇을 고쳐야 하는지 빠르게 찾을 수 있게 하는 것이 목표이며, 정책 세부 표현이 다를 경우 본 문서가 아닌 코드/세부 문서를 신뢰한다.

세부 정책 / 절차 문서는 따로 유지된다:
- 결제·환불 상세 (PG / webhook / hardening): [docs/payment-refund-policy.md](payment-refund-policy.md)
- 수동 QA 체크리스트: [docs/manual-qa-checklist.md](manual-qa-checklist.md)
- 로컬 셋업: [docs/dev-setup.md](dev-setup.md)
- 운영 배포: [docs/deploy-checklist.md](deploy-checklist.md)
- Kafka outbox 도입(예정): [docs/kafka-outbox-plan.md](kafka-outbox-plan.md)

---

## 1. Platform Overview

| 영역 | 스택 |
|---|---|
| Backend | Spring Boot 3.3, Kotlin 1.9.24, Java 21, JPA/MySQL, Redis, Elasticsearch |
| Frontend | Vite + React 18 + TypeScript, 모바일 420px 우선 |
| Auth | JWT(access+refresh) + Spring Security, `@PreAuthorize("hasRole('ADMIN')")` 등 메서드 가드 |
| Realtime | SSE (`/api/v1/notifications/stream`) — payment / participation / ticket / refund 알림 |
| Payment PG | Toss(`TossPaymentGateway`), local/CI 는 `MockPaymentGateway` |
| 배포 형태 | 단일 모놀리스 + frontend 정적 빌드 |

Kotlin 패키지명은 `com.contenido.*`. 디스크 경로는 `src/main/kotlin/com/OfflinePlay/domain/...` (모듈 리브랜드 잔재로 디렉터리만 `OfflinePlay`).

---

## 2. Backend Domain Map

도메인 패키지 (`src/main/kotlin/com/OfflinePlay/domain/`) 별 책임 요약. 같은 도메인 안에서 `controller / service / repository / entity / dto / gateway / webhook` 하위 디렉터리를 사용한다.

| 도메인 | 주요 컴포넌트 | 책임 |
|---|---|---|
| `user` | `UserService`, `User`, `UserRole` | 회원, 역할, 소셜/이메일 가입 |
| `auth` | `AuthService`, `JwtFilter`, `JwtService` | JWT 발급/갱신, Spring Security 설정 |
| `channel` | `ChannelService`, `Channel`, `Subscription` | 채널, 구독, 채널 owner |
| `event` | `EventService`, `EventParticipation`, `EventComment` | 이벤트 생성/수정, 신청·승인·거절·취소·재신청 |
| `ticket` | `TicketService`, `Ticket`, `TicketStatus` | 무료 발급 / 체크인 / 환불 표시 |
| `payment` | `PaymentService`, `PaymentAttempt`, `PaymentGateway`, `PaymentWebhookSignatureVerifier` | prepare / confirm / refund / webhook 멱등 / hardening |
| `report` | `ReportService`, `ReportAppealService`, `Report`, `ReportAppeal` | 사용자 신고 + appeal 큐 |
| `admin` | (§3 참조) | 운영 콘솔 — 신고/이의/모더레이션/감사 로그/스케줄러 |
| `notification` | `NotificationService`, `SseEmitterService`, `Notification` | DB 알림 row + SSE push |
| `review`, `post`, `interaction(comment/like)` | 도메인별 서비스 | 후기, 게시글, 댓글, 좋아요 |

전역 인프라:
- `global.response.ApiResponse` / `PageResponse` — 통일된 응답 래퍼
- `global.exception.*` — 도메인 예외 + `GlobalExceptionHandler`
- `infrastructure.scheduler.*` — `@EnableScheduling` + `AuditLogRetentionSchedulerRunner` 동적 cron

마이그레이션은 `src/main/resources/db/migration/V1..V9__*.sql` (Flyway).

---

## 3. Admin Console Structure

PR87 에서 controller / service 가 책임별로 분리됐다. 외부 endpoint 경로/응답/권한은 변경되지 않는 mechanical split 이라 컨트롤러 호출부는 그대로다.

### 3.1 컨트롤러 분할

| 컨트롤러 | 담당 경로 | 책임 |
|---|---|---|
| `AdminController` | `/api/v1/admin/users`, `/channels` | 유저/채널 기본 관리 |
| `AdminReportController` | `/api/v1/admin/reports` | 신고 큐, 상태 변경 |
| `AdminAppealController` | `/api/v1/admin/appeals` | 이의 제기 처리 |
| `AdminModerationController` | `/api/v1/admin/moderation/*` | hide/unhide, queue, stats, threshold, 채널 ban |
| `AdminAuditController` | `/api/v1/admin/audit-logs/*` | 감사 로그 조회/export, retention dry-run/archive, scheduler |

모든 컨트롤러는 클래스 레벨 `@PreAuthorize("hasRole('ADMIN')")`.

### 3.2 서비스 분할 — moderation facade

`AdminModerationService` 는 PR87 이후 **facade 역할만 유지**한다. hide/unhide 의 직접 구현은 facade 가 갖고 있고, 채널 ban / queue / stats 는 sub-service 로 위임된다:

| 서비스 | 책임 |
|---|---|
| `AdminModerationService` (facade) | hide/unhide 직접 처리, 나머지는 위임. 외부에서 보이는 public 시그니처가 보존됨. |
| `AdminChannelBanService` | 채널 ban / unban + 알림(`CHANNEL_BANNED` / `CHANNEL_UNBANNED`) |
| `AdminModerationQueueService` | 신고 큐 / hidden 큐 페이지네이션 |
| `AdminModerationStatsService` | 운영 콘솔 상단 stats (target type 별 hidden 카운트, recent appeal 등) |
| `ModerationThresholdService` | target type 별 자동 hide 임계치 (DB 보관, 운영 중 변경 가능) |

### 3.3 감사 로그 / archive / scheduler

| 서비스 | 책임 |
|---|---|
| `ModerationAuditLogService` | hide/unhide/ban/appeal 등 운영 액션을 1 row 기록. hide 트랜잭션에 동참 — 실패하면 hide 도 rollback. |
| `ModerationAuditLogRetentionService` | retention dry-run preview (한도/cutoff/oldest/newest 계산), 만료 row count |
| `ModerationAuditLogArchiveService` | 만료 row 를 `moderation_audit_log_archive` 로 이동. 한 번에 최대 `ARCHIVE_LIMIT=1000`. preview/execute 사이 stale 가드 (expectedCutoffAt + expectedCandidateCount). 운영 confirmText `ARCHIVE` 안전 가드. archive 자체 액션을 `AUDIT_LOGS_ARCHIVED` 로 1 row 기록. |
| `AuditLogRetentionSchedulerService` | scheduler 설정(`audit_log_retention_scheduler_settings`, single row id=1) 읽기/쓰기 + tick 진입점 (`runIfEnabled`). cron 사전 검증 + commit 후 runtime reschedule. |
| `AuditLogRetentionSchedulerRunner` | `@Profile("!test")` 에서만 등록. 실제 cron tick 실행 + `lastRescheduledAt` 추적. |
| `SystemActorService` | scheduled archive 같은 무인 job 의 audit actor. V9 가 `system@contenido.local` 을 seed; test 처럼 V9 미적용 환경에서는 lookup 실패 시 1회 생성. role 은 의도적으로 `PARTICIPANT` — `hasRole('ADMIN')` 권한을 갖지 않게 한다. |

### 3.4 V9 마이그레이션 — system actor

- 파일: `src/main/resources/db/migration/V9__seed_system_actor.sql`
- 내용: `system@contenido.local` user row 1건 seed
- 운영 정책: 정상 로그인 경로로 인증되지 않게 `SYSTEM_ACTOR_PASSWORD_PLACEHOLDER` sentinel 을 password 컬럼에 저장. bcrypt prefix 가 아니라 `BCryptPasswordEncoder.matches` 가 항상 false.

---

## 4. Frontend Structure Map

`frontend/src/` 기준.

### 4.1 라우팅 & 앱 셸

- `App.tsx` — path-매칭 라우터 (수동). 인증 가드, 비인증 첫 방문 시 `/onboarding`.
- `components/BottomTabBar.tsx` — 홈/탐색/티켓/알림/마이 5탭.
- `components/BottomCtaDock.tsx` — Thumb zone 액션 dock.
- `components/Toast.tsx`, `ErrorBoundary.tsx`, `Skeleton.tsx` — 글로벌 UX primitive.

### 4.2 스타일 분할 (PR85)

`frontend/src/index.css` 는 단일 manifest 가 됐고 실제 규칙은 도메인별로 쪼개져 `styles/*.css` 에 있다.

| 파일 | 책임 |
|---|---|
| `styles/tokens.css` | 디자인 토큰 (`--c-/--t-/--s-/--r-/--e-/--dur-/--ease-`) + 기존 토큰 alias |
| `styles/base.css` | reset / button / badge / card / form / toast primitive |
| `styles/layout.css` | app shell / page shell / detail page shell / bottom tab bar |
| `styles/notifications.css` | NotificationsPage |
| `styles/auth-onboarding.css` | Onboarding / Login / Signup / role picker |
| `styles/admin.css` | Admin moderation 콘솔 5섹션 |
| `styles/creator.css` | 크리에이터 apply stepper + Studio + EventCreate/Edit |
| `styles/home-feed.css` | HomePage |
| `styles/explore-channel.css` | ExplorePage / ChannelDetailPage |
| `styles/event-detail.css` | EventDetailPage hero/meta/sections |
| `styles/my-page.css` | MyPage |
| `styles/payment.css` | PaymentPage / PaymentResultPages |

cascade 순서 = `index.css` 의 `@import` 순서. 변경 시 우선순위 잘 살핀다.

### 4.3 Types 분할 (PR86)

`frontend/src/types/index.ts` 가 도메인별 모듈을 re-export:

`auth.ts`, `channel.ts`, `common.ts`, `creator.ts`, `event.ts`, `moderation.ts`, `notification.ts`, `payment.ts`, `ticket.ts`

호출처는 `from '../types'` 그대로 사용 — barrel 이 모듈 경로를 흡수.

### 4.4 Admin 콘솔 (PR87 frontend)

`pages/AdminPage.tsx` 가 5섹션을 탭으로 조립한다. URL `?tab=...` 쿼리로 deep link + popstate 동기화 + 한 번 mount 한 탭은 보존 (재진입 시 fetch 회피).

| 섹션 컴포넌트 | 책임 |
|---|---|
| `AdminModerationOverviewSection` | stats + threshold 운영 |
| `AdminReportsSection` | 신고 큐 + manual hide/unhide |
| `AdminAppealsSection` | 이의 제기 큐 |
| `AdminAuditLogsSection` | audit log 조회/export/archive browse |
| `AdminRetentionSection` | retention preview / archive 실행 / scheduler 설정 |

### 4.5 Event Detail Frontend Structure

PR84 가 JSX 를 섹션 컴포넌트로 쪼갰고, PR89 가 fetch/effect/mutation 을 hook 으로 분리했다.

`pages/event-detail/` 디렉터리:

| 파일 | 역할 |
|---|---|
| `EventDetailHeroSection.tsx` | hero(배경 이미지 + 채널 row + 좋아요/공유), participation 기반 cancel CTA |
| `EventReviewsSection.tsx` | 후기 summary + 목록 + 작성 폼 + 신고 |
| `EventCommentsSection.tsx` | 댓글 입력 + 목록 |
| `EventOwnerPanel.tsx` | owner 전용 신청자 / 체크인 보드 |
| `EventDetailActionPanel.tsx` | 하단 thumb-zone CTA (참가/결제/취소 분기) |
| `eventDetailFormatters.ts` | status label / tone / 일정/금액 포맷 |
| `hooks/useEventDetailData.ts` | event + participation + comments + applicants + checkIn fetch · SSE refetch (300ms debounce) · hash scroll |
| `hooks/useEventDetailReviews.ts` | review summary/list/myReview + 작성/수정/삭제/신고 — self-contained |
| `hooks/useEventDetailActions.ts` | apply / paidApply / cancel / approve / reject / submitComment mutation |

`pages/EventDetailPage.tsx` 는 라우트 파라미터를 받아 3 hook 을 wiring 하고 cta 라벨 계산 + 섹션 조립만 담당하는 orchestrator (PR89 기준 ~280 줄).

### 4.6 Stores / Hooks / API

- `stores/notificationStore.ts` — SSE 수신 dispatch + 미읽음 카운트 + `onIncoming(callback)` subscription.
- `stores/authStore.ts` — 로그인/토큰 갱신 + role 노출.
- `hooks/useAuth.ts` / `useToast.ts` / `useNotificationStream.ts` — 단일 진입점.
- `api/*.ts` — 도메인별 axios wrapper. 응답 unwrap 은 `apiClient` 에서 일괄.

---

## 5. Payment · Refund · Reapply Flow

상세 enum/예외/HTTP 매핑은 [payment-refund-policy.md](payment-refund-policy.md) §8–12. 본 절은 PR75~89 사이클을 반영한 현재 상태 요약.

### 5.1 결제 — prepare → confirm

```
client (PaymentPage)
  ├─ POST /events/{id}/payments/prepare   → PaymentAttempt(READY)
  ├─ Toss SDK requestPayment              (clientKey 있을 때만; 없으면 mock fallback)
  ├─ Toss redirect → /payments/success    (paymentKey, orderId, amount)
  └─ POST /payments/{id}/confirm          → confirm 게이트웨이 호출
       ├─ PaymentAttempt.status=PAID
       ├─ Ticket(PAID) 발급 + currentParticipants++
       └─ EventParticipation 정합성 보정 (없으면 APPROVED 생성, 있으면 approveByPayment)
```

confirm 멱등: 이미 PAID 인 attempt 에 다시 confirm 이 들어와도 gateway 재호출 없이 기존 응답 반환.

### 5.2 환불 (PR42 + PR81~82 보정)

```
POST /tickets/{ticketId}/refund   ← buyer / 채널 owner / ADMIN (PR42)
   ├─ PG gateway.refund()  (Toss POST /v1/payments/{paymentKey}/cancel, Mock 은 항상 성공)
   ├─ Ticket: PAID → REFUNDED
   ├─ PaymentAttempt.refundedAt 기록 (status=PAID 유지 — REFUNDED 권위는 Ticket)
   ├─ Event.currentParticipants -- (정원 회복)
   ├─ EventParticipation: APPROVED → CANCELED (PR82 sync 보강)
   └─ Notification(REFUND_COMPLETED) → buyer 1건 (PR81, best-effort)
```

거부 조건 요약 (PR42 §11.3, PR43 시간 가드):
- USED 티켓 → `TicketAlreadyUsedException` 409
- CANCELED 티켓 → `PaymentNotRefundableException` 409
- 이미 REFUNDED → 멱등 응답 (no throw, gateway 재호출 없음)
- 이벤트 시작 시각 ≤ now → `RefundDeadlinePassedException` 409
- PG gateway 실패 → `RefundFailedException` 502

webhook `refund.completed` 도 같은 보정을 수행 (`PaymentService.handleRefundedWebhook`). 멱등 가드: `attempt.refundedAt != null` 이면 skip.

### 5.3 유료 APPROVED 참가 취소 (PR75)

유료 이벤트의 **APPROVED** 참가는 `DELETE /events/{id}/participations` (셀프 cancel) 로는 처리할 수 없다. 결제·티켓 정합성을 위해 반드시 환불 흐름을 거쳐야 한다.

- 백엔드: 결제 이벤트의 APPROVED 셀프 cancel 시도 → 409.
- 프론트: `useEventDetailActions.handleCancel` 이 `participationFee > 0 && status === 'APPROVED'` 일 때 confirm 문구를 환불 안내로 분기하고, 티켓 페이지의 환불 CTA 로 유도한다.
- USED 티켓은 본인/owner/ADMIN 모두 환불 불가 (불변 — payment-refund-policy.md §3).

### 5.4 재신청 (PR85)

`REJECTED` 또는 `CANCELED` 상태인 본인 EventParticipation 은 같은 row 를 PENDING 으로 복구한다.

- 백엔드: `EventService.applyToEvent` 가 기존 row 가 terminal(REJECTED/CANCELED) 이면 새 row 를 만들지 않고 PENDING 으로 reset.
- 프론트: EventDetailActionPanel CTA 라벨이 `status === 'CANCELED'` 일 때 `'다시 신청하기'` 로 분기.

### 5.5 결제 알림 라우팅 (PR83)

`NotificationsPage` 의 항목 클릭 시 결제/티켓/환불 알림은 알맞은 페이지로 라우팅:
- `TICKET_ISSUED`, `TICKET_CHECKED_IN`, `REFUND_COMPLETED` → `/tickets/{id}`
- `PARTICIPATION_*` → `/events/{id}` (또는 채널 라우트가 있으면 `/channels/{cid}/events/{eid}`)
- `CHANNEL_BANNED/UNBANNED` → `/channels/{id}`

---

## 6. Notification Flow

### 6.1 발송 경로

`NotificationService.create*` 시리즈가 1 row 저장 + `SseEmitterService.push(receiverId, payload)` 호출. SSE 실패는 best-effort — DB row 는 그대로 유지되므로 다음 조회 시 보인다.

`NotificationType` 현재 enum 값:
- `NEW_EVENT`, `NEW_POST`, `NEW_COMMENT`, `NEW_LIKE`
- `APPLICATION_APPROVED`, `APPLICATION_REJECTED` (크리에이터 신청)
- `PARTICIPATION_REQUESTED`, `PARTICIPATION_APPROVED`, `PARTICIPATION_REJECTED`, `PARTICIPATION_CANCELED`
- `TICKET_ISSUED`, `TICKET_CHECKED_IN`
- `CHANNEL_BANNED`, `CHANNEL_UNBANNED` (PR59)
- `REFUND_COMPLETED` (PR81)

`targetType` 은 `"events" | "tickets" | "channels" | "participations" | "applications" | "posts" | "comments"`.

### 6.2 SSE 스트림

- 엔드포인트: `GET /api/v1/notifications/stream` (인증 필요)
- 클라이언트: `stores/notificationStore` 가 `EventSource` 로 구독하고 `onIncoming(cb)` 으로 페이지 hook 에 fan-out.
- EventDetail 은 `useEventDetailData` 가 알림 수신 시 300ms 디바운스로 event / participation / applicants / checkIn 을 묶어 refetch.

### 6.3 NotificationsPage

알림 카드 클릭 → §5.5 라우팅 + `markRead` 호출. 필터는 type 묶음 (전체/티켓/참가/시스템 등).

---

## 7. Moderation Flow

### 7.1 신고 → 자동 hide (PR50~52, PR60 임계치 DB화)

```
POST /api/v1/reports                     ← reporter
  ├─ 본인 글 차단 / 중복 차단 / 대상 존재 확인
  ├─ Report row INSERT (status=PENDING)
  └─ ReportService: 같은 (targetType, targetId) 에 누적 신고 수가
     ModerationThresholdService.thresholdFor(targetType) 이상이면
     target 의 hidden=true 자동 전환 + 신고들의 status=AUTO_HIDDEN 갱신
```

자동 hide 대상: REVIEW / COMMENT / POST / EVENT / CHANNEL. 임계치는 DB `moderation_threshold_settings` 에서 운영 중 변경 가능 (V4 마이그레이션, PR60).

### 7.2 이의 제기 — Appeal (PR53)

자동 hide 된 사용자가 한 번에 한해 appeal 제출 가능. 운영자가 approve → unhide + 신고 dismissed, reject → hidden 유지. `ReportAppeal` 엔티티 + `ReportAppealService` + `AdminAppealsSection`.

### 7.3 Manual hide / unhide (PR54)

`AdminModerationController.hide/unhide` 가 facade(`AdminModerationService`) 를 호출. 정책 (PR54 그대로):
- hide/unhide 는 appeal 도메인을 자동으로 변경하지 않는다. PENDING appeal 이 있어도 수동 hide 가 그것을 reject 처리하지 않고, 수동 unhide 가 PENDING appeal 을 approve 처리하지도 않는다.
- 이미 hidden → `TargetAlreadyHiddenException` 409
- hidden 이 아닌 대상 unhide → `TargetNotHiddenException` 409
- 동일 트랜잭션에 `ModerationAuditLogService.record(TARGET_HIDDEN/UNHIDDEN)` 기록. audit 실패 시 hide 도 rollback.

### 7.4 큐 / Stats / Threshold

- `AdminModerationQueueService` — pending 신고 + hidden target 페이지네이션
- `AdminModerationStatsService` — overview 카드 (hidden / pending appeal / recent ban)
- `ModerationThresholdService` — target type 별 임계치 read/update (DB)

### 7.5 채널 Ban / Unban (PR59 → PR87 분리)

`AdminChannelBanService` 가 ban / unban 둘 다 책임. channel.owner 에게 즉시 `CHANNEL_BANNED` / `CHANNEL_UNBANNED` 알림 발송. cascade 영향 카운트 (hidden 된 sub-target 수) 포함.

---

## 8. Audit Log · Archive · Scheduler Flow

### 8.1 Audit 기록 (PR61~63)

`moderation_audit_logs` 테이블 (V5). 컬럼: `id, actor_id, action, target_type, target_id, reason, before_value, after_value, created_at`.

기록되는 액션 (`ModerationAuditAction` enum):
- `TARGET_HIDDEN`, `TARGET_UNHIDDEN`
- `CHANNEL_BANNED`, `CHANNEL_UNBANNED`
- `REPORT_DISMISSED`, `APPEAL_APPROVED`, `APPEAL_REJECTED`
- `THRESHOLD_UPDATED`
- `AUDIT_LOGS_ARCHIVED` (PR65 — archive job 자체의 기록)

조회 / 검색: `AdminAuditController` + `ModerationAuditLogSpecs`. CSV export 1000 행 한도 (`ModerationAuditLogService.EXPORT_LIMIT`).

### 8.2 Retention dry-run (PR64)

`ModerationAuditLogRetentionService.preview(retentionDays)` 가 cutoff = `now - retentionDays`, 만료 후보 count, oldest/newest createdAt 을 반환. 운영자가 보존 기간을 바꿔 보며 영향 범위를 가늠한다 (실 삭제/archive 는 §8.3).

### 8.3 Archive 실행 (PR65~67)

`ModerationAuditLogArchiveService` 가 active → archive 이동.

흐름:
1. **Preview** — `previewArchive(retentionDays)` 로 cutoffAt, candidateCount, willArchiveCount(=min(candidate, ARCHIVE_LIMIT)) 반환.
2. **Execute** — `executeArchive(adminId, ExecuteAuditLogArchiveRequest)`:
   - `confirmText` 정확히 `"ARCHIVE"` 가드 — `AuditLogArchiveConfirmationRequiredException`.
   - `expectedCutoffAt` / `expectedCandidateCount` 가 server 재계산 결과와 다르면 → `AuditLogArchiveStaleException` (preview 와 execute 사이에 row 가 추가됐을 때).
   - 한 번에 최대 `ARCHIVE_LIMIT = 1000` row. 더 많으면 다시 호출.
   - hard delete 안 함 — `moderation_audit_log_archive` 에 복사 후 active 에서 제거.
   - 작업 자체를 `AUDIT_LOGS_ARCHIVED` 액션으로 1 row 기록 (createdAt > cutoffAt 이라 본 batch 에 포함되지 않음).
3. **Archive 조회** — `AdminAuditController` 의 archive list/export. CSV header originalId 시작, EXPORT_LIMIT=1000 동일.

### 8.4 Scheduler (PR68~70)

`AuditLogRetentionSchedulerService` + `AuditLogRetentionSchedulerRunner` 가 archive 를 자동 실행.

- 설정 테이블 (V8): `audit_log_retention_scheduler_settings`, single row id=1. 디폴트 OFF + `DEFAULT_CRON = "0 30 3 * * *"` (매일 03:30).
- 운영 ADMIN 만 enable / cron 갱신 가능. cron 사전 검증 (`CronExpression.isValidExpression`).
- DB 저장 commit 후 `runner.reschedule(enabled, cron)` 으로 runtime 동적 재등록 (`afterCommit` hook 으로 rollback 안전).
- `runner` 는 `@Profile("!test")` 라 테스트 컨텍스트에서는 bean 자체가 없다 — `ObjectProvider.ifAvailable` 로 안전하게 no-op.
- tick 실행 시 `executeScheduledArchive(scheduledByAdminId)` 호출. archive 가 한도(`ARCHIVE_LIMIT=1000`) 에 닿으면 그날은 거기까지 — 다음 tick 에서 이어 처리.

### 8.5 System Actor (PR69)

scheduler 같은 무인 job 은 audit 의 actor 가 필요하다 (`actor_id` NOT NULL). 그 자리는 `SystemActorService.getSystemActor()` 가 제공하는 `system@contenido.local` row 가 채운다.

- V9 마이그레이션이 운영 DB 에 seed.
- test/local 등 V9 가 적용되지 않은 환경에선 첫 호출 시 1회 생성 (UNIQUE(email) 가 race 방지).
- 비밀번호는 bcrypt 와 매칭 불가능한 sentinel — 일반 로그인 경로 차단.
- role 은 `PARTICIPANT` — `hasRole('ADMIN')` 권한을 갖지 않게 한다. archive / audit 기록 용도 외엔 쓰지 않는 것이 원칙.

---

## 9. Operational Notes

### 9.1 운영 hardening

결제 운영 점검: [payment-refund-policy.md §12](payment-refund-policy.md). `PaymentHardeningCheck` 가 부팅 시 (a) Toss enabled + secretKey 누락, (b) webhook signature required + secretKey 누락 두 케이스를 fail-fast 한다.

### 9.2 SSE 끊김 / 재연결

`useNotificationStream` 이 재연결 backoff 를 처리한다. SSE 미연결 상태에서 발생한 알림은 다음 페이지 진입 시 페이지 자체 fetch 가 반영 — REFUND_COMPLETED 등 결제 후 알림은 EventDetailPage 의 SSE refetch 가 받지 못해도 ticket 페이지 진입 시 직접 fetch 로 보정된다.

### 9.3 수동 QA

릴리스 전 click-through 동선은 [manual-qa-checklist.md](manual-qa-checklist.md). 본 architecture 문서가 보여주는 흐름과 실제 수동 검증 단계는 1:1 으로 매칭되도록 유지한다.

### 9.4 빌드 / 테스트

```
./gradlew.bat test            # 백엔드 단위/통합 테스트
cd frontend; npm run build    # tsc -b + vite build (typecheck 포함)
```

테스트 컨텍스트는 `AuditLogRetentionSchedulerRunner` 가 비활성이라 archive scheduler tick 이 자동으로 돌지 않는다 — 단위 테스트는 service 메서드를 직접 호출.

---

## 10. Known Exclusions (의도된 미구현)

본 문서는 **현재 구현된 것** 만을 다룬다. 다음 항목은 아직 코드에 없거나 향후 PR 로 분리된다.

| 항목 | 현 상태 | 이전 PR / 후속 PR |
|---|---|---|
| **COMMENT cascade 자동 hide** | 자동 hide 대상은 §7.1 enum 5종. comment cascade(부모 글 hide 시 자식 댓글 자동 hide) 는 운영자 수동 처리. | 향후 PR |
| **부분 환불** | 전액 환불만. `cancelAmount = attempt.amount`. | 향후 PR (payment-refund-policy §11.7) |
| **USED 후 강제 환불 (운영 도구)** | USED 티켓 환불 차단. 노쇼 보상 / 행사 취소 등은 별도 ADMIN 도구 필요. | 향후 PR |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED 카운트 일치 batch 없음. | 향후 PR |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. | 향후 PR |
| **PortOne / 다른 PG 어댑터** | `PaymentGateway` interface 는 열려 있으나 구현체는 Toss + Mock 만. | 향후 PR |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수가 동시 confirm 시 초과 가능. | 향후 PR |
| **Kafka outbox** | 도입 설계만 ([kafka-outbox-plan.md](kafka-outbox-plan.md)). 현재 알림은 직접 SSE push. | 향후 PR |
| **실시간 잔여 자리 / QR 회전 / 푸시** | EventDetail 의 잔여 자리는 SSE refetch 기반. QR 30초 회전 / 푸시 / 시스템 밝기는 미구현. | 향후 PR |

운영 검증 미수행 / 백엔드 영향 있는 변경은 [manual-qa-checklist.md](manual-qa-checklist.md) 의 항목 단위로 추적한다.

---

## 11. PR 히스토리 (현 구조에 닿은 주요 PR)

본 문서가 반영한 PR 사이클 (커밋 해시는 변경 가능):

- PR42 — refund endpoint + Ticket REFUNDED
- PR50~54 — 신고 + 자동 hide + appeal + manual hide
- PR55~57 — moderation queue / analytics / threshold UI
- PR59 — 채널 ban + 알림
- PR60 — moderation threshold DB 화
- PR61~63 — audit log + export
- PR64~67 — retention dry-run + archive + archive browse
- PR68~70 — archive scheduler + system actor + 동적 cron
- PR75 — 유료 APPROVED 셀프 cancel 가드
- PR76~80 — payment / refund UX 마무리
- PR81 — REFUND_COMPLETED 알림
- PR82 — refund 후 participation sync
- PR83 — 결제 알림 라우팅
- PR84 — EventDetailPage 섹션 분리
- PR85 — global styles 도메인 분리
- PR86 — frontend types 도메인 분리
- PR87 — admin controller / service facade 분리
- PR88 — admin moderation service 테스트 분리
- PR89 — EventDetailPage hooks 분리
- PR90 — 본 문서

상세 정책 변경 이력은 도메인별 세부 문서 (특히 [payment-refund-policy.md](payment-refund-policy.md)) 와 git log 를 참고.
