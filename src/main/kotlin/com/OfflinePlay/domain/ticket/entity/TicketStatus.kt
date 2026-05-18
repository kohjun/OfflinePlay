package com.contenido.domain.ticket.entity

/**
 * 참가비 티켓 상태.
 *
 *  - PAID                : 결제 완료, 정상 티켓. MVP 는 무료 발급이라 APPROVED 시점에 바로 PAID.
 *                          PG 도입 후엔 결제 webhook 으로만 이 상태로 진입한다.
 *  - USED                : 현장 스태프/owner/ADMIN 이 체크인 코드로 처리 완료. **불변** — 어떤 주체도
 *                          USED 를 다시 PAID/CANCELED 로 되돌릴 수 없다.
 *  - CANCELED            : participant 가 APPROVED → CANCELED 로 셀프 취소했을 때 연결된 티켓이
 *                          함께 전환. PG 환불은 아직 in-flight 일 수 있다.
 *  - REFUNDED            : 환불 입금 완료. PG 의 `refund.completed` webhook 처리 종착점.
 *                          전액 환불 (또는 누적 부분 환불이 결제 금액에 도달) 시 진입.
 *  - PARTIALLY_REFUNDED  : PR117 — 부분 환불 진행 중. 누적 refundedAmount 가 amount 미만.
 *                          참가 상태(participation) 와 정원(currentParticipants) 은 유지된다.
 *                          누적 환불액이 결제 금액에 도달하면 REFUNDED 로 전이 (full cascade).
 *
 * 정책/전이 다이어그램 상세: docs/payment-refund-policy.md
 */
enum class TicketStatus {
    PAID, USED, REFUNDED, CANCELED, PARTIALLY_REFUNDED,
}
