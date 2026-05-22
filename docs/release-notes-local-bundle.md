# Local Release Bundle — PR159~PR163 (current cycle)

이 문서는 두 가지 역할을 동시에 한다:

1. **Push 전 self-audit** — PR163 commit + PR162 docs 까지 한 시점에서 ahead N commits 의 ship-readiness 노트.
2. **PR159~PR163 사이클 retrospective** — avatar upload (PR159), event room chat (PR160 + PR161 + PR162 docs), local storage fallback + image compression (PR163) 정리.

| 항목 | 값 |
|---|---|
| Base | `origin/main` (PR158 까지 push 완료) |
| Head | `<PR162 docs commit>` (본 문서 commit) |
| Ahead (본 cycle) | **5 commits** (PR159 ~ PR163) |
| 직전 cycle | PR144~PR158 15 commits (이미 push 완료) |
| 작성 시점 | 2026-05-22 |

이번 사이클은 직전 cycle 가 닫아둔 **이벤트룸 hub** 를 사용자가 실제로 대화할 수 있는 채팅 공간으로 활성화하고, 로컬 환경에서 이미지 업로드 흐름이 자연스럽게 동작하도록 폴리시한다. 새 backend 인프라(WebSocket / 메시지 큐 / 새 인증 체계) 없이 **기존 SSE + S3 추상화만 확장**해 운영 부담을 늘리지 않는다.

---

## 1. 커밋 묶음 요약

| commit | PR | 요약 |
|---|---|---|
| `d24b5c9` | PR159 | **Avatar file upload in profile**. URL input → 파일 업로드 버튼 + thumbnail + uploading aria-busy. 빈 상태 fallback. 기존 PR152 picker 패턴 재사용. backend 변경 없음. |
| `c92cb3b` | PR160 | **Event room chat backend (MVP)**. V19 `event_chat_messages` + `EventChatService` 입장/송신 가드 + `SseEmitterService.broadcast` 확장 + 운영자 isAnnouncement push. 7 신규 단위 테스트. |
| `eb3bba7` | PR161 | **Event room chat frontend (MVP)**. `connectNotificationStream` onChatMessage fan-in + `chatStore` event bus + `EventChatPanel` (canEnter / history / 실시간 append / 공지 체크박스 / Enter=전송). `EventRoomSection` 대화 탭 교체. `CommunityPage` 가 "내 이벤트룸" 입구로 전환. |
| `cb799b5` | PR163 | **Local disk fallback + frontend image compression**. `LocalFileStorage` `@ConditionalOnProperty` bean + `/uploads/**` 정적 핸들러 + `S3Service` `ObjectProvider` 위임. application.yml env 노출. `utils/imageCompression.ts` Canvas 자동 리사이즈 1280px JPEG 0.85. `api/files.ts` uploadFile 이 자동 압축 — 호출처 변경 없음. |
| `<TBD>` | PR162 | **docs**. architecture.md §6.13 (Event chat) / §6.14 (Local storage fallback) + manual-qa §49~§52 + 본 release-notes 갱신. |

PR162 가 마지막 docs commit 으로 들어가 ahead 5 commits 가 완성된다.

---

## 2. 사이클의 운영 가치

### (a) 프로필 image upload 격차 해소 (PR159)

직전 사이클 (PR144) 에서 user_profiles 1:1 테이블을 만들면서 avatarUrl 은 URL input 으로만 받을 수 있었다. 사용자가 직접 hosting URL 을 갖고 와야 했다는 뜻 — 사실상 사용 불가능. PR159 가 PR152 의 announcement picker 패턴을 그대로 옮겨 끝.

### (b) 이벤트룸 채팅 활성화 (PR160 + PR161)

PR150 에서 만든 "이벤트룸" hub 의 "대화" 탭은 기존 `comments` 테이블을 그대로 썼다 (TargetType.EVENT). 그러나 comment 도메인은 신고/숨김/cascaded delete/like count 등 운영 정책이 두껍고, 카카오톡 같은 실시간 대화에 적합하지 않다. 본 사이클은 **새 `event_chat_messages` 테이블 + 새 service** 를 만들어 분리:

