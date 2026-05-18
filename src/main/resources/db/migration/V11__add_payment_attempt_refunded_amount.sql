-- ---------------------------------------------------------------
-- Payment partial refund foundation (PR117)
--
-- 부분 환불 누적 금액을 PaymentAttempt 에 저장한다. 정책:
--  - default 0 (NOT NULL) — 기존 row 도 안전하게 0 으로 채워진다.
--  - amount 와 동일하게 BIGINT (원화 — 소수점 없음).
--  - 누적 refunded_amount == amount 가 되면 full refund cascade.
--  - 부분 환불은 PaymentAttempt.status = PARTIALLY_REFUNDED 로 표시되고, Ticket.status 도
--    동일 enum 값으로 전환된다 (Kotlin enum 측 추가, DB 는 VARCHAR(20) 컬럼 그대로 사용).
--
-- 본 마이그레이션은 컬럼 1개만 추가한다. PaymentStatus / TicketStatus 컬럼은 이미 VARCHAR(20)
-- 으로 새 enum 값(PARTIALLY_REFUNDED, 19자) 을 수용할 수 있어 schema 변경 불필요.
-- ---------------------------------------------------------------

ALTER TABLE payment_attempts
    ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0;
