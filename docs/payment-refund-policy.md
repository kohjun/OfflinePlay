# 결제 / 환불 정책 (PG 도입 전 정리)

CONTENIDO MVP 는 결제 PG 가 아직 붙지 않은 상태. 티켓은 무료 발급 + 수동 체크인까지만 동작한다. 이 문서는 PG (토스/포트원/스트라이프 등) 가 합류하기 전 현재 코드의 상태 머신을 정리하고, 도입 시 보강할 지점을 미리 정의한다.

작성 시점: 2026-05-12
대상 코드: `src/main/kotlin/com/OfflinePlay/domain/ticket/`, `src/main/kotlin/com/OfflinePlay/domain/event/`

---

## 1. 상태 머신

### TicketStatus

```
              ┌────────┐  participant 승인
              │ PAID   │ ◀──────────────────── (참가비=0인 현재 MVP)
              └───┬────┘
                  │
                  │ 스태프/owner 체크인        participant 본인 취소
                  ▼                            (이벤트 시작 전, USED 아님)
              ┌────────┐                  ┌──────────┐
              │ USED   │                  │ CANCELED │
              └────────┘                  └──────────┘
                                              │
                                              │ (PG 도입 후) 환불 완료
                                              ▼
                                          ┌──────────┐
                                          │ REFUNDED │
                                          └──────────┘
```

- **PAID**: 참가 신청이 APPROVED 로 전환되면 `ticketService.issueFreeTicket()` 가 PAID 로 발급한다. **PG 도입 후**: 결제 webhook 으로 `결제완료` 이벤트를 받아야 PAID 로 전환.
- **USED**: 현장 스태프/owner/ADMIN 이 체크인 코드(`CONTENIDO-{ticketId}-{eventId}`)로 체크인 → `Ticket.markUsed()` 가 `usedAt` 을 세팅. **PAID → USED** 만 허용 (그 외 상태는 `TicketNotPaidException`).
- **CANCELED**: participant 가 본인 참가 신청을 취소(APPROVED → CANCELED 경로) 할 때 연결된 PAID 티켓도 함께 CANCELED. **USED 상태는 취소 불가** (`TicketAlreadyUsedException`).
- **REFUNDED**: 현재 코드는 `Ticket.refund()` 메서드가 정의만 되어 있고 호출처가 없음. **PG 도입 시** 환불 webhook 처리 흐름의 종착점. CANCELED → REFUNDED 가 정상 경로.

### ParticipationStatus

```
신청          ┌────────┐  기획자 승인     ┌──────────┐
─────────────▶│ PENDING│ ───────────────▶│ APPROVED │
              └───┬────┘                  └────┬─────┘
       기획자 거절│                            │ 참가자 본인 취소
                  ▼                            ▼ (이벤트 시작 전)
              ┌────────┐                  ┌──────────┐
              │REJECTED│                  │ CANCELED │
              └────────┘                  └──────────┘
                  │                            ▲
                  │  재신청 (REJECTED/CANCELED → PENDING)
                  └────────────────────────────┘
```

- **PENDING**: 참가자가 `POST /events/{id}/participations` 호출 → 즉시 PENDING 저장. `Event.currentParticipants` 는 증가하지 않음.
- **APPROVED**: 채널 owner/ADMIN 이 승인 → `Event.currentParticipants` +1, `TicketService.issueFreeTicket` 발급. 정원이 차 있으면 `EventFullException`.
- **REJECTED**: 채널 owner/ADMIN 이 거절 + 사유 보관. 카운트 영향 없음.
- **CANCELED**: 참가자 본인이 취소. PENDING 에서는 자유, APPROVED 에서는 **이벤트 시작 전이고 티켓이 USED 가 아닐 때만** 가능.

---

## 2. 취소 정책

### 참가자 셀프 취소 (현재 코드)

| 시점 | 상태 전이 | 정원 | 티켓 |
|---|---|---|---|
| PENDING | PENDING → CANCELED | 변화 없음 | 발급된 티켓 없음 |
| APPROVED, 이벤트 시작 전, 티켓 PAID | APPROVED → CANCELED | `decreaseParticipant()` -1 | PAID → CANCELED |
| APPROVED, 이벤트 시작 전, 티켓 USED | `TicketAlreadyUsedException` (409) | — | — |
| APPROVED, 이벤트 시작 후 | `EventAlreadyStartedException` (409) | — | — |
| REJECTED / 이미 CANCELED | `ParticipationNotPendingException` (409) | — | — |

### 기획자 측 거절 / 취소

- 거절(REJECTED): PENDING 만 거절 가능. APPROVED 거절 API 는 현재 미구현 — APPROVED 후엔 참가자 셀프 취소 또는 ADMIN 개입.
- 이벤트 자체 취소: 본 MVP 에는 별도 엔드포인트 없음. 이벤트 `status` 가 CLOSED 로 전환되면 신규 신청만 막힘 (`EventClosedException`), 기존 APPROVED 는 그대로 유지.

