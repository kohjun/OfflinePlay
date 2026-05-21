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
| `notification` | `NotificationService`, `NotificationPreferenceService`, `SseEmitterService`, `Notification`, `UserNotificationPreference` | DB 알림 row + SSE push + 사용자별 NotificationType 수신 preference (§6) |
| `review`, `post`, `interaction(comment/like)` | 도메인별 서비스 | 후기, 게시글, 댓글, 좋아요 |

전역 인프라:
- `global.response.ApiResponse` / `PageResponse` — 통일된 응답 래퍼
- `global.exception.*` — 도메인 예외 + `GlobalExceptionHandler`
- `infrastructure.scheduler.*` — `@EnableScheduling` + `AuditLogRetentionSchedulerRunner` 동적 cron

마이그레이션은 `src/main/resources/db/migration/V1..V10__*.sql` (Flyway).

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
| `AdminPaymentController` (PR106) | `/api/v1/admin/tickets/*` | 운영 결제 도구 — 현재는 USED/시작 후 PAID 티켓 강제 전액 환불 (§5.2.1) |

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
| `ModerationAuditLogService` | hide/unhide/ban/appeal 등 운영 액션을 1 row 기록. hide 트랜잭션에 동참 — 실패하면 hide 도 rollback. PR106 부터 결제 도메인의 `TICKET_FORCED_REFUNDED` 도 같은 테이블에 기록한다 (`targetType=null`, afterValue JSON 에 ticketId/paymentAttemptId 동봉). **PR122** 부터 일반 사용자 환불 (`refundPaymentByTicket`) 도 `PAYMENT_REFUNDED` / `PAYMENT_PARTIALLY_REFUNDED` 액션으로 기록 — actor 는 호출자 (buyer/owner/ADMIN), forced refund 와 분리. |
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

### 4.4 Admin 콘솔 (PR87 frontend + PR106)

`pages/AdminPage.tsx` 가 6섹션을 탭으로 조립한다. URL `?tab=...` 쿼리로 deep link + popstate 동기화 + 한 번 mount 한 탭은 보존 (재진입 시 fetch 회피).

| 섹션 컴포넌트 | 책임 |
|---|---|
| `AdminModerationOverviewSection` | stats + threshold 운영 + 운영자 활동 요약 (PR93) |
| `AdminReportsSection` | 신고 큐 + manual hide/unhide |
| `AdminAppealsSection` | 이의 제기 큐 |
| `AdminAuditLogsSection` | audit log 조회/export/archive browse |
| `AdminRetentionSection` | retention preview / archive 실행 / scheduler 설정 |
| `AdminPaymentToolsSection` (PR106) | 운영 결제 도구 — ticketId + 사유 입력 → 강제 전액 환불. confirm dialog + 결과 카드. |

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
- `hooks/useCoalescedRefresh.ts` (PR92) — SSE 묶음 알림을 한 번의 refetch 로 합치는 debounce helper.
- `utils/notificationMeta.ts` (PR97) — NotificationType 별 label/tone/path 의 single source. §6.7 참고.
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

### 5.2 환불 (PR42 + PR81~82 보정 + PR117 부분 환불 + PR122 audit)

```
POST /tickets/{ticketId}/refund   ← buyer / 채널 owner / ADMIN (PR42)
   body: { reason?, amount? }     ← amount 는 PR117 — null 이면 남은 환불 가능 금액 전체
   ├─ PG gateway.refund(amount = 이번 호출 금액)
   ├─ amount == remainingRefundableAmount  (전액 cascade)
   │     ├─ Ticket: PAID → REFUNDED      (또는 PARTIALLY_REFUNDED → REFUNDED)
   │     ├─ PaymentAttempt: refundedAmount = amount, refundedAt, status=PAID 유지
   │     ├─ Event.currentParticipants --
   │     ├─ EventParticipation: APPROVED → CANCELED
   │     ├─ Notification(REFUND_COMPLETED, title="환불이 완료되었어요") → buyer
   │     └─ Audit(PAYMENT_REFUNDED, actor=호출자, before/after JSON, reason) — PR122
   └─ amount < remainingRefundableAmount  (partial — PR117)
         ├─ Ticket: PAID → PARTIALLY_REFUNDED  (또는 그대로 PARTIALLY_REFUNDED)
         ├─ PaymentAttempt: refundedAmount += amount, refundedAt, status=PARTIALLY_REFUNDED
         ├─ (정원 / participation 변경 없음)
         ├─ Notification(REFUND_COMPLETED, title="부분 환불이 처리되었어요") → buyer
         └─ Audit(PAYMENT_PARTIALLY_REFUNDED, actor=호출자, before/after JSON, reason) — PR122
```

거부 조건 요약 (PR42 §11.3, PR43 시간 가드, PR117 금액 가드):
- USED 티켓 → `TicketAlreadyUsedException` 409
- CANCELED 티켓 → `PaymentNotRefundableException` 409
- 이미 REFUNDED → 멱등 응답 (no throw, gateway 재호출 없음)
- 이벤트 시작 시각 ≤ now → `RefundDeadlinePassedException` 409
- PG gateway 실패 → `RefundFailedException` 502
- **PR117** — `amount < 1` 또는 `amount > remainingRefundableAmount` → `InvalidRefundAmountException` 400

webhook `refund.completed` 도 같은 보정을 수행 (`PaymentService.handleRefundedWebhook`). 멱등 가드: `attempt.refundedAt != null` 이면 skip. webhook 은 부분 환불 입력을 처리하지 않는다 (`PARTIALLY_REFUNDED` webhook 은 무시). PR122 의 audit 도 webhook 흐름에서는 만들지 않는다 — PG-driven 이고 명시적 actor 가 없어서.

자세한 부분 환불 정책 (cascade 조건 / PG 호출 / 알림 카피 / 상태 전이): [docs/payment-refund-policy.md §14](payment-refund-policy.md).

#### 5.2.1 ADMIN 강제 전액 환불 (PR106)

USED / 시작 후 PAID 티켓 등 일반 환불 경로의 deadline / status 가드로 막힌 케이스를 위한 ADMIN 전용 진입점.

```
POST /admin/tickets/{ticketId}/forced-refund   ← ADMIN only
   ├─ AdminPaymentService.forceRefund (@Transactional)
   │   ├─ PaymentService.forceRefundByAdmin (PAID/USED 통과, REFUNDED/CANCELED 거부, deadline 무시)
   │   │   └─ markRefundedInternal 재사용 (5.2 cascade 그대로)
   │   └─ ModerationAuditLogService.record(TICKET_FORCED_REFUNDED, afterValue={ticketId,..}, reason=...)
   └─ AdminForcedRefundResponse (refundReason echo)
```

