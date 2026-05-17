# Local Release Bundle — PR104 to PR107

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 4 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `6df6a6d docs(payment): document admin forced refund` |
| Ahead | **4 commits** |
| First ahead | `77b666a feat(notification): show preference updated time` (PR104) |
| 작성 시점 | 2026-05-18 |

직전 push (PR81~PR102, 22 커밋 = 결제 안정화 + 구조 분리 + 알림 설정 사이클) 가 이미 origin 에 반영된 상태. 본 묶음은 그 위에 얹은 **알림 설정 마지막 한 칸 (preference updatedAt signal) + 결제 도메인 운영 도구 (ADMIN 강제 전액 환불)** 두 사이클이다.

---

## 1. 커밋 묶음 요약

### 알림 설정 — preference updatedAt signal

PR95~PR102 의 알림 설정 사이클 마무리. 별도 history 테이블 없이 기존 `UserNotificationPreference.updatedAt` 컬럼을 응답에 노출.

| commit | PR | 요약 |
|---|---|---|
| `77b666a` | PR104 | `NotificationPreferenceResponse.updatedAt` 응답 + `UserNotificationPreference.update()` 가 in-place 로 timestamp 갱신 + 패널에 "마지막 저장: {상대시간} / 기본값" 표시 + `formatRelativeTime` helper |
| `7f5492b` | PR105 | architecture §6.4 / §6.6 갱신 — updatedAt 은 lightweight signal (history 아님) 명시 + §10 Known Exclusions 의 "Preference 변경 audit / 이력" 행에 PR104 cross-link |

### 결제 운영 도구 — ADMIN 강제 전액 환불

`payment-refund-policy.md §11.7` 의 "USED 후 강제 환불 (운영 도구)" 미구현 항목을 채운 PR + 문서화.

| commit | PR | 요약 |
|---|---|---|
| `1f17d06` | PR106 | `POST /admin/tickets/{id}/forced-refund` (ADMIN 전용) + `AdminPaymentController` / `AdminPaymentService` / `PaymentService.forceRefundByAdmin` + `ModerationAuditAction.TICKET_FORCED_REFUNDED` enum + `AdminPaymentToolsSection` 새 admin 탭 + PaymentServiceTest 6건 + AdminPaymentServiceTest 2건 |
| `6df6a6d` | PR107 | payment-refund-policy §13 신규 + architecture §3.1 / §3.3 / §4.4 / §5.2.1 / §8.1 / §10 / §11 갱신. 부분 환불은 여전히 미지원임을 §10 / §13.7 에서 명시 |

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

push 직전 `git status -sb` 로 위 파일들이 staged 영역에 들어가 있지 않은지 한 번 더 확인한다. 위 파일들은 작업 트리에 modified/untracked 로 남아 있어도 정상이다.

### 최종 git 상태 (push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead 4]
 M .claude/settings.local.json
 M build/resources/main/application.yml