---

## 3. USED 이후 취소 불가 (불변 규칙)

체크인이 완료된 티켓은 **참가자 본인도 기획자도 ADMIN 도 취소할 수 없다**. 근거:
- 현장 입장 확인 = 서비스 제공 완료
- 환불 처리는 PG 도입 후 별도 운영 도구를 통해 수동 처리 (예: 노쇼 보상, 행사 취소 보상 등)

코드 강제 지점:
- `EventService.cancelMyApplication`: `ticket.status == TicketStatus.USED` 이면 `TicketAlreadyUsedException`.
- `TicketService.checkInTicket`: `ticket.status != TicketStatus.PAID` 이면 `TicketNotPaidException` (USED 재체크인도 여기서 차단).

---

## 4. 이벤트 시작 전 취소 정책

`EventService.cancelMyApplication` APPROVED 분기:

```kotlin
if (!event.startAt.isAfter(LocalDateTime.now())) {
    throw EventAlreadyStartedException()
}
```

- **시작 시각이 미래** (지금 < startAt): 취소 허용.
- **시작 시각이 현재/과거**: 취소 거부. participant 는 노쇼 처리됨 (티켓이 PAID 로 유지되고 체크인 안 되면 운영 보드에서 "미입장"으로 노출).

PG 도입 시 환불 정책은 위 시점 + 추가 규칙 (예: 시작 24시간 전까지 100%, 그 이전까지 50% 등) 으로 세분화 예정. `Event.refundPolicy` 필드(TEXT) 가 이미 자유 텍스트로 존재 — 추후 구조화된 enum 또는 작은 sub-document 로 확장 가능.

---

## 5. PG 도입 시 필요한 webhook 상태

PG 가 보내는 webhook 종류와 ticket 상태 매핑:

| Webhook 이벤트 | 트리거 | TicketStatus 전이 | 비고 |
|---|---|---|---|
| `payment.initiated` | 결제 페이지 진입 | (DB row 미생성, 또는 별도 `PaymentAttempt` 테이블) | 멱등 키 확보용 |
| `payment.completed` | 결제 승인 완료 | (PENDING_PAYMENT →) PAID | **이때 비로소 Ticket row 생성/PAID 전환**, `EventParticipation` APPROVED 자동 전환 가능 |
| `payment.failed` | 결제 거절/취소 | (DB row 정리) | participant 에게 알림 |
| `refund.initiated` | 운영 도구에서 환불 요청 | PAID/CANCELED → (in-flight) | `Ticket.refundRequestedAt` 등 신규 컬럼 필요 |
| `refund.completed` | 환불 입금 완료 | (in-flight) → REFUNDED | `Ticket.refund()` 호출 |
| `refund.failed` | 환불 실패 | (in-flight) → PAID/CANCELED 복구 | 후술 |

### 추가될 enum / 컬럼 후보

- `TicketStatus.PENDING_PAYMENT` 또는 `AWAITING_PAYMENT` — 결제 페이지로 redirect 됐지만 webhook 미수신 상태. **현재는 무료 발급이라 불필요** — 도입 시점에 추가.
- `Ticket.paymentTransactionId: String?` — PG 측 거래 ID. 멱등성 + 환불 시 참조.
- `Ticket.refundedAt: LocalDateTime?` — `usedAt` 과 동일 패턴.
- `PaymentAttempt` 엔티티 (선택) — 결제 시도 이력. PG 가 동일 ticket 에 대한 webhook 을 재전송할 때 멱등 처리용.

### 멱등성

PG webhook 은 네트워크 재시도로 중복 도착 가능. 처리 패턴:
1. `payment.completed` webhook 의 `paymentTransactionId` 를 unique 인덱스로.
2. 이미 처리된 transactionId 면 200 응답만 돌려주고 상태 변경 skip.
3. signed payload 검증 (PG 별 secret 으로 HMAC) — `@RestController` 진입 시점.

---

## 6. 환불 실패 시 처리

`refund.failed` webhook 도착 시:

1. Ticket 을 직전 상태로 복구 (PAID 또는 CANCELED) — 어떤 상태에서 환불 시도했는지 별도 컬럼/이벤트 로그로 기억.
2. 운영자에게 즉시 알림 (NotificationService 또는 Slack 같은 외부 채널).
3. participant 에게도 알림 — "환불 처리에 문제가 발생했어요. 운영팀이 연락드릴게요." 정도의 안전한 메시지.
4. 재시도 정책:
   - 일시적 PG 장애 (5xx) → 자동 재시도 3회, exponential backoff.
   - 결제수단 만료 등 클라이언트 사유 → 자동 재시도 금지, 운영 큐로.