특징:
- **권한**: ADMIN 만 (buyer / channel owner 는 본 경로로 호출 불가)
- **상태 허용**: PAID + USED — 일반 경로의 `TicketAlreadyUsedException` / `RefundDeadlinePassedException` 우회
- **상태 거부**: REFUNDED → `TicketAlreadyRefundedException` (멱등 응답 아님, 실수 방지) / CANCELED → `PaymentNotRefundableException`
- **상태 허용**: PAID / USED / **PARTIALLY_REFUNDED (PR117)** — PARTIALLY_REFUNDED 티켓에 강제 환불 호출 시 **남은 금액 전체** 를 환불해 REFUNDED 로 cascade
- **부분 forced refund 미지원** — 일반 사용자 흐름은 PR117 으로 부분 환불 가능하지만, ADMIN 강제 환불은 여전히 한 번에 남은 금액 전액을 환불 (운영 의미: "이 티켓의 환불을 한 번에 끝낸다")
- **사유 필수** — `@NotBlank @Size(1..500)`. audit `reason` 컬럼 + 응답 `refundReason` 에 그대로 기록
- **buyer 알림** — 기존 `REFUND_COMPLETED` 1건. **운영 사유는 알림 메시지에 노출되지 않음** (사용자 친화 카피 유지)
- **audit + 환불 cascade 가 같은 트랜잭션** — audit 실패 시 환불도 rollback (§8.1 audit 정책 일관)

자세한 정책 / 거부 조건 / 의도적 제외는 [docs/payment-refund-policy.md §13](payment-refund-policy.md).

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

`NotificationService.notify(receiverIds, type, title, message, targetType, targetId)` 가 단일 진입점. `@Async + @Transactional` 로 호출자 트랜잭션과 독립 실행, 내부에서 (a) receiver 별 preference 필터(§6.4), (b) `notificationRepository.saveAll`, (c) 각 row 에 대해 `SseEmitterService.sendToUser` 순서로 동작. SSE 실패는 best-effort — DB row 는 그대로 유지되므로 다음 조회 시 보인다.

`NotificationType` 현재 enum 값:
- `NEW_EVENT`, `NEW_POST`, `NEW_COMMENT`, `NEW_LIKE`
- `APPLICATION_APPROVED`, `APPLICATION_REJECTED` (크리에이터 신청)
- `PARTICIPATION_REQUESTED`, `PARTICIPATION_APPROVED`, `PARTICIPATION_REJECTED`, `PARTICIPATION_CANCELED`
- `TICKET_ISSUED`, `TICKET_CHECKED_IN`
- `CHANNEL_BANNED`, `CHANNEL_UNBANNED` (PR59)
- `REFUND_COMPLETED` (PR81)
- `EVENT_ANNOUNCEMENT` (PR141)

`targetType` 은 `"events" | "tickets" | "channels" | "participations" | "applications" | "posts" | "comments"`.

발송 단계 (PR95 → PR140):
1. receiver 별 `NotificationPreferenceService.isEnabled(userId, type)` 필터 — disabled 면 row/SSE/push 모두 skip.
2. 통과한 receiver 들의 `Notification` row 를 `saveAll`.
3. 각 row 에 대해 `SseEmitterService.sendToUser` — best-effort, 실패해도 DB row 는 남는다.
4. `PushNotificationService.dispatch(notifications)` — Web Push best-effort (PR140). 실패는 swallow.

### 6.2 SSE 스트림

- 엔드포인트: `GET /api/v1/notifications/stream` (인증 필요)
- 클라이언트: `stores/notificationStore` 가 `EventSource` 로 구독하고 `onIncoming(cb)` 으로 페이지 hook 에 fan-out.
- EventDetail 은 `useEventDetailData` 가 알림 수신 시 300ms 디바운스로 event / participation / applicants / checkIn 을 묶어 refetch.

### 6.3 NotificationsPage

알림 카드 클릭 → §5.5 라우팅 + `markRead` 호출. 카드 라벨/뱃지 tone/라우팅 규칙은 §6.6 의 단일 모듈에서 가져온다.

### 6.4 Preferences — DB · API (PR95 + PR104)

사용자가 NotificationType 별로 알림 수신을 켜고 끌 수 있게 하는 영속 설정.

**DB**: V10 마이그레이션이 `user_notification_preferences` 테이블 생성. 컬럼: `id PK / user_id FK(users) / notification_type VARCHAR(50) / enabled BOOLEAN DEFAULT TRUE / created_at / updated_at`. `UNIQUE(user_id, notification_type)` + `INDEX(user_id)`.

| 항목 | 정책 |
|---|---|
| row 부재 | enabled = true 로 간주 (DB default + 서비스 fallback 양쪽으로 안전망) |
| 같은 type 중복 row | UNIQUE 가 차단 — 발생 불가 |
| audit 기록 | 없음 (개인 설정 영역, moderation_audit_logs 와 무관) |

**API** (모두 인증 사용자 전용):

| 메서드/경로 | 책임 |
|---|---|
| `GET /api/v1/notifications/preferences` | 모든 `NotificationType` 에 대한 응답 (row 없는 type 은 enabled=true 채워서 반환). PR104: row 있는 type 은 `updatedAt` 동봉. |
| `PATCH /api/v1/notifications/preferences` | 부분 갱신. request 에 없는 type 은 기존 값 유지. 같은 type 중복이 한 요청에 들어오면 **마지막 값** 채택 (단순화). 응답은 갱신 후의 전체 preference 목록. |

요청 페이로드: `{ preferences: [{ type: NotificationType, enabled: boolean }, ...] }`.

응답 페이로드: `[{ type, enabled, updatedAt? }, ...]`. `updatedAt` 정책 (PR104):

| 케이스 | 응답 `updatedAt` |
|---|---|
| DB row 가 있는 type | `row.updatedAt` (ISO LocalDateTime) |
| DB row 가 없는 type (한 번도 설정한 적 없음) | `null` |
| PATCH 직후 응답 — 변경된 type | `update()` 가 `LocalDateTime.now()` 로 in-place 갱신한 시각. 같은 트랜잭션 안에서 다시 읽어도 갱신 값이 보인다. |
| PATCH 직후 응답 — request 에 없는 type | 그대로 유지 (entity 자체를 건드리지 않음) |

> **주의**: `updatedAt` 은 "마지막 row 변경 시각" 만 보여주는 lightweight signal 이다. **변경 이력 / actor / 변경 전·후 값을 저장하지 않으며, audit / history 가 아니다.** 변경 audit 가 필요해지면 §10 의 "Preference 변경 audit / 이력" 항목을 채우는 별도 PR 이 필요하다.

### 6.5 발송 시 preference 필터 (PR95)

`NotificationService.notify` 는 receiver 별로 `NotificationPreferenceService.isEnabled(userId, type)` 를 호출해 발송 대상을 거른다. **disabled 인 receiver 는 `notifications` 테이블에 row 자체가 INSERT 되지 않고 SSE 도 발송되지 않는다** — 끈 알림은 발송 단계에서 사라진다.

`isEnabled` 동작:
1. `preferenceRepository.findByUserIdAndNotificationType(userId, type)` lookup.
2. row 가 없거나 enabled=true 면 true.
3. lookup 자체가 예외 (DB 일시 장애 등) → **fail-open**: warn log 한 줄 + true 반환. preference 조회 문제로 알림이 사라지는 회귀를 막는다.

복수 receiver 발송에서는 일부 receiver 만 필터링되며 나머지에게는 정상 발송된다 (`receiverIds.filter { isEnabled(it, type) }`).

