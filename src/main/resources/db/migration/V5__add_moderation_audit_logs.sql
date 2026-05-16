-- ---------------------------------------------------------------
-- Moderation audit log (PR61)
--
-- ADMIN 의 강한 운영 액션 (수동 hide/unhide, channel ban/unban, appeal 처리,
-- threshold 변경 등) 을 감사 로그로 남긴다. 사용자-facing 동작에는 영향 없음.
--
-- 정책:
--   - append-only — 수정/삭제 API 제공하지 않는다.
--   - 같은 트랜잭션에 기록 — audit 실패 시 원 액션도 rollback (운영 액션 추적성 우선).
--   - actor 는 ADMIN 한정 — users(id) 로 FK.
--   - target 은 nullable — threshold 변경처럼 단일 대상이 없는 액션도 있음.
--   - before/after 는 JSON 문자열 가능. 5~10년 후 운영 데이터 양 보고 archive 정책 후속.
-- ---------------------------------------------------------------

CREATE TABLE moderation_audit_logs (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    actor_id        BIGINT NOT NULL,
    action          VARCHAR(50) NOT NULL,
    target_type     VARCHAR(20) NULL,
    target_id       BIGINT NULL,
    before_value    TEXT NULL,
    after_value     TEXT NULL,
    reason          VARCHAR(500) NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_moderation_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    INDEX idx_moderation_audit_logs_actor (actor_id, created_at),
    INDEX idx_moderation_audit_logs_action (action, created_at),
    INDEX idx_moderation_audit_logs_target (target_type, target_id),
    INDEX idx_moderation_audit_logs_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
