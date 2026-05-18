# Local Release Bundle — PR117 to PR119

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 3 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `<PR119 docs commit>` (커밋 직후 갱신) |
| Ahead | **3 commits** |
| First ahead | `a84c950 feat(payment): add partial refund foundation` (PR117) |
| 작성 시점 | 2026-05-18 |

직전 push (PR115 + PR116, 2 커밋 = forced refund audit detail enrichment + release bundle 문서) 가 origin 에 반영된 위에 얹은 **부분 환불 도입 한 사이클**. PR117 은 backend foundation, PR118 은 frontend UX, PR119 는 정책/구조 문서 + 본 release bundle 갱신.

---

## 1. 커밋 묶음 요약

### Partial refund — backend foundation + frontend UX + docs

| commit | PR | 요약 |
|---|---|---|
| `a84c950` | PR117 | 부분 환불 backend foundation. V11 `payment_attempts.refunded_amount BIGINT NOT NULL DEFAULT 0` migration + `TicketStatus.PARTIALLY_REFUNDED` + `PaymentStatus.PARTIALLY_REFUNDED` enum + `PaymentAttempt` 헬퍼 (`remainingRefundableAmount()` / `markPartiallyRefunded(delta, reason)` / `markFullyRefunded(reason)` — `markRefunded` 는 fully 로 위임) + `RefundTicketRequest.amount: Long? = null` 추가 (null → 남은 환불 가능 금액 전체 = 기존 전액 동작) + `RefundTicketResponse` 에 `refundedAmount` / `remainingRefundableAmount` 필드 추가 + `InvalidRefundAmountException` (400) 신규 + `PaymentService.refundPaymentByTicket` 의 부분 환불 분기 (`applyPartialRefund` 헬퍼 — participation / capacity 무변경, partial 알림 카피) + 누적 도달 시 기존 `markRefundedInternal` (full cascade) 자동 진입 + `forceRefundByAdmin` 가 PARTIALLY_REFUNDED 티켓도 허용 (remaining 만큼 cancel 호출 후 REFUNDED 로 cascade) + PR117 신규 6 테스트 케이스 (부분 환불 성공 / 누적 후 full cascade / amount=0/-1/over → 400 / amount=null 회귀 / PARTIALLY_REFUNDED 티켓 추가 환불) |
| `4a3f646` | PR118 | 부분 환불 frontend UX. `TicketStatus` / `PaymentStatus` union 에 `PARTIALLY_REFUNDED` 추가 (backend enum 동기화) + `RefundTicketRequest.amount?` / `RefundTicketResponse.refundedAmount` / `remainingRefundableAmount` 타입 반영 + `TicketDetailPage` 의 환불 진입을 inline form 으로 교체 (전액/부분 라디오 + amount input + 사유 textarea + 진행/취소 버튼) + 클라이언트 사전 검증 (1 이상 정수 / remaining 이하) + 부분 환불 후 form 유지 ("추가 환불 요청" 진입) + REFUNDED 도달 시 form 자동 닫힘 + `TICKET_STATUS_LABEL` 3곳에 "부분 환불됨" (warning tone) 추가 + MyPage `isTerminalTicket` 가드는 여전히 REFUNDED/CANCELED 만 (PARTIALLY_REFUNDED 는 active) + `payment.css` `.ct-ticket-refund-form` / `.ct-ticket-refund-radio` / `.ct-ticket-refund-actions` 신설 + 4xx 친화 카피 (400 + "환불 금액..." → "환불 금액을 확인해주세요") |
| `<PR119>` | PR119 | 정책 / 구조 문서. `docs/payment-refund-policy.md` §11.7 / §13.7 의 "부분 환불" Known exclusion 제거 + 새 §14 "PR117 — 부분 환불 (Partial Refund)" 섹션 (정책 요약 / 스키마 / 엔티티 헬퍼 / cascade 전이 / frontend / 의도적 제외) + §1.1 TicketStatus 표에 PARTIALLY_REFUNDED 추가. `docs/architecture.md` §5.2 환불 흐름 표에 partial branch 추가 + §5.2.1 forced refund 의 상태 허용 PARTIALLY_REFUNDED 추가 + §10 Known Exclusions 의 "부분 환불" → 구현됨으로 변경 + "부분 forced refund" 신규 항목 + §11 PR history 에 PR116/PR117/PR118 entry. `docs/manual-qa-checklist.md` §14 환불 플로우에 부분 환불 / 추가 환불 / 1원 미만·remaining 초과 검증 / cascade 확인 / MyPage badge 유지 11 항목. 본 release-notes 갱신 (PR117~PR119 묶음 self-audit). |