### 6.6 Frontend — NotificationsPage 알림 설정 패널 (PR96 + PR99 + PR104)

`pages/NotificationsPage.tsx` 헤더에 "알림 설정" 토글 버튼이 있고, 클릭 시 collapsible 패널이 열려 NotificationType 별 체크박스를 나열한다.

| 동작 | 동작 정책 |
|---|---|
| 패널 열기 | 첫 오픈 시에만 backend 에서 lazy fetch (재오픈은 캐시 재사용) |
| 개별 토글 | 즉시 `PATCH /preferences` 호출 — request 에는 변경된 type 단건만 포함 |
| 묶음 토글 (PR99) | 패널 상단 "전체 알림 + 5 카테고리" 행. 단일 PATCH 로 해당 bundle type 들을 한 번에 갱신 — §6.6.1 참고 |
| 응답 처리 | 응답으로 전체 preferences 갱신, "알림 수신 설정을 저장했어요" success toast |
| 실패 처리 | 토글 직전 snapshot 으로 rollback (개별 토글은 해당 type 만, 묶음 토글은 bundle type 전체) + danger toast |
| 마지막 저장 표시 (PR104) | 각 row 라벨 아래 muted text — `updatedAt` 있으면 "마지막 저장: {상대시간}" (방금 전 / N분 전 / N시간 전 / N일 전 / 7일 이상은 로컬 날짜), 없으면 "기본값". 토글/묶음/quick mute/undo 응답이 도착하면 `setPreferences(saved)` 가 동기적으로 갱신해 표시도 즉시 새 시각으로 바뀐다. |
| 중복 클릭 가드 | `savingTypes: Set<NotificationType>` 가 type 단위 가드. 묶음 토글은 자신의 bundle type 들을 모두 set 에 추가해 같은 시간 동안 그 안의 개별 체크박스와 다른 bundle 버튼이 모두 disabled + `aria-busy` |
| accessibility | 토글 버튼 `aria-expanded` / `aria-controls`, 섹션 `aria-labelledby`, 체크박스 `htmlFor`/`id`, 묶음 영역 `aria-label="알림 묶음 토글"` |

상대 시간 포맷은 `pages/NotificationsPage.tsx` 의 inline `formatRelativeTime` helper — 새 라이브러리 없이 분/시간/일 단위만 표현하고 7일 이상은 `Date.toLocaleDateString()` fallback. "마지막 저장" 은 §6.4 의 lightweight signal 정책 그대로 — 변경 이력이 아니라 row 의 마지막 변경 시각만 보여준다.

#### 6.6.1 카테고리 묶음 정의 (PR99)

`utils/notificationMeta.ts` 의 `NOTIFICATION_PREFERENCE_BUNDLES` 가 단일 source. NotificationType 15개를 정확히 분할(partition) — 한 type 이 두 bundle 에 속하지 않고, 모든 type 이 정확히 하나의 bundle 에 속한다.

| bundle id | label | 포함 NotificationType |
|---|---|---|
| `participation` | 참가 관련 | `PARTICIPATION_REQUESTED`, `PARTICIPATION_APPROVED`, `PARTICIPATION_REJECTED`, `PARTICIPATION_CANCELED`, `TICKET_ISSUED`, `TICKET_CHECKED_IN` |
| `payment` | 결제 관련 | `REFUND_COMPLETED` |
| `content` | 콘텐츠 관련 | `NEW_EVENT`, `NEW_POST`, `NEW_COMMENT`, `NEW_LIKE` |
| `moderation` | 운영 알림 | `CHANNEL_BANNED` |
| `system` | 시스템 알림 | `APPLICATION_APPROVED`, `APPLICATION_REJECTED`, `CHANNEL_UNBANNED` |

"전체 알림" 은 별도 bundle id 가 아니라 현재 preferences 의 모든 NotificationType 을 직접 사용 (UI 가 한곳에서 계산). 새 enum 이 추가되면 위 표와 `NOTIFICATION_PREFERENCE_BUNDLES` 를 함께 갱신해 모든 type 이 어딘가의 bundle 에 들어가도록 유지한다.

버튼 라벨 정책 (`every-true` 기반):
- 해당 bundle 의 모든 type 이 `enabled === true` 면 버튼 라벨은 "{카테고리명} 끄기"
- 하나라도 `false` 면 "{카테고리명} 켜기"
- `row 없음 → enabled=true` 의 §6.4 정책을 그대로 활용 (`prefByType.get(t) ?? true`) — 새 사용자도 일관된 라벨을 본다

PATCH payload 는 해당 bundle 의 type 들만 `{ type, enabled: nextEnabled }` 로 포함한다. backend 의 partial-update 정책(§6.4) 덕에 다른 카테고리 type 은 영향 없이 유지된다.

#### 6.6.2 카드 Quick Mute + Undo (PR101)

알림 카드 자체에서 해당 NotificationType 을 한 클릭으로 끌 수 있는 보조 액션. 설정 패널을 거치지 않고도 "이 종류 알림이 너무 많이 와요" 상황을 즉시 해결할 수 있게 한다.

| 항목 | 동작 정책 |
|---|---|
| 카드 액션 | 알림 카드 우측 상단에 "이 유형 끄기" 작은 버튼. 카드 내부 클릭은 기존 라우팅 동작 그대로 유지 — 버튼은 `<button.notification-item>` 의 형제(`<div.notification-row>` wrapper) 로 배치해 버튼 중첩 회피. |
| 클릭 결과 | 해당 알림의 `type` 한 건만 `PATCH /preferences` 로 `enabled=false`. |
| state 동기화 | `preferences` 가 한 번이라도 로드된 적 있으면 optimistic 갱신 + 응답으로 동기화 — 패널이 열려 있다면 체크박스가 즉시 unchecked. `preferences === null` 이면 state 를 건드리지 않는다 (다음에 패널을 열면 backend 에서 fresh fetch — §6.4 의 row 없음 = true 정책 위에서 안전). |
| 이미 꺼진 type | preferences 가 로드돼 있고 해당 type 이 `enabled=false` 면 버튼 대신 "꺼짐" 회색 배지 표시. 액션 없음. |
| undo banner | mute 성공 시 화면 상단에 banner 5초 노출. "{라벨} 알림을 껐어요. 실수였다면 5초 안에 되돌릴 수 있어요." + "되돌리기" 버튼. |
| undo 동작 | 같은 type 을 `PATCH /preferences` 로 `enabled=true`. 성공 시 success toast, 실패 시 snapshot rollback + danger toast. |
| 연속 mute | 새 quick mute 가 발생하면 기존 timer 를 `clearTimeout` 하고 새 type 으로 banner 교체 + 5초 재시작. 가장 마지막 mute 만 되돌릴 수 있다. |
| 5초 만료 | banner 가 자동으로 사라진다. 이후에는 설정 패널(§6.6) 에서 다시 켤 수 있다. |
| unmount cleanup | 페이지 이탈 시 `useEffect` cleanup 으로 펜딩 setTimeout 정리. |
| 가드 | `quickMuting: Set<NotificationType>` 가 quick mute/undo 진행 중 type 을 추적 — 같은 type 의 연속 클릭 / mute-와-undo race 방지. `savingTypes` (개별 토글) 와는 별도 set 으로 분리. |
| accessibility | mute 버튼 `aria-label="{라벨} 알림 끄기"` + `aria-busy` + disabled, "꺼짐" 배지 `aria-label="{라벨} 알림 꺼짐"`, undo banner `role="status"` + `aria-live="polite"`, undo 버튼도 PATCH 진행 중 `aria-busy` + disabled. |

