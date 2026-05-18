# Local Release Bundle — PR122

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 1 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `1bd94f7 feat(payment): audit user refund actions` |
| Ahead | **1 commit** |
| First ahead | `1bd94f7 feat(payment): audit user refund actions` (PR122) |
| 작성 시점 | 2026-05-18 |

직전 push (PR117~PR121, 5 커밋 = partial refund 도입 1단계 사이클) 가 origin 에 반영된 위에 얹은 **단독 backend+frontend PR 1건**. PR122 는 PR117 으로 도입한 일반 사용자/owner/ADMIN 환불 흐름 (`refundPaymentByTicket`) 에 audit log 를 추가해 부분 환불의 운영 추적성을 닫는다 — 직전 사이클의 Known follow-up "부분 환불 audit log" 가 본 PR 로 채워진다.

ADMIN 강제 환불 (`forceRefundByAdmin`) 의 audit 정책은 PR106 그대로 — `TICKET_FORCED_REFUNDED` 1건만 기록. 일반 환불과 강제 환불은 audit action 으로 명확히 분리된다.

---

## 1. 커밋 묶음 요약

### User refund audit actions

| commit | PR | 요약 |
|---|---|---|
| `1bd94f7` | PR122 | `ModerationAuditAction` enum 에 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` 2 값 추가 (backend kotlin + frontend union 양쪽 동기화) + `PaymentService` 생성자에 `ModerationAuditLogService` 주입 + `refundPaymentByTicket` 의 PG cancel 성공 분기에서 `recordUserRefundAudit` 호출 (actor=호출자, `targetType/targetId=null`, beforeValue JSON 4 필드 + afterValue JSON 9 필드 + 사용자 사유). `fullRefund=true` (cascade 발동) 면 `PAYMENT_REFUNDED`, false 면 `PAYMENT_PARTIALLY_REFUNDED`. 같은 `@Transactional` 안에서 기록 — audit 실패 시 환불 트랜잭션도 rollback (admin forced refund 와 동일). `forceRefundByAdmin` 흐름과 webhook 흐름은 audit 미기록 (`AdminPaymentService` 가 별도로 `TICKET_FORCED_REFUNDED` 기록 — 중복 방지). frontend `AdminAuditLogsSection` 의 label/tone/options 세 곳에 새 액션 매핑 (`PAYMENT_PARTIALLY_REFUNDED` → "부분 환불" warning / `PAYMENT_REFUNDED` → "환불 완료" success). PaymentServiceTest 6 신규 케이스 (partial 성공 / full 성공 / cascade chain / PG failure / invalid amount / forced refund no-audit). docs: payment-refund-policy §15 신규 + §14.6 의 "partial refund 이력 audit" Known exclusion 제거 + architecture §3 / §5.2 / §8.1 / §11 갱신 + manual-qa §14 PR122 11 항목 |

**본 PR 의 결과**: 운영자가 `/admin?tab=audit-logs` 에서 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` quick filter 또는 select 옵션으로 사용자 환불 이력을 추적할 수 있다. row 상세 펼침 시 raw before/after JSON 으로 ticketId / paymentAttemptId / eventId / refundAmount / refundedAmount / remainingRefundableAmount / ticketStatus / paymentStatus / fullRefund 9 필드 확인. ADMIN 의 일반 환불은 PAYMENT_(PARTIALLY_)REFUNDED 로 기록, ADMIN 강제 환불 (`/admin/tickets/{id}/forced-refund`) 만 `TICKET_FORCED_REFUNDED` 로 기록 — 같은 ADMIN 이라도 endpoint 가 audit action 을 결정.

---

## 2. Push 전 확인사항

### 스테이징 금지 파일

다음 파일들은 **절대 stage / commit 하지 않는다** — 사이클 내내 합의된 제외 목록:

- `.claude/settings.local.json`
- `.claude/scheduled_tasks.lock`
- `build/resources/main/application.yml`
- `.gradle/**`
- `build/**`
- `frontend/dist/**`
- `*.tsbuildinfo`

push 직전 `git status -sb` 로 위 파일들이 staged 영역에 들어가 있지 않은지 한 번 더 확인한다. 위 파일들은 작업 트리에 modified/untracked 로 남아 있어도 정상.

### 최종 git 상태 (push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead 2]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

