# Local Release Bundle — PR134 to PR136

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 6 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `<PR136 docs commit>` (본 문서 commit) |
| Ahead | **6 commits** (예상) |
| First ahead | `0e41e23 feat(admin): enrich archived audit details` (PR130) |
| 작성 시점 | 2026-05-19 |

직전 push (PR128 + PR129) 가 origin 에 반영된 위에 얹은 사이클은 두 묶음 — **(a) PR130~PR132 환불 audit 가독성 정리 + PR133 회귀 가드** (archive detail enrichment / CSV refund columns / docs / regression hardening) 와 **(b) PR134~PR136 부분 강제 환불 도입** (backend + UI + docs). 본 release-notes 는 (a) 가 같은 묶음에 묶여 있다는 가정으로 작성. 검증 / 운영 주의사항은 두 묶음을 한 번에 다룬다.

**(a) PR130~PR133** — Archive detail / CSV 환불 컬럼이 더해져 환불 audit 의 가독성 정책이 6 응답 경로 (active/archive × detail/list/CSV) 모두 일관되게 정리됐다. PR133 이 회귀 가드를 한 번 더 잠갔다.

**(b) PR134~PR136** — ADMIN 강제 환불 (`/admin/tickets/{id}/forced-refund`) 이 optional `amount` 를 받아 부분 강제 환불을 지원한다. PR106 의 전액 동작은 `amount=null` 시 그대로 유지 — 운영자가 노쇼 보상의 일부만 돌려주는 케이스도 단일 도구에서 처리 가능. PR117 `applyPartialRefund` 헬퍼 재사용으로 코드 / 정책 단일 원천.

backend / migration / 일반 사용자 환불 정책 / 일반 환불 UI / archive enrichment / CSV 컬럼 정의는 본 묶음에서 변경 없음. PR134 의 audit afterValue 4 필드 추가는 PR106 의 기존 4 필드와 호환되어 PR115 / PR130 / PR131 enrichment / CSV 가 그대로 작동.

---

## 1. 커밋 묶음 요약

### Archive audit gradeability + partial forced refund

| commit | PR | 요약 |
|---|---|---|
| `0e41e23` | PR130 | **Archive audit detail enrichment**. `ArchivedModerationAuditLogResponse` 에 `forcedRefundContext` / `paymentRefundContext` optional 필드 + `getArchived` 가 active detail 과 같은 정책으로 enrichment. archive list / CSV 응답은 미적용 (N+1 회피). frontend `ArchivedModerationAuditLog` type 확장 + archive 탭 detail 에서 기존 panel 재사용. 6 신규 MockK 케이스. |
| `5f3bc32` | PR131 | **Audit CSV refund-derived columns**. active / archive CSV 모두에 환불 분석용 10 파생 컬럼 append-only — `refundKind` / `ticketId` / `paymentAttemptId` / `eventId` / `refundAmount` / `refundedAmount` / `remainingRefundableAmount` / `ticketStatus` / `paymentStatus` / `fullRefund`. 단일 helper `csvRefundDerivedColumns` 가 두 서비스 공유 — afterValue JSON 파생값만, lookup 호출 없음. 6 active + 2 archive 신규 케이스. |
| `165593d` | PR132 | **Refund audit enrichment 정책 문서화**. architecture.md 6-row enrichment 표 (active/archive × detail/list/CSV) + manual-qa §28 + release-notes 갱신. docs only. |
| `e16be9e` | PR133 | **Refund audit enrichment 회귀 가드**. 5 신규 MockK 케이스 — active CSV 모든 row 가 정확히 20 컬럼 (action 무관), archive CSV 모든 row 가 21 컬럼, FORCED malformed JSON export 성공, ticket lookup 이 throw 해도 detail 200, archive mutual exclusion. production 코드 변경 없음. |
| `41a50ae` | PR134 | **Partial admin forced refund backend**. `AdminForcedRefundRequest.amount: Long?` optional + `PaymentService.forceRefundByAdmin(amount = null)` 시그니처 확장. amount null → PR106 동작 그대로, 지정 시 `1 <= amount <= remaining` 검증 → `applyPartialRefund` (PR117 헬퍼 재사용) 또는 `markRefundedInternal`. audit afterValue JSON 에 4 필드 (`refundAmount / refundedAmount / remainingRefundableAmount / fullRefund`) 추가 — 기존 4 필드는 호환을 위해 유지. `AdminForcedRefundResponse` 에 3 필드 추가 (response 의 누적/잔여/유형). action 은 PR106 그대로 `TICKET_FORCED_REFUNDED` 1건만. 5 신규 PaymentServiceTest + 2 신규 AdminPaymentServiceTest 케이스. |
| `3681282` | PR135 | **Partial admin forced refund UI**. `AdminPaymentToolsSection` 에 "환불 방식" 라디오 fieldset (전액 / 금액 지정) + PARTIAL 선택 시 amount input + confirm dialog 의 선택한 방식 / 금액 / cascade 명시 + result card 에 누적 환불액 / 남은 환불 가능액 + 환불 유형 Badge. 400 → "환불 금액을 확인해주세요." error mapping. `forceRefundTicket(ticketId, reason, amount?)` API 함수 — undefined 이면 body 에서 amount 키 제외해 PR106 호출 경로 유지. 일반 사용자 refund UI 변경 없음. 11 manual-qa 항목. |
| `(this PR)` | PR136 | **Partial forced refund 정책 문서화**. payment-refund-policy.md §16 신설 (요청/응답 변경 + USED 부분 환불 + audit afterValue 확장 + 의도적 제외) + architecture.md PR134/135 entries + release-notes 본 갱신. Known follow-ups 에서 "부분 forced refund" 제거. docs only. |