5. **financial reconciliation 보고서** — 일별 PG 정산 데이터와 `tickets` 테이블의 REFUNDED 카운트가 일치하는지 batch 확인.

### 코드 변경 최소 범위 (이번 PR 에서)

이번 PR(38) 은 본구현을 하지 않으므로 **TicketStatus enum 의 KDoc 만 보강** 하여 향후 작업자가 정책을 빠르게 이해할 수 있게 한다. 새 컬럼/enum 추가는 PG 도입 PR 에서.

---

## 7. 정리

| 항목 | 현재 (MVP) | PG 도입 후 |
|---|---|---|
| 결제 | 무료 발급만 | PG webhook 로 PAID 전환 |
| 발급 시점 | APPROVED 즉시 | 결제 완료 시 |
| 취소 (시작 전) | 즉시 가능 | PG 환불 webhook 까지 in-flight |
| 취소 (USED 후) | 불가 | 불가 (불변) |
| 환불 | 호출처 없음 (`refund()` 만 정의) | PG webhook → `Ticket.refund()` |
| 멱등성 | 불필요 | `paymentTransactionId` unique |
| 운영 대응 | — | 환불 실패 큐 + 정산 batch |

이 정책에 변경이 생기면 본 문서를 먼저 갱신하고, 코드를 따라간다.

---

## 8. PR39 — 실제 도입된 모델 / API (외부 PG 미연동)

PR39 부터 위 정책을 바탕으로 도메인 뼈대를 도입했다. 외부 PG SDK 호출과 결제 UI 분기는 아직 들어가지 않았고, 본 단계는 상태 모델 + API 경계 + webhook 멱등성까지다.

### 8.1 새 엔티티 `PaymentAttempt`

```
payment_attempts
├─ id                     (PK)
├─ event_id               (FK Event)
├─ buyer_id               (FK User)
├─ ticket_id              (FK Ticket, nullable — webhook PAID 후 채움)
├─ idempotency_key        (VARCHAR(64) UNIQUE NOT NULL — PG 의 orderId 로 사용)
├─ amount                 (BIGINT — prepare 시점 event.participationFee 스냅샷)
├─ status                 (ENUM READY / PAID / FAILED / CANCELED)
├─ provider               (ENUM NONE / TOSS / PORTONE — webhook 도착 시 갱신)
├─ provider_payment_key   (VARCHAR(128), nullable — PG 가 부여한 결제 키)
├─ created_at / updated_at
```

전이:

```
prepare API   ┌────────┐  webhook PAID 멱등 처리 + Ticket(PAID) 발급
─────────────▶│ READY  │ ─────────────────────────────────────────────▶ PAID
              └───┬────┘
       webhook   │              webhook CANCELED / 사용자 취소
       FAILED    ▼              ▼
              ┌────────┐    ┌──────────┐
              │ FAILED │    │ CANCELED │
              └────────┘    └──────────┘
```

### 8.2 TicketStatus 변경 — 추가하지 않음

`PENDING_PAYMENT` 같은 enum 값을 검토했으나 **추가하지 않기로 결정**했다. 근거:
- Ticket row 는 webhook PAID 가 도착한 시점에만 생성한다 (§5 정책과 일치).
- 결제 진행 중인 상태는 `PaymentAttempt(READY)` 가 단독으로 보유한다. Ticket 과 정보 중복이 없다.
- 결과적으로 `TicketStatus` 는 발급 완료 이후 상태 (`PAID/USED/CANCELED/REFUNDED`) 만 다룬다 — 의미가 깔끔.

### 8.3 새 API

| 메서드/경로 | 인증 | 책임 |
|---|---|---|
| `POST /api/v1/events/{eventId}/payments/prepare` | 인증 사용자 | PaymentAttempt(READY) 생성 또는 멱등 반환. 응답의 `idempotencyKey` 가 PG 의 orderId 가 된다. |
| `POST /api/v1/payments/webhook` | **permitAll** (signature 검증은 TODO) | PG 가 결제 결과를 통지. idempotencyKey 로 attempt 를 찾고 PAID/FAILED/CANCELED 분기. 중복 호출 멱등. |

응답 (`PaymentPrepareResponse`): `paymentAttemptId / eventId / amount / orderName / idempotencyKey / status`.

webhook 요청 (`PaymentWebhookRequest`): `idempotencyKey / providerPaymentKey? / amount / status / provider`.

### 8.4 0원 이벤트 정책

`participationFee == 0` 인 이벤트에 prepare 를 호출하면 `FreeEventCannotPreparePaymentException`(400) 으로 거부한다. 무료 이벤트는 기존 흐름(신청 → 기획자 승인 → `TicketService.issueFreeTicket`) 그대로 유지한다.

### 8.5 prepare 거부 조건 (모두 검증됨)