| 차이 | comments (PR140 패턴) | event_chat_messages (PR160) |
|---|---|---|
| 도메인 | 게시판형 (좋아요/대댓글/숨김/신고/cascade) | 채팅형 (단방향 append, soft delete) |
| 페이징 | 일반 page/size | cursor (createdAt + id) — 안정 |
| 실시간 | SSE 'notification' event 일부 fan-out | SSE 'event-chat' fan-in (별도 채널) |
| 운영자 공지 | EventAnnouncement 별도 흐름 (PR141/151/152) | isAnnouncement=true 메시지가 자동 push |

운영자 입장에서는 "지금 빠르게 알려야 하는 일" 은 채팅 안에서 체크박스 한 번으로, "영구 공지로 남길 일" 은 공지 탭에서 — 두 흐름이 깨끗하게 분리.

#### 실시간 인프라 — WebSocket 없이 SSE 재사용

가장 큰 design 결정은 **WebSocket 도입을 미루고 기존 SSE 만 확장**한 것이다.

- backend `SseEmitterService.broadcast(userIds, eventName, payload)` 한 메서드만 추가. 같은 emitter 가 notification + chat 둘 다 전달.
- frontend `connectNotificationStream` 이 `onChatMessage` 콜백을 받고 `chatStore.dispatch` 로 fan-in. 새 EventSource 없음.

이 선택의 비용: SSE 는 server→client 단방향이므로 메시지 송신은 항상 REST POST 가 한 번 더 일어난다. 채팅 수준 (사용자 input → server 까지 < 100ms) 에서는 체감되지 않는다. WebSocket 의 이점 (typing indicator / presence / 양방향 메타) 이 필요해지면 후속 사이클에서 도입.

### (c) 로컬 개발 환경 이미지 업로드 정상화 (PR163)

기획자가 이벤트 등록 시 이미지 업로드가 실패한 근본 원인은 `application-local.yml` 의 fake AWS 자격 증명이었다. local/dev 의 S3 호출이 실제로 AWS 에 가서 403 / not-found 으로 실패. PR163 의 두 갈래 해결:

| 갈래 | 효과 |
|---|---|
| backend `LocalFileStorage` ConditionalOnProperty | `storage.local-fallback.enabled=true` 면 disk 저장 + `/uploads` 정적 핸들러. prod 는 bean 미등록 → S3 그대로. |
| frontend Canvas 자동 압축 | 4000×3000 PNG (~3MB) → ~200KB JPEG 로 정규화. local fallback 의 disk 사용량 + 운영 S3 비용 동시 절감. |

이 두 가지로 EventCreatePage / ProfilePage / EventAnnouncementsSection 모두 별도 변경 없이 자연스럽게 업로드 가능.

---

## 3. Push 전 확인사항

### 스테이징 금지 파일

여전히 합의된 제외 목록:

- `.claude/settings.local.json`
- `.claude/scheduled_tasks.lock`
- `build/resources/main/application.yml`
- `.gradle/**`
- `build/**`
- `frontend/dist/**`
- `*.tsbuildinfo`

추가로 본 사이클에서 새로 등장한 디스크 산출물:

- `~/.woya/uploads/**` — PR163 local fallback 으로 disk 에 저장되는 사용자 업로드 파일. **commit 절대 금지**. .gitignore 가 home 디렉터리를 추적하지 않으므로 자연스럽게 제외.

### 최종 git 상태 (PR162 commit 후 push 직전 예상)

```
git -C C:/WOYA status -sb
## main...origin/main [ahead 5]
 M .claude/settings.local.json
 M build/resources/main/application.yml
```

본 cycle 가 더하는 5 commits (PR159 ~ PR163). `git log --oneline origin/main..HEAD` 로 정확한 목록 확인.

### V19 migration 적용 확인

PR160 의 V19 `event_chat_messages` 가 prod 배포 시 자동 적용된다. 새 테이블만 추가 — 기존 데이터에 영향 없음. roll-back 이 필요하면 `DROP TABLE event_chat_messages;` 한 줄로 충분.

### 운영 cutover 시 storage 설정 점검

`storage.local-fallback.enabled` 가 prod 에서 false (또는 미설정) 인지 확인. 만약 prod yml 에 실수로 true 가 들어가면 S3 가 아닌 prod 서버 디스크에 저장돼 user 업로드 파일이 instance restart 마다 사라진다.

