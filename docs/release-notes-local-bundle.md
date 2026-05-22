# Local Release Bundle — PR144~PR157 (retrospective) + PR158 (this doc)

본 문서는 두 역할을 동시에 한다:

1. **Push 전 self-audit** — PR158 (docs cycle) 까지 commit 한 상태에서 ahead N commits 의 ship-readiness 노트.
2. **PR144~PR157 사이클 retrospective** — 직전 14 commit 묶음 (5 phase: 프로필/신뢰 → 탐색/추천 → 모임방 → 크리에이터 도구 → PWA) 정리.

| 항목 | 값 |
|---|---|
| Base | `origin/main` (PR143 까지 push 완료) |
| Head | `<PR158 docs commit>` (본 문서 commit) |
| Ahead (본 cycle) | **15 commits** (PR144~PR158) |
| 직전 cycle | `d5ee69f (PR139) … 896be4f (PR143)` (이미 push 완료) |
| 작성 시점 | 2026-05-22 |

이번 사이클은 운영 가능한 베타에서 **소비자 플랫폼 경험** 으로 전진하는 묶음이다. 다섯 phase 모두 기존 NotificationService / EventService / Channel/Event entity / S3Service / PushNotificationService 위에 새 기능을 얹었으며, V14~V18 마이그레이션 5건은 신규 테이블 / 컬럼만 추가하고 기존 결제·환불·신고·모더레이션 정책은 그대로 둔다.

본 PR158 은 PR144~PR157 14 commit 의 docs 정리만 — production / backend / frontend / migration 변경 없음 (단, BrowserPushPanel 의 denied 안내 카피 추가만 frontend 변경).

---

## 1. 커밋 묶음 요약 (PR144~PR157, 로컬 only — 본 push 에 함께 올라감)

### Phase 1 — 프로필 / 신뢰 (PR144~PR146)

| commit | PR | 요약 |
|---|---|---|
| `1ec3569` | PR144 | **Public profile foundation**. V14 `user_profiles` (1:1 lazy create). bio / avatar / region / visibility. PRIVATE 가시성 필터. 새 `ProfileViewPage` + 본인 편집 form. `/users/{userId}` 라우트. |
| `7f9ae11` | PR145 | **Trust snapshot**. `ReviewRepository.averageRatingByHostUserId` + count derived query 3건. `TrustSummaryService.compute` 5-query 합산. `GET /users/{id}/trust-summary`. `TrustChips` (compact/full). ProfileViewPage Promise.all 병렬 fetch. |
| `89f86ae` | PR146 | **Manner feedback MVP**. V15 `user_manner_feedbacks` (UNIQUE pair). TEXT JSON tags + `MannerTagsConverter`. host↔participant pair 가드 + endAt 가드 + 3건 미만 summary=null. `POST /events/{eid}/manner-feedbacks` + `/users/{id}/manner-summary`. `MannerFeedbackForm` + `EventMannerSection`. |

### Phase 2 — 탐색 / 추천 (PR147~PR149)

| commit | PR | 요약 |
|---|---|---|
| `54dd140` | PR147 | **Interest & region taxonomy**. V16 `interests` (32 seed) + `regions` (17 시도 + 250+ 시군구, 법정동 코드) + `user_interests`/`event_interests` composite-PK join + `events.region_code`/`user_profiles.region_code` FK ALTER. `InterestPicker` (chip multi-select max 10) + `RegionPicker` (cascade) — 모듈 캐시. catalog endpoint permitAll. |
| `eef2091` | PR148 | **Personalized explore feed**. `EventRepository.findRecommendationCandidates` + `RecommendationService` 가중치 score (interest×3 + region×2 + subscribed×2 + recency×1.5 + rating×1) + POPULAR/CLOSING_SOON/LATEST 3 fallback. `GET /recommendations/events?segment=`. `EventCard` reason chip + `RecommendationStrip` (인증 4 / 비로그인 3 탭). |
| `1bd537e` | PR149 | **Discovery quality polish**. reason chip 우선순위 desc 재정렬 + 0건 검색 empty state copy. |

### Phase 3 — 모임방 / 커뮤니티 (PR150~PR152)

