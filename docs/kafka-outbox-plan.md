# WOYA Kafka / Outbox Plan

> 본 문서는 **설계 제안서**다. 이번 PR에서는 코드를 변경하지 않으며, 향후 점진적 도입을
> 위한 합의 기준을 제공한다. 의사결정의 출발점은 “WOYA는 크리에이터가 이벤트 공고를 올리고
> 유저가 신청하는 플랫폼이며 곧 모바일 앱으로 확장된다”이다.

## 1. Why Now

### 1.1 현재 동기/인메모리 구조의 한계

PR1~PR6을 거치며 안정화된 현재 시스템은 다음 패턴들을 동기/인메모리로 처리한다.

- **알림**: `NotificationService.notify(...)`가 `@Async`로 분리되지만 여전히 **단일 JVM
  스레드 풀**에서 동작하며, DB 저장과 SSE 전송이 한 함수 안에 묶여 있다. push 실패는 로그만
  남고 재시도되지 않는다.
- **검색 인덱싱**: `SearchSyncService`가 `@TransactionalEventListener(AFTER_COMMIT)`로
  Elasticsearch에 직접 save한다. ES가 일시 장애일 때 `runCatching` 안에서 로그만 남고 **유실**된다.
- **SSE fanout**: `SseEmitterService`는 `ConcurrentHashMap<Long, SseEmitter>` 기반. 같은
  JVM에 접속한 사용자에게만 전달된다. 다중 인스턴스 배포 즉시 미전달 사용자가 발생한다.
- **신고 후속 처리**: 현재 `ReportService.createReport`는 DB 저장 외 다른 후속 액션이 없다.
  운영적으로 "신고 N건 누적 시 자동 알림" 같은 정책을 붙이려면 신호 채널이 필요하다.

### 1.2 곧 닥칠 부하 요인

- 모바일 앱은 SSE를 백그라운드에서 유지할 수 없다. **FCM/APNs push 파이프라인**이 필수다.
- 통계 집계(채널 구독자/콘텐츠 조회 등)가 현재는 join 쿼리지만, 사용자 수 증가에 따라 사전
  집계로 옮겨야 한다. 변경 이벤트가 broker로 흘러야 자연스럽다.
- 검색 ES 인스턴스가 장애를 겪을 때 **본문 트랜잭션은 성공했지만 검색에는 안 보이는 상태**가
  쌓이는 것을 운영적으로 묵인할 수 없다. backfill이 필요해진다.

### 1.3 Kafka를 바로 붙이지 않고 outbox부터 설계하는 이유

“DB 트랜잭션 안에서 직접 Kafka publish” 패턴은 **이중 쓰기(dual write)** 문제를 만든다.
DB commit과 Kafka publish 중 한쪽만 성공하는 윈도우가 존재한다.

- DB commit 성공 + Kafka publish 실패 → 이벤트 유실
- Kafka publish 성공 + DB commit 실패 → 유령 이벤트

이를 막는 가장 단순한 방법이 **transactional outbox**다. domain row와 outbox row를 같은
트랜잭션에서 저장하고, 별도 publisher가 outbox를 읽어 Kafka로 publish한다. Kafka 인프라가
당장 없어도 outbox 패턴은 코드/스키마 레벨에서 먼저 도입 가능하므로, "지금은 in-process로
처리하지만 outbox row는 남기는" dry-run 단계를 거칠 수 있다.

## 2. Current Event Hotspots

현재 코드 기준 이벤트성 작업이 일어나는 지점과 그 한계.

### 2.1 `NotificationService.kt`
```kotlin
@Async
@Transactional
fun notify(receiverIds, type, title, message, targetType, targetId) {
    val notifications = notificationRepository.saveAll(...)
    notifications.forEach { sseEmitterService.sendToUser(it.receiver.id, it.toResponse()) }
}
```
- 호출자: `EventService.createEvent`가 채널 구독자 전원에게 NEW_EVENT 알림
- 한계:
  - `@Async`는 별도 스레드일 뿐 **재시도/유실 방지 없음**
  - SSE send 실패는 emitter map에서 해당 emitter만 제거하고 끝
  - 다중 인스턴스에서 receiver의 emitter가 다른 JVM에 있으면 silent miss
  - 모바일 push가 추가되면 이 함수 안에서 또 fan-out 해야 하므로 책임이 커진다

