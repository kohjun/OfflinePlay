# Local Release Bundle — PR130 to PR132

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 3 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `<PR132 docs commit>` (본 문서 commit) |
| Ahead | **3 commits** (예상) |
| First ahead | `0e41e23 feat(admin): enrich archived audit details` (PR130) |
| 작성 시점 | 2026-05-19 |

직전 push (PR128 + PR129) 가 origin 에 반영된 위에 얹은 **환불 audit 가독성 확장 사이클**. PR130 은 PR115 / PR126 의 detail enrichment 를 archive 탭에도 동일하게 적용해 "옛 환불 사고" 도 raw JSON 없이 한 panel 에서 본다. PR131 은 active / archive CSV 모두에 환불 분석용 10 파생 컬럼을 append-only 추가해 외부 도구 (Excel / Sheets) 한 줄로 환불 종류 / 세 금액 / 상태가 보이게 한다. PR132 가 본 문서 + manual QA + architecture 의 enrichment 정책 정리.

backend / frontend 변경의 결과로 새 endpoint / 마이그레이션 / audit 기록 로직 / 환불 정책 / list API JSON shape 는 모두 무변경 — 응답 DTO 의 optional 추가 + CSV 헤더 append-only + 한 helper internal 노출이 전부다.

---

## 1. 커밋 묶음 요약

### Refund audit enrichment expansion (archive + CSV) + docs

| commit | PR | 요약 |
|---|---|---|
| `0e41e23` | PR130 | **Archive audit detail enrichment**. `ArchivedModerationAuditLogResponse` 에 `forcedRefundContext` / `paymentRefundContext` optional 필드 추가. `ModerationAuditLogArchiveService.getArchived` 가 active detail (PR115/PR126) 과 같은 정책으로 enrichment — `TICKET_FORCED_REFUNDED` → forcedRefundContext, `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` → paymentRefundContext. `ModerationAuditLogService.buildForcedRefundContext` / `buildPaymentRefundContext` 의 가시성을 `internal` 로 노출해 archive service 가 같은 helper 재사용 (코드 중복 회피). `listArchived` / `exportArchivedToCsv` 는 enrichment 미적용 (N+1 회피 + CSV 호환). frontend `ArchivedModerationAuditLog` type 에 두 optional context 추가 + `AdminAuditLogsSection` archive 탭 detail 에서 기존 `ForcedRefundContextPanel` / `PaymentRefundContextPanel` 재사용 + "읽기 전용" Badge 유지. 6 신규 MockK 케이스 (forced detail / partial detail / full detail / non-refund both-null / contextAvailable=false fallback / list endpoint 무 lookup). |
| `5f3bc32` | PR131 | **Audit CSV refund-derived columns**. active export (`exportToCsv`) + archive export (`exportArchivedToCsv`) 양쪽 헤더에 환불 분석 10 파생 컬럼 append-only 추가 — `refundKind` (`FORCED` / `PARTIAL` / `FULL` / 빈 값), `ticketId`, `paymentAttemptId`, `eventId`, `refundAmount` (forced 의 `amount` + user 의 `refundAmount` 통합), `refundedAmount`, `remainingRefundableAmount`, `ticketStatus`, `paymentStatus`, `fullRefund`. 단일 helper `ModerationAuditLogService.csvRefundDerivedColumns(action, afterValue)` 가 active / archive 모두에서 호출 — afterValue JSON 파생값만 사용하고 **lookup 호출 없음** (CSV 는 절대 N+1 부담 안 진다). malformed JSON / null afterValue → export 절대 throw 안 함 + 새 컬럼 빈 값으로 떨어짐. 기존 prefix (active 10 / archive 11) 컬럼은 위치 / 값 / 의미 그대로 — 외부 도구 호환 유지. 6 active + 2 archive 신규 MockK 케이스 (헤더 invariant / forced / partial / full / non-refund all-blank / malformed JSON / archive helper 호출). |
| `(this PR)` | PR132 | **Refund audit enrichment 정책 문서화**. `architecture.md` 에 "Refund Audit Enrichment 정책 (PR115 / PR126 / PR130 / PR131 통합)" 섹션 추가 — active detail / archive detail / list / CSV 6 응답 경로별 enrichment 종류 / DB lookup 유무 / 비용 특성을 한 표로 정리. raw audit row 무변경 + lookup 실패가 detail / export 자체를 깨지 않음 (best-effort) 정책을 한 곳에 명시. `manual-qa-checklist.md` §28 CSV 컬럼 QA 11 항목 + 본 release-notes 갱신. docs only. |