| commit | PR | 요약 |
|---|---|---|
| `ff0fff5` | PR150 | **Event room hub (frontend-only)**. `EventRoomSection` 가 공지/대화/참가자 3 tabbed view — BFF endpoint 없이 기존 컴포넌트 재사용. |
| `2c0b7fc` | PR151 | **Pinned + read receipts**. V17 `event_announcements.pinned_at` ALTER + `event_announcement_reads`. `PATCH /pin` (기존 pinned 자동 해제) / `POST /read` (멱등 upsert) / `GET /unread-count`. EventRoomSection 공지 탭 unread badge. |
| `aa33cb3` | PR152 | **Inline media**. V18 `event_announcement_images` + `comments.images TEXT`. `CommentImagesConverter` (MannerTagsConverter 패턴). 최대 3장 + S3 upload. 펼침 시 grid. |

### Phase 4 — 크리에이터 운영 도구 (PR153~PR155)

| commit | PR | 요약 |
|---|---|---|
| `711e7ed` | PR153 | **Revenue & refund analytics**. `PaymentAttemptRepository.aggregateChannelAnalytics` 단일 GROUP BY. owner/STAFF/ADMIN 가드. `GET /creator/channels/{cid}/analytics?from=&to=`. `CreatorAnalyticsCard` (4 metric tile + 30일/90일/전체 + breakdown 테이블). |
| `00b34b4` | PR154 | **Participant CSV export**. `ModerationAuditAction.PARTICIPANT_EXPORTED` 추가. phoneMasked + N+1 회피 + audit row. UTF-8 BOM Excel 호환. EventOwnerPanel "CSV 내보내기" 버튼. |
| `2ff96bd` | PR155 | **Event clone & repeat**. owner/ADMIN 가드. metadata 복사 (interests 포함) + currentParticipants 0 reset. NEW_EVENT 알림 (PR142 정책). EventOwnerPanel inline form. |

### Phase 5 — PWA / 모바일 (PR156~PR158)

