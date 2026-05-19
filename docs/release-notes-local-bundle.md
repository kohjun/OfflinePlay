# Local Release Bundle — PR128

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 1 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `d1d1078 feat(admin): add user refund audit filters` |
| Ahead | **1 commit** |
| First ahead | `d1d1078 feat(admin): add user refund audit filters` (PR128) |
| 작성 시점 | 2026-05-19 |

직전 push (PR122~PR127, 6 커밋 = 사용자 환불 audit 사이클 — 기록 + 표시 + detail enrichment + 문서) 가 origin 에 반영된 위에 얹은 **사용자 환불 audit quick filter 보강** 한 묶음. PR128 단일 frontend 보강 — `/admin?tab=audit-logs` 의 빠른 필터 chip row 에 `부분 환불` / `환불 완료` 2개 chip 추가 (PR113 "강제 환불" chip 패턴 그대로). 운영자가 select dropdown 을 거치지 않고 한 클릭으로 `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` row 만 필터링.

backend / API / DB / migration / audit 기록 정책 / CSV export / 환불 정책 모두 무변경 — frontend 한 파일에 chip 2개 추가하고 기존 `auditFilters.action` state 를 재사용한다.

---

## 1. 커밋 묶음 요약

### User refund audit quick filters

| commit | PR | 요약 |
|---|---|---|
| `d1d1078` | PR128 | **User refund audit quick filter chips**. `AdminAuditLogsSection.tsx` 의 `.ct-audit-quick-filters` row 에 `부분 환불` (`PAYMENT_PARTIALLY_REFUNDED`) + `환불 완료` (`PAYMENT_REFUNDED`) 2개 chip 추가. 기존 `강제 환불` (`TICKET_FORCED_REFUNDED`, PR113) chip 과 동일 패턴 — 클릭 시 `auditFilters.action` toggle, 같은 action 재클릭 시 해제, 클릭마다 `auditPage` / `archivedPage` 0 으로 reset. 세 chip 은 같은 state 를 공유해 상호 배타 (한 번에 하나만 active). 액션 select dropdown 과 양방향 동기화 — chip 클릭 → select 자동 갱신, select 변경 → chip active style 자동 동기화. "필터 초기화" 버튼이 `EMPTY_AUDIT_FILTERS` 로 reset 하면 세 chip 모두 한 번에 inactive (별도 hookup 불필요). `aria-pressed` + title tooltip 으로 a11y. CSS `.ct-audit-quick-filters` 의 `flex-wrap: wrap` 이 모바일 420px 폭 wrap 자동 처리 — 새 스타일 추가 없음. label / tone / options 3 map 은 PR122 에서 이미 두 action 을 커버하므로 추가 변경 없음. backend / API / DB / 마이그레이션 / audit 기록 / CSV / 환불 정책 전부 무변경 — `auditListEndpoint` 의 기존 `action` query parameter 만 사용. docs: manual-qa §26 12 항목 + architecture.md 한 줄. |

**본 사이클의 결과**: 운영자가 `/admin?tab=audit-logs` 의 chip 한 번 클릭으로 부분 환불 / 환불 완료 / 강제 환불 세 종류 audit row 를 즉시 분리해 본다. PR113 + PR128 의 chip row 가 PR93/PR109/PR124 의 actor stats 카드와 PR115/PR126 의 detail enrichment panel 사이를 빠르게 잇는 진입 동선이 된다.

---

## 2. PR128 운영 가치 — 진입 동선 단축

PR128 이전 운영자가 일반 사용자 환불 audit 만 보려면:

1. `/admin?tab=audit-logs` 진입
2. 액션 select 열기
3. 13개 옵션 중 "부분 환불" 또는 "환불 완료" 선택
4. 목록 갱신 대기

PR128 이후:

1. `/admin?tab=audit-logs` 진입
2. 빠른 필터 row 의 chip 1개 클릭

세 chip 의 좌→우 순서는 `강제 환불` → `부분 환불` → `환불 완료`. ADMIN 강제 환불은 endpoint 가 다르므로 처음, 일반 사용자 환불은 cascade 진행 순으로 `부분 → 완료`. 운영자가 비정상 케이스 (강제 환불) 부터 정상 케이스 (일반) 로 자연스럽게 시선이 흐르도록 의도.

archive 탭 (`tab=archived`) 으로 전환해도 chip 들은 그대로 보이며 동일한 `action` 필터가 archive list endpoint 에도 적용된다 — archive 가 active 와 같은 backend query 를 쓰므로 별도 wiring 불필요.

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
## main...origin/main [ahead 2]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