### 2.2 `SseEmitterService.kt`
- JVM local `ConcurrentHashMap<Long, SseEmitter>` (PR5에서 활성 개수/연결 여부 조회 메서드만 노출)
- 클래스 KDoc에 이미 **multi-instance fanout 필요** TODO 명시
- Kafka가 아닌 **Redis Pub/Sub**가 더 적합할 가능성이 큼 (§7 참조)

### 2.3 `SearchSyncService.kt`
```kotlin
@TransactionalEventListener(AFTER_COMMIT)
@Transactional(REQUIRES_NEW)
fun handleChannelSync(event: ChannelSyncEvent) { ... }
fun handleContentSync(event: ContentSyncEvent) { ... }
```
- Spring `ApplicationEventPublisher` 기반 in-process 이벤트
- ES 저장 실패는 `runCatching`으로 삼키고 로그만 남음 → **재시도/유실 방지 없음**
- **outbox 도입 1순위 대상**

### 2.4 `ChannelService.kt`
- `createChannel`/`updateChannel`에서 `publisher.publishEvent(ChannelSyncEvent(channel.id))`
- subscribe/unsubscribe는 publish 없음 (구독자 카운트 즉시 갱신만)

### 2.5 `EventService.kt`
- `createEvent`에서 `publisher.publishEvent(ContentSyncEvent(SYNC, "EVENT", event.id))`
- 동시에 `notificationService.notify(subscriberIds, NEW_EVENT, ...)`를 직접 호출
  → 검색 인덱싱과 알림이 **서로 다른 메커니즘**으로 흩어져 있다

### 2.6 `ReportService.kt` / `AdminService.kt`
- 현재 신고 생성/처리 모두 DB 단순 저장/상태 전이만 수행
- 향후 정책 후보:
  - 같은 타겟에 신고 N건 누적 → 관리자에게 알림
  - 신고 누적 임계치 초과 → 자동 일시 정지
- 이런 정책을 service 본문에 직접 박지 않고 `woya.report.created` 이벤트로 노출하는 게 깔끔

### 2.7 `AuthService.kt`
- refresh token Redis lifecycle은 **Kafka 대상이 아님**
- 짧은 TTL 키-값 저장 + 즉시 일관성이 필요 → Redis가 맞는 선택
- Kafka outbox 설계에서 Auth 도메인은 제외

## 3. Outbox First Principle

### 3.1 트랜잭션 경계

```
@Transactional {
  domainRepository.save(domain)
  outboxRepository.save(OutboxRow(eventType, payload, status=PENDING, ...))
}
```

- domain row와 outbox row를 같은 트랜잭션에 저장 → 둘 다 commit되거나 둘 다 rollback
- DB commit 후 publisher worker가 PENDING row를 polling/listening해서 Kafka publish
- publish 성공 시 status=PUBLISHED + publishedAt 갱신
- publish 실패 시 retryCount 증가, nextRetryAt 갱신, status=FAILED는 retry 한계 초과 시
- DLQ/dead-letter는 status=FAILED인 row를 별도 모니터링으로 처리

### 3.2 보장하는 것 / 보장하지 않는 것

| 항목 | 보장? |
|---|---|
| at-least-once delivery to Kafka | ✓ |
| exactly-once across consumers | ✗ (consumer side idempotency 필요) |
| event ordering across aggregateIds | ✗ |
| event ordering within same aggregateId | ✓ (publisher가 outbox id 또는 partition key를 aggregateId로) |
| broker 장애 시 도메인 트랜잭션 실패 | ✗ (outbox row만 쌓이고 publisher가 따라잡음) |

### 3.3 Consumer idempotency 패턴

- payload에 `eventId` (outbox row PK) 포함
- consumer가 처리한 eventId를 Redis SETEX (TTL 24h~7d) 또는 별도 `processed_events` 테이블에 기록
- 중복 수신 시 skip

## 4. Proposed Topics

명명 규칙: `woya.{aggregate}.{event}` (kebab-case)

### 4.1 `woya.notification.requested`