- 이벤트/사용자 미존재 (404)
- 이벤트 CLOSED (`EventClosedException`)
- 이벤트 시작 시각이 현재 ≤ (`EventAlreadyStartedException`)
- 채널 owner 본인 시도 (`OwnerCannotApplyException`)
- 무료 이벤트 (`FreeEventCannotPreparePaymentException`)
- 이미 PAID/USED 티켓 보유 (`AlreadyJoinedException`)
- 정원 가득 (`EventFullException`)
- 같은 (event, buyer) 에 READY PaymentAttempt 가 살아있으면 **새 row 를 만들지 않고 그대로 반환** (멱등).

### 8.6 webhook 멱등 + 금액 검증

- 같은 `idempotencyKey` 가 다시 도착하면 → 이미 PAID/FAILED/CANCELED 인 attempt 는 아무 일 하지 않고 200 응답.
- PAID webhook 의 `amount` 가 attempt 의 prepare 시점 `amount` 와 다르면 `InvalidPaymentAmountException`(409).
- 모르는 `idempotencyKey` 는 `PaymentAttemptNotFoundException`(404).

### 8.7 본 PR 의 의도적 제외 (PR40+ 후속)

- 실제 Toss/PortOne SDK 호출. 현재 `provider = NONE` 으로 도메인만 검증 가능.
- provider 별 webhook signature / HMAC 검증.
- 유료 이벤트가 EventParticipation row 를 만들지 않는 점 — 신청자 관리 페이지/통계 일관성을 위해 후속 PR 에서 (a) PAID 시 EventParticipation(APPROVED) 동기 생성 또는 (b) Ticket 기반 신청자 목록 재구성 중 결정.
- 정원 race condition — prepare 시점에만 정원 검증한다. READY 다수가 동시에 떠 있고 모두 PAID 가 되면 초과 가능. (1) READY 카운트를 정원 검증에 합치거나 (2) webhook 시점에서도 정원 재확인 + 초과 시 자동 환불 큐로 보낼지 결정 필요.
- 환불 흐름 (`refund.completed`) — Ticket.refund() 호출처 신설은 별도 PR.
- 유료 이벤트의 EventDetailPage CTA 분기 — frontend 는 PR39 단계에선 helper/타입만 추가하고 기존 신청 흐름을 유지한다.

---

## 9. PR40 — Gateway 어댑터 + confirm API + EventParticipation 정합성

PR40 부터 실제 PG sandbox 연결이 가능한 구조를 얹는다. sandbox secret/client key 가 아직 없는 환경에서도 전체 흐름이 검증되도록 `MockPaymentGateway` 가 빈으로 등록된다.

### 9.1 PaymentGateway 어댑터

`PaymentGateway` interface 가 PG 추상화의 단일 진입점:

```kotlin
interface PaymentGateway {
    fun provider(): PaymentProvider
    fun confirm(request: PaymentGatewayConfirmRequest): PaymentGatewayConfirmResult
}
```

빈 선정 (`PaymentConfig`):
- `payment.toss.enabled=true` → `TossPaymentGateway` (Toss `POST /v1/payments/confirm` 직접 호출, `Authorization: Basic Base64(secretKey:)`)
- 그 외 (기본값) → `MockPaymentGateway` (항상 Success 응답, sandbox 키 없는 dev/CI 용)

PortOne 어댑터는 동일 인터페이스 구현으로 추후 추가. PaymentService 는 인터페이스만 의존하므로 빈 교체로 PG 전환이 끝난다.

### 9.2 새 API — confirm

| 메서드/경로 | 인증 | 책임 |
|---|---|---|
| `POST /api/v1/payments/{paymentAttemptId}/confirm` | 인증 사용자 (buyer 본인) | PG SDK 콜백으로 받은 paymentKey 를 백엔드가 PG 에 confirm 호출. 성공 시 Ticket 발급 + PaymentAttempt PAID + 정원 ++ + EventParticipation(APPROVED) 보장. 멱등(이미 PAID 면 gateway 재호출 X). |

요청 (`PaymentConfirmRequest`): `paymentKey / orderId / amount` — orderId 는 prepare 응답의 idempotencyKey 와 동일해야 함.
응답 (`PaymentConfirmResponse`): `paymentAttemptId / status / provider / amount / ticketId / providerPaymentKey / approvedAt`.

### 9.3 confirm 거부 조건

| 케이스 | Exception | HTTP |
|---|---|---|
| buyer 본인이 아님 | `UnauthorizedException` | 403 |
| PaymentAttempt 없음 | `PaymentAttemptNotFoundException` | 404 |
| READY 가 아닌데 PAID 도 아님 (FAILED/CANCELED) | `InvalidPaymentStateException` | 409 |
| orderId 불일치 | `InvalidPaymentOrderIdException` | 409 |
| amount 불일치 | `InvalidPaymentAmountException` | 409 |
| 이벤트 CLOSED / 시작 후 / owner 본인 / 정원 가득 | 기존 exception 재사용 | 409 |
| PG gateway Failure | `PaymentConfirmFailedException(code, detail)` | 502 |

