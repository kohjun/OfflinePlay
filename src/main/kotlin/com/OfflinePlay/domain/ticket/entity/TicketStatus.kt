package com.contenido.domain.ticket.entity

/**
 * 참가비 티켓 상태.
 *
 *  - PAID     : 결제 완료, 아직 이벤트가 종료되지 않은 정상 티켓
 *  - USED     : 이벤트 입장 처리가 완료된 티켓
 *  - REFUNDED : 환불 처리된 티켓
 *  - CANCELED : 사용자/기획자가 취소한 티켓 (환불 절차 진행 전 상태 포함)
 */
enum class TicketStatus {
    PAID, USED, REFUNDED, CANCELED,
}