본 액션은 **사용자 preference 만 변경**한다. notification row 의 read 상태 / DB 행 삭제 / 라우팅(`pathForNotification`) 동작은 건드리지 않는다 — 이미 도착한 알림은 그대로 남고, 이후 같은 type 으로 발송되는 알림이 §6.5 의 발송 단계 필터에서 차단된다.

### 6.6.3 EVENT_ANNOUNCEMENT bundle 편입 (PR141)

신규 `EVENT_ANNOUNCEMENT` enum 은 `content` 카테고리에 들어간다 — `구독 채널의 새 이벤트 / 새 글 / 댓글 / 좋아요 / 이벤트 공지` 묶음 토글이 본 type 도 함께 켜고 끈다. UI 의 individual checkbox 는 `notificationMeta.ts` 의 `META.EVENT_ANNOUNCEMENT` 정의에서 라벨/tone 을 가져온다 (label: "이벤트 공지", tone: primary).

### 6.7 notificationMeta.ts — 메타데이터 single source (PR97)

`frontend/src/utils/notificationMeta.ts` 가 NotificationType 의 label / tone / 설명 / 라우팅 규칙을 단일 정의한다. NotificationsPage 알림 카드와 §6.6 의 알림 설정 패널이 같은 정의를 공유 — 새 enum 이 추가되면 이 파일 하나만 수정하면 두 화면에 반영된다.

- `META: Record<NotificationType, { label, tone, description? }>` — 라벨/뱃지 색/설정 패널의 보조 설명
- `getNotificationMeta/Label/Tone(type: string)` — 알 수 없는 type 은 안전 fallback (`{ label: '알림', tone: 'neutral' }`) 반환
- `pathForNotification(targetType, targetId, type, viewerRole)` — §5.5 알림 라우팅 규칙 (events / channels / tickets / creator-applications) 의 구현. NotificationsPage 와 `useEventDetailData` 등 후속 진입처가 동일 helper 를 사용한다.

### 6.8 Web Push (PR139 + PR140)

브라우저 Web Push 는 인앱 알림 row / SSE 와 같은 NotificationService 경로 위에 얹어지는 **세 번째 채널**이다. preference 가 false 인 사용자는 row / SSE / push 모두 받지 않는다 (§6.5 의 필터가 모든 채널의 공통 게이트).

#### 6.8.1 구독 저장 (PR139)

| 컴포넌트 | 책임 |
|---|---|
| `user_push_subscriptions` (V12) | endpoint TEXT + endpoint SHA-256 hex 64자 + p256dh / auth (base64url) + userAgent + enabled + last_seen_at. `UNIQUE(user_id, endpoint_hash)` — 같은 사용자가 같은 endpoint 를 다시 등록하면 credential 만 갱신. |
| `UserPushSubscription` entity | `refreshCredentials(p256dh, auth, userAgent)` / `disable()` (PR140 self-healing) / `touchSeen()`. |
| `UserPushSubscriptionRepository` | `findByUserIdInAndEnabledTrue(...)` (PR140 dispatch), `findByUserAndEndpointHash(...)` (upsert), `deleteByUserAndEndpointHash(...)` (사용자 명시 해지). |
| `PushSubscriptionService` | endpoint validation (https + 길이) → SHA-256 hash → upsert / hard delete / list. 실패는 `InvalidPushSubscriptionException` (400). |
| `PushSubscriptionController` | `POST /api/v1/push/subscriptions` (등록/갱신), `DELETE /api/v1/push/subscriptions` (해지 — body `{endpoint}`), `GET /api/v1/push/subscriptions/me` (내 디바이스 목록). |

`disable()` vs `delete`:
- `disable()` — backend self-healing. 410/404 응답을 받았을 때 PR140 `PushNotificationService` 가 호출. row 는 남는다.
- `deleteByUserAndEndpointHash` — 사용자 명시 해지. UI 가 "끄기" 를 누르면 호출. row 자체가 사라진다.

이 분리는 "사용자의 의도" 와 "운영 self-healing" 을 구별하기 위함이다 — 둘 다 hard delete 로 통일하면 잘못된 endpoint 로 인한 자동 disable 까지 사용자 의도로 오인된다.

#### 6.8.2 VAPID 키 (PR139 + PR140)

| 항목 | 값 | 비고 |
|---|---|---|
| `push.vapid.public-key` | env `PUSH_VAPID_PUBLIC_KEY` (default empty) | 클라이언트 `PushManager.subscribe({applicationServerKey})` 가 사용. 비어 있으면 frontend 가 "no-vapid-key" 폴백. |
| `push.vapid.private-key` | env `PUSH_VAPID_PRIVATE_KEY` (default empty) | 운영 환경 env var 만으로 주입. **절대 commit 금지.** 비어 있으면 backend dispatch 가 no-op. |
| `push.vapid.subject` | env `PUSH_VAPID_SUBJECT` (default `mailto:no-reply@contenido.local`) | RFC8292 VAPID `sub` claim. mailto: 또는 https URL. |
| frontend env | `VITE_PUSH_VAPID_PUBLIC_KEY` | 빌드 시 인라이닝. 비어 있으면 `BrowserPushPanel` 이 "no-vapid-key" 메시지 표시. |

backend `PushNotificationProperties.enabled` 는 publicKey + privateKey 가 모두 채워졌을 때만 true. 한쪽이라도 비면 `LibraryWebPushSender.send` 가 `WebPushSendResult.disabled()` 반환 → dispatch 흐름 자체가 no-op (로컬/CI 안전 디폴트).

#### 6.8.3 Dispatch 흐름 (PR140)

`NotificationService.notify` 가 row 저장 + SSE 발송 후 `PushNotificationService.dispatch(notifications)` 를 호출. 실패는 try-catch 로 swallow 되어 notification 트랜잭션을 깨지 않는다.

`PushNotificationService`:
1. `findByUserIdInAndEnabledTrue(receiverIds)` 로 활성 구독 묶음 조회.
2. notification 단위로 payload 생성 (`encodePayload`):
   - `{title, body, type, targetType, targetId, url, notificationId}` JSON UTF-8 bytes.
   - `url` 은 `defaultUrlFor(targetType, targetId, type)` 의 결과 — frontend `notificationMeta.pathForNotification` 과 호환되는 fallback 라우팅 (viewerRole 분기는 SW 가 받은 뒤 router 가 보정).
3. 각 구독에 `WebPushSender.send(endpoint, p256dh, auth, payload)` 호출.
4. 결과 분기 — 모두 별도 `REQUIRES_NEW` 트랜잭션으로 처리:
   - 2xx → `touchSeen()` (last_seen_at 갱신).
   - 404 / 410 → `disable()` (브라우저 unsubscribe 흔적).
   - 기타 → warn log + 다음 구독으로 진행.
   - `WebPushSendResult.disabled()` (VAPID 키 미설정) → 침묵.