?? .claude/scheduled_tasks.lock
```

위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 3. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR104 (`77b666a`) | backend 좁은 `--tests *NotificationPreferenceServiceTest*` | green — 4 신규 케이스 (row 없음 updatedAt null / row 있음 updatedAt 반환 / PATCH 후 timestamp 갱신 / request 에 없는 type 의 updatedAt 보존) |
| PR104 (`77b666a`) | backend full `gradle test` | green (1m 45s, 168 base + PR95 4 + PR93 5 + PR104 4 신규 모두 통과) |
| PR104 (`77b666a`) | frontend `npm run build` | green (100 modules, 1.10s) |
| PR105 (`7f5492b`) | docs-only | build/test 생략 |
| PR106 (`1f17d06`) | backend 좁은 `--tests *PaymentServiceTest* *AdminPaymentServiceTest*` | green — 6 + 2 신규 케이스 (USED 강제 환불 / PAID 강제 환불 / REFUNDED 차단 / CANCELED 차단 / attempt 부재 / ADMIN 아님 / PG Failure / audit 매핑 / PaymentService 예외 시 audit 미기록) |
| PR106 (`1f17d06`) | backend full `gradle test` | green (1m 31s, 168 base + PR95 4 + PR93 5 + PR104 4 + PR106 8 신규 모두 통과) |
| PR106 (`1f17d06`) | frontend `npm run build` | green (101 modules, 1.48s) |
| PR107 (`6df6a6d`) | docs-only | build/test 생략 |

**마지막 전체 backend `gradle test` green**: PR106 (`1f17d06`). 이후 backend 변경은 PR107 docs only 라 회귀 위험 없음.

**마지막 frontend `npm run build` green**: PR106 (`1f17d06`). PR107 은 docs only.

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 다시 cold-start 로 통과해야 한다. 빌드 캐시 corruption (`.gradle/kotlin` daemon zip) 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean` 으로 회복 — 본 묶음의 변경과 무관한 Windows 환경 이슈 (PR74 stabilize 시리즈 기록 참고).

---

## 4. 운영 / 배포 주의사항

### Flyway 마이그레이션

**본 묶음은 새 V 마이그레이션 없음.** PR104 는 기존 `user_notification_preferences.updated_at` 컬럼만 활용하고, PR106 도 새 테이블/컬럼 없이 `ModerationAuditAction` enum 값 추가로만 구현됐다. 전체 마이그레이션 범위는 V1~V10 그대로 — 직전 push (PR95 의 V10) 가 마지막.

### Notification preference `updatedAt` (PR104)

- DB row 의 `updated_at` 컬럼이 `@LastModifiedDate` + 엔티티의 `update()` 가 `LocalDateTime.now()` 로 in-place set 함께 갱신. 같은 트랜잭션 내 재조회 시에도 갱신 값 보임.
- **lightweight signal 일 뿐 audit/history 가 아님** — 변경 이력 / actor / 전·후 값을 저장하지 않는다 (§10 Known Exclusions 의 "Preference 변경 audit / 이력" 항목은 여전히 유지).
- 기존 사용자: 처음 응답 시 모든 type 의 `updatedAt = null` ("기본값" 표시). 이후 토글 한 type 만 점진적으로 timestamp 채워짐.

### ADMIN 강제 환불 도구 (PR106)

- **전액 환불만 지원** — `attempt.amount` 그대로. **부분 환불은 미구현** (`Ticket.refundedAmount` 컬럼 없음, payment-refund-policy.md §13.7 참고).
- 일반 사용자 환불 경로 (`POST /tickets/{id}/refund`) 의 deadline / USED 가드는 **무변경** — 일반 사용자는 여전히 시작 전 + PAID 만 환불 가능. ADMIN 만 본 운영 도구를 통해 우회.
- ADMIN 이 일반 경로 (`/tickets/{id}/refund`) 로 시작 전 환불을 처리할 때는 PR42 기존 권한 로직 그대로 (audit 기록 없음). USED / 시작 후 환불을 처리하려면 **반드시** `/admin/tickets/{id}/forced-refund` 사용 — audit 가 기록되도록.
- 운영 사유 (`reason`) 는 audit log 의 `reason` 컬럼 + 응답 `refundReason` 에는 그대로 저장되지만, **buyer 알림 메시지에는 노출되지 않는다** (사용자 친화 카피 유지).
- `ModerationAuditAction.TICKET_FORCED_REFUNDED` 가 audit 테이블에 누적 — 운영 콘솔 §19 의 actor stats 에서는 별도 카운트 필드를 추가해야 표시되지만, 본 묶음은 stats DTO 를 건드리지 않았다 (후속 PR 후보).

### Audit 아카이브 스케줄러 (PR68~70 기존, 본 묶음에서 변경 없음)