**본 사이클의 결과**: 환불 audit 가독성 (PR130~PR133) 과 환불 운영 도구 확장 (PR134~PR136) 두 축이 같이 정리됐다. 운영자가 (a) detail / CSV 어디에서나 환불 row 의 buyer/event/금액/상태를 한눈에 보고, (b) 노쇼 부분 보상 같은 케이스를 별도 우회 없이 단일 운영 도구로 처리. 두 묶음 모두 backend 의 `applyPartialRefund` / `markRefundedInternal` / `csvRefundDerivedColumns` / `buildForcedRefundContext` 같은 기존 헬퍼를 재사용 — 신규 헬퍼 추가는 최소화.

---

## 2. PR134 운영 가치 — 부분 강제 환불 가능

PR106 부터 PR133 까지 ADMIN 강제 환불은 항상 남은 금액 전액. 노쇼 보상의 일부만 돌려주는 케이스는 운영자가 어쩔 수 없이 전액 환불 → 부분만큼 buyer 에게 별도 송금하는 우회로 처리해야 했다.

PR134 이후:

| 케이스 | UI 흐름 | backend 호출 | cascade |
|---|---|---|---|
| 전액 강제 환불 (PR106 회귀) | "환불 방식: 남은 환불 가능액 전액" 선택 | `forceRefundByAdmin(amount=null)` | full — ticket REFUNDED + participation CANCELED + 정원 -1 |
| 부분 강제 환불 (PR134 신규) | "금액 지정" 선택 + 환불 금액 입력 | `forceRefundByAdmin(amount=10000)` | partial — ticket PARTIALLY_REFUNDED + 참가/정원 무영향 |
| 부분 누적 + 전액 마무리 | partial 후 다시 도구 진입 → 전액 선택 | `forceRefundByAdmin(amount=null)` 또는 `amount=remaining` | full cascade |

세 케이스 모두 `TICKET_FORCED_REFUNDED` audit row 1건만 기록 — partial / full 구분은 `afterValue.fullRefund` 플래그로 표시. action 단위 분류 (PR109 actor stats / PR128 quick filter chip) 는 변경 없이 그대로 작동.

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

push 직전 `git status -sb` 로 위 파일들이 staged 영역에 들어가 있지 않은지 한 번 더 확인. 위 파일들은 작업 트리에 modified/untracked 로 남아 있어도 정상.

### 최종 git 상태 (push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead 6]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