PR123 (본 release-notes 갱신) 까지 포함하면 2 ahead. 위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 3. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR122 (`1bd94f7`) | backend 좁은 `--tests *PaymentServiceTest` | green (42s) — 신규 6 케이스 (partial 성공 → `PAYMENT_PARTIALLY_REFUNDED` + actor=buyer + after JSON / full 성공 → `PAYMENT_REFUNDED` + fullRefund=true / partial 후 cascade chain — 마지막 호출만 `PAYMENT_REFUNDED` / PG failure → audit 미기록 + rollback / `InvalidRefundAmountException` → audit 미기록 + gateway 미호출 / `forceRefundByAdmin` 호출 시 `PaymentService` 가 audit 미기록 — `AdminPaymentService` 의 `TICKET_FORCED_REFUNDED` 와 중복 방지) + 기존 PR42 / PR43 / PR78 / PR81 / PR82 / PR106 / PR117 / PR120 회귀 가드 통과 |
| PR122 (`1bd94f7`) | backend full `gradle test` | green (1m 59s, BUILD SUCCESSFUL) — `PaymentService` 생성자에 `ModerationAuditLogService` 주입 추가에 대해 Spring DI 가 정상 해결, 다른 service / controller / integration test 회귀 없음. AdminPaymentService 는 PaymentService 를 mock 으로 받으므로 직접 영향 없음 |
| PR122 (`1bd94f7`) | frontend `npm run build` | green (101 modules, 838ms — `tsc -b` + Vite 모두 통과). `Record<ModerationAuditAction, ...>` exhaustiveness 가 새 enum 값 2개에 대해 label / tone / options 세 맵 모두 정상 인식 |
| PR123 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR122 (`1bd94f7`).

**마지막 전체 backend `gradle test` green**: PR122 (`1bd94f7`).

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고). 본 사이클의 PR122 검증 시점에는 cache lock 이슈가 발생하지 않음.

---

## 4. 운영 / 배포 주의사항

### Flyway 마이그레이션 — 없음

PR122 는 **새 V 마이그레이션 없음**. enum 값 2개 (`PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED`) 는 Kotlin enum 추가만으로 처리되고, `moderation_audit_logs` 의 `action` 컬럼은 `VARCHAR(50)` 이라 새 값 (`PAYMENT_PARTIALLY_REFUNDED` = 26자) 을 그대로 수용 가능. 전체 마이그레이션 범위는 V1~V11 그대로 (직전 PR117 V11 이 마지막).

### 환불 정책 / 상태 전이 — 변경 없음

PR122 는 **PR117 의 환불 정책을 일절 건드리지 않는다**. 즉:

- `refundPaymentByTicket` 의 amount 가드 / cascade 조건 / participation·정원 보존 정책 / notification 카피 — 모두 PR117 그대로.
- `forceRefundByAdmin` 의 정책 (PARTIALLY_REFUNDED 받아 remaining 전체 환불) — PR117 그대로.
- webhook `refund.completed` 처리 흐름 — PR42 그대로.
- 모든 거부 조건 (`TicketAlreadyUsedException` / `RefundDeadlinePassedException` / `InvalidRefundAmountException` / `RefundFailedException`) — 변경 없음.

본 PR 의 추가는 **audit log 1건 기록** 만이다. PG 호출 / DB 전이 / 알림 발송은 그대로.

### Audit 기록 정책 (PR122 신규)

- **트리거**: `refundPaymentByTicket` 의 PG cancel 성공 + DB 상태 전이 완료 직후. `@Transactional` 안.
- **action**:
  - 누적 환불액이 결제 금액에 도달 (cascade) → `PAYMENT_REFUNDED`
  - 누적 환불액 < 결제 금액 (partial) → `PAYMENT_PARTIALLY_REFUNDED`
- **actor**: 호출자 (`actorId`) — buyer / channel owner / ADMIN 중 누구든 호출자가 audit actor.
- **target**: `targetType=null`, `targetId=null`. `ReportTargetType` 에 TICKET 이 없음 (admin forced refund 와 동일 패턴).
- **beforeValue JSON** (cascade 전 snapshot — 호출 직전 remaining 과 함께 저장):
  - `ticketStatusBefore` / `paymentStatusBefore` / `refundedAmountBefore` / `remainingRefundableAmountBefore`