이미 PAID 인 attempt 에 다시 confirm 이 도착하면 → gateway 재호출 없이 기존 응답 멱등 반환 (200 OK).

### 9.4 EventParticipation 정합성

confirm 성공 시 `PaymentService.ensureApprovedParticipation` 가 실행:
- (event, buyer) 의 EventParticipation row 가 있으면 → APPROVED 가 아닌 경우 `approveByPayment()` 로 전환 (system approval; `reviewedBy = null`)
- row 가 없으면 → 새로 만들고 APPROVED 로 저장

이로써 무료 흐름(approve → issueFreeTicket)과 유료 흐름(confirm → issuePaidTicket) 모두 동일한 EventParticipation 모델 위에서 신청자 관리/통계가 동작한다.

`EventParticipation.approveByPayment()` 가 새로 추가됨. `reviewedBy` 가 null 인 row 를 신청자 관리 UI 가 "결제 자동 승인" 으로 표시할지는 후속 UX 결정.

### 9.5 설정

`application.yml` (공통):

```yaml
payment:
  toss:
    enabled: ${TOSS_PAYMENTS_ENABLED:false}
    secret-key: ${TOSS_SECRET_KEY:}
    client-key: ${TOSS_CLIENT_KEY:}
    api-base-url: ${TOSS_API_BASE_URL:https://api.tosspayments.com}
```

운영 배포는 반드시 `TOSS_PAYMENTS_ENABLED=true` + 유효한 `TOSS_SECRET_KEY`. **secret-key 는 절대 리포지토리에 commit 하지 말 것**.

### 9.6 Frontend (sandbox 단계)

`EventDetailPage` 가 `event.participationFee > 0` 이면 CTA 라벨이 `{금액}원 결제하고 참가하기` 로 바뀌고, 클릭 시 `handlePaidApply` 가 호출된다. 현재 단계는 sandbox 키 없이도 동작하도록 다음 두 단계를 자동 실행:

1. `preparePayment(eventId)` → PaymentAttempt(READY) 받음
2. `confirmPayment(paymentAttemptId, { paymentKey: "sandbox-mock-${idempotencyKey}", orderId, amount })` → 백엔드 MockPaymentGateway 가 Success 응답 → Ticket 발급 → `/tickets/{id}` 로 이동

PR41 에서 이 두 단계 사이에 **실제 Toss JS SDK 호출** 이 끼어든다 (clientKey + requestPayment + success/fail URL). frontend 코드에 PR41 전환 메모를 주석으로 남겨 둠.

### 9.7 PR40 의도적 제외 (PR41+ 후속)

- **Webhook signature 검증** — 운영 전 필수. PortOne/Toss 별 HMAC-SHA256 검증 필터. 현재는 webhook 엔드포인트가 idempotencyKey 매핑만 함.
- **환불 흐름** — `refund.completed` webhook + `Ticket.refund()` 호출처 + 운영 도구. PR41 또는 PR42 로 분리.
- **정원 race condition** — confirm 시점 재검증으로 일부 완화됐지만, 둘 이상의 READY 가 동시에 confirm 으로 진입하면 여전히 초과 가능. DB row lock 또는 Redisson 분산락 도입 검토.
- **Webhook 과 confirm 의 충돌** — 클라이언트 confirm 과 PG webhook 이 같은 PaymentAttempt 에 거의 동시에 도착할 때 멱등은 보장되지만, 두 진입점 중 하나만 살리는 방향(웹훅 단일화)도 PR41 에서 평가.
- **PortOne 어댑터** — interface 만 열려 있고 구현체는 후속 PR.

---

## 10. PR41 — Toss webhook signature 검증 + 결제 UI sandbox 연결

PR40 의 confirm 흐름 위에 운영 전 cut-line 인 webhook signature 검증과 실제 Toss SDK 연결을 얹는다. sandbox 키 / signature secret 이 없는 환경에서도 흐름 자체는 회귀 없이 동작한다.

### 10.1 Webhook signature 검증

| 컴포넌트 | 책임 |
|---|---|
| `PaymentWebhookSignatureVerifier` (`@Component`) | rawBody + `Toss-Signature` 헤더 → HMAC-SHA256(secretKey) hex 비교. `MessageDigest.isEqual` 로 timing-safe. |
| `TossPaymentProperties.webhookSignatureRequired` | true 면 검증 강제. 디폴트 false (local/CI). |
| `PaymentController.handleWebhook` | `@RequestBody String rawBody` + `@RequestHeader("Toss-Signature")` 받아서 verifier 통과 후 ObjectMapper 로 파싱. |

