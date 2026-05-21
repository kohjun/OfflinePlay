# Local Release Bundle — PR139~PR142 (retrospective) + PR143 (this doc)

본 문서는 두 역할을 동시에 한다:

1. **Push 전 self-audit** — 본 PR143 (release-notes refresh) 만 origin 에 push 전이라면 5 commit ahead 인 상태의 ship-readiness 노트.
2. **PR139~PR142 사이클 retrospective** — 직전 4 commit 묶음 (Web Push 인프라 + 발송 + 이벤트 공지 + 채널 새 이벤트 push 커버리지) 의 정리. 이 4 commit 은 본 문서 작성 시점에는 아직 로컬에만 존재 — 같은 push 에 PR143 docs 와 함께 origin/main 으로 올라간다.

| 항목 | 값 |
|---|---|
| Base | `origin/main` (PR137 + 두 운영 PR `843c3b9` review-gate / `37c4737` event-room 까지 반영된 시점) |
| Head | `<PR143 docs commit>` (본 문서 commit) |
| Ahead | **본 cycle 5 commits** (PR139~PR142 + PR143). 그 외 origin 미반영 commit (PR142 prior 3) 도 함께 push 될 수 있음 — `git log origin/main..HEAD` 로 정확한 ahead 확인. |
| 직전 cycle | `0e41e23 (PR130) … f7c09cb (PR137)` (이미 origin 에 push 완료) |
| 본 cycle | `d5ee69f (PR139) … 994b17d (PR142)` (4 commits, 로컬 only) + 본 PR143 docs commit |
| 작성 시점 | 2026-05-21 |

이번 사이클은 **알림 채널의 세 번째 다리** 인 Web Push 를 in-app 알림 + SSE 와 같은 NotificationService 경로 위에 올리고, 그 위에 두 개의 새 fan-out 흐름 (이벤트 공지 + 채널 새 이벤트) 을 정리했다. 모든 변경이 기존 `NotificationService.notify` 단일 진입점 위에서 이루어졌으므로 preference 게이트 (PR95) 와 NotificationType 별 라우팅 (PR97) 은 그대로 활용된다.

본 PR143 은 PR139~PR142 4 commit 의 docs 정리만 — production / backend / frontend / migration 변경 없음.

---

## 1. 커밋 묶음 요약 (PR139~PR142, 로컬 only — 본 push 에 함께 올라감)

### Web Push + Event announcements + new-event push coverage