**본 사이클의 결과**: 환불 audit 의 운영 가독성이 세 layer (detail / list / CSV) × 두 store (active / archive) 6 경로 모두 일관된 정책으로 정리됐다. detail 은 깊은 enrichment, list 는 비용 회피 (N+1 없음), CSV 는 JSON 파생 컬럼만 — 같은 정책이 active / archive 모두에 적용된다. 운영자가 환불 처리량 분석, 옛 환불 사고 조사, 외부 도구 export 세 시나리오 모두 한 화면 / 한 파일에서 처리 가능.

---

## 2. PR130 + PR131 운영 가치 — enrichment 6 경로 정리

| 응답 경로 | enrichment | DB lookup | 도입 PR | 비용 특성 |
|---|---|---|---|---|
| `GET /admin/moderation/audit-logs/{id}` | buyer/event/channel + 세 금액 + 상태 | ✅ | PR115 / PR126 | row 1 개 / 호출 |
| `GET /admin/moderation/audit-logs/archive/{originalId}` | 동일 | ✅ | **PR130** | 동일 |
| `GET /admin/moderation/audit-logs?page=...` | 없음 | ❌ | PR62 | row N 개 / page |
| `GET /admin/moderation/audit-logs/archive?page=...` | 없음 | ❌ | PR67 | 동일 |
| `GET /admin/moderation/audit-logs/export` (CSV) | afterValue JSON 파생 10 컬럼 | ❌ | **PR131** | 최대 1000 행, lookup 없음 |
| `GET /admin/moderation/audit-logs/archive/export` (CSV) | 동일 | ❌ | **PR131** | 동일 |

PR130 이전엔 archive tab detail 이 raw JSON 만 — 운영자가 "이 archived row 의 buyer 가 누구냐" 알아내려면 ticketId 만 보고 다른 화면을 따로 켰다. PR130 이후 active 와 동일한 panel 이 그대로 archive 에서도 노출.

PR131 이전엔 환불 분석을 CSV 한 줄로 정렬 / 필터하려면 `afterValue` JSON 을 수동 파싱해야 했다 (Excel 의 텍스트 함수로 추출). PR131 이후 운영자가 `refundKind` / `refundAmount` / `fullRefund` 같은 컬럼을 그대로 정렬 / 필터 / 피벗 가능. 단 buyer 닉네임 / event 제목 등 lookup 결과는 CSV 에 포함하지 않음 — CSV 는 1000 행을 처리하므로 N+1 부담을 절대 안 진다. 그 정보가 필요하면 detail endpoint 호출 (PR115/PR126/PR130).

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
## main...origin/main [ahead 3]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

PR132 (본 release-notes 갱신) 까지 포함하면 3 ahead. 위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 4. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR130 (`0e41e23`) | backend 좁은 `--tests *ModerationAuditLog*` | green (50s) — 신규 6 archive 케이스 (forced detail / partial detail / full detail / non-refund both-null / contextAvailable=false fallback / list endpoint 무 lookup) + 기존 PR115 / PR126 active 8 케이스 회귀 통과 |
| PR130 (`0e41e23`) | backend full `gradle test` | green (46s, BUILD SUCCESSFUL) — `internal fun` 가시성 변경에 controller / 다른 service 회귀 없음 |
| PR130 (`0e41e23`) | frontend `npm run build` | green (101 modules, 981ms — `tsc -b` + Vite 통과). `ArchivedModerationAuditLog` 타입 확장 + 두 panel archive 탭 재사용에 회귀 없음 |
| PR131 (`5f3bc32`) | backend 좁은 `--tests *ModerationAuditLog*` | green (50s) — 신규 6 active + 2 archive CSV 케이스 (헤더 invariant / forced / partial / full / non-refund all-blank / malformed JSON 가드 / archive helper 호출) + 기존 PR63 / PR67 CSV 회귀 (assertEquals 행 케이스가 trailing 10 컬럼 포함하도록 갱신) |
| PR131 (`5f3bc32`) | backend full `gradle test` | green (50s, BUILD SUCCESSFUL) — CSV 헤더 길이 변경에 controller `Content-Disposition` / `X-Export-Limit` 회귀 없음 |
| PR131 (`5f3bc32`) | frontend `npm run build` | **생략** — CSV 는 blob 다운로드라 frontend 변경 없음 |
| PR132 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR130 (`0e41e23`).