**본 사이클 결과**: 사용자가 `/tickets/{id}` 에서 결제 금액의 일부만 환불받고 참가 자격은 유지할 수 있다. 누적 환불액이 결제 금액에 도달하면 기존 전액 환불 cascade (정원 회복 + participation CANCELED + Ticket.REFUNDED) 가 발동. ADMIN forced refund 는 운영 정책상 항상 한 번에 남은 금액 전액을 환불해 REFUNDED 로 cascade — PR117 의 PARTIALLY_REFUNDED 티켓도 forced refund 한 번이면 끝까지 처리된다.

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
## main...origin/main [ahead 3]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 3. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR117 (`a84c950`) | backend 좁은 `--tests *PaymentServiceTest` | green (2m 19s) — 신규 6 케이스 (부분 환불 성공 + 부분 → full cascade + amount=0/-1/over + null 회귀 + PARTIALLY_REFUNDED 추가 환불) + 기존 PR42 / PR43 / PR78 / PR81 / PR82 / PR106 회귀 가드 통과 |
| PR117 (`a84c950`) | backend full `gradle test` | green (1m 16s, BUILD SUCCESSFUL) — `PaymentAttempt` 엔티티 변경 (`refundedAmount` 컬럼 추가) + `TicketStatus` / `PaymentStatus` enum 확장 + `RefundTicketResponse` shape 변경에 대해 모든 의존 service / controller / repository test 회귀 없음 |
| PR118 (`4a3f646`) | frontend `npm run build` | green (101 modules, 812ms — `tsc -b` + Vite 모두 통과). frontend type 확장 (`RefundTicketRequest.amount`, `RefundTicketResponse.refundedAmount/remainingRefundableAmount`, `TicketStatus.PARTIALLY_REFUNDED`, `PaymentStatus.PARTIALLY_REFUNDED`) 가 모든 호출처에서 정상 인식 |
| PR119 (`<commit>`) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR118 (`4a3f646`).

**마지막 전체 backend `gradle test` green**: PR117 (`a84c950`).

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고). 실제 본 사이클 내에서도 PR117 좁은 테스트 첫 시도가 daemon cache lock 으로 실패 → `--stop` 후 재시도 green 으로 회복한 사례 있음.

---

## 4. 운영 / 배포 주의사항

### Flyway 마이그레이션 — V11 신규

**`V11__add_payment_attempt_refunded_amount.sql`** — `ALTER TABLE payment_attempts ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0`.

- **기존 row 안전**: default 0 으로 자동 채워진다. 운영 환경의 기존 PaymentAttempt 들은 `refundedAmount = 0` 으로 시작 → `remainingRefundableAmount = amount` (= 결제 금액 전체) 로 인식되어 정책 변경 전 환불 흐름과 동일하게 동작.
- **이미 환불된 row** (PR42 이전 환불): `refundedAt != null` 이지만 `refundedAmount = 0` 상태. 새 코드는 `remainingRefundableAmount` 가 amount 이므로 추가 환불 가능하게 보이는 회귀 가능성 있다 — 이를 막기 위해 `refundPaymentByTicket` 는 `ticket.status == REFUNDED` 분기에서 멱등 응답을 먼저 처리한다 (gateway 재호출 없이). 즉 ticket.status 가 권위 있는 가드. ADMIN forced refund 도 같은 가드.
- **새 enum**: `TicketStatus.PARTIALLY_REFUNDED` / `PaymentStatus.PARTIALLY_REFUNDED` 는 Kotlin enum 으로만 추가 — DB 의 `status` 컬럼은 `VARCHAR(20)` 이라 새 enum 값 (`PARTIALLY_REFUNDED`, 19자) 을 그대로 수용 가능. schema migration 불필요.

### 부분 환불 정책 (PR117)