| commit | PR | 요약 |
|---|---|---|
| `db8c1b5` | PR156 | **PWA manifest & install prompt**. `manifest.webmanifest` (standalone, theme #FA5252, 192/512 SVG). `InstallPrompt` 컴포넌트 — `beforeinstallprompt` 캐치 + 14일 dismissal. |
| `4100965` | PR157 | **SW cache shell**. `offline.html` + `sw.js` 의 install precache + activate 옛 cache 정리 + fetch handler (navigate network-first → cached → offline.html chain; API 절대 캐시 안 함). `SHELL_VERSION` bump 정책. |
| `<TBD>` | PR158 | **Push onboarding QA + docs**. BrowserPushPanel denied 안내에 Chrome/Edge / Android Chrome / iOS Safari 18.5+ 브라우저별 ul. architecture.md §6.10·§6.11·§6.12 신설 + manual-qa §34~§48 (15 신규 섹션) + 본 release-notes 재작성. |

---

## 2. 사이클의 운영 가치

### (a) 사용자 신뢰 표면 (Phase 1)

PR144~PR146 은 "이 사람이 누구인가" 를 신청 전·후로 확인할 수 있는 layer 를 만들었다.

| 영역 | 노출 데이터 | 출처 |
|---|---|---|
| Profile (PR144) | bio / avatar / 활동 지역 / 공개 범위 | user_profiles 1:1 row |
| Trust (PR145) | 기획 N회 / 참가 N회 / 체크인 N회 / 후기 N건 / host 평균 별점 | 기존 event / participation / ticket / review 집계 |
| Manner (PR146) | host↔participant 양방향 평점 + topTag (3건 누적 후 공개) | user_manner_feedbacks |

세 데이터 모두 별도 endpoint 로 분리해 PRIVATE 가시성 / 3건 미만 hide 등 정책 분기를 명확하게 유지.

### (b) 탐색 알고리즘 자리잡기 (Phase 2)

기존 ExploreService 는 키워드 + category 필터만 다뤘다. PR148 부터 사용자별 RecommendationService 가 추가되며, score 가중치를 코드로 박아 확장의 출발점이 됐다:

```
score = interestMatch * 3 + regionMatch * 2 + subscribedChannelBoost * 2
      + recency(7d) * 1.5 + ratingBoost * 1
```

매칭 0건이면 POPULAR fallback 으로 자연스럽게 polish. ES function_score 로의 이관은 P95 임계 초과 시 — 본 cycle 은 JPA + in-memory 정렬로 출발 (architecture §6 + plan 의 의사결정).

### (c) 이벤트룸 hub + 운영자 광고판 (Phase 3)

EventDetailPage 에 흩어져 있던 announcement / comment / participants 영역이 **이벤트룸** 라는 단일 tab 묶음으로 정리됐다 (PR150 — BFF 없이). PR151 에서 pinned + read receipt 가 더해져 운영자가 우선 공지를 상단 고정할 수 있게 됐고, PR152 에서는 최대 3장 inline 이미지 첨부가 가능해졌다.

### (d) 크리에이터 운영 도구 (Phase 4)

| 영역 | 새 endpoint | 운영 가치 |
|---|---|---|
| Revenue (PR153) | `GET /creator/channels/{cid}/analytics` | 채널 단위 매출/환불 + 이벤트별 breakdown — 어떤 이벤트가 돈을 벌고 환불을 만들었는지 한 화면 |
| Participant export (PR154) | `GET /creator/events/{eid}/participants/export` | CSV 내보내기 + phoneMasked + audit row 추적 — 개인정보 export 의 운영 감사 |
| Event clone (PR155) | `POST /events/{eid}/clone` | 1-click 복제 + currentParticipants 0 reset + NEW_EVENT 알림 — 정기 이벤트 운영 도구 |

### (e) PWA / 모바일 품질 (Phase 5)

| 영역 | 효과 |
|---|---|
| Manifest (PR156) | 홈 화면 추가 가능. iOS Safari 18.5+ 의 push 알림 전제 조건. |
| Install prompt (PR156) | Chromium 계열에서 native install banner 트리거. 14일 dismissal localStorage. |
| SW cache shell (PR157) | offline fallback page + 옛 cache 정리. API 응답은 절대 캐시 안 함 — 환불/결제 hot path 보호. |
| Push onboarding (PR158) | BrowserPushPanel 의 denied 상태에서 브라우저별 절차 안내. iOS Safari 18.5+ 의 PWA 필수 caveat 노출. |

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

### 최종 git 상태 (PR158 commit 후 push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead 15]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

본 cycle 가 더하는 15 commits (PR144~PR158). `git log --oneline origin/main..HEAD` 로 정확한 목록 확인.

### SW cache version bump 확인 (PR157)

`frontend/public/sw.js` 의 `SHELL_VERSION = 'v1'` 이 본 cycle 의 초기 값. 이후 사이클에서 sw.js / manifest / icons / offline.html / index 의 캐싱 가능한 자원을 수정했다면 **반드시 SHELL_VERSION 을 bump** 해야 옛 cache 가 깨끗하게 비워진다 — 다음 cycle release-notes 작성 시 본 체크 항목 유지.

---

## 4. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR144 (`1ec3569`) | backend `--tests *UserProfileServiceTest*` | green — 9 케이스 (row 없음 default / PRIVATE filter / lazy create / blank→null / no-op / null→unchanged / deleted / not-found / 2 더) |
| PR144 (`1ec3569`) | backend full / frontend build | green |
| PR145 (`7f9ae11`) | backend `--tests *TrustSummaryServiceTest*` + full | green — 4 케이스 + full 회귀 |
| PR146 (`89f86ae`) | backend `--tests *MannerFeedbackServiceTest*` + full | green — 8 케이스 (양방향 / 종료 전 / self / pair / 중복 / null summary / topTags / 등) |
| PR147 (`54dd140`) | backend `--tests *InterestServiceTest*` + full | green — 5 케이스 + 기존 UserProfile/Event 테스트 회귀 |
| PR148 (`eef2091`) | backend `--tests *RecommendationServiceTest*` + full | green — 8 케이스 (POPULAR fallback / INTEREST_MATCH / region+subscribed / tie-break / 매칭 0건 → POPULAR / CLOSING_SOON / LATEST / empty) |
| PR149 (`1bd537e`) | frontend build | green |
| PR150 (`ff0fff5`) | frontend build | green |
| PR151 (`2c0b7fc`) | backend `--tests *EventAnnouncementServiceTest*` + full | green — 기존 6 케이스 회귀 (생성자에 readRepository 주입 추가) |
| PR152 (`aa33cb3`) | backend full + frontend build | green |
| PR153 (`711e7ed`) | backend `--tests *CreatorAnalyticsServiceTest*` + full | green — 6 케이스 (빈 채널 / 다중 이벤트 합산 / non-owner / STAFF / ADMIN / 날짜 필터) |
| PR154 (`00b34b4`) | backend `--tests *ParticipantExportServiceTest*` + full | green — 6 케이스 (owner 빈 / phone masking + paid amount / non-owner / STAFF / ADMIN / audit row) |
| PR155 (`2ff96bd`) | backend `--tests *EventServiceTest*` + full | green — 4 신규 케이스 (owner clone / non-owner / invalid date / ADMIN) |
| PR156 (`db8c1b5`) | frontend build + dist manifest/icons 확인 | green |
| PR157 (`4100965`) | frontend build + dist sw.js/offline.html 확인 | green |
| PR158 (본 문서) | docs / BrowserPushPanel 카피 변경만 | build / test 생략 가능 |

**마지막 frontend `npm run build` green**: PR157 (`4100965`).

**마지막 전체 backend `gradle test` green**: PR155 (`2ff96bd`).

push 직후 CI 가 cold-start 로 (a) 전체 `./gradlew.bat test`, (b) `cd frontend; npm run build` 를 다시 통과해야 한다. Windows 환경 caches-jvm lock 으로 첫 시도가 실패하면 `./gradlew.bat --stop && ./gradlew.bat clean test` 으로 회복 — 본 cycle 중에도 한 번 발생 + 회복.

---

## 5. 운영 / 배포 주의사항

### Flyway 마이그레이션 — V14~V18 5건

| 버전 | 파일 | 내용 |
|---|---|---|
| V14 | `V14__add_user_profiles.sql` | PR144 — `user_profiles` 1:1 by user_id UNIQUE |
| V15 | `V15__add_user_manner_feedbacks.sql` | PR146 — UNIQUE(reviewer, reviewee, event), tags TEXT JSON |
| V16 | `V16__add_interests_and_regions.sql` | PR147 — `interests` (32 seed) + `regions` (270 row seed) + `user_interests`/`event_interests` composite-PK join + `events.region_code`/`user_profiles.region_code` ALTER |
| V17 | `V17__add_event_announcement_reads.sql` | PR151 — `event_announcements.pinned_at` ALTER + `event_announcement_reads` composite-PK |
| V18 | `V18__add_event_room_media.sql` | PR152 — `event_announcement_images` + `comments.images TEXT` ALTER |

배포 시 Flyway 가 V13 → V14 → ... → V18 순으로 적용. 모두 새 테이블 / 컬럼 추가만 — 기존 데이터에 영향 없음.

### 신규 환경 변수 — 없음

본 cycle 은 새 env 추가 없음 (PR139~PR143 에서 도입한 `PUSH_VAPID_*` 그대로 사용).

### 새 API 엔드포인트

| 엔드포인트 | 권한 | PR |
|---|---|---|
| `GET /api/v1/users/{userId}/profile` | 인증 | PR144 |
| `GET·PATCH /api/v1/users/me/profile` | 본인 | PR144 |
| `GET /api/v1/users/{userId}/trust-summary` | 인증 | PR145 |
| `POST /api/v1/events/{eid}/manner-feedbacks` | host/participant pair | PR146 |
| `GET /api/v1/users/{userId}/manner-summary` | 인증 | PR146 |
| `GET /api/v1/interests` | permitAll | PR147 |
| `GET /api/v1/regions` | permitAll | PR147 |
| `GET·PATCH /api/v1/users/me/interests` | 본인 | PR147 |
| `GET /api/v1/recommendations/events?segment=&size=` | permitAll | PR148 |
| `PATCH /api/v1/events/{eid}/announcements/{aid}/pin` | owner/STAFF/ADMIN | PR151 |
| `POST /api/v1/events/{eid}/announcements/{aid}/read` | APPROVED + writers | PR151 |
| `GET /api/v1/events/{eid}/announcements/unread-count` | APPROVED + writers | PR151 |
| `GET /api/v1/creator/channels/{cid}/analytics?from=&to=` | owner/STAFF/ADMIN | PR153 |
| `GET /api/v1/creator/events/{eid}/participants/export` | owner/STAFF/ADMIN | PR154 |
| `POST /api/v1/events/{eid}/clone` | owner/ADMIN | PR155 |

### Audit action enum 확장

`ModerationAuditAction.PARTICIPANT_EXPORTED` 추가 (PR154). frontend 의 `ModerationAuditAction` union 도 동기화 필요 (PR113 패턴) — 본 cycle 은 backend 만 도입했으므로 다음 cycle 에서 admin UI 갱신.

### NotificationType enum — 무변경

`EVENT_ANNOUNCEMENT` 는 PR141 (직전 cycle) 에서 도입됨. 본 cycle 은 신규 NotificationType 없음.

### 새 dependency — 없음

PR140 의 `nl.martijndwars:web-push` + `org.bouncycastle:bcprov-jdk18on` 그대로 사용.

### Service worker / manifest 변경

PR156 + PR157 부터 `dist/` 산출물에 `manifest.webmanifest` / `icons/icon-{192,512}.svg` / `offline.html` / `sw.js` 가 포함된다. 배포 시 정적 호스팅 (nginx / S3+CloudFront 등) 이 이 경로들을 그대로 서빙해야 PWA 가 동작. `Content-Type` 자동 인식이 안 되면 nginx config 에 `application/manifest+json` mime 추가.

---

## 6. Known follow-ups (의도된 미구현)

본 사이클은 다음 항목을 **건드리지 않는다**.

| 영역 | 상태 |
|---|---|
| **Phone PNG icons** | 192/512 SVG 로만 제공. Android Chrome 일부 OEM 은 PNG 를 선호 — 후속 PR 에서 raster 추가 가능. |
| **Frontend `ModerationAuditAction` 동기화** | PR154 가 추가한 `PARTICIPANT_EXPORTED` 가 admin UI filter union 에 아직 없음. 다음 cycle 에서 frontend 동기화 + filter chip 추가. |
| **Manner feedback edit / delete** | PR146 은 create / 누적 read 만. 잘못 보낸 평가는 수정/삭제 불가. |
| **Event announcement edit / delete** | PR141 + PR151 + PR152 누적 후에도 create / pin / read receipt 만. edit / delete 는 후속 PR. |
| **Channel STAFF 이벤트 복제** | PR155 는 owner / ADMIN 만. STAFF 는 운영 정책 (재발행은 owner 결정) 으로 제외. |
| **iOS PNG App Icons** | manifest 의 SVG icon 은 iOS 일부 버전에서 ignore. 별도 PNG splash 추가 가능. |
| **vite-plugin-pwa precache** | PR157 은 수동 SHELL_VERSION bump. dist/assets/* 의 build hash JS/CSS 는 precache 안 함 — 더 깊은 캐싱은 후속 PR. |
| **Native FCM adapter** | Web Push 만 지원. 직전 cycle (PR139~PR143) 의 known follow-up 그대로. |
| **Push retry queue** | 4xx/5xx 응답은 warn log 만. 직전 cycle 의 known follow-up 그대로. |
| **Recommendation 캐시** | RecommendationService 가 매 호출마다 100 candidate JPA + in-memory score. P95 임계 초과 시 Redis 캐시 또는 ES function_score. |
| **CSV 의 buyer/event lookup 컬럼** | PR154 export 의 nickname 은 user.nickname 만 (참가 시점의 spelling). channel name / event title 은 컬럼에 없음. |
| **환불 정산 reconciliation batch** | 직전 cycle 부터 미구현. |

---

## 7. Recommended manual QA (post-push verification)

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 staging deploy 직후 (또는 다음 사이클 작업 시작 전) 한 번 더 훑는다.

### 핵심 동선 (매 릴리스 필수)

- §1~§11 — 회원가입 / 채널 / 이벤트 생성 / 참가 신청 / 승인·거절 / 티켓 / 체크인 / 공지 / 알림 라우팅 / 비밀번호 변경

### 본 사이클의 핵심

- **§34 Public profile foundation (PR144)** — 6 항목
- **§35 Trust snapshot (PR145)** — 3 항목
- **§36 Manner feedback (PR146)** — 6 항목
- **§37 Interest & region taxonomy (PR147)** — 6 항목
- **§38 Personalized explore feed (PR148)** — 7 항목
- **§39 Discovery quality polish (PR149)** — 3 항목
- **§40 Event room hub (PR150)** — 4 항목
- **§41 Pinned announcements & read receipts (PR151)** — 5 항목
- **§42 Event room media (PR152)** — 5 항목
- **§43 Creator revenue & refund analytics (PR153)** — 5 항목
- **§44 Participant CSV export (PR154)** — 7 항목
- **§45 Event clone (PR155)** — 7 항목
- **§46 PWA manifest & install prompt (PR156)** — 7 항목
- **§47 SW cache shell + offline page (PR157)** — 6 항목
- **§48 Push onboarding (PR158)** — 5 항목 + §48.1 브라우저별 caveat 4종

### 회귀 (본 사이클 무변경)

- §13~§16, §22~§29 — 결제 / 환불 / forced refund / audit
- §20 / §21 — 알림 preference / notificationMeta
- §30~§33 — Web Push / Event 공지 / Channel new event (직전 cycle 결과 회귀)

🖱 / 📋 라벨 의미는 manual QA 문서 상단 "본 문서 사용법" 참고.

---

## 8. Push 전 권장 명령 (PR158 commit 후)

```bash
# 1) 최종 상태 확인 — PR144~PR158 = 15 ahead
git -C C:/WOYA status -sb
git -C C:/WOYA log --oneline origin/main..HEAD

# 2) 풀 빌드 + 테스트
cd C:/WOYA && ./gradlew.bat test
cd C:/WOYA/frontend && npm run build

# 3) 위 모두 green 이면 push (사용자가 직접 실행)
# git -C C:/WOYA push origin main
```

본 PR158 은 사실상 docs + BrowserPushPanel denied 안내 카피 추가만. 같은 push 에 올라가는 PR144~PR157 은 새 마이그레이션 (V14~V18) + 새 endpoint 15종 + 새 audit action + manifest/icons/offline.html/sw.js cache shell 을 포함하므로 deploy 시 Flyway 적용 + 정적 호스팅 mime 확인 필요.

---

## 9. 다음 사이클 (post-PR158 추천)

PR144~PR158 15 commit 으로 5 phase 가 닫혔다. 다음 사이클 후보:

1. **PR159 옵션 A — `PARTICIPANT_EXPORTED` admin filter 동기화**: frontend `ModerationAuditAction` union 갱신 + admin audit logs page 의 filter chip 추가. 작은 frontend 묶음.
2. **PR159 옵션 B — Push retry queue + delivery analytics**: PR140 의 best-effort 실패를 dead letter queue 로 모아 자동 retry / 운영자 dashboard. push 운영 신뢰성.
3. **PR159 옵션 C — Recommendation 캐시 / ES function_score**: P95 임계 초과 시 candidate fetch 를 ES 로 이관. 인덱싱 파이프라인 필요한 큰 backend PR.
4. **PR159 옵션 D — Manner feedback edit / delete + announcement edit / delete**: 잘못 보낸 평가/공지 수정·삭제. 운영 audit row 추가.
5. **PR159 옵션 E — vite-plugin-pwa precache**: PR157 의 수동 SHELL_VERSION 대신 build hash asset 자동 precache. 더 깊은 offline 보호.

옵션 A 는 frontend 정리. 옵션 B 는 push 운영의 신뢰성. 옵션 C 는 추천 인프라 확장. 옵션 D 는 본 cycle 의 known follow-up 해소. 옵션 E 는 PWA 품질의 다음 단계.

---

본 문서는 PR144~PR157 retrospective + PR158 self-audit. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR144-PR157.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