**마지막 전체 backend `gradle test` green**: PR131 (`5f3bc32`).

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 cold-start 로 다시 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고).

---

## 5. 운영 / 배포 주의사항

### Flyway 마이그레이션 — 없음

PR130 ~ PR131 모두 **새 V 마이그레이션 없음**. enum / 테이블 추가 없음. 전체 마이그레이션 범위는 V1~V11 그대로 (PR117 V11 이 마지막).

### Detail endpoint response shape — archive 측 2 필드 추가 (PR130)

`GET /admin/moderation/audit-logs/archive/{originalId}` 응답에 `forcedRefundContext` / `paymentRefundContext` 두 optional 필드가 추가된다. active detail (PR115/PR126) 과 동일 정책 / 동일 helper / 동일 shape. 다음 보장:

- **endpoint / path / params / 권한 무변경** — URL, query parameter, ADMIN 권한 가드, response wrapper 모두 그대로.
- **`TICKET_FORCED_REFUNDED` row** → `forcedRefundContext` 만 채워짐, `paymentRefundContext = null`.
- **`PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` row** → `paymentRefundContext` 만 채워짐, `forcedRefundContext = null` — 상호 배타.
- **`listArchived` / `exportArchivedToCsv` 응답은 enrichment 미적용** — N+1 회피 + CSV 호환.
- **이전 frontend 호환** — 두 필드는 optional 로 직렬화, 못 받아도 깨지지 않음.

### CSV export header — 10 컬럼 append (PR131)

active CSV 와 archive CSV 양쪽 헤더에 동일한 10 컬럼이 끝에 추가된다. 다음 보장:

- **prefix 컬럼 위치 / 이름 / 값 무변경** — active 의 첫 10 컬럼 (`id` ~ `afterValue`), archive 의 첫 11 컬럼 (`originalId` ~ `afterValue`) 은 PR63 / PR67 정의 그대로.
- **append-only** — 운영자의 외부 도구가 prefix 컬럼만 보고 있다면 PR131 이후에도 같은 컬럼이 같은 위치에 있다.
- **lookup 호출 없음** — CSV path 는 ticket / buyer / event / channel repository 를 호출하지 않음. 대용량 export (최대 1000 행) 도 N+1 부담 없음.
- **best-effort 파싱** — malformed JSON / null afterValue / 비-object root / 정수 변환 실패 모두 → export 가 throw 하지 않고 새 10 컬럼만 빈 값으로 떨어진다. `refundKind` 는 action 기반이라 JSON 이 깨져도 채워진다.
- **`refundAmount` 컬럼의 통합 의미** — forced refund 의 `amount` 와 user refund 의 `refundAmount` 둘 다 같은 컬럼으로 들어간다 — "이 audit row 가 만든 환불 금액" 의미가 일관.

### Audit recording 정책 — PR122 그대로

PR130 + PR131 모두 audit row 자체는 손대지 않는다. 다음 보장:

- `refundPaymentByTicket` 의 success 분기에서만 audit 기록 (actor=호출자) — PR122 그대로.
- `forceRefundByAdmin` 흐름은 `TICKET_FORCED_REFUNDED` 1건만 (AdminPaymentService 책임), 중복 audit 없음.
- webhook `refund.completed` 흐름은 audit 미기록 (PG-driven, actor 부재).
- audit 실패 시 환불 트랜잭션 rollback (`@Transactional` propagation join).

### Enrichment 실패는 detail / export 자체를 깨지 않음

PR130 의 archive detail enrichment, PR131 의 CSV refund-derived columns 둘 다 **best-effort + throw 금지** 정책. detail endpoint 는 lookup 실패 시 200 + `contextAvailable=false` + fallback 카피, CSV export 는 malformed JSON 시 새 10 컬럼만 빈 값. 두 경우 모두 endpoint / export 자체는 정상 응답.

---

## 6. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **부분 forced refund** | ADMIN `/admin/tickets/{id}/forced-refund` 는 PR117 부터 PARTIALLY_REFUNDED 티켓도 받지만 항상 한 번에 remaining 전체를 환불 (cascade). 부분 금액 강제 환불은 별도 endpoint 또는 옵션 도입 필요. |
| **CSV 의 buyer / event title / channel lookup 컬럼** | PR131 은 afterValue JSON 파생값만 노출 — buyer 닉네임 / event 제목 / 채널 이름은 detail endpoint (PR115/PR126/PR130) 에서만. CSV 한 줄로 닉네임까지 보고 싶다면 N+1 lookup 또는 join-loaded query 가 필요한 별도 PR. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED/PARTIALLY_REFUNDED 카운트 일치 batch 없음. 환불 audit 의 모든 가독성 layer (detail / list / CSV × active / archive) 가 정리된 지금 정합성 batch 의 가치가 가장 커졌다. |
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

