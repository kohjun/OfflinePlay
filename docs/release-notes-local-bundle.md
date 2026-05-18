# Local Release Bundle — PR110 to PR113

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 4 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `9344b09 feat(admin): add forced refund audit filter` |
| Ahead | **4 commits** |
| First ahead | `c08fc16 docs: refresh release bundle after actor stats` (PR110) |
| 작성 시점 | 2026-05-18 |

직전 push (PR104~PR109, 6 커밋 = 알림 설정 마지막 한 칸 + ADMIN 강제 전액 환불 도입 + actor stats forced refund 분류) 가 origin 에 반영된 위에 얹은 **forced refund 운영 도구 마무리 사이클** 한 묶음이다. 본 묶음은 PR106 의 운영 도구를 "실행 → 통계 → 감사 로그 추적" 까지 닫는 4 PR — backend 정책은 PR106 그대로, 모두 admin console UX / audit visibility 개선에 한정된다.

---

## 1. 커밋 묶음 요약

### 사이클 회고 docs — release bundle refresh

| commit | PR | 요약 |
|---|---|---|
| `c08fc16` | PR110 | `docs/release-notes-local-bundle.md` 갱신 — PR104~PR109 묶음을 post-push 회고 노트로 전환. 직전 사이클이 외부에서 push 된 후의 self-audit / 다음 사이클 준비 노트. 코드 변경 없음, docs only |

### 결제 운영 도구 — forced refund console UX 보강

PR106 의 ADMIN 강제 환불 도구가 운영자 실수에 더 안전해지도록 한 세 번의 frontend-only 변경.

| commit | PR | 요약 |
|---|---|---|
| `b50f955` | PR111 | `AdminPaymentToolsSection` UX polish — 정책 안내 bullet 3종 (전액만 / USED·시작 이후 가능 / audit 기록) + reason textarea `aria-describedby` help + confirm dialog 4 항목 명세형 본문 + 결과 카드 labeled grid (티켓 ID / 금액 / 결제 수단 / 결제 시도 ID / 처리 시각 / PG 결제 키) + `Badge` ticketStatus + 통화/시각/provider 라벨 매핑 + 4xx/5xx 친화 카피 (backend message 는 toast details) + `role="status"` + `aria-live="polite"`. `admin.css` 에 `.form-field` / `.ct-forced-refund-notice` / `.ct-forced-refund-result__*` 신설. manual-qa §22 에 PR111 폴리시 6 항목 추가 |
| `981b510` | PR112 | "REFUND" 텍스트 확인 잠금 — `CONFIRM_PHRASE = 'REFUND'` 상수 + `confirmText` state + reason 아래 확인 입력 필드 (label `htmlFor` / input `id` / help `aria-describedby` / `autoCapitalize="characters"`) + `confirmText.trim() === 'REFUND'` 일 때만 실행 버튼 활성 + 미일치 + 비어있지 않을 때 `aria-invalid="true"` + `admin.css` 에 `input[aria-invalid='true']` 빨간 border 룰. 성공 시 세 필드 모두 비움 / 실패 시 모두 유지. `confirmText` 는 클라이언트 잠금 — API payload (`{ reason }`) 무변경. manual-qa §22 에 PR112 항목 6개 (chip activation / case-sensitive / trim / aria-invalid / reset on success / preserve on error / Network payload 확인) 추가 |
| `9344b09` | PR113 | `AdminAuditLogsSection` 에 forced refund quick filter chip — `.ct-audit-quick-filters` row + "강제 환불" chip (`.chip` / `.chip.is-active` 재사용) + `aria-pressed` + 클릭 시 `auditFilters.action` 을 `TICKET_FORCED_REFUNDED` 로 toggle + page reset + 재클릭 해제. `ModerationAuditAction` union 에 `TICKET_FORCED_REFUNDED` + `AUDIT_LOGS_ARCHIVED` 추가 (PR65/PR106 잔존 mismatch 해소, backend enum 과 동기화) → `AUDIT_ACTION_LABEL` ("강제 환불" / "감사 로그 아카이브") / `AUDIT_ACTION_TONE` (warning / neutral) / `AUDIT_ACTION_OPTIONS` 세 곳 동시 확장. select dropdown 에도 두 옵션 추가. manual-qa §22 에 PR113 8 항목 (chip activation / select 동기화 / action Badge 라벨·tone / afterValue JSON 4키 / 재클릭 해제 / 필터 초기화 연동 / select dropdown 옵션 / aria-pressed) 추가 |