- **Producer 후보**: `EventService.createEvent`, 향후 `PostService.createPost`, `CommentService.createComment`, `AdminService.creator-application-decided`
- **Consumer 후보**:
  - `NotificationPersistenceConsumer` — DB에 Notification row 저장
  - `SseFanoutConsumer` — 현재 접속 중인 receiver에게 SSE 전송
  - `MobilePushConsumer` — FCM/APNs 전송 (향후)
- **Payload 핵심 필드**: `notificationId(outbox PK)`, `receiverIds: List<Long>`, `type`, `title`, `message`, `targetType`, `targetId`, `createdAt`
- **Idempotency key**: `notificationId` (outbox PK 기반). receiver별로 fan-out 후에는 `(notificationId, receiverId)` 조합
- **Retry/DLQ**: receiver별 처리가 분리되므로 retry 가능. SSE는 best-effort, 모바일 push는 retry 3회 + DLQ

### 4.2 `woya.search.index-requested`

- **Producer**: `ChannelService.createChannel/updateChannel`, `EventService.createEvent`, `PostService.createPost`, 향후 delete 흐름
- **Consumer**: `SearchIndexConsumer` — ES upsert/delete
- **Payload**: `aggregateType: "CHANNEL"|"EVENT"|"POST"`, `aggregateId: Long`, `action: "SYNC"|"DELETE"`
- **Idempotency key**: ES document id는 이미 `EVENT_{id}` 같은 결정적 키. 같은 payload의 재처리는 멱등
- **Retry/DLQ**: ES 일시 장애에 매우 흔한 케이스. exponential backoff 5회 + DLQ
- **순서 보장**: 같은 aggregateId(예: 같은 채널의 update→delete)는 순서가 중요. partition key = aggregateType+aggregateId

### 4.3 `woya.stats.channel-events`

- **Producer**: `ChannelService.subscribe/unsubscribe`, `EventService.createEvent`, `PostService.createPost`, `ContentService.createContent`, view tracking (향후)
- **Consumer**: `ChannelStatsConsumer` — `channel_stats` 사전 집계 테이블 또는 OLAP store(예: ClickHouse) 갱신
- **Payload**: `channelId`, `metric: "SUBSCRIBER_DELTA"|"EVENT_CREATED"|"POST_CREATED"|"CONTENT_VIEWED"`, `delta: Long`, `occurredAt`
- **Idempotency key**: `eventId` (outbox PK)
- **Retry/DLQ**: stats는 eventual consistency 허용. retry 무한이지만 timeout 후 DLQ
- **순서 보장**: 같은 channelId의 delta 순서는 무관 (합산만 정확하면 됨) → idempotency만 잘 잡으면 됨

### 4.4 `woya.report.created`

- **Producer**: `ReportService.createReport`
- **Consumer 후보**:
  - `ReportAuditConsumer` — 동일 타겟의 신고 누적 카운트를 Redis/cache에 증가
  - `AdminAlertConsumer` — 누적 N건 넘으면 admin 채널/이메일/알림 트리거
  - `AutoModerationConsumer` (향후) — 자동 일시 정지 등
- **Payload**: `reportId`, `reporterId`, `targetType`, `targetId`, `reason`, `createdAt`
- **Idempotency key**: `reportId`
- **Retry/DLQ**: best-effort consumer가 다수. 핵심은 DB의 reports 테이블; broker 이벤트는 부가 액션용

### 4.5 `woya.event.application-changed`

- **Producer**: `EventService.joinEvent/cancelJoin` (현재 `EventParticipation` row 저장 시점)
- **Consumer 후보**:
  - `CreatorNotificationConsumer` — 이벤트 owner에게 "새 신청자 X명" 알림 (현재 미구현)
  - `ParticipantNotificationConsumer` — 참가자에게 "신청 확정/취소" 알림
  - `StatsConsumer` — 채널 stats 갱신
  - `MobilePushConsumer`
- **Payload**: `eventId`, `channelId`, `participantId`, `action: "APPLIED"|"CANCELLED"`, `currentParticipants`, `maxParticipants`, `occurredAt`
- **Idempotency key**: `(eventId, participantId, action, occurredAt)` 또는 outbox PK
- **Retry/DLQ**: 알림은 retry 가능. participant DB row가 source of truth