- **afterValue JSON** (환불 후 결과):
  - `ticketId` / `paymentAttemptId` / `eventId` / `refundAmount` (이번 호출 금액) / `refundedAmount` (누적) / `remainingRefundableAmount` / `ticketStatus` / `paymentStatus` / `fullRefund` (boolean)
- **reason**: `refundPaymentByTicket` 가 service 진입 시 trim + `USER_REQUEST` default 처리한 값.

### Forced refund audit 와의 분리

| 흐름 | endpoint | audit action | actor |
|---|---|---|---|
| 일반 환불 (PR122) | `POST /tickets/{id}/refund` | `PAYMENT_REFUNDED` / `PAYMENT_PARTIALLY_REFUNDED` | 호출자 (buyer / owner / ADMIN) |
| ADMIN 강제 환불 (PR106) | `POST /admin/tickets/{id}/forced-refund` | `TICKET_FORCED_REFUNDED` | ADMIN |

**같은 ADMIN 이라도 endpoint 가 audit action 을 결정.** ADMIN 이 `/tickets/{id}/refund` 로 일반 경로를 호출하면 `PAYMENT_REFUNDED` 가 기록되고 (deadline / USED 가드 적용), `/admin/tickets/{id}/forced-refund` 로 강제 환불하면 `TICKET_FORCED_REFUNDED` 가 기록된다 (deadline / USED 우회).

`forceRefundByAdmin` 흐름은 `PaymentService` 가 audit 을 만들지 않도록 분리 — `AdminPaymentService.forceRefund` 가 단독으로 `TICKET_FORCED_REFUNDED` 1건만 기록한다. 중복 audit 없음을 PaymentServiceTest 의 회귀 가드 케이스가 명시적으로 검증.

### Webhook `refund.completed` — audit 없음

PG-driven `refund.completed` webhook 처리 흐름 (`handleRefundedWebhook`) 은 audit 을 만들지 않는다. 명시적 actor 가 없는 자동 처리라 audit 의 의미가 약하고, 운영 흐름은 일반 환불 endpoint 또는 admin forced refund 로 우선 진행된다는 가정이다. webhook 만으로 환불되는 케이스는 webhook 로그를 통해 별도 추적.

### Audit 실패 시 rollback

`ModerationAuditLogService.record` 는 그 자체로 `@Transactional` 이지만 기본 propagation 으로 호출자의 트랜잭션에 join 한다. `recordUserRefundAudit` 가 어떤 이유로 예외를 던지면 (예: actor 조회 실패) `refundPaymentByTicket` 의 `@Transactional` 트랜잭션이 rollback 되어 환불도 취소된다 — admin forced refund (PR106) 와 같은 정책. 운영 추적성을 결제 일관성보다 우선시.

### Frontend label / tone (PR122)

- `AdminAuditLogsSection` 의 3 맵 모두 새 액션 매핑:
  - `AUDIT_ACTION_LABEL`: "부분 환불" / "환불 완료"
  - `AUDIT_ACTION_TONE`: warning / success
  - `AUDIT_ACTION_OPTIONS`: select dropdown 에 두 옵션 추가 (`TICKET_FORCED_REFUNDED` 와 `AUDIT_LOGS_ARCHIVED` 사이)
- quick filter 칩은 본 PR 에서 신설하지 않음 — PR113 의 "강제 환불" chip 만 유지. 사용자 환불용 chip 은 후속 PR 후보.
- detail enrichment panel (`ForcedRefundContextPanel`, PR115) 은 `TICKET_FORCED_REFUNDED` row 에만 적용 — `PAYMENT_REFUNDED` / `PAYMENT_PARTIALLY_REFUNDED` row 는 raw JSON pretty-print 만 표시. enrichment 확장은 후속 PR.

---