### SW cache version bump 확인

본 사이클은 `frontend/public/sw.js` 변경 없음. `SHELL_VERSION = 'v1'` 그대로. 다음 사이클에서 sw.js / manifest / icons 를 수정한다면 bump 필요.

---

## 4. 검증 기록 (사이클 내 빌드/테스트 결과)

| 시점 | 검증 | 결과 |
|---|---|---|
| PR159 (`d24b5c9`) | frontend build | green — 895ms |
| PR160 (`c92cb3b`) | backend `--tests *EventChatServiceTest*` | green — 7 케이스 (owner 무조건 / APPROVED 무료 / PENDING 거부 / 유료 CANCELED 거부 / 유료 PAID OK / participant announcement 거부 / owner announcement push receiver 본인 제외) |
| PR160 (`c92cb3b`) | backend full | green |
| PR161 (`eb3bba7`) | frontend build | green — 1.01s, 120 modules |
| PR163 (`cb799b5`) | frontend build + backend full | green (frontend 876ms, backend success) |
| PR162 (본 문서) | docs only | build / test 생략 가능 |

**마지막 frontend `npm run build` green**: PR163 (`cb799b5`).
**마지막 전체 backend `gradle test` green**: PR163 (`cb799b5`).

---

## 5. 운영 / 배포 주의사항

### Flyway 마이그레이션 — V19 1건

| 버전 | 파일 | 내용 |
|---|---|---|
| V19 | `V19__add_event_chat_messages.sql` | PR160 — 채팅 메시지 테이블 + idx(event_id, created_at) |

배포 시 V18 → V19 순으로 적용. 기존 데이터 영향 없음.

### 신규 환경 변수

| Key | 기본값 | 의미 |
|---|---|---|
| `STORAGE_LOCAL_FALLBACK_ENABLED` | `false` | true 면 disk 저장 + `/uploads` 서빙. **운영(prod) 에서 절대 true 금지**. |
| `STORAGE_LOCAL_FALLBACK_PATH` | `${user.home}/.woya/uploads` | disk 저장 경로. |
| `STORAGE_LOCAL_FALLBACK_PUBLIC_URL` | `http://localhost:8080/uploads` | 응답 URL prefix. dev 가 다른 host 면 override. |

### 새 API 엔드포인트

| 엔드포인트 | 권한 | PR |
|---|---|---|
| `GET /api/v1/events/{eid}/chat/can-enter` | 입장 가드 충족 사용자 | PR160 |
| `GET /api/v1/events/{eid}/chat/messages?beforeCreatedAt=&beforeId=&size=` | 위 | PR160 |
| `POST /api/v1/events/{eid}/chat/messages` | 위 (isAnnouncement=true 면 owner/STAFF/ADMIN 만) | PR160 |
| `GET /uploads/**` | permitAll (local fallback 활성 시에만 핸들러 등록) | PR163 |

### Audit action enum — 변경 없음

PR163 은 새 audit action 도입하지 않음. PR154 의 `PARTICIPANT_EXPORTED` 후속 frontend 동기화는 여전히 known follow-up.

### NotificationType — 변경 없음

PR160 의 공지 메시지는 기존 `EVENT_ANNOUNCEMENT` type 을 그대로 사용. 새 NotificationType 없음.

### 새 dependency — 없음

PR163 의 Canvas 압축은 standard `HTMLCanvasElement`/`createImageBitmap` 만 사용. frontend / backend 양쪽 모두 dep 추가 없음.

---

## 6. Known follow-ups (의도된 미구현)