PR136 (본 release-notes 갱신) 까지 포함하면 6 ahead. 위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 4. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR130 (`0e41e23`) | backend 좁은 `--tests *ModerationAuditLog*` | green (50s) — archive enrichment 6 신규 케이스 + PR115/PR126 회귀 |
| PR130 (`0e41e23`) | backend full `gradle test` | green (46s) — `internal fun` 가시성 변경 회귀 없음 |
| PR130 (`0e41e23`) | frontend `npm run build` | green (981ms) |
| PR131 (`5f3bc32`) | backend 좁은 `--tests *ModerationAuditLog*` | green — CSV refund 컬럼 6 active + 2 archive 신규 케이스 + PR63/PR67 회귀 |
| PR131 (`5f3bc32`) | backend full `gradle test` | green (50s) — CSV 헤더 길이 변경에 controller 회귀 없음 |
| PR132 (`165593d`) | docs-only | build/test 생략 |
| PR133 (`e16be9e`) | backend `--tests *ModerationAuditLog*` (rerun-tasks) | green — 컬럼 수 invariant + FORCED malformed JSON + lookup-throw + 상호 배타 |
| PR133 (`e16be9e`) | backend full `gradle test` | green (41s) |
| PR133 (`e16be9e`) | frontend `npm run build` | green (789ms) |
| PR134 (`41a50ae`) | backend `--tests *PaymentServiceTest*` | green (1m 54s) — partial / full / amount over / amount 0 / USED partial 5 신규 케이스 + PR106 회귀 |
| PR134 (`41a50ae`) | backend `--tests *AdminPaymentServiceTest*` | green (3m 2s, kotlin daemon stop/restart 필요했음) — afterValue 4 신규 키 + response 3 신규 필드 |
| PR134 (`41a50ae`) | backend full `gradle test` | green (1m 48s) |
| PR135 (`3681282`) | frontend `npm run build` | green (761ms) — 새 fieldset / 라디오 / amount input / Badge 토큰 정상 |
| PR136 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR135 (`3681282`).

**마지막 전체 backend `gradle test` green**: PR134 (`41a50ae`).

push 직후 CI 가 cold-start 로 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고).

---

## 5. 운영 / 배포 주의사항

### Flyway 마이그레이션 — 없음

PR130 ~ PR136 모두 **새 V 마이그레이션 없음**. enum / 테이블 / 컬럼 변경 없음. 전체 마이그레이션 범위는 V1~V11 그대로 (PR117 V11 이 마지막).

### `forceRefundByAdmin` 시그니처 변경 (PR134)

`PaymentService.forceRefundByAdmin(adminUserId, ticketId, reason, amount = null)` 가 default 인자라 옛 호출 사이트는 컴파일이 깨지지 않는다. AdminPaymentService 가 직접 호출하므로 외부 호출자는 없으며 (test 만), 본 묶음에서 모두 함께 갱신됐다.

### `/admin/tickets/{id}/forced-refund` request body (PR134)

옛 body `{ "reason": "..." }` 는 여전히 유효 — `amount` 가 없으면 backend 가 PR106 동작 그대로. 새 body 는 `{ "reason": "...", "amount": 10000 }`. frontend 가 `amount=undefined` 이면 body 에서 키를 제외해 호환성 유지 (curl 으로 호출하던 외부 운영 스크립트도 깨지지 않음).

### `AdminForcedRefundResponse` shape 변경 (PR134)

세 optional 필드 (`refundedAmount` / `remainingRefundableAmount` / `fullRefund`) 가 추가. 옛 frontend bundle 은 새 필드를 무시 (TypeScript optional) — 깨지지 않는다.

### audit afterValue JSON 변경 (PR134)

`TICKET_FORCED_REFUNDED` row 의 `afterValue` 가 PR134 부터 8 키로 확장:

- 기존 4 키 (`ticketId / paymentAttemptId / ticketStatus / amount`) 는 위치 / 의미 그대로
- 신규 4 키 (`refundAmount / refundedAmount / remainingRefundableAmount / fullRefund`) 추가

PR115 `ForcedRefundContextResponse` enrichment 는 기존 4 키만 사용해 panel 을 그렸기 때문에 신규 키가 추가돼도 그대로 작동. PR131 CSV `csvRefundDerivedColumns` 가 forced refund 에 대해 `refundedAmount / remainingRefundableAmount / fullRefund` 컬럼을 채우도록 이미 helper 가 만들어져 있어 PR134 이후 forced refund row 의 CSV 컬럼이 더 풍부해진다 (helper 변경 없이 자동).

### 일반 사용자 환불 정책 (PR42 / PR117 / PR122) — 무변경

`POST /tickets/{id}/refund` 의 동작 / 가드 / audit 정책은 본 묶음에서 변경되지 않는다. 부분 환불 / 전액 환불 / cascade / `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` audit 기록 모두 PR133 이전과 동일.

### archive / CSV / 운영 콘솔 — 무변경

PR130 / PR131 의 archive detail / CSV 컬럼은 본 묶음에서 더 손대지 않는다. PR134 의 audit afterValue 확장은 PR131 CSV helper 와 자동 호환.

---

