# Local Release Bundle — PR115

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 1 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `db91787 feat(admin): enrich forced refund audit details` |
| Ahead | **1 commit** |
| First ahead | `db91787 feat(admin): enrich forced refund audit details` (PR115) |
| 작성 시점 | 2026-05-18 |

직전 push (PR110~PR114, 5 커밋 = 결제 운영 도구 UX 보강 + audit quick filter + release bundle 문서 사이클) 가 origin 에 반영된 위에 얹은 **단독 backend+frontend PR** 한 건. PR115 는 PR113 이 quick filter 까지 닫아 둔 audit row 의 가독성을 한 단계 더 — 단건 detail 조회 시 ticket → buyer / event / channel 을 조회 시점 lookup 으로 채운다.

---

## 1. 커밋 묶음 요약

### Forced refund audit detail enrichment

| commit | PR | 요약 |
|---|---|---|
| `db91787` | PR115 | `TICKET_FORCED_REFUNDED` audit row 의 단건 detail (`GET /admin/moderation/audit-logs/{id}`) 응답에 `forcedRefundContext` 추가. `afterValue` JSON 에서 ticketId/paymentAttemptId/amount/ticketStatus 를 best-effort 파싱 + `TicketRepository.findById(ticketId)` 로 ticket → buyer / event / channel 까지 lookup. 실패 시 `contextAvailable=false` + 가능한 raw 값만 유지. list / CSV export / archive 응답은 enrichment 제외 (N+1 회피 + CSV 호환). 원본 audit row (`beforeValue`/`afterValue`/`reason`) 무변경. backend: `ModerationAuditLogResponse.forcedRefundContext` 신규 + `ForcedRefundAuditContextResponse` DTO (12 필드) + `ModerationAuditLogService` 에 `TicketRepository` 주입 + `buildForcedRefundContext` private 헬퍼 (`runCatching` 으로 swallow). frontend: `ForcedRefundAuditContext` 타입 + `AdminAuditLogsSection` 의 detail expand 안에 `ForcedRefundContextPanel` (raw JSON pretty-print 위에 표시) + `admin.css` `.ct-audit-context` / `.ct-audit-context-grid` 룰. tests: 신규 7 케이스 (완전 JSON + ticket / 부분 JSON / ticket 부재 / malformed JSON / null afterValue / non-forced-refund / list endpoint enrichment 미적용) — N+1 가드 verify 포함. docs: architecture §8.1 cross-reference + §11 PR history + manual-qa §22 PR115 9 항목 |

**본 PR 의 결과**: `/admin?tab=audit-logs` 에서 PR113 의 "강제 환불" quick filter 로 진입 → row 상세 펼침 → raw JSON pretty-print 위에 7칸 labeled grid (구매자 / 이벤트 / 채널 / 티켓 ID / 결제 시도 ID / 환불 금액 / 티켓 상태) 가 노출. ticket 이 lookup 되면 ticket.status 가 현재 상태로, 부재면 JSON snapshot 으로 fallback. 운영자가 `afterValue` JSON 의 `"ticketId": 123` 만 보고 다른 화면으로 점프할 필요가 없어졌다.

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
## main...origin/main [ahead 1]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 3. 검증 기록 (사이클 내 빌드/테스트 결과)

본 PR 은 단건이지만 **backend + frontend + tests + docs 8 파일** 을 동시에 건드린다. 그래서 좁은 단위 테스트 + 전체 backend test + frontend build 세 단계로 모두 게이트.

| 시점 | 검증 | 결과 |
|---|---|---|
| PR115 (`db91787`) | backend 좁은 `--tests *ModerationAuditLogServiceTest` | green (2m 16s) — 신규 7 케이스 모두 통과 + 기존 PR61/PR62/PR63 회귀 가드도 그대로 |
| PR115 (`db91787`) | backend 전체 `gradle test` | green (3m 10s, BUILD SUCCESSFUL) — `ModerationAuditLogService` 생성자에 `TicketRepository` 주입이 추가됐지만 Spring DI 가 자동 해결, 다른 service / controller 회귀 없음 |
| PR115 (`db91787`) | frontend `npm run build` | green (101 modules, 886ms — `tsc -b` + Vite 모두 통과) |

**마지막 frontend `npm run build` green**: PR115 (`db91787`).

**마지막 전체 backend `gradle test` green**: PR115 (`db91787`).

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고).

---

## 4. 운영 / 배포 주의사항

### Flyway 마이그레이션

**본 PR 은 새 V 마이그레이션 없음.** 신규 컬럼 / 테이블 / index 없음. 전체 마이그레이션 범위는 V1~V10 그대로 (직전 PR95 V10 이 마지막).

### Audit 원본 row 무변경 (정책)

본 PR 은 **읽기 시점 enrichment 뿐** — audit row 의 `beforeValue` / `afterValue` / `reason` 컬럼은 손대지 않는다. 다음을 보장:

- 과거에 기록된 `TICKET_FORCED_REFUNDED` row 도 신규 detail endpoint 에서 동일한 enrichment 시도 — `afterValue` JSON 만 잘 파싱되면 `ticketId` 로 lookup 진행.
- 과거 row 의 JSON shape 이 부분적이거나 결측이어도 가능한 값 (예: ticketId 만) 은 raw 그대로 노출 + `contextAvailable=false` fallback.
- audit retention / archive 정책 (PR65~PR70) 무영향 — archive 된 row 는 본 enrichment 대상 아님 (active 테이블 단건 endpoint 만 enrich).
- back-fill migration 없음 — 향후 row shape 변경이 있어도 본 PR 의 best-effort 파싱이 그대로 호환.

### CSV export 무변경

`GET /admin/moderation/audit-logs/export` 응답의 컬럼 / 행 / 인코딩 무변경. `forcedRefundContext` 는 응답 DTO 의 default null 필드라 직렬화 시 CSV builder 에 영향 없음 (CSV builder 는 명시적으로 10개 컬럼만 직렬화 — `id,createdAt,actorId,actorNickname,action,targetType,targetId,reason,beforeValue,afterValue`).

운영자가 CSV 를 외부 도구로 가공하던 흐름이 그대로 유지된다. enrichment 정보를 batch 로 받고 싶으면 후속 PR 후보.

### Archive endpoint 무변경

`GET /admin/moderation/audit-logs/archive` 와 `/archive/{originalId}` 두 endpoint 는 PR115 enrichment 대상 아님. `ArchivedModerationAuditLogResponse` 에 `forcedRefundContext` 필드 자체가 없으므로 frontend archive tab 도 panel 미노출. archive 된 row 의 ticket 은 retention 정책상 이미 오래된 데이터일 가능성이 크고 lookup 부담이 큰데, 운영 가치는 낮다 (archive 는 회고용).

### N+1 회피 정책

`buildForcedRefundContext` 는 `get(id)` 단건 호출에서만 실행. `list(...)` / `exportToCsv(...)` 는 기존 그대로 `toResponse()` (enrichForcedRefund=false) 호출. 한 페이지 20개 row 가 모두 forced refund 라도 list 응답은 ticket lookup 0회 — 운영자가 detail expand 한 row 만 추가 쿼리 발생 (보통 1~2 row).

테스트 (`list - TICKET_FORCED_REFUNDED row 가 있어도 list 응답은 forcedRefundContext null`) 가 `verify(exactly = 0) { ticketRepository.findById(any()) }` 로 N+1 가드.

### Forced refund backend 실행 정책 (PR106 그대로 — 본 PR 무변경)

본 PR 은 **PR106 의 backend 실행 정책을 일절 건드리지 않는다**. 환불 endpoint / payload / 권한 / audit 기록 / buyer 알림 / 전액 한정 / 일반 환불 경로 가드 모두 그대로. PR115 는 audit row 의 **읽기 뷰** 만 풍성하게 한다.

---

## 5. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 환불** | 전액만. `cancelAmount = attempt.amount`. PR106 의 강제 환불도 전액 한정. `Ticket.refundedAmount` 컬럼 / `TicketStatus.PARTIALLY_REFUNDED` enum 모두 미도입. payment-refund-policy.md §13.7 / §11.7. |
| **부분 환불 시 정원 cascade 정책** | 미결정 — 부분 환불 PR 진행 전 `Event.currentParticipants` 감소 여부 (감소 / 유지 / 운영자 선택) 결정 필요. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED 카운트 일치 batch 없음. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **Forced refund detail page (deep-link 회고 뷰)** | PR115 의 row 확장 panel 위에 별도 페이지로 "이 환불 한 건의 buyer 다른 결제 / 같은 event 의 다른 환불 audit / providerPaymentKey → PG 콘솔 외부 링크" 까지 확장. backend 작은 endpoint 추가 (1 ticket → 관련 audit 묶음) + frontend 새 라우트. |
| **Forced refund row 의 same-event grouping** | quick filter 결과를 event 별로 묶어 한 행사가 N건 환불됐는지 한눈에. backend는 `event_id` 기반 group by audit 필요 (audit 테이블에 event_id 가 직접 없어서 ticket → event 조인 필요). |
| **Archive audit detail 에도 enrichment** | 현재 PR115 는 active 테이블만 대상. archive row 는 retention 정책상 이미 오래된 데이터라 ticket lookup 부담은 크고 가치는 낮지만, 필요해지면 별도 PR. |
| **CSV export 에 enrichment 컬럼** | 현재 CSV 는 audit 원본 10 컬럼 그대로. 외부 도구에서 batch 로 buyer/event 정보까지 받고 싶다면 별도 export endpoint 또는 enrichment column 옵션 필요. |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 사이클의 release notes 에 있던 다음 항목은 본 PR 에서 채워졌으므로 제거됐다:

- **"Forced refund audit row 의 buyer / event title enrichment"** → PR115 가 detail endpoint 에서 buyer / event title / channel name 까지 모두 채워서 row 확장만으로 즉시 확인 가능.

---

## 6. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (PR106/PR111/PR112/PR113 기존 정책 회귀 가드)

- §13~§16 — 결제 / 환불 / 재신청 / 결제 알림 라우트 가드 (기존 정책 그대로 동작하는지 회귀 가드)
- **§22 ADMIN 강제 환불 운영 도구 — PR115 항목** — 본 PR 의 핵심:
  - 강제 환불 1건 실행 후 audit logs quick filter 진입 → row "상세" 펼침 → "강제 환불 상세" panel 노출 + buyer (닉네임/ID/이메일) / 이벤트 (제목/ID) / 채널 (이름/ID) / 티켓 ID / 결제 시도 ID / 환불 금액 (`₩25,000`) / 티켓 상태 7칸 grid 확인
  - panel 상단 ticketStatus 가 **현재 DB ticket.status** (보통 REFUNDED) 가 우선 — afterValue snapshot 보다 ticket 우선
  - ticket 삭제 시뮬레이션 / malformed JSON 시 "원본 감사 로그는 확인되지만 티켓 상세 정보를 찾을 수 없습니다." fallback + JSON 의 raw 값 (ticketId/paymentAttemptId/amount) 은 그대로 노출, buyer/event/channel 칸은 "—"
  - non-forced-refund row ("수동 숨김" 등) detail 에는 panel 노출되지 않음 (회귀 가드)
  - archive tab 의 같은 forced refund row 는 panel 노출되지 않음 (archive endpoint enrichment 제외)
  - raw `beforeValue` / `afterValue` JSON pretty-print 는 panel 과 별개로 그대로 유지 (원본 audit row 변경 없음)
  - CSV export 결과의 컬럼 / 행은 PR114 이전과 동일 — `forcedRefundContext` 미포함
  - list endpoint 응답의 각 row 에 `forcedRefundContext = null` (Network 탭 spot-check) — N+1 회피
- PR106 / PR111 / PR112 / PR113 항목은 직전 push 그대로 동작 회귀 가드

### 운영 콘솔

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (PR93 + PR109)
- §23 운영자 활동 강제 환불 카운트 (PR109) — 본 PR 무변경, 회귀 가드만

### 알림 (PR104 영향, 본 PR 무변경)

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

`./gradlew.bat test` 가 cold-start 일 때 `PermissionIntegrationTest` 등 Spring context 초기화에서 flaky 가 보일 수 있다. 같은 명령 재실행으로 회복되면 본 PR 의 변경과 무관하다고 본다 (PR74 stabilize 시리즈 기록).

본 PR 은 `ModerationAuditLogService` 의 생성자에 `TicketRepository` 가 추가됐다. Spring DI 가 자동 해결하므로 wiring 변경은 불필요하지만, 부팅 시 ApplicationContext 가 정상 로드되는지 (다른 service 에서 같은 service 를 주입받는 경우 등) cold-start full test 에서 한 번 더 확인된다.

---

## 8. 다음 사이클 (push 이후 추천)

본 PR 로 forced refund audit row 의 detail enrichment 까지 닫혔다. 다음 사이클의 후보:

1. **PR117 옵션 A — partial refund first step**: `payment-refund-policy.md §13.7 / §11.7` 의 부분 환불 1 단계. `Ticket.refundedAmount` 컬럼 + V11 migration + refund endpoint 의 optional `amount` 인자 + UI 금액 입력. **사전 정책 결정 필요**: 부분 환불 시 `Event.currentParticipants` cascade (감소 / 유지 / 운영자 선택) 및 `TicketStatus.PARTIALLY_REFUNDED` enum 도입 여부. 결제 도메인의 마지막 큰 미구현.
2. **PR117 옵션 B — Forced refund deep-link detail page**: PR115 의 row 확장 panel 을 별도 라우트 (예: `/admin/audit-logs/{id}`) 로 확장 — 같은 buyer 의 다른 결제 attempt 목록, 같은 event 의 다른 forced refund audit, providerPaymentKey → PG 콘솔 외부 링크. backend 작은 endpoint 추가 (1 ticket → 관련 audit 묶음) + frontend 새 라우트.
3. **PR117 옵션 C — Push/Email channel preference 1 단계**: NotificationType 차원 위에 `channel` 차원을 추가하는 큰 정책 결정. backend 모델 확장 필요. 정책 결정 선행.

옵션 A 는 결제 도메인의 마지막 큰 미구현 — 정책 결정 비용이 큼. 옵션 B 는 PR115 의 자연스러운 다음 한 걸음 — 같은 영역의 점진 확장. 옵션 C 는 별도 큰 사이클로 권장.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR115.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