- 디폴트 OFF (V8 seed). 본 push 자체로 동작이 바뀌지 않는다.
- 단, PR106 의 `TICKET_FORCED_REFUNDED` audit row 가 같은 `moderation_audit_logs` 테이블에 누적되므로, archive scheduler 가 활성화된 운영 환경에서는 환불 audit 도 retention 정책에 따라 archive 된다 (archive 후에도 `moderation_audit_log_archive` 에서 조회 가능).

### 결제 hardening (PR40~42 기존)

`PaymentHardeningCheck` 가 부팅 시 fail-fast — 변경 없음. ADMIN 강제 환불도 같은 `paymentGateway.refund` 를 통하므로 운영 환경의 Toss secret key 가 누락되면 동일하게 부팅 차단된다.

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
| **운영자 활동 stats 에 forced refund 카운트** | `AdminModerationStatsService.getActorStats` (PR93) 응답에 `TICKET_FORCED_REFUNDED` 별도 카운트 필드 없음. 현재는 audit 로만 추적. |
| **COMMENT cascade 자동 hide** | comment cascade 미구현 — 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반 + highlight (PR91). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

직전 push 의 release notes 에 있던 **"USED 후 강제 환불 (운영 도구)"** 항목은 PR106 으로 채워졌으므로 제거됐다.

---

## 6. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (본 묶음의 PR106 영향)

- §13~§16 — 결제 / 환불 / 재신청 / 결제 알림 라우트 가드 (기존 정책 그대로 동작하는지 회귀 가드)
- **§22 ADMIN 강제 환불 운영 도구 (PR106)** — 본 묶음의 핵심 신규. ADMIN 전용 강제 환불 + audit + buyer 알림 + 일반 환불 경로 가드 회귀 확인 (PR106 사이클의 가장 손이 많이 간 영역)

### 운영 콘솔

- §12 / §19 — 기존 Admin 콘솔 + 운영자 활동 요약 (직전 push 의 PR93). 본 묶음은 운영자 활동 stats DTO 를 건드리지 않았지만, audit 테이블에 새 액션이 누적되므로 audit 탭의 필터·CSV 가 `TICKET_FORCED_REFUNDED` 액션을 정상 표시하는지 spot-check 권장

### 알림 (본 묶음의 PR104 영향)

- §20 알림 수신 설정 (기존 동작)
- §20a 알림 묶음 토글
- §20b 알림 카드 Quick Mute + Undo
- **§20c 알림 설정 "마지막 저장 시각" (PR104)** — 본 묶음의 신규
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

---

## 8. 다음 사이클 (push 이후 추천)

push 이후 origin/main 이 평탄해진 다음 단계로:

1. **PR109 옵션 A — partial refund first step**: `payment-refund-policy.md §13.7 / §11.7` 의 부분 환불 1 단계. `Ticket.refundedAmount` 컬럼 + V11 migration + refund endpoint 의 optional `amount` 인자 + UI 금액 입력. **사전 정책 결정 필요**: 부분 환불 시 `Event.currentParticipants` cascade (감소 / 유지 / 운영자 선택) 및 `TicketStatus.PARTIALLY_REFUNDED` enum 도입 여부.
2. **PR109 옵션 B — forced refund stats in actor activity**: PR93 의 `AdminModerationStatsService.getActorStats` 응답에 `forcedRefundCount` 필드 추가 (현재 `archiveCount` 옆에). 작은 backend+frontend PR — actor stats DTO 갱신 + UI breakdown 한 줄 추가.
3. **PR109 옵션 C — Push/Email channel preference 1 단계**: NotificationType 차원 위에 `channel` 차원을 추가하는 큰 정책 결정. backend 모델 확장 필요. 정책 결정 선행.

옵션 B 가 가장 작고 안전 (audit 데이터가 이미 누적되고 있으므로 표시만 추가). 옵션 A 는 결제 도메인의 마지막 큰 미구현. 옵션 C 는 정책 결정 선행 필요.

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (or 별도 `release-notes/` 디렉터리로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
