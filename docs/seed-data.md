# 수동 QA 용 seed 데이터

`spring.jpa.hibernate.ddl-auto: create` 로컬 설정 때문에 백엔드를 부팅할 때마다 DB 가 비워집니다. 이 문서는 QA 동선 ([docs/manual-qa-checklist.md](manual-qa-checklist.md)) 을 빠르게 돌릴 수 있도록 필요한 최소 데이터를 만드는 순서를 정리합니다.

코드 seed 스크립트는 본 PR 단계에서 도입하지 않습니다 — Spring 의 `CommandLineRunner` 나 SQL 시드를 추가하기 전에 정책(언제 돌릴지, 운영 프로파일과 어떻게 격리할지)을 먼저 합의해야 하기 때문에, 우선은 curl / UI 기반 매뉴얼 절차로 남깁니다.

## 1. 빠른 시드 (≈ 5분)

핵심 동선(가입 → 채널 → 이벤트 → 신청 → 승인 → 체크인) 한 번을 검증할 최소 세트.

### 1) 계정 3개 생성

```
# 기획자 (채널 owner)
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"owner@test.com","password":"password1","nickname":"오너","phoneNumber":"01011110000","role":"CREATOR"}'

# 스태프 후보 (채널 STAFF 로 추가될 예정)
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"staff@test.com","password":"password1","nickname":"스태프","phoneNumber":"01022220000","role":"CREATOR"}'

# 참가자
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"member@test.com","password":"password1","nickname":"참가자","phoneNumber":"01033330000","role":"PARTICIPANT"}'
```

응답의 `data.accessToken` 을 메모해 두면 이후 curl 에 `Authorization: Bearer ...` 헤더로 그대로 붙일 수 있습니다. 또는 UI 에서 각 계정으로 로그인해서 진행해도 됩니다.

### 2) 채널 생성 (owner)

UI: `/creator` → 채널 생성 폼. 또는 curl:

```
curl -X POST http://localhost:8080/api/v1/channels \
  -H "Authorization: Bearer <OWNER_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"name":"오너의 음악 채널","description":"홍대 라이브 모임","category":"MUSIC"}'
```

`ChannelService.createChannel` 이 owner 를 `ChannelMember(OWNER)` 로 자동 등록합니다.

### 3) STAFF 추가 (owner → staff 계정)

```
curl -X POST http://localhost:8080/api/v1/channels/<CHANNEL_ID>/members/staff \
  -H "Authorization: Bearer <OWNER_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"email":"staff@test.com"}'
```

### 4) 이벤트 생성 (owner)

UI: 채널 상세 → "새 이벤트". 또는 curl (시작/종료 시각은 미래로):

```
curl -X POST http://localhost:8080/api/v1/channels/<CHANNEL_ID>/events \
  -H "Authorization: Bearer <OWNER_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "title":"홍대 라이브 #1",
    "description":"신인 밴드 5팀",
    "location":"서울 마포구 어울마당로",
    "mainImageUrl":"https://placehold.co/600x400",
    "startAt":"2026-12-31T19:00:00",
    "endAt":"2026-12-31T22:00:00",
    "maxParticipants":30,
    "participationFee":0,
    "refundPolicy":"이벤트 시작 24시간 전까지 100% 환불",
    "detailContent":"## 라인업\n- 밴드 A\n- 밴드 B"
  }'
```

### 5) 참가 신청 → 승인 → 티켓 발급

```
# 참가자가 신청
curl -X POST http://localhost:8080/api/v1/events/<EVENT_ID>/participations \
  -H "Authorization: Bearer <PARTICIPANT_TOKEN>"

# owner 가 신청자 목록 조회 → participation id 확인
curl http://localhost:8080/api/v1/events/<EVENT_ID>/participations \
  -H "Authorization: Bearer <OWNER_TOKEN>"

# 승인 (PATCH 라는 점 주의)
curl -X PATCH http://localhost:8080/api/v1/events/<EVENT_ID>/participations/<PARTICIPATION_ID>/approve \
  -H "Authorization: Bearer <OWNER_TOKEN>"
```

승인 직후 `TicketService.issueFreeTicket` 이 PAID 티켓을 만들고, 참가자에게 `PARTICIPATION_APPROVED` + `TICKET_ISSUED` SSE 알림이 발송됩니다.

### 6) 체크인 (staff 또는 owner)

티켓의 체크인 코드는 `CONTENIDO-{ticketId}-{eventId}` 형식입니다. 티켓 상세에서 확인하거나, UI 의 `/tickets/{id}` 에 QR/코드가 노출됩니다.