## 5. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 forced refund** | ADMIN `/admin/tickets/{id}/forced-refund` 는 PR117 부터 PARTIALLY_REFUNDED 티켓도 받지만 항상 한 번에 remaining 전체를 환불 (cascade). 부분 금액 강제 환불은 별도 endpoint 또는 옵션 도입 필요. PR120 의 회귀 테스트가 "remaining 전액 cascade" 동작을 명시적으로 가드. |
| **Admin actor stats 의 환불 카운트** | PR93/PR109 의 `forcedRefundCount` 패턴처럼 `userRefundCount` / `partialRefundCount` / `fullRefundCount` 등 별도 컬럼은 미신설. PR122 의 audit 데이터는 `totalActionCount` 에만 합산. 운영자별 일반 환불 처리 건수가 필요하면 후속 PR. |
| **`PAYMENT_*` audit detail enrichment** | PR115 의 `ForcedRefundContextResponse` 패턴 (ticket → buyer / event / channel lookup) 을 사용자 환불 audit 까지 확장할 수 있음. 본 PR 은 raw JSON pretty-print 만 — 운영자가 ticketId 만으로 다른 화면 점프 필요. 후속 PR 후보. |
| **PAYMENT_REFUNDED quick filter chip** | PR113 의 "강제 환불" chip 패턴을 새 액션에 확장하지 않음. 운영자가 select dropdown 으로 필터 가능. chip 추가는 후속 PR. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 환불 + audit 도입으로 정합성 위험이 커진 만큼 운영 안정성에 가치 큼. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **부분 환불 동시 race** | 별도 lock 없음. 후순위 호출이 400 으로 거부될 뿐 — race 빈도 낮아 의도적으로 lock 도입 보류. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **부분 환불 webhook** | PG 가 partial cancel webhook 을 보낼 가능성은 본 PR 범위 밖. `PaymentStatus.PARTIALLY_REFUNDED` webhook 입력은 무시. |
| **Webhook 환불 audit** | `refund.completed` webhook 흐름은 audit 미기록 그대로 (PG-driven, actor 없음). 자동 환불 추적이 필요해지면 별도 PR. |
| **CSV export 의 partial 정보** | CSV 는 audit 원본 10 컬럼 그대로. 새 액션도 동일 컬럼 구조로 직렬화 — 부분 환불 누적 보기를 batch 로 받고 싶다면 별도 export endpoint 필요. |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 사이클의 release notes 에 있던 다음 항목은 본 사이클에서 채워졌으므로 제거됐다:

- **"부분 환불 audit log"** → PR122 으로 구현. 일반 사용자/owner/ADMIN 의 `refundPaymentByTicket` 성공 시 `PAYMENT_REFUNDED` / `PAYMENT_PARTIALLY_REFUNDED` audit row 1건 기록. webhook 흐름과 ADMIN forced refund 는 정책상 제외.

---

## 6. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (본 묶음 영향)

- §13 결제 플로우 (PR74) — 회귀 가드
- **§14 환불 플로우** — 본 묶음의 핵심:
  - PR42 / PR117 / PR118 / PR120 기존 항목 (회귀 가드)
  - **§14 PR122 사용자 환불 audit 11 항목 (📋 7 + 🖱 3 + 회귀 1)** — 본 사이클의 신규:
    - buyer 본인 전액 환불 → `PAYMENT_REFUNDED` row + actor=buyer + after JSON 9 필드 + before JSON 4 필드
    - buyer 본인 부분 환불 → `PAYMENT_PARTIALLY_REFUNDED` row + `fullRefund=false`
    - 부분 2회 → 누적 도달 시 마지막만 `PAYMENT_REFUNDED`
    - PG gateway 거부 → audit row 미생성 (rollback)
    - amount 위반 → audit 미생성 + gateway 미호출
    - 채널 owner / ADMIN 일반 환불 호출 → actor 는 호출자, action 은 `PAYMENT_(PARTIALLY_)REFUNDED` (`TICKET_FORCED_REFUNDED` 아님)
    - ADMIN 강제 환불 → 여전히 `TICKET_FORCED_REFUNDED` 1건만, `PAYMENT_REFUNDED` 중복 row 없음
    - webhook `refund.completed` → audit row 미생성
    - `/admin?tab=audit-logs` 의 select / Badge 에 "부분 환불" (warning) / "환불 완료" (success) 라벨 노출
    - select 필터로 새 액션만 조회 가능
    - row 상세 펼침 → raw JSON pretty-print 만 (enrichment panel 없음, PR122 범위 밖)
- **§14 PR120 회귀 매트릭스 (11 행)** — partial refund 정책 회귀 가드. 행마다 1회 spot-check
- §15 결제·환불·재신청 정합성 (PR76 / PR78 / PR79) — 회귀
- §16 재신청 / 결제 알림 라우트 가드 — 회귀
- **§22 ADMIN 강제 환불** (PR106/PR111/PR112/PR113) — 회귀:
  - **PR122 가 forced refund audit 정책을 변경하지 않았는지** 명시 확인: forced refund 호출 후 `moderation_audit_logs` 에 `TICKET_FORCED_REFUNDED` row 가 정확히 1건만 생성되고 `PAYMENT_REFUNDED` 중복 row 가 없음
  - PR117 의 PARTIALLY_REFUNDED 티켓 forced refund 처리 — 회귀

