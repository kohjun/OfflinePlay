# Local Release Bundle — PR42 to PR102

본 문서는 origin/main 대비 **로컬 main 이 앞서 있는 22 커밋** 을 push 하기 전에 한 번 훑는 ship-readiness 노트다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` |
| Head | `6d6d804 docs(notification): document quick mute flow` |
| Ahead | **22 commits** |
| First ahead | `7a08572 feat(notification): notify refund completion` (PR81) |
| 작성 시점 | 2026-05-18 |

PR 번호 범위는 개념적으로 **PR42~PR102** 사이에 걸쳐 있지만, **실제 push 대상 delta 는 PR81~PR102 (22 커밋)** 다. 그 이전 (PR42~PR80) 의 결제·환불 / 모더레이션 / audit / archive / scheduler 기반은 이미 origin 에 있고, 본 묶음은 그 위에 얹은 **안정화 + 구조 분리 + 알림 설정 사이클** 이다.

---

## 1. 커밋 묶음 요약

### 결제 / 환불 / 재신청 안정화 (PR42 사이클의 마무리)

PR42 (refund endpoint + Ticket REFUNDED) 가 이미 origin 에 있는 상태에서, 본 묶음은 그 위에 사용자 알림 / 라우트 가드 / 라우팅 보정을 얹어 결제·환불·재신청 흐름을 운영 가능 수준으로 닫았다.

| commit | PR | 요약 |
|---|---|---|
| `7a08572` | PR81 | REFUND_COMPLETED 알림 + buyer 단일 발송 |
| `37374e2` | PR82 | `/events/{id}/payment` 라우트 가드 (이미 결제됨 / USED / PENDING / REJECTED / owner / CLOSED / 시작 후 / 무료) |
| `afcb5ec` | PR83 | 결제 알림 라우팅 polish (TicketDetail REFUNDED/CANCELED copy 포함) |

### Admin Console 구조 분리

| commit | PR | 요약 |
|---|---|---|
| `a3d5933` | PR87 | `AdminModerationService` facade + `AdminChannelBanService` / `AdminModerationQueueService` / `AdminModerationStatsService` 분리 + 5 컨트롤러 분할 |
| `e72a9f9` | PR88 | 위 분리에 맞춰 service test 도 분리 |
| `6de210d` | PR93 | 운영자 활동 요약 endpoint (`/admin/moderation/actor-stats`) + overview UI 카드. System actor 구분 표시. |

### EventDetail 구조 분리 + UX 개선

| commit | PR | 요약 |
|---|---|---|
| `6b34d70` | PR84 | EventDetailPage JSX → 5 섹션 컴포넌트 분리 (Hero / Reviews / Comments / OwnerPanel / ActionPanel) + formatters helper |
| `96b3b70` | PR89 | fetch/effect/mutation 을 3 hook 으로 분리 (`useEventDetailData` / `useEventDetailReviews` / `useEventDetailActions`) |
| `aa8f9fb` | PR91 | 잔여 자리 라이브 highlight (1.5 초 pulse + reduced-motion 가드) |

### Frontend 구조 분리

| commit | PR | 요약 |
|---|---|---|
| `d73139c` | PR85 | `index.css` 단일 manifest + `styles/*.css` 12 도메인 분할 |
| `79cdbb7` | PR86 | `types/index.ts` barrel + 9 도메인 분할 |
| `1718f6d` | PR92 | `useCoalescedRefresh` hook + EventDetail / MyPage / TicketDetail / CreatorDashboard 적용 |

### Notification Preferences 사이클

| commit | PR | 요약 |
|---|---|---|
| `4a3789f` | PR95 | V10 migration + `UserNotificationPreference` + GET/PATCH endpoint + NotificationService 발송 필터 (fail-open) |
| `383ec9f` | PR96 | NotificationsPage 알림 설정 패널 (lazy fetch + 즉시 PATCH + rollback) |
| `5f5438d` | PR97 | `notificationMeta.ts` 메타데이터 single source (label/tone/path) |
| `b9f33d5` | PR99 | 카테고리 묶음 토글 (5 bundle + 전체) |
| `44124ff` | PR101 | 알림 카드 quick mute + 5초 undo banner |

### Docs

| commit | PR | 요약 |
|---|---|---|
| `8ba150a` | PR90 | `docs/architecture.md` 신규 — Platform / Backend / Frontend / Payment / Notification / Moderation / Audit 흐름 + Known Exclusions |
| `fa0e2f4` | PR94 | `docs/manual-qa-checklist.md` consolidation — 🖱/📋 라벨 도입 |
| `4af99d7` | PR98 | architecture §6.4~6.7 notification preferences 흐름 |
| `5601bae` | PR100 | architecture §6.6.1 카테고리 묶음 분류표 |
| `6d6d804` | PR102 | architecture §6.6.2 quick mute + undo |

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
git status -sb
## main...origin/main [ahead 22]
 M .claude/settings.local.json
 M build/resources/main/application.yml
?? .claude/scheduled_tasks.lock
```

위 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 3. 검증 기록 (사이클 내 빌드/테스트 결과)

본 묶음은 PR 별로 작게 잘려 있어 매 PR 마다 build/test 실행 결과를 보고했다. 사이클 동안 발생한 주요 검증 이벤트:

| 시점 | 검증 | 결과 |
|---|---|---|
| PR81 (`7a08572`) | backend full `gradle test` | green (refund + REFUND_COMPLETED 알림 회귀 포함) |
| PR82 (`37374e2`) | frontend `npm run build` | green |
| PR84 (`6b34d70`) | frontend `npm run build` | green (섹션 분리 후) |
| PR85 (`d73139c`) | frontend `npm run build` | green (styles 분리 후) |
| PR86 (`79cdbb7`) | frontend `npm run build` | green (types 분리 후) |
| PR87 (`a3d5933`) | backend full `gradle test` | green (admin moderation facade 분리 후) |
| PR88 (`e72a9f9`) | backend service tests 분리 후 | green |
| PR89 (`96b3b70`) | frontend `npm run build` | green (event-detail hooks) |
| PR91 (`aa8f9fb`) | frontend `npm run build` | green |
| PR92 (`1718f6d`) | frontend `npm run build` | green (`useCoalescedRefresh` 도입 후) |
| PR93 (`6de210d`) | backend stats/facade tests 좁은 실행 | green (actor-stats 5 케이스 추가) |
| PR95 (`4a3789f`) | backend full `gradle test` | green (V10 + NotificationService preference filter 회귀 포함) |
| PR96 (`383ec9f`) | frontend `npm run build` | green |
| PR97 (`5f5438d`) | frontend `npm run build` | green |
| PR99 (`b9f33d5`) | frontend `npm run build` | green |
| PR101 (`44124ff`) | frontend `npm run build` | green |
| PR90, PR94, PR98, PR100, PR102 | docs-only | build/test 생략 |

**마지막 전체 backend `gradle test` green**: PR95 (`4a3789f`, V10 migration + NotificationService 필터 회귀 포함). 이후 backend 변경은 PR97~102 사이 없음 (alle frontend/docs).

**마지막 frontend `npm run build` green**: PR101 (`44124ff`, quick mute UI). PR102 는 docs-only.

push 직후 CI 가 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 다시 한 번 끝까지 통과해야 한다. 본 묶음의 PR 별 검증이 부분적이라 (좁은 `--tests` 실행 포함), CI 의 cold-start 전체 실행이 최종 게이트.

---

## 4. 운영 / 배포 주의사항

### Flyway 마이그레이션

본 묶음은 새 V 마이그레이션 **V10** 한 건을 포함한다.

- `V10__add_user_notification_preferences.sql` (PR95) — `user_notification_preferences` 테이블 + `UNIQUE(user_id, notification_type)` + `INDEX(user_id)`.

전체 마이그레이션 범위는 V1~V10. 운영 DB 에 적용 시 V10 까지 빠짐없이 검증.

### Notification 발송 기본값

- `UserNotificationPreference.enabled` DB default = `TRUE`.
- 서비스 정책: **row 없음 → enabled=true 로 fallback** (`NotificationPreferenceService.isEnabled` + DB default 양쪽으로 안전망).
- `isEnabled` 조회 실패 → **fail-open** (true 반환 + warn log). preference 조회 문제로 알림이 사라지지 않게 한다.
- 결과: 운영 배포 직후 기존 사용자도 모든 알림을 그대로 받는다. preferences 는 사용자가 토글했을 때만 row 가 만들어진다.

### Audit 아카이브 스케줄러 (PR68~70 기존, 본 묶음에서 변경 없음)

- 디폴트 **OFF**. V8 의 `audit_log_retention_scheduler_settings` 단일 row 가 `enabled = FALSE`, `cron = '0 30 3 * * *'` 로 seed.
- ADMIN 이 콘솔에서 명시적으로 ON 토글해야 매일 03:30 KST 에 archive tick 이 돈다.
- runner 는 `@Profile("!test")` 라 테스트 컨텍스트에서는 자동 실행되지 않는다.
- 운영 배포 후 ADMIN 의 의도에 따라 enable 여부 결정. **본 push 자체로는 동작이 바뀌지 않는다**.

### 결제 hardening (PR40~42 기존)

`PaymentHardeningCheck` 가 부팅 시 (a) `payment.toss.enabled=true` + secretKey 누락, (b) `webhook-signature-required=true` + secretKey 누락 두 케이스를 fail-fast. 운영 배포 시 env 가 모두 채워져 있어야 한다 — 자세한 운영 점검은 [docs/payment-refund-policy.md §12](payment-refund-policy.md).

### Docker / Flyway / Actuator baseline

- `docs/deploy-checklist.md` 와 `docs/dev-setup.md` 가 환경 셋업 / 헬스 체크 / Flyway baseline 동작을 다룬다. 본 PR 사이클에서 baseline 인프라 변경은 없다.

---

## 5. Known follow-ups (의도된 미구현)

본 묶음은 다음 항목을 **건드리지 않는다**. push 전 운영팀이 인지할 필요가 있다.

| 영역 | 상태 |
|---|---|
| **부분 환불** | 전액 환불만. 부분 cancelAmount 미지원. `payment-refund-policy.md §11.7`. |
| **USED 후 강제 환불 (운영 도구)** | USED 티켓 환불 차단. 노쇼 보상 / 행사 취소 보상은 별도 ADMIN 도구 필요. |
| **환불 정산 reconciliation batch** | 일별 PG 정산 vs REFUNDED 카운트 일치 batch 없음. |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock 만. |
| **정원 race condition lock** | confirm 시점 재검증만. READY 다수 동시 confirm 시 초과 가능. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만. 채널별 (SSE / push / email) 선택 불가. |
| **Preference 변경 audit / 이력** | preference 변경은 audit log 에 기록되지 않으며 별도 이력 테이블도 없음. |
| **COMMENT cascade 자동 hide** | 자동 hide 대상은 REVIEW/COMMENT/POST/EVENT/CHANNEL 5 enum. comment cascade(부모 hide 시 자식 자동 hide) 는 운영자 수동 처리. |
| **실시간 잔여 자리 SSE 채널 / QR 회전 / 푸시** | 잔여 자리는 SSE refetch 기반(PR91 highlight 포함). QR 30초 회전 / push 알림 / 시스템 밝기는 미구현. |

---

## 6. Recommended manual QA before push / deploy

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 push 직전 (또는 staging 에 deploy 한 직후) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 결제·환불·재신청 (본 묶음의 PR81~PR83 영향)

- §13 결제 플로우 (PR74)
- §14 환불 플로우 (PR77)
- §15 결제·환불·재신청 정합성 (PR76 / PR78 / PR79)
- §16 환불 알림 & 결제 라우트 가드 (PR81 / PR82 / PR83) — **본 묶음에서 가장 손이 많이 간 영역**

### 운영 콘솔 (본 묶음의 PR87 / PR88 / PR93 영향)

- §12 Admin 운영 콘솔 (PR72+73 기존 + PR87 facade + PR93 actor stats)
- §19 운영자 활동 요약 (PR93)

### Event Detail / 알림 / 라이브 효과 (본 묶음의 PR84~PR92 / PR95~PR101 영향)

- §17 EventDetail 남은 자리 라이브 강조 (PR91)
- §18 알림 묶음 refetch 코얼레싱 (PR92)
- §20 알림 수신 설정 (PR95 / PR96)
- §20a 알림 묶음 토글 (PR99)
- §20b 알림 카드 Quick Mute + Undo (PR101)
- §21 알림 메타데이터 일관성 (PR97)

🖱 / 📋 라벨 의미는 manual QA 문서 상단 "본 문서 사용법" 참고. 시간이 부족하면 🖱 만 먼저, 📋 는 다음 사이클로.

---

## 7. Push 전 권장 명령

```bash
# 1) 최종 상태 확인
git status -sb
git log --oneline origin/main..HEAD

# 2) 풀 빌드 + 테스트 (CI 가 어쨌든 다시 돌리지만 cold-start 게이트)
./gradlew.bat test
cd frontend && npm run build && cd ..

# 3) 위 모두 green 이면 push
git push origin main
```

`./gradlew.bat test` 가 처음 cold-start 일 때 `PermissionIntegrationTest` 등 Spring context 초기화에서 flaky 케이스가 보인 적이 있다. 재실행으로 회복되는 경우 본 묶음의 변경과 무관하다고 본다 (PR74 stabilize 시리즈 기록 참고).

---

## 8. 다음 사이클 (push 이후 추천)

push 이후 origin/main 이 평탄해진 다음 단계로:

1. **PR103 옵션 B** — `feat(notification): preference history (audit-light first step)`: `UserNotificationPreference.updatedAt` 을 GET 응답에 동봉해 패널에 "마지막 저장: X분 전" 표시. 데이터 모델 변경 없는 작은 PR. §5 의 "Preference 변경 audit / 이력" Known Follow-up 의 1 단계 진행.
2. **partial refund (PR104?)** — `payment-refund-policy.md §11.7` 의 부분 환불 1 단계. `Ticket.refundedAmount` 컬럼 + refund endpoint 의 optional `amount` 인자 + UI 금액 입력. 정원 cascade 정책 결정 필요 (부분 환불 시 currentParticipants 감소 여부).

---

본 문서는 push **이전** 의 self-audit 용. push 후에는 본 문서를 그대로 두고 (or 별도 `release-notes/` 디렉터리로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