| commit | PR | 요약 |
|---|---|---|
| `d5ee69f` | **PR139** | **Web Push 구독 인프라**. V12 `user_push_subscriptions` (endpoint TEXT + SHA-256 hex 64자 UNIQUE) + `UserPushSubscription` entity / repository / service / controller (`POST/DELETE/GET /api/v1/push/subscriptions`) + `push.vapid.*` placeholder yml + `InvalidPushSubscriptionException` (400). frontend `public/sw.js` (install/activate/push/notificationclick) + `api/push.ts` (register/unregister/list + permission/support detection) + `BrowserPushPanel` UI in NotificationsPage + `VITE_PUSH_VAPID_PUBLIC_KEY` env. App.tsx 가 SW 등록 + `notificationclick` 메시지를 router 로 bridge. PushSubscriptionService 9 단위 테스트. **발송 자체는 PR140 에서.** |
| `c5e09f6` | **PR140** | **Web Push 발송**. `WebPushSender` 인터페이스 + `LibraryWebPushSender` (`nl.martijndwars:web-push:5.1.1` + `org.bouncycastle:bcprov-jdk18on:1.78.1`, BC provider 클래스 로드 시 1회 등록). `PushNotificationService.dispatch(notifications)` 가 receiver 별 active 구독 묶음 조회 → payload JSON (`title/body/type/targetType/targetId/url/notificationId`) → 각 endpoint 발송 → 결과 분기 (2xx → `touchSeen`, 410/404 → `disable()`, 그 외 → warn log). `disable()` / `touchSeen()` 은 `REQUIRES_NEW` 트랜잭션. NotificationService 가 row 저장 + SSE 이후 호출하며 실패를 try-catch 로 swallow — push 가 notification 트랜잭션을 깨지 않는다. VAPID 키 미설정이면 `WebPushSendResult.disabled()` 반환 → dispatch 자체가 no-op (로컬/CI 안전). `PushNotificationProperties` 가 application 에서 `@EnableConfigurationProperties` 로 활성화. PushNotificationServiceTest 7 신규 + NotificationServiceTest 3 신규 (preference 통합 + dispatch 예외 격리). |
| `2632aab` | **PR141** | **Event announcement notifications**. V13 `event_announcements` (event_id FK / author_id FK / title VARCHAR(200) / content TEXT, idx event+created) + `EventAnnouncement` entity / repository / DTOs + `EventAnnouncementService` (create / list + 권한 가드) + `EventAnnouncementController` (`POST/GET /api/v1/events/{eventId}/announcements`). `NotificationType.EVENT_ANNOUNCEMENT` 추가. 작성 권한 — owner / 채널 STAFF / ADMIN. 조회 권한 — 작성자 + APPROVED 참가자. 수신자 — APPROVED participation × (무료 또는 ticket NOT IN CANCELED/REFUNDED). frontend `EventAnnouncementsSection` 이 EventDetailPage 안에서 canWrite/canRead 분기로 form + list 렌더. notificationMeta `content` 묶음에 EVENT_ANNOUNCEMENT 편입. EventAnnouncementServiceTest 6 신규 (owner success / non-owner 403 / STAFF 허용 / CANCELED·REJECTED 제외 / 유료 ticket 상태 필터 / list 권한). |
| `994b17d` | **PR142** | **Channel new event push 커버리지**. `EventService.createEvent` 의 NEW_EVENT 수신자 묶음에서 channel.owner.id 제외 (defensive — owner 가 자기 채널을 구독한 비정상 상태에서도 자기 알림은 안 받게) + dedupe. notify 실패 시 warn log 추가 (다른 경로와 parity). EventServiceTest 3 신규 케이스 — 구독자 수신 / owner 본인 제외 / 빈 구독자도 notify 호출. push dispatch 자체는 PR140 NotificationService 통합 테스트로 보장 — 본 PR 은 receiver 정책만 정리. |

### PR143 (본 문서)

| commit | PR | 요약 |
|---|---|---|
| `<TBD>` | **PR143** | **Release notes / architecture / manual QA 갱신**. PR139~PR142 4 commit 사이클 정리 + architecture.md §6.8 (Web Push) / §6.9 (Event announcements) / Known Exclusions 갱신 + manual-qa §30~§33 4 신규 섹션. docs only. |

---

## 2. 사이클의 운영 가치

### (a) 알림 채널의 세 번째 다리 (PR139 + PR140)

NotificationService 의 알림 흐름이 이제 **세 채널 동시 fan-out**:

| 채널 | 시점 | 실패 정책 |
|---|---|---|
| In-app row (`notifications` 테이블) | preference 통과 → saveAll | 트랜잭션과 함께 rollback |
| SSE (`SseEmitterService.sendToUser`) | row 저장 직후 | best-effort, 실패해도 row 유지 |
| **Web Push (PR140 신규)** | SSE 직후 | best-effort, 실패해도 row/SSE 유지. 410/404 → subscription self-disable |

세 채널 모두 같은 NotificationType preference 게이트 (PR95) 위에서 동작. 사용자가 PARTICIPATION_REQUESTED 를 끄면 세 채널 모두 발송되지 않는다. 채널별 분리 (예: SSE 만 받고 push 만 끄기) 는 Known follow-ups 에 남아 있다.

### (b) 이벤트 운영자 → active 참가자 직접 채널 (PR141)

기존 흐름은 owner 가 active 참가자에게 알림을 보낼 도구가 없었다 — `채널 owner → 채널 구독자` 의 NEW_POST / NEW_EVENT 뿐. PR141 은 그 사이의 빈 영역인 `이벤트 owner → 이벤트의 APPROVED 참가자` 직접 채널을 열었다.