### 4.6 `woya.mobile-push.requested`

- **Producer**: `NotificationFanoutConsumer` (위 `woya.notification.requested`의 subscriber)
  → 모바일 등록 토큰을 가진 receiver만 push 대상으로 변환
- **Consumer**: `FcmPushConsumer`, `ApnsPushConsumer`
- **Payload**: `receiverId`, `deviceTokens: List<String>`, `platform: "FCM"|"APNS"`, `payload: Map`
- **Idempotency key**: `(notificationId, deviceToken)`
- **Retry/DLQ**: 외부 의존도 높음. exponential backoff + DLQ + 무효 토큰 정리

## 5. Proposed Outbox Schema

```sql
CREATE TABLE outbox_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    aggregate_type   VARCHAR(50)  NOT NULL,
    aggregate_id     BIGINT       NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    payload_json     JSON         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count      INT          NOT NULL DEFAULT 0,
    next_retry_at    DATETIME     NULL,
    created_at       DATETIME     NOT NULL,
    published_at     DATETIME     NULL,
    last_error       VARCHAR(500) NULL,

    INDEX idx_outbox_status_next_retry (status, next_retry_at),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
);
```

JPA 엔티티 스케치:
```kotlin
@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Column(nullable = false, length = 50) val aggregateType: String,
    @Column(nullable = false)               val aggregateId: Long,
    @Column(nullable = false, length = 100) val eventType: String,
    @Column(nullable = false, columnDefinition = "JSON") val payloadJson: String,
) {
    @Id @GeneratedValue val id: Long = 0
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING
    var retryCount: Int = 0
    var nextRetryAt: LocalDateTime? = null
    @CreatedDate var createdAt: LocalDateTime = LocalDateTime.now()
    var publishedAt: LocalDateTime? = null
    @Column(length = 500) var lastError: String? = null
}

enum class OutboxStatus { PENDING, PUBLISHED, FAILED }
```

### 5.1 페이로드 가이드라인

- **내부 식별자 OK**: `userId`, `channelId`, `eventId`, `reportId`
- **민감정보 금지**: email, phoneNumber, password hash, 결제 정보, 실명, JWT/refresh token
- 알림 텍스트(`title`, `message`)는 사용자 입력이 포함될 수 있으므로 길이 제한과 sanitize는 producer 측에서 처리
- payload는 forward-compatible JSON: 필수 필드만 명시하고 unknown field는 consumer가 무시하도록 설정

### 5.2 보존 정책

- `PUBLISHED` row는 30일 보관 후 cron으로 archive 또는 delete
- `FAILED` row는 영구 보관 + 알람
- production DB 부하 우려가 있으면 `outbox_events_archive` 테이블로 옮긴 뒤 OLAP/cold storage로 빼낸다

## 6. Migration Path

단계별로 진행한다. **각 단계는 단독 PR**로 끊어 PR이 비대해지지 않게 한다.

### 6.1 Step 1 — Outbox 테이블/엔티티 추가, dry-run
- `OutboxEvent` entity + repository + `OutboxRecorder` (DB save만 담당)
- production code는 기존 동기 흐름을 그대로 유지하되, 한 도메인(예: `ReportService.createReport`)에서 outbox row를 추가 저장
- Kafka publisher는 도입하지 않음. row가 쌓이는 것만 확인
- 마이그레이션 SQL 별도 작성 (prod ddl-auto=validate)

### 6.2 Step 2 — SearchSyncService를 outbox 기반으로 전환
- `ChannelService`/`EventService`/`PostService`가 outbox에 `search.index-requested` 이벤트를 저장
- 기존 `ApplicationEventPublisher` 경로는 일정 기간 병행 → 회귀 없으면 제거
- Polling-based publisher (`@Scheduled(fixedDelay = 500)`): outbox에서 PENDING row 1배치 가져와 ES upsert → 성공 시 PUBLISHED
- 이 단계까지는 **Kafka 없이도 동작**. broker는 그 다음 단계

### 6.3 Step 3 — NotificationService를 outbox 기반으로 전환
- `EventService.createEvent`가 직접 `notificationService.notify(...)`를 호출하던 부분을 outbox 저장으로 교체
- `NotificationFanoutConsumer`가 outbox에서 읽어 DB 저장 + SSE send를 수행
- 이 시점에서 **알림 유실 0**, **재시도 가능** 보장