본 묶음의 결과: 운영자가 `/admin?tab=payments` 에서 강제 환불을 실행할 때 (a) 안내 / (b) confirm dialog / (c) "REFUND" 텍스트 확인 / (d) 결과 카드 / (e) `/admin?tab=audit-logs` 의 quick filter 까지 5 단계로 가시화되고, 결과 JSON 에서 ticketId / paymentAttemptId / amount 가 row 확장만으로 확인된다.

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
## main...origin/main [ahead 4]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 3. 검증 기록 (사이클 내 빌드/테스트 결과)

본 묶음은 **backend / API / DB / migration 변경이 전혀 없다** — 4 PR 모두 docs / frontend 한정. 그래서 backend `gradle test` 는 사이클 내 명시적으로 생략했고, 회귀 위험은 frontend build 의 `tsc -b` 타입 체크가 게이트.

| 시점 | 검증 | 결과 |
|---|---|---|
| PR110 (`c08fc16`) | docs-only | build/test 생략 |
| PR111 (`b50f955`) | frontend `npm run build` | green (101 modules, 808ms) |
| PR112 (`981b510`) | frontend `npm run build` | green (101 modules, 729ms) |
| PR113 (`9344b09`) | frontend `npm run build` | green (101 modules, 777ms) |

**마지막 frontend `npm run build` green**: PR113 (`9344b09`).

**마지막 전체 backend `gradle test` green**: 직전 사이클의 PR109 (`dc8afb4`) — 본 묶음은 backend 무변경이라 회귀 가능성 없음. push 직후 CI 가 전체 `./gradlew.bat test` 를 cold-start 로 통과해야 한다.

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고).

---

## 4. 운영 / 배포 주의사항

### Flyway 마이그레이션

**본 묶음은 새 V 마이그레이션 없음.** 4 PR 모두 frontend / docs 한정 — DB schema 변경 0. 전체 마이그레이션 범위는 V1~V10 그대로 (직전 push 의 PR95 V10 이 마지막).

### Forced refund backend 정책 (PR106 그대로 — 본 묶음 무변경)

본 묶음의 PR111~113 은 **PR106 의 backend 정책을 일절 건드리지 않는다**. 즉:

- 환불 endpoint (`POST /admin/tickets/{id}/forced-refund`), payload (`{ reason }`), 권한 (ADMIN only), audit 기록 (`TICKET_FORCED_REFUNDED`), buyer 알림 (`REFUND_COMPLETED`, 운영 사유 미포함) — 모두 PR106 동작 그대로.
- **전액 환불만 지원** — `attempt.amount` 그대로. 부분 환불은 미구현 (`Ticket.refundedAmount` 컬럼 없음, payment-refund-policy.md §13.7 참고).
- 일반 사용자 환불 경로 (`POST /tickets/{id}/refund`) 의 deadline / USED 가드는 무변경 — 일반 사용자는 시작 전 + PAID 만 환불 가능. ADMIN 만 본 운영 도구를 통해 우회.

### Forced refund 운영 콘솔 UX (PR111~PR113 신규)

본 묶음의 행동 변화는 **운영자가 보는 화면** 한정:

- **PR111** — 정책 안내 bullet 3종 + 결과 카드 라벨 grid + 통화/provider 라벨 매핑 + a11y (`role="status"` / `aria-live` / `aria-describedby`) + 4xx/5xx 친화 카피.
- **PR112** — 실행 버튼이 `ticketId + reason + "REFUND" 확인 문구` 세 필드 모두 통과해야 활성. `confirmText` 는 클라이언트 잠금 — backend payload (`{ reason }`) 그대로. 성공 시 세 필드 모두 비움 (재실행 사고 차단), 실패 시 모두 유지 (수정 후 즉시 재시도).
- **PR113** — `/admin?tab=audit-logs` 에 "강제 환불" quick filter chip. chip 활성 시 `auditFilters.action = 'TICKET_FORCED_REFUNDED'` 로 set 되고 select dropdown 도 동기화 — 같은 backend action 값을 chip / select 두 진입점에서 호출. backend 검색 API 호출 형태 동일.