PR129 (본 release-notes 갱신) 까지 포함하면 2 ahead. 위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 4. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR128 (`d1d1078`) | frontend `npm run build` | green (101 modules, 811ms — `tsc -b` + Vite 모두 통과). chip 2개 추가에 대해 `Record<ModerationAuditAction, ...>` exhaustiveness 가 PR122 label/tone/options 매핑을 그대로 사용해 회귀 없음 |
| PR128 (`d1d1078`) | backend full `gradle test` | **생략** — 변경 없음 (frontend + docs only) |
| PR129 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR128 (`d1d1078`).

**마지막 전체 backend `gradle test` green**: PR126 (`087ed8d`, 직전 push 묶음의 마지막 backend 변경). PR128 은 backend 미변경이므로 같은 baseline 유지.

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고).

---

## 5. 운영 / 배포 주의사항

### Flyway 마이그레이션 — 없음

PR128 은 **새 V 마이그레이션 없음**. 전체 마이그레이션 범위는 V1~V11 그대로 (PR117 V11 이 마지막).

### Backend / API / DB — 무변경

PR128 은 frontend 한 파일 + 문서 2 파일만 — 다음 보장:

- **endpoint / path / params / 권한 무변경** — `/api/v1/admin/moderation/audit-logs` 의 `action` 쿼리 파라미터는 PR62 부터 이미 모든 `ModerationAuditAction` 값을 받는다. chip 은 기존 파라미터를 다른 진입점에서 set 할 뿐.
- **audit 기록 정책 무변경** — PR122 의 `recordUserRefundAudit` 흐름, `forceRefundByAdmin` 흐름, webhook 흐름 모두 그대로.
- **detail enrichment 무변경** — PR115 `forcedRefundContext`, PR126 `paymentRefundContext` 모두 그대로 작동. chip 필터링과 detail 펼침은 독립.
- **CSV export 무변경** — PR63 의 10 컬럼 그대로. chip 은 list endpoint 만 통과.

### Frontend state 공유 — 핵심 정합성

세 chip (`강제 환불` / `부분 환불` / `환불 완료`) + 액션 select + "필터 초기화" 버튼은 모두 같은 `auditFilters.action` 단일 state 를 읽고 쓴다. 다음 정합성:

- **상호 배타** — 한 번에 한 chip 만 active. 다른 chip 클릭 시 직전 chip 자동 inactive (state 가 새 값으로 덮어쓰여짐).
- **양방향 동기화** — select 에서 선택 → chip active style 자동 갱신. chip 클릭 → select 자동 변경.
- **Reset 일관성** — "필터 초기화" 버튼이 `EMPTY_AUDIT_FILTERS` (`action=''`) 로 reset → 세 chip 모두 inactive + select "전체" 복귀.
- **Archive tab 자동 적용** — 같은 `auditFilters.action` 이 active list + archive list 양쪽 endpoint 에 전달됨. chip 들을 archive 탭에서도 그대로 사용 가능.
- **Page reset** — chip 클릭마다 `setAuditPage(0)` + `setArchivedPage(0)` — 필터 갱신 직후 첫 페이지부터 다시 본다 (PR113 동작 그대로).

### 모바일 / 접근성

- CSS `.ct-audit-quick-filters` 의 `flex-wrap: wrap` 이 모바일 420px 폭에서 chip 들의 줄바꿈 자동 처리.
- 세 chip 모두 `aria-pressed` 적용 — devtools accessibility tree 에서 toggle button 으로 인식.
- title tooltip 에 action 값 + 재클릭 해제 안내 ("재클릭 해제" 문구).

---

## 6. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 forced refund** | ADMIN `/admin/tickets/{id}/forced-refund` 는 PR117 부터 PARTIALLY_REFUNDED 티켓도 받지만 항상 한 번에 remaining 전체를 환불 (cascade). 부분 금액 강제 환불은 별도 endpoint 또는 옵션 도입 필요. |
| **CSV export 에 enrichment 컬럼** | CSV 는 audit 원본 10 컬럼 그대로 — PR115 / PR126 enrichment 는 detail endpoint 응답에만. 운영자가 CSV 한 줄로 buyer/event/channel 까지 보고 싶다면 별도 CSV 확장 PR 필요 (컬럼 호환성 신중). |
| **Archive audit detail enrichment** | archive 단건 / archive 리스트 응답은 PR67 의 shape 그대로 — PR115 / PR126 enrichment 미적용. archive 진입 빈도가 낮아 의도적으로 보류. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 부분 환불 + audit + actor stats + detail enrichment + quick filter 까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다. |
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