### 운영 콘솔

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (PR93 + PR109) — 회귀. PR122 의 새 액션이 `totalActionCount` 에 합산되어 표시되는지 spot-check (별도 breakdown 행은 본 PR 에서 추가하지 않음 — 후속 PR 후보)
- §23 운영자 활동 강제 환불 카운트 (PR109) — 회귀. PR122 의 일반 환불은 `forcedRefundCount` 에 영향을 주지 않아야 함 (action 분리)

### 알림 (PR104 영향, 본 묶음 무변경)

- §20 알림 수신 설정 / §20a 묶음 토글 / §20b Quick Mute + Undo / §20c 마지막 저장 시각 / §21 알림 메타데이터 일관성

🖱 / 📋 라벨 의미는 manual QA 문서 상단 "본 문서 사용법" 참고.

---

## 7. Push 전 권장 명령

```bash
# 1) 최종 상태 확인
git -C C:/WOYA status -sb
git -C C:/WOYA log --oneline origin/main..HEAD

# 2) 풀 빌드 + 테스트 (CI 가 어쨌든 다시 돌리지만 cold-start 게이트)
cd C:/WOYA && ./gradlew.bat test
cd C:/WOYA/frontend && npm run build

# 3) 위 모두 green 이면 push (사용자가 직접 실행)
# git -C C:/WOYA push origin main
```

`./gradlew.bat test` 가 cold-start 일 때 `PermissionIntegrationTest` 등 Spring context 초기화에서 flaky 가 보일 수 있다. 같은 명령 재실행으로 회복되면 본 묶음의 변경과 무관하다고 본다 (PR74 stabilize 시리즈 기록).

본 묶음은 새 migration 이 없다 (V11 까지 그대로). `PaymentService` 생성자에 `ModerationAuditLogService` 가 추가됐지만 Spring DI 가 자동 해결하므로 wiring 변경은 불필요. cold-start full test 가 통과하면 안전.

---

## 8. 다음 사이클 (push 이후 추천)

본 묶음으로 부분 환불 사이클 (PR117~PR122) 이 모두 닫혔다. 다음 사이클의 후보:

1. **PR124 옵션 A — 부분 forced refund**: ADMIN 의 `/admin/tickets/{id}/forced-refund` 에 optional `amount` 추가 (현재는 한 번에 remaining 전체). 운영자가 노쇼 보상의 일부만 돌려주는 케이스에 사용. backend + frontend + audit 의 amount 의미 재정의. 작은~중간 backend PR + frontend UI.
2. **PR124 옵션 B — Admin actor stats 의 환불 카운트 추가**: PR122 audit 데이터를 활용해 `AdminModerationActorStatItem` 에 `partialRefundCount` / `fullRefundCount` 필드 추가 + 운영자 활동 카드 breakdown 행. PR93/PR109 패턴. 작은 backend PR + frontend 라벨.
3. **PR124 옵션 C — `PAYMENT_*` audit detail enrichment**: PR115 의 `ForcedRefundContextResponse` 패턴 (ticket → buyer / event / channel lookup) 을 사용자 환불 audit 까지 확장. detail endpoint 에서만 enrichment (N+1 회피). frontend panel 표시.
4. **PR124 옵션 D — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 REFUNDED/PARTIALLY_REFUNDED 카운트 합산과 일치 검증. 부분 환불 + audit 도입으로 정합성 위험이 커진 만큼 운영 안정성에 가치 큼. 큰 backend PR.

옵션 A 는 ADMIN 운영 도구의 확장 (사용자 흐름과 같은 partial 지원). 옵션 B 는 운영자 가시성 (PR122 audit 의 자연스러운 활용). 옵션 C 는 row 가독성 한 단계 더. 옵션 D 는 안정성. 옵션 B 가 본 사이클의 자연스러운 다음 한 걸음 — audit 데이터가 이미 누적되니 카운트만 추가하면 운영 가치 즉시 발생.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR122.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