### Frontend type ↔ backend enum 동기화 (PR113)

- `frontend/src/types/moderation.ts` 의 `ModerationAuditAction` union 에 `AUDIT_LOGS_ARCHIVED` (PR66) + `TICKET_FORCED_REFUNDED` (PR106) 두 enum 값이 추가됐다. backend `com.OfflinePlay.domain.admin.entity.ModerationAuditAction` 와 정확히 11개 값으로 일치.
- 잔존 mismatch 해소 — 직전 사이클 (PR65 archive / PR106 forced refund) 에서 enum 만 추가됐고 frontend type 에는 반영되지 않았던 행을 정리. 이제 `AUDIT_ACTION_LABEL` / `AUDIT_ACTION_TONE` 가 Record key exhaustiveness 를 강제하므로, 다음 enum 추가 시 frontend 빌드가 즉시 깨져 알림.
- 액션 select dropdown 에도 두 옵션 추가 — 운영자가 select 로도 같은 필터 적용 가능.

### Audit 아카이브 스케줄러 (PR68~70 기존, 본 묶음에서 변경 없음)

- 디폴트 OFF (V8 seed). 본 push 자체로 동작이 바뀌지 않는다.
- 단, PR106 의 `TICKET_FORCED_REFUNDED` audit row 가 같은 `moderation_audit_logs` 테이블에 누적되므로, archive scheduler 가 활성화된 운영 환경에서는 환불 audit 도 retention 정책에 따라 archive 된다 (archive 후에도 `moderation_audit_log_archive` 에서 조회 가능). PR113 quick filter 는 active 테이블 기준 — archive 된 row 는 "아카이브" 탭에서 별도 확인.

### 결제 hardening (PR40~42 기존, 무변경)

`PaymentHardeningCheck` 가 부팅 시 fail-fast — 변경 없음. ADMIN 강제 환불도 같은 `paymentGateway.refund` 를 통하므로 운영 환경의 Toss secret key 가 누락되면 동일하게 부팅 차단된다.

---

## 5. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 환불** | 전액만. `cancelAmount = attempt.amount`. PR106 의 강제 환불도 전액 한정 — PR112 의 "REFUND" 확인 문구 + PR111 의 안내 bullet 도 "전액 환불만 가능" 을 명시. `Ticket.refundedAmount` 컬럼 / `TicketStatus.PARTIALLY_REFUNDED` enum 모두 미도입. payment-refund-policy.md §13.7 / §11.7. |
| **부분 환불 시 정원 cascade 정책** | 미결정 — 부분 환불 PR 진행 전 `Event.currentParticipants` 감소 여부 (감소 / 유지 / 운영자 선택) 결정 필요. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED 카운트 일치 batch 없음. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 선택 불가. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal — 변경 이력 / actor / 전·후 값 미저장. 별도 history 테이블 도입은 후속 PR. |
| **Forced refund audit row 의 buyer / event title enrichment** | PR113 의 quick filter + `afterValue` JSON 만으로 ticketId / paymentAttemptId / amount 는 확인 가능하지만, 어떤 buyer 의 어떤 event 환불인지 즉시 보이지 않음. row 확장 시 보조 fetch (buyer email / event title) 가 있으면 운영 가시성 추가. backend 추가 endpoint 또는 join 필요해서 작지 않음. |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 사이클의 release notes 에 있던 다음 항목들은 본 묶음에서 채워졌으므로 제거됐다:

- **"forced refund 운영 콘솔 보강 (audit 검색 / CSV export 필터)"** → PR113 quick filter + `AUDIT_ACTION_OPTIONS` 확장으로 구현. CSV export 는 기존 `exportModerationAuditLogs({ action, ... })` 가 동일 필터를 그대로 받으므로 추가 작업 없이 동작.

---

## 6. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (PR106 기존 정책 회귀 가드)