- **사용자 흐름** (`POST /tickets/{id}/refund`):
  - `amount` null → 남은 환불 가능 금액 전체 환불 (= 기존 전액 동작 회귀)
  - `amount` 지정 → `1 <= amount <= remainingRefundableAmount` 검증, 위반 시 400 `InvalidRefundAmountException`
  - 누적이 결제 금액 미만이면 PARTIALLY_REFUNDED (참가/정원 무변경, partial 알림)
  - 누적이 결제 금액에 도달하면 REFUNDED (full cascade: 정원-- / participation CANCELED / full 알림)
- **USED / deadline / 권한 가드**: PR42 / PR43 그대로. USED 티켓 부분 환불 시도 → `TicketAlreadyUsedException`. 시작 이후 → `RefundDeadlinePassedException`.
- **ADMIN forced refund** (`/admin/tickets/{id}/forced-refund`): 정책 무변경 — 항상 남은 금액 전체를 환불해 REFUNDED 로 cascade. PARTIALLY_REFUNDED 티켓에 호출하면 remaining 만큼 cancel 후 REFUNDED. 운영 의미: "이 티켓의 환불을 한 번에 끝낸다".
- **알림**: `NotificationType.REFUND_COMPLETED` 재사용 (새 type 도입 X). 메시지 카피로 partial/full 구분.
- **webhook**: `PaymentStatus.PARTIALLY_REFUNDED` 가 webhook 입력으로 도착하면 로그 후 skip (지원하지 않음). PR42 webhook 정책 변경 없음.

### 동시 환불 race

같은 attempt 에 두 사용자가 동시에 refund 호출하면 (예: buyer + owner) `remainingRefundableAmount` 계산이 race condition 으로 일관되지 않을 수 있다. 본 PR 은 별도 lock 을 두지 않고 backend 검증을 신뢰 — 후순위 호출이 400 으로 거부된다. PG 측에서도 cancelAmount 가 잔액을 초과하면 거부될 것. 운영 빈도가 낮아 race 발생률은 미미하며, PR118 UI 는 사용자가 자기 view 의 remaining 으로 검증하므로 자기 자신 더블 클릭은 form 의 `disabled` 가드로 차단.

### CSV export / audit / archive 무변경

- 일반 사용자 부분 환불은 audit log (`moderation_audit_logs`) 를 만들지 않는다 (PR42 정책 그대로 — 일반 환불은 audit 비대상).
- ADMIN forced refund 는 `TICKET_FORCED_REFUNDED` audit row 1건. afterValue JSON 의 `amount` 는 attempt.amount (총 결제 금액) 가 그대로 — partial refund 의 누적 amount 가 아님. PR115 의 forcedRefundContext enrichment 도 그대로 동작.
- CSV export 의 컬럼 / 행은 PR114 이전과 동일 — `refundedAmount` 등 새 필드는 포함되지 않음.

### Frontend status 표시 (PR118)

- 모든 `TICKET_STATUS_LABEL` (TicketDetailPage / MyPage / TicketCheckInPage) 에 "부분 환불됨" (warning tone) 추가.
- MyPage 의 `isTerminalTicket = REFUNDED || CANCELED` — PARTIALLY_REFUNDED 는 active 로 취급, "참가 확정" highlight + "티켓 보기" 버튼 유지. 사용자 직관에 부합 (참가 자격은 유지되므로 ticket 진입 가능).
- TicketDetailPage 의 `isUsable = PAID || PARTIALLY_REFUNDED` — QR / 체크인 코드 활성. 부분 환불을 받아도 행사 입장 가능.

---

## 5. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 forced refund** | ADMIN `/admin/tickets/{id}/forced-refund` 는 PR117 부터 PARTIALLY_REFUNDED 티켓도 받지만 항상 한 번에 remaining 전체를 환불 (cascade). 부분 금액 forced refund 는 별도 endpoint 또는 옵션 도입 필요. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 환불 도입으로 batch 가 더 복잡해졌지만 본 PR 범위 밖. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **부분 환불 동시 race** | 별도 lock 없음. 후순위 호출이 400 으로 거부될 뿐 — race 빈도 낮아 의도적으로 lock 도입 보류. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **부분 환불 webhook** | PG 가 partial cancel webhook 을 보낼 가능성은 본 PR 범위 밖. `PaymentStatus.PARTIALLY_REFUNDED` webhook 입력은 무시. |
| **부분 환불 audit** | 일반 사용자 부분 환불은 audit log 비대상 (PR42 정책 그대로). ADMIN forced refund 만 `TICKET_FORCED_REFUNDED` audit. |
| **CSV export 의 partial 정보** | CSV 는 audit 원본 10 컬럼 그대로. 부분 환불 누적 보기를 batch 로 받고 싶다면 별도 export endpoint 필요. |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 사이클의 release notes 에 있던 다음 항목은 본 사이클에서 채워졌으므로 제거됐다:

- **"부분 환불 미구현 — 일반 사용자/owner 환불 흐름"** → PR117 으로 구현. ADMIN forced refund 의 부분 금액 호출은 여전히 미구현 (위 follow-ups 의 "부분 forced refund" 항목으로 분리).

---

## 6. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (본 묶음 영향)

- §13 결제 플로우 (PR74) — 회귀 가드
- **§14 환불 플로우** — 본 묶음의 핵심:
  - PR42 기존 항목 (전액 환불 회귀)
  - **PR118 신규**: inline 환불 form (전액/부분 라디오) + amount input + 사유 + 진행/취소 + 부분 환불 후 form 유지 + 추가 환불 + 누적 도달 시 cascade + 1원 미만 / remaining 초과 사전 차단 + 400 친화 카피
  - **PR118 신규**: PARTIALLY_REFUNDED 티켓 MyPage 카드는 active 로 유지 + status Badge "부분 환불됨" warning + QR 활성
- §15~§16 재신청 / 결제 알림 라우트 가드 — 회귀
- §22 ADMIN 강제 환불 (PR106/PR111/PR112/PR113) — 회귀. PARTIALLY_REFUNDED 티켓에 forced refund 호출 시 정상 REFUNDED 로 cascade 되는지 spot-check 권장

### 운영 콘솔

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (PR93 + PR109) — 회귀
- §23 운영자 활동 강제 환불 카운트 (PR109) — 회귀

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

`./gradlew.bat test` 가 cold-start 일 때 `PermissionIntegrationTest` 등 Spring context 초기화에서 flaky 가 보일 수 있다. 같은 명령 재실행으로 회복되면 본 묶음의 변경과 무관하다고 본다 (PR74 stabilize 시리즈 기록). 본 사이클 내에서도 PR117 좁은 테스트 첫 시도가 `caches-jvm` 잠금 실패 → `--stop && rerun` 으로 회복한 사례 있음.

본 묶음은 V11 migration 을 포함한다. Flyway 가 부팅 시 자동 적용 — staging 에 deploy 할 때 V10 → V11 migration 이 한 번 돌고, 이후 cold-start 부팅 로그에서 V11 적용 라인을 확인하면 안전.

---

## 8. 다음 사이클 (push 이후 추천)

본 사이클로 부분 환불 1단계가 완결됐다. 다음 사이클의 후보:

1. **PR120 — Partial refund regression hardening**: 정책/UI 안정성 게이트. PaymentServiceTest 에 부분 환불 여러 번 누적 / cascade / full 후 재환불 차단 / PARTIALLY_REFUNDED 상태 reapply 가드 + EventServiceTest 에 PARTIALLY_REFUNDED participation 의 AlreadyJoined 유지 + Admin forced refund 의 PARTIALLY_REFUNDED 처리 + frontend label coverage spot-check. 본 PR 시퀀스의 마지막 단계 — 즉시 진행 권장.
2. **PR121 옵션 A — 부분 forced refund**: ADMIN 의 `/admin/tickets/{id}/forced-refund` 에 optional amount 추가 (현재는 한 번에 remaining 전체). 운영자가 노쇼 보상의 일부만 돌려주는 케이스에 사용. backend + frontend.
3. **PR121 옵션 B — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 REFUNDED/PARTIALLY_REFUNDED 카운트 합산과 일치 검증. 부분 환불 도입으로 정합성 위험이 커진 만큼 운영 안정성에 가치 큼. 큰 backend PR.
4. **PR121 옵션 C — 부분 환불 audit log**: 일반 사용자 부분 환불도 audit 에 기록 (현재는 ADMIN forced refund 만 audit). 환불 이력 추적 운영 가치. 작은 backend PR.

옵션 1 (PR120) 은 본 사이클의 자연스러운 클로저. PR121 은 별도 사이클 권장.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR117-PR119.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
