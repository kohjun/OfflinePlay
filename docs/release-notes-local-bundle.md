# Local Release Bundle — PR122 to PR126

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 5 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `087ed8d feat(admin): enrich user refund audit details` |
| Ahead | **5 commits** |
| First ahead | `1bd94f7 feat(payment): audit user refund actions` (PR122) |
| 작성 시점 | 2026-05-19 |

직전 push (PR117~PR121, 5 커밋 = partial refund 도입 1단계) 가 origin 에 반영된 위에 얹은 **사용자 환불 audit + 운영자 활동 카운트 + 환불 audit 상세 enrichment 사이클** 한 묶음. PR122 가 일반 사용자/owner/ADMIN 환불 흐름에 audit log 를 추가해 운영 추적성을 확보했고, PR124 가 그 audit 데이터를 `AdminModerationStatsService.getActorStats` 의 actor 별 breakdown 으로 즉시 활용했으며, **PR126 이 PR115 의 forced refund detail enrichment 패턴을 그대로 일반 사용자 환불 audit 까지 확장** 해 운영자가 raw JSON 을 직접 읽지 않고도 buyer / event / channel / 세 금액 / 환불 유형을 한 panel 에서 본다. PR123 / PR125 는 각 사이클 직후의 release bundle 갱신.

`forcedRefundCount` (PR109) 와 `partialRefundCount` / `refundCount` (PR124) 는 audit action 단위로 분리된 카운트라 일반 환불과 강제 환불 처리량을 운영자별로 따로 볼 수 있다. 같은 ADMIN 이라도 endpoint 가 audit action 을 결정하므로 (일반 `/tickets/{id}/refund` vs 강제 `/admin/tickets/{id}/forced-refund`) 카운트도 분리된다. PR126 detail enrichment 는 두 audit context (`forcedRefundContext` PR115 / `paymentRefundContext` PR126) 가 **상호 배타** — 한 row 가 둘 다 채워지는 일은 없다.

---

## 1. 커밋 묶음 요약

### User refund audit + actor stats + detail enrichment