- §13~§16 — 결제 / 환불 / 재신청 / 결제 알림 라우트 가드 (기존 정책 그대로 동작하는지 회귀 가드)
- **§22 ADMIN 강제 환불 운영 도구** — 본 묶음에서 손이 가장 많이 간 영역. 다음 PR 항목을 한 줄씩:
  - PR106 항목 (실행 / audit / buyer 알림 / 일반 환불 경로 가드)
  - **PR111 항목** — 안내 bullet 3종 / textarea help + aria-describedby / id+htmlFor / confirm dialog 4 항목 / 결과 카드 labeled grid + Badge + role=status / 4xx-5xx 친화 카피 + backend message 보존
  - **PR112 항목** — REFUND 확인 문구 필드 + help + 미일치 시 disabled + aria-invalid + trim 후 일치 시 활성 + 성공 후 세 필드 비움 / 실패 후 유지 + Network payload 에 confirmText 없음
  - **PR113 항목** — `/admin?tab=audit-logs` 의 "강제 환불" quick filter chip + 재클릭 해제 + 필터 초기화 연동 + select dropdown 동기화 + 액션 Badge 라벨/tone (warning) + `afterValue` JSON 4 키 (ticketId / paymentAttemptId / ticketStatus / amount) + aria-pressed

### 운영 콘솔

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (직전 push 의 PR93 + PR109)
- **§23 운영자 활동 강제 환불 카운트 (PR109)** — actor stats breakdown 에 "강제 환불 N" row 가 forcedRefundCount > 0 일 때만 노출 + 0 일 때 회귀 없음 spot-check
- 추가로 PR113 의 audit "감사 로그 아카이브" 라벨 (AUDIT_LOGS_ARCHIVED tone=neutral) 이 archive 실행 audit row 에 잘 표시되는지도 같이 spot-check (직전 사이클까지는 frontend type 미동기화로 빈 라벨이었음)

### 알림 (직전 push 의 PR104 영향, 본 묶음 무변경)

- §20 알림 수신 설정 (기존 동작)
- §20a 알림 묶음 토글
- §20b 알림 카드 Quick Mute + Undo
- §20c 알림 설정 "마지막 저장 시각" (PR104) — 본 묶음에서 변경 없음, 회귀 가드만
- §21 알림 메타데이터 일관성

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

본 묶음은 backend 무변경이라 `gradle test` 는 직전 PR109 green 상태가 그대로 유지되어야 정상. 새 실패가 보이면 cold-start flaky 또는 외부 환경 변화 의심.

---

## 8. 다음 사이클 (push 이후 추천)

본 묶음으로 forced refund 운영 도구 사이클 ("실행 → 통계 → 감사 로그 추적") 이 완결됐다. 다음 사이클의 후보:

1. **PR115 옵션 A — partial refund first step**: `payment-refund-policy.md §13.7 / §11.7` 의 부분 환불 1 단계. `Ticket.refundedAmount` 컬럼 + V11 migration + refund endpoint 의 optional `amount` 인자 + UI 금액 입력. **사전 정책 결정 필요**: 부분 환불 시 `Event.currentParticipants` cascade (감소 / 유지 / 운영자 선택) 및 `TicketStatus.PARTIALLY_REFUNDED` enum 도입 여부. 결제 도메인의 마지막 큰 미구현.
2. **PR115 옵션 B — Push/Email channel preference 1 단계**: NotificationType 차원 위에 `channel` 차원을 추가하는 큰 정책 결정. backend 모델 확장 필요. 정책 결정 선행.
3. **PR115 옵션 C — Forced refund audit row enrichment**: PR113 의 quick filter 결과 row 에 buyer email / event title 보조 fetch 로 사람이 읽기 좋게. `afterValue` JSON 의 ticketId 만으로는 즉시 식별이 어려움. backend 추가 endpoint (또는 기존 ticket detail 재사용) 필요.

옵션 A 는 결제 도메인의 마지막 큰 미구현 — 정책 결정이 큰 만큼 비용도 큼. 옵션 B 는 정책 결정 선행 필요. 옵션 C 는 본 사이클의 자연스러운 확장 — quick filter 까지 닫았으니 row 가독성을 한 단계 더 (단, backend 변경 동반).

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR110-PR113.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