`VerificationResult` 5 분류:
- `Bypassed` (required=false) → 통과
- `Valid` (HMAC 일치) → 통과
- `Missing` (헤더 없음) → 401 `InvalidWebhookSignatureException`
- `Invalid` (HMAC 불일치) → 401 `InvalidWebhookSignatureException`
- `Misconfigured` (required=true 인데 secret 비어 있음) → 500 `WebhookMisconfiguredException`

JSON 파싱 실패 → 400 `MalformedWebhookBodyException`.

**TODO (운영 hardening)**: Toss 공식 webhook 문서 기준으로 (a) 헤더 이름(`Toss-Signature` 가정 중), (b) 인코딩(hex vs base64), (c) prefix 형식(`t=...,v1=...` 등) 재확인. `PaymentWebhookSignatureVerifier.SIGNATURE_HEADER` 와 `computeHmacHex` 두 곳만 갱신하면 됨.

### 10.2 새 설정

```yaml
payment:
  toss:
    ...
    webhook-signature-required: ${TOSS_WEBHOOK_SIGNATURE_REQUIRED:false}
```

운영은 반드시 `TOSS_WEBHOOK_SIGNATURE_REQUIRED=true` + 유효한 `TOSS_SECRET_KEY` 조합.

### 10.3 Frontend — Toss SDK 연결

| 구성요소 | 역할 |
|---|---|
| `utils/toss.ts` — `loadTossPayments(clientKey)` | `https://js.tosspayments.com/v1/payment` script 동적 로드, 캐시. `window.TossPayments(clientKey)` 인스턴스 반환. |
| `utils/toss.ts` — `tossClientKey()` | `import.meta.env.VITE_TOSS_CLIENT_KEY` 읽기. 빈 값이면 mock fallback 트리거. |
| `pages/PaymentResultPages.tsx` — `PaymentSuccessPage` | `/payments/success` 라우트. URL query (`paymentAttemptId/paymentKey/orderId/amount`) → confirmPayment → `/tickets/{id}` 이동. |
| `pages/PaymentResultPages.tsx` — `PaymentFailPage` | `/payments/fail` 라우트. URL query (`code/message`) 표시 + 뒤로가기. |
| `pages/EventDetailPage.tsx` — `handlePaidApply` | clientKey 있으면 `tossPayments.requestPayment('카드', ...)` → 결제창 redirect. 없으면 기존 mock confirm fallback. |

### 10.4 새 라우트

| 경로 | 페이지 | 진입 |
|---|---|---|
| `/payments/success` | `PaymentSuccessPage` | Toss `successUrl` redirect — query: paymentAttemptId, paymentKey, orderId, amount |
| `/payments/fail` | `PaymentFailPage` | Toss `failUrl` redirect — query: code, message |

### 10.5 mock fallback 정책

`VITE_TOSS_CLIENT_KEY` 가 비어 있으면 → SDK 호출 단계를 건너뛰고 `sandbox-mock-{idempotencyKey}` paymentKey 로 즉시 confirm. 백엔드의 MockPaymentGateway 가 Success 응답 → ticket 발급. 이 흐름은 sandbox 키 도착 전까지 유지된다.

`VITE_TOSS_CLIENT_KEY` 가 있지만 SDK 로드/호출 실패 → "결제창을 열 수 없습니다" 토스트만 띄우고 PaymentAttempt 는 READY 그대로 (다음 시도에서 같은 row 멱등 재사용).

### 10.6 PR42 의도적 제외

- **환불 흐름** — `refund.completed` webhook + `Ticket.refund()` + 운영 도구 + 환불 정산 batch.
- **Toss 공식 signature format hardening** — 본 PR 의 TODO 그대로. 운영 키 받은 후 문서로 재검증.
- **정원 race condition lock** — DB row lock(`SELECT ... FOR UPDATE`) 또는 Redisson 분산락 도입.
- **Webhook + confirm 충돌 정책** — 두 진입점 동시 도착 시 멱등은 보장되지만, 한 쪽으로 일원화하는 방향 평가.
- **PortOne 어댑터** — interface 만 열려 있고 구현체는 후속 PR.

---

## 11. PR42 — 환불 흐름

전액 환불 1차. Toss 의 `POST /v1/payments/{paymentKey}/cancel` 를 어댑터로 추상화하고
사용자/owner/ADMIN 환불 API 와 `refund.completed` webhook 분기를 함께 도입했다.
부분 환불, 환불 정산 batch, USED 후 강제 환불은 후속 PR.

### 11.1 PaymentGateway 확장