`WebPushSender` 는 추상화. 운영 구현체는 `LibraryWebPushSender` (nl.martijndwars:web-push 5.1.1 + BouncyCastle 1.78.1, BC provider 는 클래스 로드 시 1회 등록). 발송 자체 예외는 라이브러리 레벨에서 잡아 `WebPushSendResult.failure(msg)` 로 변환 — 호출자가 외부 라이브러리 타입에 의존하지 않는다.

#### 6.8.4 Service worker (`frontend/public/sw.js`)

| 이벤트 | 동작 |
|---|---|
| `install` | `self.skipWaiting()` — 사용자가 첫 구독 시 reload 없이 즉시 활성화. |
| `activate` | `clients.claim()` — 모든 탭이 새 worker 를 따른다. |
| `push` | `event.data.json()` parse → `showNotification(title, {body, icon, badge, data: {url, notificationId, type, targetType, targetId}})`. JSON 이 아니면 plain text fallback. |
| `notificationclick` | `notification.close()` 후 같은 origin 의 client 가 있으면 focus + `postMessage({source: 'contenido-sw', type: 'navigate', url})`. 없으면 `clients.openWindow(url)`. |

`App.tsx` 가:
1. mount 시 `serviceWorker.register('/sw.js')` (https / localhost 만).
2. `serviceWorker.message` listener — `contenido-sw / navigate` 메시지를 router `navigate(url)` 로 연결.

#### 6.8.5 Frontend — BrowserPushPanel (PR139)

`pages/NotificationsPage.tsx` 알림 설정 패널 안에 `components/BrowserPushPanel.tsx` 가 들어간다. 상태는 세 가지:
- `unsupported` — SW / PushManager 없음. 안내 텍스트만.
- `no-vapid-key` — env 가 비어 있음. "잠시 후 다시 시도" 안내.
- `supported` — 토글 가능. 권한이 `denied` 면 브라우저 설정에서 풀어달라는 안내 + 등록 버튼 disabled.

API helpers (`frontend/src/api/push.ts`):
- `registerPushSubscription()` — 권한 요청 → SW 등록 → `pushManager.subscribe({userVisibleOnly, applicationServerKey})` → `POST /push/subscriptions`. 이미 같은 endpoint 가 있으면 backend 가 credential 만 갱신.
- `unregisterPushSubscription()` — 현재 PushManager 구독을 찾아 `DELETE /push/subscriptions` body `{endpoint}` 호출 + 브라우저 측 `subscription.unsubscribe()`.
- `getActivePushSubscription()` — 현재 브라우저가 PushManager 에 구독돼 있는지 (UI 가 토글 초기 상태에 사용).
- `getMyPushSubscriptions()` — 내 활성/비활성 구독 디바이스 목록.

### 6.9 Event announcements (PR141)

이벤트 owner / 채널 STAFF / ADMIN 이 이벤트의 **활성 참가자**에게 공지를 보내면 in-app row + SSE + Web Push 가 한 번에 발송된다.

| 컴포넌트 | 책임 |
|---|---|
| `event_announcements` (V13) | id PK / event_id FK / author_id FK / title VARCHAR(200) / content TEXT / created_at + updated_at. INDEX `(event_id, created_at)`. |
| `EventAnnouncement` entity | title / content / author + audit timestamps. |
| `EventAnnouncementRepository` | `findByEventOrderByCreatedAtDesc(event)` / `countByEvent(event)`. |
| `EventAnnouncementService` | create (가드 + notify) / list (가드). |
| `EventAnnouncementController` | `POST/GET /api/v1/events/{eventId}/announcements`. |

권한:
- 작성 — 채널 owner, 채널 STAFF, ADMIN. 그 외 → `UnauthorizedException` (403).
- 조회 — 작성 권한자 + 해당 이벤트의 APPROVED 참가자. 그 외 → 403.

수신자 (active 참가자) 계산:
1. `participationRepository.findByEventOrderByJoinedAtDesc(event)` 에서 `status == APPROVED` 만.
2. **무료 이벤트** (`participationFee <= 0`) → 위 그대로 수신자.
3. **유료 이벤트** → 위 buyer 들의 최근 ticket 을 묶음 조회. `CANCELED / REFUNDED` 제외, `PAID / USED / PARTIALLY_REFUNDED` 포함. 유료인데 티켓이 없는 APPROVED 는 비정상이라 안전한 쪽으로 제외.

알림 호출:
```
notificationService.notify(
    receiverIds = activeParticipantIds(event),
    type = NotificationType.EVENT_ANNOUNCEMENT,
    title = "[공지] ${event.title}",
    message = announcement.title,
    targetType = "events",
    targetId = event.id,
)
```

NotificationService preference 필터를 통과한 수신자만 row/SSE/push 가 발송된다. notify 실패는 try-catch 로 swallow — 공지 row 자체는 트랜잭션과 함께 commit.

Frontend (`pages/event-detail/EventAnnouncementsSection.tsx`):
- `canRead` 가 true 일 때만 렌더 — 권한 없는 사용자에게는 섹션 자체가 안 보인다.
- `canWrite` 면 inline form (제목/내용) + "공지 보내기" 버튼. 발송 성공 시 목록 prepend + form 초기화.
- 목록은 created_at desc, 항목당 author nickname + 로컬 시각.

