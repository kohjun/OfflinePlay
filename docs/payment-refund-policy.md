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