| 흐름 | 수신자 | 사용 사례 |
|---|---|---|
| 채널 공지 (NEW_POST) | 채널 구독자 전원 | 채널 단위 마케팅 / 새 콘텐츠 안내 |
| 새 이벤트 알림 (NEW_EVENT) | 채널 구독자 전원 | 새 이벤트 발행 시 자동 |
| **이벤트 공지 (EVENT_ANNOUNCEMENT, PR141)** | 이벤트의 active 참가자 | 공연 시간 변경 / 장소 안내 / 우천 대비 등 운영 직접 채널 |

active 참가자 정의: APPROVED 참가 × 티켓이 (없음 OR `PAID/USED/PARTIALLY_REFUNDED`). CANCELED/REFUNDED 티켓 보유자는 자동 제외 — 이미 참가하지 않을 사람에게는 운영 공지가 노이즈.

### (c) 채널 owner self-notification 방어 (PR142)

PR47 부터 `EventService.createEvent` 는 채널 구독자 전원에게 NEW_EVENT 를 발송했지만, owner 본인이 자기 채널을 구독한 비정상 상태에서는 자기 이벤트 알림을 자기가 받는 결과가 나왔다. PR142 는 defensive 제외 — `receiverIds.filter { it != channel.owner.id }` — 로 막는다. preference 필터링은 그대로 NotificationService 가 담당.

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

push 직전 `git status -sb` 로 위 파일들이 staged 영역에 들어가 있지 않은지 한 번 더 확인.

### 최종 git 상태 (PR143 commit 후 push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead N]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

본 cycle 가 더하는 5 commits (PR139~PR142 + PR143) 위에 prior cycle 의 미반영 commit (PR136~PR138 등) 이 함께 ahead 로 보일 수 있다. `git log --oneline origin/main..HEAD` 로 정확한 목록을 확인하고, 위에 나열된 staged 파일 외에 staged 변화가 있으면 push 보류하고 원인 확인.

---

## 4. 검증 기록 (PR139~PR142 사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR139 (`d5ee69f`) | backend `--tests *PushSubscriptionServiceTest*` | green — upsert / duplicate / delete / invalid payload / not found 9 케이스 |
| PR139 (`d5ee69f`) | backend full `gradle test` | green (~31s) |
| PR139 (`d5ee69f`) | frontend `npm run build` | green (~770ms) — sw.js 가 dist 로 복사됨 |
| PR140 (`c5e09f6`) | backend `--tests com.contenido.domain.notification.*` | green — 새 PushNotificationServiceTest 7 + NotificationServiceTest 신규 3 |
| PR140 (`c5e09f6`) | backend full `gradle test` | green (~34s) |
| PR140 (`c5e09f6`) | frontend `npm run build` | 본 PR 은 service worker / API helper 변경 없음 (PR139 build 재사용) |
| PR141 (`2632aab`) | backend `--tests *EventAnnouncementServiceTest*` | green — owner / non-owner / STAFF / 상태 필터 / list 권한 6 케이스 |
| PR141 (`2632aab`) | backend full `gradle test` | green (~34s) |
| PR141 (`2632aab`) | frontend `npm run build` | green (~730ms) — `EventAnnouncementsSection` + `eventAnnouncements.ts` 추가 |
| PR142 (`994b17d`) | backend `--tests *EventServiceTest*` | green — NEW_EVENT 구독자 / owner 제외 / 빈 구독자 3 신규 |
| PR142 (`994b17d`) | backend full `gradle test` | green (~34s) |
| PR142 (`994b17d`) | frontend `npm run build` | green (~777ms) |
| PR143 (본 문서) | docs-only | build/test 생략 |

**마지막 frontend `npm run build` green**: PR142 (`994b17d`).

**마지막 전체 backend `gradle test` green**: PR142 (`994b17d`).

push 직후 CI 가 cold-start 로 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 다시 통과해야 한다. 빌드 캐시 corruption (Windows 환경의 kotlin daemon `caches-jvm/inputs/source-to-output.tab.keystream.len`) 으로 첫 시도가 실패할 수 있다 — 사이클 중 한 번 발생했고 `./gradlew.bat --stop && ./gradlew.bat clean test` 로 회복. PR74 stabilize + PR134 caches-jvm 경험과 동일한 Windows-specific 이슈.