## 6. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **CSV 의 buyer / event title / channel lookup 컬럼** | PR131 은 afterValue JSON 파생값만 노출 — buyer 닉네임 / event 제목 / 채널 이름은 detail endpoint (PR115/PR126/PR130) 에서만. CSV 한 줄로 닉네임까지 보고 싶다면 N+1 lookup 또는 join-loaded query 가 필요한 별도 PR. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 강제 환불까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **부분 환불 동시 race** | 별도 lock 없음. 후순위 호출이 400 으로 거부될 뿐 — race 빈도 낮아 의도적으로 lock 도입 보류. |
| **partial forced refund 의 별도 audit action** | PR134 는 `TICKET_FORCED_REFUNDED` 1 action 으로 유지. partial / full 구분은 `afterValue.fullRefund` 플래그. |
| **forced refund 의 별도 알림 카피** | partial forced refund 의 buyer 알림은 PR117 `applyPartialRefund` 알림 ("부분 환불이 처리되었어요") 그대로 재사용. 운영 vs 일반 환불의 알림 카피 분리는 후속 PR. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **부분 환불 webhook** | PG 가 partial cancel webhook 을 보낼 가능성은 본 PR 범위 밖. `PaymentStatus.PARTIALLY_REFUNDED` webhook 입력은 무시. |
| **Webhook 환불 audit** | `refund.completed` webhook 흐름은 audit 미기록 그대로 (PG-driven, actor 없음). |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 사이클의 release notes 에 있던 다음 항목은 본 사이클에서 채워졌으므로 제거됐다:

- **"부분 forced refund"** → PR134 + PR135 으로 구현. `AdminForcedRefundRequest.amount` optional + 부분/전액 라디오 + cascade 분기 + audit afterValue 확장. PR106 의 전액 동작은 amount=null 시 그대로 유지.

---

## 7. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (회귀 가드)

- §13 결제 플로우 / §14 환불 플로우 / §15 결제·환불·재신청 정합성 / §16 재신청 — 본 묶음은 일반 환불 정책 무변경
- §22 ADMIN 강제 환불 (PR106) — 회귀. PR134 의 amount=null 경로가 PR106 동작과 동일해야 함

### 운영 콘솔 — 본 묶음의 핵심

- §12 / §19 / §23 / §24 / §25 / §26 / §27 / §28 — 기존 환불 audit / actor stats / detail enrichment / quick filter / archive enrichment / CSV columns — 회귀
- **§29 — 부분 강제 환불 (PR134 / PR135)** — 본 사이클의 신규 11 항목:
  - 환불 방식 라디오 (전액 default + PARTIAL 토글)
  - 부분 환불 amount 1 / 0 / 비움 / over remaining 각 케이스
  - confirm dialog 의 방식 / 금액 / cascade 명시
  - result card 의 누적 / 잔여 / 환불 유형 Badge
  - USED ticket 부분 강제 환불
  - partial → full forced refund cascade chain
  - 400 error mapping
  - audit afterValue 의 8 키 확인 (기존 4 + 신규 4)
  - 일반 환불 (`POST /tickets/{id}/refund`) 무변경 회귀

### 알림 (PR104 영향, 본 묶음 무변경)

- §20 / §21

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

`./gradlew.bat test` 가 cold-start 일 때 kotlin daemon 의 caches-jvm lock 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat test` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록).

본 묶음은 새 migration 이 없다 (V11 까지 그대로). DTO optional 필드 추가 + audit JSON 키 추가 + CSV 헤더 무변경 (PR131 이미 추가된 컬럼만 자동 채움) 라 이전 frontend bundle / 외부 도구 모두 호환된다.

---

## 9. 다음 사이클 (push 이후 추천)

본 묶음으로 부분 강제 환불 사이클이 닫혔다. 다음 사이클의 후보:

1. **PR138 옵션 A — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 카운트 일치 검증. 큰 backend PR — 부분 강제 환불까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다.
2. **PR138 옵션 B — CSV 에 buyer / event title lookup 컬럼**: 외부 도구가 한 CSV 로 닉네임까지 보고 싶을 때. join-loaded query 또는 batch lookup 으로 N+1 회피 필요. 큰 backend PR.
3. **PR138 옵션 C — 환불 실패 큐 / 자동 재시도**: `refund.failed` webhook 의 deadletter + 운영자 retry 도구. backend + frontend + 알림.
4. **PR138 옵션 D — Forced refund 의 별도 buyer 알림 카피**: 운영 vs 일반 환불의 알림 메시지 분리. 작은 frontend / backend 묶음.

옵션 A 는 안정성. 옵션 B 는 외부 도구 호환 확장. 옵션 C 는 환불 실패의 신뢰성. 옵션 D 는 buyer-facing 카피 polish.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR130-PR136.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