### 6.4 Step 4 — SSE multi-instance fanout
- **Kafka보다 Redis Pub/Sub가 더 단순**
- 알림 fanout consumer가 receiver의 emitter를 갖고 있을 인스턴스를 모르므로, 모든 인스턴스에
  publish하는 방식: Redis channel `notif:user:{userId}` 또는 단일 채널 + 메시지 내 receiverId 필드
- 각 인스턴스의 `SseEmitterService`는 채널 subscribe → 자기 JVM에 emitter가 있을 때만 send
- 이 단계는 §7에서 별도로 다룬다

### 6.5 Step 5 — Kafka 도입
- 이 시점까지 outbox가 모든 비동기 작업의 source of truth로 자리잡음
- Polling publisher를 **Kafka producer**로 교체 (또는 병행)
- Consumer는 각 도메인별 microservice 또는 분리된 process로 운영
- Schema Registry (Avro/Protobuf) 도입 검토

### 6.6 Step 6 — 모바일 push pipeline
- `NotificationFanoutConsumer`가 receiver의 device token이 있으면 `woya.mobile-push.requested`에 publish
- `FcmPushConsumer` / `ApnsPushConsumer`가 외부 push 서비스 호출
- 무효 토큰 정리, retry, DLQ 정책 필요

### 6.7 Step 7 — 통계 집계 consumer
- `channel_stats` 사전 집계 테이블 또는 OLAP store
- `ChannelStatsService`가 read 시 사전 집계 값을 사용 (현재 join 쿼리 대체)

## 7. Redis vs Kafka Responsibilities

| 책임 | Redis | Kafka/outbox |
|---|---|---|
| refresh token 저장 | ✓ (`RT:{userId}`, PEXPIRE) | ✗ |
| rate limit (IP/사용자별 quota) | ✓ (Bucket4j Redis) | ✗ |
| short-lived SSE token (PR5a 후보) | ✓ (`SSE:{userId}`, 60s TTL) | ✗ |
| SSE presence (현재 user가 접속 중인지) | ✓ (`PRESENCE:{userId}`, EXPIRE on ping) | ✗ |
| SSE 알림 fanout to instances | ✓ (Pub/Sub) | △ (가능하지만 과함) |
| 알림 durable 처리 (재시도/DLQ) | ✗ | ✓ |
| 검색 인덱싱 | ✗ | ✓ |
| 통계 집계 stream | ✗ | ✓ |
| 신고 후속 워크플로 | ✗ | ✓ |
| 모바일 push dispatch | ✗ | ✓ |

핵심 분할 원칙:
- **Redis**: 짧은 TTL, 즉시 일관성, fanout
- **Kafka/outbox**: durable, 재시도, replay, 멀티 consumer

> **SSE multi-instance fanout만은 Kafka가 아닌 Redis Pub/Sub를 권장.** 알림이 이미 outbox→
> `woya.notification.requested`로 가는데, 동일 알림을 SSE용으로 다시 Kafka topic에 쓰면
> 메시지가 두 곳에 흐른다. 대신 fanout consumer가 알림 DB 저장 후 Redis Pub/Sub로
> "현재 접속 user에게만" 신호를 보내는 게 단순하다.

## 8. Mobile Implications

### 8.1 SSE의 모바일 한계
- iOS Safari/WebKit: 백그라운드 진입 시 EventSource 자동 종료
- Android Chrome: 도즈 모드에서 연결 유지 불가
- 네이티브 앱: 백그라운드 네트워크 사용 제약 + 배터리 영향
- → 백그라운드 알림은 반드시 **FCM/APNs**

### 8.2 이중 채널 전략
- **앱 foreground**: SSE 또는 polling (가벼운 변경에 사용)
- **앱 background**: FCM/APNs push (durable)
- 같은 알림이 두 채널로 도착할 수 있으므로 모바일 클라이언트는 `notificationId` 기반 중복 제거