---

## 5. 운영 / 배포 주의사항 (사이클 누적)

### Flyway 마이그레이션 — V12 + V13 두 건 추가

| 버전 | 파일 | 내용 |
|---|---|---|
| V12 | `V12__add_user_push_subscriptions.sql` | PR139 — `user_push_subscriptions` 테이블 (endpoint TEXT + endpoint_hash CHAR(64) UNIQUE per user). |
| V13 | `V13__add_event_announcements.sql` | PR141 — `event_announcements` 테이블 (event_id FK / author_id FK / title VARCHAR(200) / content TEXT). |

배포 시 Flyway 가 V11 → V12 → V13 순으로 적용. 마이그레이션 자체는 새 테이블 + FK 만 — 기존 데이터에 영향 없음.

### 새 환경 변수 (운영 필수)

| 변수 | 설명 |
|---|---|
| `PUSH_VAPID_PUBLIC_KEY` | backend 가 공개 키로 보유. 비어 있으면 push 발송 자체 no-op. |
| `PUSH_VAPID_PRIVATE_KEY` | backend 만 알아야 하는 비밀 키. **절대 commit 금지** — 운영 env var 전용. |
| `PUSH_VAPID_SUBJECT` | VAPID `sub` claim (`mailto:` 또는 `https://...`). default `mailto:no-reply@contenido.local`. |
| `VITE_PUSH_VAPID_PUBLIC_KEY` | frontend 빌드 시 인라이닝되는 공개 키. backend 의 PUSH_VAPID_PUBLIC_KEY 와 동일 값. 비어 있으면 frontend 가 "푸시 키 미배포" 폴백. |

키 페어 생성 — staging / production 각각 별도 keypair 권장. 운영 셋업 시:
```bash
# nl.martijndwars:web-push CLI 또는 web-push npm 패키지로 생성
npx web-push generate-vapid-keys
# 출력의 Public/Private 를 위 env 에 그대로 주입
```

### 새 API 엔드포인트

| 엔드포인트 | 권한 | 책임 |
|---|---|---|
| `POST /api/v1/push/subscriptions` | 인증 사용자 | 새 구독 등록 / 같은 endpoint credential 갱신 (PR139) |
| `DELETE /api/v1/push/subscriptions` | 인증 사용자 | body `{endpoint}` — 본 endpoint hash 매칭 row hard delete (PR139) |
| `GET /api/v1/push/subscriptions/me` | 인증 사용자 | 내 구독 디바이스 목록 (PR139) |
| `POST /api/v1/events/{eventId}/announcements` | owner / STAFF / ADMIN | 새 공지 발송 — active 참가자에게 EVENT_ANNOUNCEMENT (PR141) |
| `GET /api/v1/events/{eventId}/announcements` | 작성자 + APPROVED 참가자 | 공지 목록 created_at desc (PR141) |

### NotificationType enum 확장

`EVENT_ANNOUNCEMENT` 추가 (PR141). frontend `notificationMeta.ts` 의 `META` / `NOTIFICATION_PREFERENCE_BUNDLES` 에도 추가됨 — `content` 묶음의 일부.

기존 frontend bundle 이 새 type 을 모르는 상태에서 EVENT_ANNOUNCEMENT 알림을 받으면 `getNotificationMeta` 의 fallback (`{label: '알림', tone: 'neutral'}`) 으로 안전하게 표시 — 회귀 없음. 새 frontend bundle 이 배포되면 정확한 라벨/색이 노출된다.

### NotificationService 변경 — push dispatch 호출 추가

`NotificationService.notify` 가 row 저장 + SSE 발송 이후 `pushNotificationService.dispatch(notifications)` 를 호출. 호출은 try-catch 로 감싸져 push 실패가 row / SSE 트랜잭션을 깨지 않는다. 외부 호출자는 정확히 동일한 시그니처 — caller side 변경 없음.

### 새 dependency