- **"PAYMENT_REFUNDED quick filter chip"** → PR128 으로 구현. `부분 환불` / `환불 완료` 2개 chip 이 audit-logs 화면의 빠른 필터 row 에 추가됨. 기존 `강제 환불` chip (PR113) 과 같은 state 공유 + 같은 패턴.

---

## 7. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (회귀 가드)

- §13 결제 플로우 / §14 환불 플로우 / §15 결제·환불·재신청 정합성 / §16 재신청 — PR128 은 audit 기록 / 환불 실행 로직 무변경이라 직접 영향 없음
- §22 ADMIN 강제 환불 — 회귀. `강제 환불` chip 의 PR113 동작이 그대로 유지되는지 확인

### 운영 콘솔 — 본 묶음의 핵심

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (PR93) — 회귀
- §23 운영자 활동 강제 환불 카운트 (PR109) — 회귀
- §24 운영자 활동 사용자 환불 카운트 (PR124) — 회귀
- §25 사용자 환불 audit 상세 enrichment (PR126) — 회귀. chip 필터 active 상태에서 row 펼침 시 `PaymentRefundContextPanel` 정상 노출 확인 (chip 필터와 detail enrichment 는 독립)
- **§26 사용자 환불 audit quick filter chip (PR128)** — 본 사이클의 신규 12 항목:
  - `부분 환불` chip 클릭 → `PAYMENT_PARTIALLY_REFUNDED` row 만 표시 + 페이지 reset + select 동기화
  - `환불 완료` chip 클릭 → `PAYMENT_REFUNDED` row 만 표시 + 페이지 reset + select 동기화
  - `강제 환불` chip 의 PR113 동작이 그대로 (TICKET_FORCED_REFUNDED 만 필터링)
  - 세 chip 상호 배타 — 한 번에 하나만 active, 다른 chip 클릭 시 직전 chip 자동 해제
  - 액션 select 에서 직접 선택해도 chip active style 동기화 (양방향)
  - "필터 초기화" → 세 chip 모두 inactive + select "전체" 복귀
  - chip active 상태에서 row "상세" 클릭 → PR126 `PaymentRefundContextPanel` 정상 노출 (독립 동작)
  - `aria-pressed` + title tooltip 적용 (a11y)
  - archive 탭 전환 → chip 그대로 + 같은 action 필터가 archive 응답에 적용
  - 모바일 420px 폭에서 chip 들이 자연스럽게 wrap
  - backend / API / DB 변경 없음 회귀 가드
  - 직전 active chip 에서 archive 탭으로 갈 때 페이지 reset (chip 클릭 시점에 둘 다 0)

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

본 묶음은 새 migration 이 없다 (V11 까지 그대로). backend 미변경 — chip 은 frontend state toggle 일 뿐이라 직전 frontend bundle 도 깨지지 않는다 (단, chip UI 가 없는 옛 bundle 은 그저 select dropdown 만 보임).

---

## 9. 다음 사이클 (push 이후 추천)

본 묶음으로 사용자 환불 audit 빠른 필터까지 사이클이 닫혔다 — 기록 (PR122) → 표시 (PR124) → detail enrichment (PR126) → 빠른 필터 (PR128) → 문서 (PR123 + PR125 + PR127 + 본 PR129). 다음 사이클의 후보:

1. **PR130 옵션 A — 부분 forced refund**: ADMIN 의 `/admin/tickets/{id}/forced-refund` 에 optional `amount` 추가. 운영자가 노쇼 보상의 일부만 돌려주는 케이스. backend + frontend + audit amount 의미 재정의.
2. **PR130 옵션 B — Archive audit detail enrichment**: PR67 archive 단건 응답에도 PR115 / PR126 패턴 확장. archive 진입 빈도가 낮아 후순위였으나 보존 정책 갱신 시점에 자연스럽게 묶을 수 있음. backend + frontend.
3. **PR130 옵션 C — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 카운트 일치 검증. 큰 backend PR — 부분 환불 + audit + actor stats + detail enrichment + quick filter 까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다.
4. **PR130 옵션 D — CSV export 의 enrichment 컬럼**: CSV 에 buyer/event/channel 컬럼 추가. 외부 도구 호환성 신중 검토 필요. 작은 backend PR.

옵션 A 는 ADMIN 운영 도구 확장. 옵션 B 는 PR115/PR126 의 자연스러운 확장. 옵션 C 는 안정성. 옵션 D 는 운영 외부 도구가 한 CSV 로 buyer/event/channel 까지 보고 싶을 때.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR128.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