`EventDetailPage.tsx` 가 `isOwner || user?.role === 'ADMIN'` 를 `canWrite` 로, `isOwner || ADMIN || participation?.status === 'APPROVED'` 를 `canRead` 로 전달한다.

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
- `TICKET_FORCED_REFUNDED` (PR106 — ADMIN 강제 전액 환불. `targetType=null`, `afterValue` JSON 에 ticketId/paymentAttemptId/ticketStatus/amount 동봉, `reason` 은 운영 사유. PR109 부터 `AdminModerationStatsService.getActorStats` 응답의 `forcedRefundCount` 로도 집계되어 운영자 활동 카드에 별도 표시. **PR115** 부터 단건 detail (`GET /admin/moderation/audit-logs/{id}`) 응답에 `forcedRefundContext` 가 채워진다 — `afterValue` 의 ticketId 로 ticket → buyer / event / channel 을 조회 시점 lookup. 원본 audit row 의 `beforeValue`/`afterValue` 는 손대지 않고, lookup 실패 시 `contextAvailable=false` 로 fallback. list / CSV export / archive 응답은 enrichment 제외 — N+1 회피 + CSV 호환 유지)
- `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` (PR122 — 일반 사용자/owner/ADMIN 의 `POST /tickets/{id}/refund` 성공. actor 는 호출자 (buyer / channel owner / ADMIN). `targetType=null`. `beforeValue` JSON 에 ticketStatusBefore / paymentStatusBefore / refundedAmountBefore / remainingRefundableAmountBefore 동봉. `afterValue` JSON 에 ticketId / paymentAttemptId / eventId / refundAmount / refundedAmount / remainingRefundableAmount / ticketStatus / paymentStatus / fullRefund 동봉. fullRefund=true 면 `PAYMENT_REFUNDED` (누적이 결제 금액에 도달한 cascade), false 면 `PAYMENT_PARTIALLY_REFUNDED`. ADMIN forced refund 는 본 액션을 만들지 않고 `TICKET_FORCED_REFUNDED` 만 기록 — `PaymentService.refundPaymentByTicket` 와 `forceRefundByAdmin` 가 audit 기록을 분리 책임. webhook `refund.completed` 도 본 액션을 만들지 않음 (PG-driven). **PR124** 부터 `AdminModerationStatsService.getActorStats` 응답의 `partialRefundCount` / `refundCount` 필드로도 집계되어 운영자 활동 카드에 별도 행으로 노출 — `forcedRefundCount` (PR109) 와는 분리된 카운트라 일반 환불과 강제 환불 처리량을 운영자별로 따로 본다)

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
| ~~**부분 환불**~~ | **PR117 에서 일반 사용자/owner/ADMIN 환불 흐름에 도입.** `RefundTicketRequest.amount` (null → 남은 환불 가능 금액 전체) + `PaymentAttempt.refundedAmount` 누적 + `TicketStatus.PARTIALLY_REFUNDED` / `PaymentStatus.PARTIALLY_REFUNDED` enum. 누적 도달 시 기존 full cascade. ADMIN forced refund 는 여전히 한 번에 전체 환불. | payment-refund-policy §14 |
| **부분 forced refund** | ADMIN `/admin/tickets/{id}/forced-refund` 는 한 번에 남은 금액 전액만 환불 (PR117). 부분 금액 forced refund 는 별도 endpoint 또는 옵션 도입이 필요. | 향후 PR |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 환불 도입으로 더 복잡해졌으나 batch 는 그대로 미구현. | 향후 PR |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. | 향후 PR |
| **PortOne / 다른 PG 어댑터** | `PaymentGateway` interface 는 열려 있으나 구현체는 Toss + Mock 만. | 향후 PR |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수가 동시 confirm 시 초과 가능. | 향후 PR |
| **Kafka outbox** | 도입 설계만 ([kafka-outbox-plan.md](kafka-outbox-plan.md)). 현재 알림은 직접 SSE push. | 향후 PR |
| **실시간 잔여 자리 / QR 회전 / 푸시** | EventDetail 의 잔여 자리는 SSE refetch 기반. QR 30초 회전 / 푸시 / 시스템 밝기는 미구현. | 향후 PR |
| **Push / Email 채널별 preference** | preference 는 NotificationType 차원만 다룬다. 같은 type 을 SSE 만 받고 push 는 끄는 등 채널별 선택 불가. **PR140 부터 Web Push 가 SSE / in-app 와 동일한 NotificationType preference 게이트로 켜고 꺼지지만, 채널별 분리는 여전히 없음.** | 향후 PR |
| **Native FCM adapter** | Web Push 만 지원 — iOS Safari/Chrome 18.5+ 와 Android 모든 브라우저 커버. native iOS/Android 앱 → APNs/FCM 어댑터는 미구현. | 향후 PR |
| **Push delivery retry queue** | 4xx/5xx 응답은 warn log 만. expired (410/404) 만 subscription disable. 일시 실패 (network blip 등) 의 자동 재시도 / dead letter queue 없음. | 향후 PR |
| **Push analytics / open tracking** | dispatch 결과 (sent / expired / failed) 의 dashboard / open-rate / CTR 추적 없음. backend 로그만 남는다. | 향후 PR |
| **Push quiet hours** | 시간대별 발송 정지 (e.g. 23시~07시 mute) 미구현. preference disable 은 24/7. | 향후 PR |
| **Preference 변경 audit / 이력** | preference 변경은 `moderation_audit_logs` 에 기록되지 않으며 별도 이력 테이블도 없음. PR104 부터 `UserNotificationPreference.updatedAt` 의 "마지막 row 변경 시각" 만 응답에 노출 — 변경 이력 / actor / 전·후 값은 여전히 미저장. | 향후 PR |