| 좌표 | 버전 | 용도 |
|---|---|---|
| `nl.martijndwars:web-push` | 5.1.1 | VAPID JWT 서명 + RFC8291 payload encryption (PR140) |
| `org.bouncycastle:bcprov-jdk18on` | 1.78.1 | EC key 처리 — nl.martijndwars 가 transitive 로 가져오지 않으므로 명시 추가 (PR140) |

두 dep 합계 약 5-6 MB 추가. Spring Boot fat jar 의 크기가 그만큼 증가.

### Service worker (`/sw.js`) 가 정적 빌드에 포함

`frontend/public/sw.js` 가 vite 의 `public/` 정책에 의해 `dist/sw.js` 로 그대로 복사됨. 배포 시 정적 호스팅 (nginx / S3+CloudFront 등) 이 `/sw.js` 를 그대로 서빙해야 SW 등록이 성공. Vite dev 서버는 자동 처리.

---

## 6. Known follow-ups (의도된 미구현)

본 사이클은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **Native FCM adapter** | Web Push 만 지원 (iOS Safari 18.5+, Android 모든 브라우저 커버). native iOS/Android 앱용 APNs/FCM 어댑터는 향후 PR. |
| **Push delivery retry queue** | 4xx/5xx 응답은 warn log 만. expired (410/404) 만 subscription disable. 일시 실패의 자동 재시도 / dead letter queue 없음. |
| **Push analytics / open tracking** | dispatch 결과 (sent / expired / failed) 의 dashboard / open-rate / CTR 추적 없음. backend 로그만. |
| **Push quiet hours** | 시간대별 발송 정지 (e.g. 23시~07시 mute) 미구현. preference disable 은 24/7. |
| **Push / Email channel preference** | preference 는 NotificationType 차원만 — 같은 type 을 SSE 만 받고 push 는 끄는 등 채널별 분리 불가 (현재 채널은 SSE / in-app / push 가 같은 게이트). |
| **이벤트 공지 수정 / 삭제** | PR141 은 create / list 만. 잘못 보낸 공지의 edit / delete 는 후속 PR. |
| **이벤트 공지 의 staff actor breakdown** | 누가 보낸 공지인지 list 응답에 author nickname 만. moderation_audit_logs 미기록 (운영 audit 영역 밖). |
| **환불 정산 reconciliation batch** | PG 정산 vs REFUNDED 카운트 일치 batch 없음 (직전 사이클부터 보류). |
| **환불 실패 큐 / 자동 재시도** | `refund.failed` webhook 처리는 단순 skip. |
| **PortOne / 다른 PG 어댑터** | interface 만 열려 있고 구현체는 Toss + Mock. |
| **정원 race condition lock** | confirm 시점 재검증만. |
| **부분 환불 동시 race** | 별도 lock 없음. 후순위 호출이 400 으로 거부. |
| **Kafka outbox** | 도입 설계만 (`kafka-outbox-plan.md`). 알림은 직접 SSE push. |
| **Preference 변경 audit / 이력** | PR104 의 `updatedAt` 은 lightweight signal. 변경 이력 / actor / 전·후 값 미저장. |
| **COMMENT cascade 자동 hide** | 운영자 수동 처리 그대로. |
| **실시간 잔여 자리 SSE 채널 / QR 회전** | 잔여 자리는 SSE refetch + highlight (PR91). QR 30초 회전은 미구현. |

직전 release notes 에 있던 다음 항목들은 본 사이클에서 부분 채워졌다:

- **"Push / Email channel preference"** → 항목 자체는 유지. PR140 부터 push 가 같은 NotificationType preference 게이트로 켜고 꺼지지만 **채널별 분리** 는 여전히 미구현.
- **"실시간 잔여 자리 / QR 회전 / 푸시"** → "푸시" 부분은 PR139 + PR140 으로 해소. 남은 두 항목은 별도 유지.

---

## 7. Recommended manual QA (post-push verification)

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 staging deploy 직후 (또는 다음 사이클 작업 시작 전) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 본 사이클의 핵심