직전 사이클의 release notes 에 있던 다음 항목들은 본 사이클에서 채워졌으므로 제거됐다:

- **"Archive audit detail enrichment"** → PR130 으로 구현. `ArchivedModerationAuditLogResponse` 에 두 optional context 추가 + 같은 helper 재사용 + frontend panel 재사용.
- **"CSV export 에 enrichment 컬럼"** → PR131 으로 부분 구현 — afterValue JSON 파생 10 컬럼은 CSV 에 있음. buyer/event title 같은 lookup 결과는 여전히 detail endpoint 의 책임 (위 표 참고).

---

## 7. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (회귀 가드)

- §13 결제 플로우 / §14 환불 플로우 / §15 결제·환불·재신청 정합성 / §16 재신청 — PR130 / PR131 모두 audit 기록 / 환불 실행 / 결제 로직 무변경
- §22 ADMIN 강제 환불 — 회귀

### 운영 콘솔 — 본 묶음의 핵심

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 — 회귀
- §23 / §24 — actor 별 환불 카운트 — 회귀
- §25 — active 사용자 환불 detail enrichment (PR126) — 회귀
- §26 — 사용자 환불 quick filter chip (PR128) — 회귀
- **§27 — Archive audit 상세 enrichment (PR130)** — 본 사이클 신규 11 항목:
  - archive forced refund row → 강제 환불 상세 panel
  - archive 부분 / 전액 환불 row → 환불 상세 panel
  - ticket 삭제 / malformed JSON → fallback 카피
  - non-refund row → panel 비노출
  - 두 context 상호 배타
  - archive list / CSV 응답은 enrichment 미적용
  - raw JSON pretty-print 유지
  - active detail 동작 무변경
- **§28 — CSV export 환불 파생 컬럼 (PR131)** — 본 사이클 신규 11 항목:
  - active / archive CSV 헤더에 동일한 10 컬럼 append-only
  - `refundKind` 가 action 기반 (FORCED / PARTIAL / FULL)
  - forced 의 `amount` 와 user 의 `refundAmount` 가 같은 컬럼으로 통합
  - non-refund row → 새 10 컬럼 빈 값
  - malformed JSON → export 성공 + `refundKind` 만 채워짐
  - CSV path 는 lookup 절대 안 함 (N+1 회피)

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

`./gradlew.bat test` 가 cold-start 일 때 `PermissionIntegrationTest` 등 Spring context 초기화에서 flaky 가 보일 수 있다. 같은 명령 재실행으로 회복되면 본 묶음의 변경과 무관하다고 본다 (PR74 stabilize 시리즈 기록).

본 묶음은 새 migration 이 없다 (V11 까지 그대로). 응답 DTO 의 optional 추가 + CSV 헤더 append-only 라 이전 frontend bundle / 외부 도구 모두 호환된다.

---

## 9. 다음 사이클 (push 이후 추천)

본 묶음으로 환불 audit 가독성의 6 응답 경로가 모두 정리됐다. 다음 사이클의 후보:

1. **PR134 옵션 A — 환불 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 카운트 일치 검증. 큰 backend PR. 환불 audit 의 detail / list / CSV layer 가 모두 정리된 지금 정합성 batch 의 가치가 가장 커졌다.
2. **PR134 옵션 B — 부분 forced refund**: ADMIN 의 `/admin/tickets/{id}/forced-refund` 에 optional `amount` 추가. 운영자가 노쇼 보상의 일부만 돌려주는 케이스. backend + frontend + audit amount 의미 재정의.
3. **PR134 옵션 C — CSV 에 buyer / event title lookup 컬럼**: 외부 도구가 한 CSV 로 닉네임까지 보고 싶을 때. join-loaded query 또는 batch lookup 으로 N+1 회피 필요. 큰 backend PR.
4. **PR134 옵션 D — 환불 실패 큐 / 자동 재시도**: `refund.failed` webhook 의 deadletter + 운영자 retry 도구. backend + frontend + 알림.

옵션 A 는 안정성. 옵션 B 는 ADMIN 운영 도구 확장. 옵션 C 는 외부 도구 호환 확장 (lookup join 필요). 옵션 D 는 환불 실패의 신뢰성.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR130-PR132.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