| 영역 | 상태 |
|---|---|
| **WebSocket 도입** | 본 사이클은 SSE 만으로 충분. typing indicator / read receipt / presence 가 필요하면 후속에서 STOMP. |
| **채팅 메시지 수정/삭제** | 본인 메시지의 수정/삭제 UI 없음. soft delete 컬럼만 미리 두고 후속 PR. |
| **이미지/파일 채팅 첨부** | text only. 이미지는 EventAnnouncement 의 PR152 흐름을 사용. |
| **답글 (replyTo)** | MVP 범위 밖. |
| **차단/신고** | 채팅 메시지의 신고 흐름 미구현. 후속 PR 에서 PR50~52 신고 패턴을 EventChatMessage 로 확장. |
| **읽음 표시** | 채팅 메시지의 read receipt 미구현. PR151 announcement read 패턴 재사용 가능. |
| **메시지 검색** | 채팅 내 텍스트 검색 미구현. |
| **운영 모드 차단** | owner 가 채팅을 일시 잠그는 UI 미구현 — 운영 정책 결정 후 추가. |
| **vite-plugin-pwa precache** | PR157 의 수동 SHELL_VERSION 그대로. dist/assets/* hash 자동 precache 없음. |
| **Capacitor 모바일 패키징** | 별도 작업. 사용자 결정 시 시작. |

---

## 7. Recommended manual QA (post-push verification)

[docs/manual-qa-checklist.md](manual-qa-checklist.md) 의 다음 섹션을 staging deploy 직후 (또는 다음 사이클 작업 시작 전) 한 번 더 훑는다.

### 본 사이클 핵심

- **§49 Avatar file upload in profile (PR159)** — 5 항목
- **§50 Event room chat (PR160 + PR161)** — 8 항목 + §50.1 운영자 공지 체크 5 항목 + §50.2 회귀 2 항목
- **§51 Local storage fallback + image compression (PR163)** — 9 항목 + §51.1·51.2 PR152·PR159 회귀 2 항목
- **§52 PR144~PR163 cycle end-to-end smoke** — 6 항목 (회원가입 → 프로필 → 추천 → 신청 → 채팅 → 후기/매너 → analytics → clone)

### 회귀 (본 사이클 무변경)

- §1~§11 핵심 동선 + §13~§16 결제/환불 + §20~§21 알림 + §30~§33 Web Push + §40~§42 이벤트룸 hub
- 특히 **§40 Event room hub (PR150)** 의 "공지" 탭은 PR141/151/152 흐름 그대로 — PR160 채팅과 분리됨

---

## 8. Push 전 권장 명령 (PR162 commit 후)

```bash
# 1) 최종 상태 확인 — PR159~PR163 = 5 ahead
git -C C:/WOYA status -sb
git -C C:/WOYA log --oneline origin/main..HEAD

# 2) 풀 빌드 + 테스트
cd C:/WOYA && ./gradlew.bat test
cd C:/WOYA/frontend && npm run build

# 3) 위 모두 green 이면 push (사용자가 직접 실행)
# git -C C:/WOYA push origin main
```

본 PR162 는 docs 정리 only. 같은 push 에 올라가는 PR159~PR163 은 V19 migration + 새 endpoint 3종 + LocalFileStorage 인프라 + frontend 압축 helper 를 포함하므로 deploy 시 storage.local-fallback.enabled prod=false 확인 + Flyway 적용 확인이 필요.

---

## 9. 다음 사이클 (post-PR163 추천)

PR159~PR163 5 commit 으로 채팅과 업로드 흐름이 깨끗해졌다. 다음 사이클 후보:

1. **PR164 — 채팅 메시지 수정/삭제 + 신고**: 본인 메시지 edit/delete + 운영자 hide. soft delete 인프라는 이미 V19 에 있음.
2. **PR165 — 채팅 이미지 첨부**: text-only 제한 해제. EventAnnouncement image 패턴 (PR152) 재사용.
3. **PR166 — 채팅 read receipt**: PR151 announcement read 패턴을 EventChatMessage 로 확장. unread count badge.
4. **PR167 — Capacitor iOS/Android 패키징**: PWA → 네이티브 앱 패키지화. App Store / Play Store 등록 준비.
5. **PR168 — `PARTICIPANT_EXPORTED` admin UI 동기화**: PR154 의 known follow-up 해소.
6. **PR169 — vite-plugin-pwa precache**: PR157 의 수동 SHELL_VERSION 자동화 + dist hash asset 까지 캐싱.

옵션 1~3 은 채팅 깊이. 옵션 4 는 모바일 시장. 옵션 5 는 운영 관리. 옵션 6 은 PWA 품질 다음 단계.

---

본 문서는 PR159~PR163 retrospective + PR162 self-audit. push 후에는 본 문서를 그대로 두고 (또는 별도 `release-notes/PR159-PR163.md` 로 옮기고) 다음 묶음을 위해 새 release-notes 를 만든다.