운영 검증 미수행 / 백엔드 영향 있는 변경은 [manual-qa-checklist.md](manual-qa-checklist.md) 의 항목 단위로 추적한다. 알림 preference + 메타데이터 흐름의 수동 QA 는 §20 / §21 참고.

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
- PR91 — EventDetail 잔여 자리 라이브 강조 (highlight + reduced-motion 가드)
- PR92 — 알림 묶음 refetch coalescing (`useCoalescedRefresh`)
- PR93 — Admin moderation actor 활동 요약
- PR94 — 수동 QA 체크리스트 consolidation
- PR95 — 알림 수신 preference 영속화 + NotificationService 필터
- PR96 — NotificationsPage 알림 설정 UI
- PR97 — `notificationMeta.ts` 메타데이터 single source
- PR98 — 알림 preference 흐름 문서화 (본 문서 §6.4~6.7 + §10 Known Exclusions 갱신)
- PR99 — 알림 preference 카테고리 묶음 토글 (5 bundle + "전체 알림")
- PR100 — bundle 분류표 / 동작 정책 문서화 (§6.6 + §6.6.1) + 묶음 토글 Known Exclusion 제거
- PR101 — 알림 카드 quick mute + 5초 undo banner
- PR102 — quick mute 흐름 문서화 (§6.6.2) + PR 히스토리 갱신
- PR103 — Ship-ready release-notes-local-bundle.md 추가 (push 전 self-audit)
- PR104 — Notification preference `updatedAt` 응답 노출 + 패널 "마지막 저장 / 기본값" 표시 (lightweight signal, history 아님)
- PR105 — `updatedAt` 정책 문서화 (§6.4 응답 표 + §6.6 표시 행) + §10 Known Exclusions 보강
- PR106 — ADMIN 강제 전액 환불 도구 (USED / 시작 후 PAID, 부분 환불은 미지원) + `TICKET_FORCED_REFUNDED` audit
- PR107 — 강제 환불 흐름 문서화 ([payment-refund-policy.md §13](payment-refund-policy.md) + 본 문서 §5.2.1) + §3 / §4.4 / §8.1 / §10 Known Exclusions 갱신
- PR108 — Release notes (`docs/release-notes-local-bundle.md`) PR104~PR107 사이클로 refresh
- PR109 — Admin actor stats 응답에 `forcedRefundCount` 필드 추가 + 운영자 활동 카드 breakdown 1줄 (PR93 stats 위에 audit 데이터 그대로 활용, 신규 endpoint/마이그레이션 없음)
- PR110 — Release bundle 문서 PR104~PR109 사이클로 refresh (post-push 회고 모드)
- PR111 — `AdminPaymentToolsSection` UX polish (안내 bullet / aria-describedby / labeled result grid / role=status / 4xx-5xx 친화 카피)
- PR112 — Forced refund 실행 전 "REFUND" 텍스트 확인 잠금 (클라이언트 잠금, API payload 무변경)
- PR113 — `AdminAuditLogsSection` 에 forced refund quick filter chip + frontend `ModerationAuditAction` union 을 backend enum 과 동기화 (`AUDIT_LOGS_ARCHIVED` + `TICKET_FORCED_REFUNDED` 추가)
- PR114 — Release bundle 문서 PR110~PR113 사이클로 refresh
- PR115 — Forced refund audit detail enrichment (`ModerationAuditLogResponse.forcedRefundContext`) — 단건 detail 조회 시점에 ticket → buyer/event/channel lookup 으로 운영자가 한 화면에서 확인. list/CSV/archive 응답은 enrichment 제외 (N+1 회피 + CSV 호환)
- PR116 — Release bundle 문서 PR115 사이클로 refresh
- PR117 — Partial refund backend foundation: V11 `payment_attempts.refunded_amount` 컬럼 + `TicketStatus.PARTIALLY_REFUNDED` / `PaymentStatus.PARTIALLY_REFUNDED` enum + `PaymentAttempt` 헬퍼 (`remainingRefundableAmount` / `markPartiallyRefunded` / `markFullyRefunded`) + `RefundTicketRequest.amount` optional + 부분 환불 cascade (참가/정원 무변경, 누적 도달 시 full cascade) + `InvalidRefundAmountException` (400) + admin forced refund 도 PARTIALLY_REFUNDED 티켓의 남은 금액 cascade 지원
- PR118 — Partial refund frontend UX: `TicketDetailPage` inline refund form (전액/부분 라디오 + amount input + 사유) + `TICKET_STATUS_LABEL` "부분 환불됨" (warning tone) + MyPage `isTerminalTicket` 가드 유지 (PARTIALLY_REFUNDED 는 active) + 4xx 친화 카피 (1원 미만 / remaining 초과 / 동시 환불 race)
- PR119 — Partial refund 정책 / 구조 문서: payment-refund-policy §14 / architecture §5.2·§10·§11 / manual-qa §14
- PR120 — Partial refund regression hardening: `validatePrepareable` active statuses 에 PARTIALLY_REFUNDED 추가 (재결제 차단) + PaymentServiceTest 5 신규 케이스 + manual-qa §14 회귀 매트릭스 11 행
- PR121 — Release bundle 문서 PR117~PR120 사이클로 refresh
- PR122 — 일반 사용자 환불 audit: `PaymentService.refundPaymentByTicket` 성공 시 `PAYMENT_REFUNDED` (cascade) / `PAYMENT_PARTIALLY_REFUNDED` (partial) audit row 기록 (actor=호출자, beforeValue/afterValue JSON). `forceRefundByAdmin` 는 기존 `TICKET_FORCED_REFUNDED` 만 유지 (중복 audit 없음). frontend `ModerationAuditAction` union + label/tone/options 확장 (warning/success). PaymentServiceTest 6 신규 케이스 (partial / full / cascade / PG failure / invalid amount / forced refund no-audit)
- PR123 — Release bundle 문서 PR122 사이클로 refresh
- PR124 — `AdminModerationActorStatItem` 에 `partialRefundCount` / `refundCount` 필드 추가 + `AdminModerationStatsService.getActorStats` 가 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` 액션을 별도 카운트로 분류 + frontend `AdminModerationOverviewSection` breakdown 에 "부분 환불 N" / "환불 완료 N" 행 추가 (0 일 때 미표시). `forcedRefundCount` (PR109) 와 분리된 카운트라 일반 환불과 강제 환불 처리량을 운영자별로 따로 볼 수 있다. 신규 endpoint/마이그레이션 없음 — PR122 audit 데이터를 그대로 활용
- PR126 — User refund audit detail enrichment: `ModerationAuditLogResponse.paymentRefundContext` 필드 + `PaymentRefundAuditContextResponse` DTO (16 필드: ticketId/paymentAttemptId/eventId/refundAmount/refundedAmount/remainingRefundableAmount/ticketStatus/paymentStatus/fullRefund + buyer/event/channel lookup + contextAvailable). PR115 의 `forcedRefundContext` 패턴을 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` audit row 에 그대로 확장 — 단건 detail 조회 시점에만 ticket → buyer/event/channel lookup, list/CSV/archive 응답은 enrichment 제외 (N+1 회피). frontend `AdminAuditLogsSection` 에 `PaymentRefundContextPanel` 추가 (CSS `.ct-audit-context` 재사용). `ModerationAuditLogService.get` 의 enrich 플래그가 `enrichRefundContexts` 로 리네임 — forced/payment 두 컨텍스트 모두 같은 플래그로 켬
- PR128 — `AdminAuditLogsSection` 의 빠른 필터 chip row 에 "부분 환불" / "환불 완료" 2개 chip 추가 (PR113 "강제 환불" chip 패턴 그대로). chip 클릭은 `auditFilters.action` 을 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` 로 set/unset 하며 액션 select 와 양방향 동기화, "필터 초기화" 버튼이 한꺼번에 해제. backend / API / DB / 마이그레이션 변경 없음 — frontend 한 파일 chip 2개 추가만
- PR130 — Archive audit detail enrichment: `ArchivedModerationAuditLogResponse` 에 `forcedRefundContext` / `paymentRefundContext` optional 필드 추가 + `ModerationAuditLogArchiveService.getArchived` 가 active detail (PR115/PR126) 과 동일한 정책으로 enrichment. archive list / CSV 응답은 enrichment 미적용 (N+1 회피 + CSV 호환). `ModerationAuditLogService.buildForcedRefundContext` / `buildPaymentRefundContext` 의 가시성을 `internal` 로 노출해 archive service 가 재사용 — best-effort + lookup 실패 swallow 정책 그대로. frontend `ArchivedModerationAuditLog` type 에 두 optional context 추가 + 기존 `ForcedRefundContextPanel` / `PaymentRefundContextPanel` 을 archive 탭 detail 에서도 동일 조건부 렌더로 재사용 ("읽기 전용" Badge 는 유지)
- PR131 — Audit CSV refund-derived columns: active export (`exportToCsv`) 와 archive export (`exportArchivedToCsv`) 양쪽 헤더에 환불 분석용 10 컬럼 append-only 추가 (`refundKind` / `ticketId` / `paymentAttemptId` / `eventId` / `refundAmount` / `refundedAmount` / `remainingRefundableAmount` / `ticketStatus` / `paymentStatus` / `fullRefund`). 단일 helper `ModerationAuditLogService.csvRefundDerivedColumns(action, afterValue)` 가 두 서비스에서 공유 — afterValue JSON 파생값만 사용, ticket / buyer / event 등 lookup 호출 **금지** (CSV 는 N+1 부담을 안 진다). refundKind 은 action 기반 (`TICKET_FORCED_REFUNDED` → FORCED, `PAYMENT_PARTIALLY_REFUNDED` → PARTIAL, `PAYMENT_REFUNDED` → FULL), 그 외는 10 컬럼 모두 빈 값. malformed JSON / null afterValue 도 export 가 절대 throw 하지 않음 — 새 컬럼만 빈 값으로 떨어진다. 원본 `beforeValue` / `afterValue` 컬럼은 위치 / 값 그대로
- PR134 — Partial admin forced refund: `AdminForcedRefundRequest.amount: Long?` optional 추가 + `PaymentService.forceRefundByAdmin(adminUserId, ticketId, reason, amount = null)` 시그니처 확장. `amount == null` 이면 PR106 동작 그대로 (remaining 전액 + full cascade), 지정 시 `1 <= amount <= remaining` 검증 후 `amount == remaining` → full cascade, `amount < remaining` → PR117 `applyPartialRefund` 재사용 (ticket/attempt PARTIALLY_REFUNDED + 참가/정원 무영향). audit action 은 PR106 그대로 `TICKET_FORCED_REFUNDED` 1건만; afterValue JSON 에 `refundAmount / refundedAmount / remainingRefundableAmount / fullRefund` 4 필드 추가 (PR126 paymentRefundContext 와 같은 의미). 기존 4 필드 (ticketId/paymentAttemptId/ticketStatus/amount) 는 호환을 위해 유지 — PR115 enrichment + PR131 CSV 컬럼이 깨지지 않음. USED 티켓 + 부분 강제 환불도 허용 (ticket → PARTIALLY_REFUNDED). `AdminForcedRefundResponse` 에 `refundedAmount / remainingRefundableAmount / fullRefund` 3 필드 추가
- PR135 — Partial admin forced refund UI: `AdminPaymentToolsSection` 에 "환불 방식" 라디오 fieldset (`남은 환불 가능액 전액` / `금액 지정 (부분 환불)`) + PARTIAL 선택 시 환불 금액 input. confirm dialog 에 선택한 방식 / 금액 / cascade 영향 명시. result card 에 `누적 환불액 / 남은 환불 가능액` + 환불 유형 Badge ("전액 환불" / "부분 환불"). 400 → "환불 금액을 확인해주세요." error mapping. `forceRefundTicket(ticketId, reason, amount?)` API 함수는 amount 가 undefined 이면 body 에서 키를 제외해 PR106 backward compat 호출 경로 유지. 일반 사용자 refund UI 변경 없음
- PR139 — Web Push 구독 인프라: V12 `user_push_subscriptions` (endpoint TEXT + SHA-256 hex 64자 UNIQUE) + `UserPushSubscription` entity / repository / service / controller (`POST/DELETE/GET /api/v1/push/subscriptions`) + `push.vapid.*` placeholder yml + `InvalidPushSubscriptionException` (400). frontend `public/sw.js` (install/activate/push/notificationclick) + `api/push.ts` (register/unregister/list + permission/support detection) + `BrowserPushPanel` UI in NotificationsPage + `VITE_PUSH_VAPID_PUBLIC_KEY` env. App.tsx 가 SW 등록 + `notificationclick` 메시지를 router 로 bridge. 발송은 미구현 (PR140 에서)
- PR140 — Web Push 발송: `WebPushSender` 인터페이스 + `LibraryWebPushSender` (`nl.martijndwars:web-push:5.1.1` + `org.bouncycastle:bcprov-jdk18on:1.78.1`, BC provider 클래스 로드 시 1회 등록). `PushNotificationService.dispatch(notifications)` 가 receiver 별 active 구독 묶음 조회 → payload JSON (`title/body/type/targetType/targetId/url/notificationId`) → 각 endpoint 발송 → 결과 분기 (2xx → `touchSeen`, 410/404 → `disable()`, 그 외 → warn log). 모든 self-healing 은 `REQUIRES_NEW` 트랜잭션. NotificationService 가 row 저장 + SSE 이후 호출하며 실패를 try-catch 로 swallow — push 가 notification 트랜잭션을 깨지 않는다. VAPID 키 미설정이면 `WebPushSendResult.disabled()` 반환 → dispatch 자체가 no-op (로컬/CI 안전). `PushNotificationProperties` 가 application 에서 `@EnableConfigurationProperties` 로 활성화
- PR141 — Event announcement notifications: V13 `event_announcements` (event_id FK / author_id FK / title VARCHAR(200) / content TEXT, idx event+created) + `EventAnnouncement` entity / repository / DTOs + `EventAnnouncementService` (create / list + 권한 가드) + `EventAnnouncementController` (`POST/GET /api/v1/events/{eventId}/announcements`). `NotificationType.EVENT_ANNOUNCEMENT` 추가. 작성 권한 — owner / 채널 STAFF / ADMIN. 조회 권한 — 작성자 + APPROVED 참가자. 수신자 — APPROVED participation × (무료 또는 ticket NOT IN CANCELED/REFUNDED). frontend `EventAnnouncementsSection` 이 EventDetailPage 안에서 canWrite/canRead 분기로 form + list 렌더. notificationMeta `content` 묶음에 EVENT_ANNOUNCEMENT 편입
- PR142 — Channel new event push coverage: `EventService.createEvent` 의 NEW_EVENT 수신자 묶음에서 channel.owner.id 제외 (defensive — owner 가 자기 채널을 구독한 비정상 상태에서도 자기 알림은 안 받게) + dedupe. EventServiceTest 3 신규 케이스 — 구독자 receive / owner 본인 제외 / 빈 구독자도 notify 호출. push dispatch 자체는 PR140 NotificationService 통합 테스트로 보장 — 본 PR 은 receiver 정책만 정리

## Refund Audit Enrichment 정책 (PR115 / PR126 / PR130 / PR131 통합)

`TICKET_FORCED_REFUNDED` (PR106) 와 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` (PR122) audit row 의 운영 가독성은 다음 세 layer 로 분리되어 있다 — 각 layer 가 다른 비용 / 부하 특성을 가진다.