| commit | PR | 요약 |
|---|---|---|
| `1bd94f7` | PR122 | **User refund audit actions**. `ModerationAuditAction` enum 에 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` 2 값 추가 (backend kotlin + frontend union 동기화) + `PaymentService` 생성자에 `ModerationAuditLogService` 주입 + `refundPaymentByTicket` 의 success 분기에서 `recordUserRefundAudit` 호출 (actor=호출자, `targetType/targetId=null`, beforeValue JSON 4 필드 + afterValue JSON 9 필드 + 사용자 사유). `fullRefund=true` 면 `PAYMENT_REFUNDED`, false 면 `PAYMENT_PARTIALLY_REFUNDED`. 같은 `@Transactional` 안 — audit 실패 시 환불 rollback. `forceRefundByAdmin` 흐름과 webhook 흐름은 audit 미기록 — `AdminPaymentService` 가 별도로 `TICKET_FORCED_REFUNDED` 만 기록 (중복 방지). frontend `AdminAuditLogsSection` label/tone/options 3곳 매핑. PaymentServiceTest 6 신규 케이스. |
| `750247d` | PR123 | **Release bundle 문서 refresh** (PR122 사이클 클로저). PR121 의 PR117~PR120 release-notes 를 PR122 단독 PR 로 재작성 + Known follow-ups 갱신 ("부분 환불 audit log" 제거). docs only. |
| `54d0c89` | PR124 | **Admin actor stats — user refund counts**. `AdminModerationActorStatItem` 에 `partialRefundCount: Long` + `refundCount: Long` 두 필드 추가 + `AdminModerationStatsService.getActorStats` 에 `count(PAYMENT_PARTIALLY_REFUNDED)` / `count(PAYMENT_REFUNDED)` 두 줄 추가. PR93/PR109 와 동일 패턴. frontend `AdminModerationActorStatItem` type + `AdminModerationOverviewSection` breakdown 에 "부분 환불 N" / "환불 완료 N" 행 2개 추가 (0 일 때 미표시, 순서: 신고 처리 → 부분 환불 → 환불 완료 → 강제 환불 → 임계치 → 아카이브). 신규 endpoint / 마이그레이션 없음 — PR122 audit 데이터 그대로 활용. AdminModerationStatsServiceTest 의 기존 "action 분류" 케이스에 `partialRefundCount=0` / `refundCount=0` zero assertion 추가 + 신규 케이스 1건 (PAYMENT_PARTIALLY_REFUNDED 2 + PAYMENT_REFUNDED 1 + TICKET_FORCED_REFUNDED 1 → partialRefundCount=2 / refundCount=1 / forcedRefundCount=1 / totalActionCount=4 + 다른 카운트 모두 0). |
| `f0f659a` | PR125 | **Release bundle 문서 refresh** (PR124 까지 사이클 클로저). PR123 의 PR122 단독 release-notes 를 PR122~PR124 묶음으로 재작성 + Known follow-ups 갱신 ("Admin actor stats 의 환불 카운트" 제거). docs only. |
| `087ed8d` | PR126 | **User refund audit detail enrichment**. `ModerationAuditLogResponse` 에 `paymentRefundContext: PaymentRefundAuditContextResponse?` 필드 추가 + 16 필드 DTO (`ticketId / paymentAttemptId / eventId / refundAmount / refundedAmount / remainingRefundableAmount / ticketStatus / paymentStatus / fullRefund` 9 JSON 파싱 + `buyerId / buyerNickname / buyerEmail / eventTitle / channelId / channelName` 6 lookup + `contextAvailable`). `ModerationAuditLogService.get(id)` 의 enrich 플래그를 `enrichForcedRefund` → `enrichRefundContexts` 로 리네임 — PR115 forced refund context 와 같은 플래그가 PR126 payment refund context 도 토글. 두 context 는 상호 배타. enrichment 는 **단건 detail endpoint 에서만** — list / CSV / archive 응답에는 채워지지 않음 (N+1 회피 + CSV 호환 유지). throw 하지 않는 best-effort 정책 — afterValue null / blank / 비-object / 파싱 실패 → contextAvailable=false + 빈 필드. ticket lookup 실패 시 JSON 값은 그대로 두고 contextAvailable=false. frontend `PaymentRefundAuditContext` type + `PaymentRefundContextPanel` 컴포넌트 (PR115 의 `ForcedRefundContextPanel` 과 시각/접근성 패턴 동일, `.ct-audit-context` / `.ct-audit-context-grid` CSS 재사용, 11 칸 grid). 조건부 렌더 `display.action === 'PAYMENT_PARTIALLY_REFUNDED' || display.action === 'PAYMENT_REFUNDED'`. ModerationAuditLogServiceTest 8 신규 케이스 — happy partial / full cascade / ticket missing / malformed JSON / null afterValue / non-payment action null / forced refund row 는 paymentRefundContext null / list endpoint 에서 lookup 없음. 신규 endpoint / 마이그레이션 / audit 기록 정책 / CSV 변경 없음. |

**본 사이클의 결과**: 운영자가 `/admin?tab=overview` 의 운영자 활동 카드에서 본인의 환불 처리량을 **세 분류로 분리된 채** 한눈에 확인하고 (PR124), `/admin?tab=audit-logs` 단건 detail 펼침에서 **두 종류의 환불 audit row (forced / user)** 모두 buyer/event/channel + 세 금액 + 환불 유형 + 상태를 한 panel 에서 본다 (PR115 + PR126). raw JSON pretty-print 영역은 panel 과 별개로 그대로 유지 — 원본 audit row 는 손대지 않는다.

---

## 2. PR126 운영 가치 — 두 audit context 의 분리 + raw JSON 의존 종료

PR115 의 `forcedRefundContext` 는 `TICKET_FORCED_REFUNDED` row 한 종류에만 채워졌다. PR126 이전엔 일반 사용자 환불 audit (PR122) row 의 detail 펼침이 raw JSON pretty-print 만 제공해 운영자가 ticketId 만으로 buyer/event/channel 을 다른 화면에서 직접 조회해야 했다. PR126 이 같은 패턴을 일반 환불에 그대로 확장한다.

| context 필드 | 채워지는 action | 도입 PR | 형식 차이 |
|---|---|---|---|
| `forcedRefundContext` | `TICKET_FORCED_REFUNDED` | PR115 | 단일 금액 (`amount`) |
| `paymentRefundContext` | `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` | PR126 | 세 금액 (`refundAmount` 이번 호출 / `refundedAmount` 누적 / `remainingRefundableAmount` 남은 한도) + `paymentStatus` + `fullRefund` 플래그 |

**두 context 는 상호 배타.** 한 audit row 는 정확히 한 action 만 가지므로, `forcedRefundContext` 와 `paymentRefundContext` 가 동시에 채워지는 row 는 없다. PR126 신규 테스트 `TICKET_FORCED_REFUNDED row 는 forcedRefundContext 만 채워지고 paymentRefundContext 는 null` 이 이 분리를 명시적으로 가드.

**enrichment 는 단건 detail 에만.** list endpoint (`GET /admin/moderation/audit-logs?page=...`) 와 CSV export (`GET /admin/moderation/audit-logs/export`) 응답은 `paymentRefundContext = null`. archive 단건/리스트도 enrichment 미적용. ticket lookup 의 N+1 부담을 피하고 CSV 컬럼 구조의 backward compatibility 도 유지.

**lookup 실패는 detail 자체를 깨트리지 않는다.** ticket 삭제 / JSON malformed / null afterValue 모든 경우에 endpoint 는 200 응답 + `contextAvailable=false` + UI 의 fallback 카피 ("원본 감사 로그는 확인되지만 티켓 상세 정보를 찾을 수 없습니다."). JSON 에서 추출된 값은 그대로 노출 (운영자가 ticketId 만으로도 사람에게 물어볼 수 있게).

---

## 3. Push 전 확인사항

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
## main...origin/main [ahead 6]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

PR127 (본 release-notes 갱신) 까지 포함하면 6 ahead. 위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 4. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR122 (`1bd94f7`) | backend 좁은 `--tests *PaymentServiceTest` | green (42s) — 신규 6 케이스 (partial / full / cascade chain / PG failure / invalid amount / forced refund no-audit) + 기존 회귀 가드 통과 |
| PR122 (`1bd94f7`) | backend full `gradle test` | green (1m 59s, BUILD SUCCESSFUL) — `PaymentService` DI 확장 (`ModerationAuditLogService`) 회귀 없음 |
| PR122 (`1bd94f7`) | frontend `npm run build` | green (101 modules, 838ms) — `Record<ModerationAuditAction, ...>` exhaustiveness 가 새 enum 값 2개에 대해 label / tone / options 세 맵 모두 정상 인식 |
| PR123 (`750247d`) | docs-only | build/test 생략 |
| PR124 (`54d0c89`) | backend 좁은 `--tests *AdminModerationStatsServiceTest` | green (1m 21s) — 신규 1 케이스 (PAYMENT_PARTIALLY_REFUNDED 2 + PAYMENT_REFUNDED 1 + TICKET_FORCED_REFUNDED 1 → partialRefundCount=2/refundCount=1/forcedRefundCount=1/totalActionCount=4/다른 카운트=0) + 기존 "action 분류" 케이스에 `partialRefundCount=0` / `refundCount=0` zero assertion 추가 + 기존 PR109 forcedRefundCount 케이스 유지 |
| PR124 (`54d0c89`) | backend full `gradle test` | green (1m 41s, BUILD SUCCESSFUL) — DTO 2 필드 추가에 대해 모든 service / controller / 직렬화 회귀 없음 |
| PR124 (`54d0c89`) | frontend `npm run build` | green (101 modules, 839ms — `tsc -b` + Vite 모두 통과). `AdminModerationActorStatItem` interface 확장에 모든 호출처 정상 인식 |
| PR125 (`f0f659a`) | docs-only | build/test 생략 |
| PR126 (`087ed8d`) | backend 좁은 `--tests *ModerationAuditLogServiceTest` | green — 신규 8 케이스 (happy partial / full cascade fullRefund=true / ticket missing → contextAvailable=false + JSON 값 유지 / malformed JSON → contextAvailable=false + ticket lookup 없음 / null afterValue → contextAvailable=false / non-payment action → paymentRefundContext null / forced refund row → forcedRefundContext 만 채워지고 paymentRefundContext null / list endpoint → paymentRefundContext null + ticket lookup 없음) + 기존 PR115 forced refund 7 케이스 회귀 통과 |
| PR126 (`087ed8d`) | backend full `gradle test` | green — `ModerationAuditLogResponse` DTO 한 필드 + `PaymentRefundAuditContextResponse` DTO 추가에 대해 service / controller / 직렬화 / Spring DI 회귀 없음 |
| PR126 (`087ed8d`) | frontend `npm run build` | green (101 modules, 853ms — `tsc -b` + Vite 모두 통과). `PaymentRefundAuditContext` interface + `PaymentRefundContextPanel` 컴포넌트 추가에 대해 `AdminAuditLogsSection` 타입 정상 인식 |
| PR127 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR126 (`087ed8d`).

**마지막 전체 backend `gradle test` green**: PR126 (`087ed8d`).

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고). 본 사이클 (PR122 / PR124 / PR126) 의 검증 시점에는 cache lock 이슈가 발생하지 않음.

---

## 5. 운영 / 배포 주의사항

### Flyway 마이그레이션 — 없음

PR122 ~ PR126 모두 **새 V 마이그레이션 없음**. enum 값 2개 (`PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED`) 는 Kotlin enum 추가만 — `moderation_audit_logs.action` 컬럼은 `VARCHAR(50)` 이라 새 값 (`PAYMENT_PARTIALLY_REFUNDED` = 26자) 그대로 수용. PR124 / PR126 는 응답 DTO 필드 추가뿐 — DB 영향 없음. 전체 마이그레이션 범위는 V1~V11 그대로 (직전 PR117 V11 이 마지막).

### Detail endpoint response shape — 1 필드 추가 (PR126)

`GET /api/v1/admin/moderation/audit-logs/{id}` 응답에 `paymentRefundContext` 필드가 추가된다. 다음 보장:

- **endpoint / path / params / 권한 무변경** — URL, query parameter, ADMIN 권한 가드, response wrapper (ApiResponse) 모두 그대로.
- **기존 필드 무변경** — `forcedRefundContext` (PR115) 도 그대로. `id / actorId / actorNickname / actorSystem / action / targetType / targetId / beforeValue / afterValue / reason / createdAt` 11 필드 정의 유지.
- **`PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` row 에만 채워짐** — 그 외 action 에서는 `paymentRefundContext = null`.
- **두 refund context 는 상호 배타** — 한 row 가 `forcedRefundContext` 와 `paymentRefundContext` 둘 다 non-null 인 경우는 없음.
- **list / CSV / archive 응답은 enrichment 제외** — N+1 회피 + CSV 컬럼 구조 호환. archive 단건도 enrichment 미적용.
- **이전 frontend 호환** — 새 필드는 optional 로 직렬화되며, type 정의에 없는 frontend 빌드는 무시. 직전 PR125 까지 빌드된 frontend bundle 도 새 필드를 그냥 못 보는 정도이지 깨지지 않는다.

### Audit action 기록 정책 — PR122 그대로

PR126 은 detail 응답 enrichment 만 추가 — audit row 기록 로직은 PR122 그대로다. 원본 audit row (`beforeValue` / `afterValue` / `reason`) 는 손대지 않으며 enrichment 는 **읽기 뷰** 만 생성. 다음 보장:

- `refundPaymentByTicket` 의 success 분기에서만 audit 기록 (actor=호출자).
- `forceRefundByAdmin` 흐름은 `TICKET_FORCED_REFUNDED` 1건만 (AdminPaymentService 책임), 중복 audit 없음.
- webhook `refund.completed` 흐름은 audit 미기록 (PG-driven, actor 부재).
- audit 실패 시 환불 트랜잭션 rollback (`@Transactional` propagation join).

### Enrichment 실패는 detail 자체를 깨지 않음 (PR126)

`ModerationAuditLogService.buildPaymentRefundContext` 는 절대 throw 하지 않는다 — `runCatching {...}.getOrNull()` 로 swallow. 다음 경로에서 endpoint 는 모두 200 응답:

- `afterValue` 가 null / blank / 비 JSON object / parse 실패 → `contextAvailable=false` + 전 필드 null
- JSON 에 `ticketId` 가 없거나 정수 변환 실패 → ticket lookup 시도 없이 `contextAvailable=false` + JSON 의 다른 값은 그대로 노출
- `ticketId` 는 있지만 ticket row 가 삭제됨 → JSON 값만 노출 + buyer/event/channel null + `contextAvailable=false`

UI 는 `contextAvailable=false` 시 fallback 카피 ("원본 감사 로그는 확인되지만 티켓 상세 정보를 찾을 수 없습니다.") + JSON 추출 값만 표시. 운영자가 ticketId 단서만으로도 사람에게 물어볼 수 있게 의도된 부분 노출.

### CSV / archive 응답 무변경

PR63 CSV export (`GET /admin/moderation/audit-logs/export`) 의 10 컬럼 (`id,createdAt,actorId,actorNickname,action,targetType,targetId,reason,beforeValue,afterValue`) 은 PR126 도 그대로. PR67 archive 단건/리스트 응답 (`originalId / actorNicknameSnapshot / ...`) 도 enrichment 미적용. 외부 도구가 의존하는 컬럼 구조는 PR114 이전과 동일.

### 운영자 활동 카드 (PR93 / PR109 / PR124 누적, PR126 무변경)

`/admin?tab=overview` 운영자 활동 카드의 breakdown 행 순서 (왼쪽 → 오른쪽):

1. 숨김 / 숨김 해제 / 채널 제재 / 제재 해제 (모더레이션)
2. 이의 처리 / 신고 처리 (appeal / report)
3. **부분 환불 / 환불 완료** (PR124, 일반 사용자 환불)
4. **강제 환불** (PR109, ADMIN forced refund)
5. 임계치 변경 / 아카이브

PR126 은 audit row detail 의 panel 만 추가 — actor stats 카운트 / 카드 / breakdown 구조는 변경 없음.

---

## 6. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 forced refund** | ADMIN `/admin/tickets/{id}/forced-refund` 는 PR117 부터 PARTIALLY_REFUNDED 티켓도 받지만 항상 한 번에 remaining 전체를 환불 (cascade). 부분 금액 강제 환불은 별도 endpoint 또는 옵션 도입 필요. |
| **PAYMENT_REFUNDED quick filter chip** | PR113 의 "강제 환불" chip 패턴을 새 액션 2개에 확장하지 않음. 운영자가 select dropdown 으로 필터 가능. chip 추가는 후속 PR. |
| **CSV export 에 enrichment 컬럼** | CSV 는 audit 원본 10 컬럼 그대로 — PR126 enrichment 는 detail endpoint 응답에만. 운영자가 CSV 한 줄로 buyer/event/channel 까지 보고 싶다면 별도 CSV 확장 PR 필요 (컬럼 호환성 신중). |
| **Archive audit detail enrichment** | archive 단건 / archive 리스트 응답은 PR67 의 shape 그대로 — PR115 / PR126 enrichment 미적용. archive 진입 빈도가 낮아 의도적으로 보류. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 환불 + audit + actor stats + detail enrichment 까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **부분 환불 동시 race** | 별도 lock 없음. 후순위 호출이 400 으로 거부될 뿐 — race 빈도 낮아 의도적으로 lock 도입 보류. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **부분 환불 webhook** | PG 가 partial cancel webhook 을 보낼 가능성은 본 PR 범위 밖. `PaymentStatus.PARTIALLY_REFUNDED` webhook 입력은 무시. |
| **Webhook 환불 audit** | `refund.completed` webhook 흐름은 audit 미기록 그대로 (PG-driven, actor 없음). |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 사이클의 release notes 에 있던 다음 항목은 본 사이클에서 채워졌으므로 제거됐다:

- **"`PAYMENT_*` audit detail enrichment"** → PR126 으로 구현. `PaymentRefundAuditContextResponse` DTO + `ModerationAuditLogResponse.paymentRefundContext` + frontend `PaymentRefundContextPanel`. PR115 forced refund 패턴을 일반 사용자 환불 audit 까지 확장. detail endpoint 에서만 enrichment, list/CSV/archive 미적용. lookup 실패는 detail 200 + fallback.

---

## 7. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (본 묶음 영향)

- §13 결제 플로우 (PR74) — 회귀 가드
- §14 환불 플로우:
  - PR42 / PR117 / PR118 / PR120 기존 항목 — 회귀 가드
  - **§14 PR122 사용자 환불 audit 11 항목** — 회귀. PR126 은 audit 기록 로직 무변경이라 직접 영향 없음
  - §14 PR120 회귀 매트릭스 11 행 — 회귀 가드
- §15 결제·환불·재신청 정합성 / §16 재신청 / 결제 알림 라우트 — 회귀
- §22 ADMIN 강제 환불 — 회귀. PR126 은 `TICKET_FORCED_REFUNDED` 의 PR115 forced refund panel 을 변경하지 않았으며, `forcedRefundContext` (PR115) 도 그대로

### 운영 콘솔 — 본 묶음의 핵심

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (PR93) — 회귀
- §23 운영자 활동 강제 환불 카운트 (PR109) — 회귀
- §24 운영자 활동 사용자 환불 카운트 (PR124) — 회귀. PR126 detail enrichment 추가가 카운트 행에 영향 없어야 함 (action 단위 분리)
- **§25 사용자 환불 audit 상세 enrichment (PR126)** — 본 사이클의 신규 11 항목:
  - 부분 환불 audit row 의 "상세" 펼침 → "환불 상세" panel 의 11 칸 grid (buyer 3 + 이벤트 2 + 채널 2 + 티켓/결제 ID 2 + 환불 유형 + 세 금액 3 + 두 상태 2)
  - 전액 환불 audit row 의 "상세" 펼침 → 환불 유형 "전액 환불" + 남은 환불 한도 ₩0 + 티켓/결제 상태 "REFUNDED"
  - `ticket.status` 가 JSON snapshot 보다 우선 — PARTIALLY_REFUNDED 추가 환불 → REFUNDED cascade 후 같은 row 재조회 시 ticketStatus 가 REFUNDED 로 갱신
  - ticket 삭제된 row → fallback 카피 + JSON 값은 그대로 + buyer/event/channel 칸 "—"
  - malformed afterValue JSON → 동일 fallback + 모든 lookup 값 "—"
  - non-payment-refund row 펼침 → "환불 상세" panel 미표시 (회귀 가드)
  - **TICKET_FORCED_REFUNDED row → forced refund panel 만 노출, payment refund panel 비노출** (두 context 상호 배타 가드)
  - archive 탭의 같은 row → enrichment panel 미노출 (archive endpoint 는 enrichment 제외)
  - raw afterValue JSON pretty-print 는 panel 과 별개로 그대로 유지 (원본 audit row 무변경)
  - CSV export 결과의 컬럼 / 행은 PR125 이전과 동일 — `paymentRefundContext` 미포함
  - list endpoint 응답의 각 row 에 `paymentRefundContext = null` — detail endpoint 응답에만 채워짐 (N+1 회피)

### 알림 (PR104 영향, 본 묶음 무변경)

- §20 알림 수신 설정 / §20a 묶음 토글 / §20b Quick Mute + Undo / §20c 마지막 저장 시각 / §21 알림 메타데이터 일관성

🖱 / 📋 라벨 의미는 manual QA 문서 상단 "본 문서 사용법" 참고.

---

## 8. Push 전 권장 명령

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

본 묶음은 새 migration 이 없다 (V11 까지 그대로). `PaymentService` 생성자의 `ModerationAuditLogService` (PR122 도입) 도 Spring DI 자동 해결. PR124 actor stats DTO 확장 2 필드와 PR126 detail 응답 1 필드 추가는 모두 backward compatible JSON serialization 이라 직전 frontend bundle 도 깨지지 않는다.

---

## 9. 다음 사이클 (push 이후 추천)

본 묶음으로 사용자 환불 audit 사이클 (PR122~PR126) 이 닫혔다 — 기록 (PR122) → 표시 (PR124) → detail enrichment (PR126) → 문서 (PR123 + PR125 + 본 PR127). 다음 사이클의 후보:

1. **PR128 옵션 A — 부분 forced refund**: ADMIN 의 `/admin/tickets/{id}/forced-refund` 에 optional `amount` 추가. 운영자가 노쇼 보상의 일부만 돌려주는 케이스. backend + frontend + audit amount 의미 재정의.
2. **PR128 옵션 B — PAYMENT_REFUNDED quick filter chip**: PR113 의 "강제 환불" chip 을 사용자 환불 액션 2개에 확장. `/admin?tab=audit-logs` 의 진입 속도 보강. 작은 frontend PR.
3. **PR128 옵션 C — Archive audit detail enrichment**: PR67 archive 단건 응답에도 PR115 / PR126 패턴 확장. archive 진입 빈도가 낮아 후순위였으나 보존 정책 갱신 시점에 자연스럽게 묶을 수 있음. backend + frontend.
4. **PR128 옵션 D — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 카운트 일치 검증. 큰 backend PR.
5. **PR128 옵션 E — CSV export 의 enrichment 컬럼**: CSV 에 buyer/event/channel 컬럼 추가. 외부 도구 호환성 신중 검토 필요. 작은 backend PR.

옵션 A 는 ADMIN 운영 도구 확장. 옵션 B 는 작은 frontend 보강. 옵션 C 는 PR115/PR126 의 자연스러운 확장. 옵션 D 는 안정성 — 부분 환불 + audit + actor stats + detail enrichment 까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다. 옵션 E 는 운영 외부 도구가 한 CSV 로 buyer/event/channel 까지 보고 싶을 때.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR122-PR126.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
