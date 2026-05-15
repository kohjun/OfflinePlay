package com.contenido.domain.payment.entity

/**
 * 결제 시도 상태.
 *
 *  - READY    : prepare API 가 생성한 직후. 사용자가 PG 결제 페이지를 거쳐
 *               webhook 도착 전까지 머무는 상태.
 *  - PAID     : PG 가 `payment.completed` webhook 을 보낸 뒤. 이때 비로소
 *               연결된 [com.contenido.domain.ticket.entity.Ticket] 이 PAID 로 발급된다.
 *               TicketStatus.PAID 와는 다른 의미 — 여기는 "결제 시도가 성공"이고,
 *               TicketStatus.PAID 는 "발급 완료된 티켓".
 *  - FAILED   : 결제 거절/타임아웃 등 PG 가 실패로 통지. 사용자는 prepare 를 다시 해야 한다.
 *  - CANCELED : 사용자가 결제 페이지를 닫거나 운영자가 시도를 무효화. PAID 로 가지 않은
 *               시도에 한해서만 사용. PAID 후 환불은 별도 흐름(TicketStatus.REFUNDED) 에서 다룬다.
 *  - REFUNDED : **webhook payload 전용 입력 값**. PG 가 `refund.completed` 통지로 보내는
 *               상태이며 PaymentAttempt.status 로 저장되지는 않는다 (PAID 유지, refundedAt 만
 *               기록 — Ticket 의 TicketStatus.REFUNDED 가 권위 있는 환불 상태). PR42 부터 도입.
 *
 * 한 (event, buyer) 조합에 대해 READY 가 동시에 여러 개 존재하지 않는다 — prepare 가
 * 멱등(같은 idempotencyKey 또는 같은 user+event READY 재호출은 기존 row 를 반환) 으로 동작한다.
 */
enum class PaymentStatus {
    READY, PAID, FAILED, CANCELED, REFUNDED,
}