| 응답 경로 | enrichment 종류 | DB lookup | 비고 |
|---|---|---|---|
| Active detail `GET /admin/moderation/audit-logs/{id}` | buyer/event/channel + 세 금액 + 상태 + fullRefund | ✅ ticket → buyer/event/channel (PR115/PR126) | endpoint 호출당 row 1개. 실패 시 `contextAvailable=false` 로 fallback. |
| Archive detail `GET /admin/moderation/audit-logs/archive/{originalId}` | 동일 | ✅ 동일 (PR130) | archive endpoint 호출당 row 1개. active 와 같은 helper 재사용. |
| Active list `GET /admin/moderation/audit-logs?page=...` | 없음 | ❌ | N+1 회피 — page 당 row N 개. raw JSON 만. |
| Archive list `GET /admin/moderation/audit-logs/archive?page=...` | 없음 | ❌ | 동일. |
| Active CSV export `GET /admin/moderation/audit-logs/export` | afterValue JSON 파생 10 컬럼 (PR131) | ❌ | 최대 1000 행. JSON 파생만, lookup 절대 안 함. |
| Archive CSV export `GET /admin/moderation/audit-logs/archive/export` | 동일 (PR131) | ❌ | 동일. |

원본 audit row (`beforeValue` / `afterValue` / `reason`) 는 어느 layer 에서도 수정되지 않는다. enrichment 는 **읽기 뷰** 일 뿐이며 lookup 실패가 detail / export 자체를 깨지 않는다 (best-effort + `runCatching {...}.getOrNull()` swallow 정책).

상세 정책 변경 이력은 도메인별 세부 문서 (특히 [payment-refund-policy.md](payment-refund-policy.md)) 와 git log 를 참고.
