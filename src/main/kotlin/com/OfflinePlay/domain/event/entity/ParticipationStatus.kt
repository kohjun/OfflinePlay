package com.contenido.domain.event.entity

/**
 * 이벤트 참가 신청 상태.
 *
 *  - PENDING  : 참가자가 신청 완료, 기획자 승인 대기
 *  - APPROVED : 기획자가 승인 — 참가 확정 (Event.currentParticipants 에 포함)
 *  - REJECTED : 기획자가 거절 — rejectReason 에 사유 보관
 *  - CANCELED : 참가자가 본인 신청을 취소. PENDING 에서는 자유, APPROVED 에서는
 *               이벤트 시작 전 + 연결된 티켓이 USED 가 아닐 때만 허용된다.
 *               (USED 이후는 [com.contenido.global.exception.TicketAlreadyUsedException],
 *               시작 후는 [com.contenido.global.exception.EventAlreadyStartedException].)
 *
 * 재신청은 REJECTED/CANCELED 상태인 행을 PENDING 으로 복구하는 방식으로 처리한다
 * (event_id, participant_id 유니크 제약을 유지하기 위함).
 *
 * 정책/전이 다이어그램 상세: docs/payment-refund-policy.md
 */
enum class ParticipationStatus {
    PENDING, APPROVED, REJECTED, CANCELED,
}