```kotlin
interface PaymentGateway {
    fun provider(): PaymentProvider
    fun confirm(request: PaymentGatewayConfirmRequest): PaymentGatewayConfirmResult
    fun refund(request: PaymentGatewayRefundRequest): PaymentGatewayRefundResult  // NEW
}
```

- `TossPaymentGateway.refund`: `POST {api-base-url}/v1/payments/{paymentKey}/cancel`, body `{cancelReason, cancelAmount}`, 응답의 `cancels[].canceledAt` 추출.
- `MockPaymentGateway.refund`: 항상 Success — sandbox 키 없는 dev/CI 에서도 환불 흐름 검증 가능.

### 11.2 새 API

| 메서드/경로 | 인증 | 책임 |
|---|---|---|
| `POST /api/v1/tickets/{ticketId}/refund` | buyer 본인 / 채널 owner / ADMIN | PG refund 호출 → Ticket PAID → REFUNDED, PaymentAttempt.refundedAt 기록, Event.currentParticipants --, EventParticipation CANCELED. 멱등(이미 REFUNDED → gateway 재호출 없이 기존 정보 반환). |

요청 (`RefundTicketRequest`): `{ reason?: string }` (빈 값은 서버가 `USER_REQUEST` 로 대체, 500자 trim).
응답 (`RefundTicketResponse`): `ticketId / ticketStatus / paymentAttemptId / provider / amount / refundedAt / providerPaymentKey`.

### 11.3 거부 조건

| 케이스 | Exception | HTTP |
|---|---|---|
| actor 가 buyer / owner / ADMIN 아님 (STAFF 포함) | `UnauthorizedException` | 403 |
| Ticket 미존재 | `TicketNotFoundException` | 404 |
| Ticket USED (체크인 완료) | `TicketAlreadyUsedException` | 409 |
| Ticket CANCELED | `PaymentNotRefundableException` | 409 |
| 이미 REFUNDED + attempt.refundedAt 세팅 | 멱등 응답 (no throw) | 200 |
| PaymentAttempt 미존재 / status≠PAID | `PaymentNotRefundableException` | 409 |
| providerPaymentKey 누락 | `PaymentNotRefundableException` | 409 |
| PG gateway 거절 | `RefundFailedException(code, detail)` | 502 |

### 11.4 webhook `refund.completed` 분기

`PaymentService.handleWebhook` 에 `PaymentStatus.REFUNDED` 케이스 추가:
- attempt.status != PAID → skip (운영 알람)
- attempt.refundedAt 이미 있음 → skip (멱등)
- ticket 없음 또는 ticket.status != PAID → skip (USED 등 비정상은 운영 도구로 별도)
- 정상 케이스: `markRefundedInternal` 로 ticket REFUNDED + 정원 -- + EventParticipation CANCELED

`PaymentStatus.REFUNDED` enum 값은 **webhook payload 전용 입력값**으로 추가됨. PaymentAttempt.status 는 PAID 그대로 유지하고 `refundedAt` 으로 환불 여부를 판단한다 — TicketStatus.REFUNDED 가 권위 있는 환불 상태.

### 11.5 PaymentAttempt 컬럼 추가

| 컬럼 | 타입 | 용도 |
|---|---|---|
| `refunded_at` | `LocalDateTime?` | 환불 처리 시각. null 이면 미환불. 멱등 가드의 기준. |
| `refund_reason` | `VARCHAR(500)?` | 운영 로그. `markRefunded` 가 500자 trim. |

`markRefunded(reason, at)` 새 helper. PaymentAttempt.status 는 그대로 PAID — 환불 자체는 Ticket REFUNDED 가 권위 있는 상태이며 PaymentAttempt 는 "결제까지 갔다" 는 사실을 보존.

### 11.6 Frontend

`TicketDetailPage` 의 기존 "환불·취소 (준비 중)" 비활성 버튼이 실제 환불 CTA 로 교체됨:
- 노출 조건: `ticket.ticketStatus === 'PAID'` + `!isStaffViewer`
- 클릭 → confirm dialog + `window.prompt` 사유 → `refundTicket(ticketId, { reason })` 호출
- 응답으로 받은 status 로 즉시 UI 갱신, 토스트로 결과 안내

권한은 백엔드가 최종 판정. UI 는 PAID 상태만 가드하고 owner/ADMIN 케이스에서도 같은 버튼 사용.

### 11.7 PR43+ 의도적 제외

- **부분 환불** — 현재는 전액만 (`cancelAmount = attempt.amount`). 부분 환불 도입 시 Ticket 모델/UI/PG body 모두 확장 필요.
- **USED 후 강제 환불** — 노쇼/행사 취소 보상 등 운영 케이스. 별도 ADMIN 전용 endpoint 와 추적 컬럼 필요.
- **환불 정산 reconciliation batch** — 일별 PG 정산 데이터와 REFUNDED 카운트 일치 검증.
- **환불 실패 큐** — PG `refund.failed` 처리 + 운영자 알림 + 자동 재시도 정책 (Toss 5xx 한정).
- **정원 race condition lock** (PR41 잔재) + **PortOne 어댑터** (PR40 잔재) — 별도 PR.

