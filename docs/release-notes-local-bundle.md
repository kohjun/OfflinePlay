# Local Release Bundle — PR130 to PR137 (retrospective) + PR138 (this doc)

본 문서는 두 역할을 동시에 한다:

1. **Push 전 self-audit** — 본 PR138 (release-notes refresh) 만 origin 에 push 전이라면 1 commit ahead 인 상태의 ship-readiness 노트.
2. **PR130~PR137 사이클 retrospective** — 직전 8 commit 묶음 (archive audit enrichment + CSV refund columns + partial admin forced refund + 회귀 가드) 의 정리. 이 8 commit 은 본 문서 작성 시점에 이미 origin/main 에 반영되어 있다 — 다음 사이클 운영자가 한 번에 훑을 수 있게 같은 문서에 retrospective 로 묶어둔다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` (PR130~PR137 까지 모두 반영된 시점) |
| Head | `<PR138 docs commit>` (본 문서 commit) |
| Ahead | **1 commit** (예상, PR138 만 — PR130~PR137 은 origin 에 있음) |
| 직전 cycle | `0e41e23 (PR130) … f7c09cb (PR137)` (8 commits, 이미 push 완료) |
| 작성 시점 | 2026-05-19 |

직전 push 가 PR130~PR137 8 커밋을 한꺼번에 origin 에 올렸다. 두 작은 cycle 의 묶음이다 — **(a) PR130~PR133 환불 audit 가독성 확장** (archive detail enrichment / CSV refund columns / docs / regression hardening) 와 **(b) PR134~PR137 부분 강제 환불 도입** (backend + UI + docs + regression hardening). 두 cycle 모두 backend 의 기존 헬퍼 (`buildForcedRefundContext` / `applyPartialRefund` / `markRefundedInternal` / `csvRefundDerivedColumns`) 를 재사용해 변경의 외연을 좁게 유지했다.

본 PR138 은 이 8 commit 의 retrospective 정리만 — production / backend / frontend / migration 변경 없음.

---

## 1. 커밋 묶음 요약 (PR130~PR137, origin 에 반영됨)

### Archive audit enrichment + CSV refund columns + partial forced refund

| commit | PR | 요약 |
|---|---|---|
| `0e41e23` | PR130 | **Archive audit detail enrichment**. `ArchivedModerationAuditLogResponse` 에 `forcedRefundContext` / `paymentRefundContext` optional 필드 + `getArchived` 가 active detail 과 같은 정책으로 enrichment. archive list / CSV 응답은 미적용 (N+1 회피). frontend `ArchivedModerationAuditLog` type 확장 + archive 탭 detail 에서 기존 panel 재사용. 6 신규 MockK 케이스 + active PR115/PR126 회귀 통과. |
| `5f3bc32` | PR131 | **Audit CSV refund-derived columns**. active / archive CSV 모두에 환불 분석용 10 파생 컬럼 append-only — `refundKind` / `ticketId` / `paymentAttemptId` / `eventId` / `refundAmount` / `refundedAmount` / `remainingRefundableAmount` / `ticketStatus` / `paymentStatus` / `fullRefund`. 단일 helper `csvRefundDerivedColumns` 가 두 서비스 공유 — afterValue JSON 파생값만, lookup 호출 없음. 6 active + 2 archive 신규 케이스. |
| `165593d` | PR132 | **Refund audit enrichment 정책 문서화**. architecture.md 6-row enrichment 표 (active/archive × detail/list/CSV) + manual-qa §28 + release-notes 갱신. docs only. |
| `e16be9e` | PR133 | **Refund audit enrichment 회귀 가드**. 5 신규 MockK 케이스 — active CSV 모든 row 가 정확히 20 컬럼 (action 무관), archive CSV 모든 row 가 21 컬럼, FORCED malformed JSON export 성공, ticket lookup 이 throw 해도 detail 200, archive mutual exclusion. production 코드 변경 없음. |
| `41a50ae` | PR134 | **Partial admin forced refund backend**. `AdminForcedRefundRequest.amount: Long?` optional + `PaymentService.forceRefundByAdmin(amount = null)` 시그니처 확장. amount null → PR106 동작 그대로, 지정 시 `1 <= amount <= remaining` 검증 → `applyPartialRefund` (PR117 헬퍼 재사용) 또는 `markRefundedInternal`. audit afterValue JSON 에 4 필드 (`refundAmount / refundedAmount / remainingRefundableAmount / fullRefund`) 추가 — 기존 4 필드는 호환을 위해 유지. `AdminForcedRefundResponse` 에 3 필드 추가. action 은 PR106 그대로 `TICKET_FORCED_REFUNDED` 1건만. 5 신규 PaymentServiceTest + 2 신규 AdminPaymentServiceTest 케이스. |
| `3681282` | PR135 | **Partial admin forced refund UI**. `AdminPaymentToolsSection` 에 "환불 방식" 라디오 fieldset (전액 / 금액 지정) + PARTIAL 선택 시 amount input + confirm dialog 의 선택한 방식 / 금액 / cascade 명시 + result card 에 누적 환불액 / 남은 환불 가능액 + 환불 유형 Badge. 400 → "환불 금액을 확인해주세요." error mapping. `forceRefundTicket(ticketId, reason, amount?)` API 함수 — undefined 이면 body 에서 amount 키 제외해 PR106 호출 경로 유지. 일반 사용자 refund UI 변경 없음. 11 manual-qa 항목. |
| `acd1db7` | PR136 | **Partial forced refund 정책 문서화**. payment-refund-policy.md §16 신설 (요청/응답 변경 + USED 부분 환불 + audit afterValue 확장 + 의도적 제외) + architecture.md PR134/135 entries + release-notes 갱신. Known follow-ups 에서 "부분 forced refund" 제거. docs only. |
| `f7c09cb` | PR137 | **Partial forced refund 회귀 가드**. 4 신규 PaymentServiceTest chain 케이스 — 두 번 partial 누적 후 PARTIALLY_REFUNDED 유지, partial → forced full cascade, user partial → admin forced full cascade, admin partial + user over-remaining 차단. production 코드 변경 없음. |

### PR138 (본 문서)

| commit | PR | 요약 |
|---|---|---|
| `<TBD>` | PR138 | **Release notes retrospective**. PR130~PR137 8 commit 사이클을 정리 + Known follow-ups 갱신. docs only. |

---

## 2. 두 cycle 의 운영 가치

### (a) Audit enrichment cycle (PR130~PR133)

환불 audit 의 가독성 정책이 6 응답 경로 모두 일관되게 정리됐다:

| 응답 경로 | enrichment | DB lookup | 도입 PR |
|---|---|---|---|
| `GET /admin/moderation/audit-logs/{id}` | buyer/event/channel + 세 금액 + 상태 | ✅ | PR115 / PR126 |
| `GET /admin/moderation/audit-logs/archive/{originalId}` | 동일 | ✅ | **PR130** |
| `GET /admin/moderation/audit-logs?page=...` | 없음 | ❌ | PR62 |
| `GET /admin/moderation/audit-logs/archive?page=...` | 없음 | ❌ | PR67 |
| `GET /admin/moderation/audit-logs/export` (CSV) | afterValue JSON 파생 10 컬럼 | ❌ | **PR131** |
| `GET /admin/moderation/audit-logs/archive/export` (CSV) | 동일 | ❌ | **PR131** |

**N+1 정책**: list / CSV 경로는 **절대 ticket / buyer / event / channel lookup 호출 없음**. 깊은 enrichment 가 필요하면 detail endpoint 로. lookup 실패는 detail 자체를 깨지 않음 (`runCatching {...}.getOrNull()` swallow).

### (b) Partial admin forced refund cycle (PR134~PR137)

ADMIN 강제 환불 (`/admin/tickets/{id}/forced-refund`) 이 optional `amount` 를 받아 부분 강제 환불을 지원. 노쇼 부분 보상 같은 케이스도 단일 도구로 처리:

| 케이스 | UI 흐름 | backend 호출 | cascade |
|---|---|---|---|
| 전액 강제 환불 (PR106 회귀) | "환불 방식: 남은 환불 가능액 전액" 선택 | `forceRefundByAdmin(amount=null)` | full — ticket REFUNDED + participation CANCELED + 정원 -1 |
| 부분 강제 환불 (PR134 신규) | "금액 지정" 선택 + 환불 금액 입력 | `forceRefundByAdmin(amount=10000)` | partial — ticket PARTIALLY_REFUNDED + 참가/정원 무영향 |
| amount == remaining | 동일 | `forceRefundByAdmin(amount=remaining)` | full cascade |

세 케이스 모두 `TICKET_FORCED_REFUNDED` audit row 1건만 — partial / full 구분은 `afterValue.fullRefund` 플래그로 표시. action 단위 분류 (PR109 actor stats / PR128 quick filter chip) 는 변경 없이 그대로 작동. 일반 사용자 환불 (`refundPaymentByTicket`, PR117/PR122) 정책 / audit 는 무변경 — endpoint 가 다르므로 정확히 구분.

---

## 3. Push 전 확인사항 (PR138 만 대상)

### 스테이징 금지 파일

다음 파일들은 **절대 stage / commit 하지 않는다** — 사이클 내내 합의된 제외 목록:

- `.claude/settings.local.json`
- `.claude/scheduled_tasks.lock`
- `build/resources/main/application.yml`
- `.gradle/**`
- `build/**`
- `frontend/dist/**`
- `*.tsbuildinfo`

push 직전 `git status -sb` 로 위 파일들이 staged 영역에 들어가 있지 않은지 한 번 더 확인.

### 최종 git 상태 (PR138 commit 후 push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead 1]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

본 PR138 docs 갱신만 ahead 1. 위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 4. 검증 기록 (PR130~PR137 사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR130 (`0e41e23`) | backend 좁은 `--tests *ModerationAuditLog*` | green (50s) — archive enrichment 6 신규 + PR115/PR126 회귀 |
| PR130 (`0e41e23`) | backend full `gradle test` | green (46s) |
| PR130 (`0e41e23`) | frontend `npm run build` | green (981ms) |
| PR131 (`5f3bc32`) | backend 좁은 `--tests *ModerationAuditLog*` | green — CSV refund 컬럼 6 active + 2 archive 신규 |
| PR131 (`5f3bc32`) | backend full `gradle test` | green (50s) |
| PR132 (`165593d`) | docs-only | build/test 생략 |
| PR133 (`e16be9e`) | backend `--tests *ModerationAuditLog*` (rerun-tasks) | green — 컬럼 수 invariant + FORCED malformed JSON + lookup-throw + 상호 배타 |
| PR133 (`e16be9e`) | backend full `gradle test` | green (41s) |
| PR133 (`e16be9e`) | frontend `npm run build` | green (789ms) |
| PR134 (`41a50ae`) | backend `--tests *PaymentServiceTest*` | green (1m 54s) — partial / full / amount over / amount 0 / USED partial 5 신규 |
| PR134 (`41a50ae`) | backend `--tests *AdminPaymentServiceTest*` | green (3m 2s, kotlin daemon stop/restart 필요) — afterValue 4 신규 키 + response 3 신규 필드 |
| PR134 (`41a50ae`) | backend full `gradle test` | green (1m 48s) |
| PR135 (`3681282`) | frontend `npm run build` | green (761ms) — 새 fieldset / 라디오 / amount input / Badge 토큰 정상 |
| PR136 (`acd1db7`) | docs-only | build/test 생략 |
| PR137 (`f7c09cb`) | backend `--tests *PaymentServiceTest*` | green (2m 3s) — 부분/누적/cascade chain / over-remaining 차단 4 신규 |
| PR137 (`f7c09cb`) | backend full `gradle test` | green (1m 40s) |
| PR137 (`f7c09cb`) | frontend `npm run build` | green (745ms) |
| PR138 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR137 (`f7c09cb`).

**마지막 전체 backend `gradle test` green**: PR137 (`f7c09cb`).

push 직후 CI 가 cold-start 로 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip 또는 `build/snapshot/kotlin` 잠금) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat test` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 + PR134 caches-jvm 잠금 경험 참고).

---

## 5. 운영 / 배포 주의사항 (사이클 누적)

### Flyway 마이그레이션 — 없음

PR130 ~ PR137 모두 **새 V 마이그레이션 없음**. enum / 테이블 / 컬럼 변경 없음. 전체 마이그레이션 범위는 V1~V11 그대로 (PR117 V11 이 마지막).

### Detail endpoint response shape — archive 측 2 필드 추가 (PR130)

`GET /api/v1/admin/moderation/audit-logs/archive/{originalId}` 응답에 `forcedRefundContext` / `paymentRefundContext` 두 optional 필드가 추가됐다. active detail 과 동일 정책 / 동일 helper / 동일 shape — 이전 frontend bundle 도 호환.

### CSV export header — 10 컬럼 append (PR131)

active CSV 와 archive CSV 양쪽 헤더에 동일한 10 컬럼이 끝에 append. prefix (active 10 / archive 11) 컬럼은 위치 / 이름 / 값 그대로 — 외부 도구가 prefix 만 보고 있다면 PR131 이후에도 호환. CSV path 는 lookup 호출 없음.

### Forced refund request body — optional amount 추가 (PR134)

`POST /admin/tickets/{id}/forced-refund` body 에 `amount` 가 optional 로 추가. 옛 body `{ "reason": "..." }` 는 여전히 유효 — 미지정 시 PR106 동작 그대로. frontend `forceRefundTicket(amount?)` 는 undefined 일 때 body 에서 키를 제외해 외부 운영 스크립트도 깨지지 않는다.

### Forced refund response shape — 3 필드 추가 (PR134)

`AdminForcedRefundResponse` 에 `refundedAmount` / `remainingRefundableAmount` / `fullRefund` 3 optional 필드가 추가. 옛 frontend bundle 은 새 필드를 무시 — 깨지지 않음.

### Audit afterValue JSON — 4 키 추가 (PR134)

`TICKET_FORCED_REFUNDED` row 의 `afterValue` 가 PR134 부터 8 키로 확장. 기존 4 키 (`ticketId / paymentAttemptId / ticketStatus / amount`) 는 위치 / 의미 그대로 — PR115 `ForcedRefundContextResponse` enrichment + PR131 CSV `csvRefundDerivedColumns` 가 깨지지 않는다. 새 4 키 (`refundAmount / refundedAmount / remainingRefundableAmount / fullRefund`) 는 PR126 `paymentRefundContext` 와 같은 의미 — PR131 CSV 의 `refundedAmount / remainingRefundableAmount / fullRefund` 컬럼이 forced refund row 에서도 자동으로 채워지게 됐다 (helper 변경 없이).

### 일반 사용자 환불 정책 (PR42 / PR117 / PR122) — 무변경

`POST /tickets/{id}/refund` 의 동작 / 가드 / audit 정책은 본 사이클에서 변경되지 않는다. 부분 / 전액 / cascade / `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` audit 기록 모두 PR129 이전과 동일. **partial admin forced refund 와 partial user refund 는 endpoint 가 다르고 audit action 도 다르다** (`TICKET_FORCED_REFUNDED` vs `PAYMENT_PARTIALLY_REFUNDED`/`PAYMENT_REFUNDED`) — 혼동 금지.

### Enrichment / Export 실패는 detail / export 자체를 깨지 않음

PR130 archive detail enrichment, PR131 CSV refund columns 둘 다 **best-effort + throw 금지** 정책 (`runCatching {...}.getOrNull()`). detail 은 lookup 실패 시 200 + `contextAvailable=false`, CSV 는 malformed JSON 시 새 10 컬럼만 빈 값. 두 경우 모두 endpoint / export 자체는 정상 응답.

---

## 6. Known follow-ups (의도된 미구현)

본 사이클은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **CSV 의 buyer / event title / channel lookup 컬럼** | PR131 은 afterValue JSON 파생값만 노출 — buyer 닉네임 / event 제목 / 채널 이름은 detail endpoint (PR115/PR126/PR130) 에서만. CSV 한 줄로 닉네임까지 보고 싶다면 N+1 lookup 또는 join-loaded query 가 필요한 별도 PR. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 강제 환불까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. deadletter + 운영자 retry 도구 필요. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **부분 환불 동시 race** | 별도 lock 없음. 후순위 호출이 400 으로 거부될 뿐 — race 빈도 낮아 의도적으로 lock 도입 보류. |
| **partial forced refund 의 별도 audit action** | PR134 는 `TICKET_FORCED_REFUNDED` 1 action 으로 유지. partial / full 구분은 `afterValue.fullRefund` 플래그. |
| **forced refund 의 별도 buyer 알림 카피** | partial forced refund 의 buyer 알림은 PR117 `applyPartialRefund` 알림 ("부분 환불이 처리되었어요") 그대로 재사용. 운영 vs 일반 환불의 알림 카피 분리는 후속 PR. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **부분 환불 webhook** | PG 가 partial cancel webhook 을 보낼 가능성은 본 PR 범위 밖. `PaymentStatus.PARTIALLY_REFUNDED` webhook 입력은 무시. |
| **Webhook 환불 audit** | `refund.completed` webhook 흐름은 audit 미기록 그대로 (PG-driven, actor 없음). |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 release notes 에 있던 다음 항목들은 본 사이클에서 채워졌으므로 제거됐다:

- **"Archive audit detail enrichment"** → PR130 으로 구현.
- **"CSV export 에 enrichment 컬럼"** → PR131 으로 부분 구현 (afterValue JSON 파생 10 컬럼). buyer/event title lookup 컬럼은 여전히 미구현 — 위 표에 별도 항목으로 유지.
- **"부분 forced refund"** → PR134 + PR135 으로 구현. PR106 의 전액 동작은 amount=null 시 그대로 유지.

---

## 7. Recommended manual QA (post-push verification)

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 staging deploy 직후 (또는 다음 사이클 작업 시작 전) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (회귀 가드)

- §13 결제 플로우 / §14 환불 플로우 / §15 정합성 / §16 재신청 — 본 사이클은 일반 환불 정책 무변경
- §22 ADMIN 강제 환불 (PR106) — 회귀. PR134 의 amount=null 경로가 PR106 동작과 동일해야 함

### 운영 콘솔 — 본 사이클의 핵심

- §12 / §19 — Admin 콘솔 / 운영자 활동 요약 — 회귀
- §23 / §24 — forced refund / user refund actor stats 카운트 — 회귀
- §25 / §26 — active 사용자 환불 detail enrichment / quick filter chip — 회귀
- **§27 — Archive audit 상세 enrichment (PR130)** — 본 사이클 11 항목
- **§28 — CSV export 환불 파생 컬럼 (PR131)** — 본 사이클 11 항목
- **§29 — 부분 강제 환불 (PR134 / PR135)** — 본 사이클 11 항목

### 알림 (PR104 영향, 본 사이클 무변경)

- §20 / §21

🖱 / 📋 라벨 의미는 manual QA 문서 상단 "본 문서 사용법" 참고.

---

## 8. Push 전 권장 명령 (PR138 commit 후)

```bash
# 1) 최종 상태 확인
git -C C:/WOYA status -sb
git -C C:/WOYA log --oneline origin/main..HEAD

# 2) 풀 빌드 + 테스트 (PR138 은 docs only 라 사실상 cold-start 가드)
cd C:/WOYA && ./gradlew.bat test
cd C:/WOYA/frontend && npm run build

# 3) 위 모두 green 이면 push (사용자가 직접 실행)
# git -C C:/WOYA push origin main
```

`./gradlew.bat test` 가 cold-start 일 때 kotlin daemon 의 caches-jvm lock 또는 `build/snapshot/kotlin/compileTestKotlin` 잠금으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat test` 으로 회복 — Windows 환경 이슈 (PR74 stabilize + PR134 caches-jvm 경험).

본 PR138 은 docs only — DTO / migration / endpoint 변경 없음. 이전 frontend bundle / 외부 도구 모두 호환된다.

---

## 9. 다음 사이클 (post-PR138 추천)

PR130~PR137 8 commit 으로 두 cycle 이 닫혔다. 다음 사이클 후보:

1. **PR139 옵션 A — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 카운트 일치 검증. 큰 backend PR — 부분 환불 + 부분 강제 환불 + audit detail + CSV 까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다.
2. **PR139 옵션 B — CSV 에 buyer / event title lookup 컬럼**: 외부 도구가 한 CSV 로 닉네임 / 이벤트 제목까지 보고 싶을 때. join-loaded query 또는 batch lookup 으로 N+1 회피 필요. 큰 backend PR.
3. **PR139 옵션 C — 환불 실패 큐 / 자동 재시도**: `refund.failed` webhook 의 deadletter + 운영자 retry 도구. backend + frontend + 알림.
4. **PR139 옵션 D — Forced refund 의 별도 buyer 알림 카피**: 운영 vs 일반 환불의 알림 메시지 분리. 작은 frontend / backend 묶음.
5. **PR139 옵션 E — PortOne / 다른 PG 어댑터**: 결제 다중화. interface 가 PR42 부터 열려 있어 구현체만 추가. backend + 설정.

옵션 A 는 안정성. 옵션 B 는 외부 도구 호환 확장. 옵션 C 는 환불 실패의 신뢰성. 옵션 D 는 buyer-facing 카피 polish. 옵션 E 는 결제 인프라 다양화.

---

본 문서는 PR130~PR137 retrospective + PR138 self-audit. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR130-PR137.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