- **§30 — Web Push 구독 (PR139)** — 권한 / SW 등록 / backend POST·DELETE / 미지원 환경 fallback 12 항목
- **§31 — Web Push 발송 (PR140)** — 알림 트리거 후 push 도착 / 클릭 라우팅 / 410 자동 disable / preference off 시 미수신 / 발송 실패가 트랜잭션 깨지 않음 10 항목
- **§32 — Event 공지 (PR141)** — owner 작성 / active 참가자만 수신 / CANCELED·REJECTED·REFUNDED 제외 / 권한 가드 13 항목
- **§33 — Channel new event push 커버리지 (PR142)** — 구독자 수신 / owner 본인 제외 / preference off / push 클릭 라우팅 7 항목

### 회귀 (본 사이클 무변경)

- §20 / §21 — 알림 preference / notificationMeta 일관성
- §22~§29 — 결제 / 환불 / forced refund / audit (직전 사이클 결과 회귀)

🖱 / 📋 라벨 의미는 manual QA 문서 상단 "본 문서 사용법" 참고.

---

## 8. Push 전 권장 명령 (PR143 commit 후)

```bash
# 1) 최종 상태 확인 — PR139~PR142 + PR143 = 5 ahead
git -C C:/WOYA status -sb
git -C C:/WOYA log --oneline origin/main..HEAD

# 2) 풀 빌드 + 테스트
cd C:/WOYA && ./gradlew.bat test
cd C:/WOYA/frontend && npm run build

# 3) 위 모두 green 이면 push (사용자가 직접 실행)
# git -C C:/WOYA push origin main
```

`./gradlew.bat test` 가 cold-start 일 때 kotlin daemon 의 caches-jvm lock 또는 `build/snapshot/kotlin/compileTestKotlin` 잠금으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean test` 으로 회복 — Windows 환경 이슈 (PR74 stabilize + PR134 caches-jvm 경험). 사이클 중 한 번 발생 + 회복.

본 PR143 은 docs only — DTO / migration / endpoint 변경 없음. 단, 같은 push 에 올라가는 PR139~PR142 는 새 마이그레이션 (V12 / V13) + 새 dependency (nl.martijndwars + bcprov) + 새 env 변수 (PUSH_VAPID_*, VITE_PUSH_VAPID_PUBLIC_KEY) 를 포함하므로 deploy 시 env 셋업 필요.

---

## 9. 다음 사이클 (post-PR143 추천)

PR139~PR142 4 commit 으로 Web Push 다리가 닫혔다. 다음 사이클 후보:

1. **PR144 옵션 A — 환불 정산 reconciliation batch**: PG 측 일별 cancel 데이터를 받아 카운트 일치 검증. 큰 backend PR — 부분 환불 + 부분 강제 환불 + audit detail + CSV + 다음 사이클 까지 도입한 지금 정합성 batch 의 가치가 가장 커졌다 (직전 사이클부터 미해결).
2. **PR144 옵션 B — Push retry queue + delivery analytics**: PR140 의 best-effort 실패를 dead letter queue 로 모아 자동 retry / 운영자 dashboard. push 도입 후 자연스러운 후속 — failed push 의 root cause 파악에 필수.
3. **PR144 옵션 C — Push quiet hours / 채널별 preference**: NotificationType 차원이 아닌 시간대 / 채널(SSE vs push) 별 분리. 사용자 friction 감소.
4. **PR144 옵션 D — Event announcement edit / delete**: PR141 의 create 만 다룬 한계 보완. 잘못 보낸 공지의 수정 / 삭제 + 알림 처리.
5. **PR144 옵션 E — Native FCM adapter**: iOS/Android 네이티브 앱 출시 시 필수. interface 가 `WebPushSender` 로 열려 있어 구현체만 추가하면 다중 채널 가능.

옵션 A 는 안정성 (직전 사이클부터 미해결). 옵션 B 는 push 운영의 신뢰성. 옵션 C 는 사용자 control. 옵션 D 는 운영자 control. 옵션 E 는 플랫폼 확장.

---

본 문서는 PR139~PR142 retrospective + PR143 self-audit. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR139-PR142.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