## 12. 운영 hardening 체크리스트

운영 배포 직전에 결제 설정을 점검할 때 사용. `PaymentHardeningCheck` 가 부팅 시 자동 검증한다.

### 12.1 환경 변수

| Env var | 운영 권장 값 | 의미 |
|---|---|---|
| `TOSS_PAYMENTS_ENABLED` | `true` | `false` 면 `MockPaymentGateway` 가 활성화돼 모든 결제가 "success" 로 통과 — 절대 운영에서 false 금지. |
| `TOSS_SECRET_KEY` | `live_sk_…` (env-only) | Toss secret key. **절대 git 에 커밋 금지** — `.env` / Secrets Manager / k8s Secret 으로만 주입. |
| `TOSS_CLIENT_KEY` | `live_ck_…` (env-only) | 프론트 SDK 가 사용. BE 응답에 실어 넘기는 흐름. |
| `TOSS_API_BASE_URL` | `https://api.tosspayments.com` | sandbox 도 동일 base — secret key 가 sandbox 면 sandbox 호출이 된다. |
| `TOSS_WEBHOOK_SIGNATURE_REQUIRED` | `true` | prod 프로파일 기본값. false 로 내리면 모든 webhook 이 통과하므로 운영에서는 절대 금지. |

`application-prod.yml` 가 `webhook-signature-required` 를 `${TOSS_WEBHOOK_SIGNATURE_REQUIRED:true}` 로 잠가 둠 — env 누락 시에도 ON 상태로 부팅한다.

### 12.2 부팅 시 fail-fast (`PaymentHardeningCheck`)

다음 misconfig 중 하나라도 해당되면 `ApplicationStartedEvent` 시점에 `IllegalStateException` 으로 부팅을 중단한다. 잘못된 결제 설정으로 prod 인스턴스가 트래픽을 받는 상황 차단.

1. `payment.toss.enabled=true` + `secretKey` 가 비어 있음
   → `TossPaymentGateway` 가 PG 호출 시 Authorization 헤더를 만들 수 없어 모든 confirm 실패.
2. `payment.toss.webhook-signature-required=true` + `secretKey` 가 비어 있음
   → `PaymentWebhookSignatureVerifier` 가 매 webhook 마다 `Misconfigured` → 500 응답. PG 의 webhook 재시도가 멈추지 않는다.

로컬/CI 디폴트(`enabled=false`, `required=false`)와 테스트 컨텍스트(secret 명시 세팅)에서는 모두 통과한다.

### 12.3 시크릿 누출 방지

- `.gitignore` 에서 차단:
  - `**/application-local.yml` / `**/application-secret*.yml` / `.env*`
  - `.claude/settings.local.json`
- 테스트 코드에서 사용하는 secret 은 `test-secret-key-do-not-use-in-prod` 와 같이 **실 시크릿과 명확히 구분되는 더미** 만 사용.
- 빌드 산출물 (`build/resources/main/application*.yml`) 은 절대 staging/commit 금지 — Gradle 이 src 의 yml 을 카피하면서 env 가 치환된 결과가 들어갈 수 있다.

### 12.4 webhook 멱등성 회귀

PR42 hardening 에서 추가된 회귀 테스트 (`PaymentServiceTest`):

- `handleWebhook FAILED 가 이미 PAID 인 attempt 에 오면 skip` — confirm 후 늦은 FAILED webhook 으로 PAID 가 뒤집히지 않는다.
- `handleRefundedWebhook attempt 가 PAID 가 아니면 skip` — FAILED/CANCELED 에 잘못 도착한 REFUNDED webhook.
- `handleRefundedWebhook ticket 이 null 이면 skip` — 비정상 상태 cascade 차단.
- `handleRefundedWebhook ticket 이 USED 면 skip` — 체크인 후 webhook 자동 환불 차단.

기존(PR41/PR42-refund) 테스트:
- `handleWebhook 같은 idempotencyKey 로 PAID 두 번 와도 ticket 한 번만 발급`
- `handleWebhook REFUNDED 중복 webhook 은 멱등`
- `confirmPayment 이미 PAID 면 gateway 재호출 없이 멱등 응답`

### 12.5 운영 점검 항목

배포 직후 (smoke):
- `GET /actuator/health` 200 (TODO: 별도 PR 에서 actuator 노출)
- prod 인스턴스 로그에 `[PaymentHardeningCheck] OK` 가 한 번 찍히는지
- 결제 1건 sandbox 흐름으로 가짜 트래픽 통과 확인 (prepare → confirm → webhook PAID → ticket 발급)