```
curl -X POST http://localhost:8080/api/v1/tickets/check-in \
  -H "Authorization: Bearer <STAFF_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"checkInCode":"CONTENIDO-<TICKET_ID>-<EVENT_ID>"}'
```

성공 시 `Ticket.status` 가 PAID → USED, `usedAt` 세팅, 이벤트 상세의 체크인 카운트 SSE 갱신.

## 2. 풍부한 시드 (≈ 15분)

다양한 카테고리/콘텐츠 유형/엣지케이스까지 보고 싶을 때 추가로 만드는 데이터.

| 추가 항목 | 만드는 방법 | 검증할 흐름 |
|---|---|---|
| 다른 카테고리 채널 (TRAVEL, COOKING 등) | owner 로 추가 채널 생성 | 홈 카테고리 3×3 그리드, 검색·탐색 |
| Original / Classic / Special 이벤트 각 1개 | 이벤트 생성 시 `contentType` 지정 | 홈의 콘텐츠 유형 탭 |
| 정원이 1인 이벤트 | `maxParticipants: 1` | `EventFullException`, 두 번째 신청 거부 |
| 이미 시작된 이벤트 (수동 INSERT) | DB 직접 `UPDATE events SET start_at = NOW() - INTERVAL 1 HOUR ...` | `EventAlreadyStartedException`, 셀프 취소 거부 |
| CLOSED 이벤트 | `EventStatusScheduler` 가 종료되면 자동 전환 — 또는 DB 직접 `UPDATE events SET status = 'CLOSED'` | `EventClosedException` |
| 후기/댓글/좋아요 | 다른 참가자 계정으로 ContentController/Interaction API 호출 | 댓글·좋아요 카운트, 알림 |
| 다른 채널 구독 | `POST /channels/<id>/subscriptions` | 헤더 unread 카운트, `NEW_POST` 알림 |

## 3. ADMIN 계정 만들기

회원가입 API 의 `role` 은 PARTICIPANT/CREATOR 만 받습니다. ADMIN 이 필요하면 DB 에서 직접 변경하세요.

```
docker exec -it contenido-mysql mysql -uroot -ppassword contenido \
  -e "UPDATE users SET role = 'ADMIN' WHERE email = 'owner@test.com';"
```

ADMIN 권한이 필요한 동선:
- `/admin` 진입, 크리에이터 신청 승인/거절
- 이벤트 강제 수정 (owner 가 아니라도 ADMIN 은 PATCH 가능)
- 신고 처리, 사용자 비활성화 등 admin 도메인 기능

## 4. 리셋

가장 간단한 방법은 백엔드 재기동입니다 (`ddl-auto: create` 가 스키마와 데이터를 모두 비웁니다).

```
# 백엔드 종료 후 다시 실행
./gradlew.bat bootRun
```

부분 리셋 (특정 테이블만 비우기) 은 FK 제약 때문에 순서가 중요합니다 — `notifications` → `tickets` → `event_participations` → `events` → `posts` → `channel_members` → `channel_subscriptions` → `channels` → `users` 순.

```
docker exec -it contenido-mysql mysql -uroot -ppassword contenido <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE notifications;
TRUNCATE tickets;
TRUNCATE event_participations;
TRUNCATE events;
TRUNCATE posts;
TRUNCATE channel_members;
TRUNCATE channel_subscriptions;
TRUNCATE channels;
TRUNCATE users;
SET FOREIGN_KEY_CHECKS = 1;
SQL
```

Elasticsearch 인덱스(`channel-index`, `content-index`) 까지 비우고 싶다면 Kibana 의 Dev Tools 또는:

```
curl -X DELETE http://localhost:9200/channel-index
curl -X DELETE http://localhost:9200/content-index
```

비운 뒤 백엔드 재기동 시 `SearchSyncService` 가 인덱스를 다시 만들고 동기화합니다.

## 5. 다음 단계

코드 기반 seed 가 필요해질 시점 신호:
- QA 가 반복적으로 같은 데이터를 만들고 있다 → `CommandLineRunner` (local 프로파일 한정) 로 단계 1 자동화
- 데모/시연 용도로 항상 같은 상태가 필요하다 → SQL 마이그레이션(`db/migration/V_seed.sql`) + Flyway 도입과 함께 정리
- E2E 자동화가 들어오면 → 별도 테스트 프로파일에서 setup/teardown 으로 재현