### 8.3 Push 대상 후보
- `NEW_EVENT`: 채널 구독자에게
- `EVENT_APPLICATION_CONFIRMED/CANCELLED`: 참가자 + 채널 owner에게
- `EVENT_REMINDER` (향후): 이벤트 시작 1시간 전 참가자에게 (scheduler 필요)
- `APPLICATION_APPROVED/REJECTED`: 크리에이터 신청 결과
- `REPORT_ACTION_TAKEN` (선택): 신고자에게 처리 결과

### 8.4 토큰 관리
- 사용자가 로그인한 device의 push token을 별도 테이블에 저장 (`push_tokens(user_id, token, platform, last_seen_at)`)
- 무효 토큰(FCM 응답 `UNREGISTERED` 등)은 정리
- 한 사용자가 여러 device를 가질 수 있음 → 1:N

## 9. Operational Concerns

### 9.1 Consumer idempotency
- 모든 consumer는 `eventId`(outbox PK)를 기반으로 중복 처리 방지
- 짧은 윈도우는 Redis SETEX, 장기 보관은 `processed_events` 테이블
- 멱등 보장이 어려운 외부 호출(예: FCM)은 외부 응답을 audit log에 기록

### 9.2 Poison message / dead-letter
- consumer가 같은 message에서 3회 연속 실패하면 DLQ로 이동
- DLQ는 별도 topic (`woya.notification.requested.dlq`) 또는 outbox `status=FAILED`
- 운영자가 검토하여 수정 후 재처리

### 9.3 Schema versioning
- payload JSON은 forward/backward compatible 원칙
- 새 필드 추가는 OK, 기존 필드 제거/타입 변경은 새 eventType (`v2`)
- Schema Registry 도입 시 (Step 5 이후) Avro/Protobuf로 enforce

### 9.4 Observability
- 핵심 메트릭:
  - outbox PENDING row 수 (publisher 백로그 지표)
  - publish lag (createdAt → publishedAt 분포)
  - consumer lag (Kafka consumer group offset)
  - retry count 분포
  - FAILED row 수
- Prometheus 노출 + Grafana 대시보드 + 임계치 알림

### 9.5 Replay strategy
- outbox는 PUBLISHED 후에도 retention 기간 동안 보관 → 필요 시 publisher가 다시 publish
- Kafka 자체 retention과 함께 활용

### 9.6 Retention
- Kafka topic retention: 7일 기본, stats topic은 30일
- outbox: PUBLISHED 30일, FAILED 영구
- 모바일 push 응답 log: 90일

### 9.7 Ordering guarantees
- Kafka는 partition 내에서만 순서 보장
- `aggregateId`를 partition key로 사용하여 같은 채널/이벤트의 순서 보존
- cross-aggregate 순서가 필요한 경우는 별도 sequence number를 payload에 포함

### 9.8 Privacy / security
- payload에 개인정보 직접 포함 금지 (§5.1)
- Kafka broker 접근은 IAM/ACL로 제한, TLS 필수
- DLQ도 일반 topic과 같은 권한 정책 적용

## 10. What Not To Do Yet

이번 PR에서 **명시적으로 하지 않을 것**:

- **Kafka 인프라 즉시 도입 금지**. Step 1~3까지는 broker 없이도 동작한다.
- **DB transaction 안에서 직접 Kafka publish 금지**. dual-write 문제를 outbox가 해결한다.
- **SSE multi-instance 문제를 Kafka만으로 해결하려 하지 말 것**. Redis Pub/Sub가 더 단순하고
  운영 부담이 작다 (§7).
- **알림 payload에 민감정보(email, phoneNumber, JWT 등) 포함 금지** (§5.1).
- **Auth 도메인(refresh token lifecycle)을 Kafka로 옮기지 말 것**. Redis가 맞는 도구다.
- **이벤트 참가비 결제/환불 처리를 Kafka outbox로 묶지 말 것**. 결제는 별도 PR/별도 도메인에서
  외부 PG 연동 + saga 패턴으로 다뤄야 한다.
- **모든 도메인을 한 PR에서 outbox로 전환하지 말 것**. Search → Notification → SSE fanout →
  Mobile push 순서로 끊어 진행한다 (§6).

---

**버전**: v1.0 (2026-05-11) · **다음 검토**: Step 1 진입 직전, 또는 SSE 멀티 인스턴스 트리거가
생겼을 때
