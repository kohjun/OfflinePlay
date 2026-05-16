-- ---------------------------------------------------------------
-- Moderation audit log archive table (PR66)
--
-- PR61~65 까지 적재되는 `moderation_audit_logs` 가 무한히 커지는 것을 막기 위해, 오래된 row 를
-- **삭제하지 않고** 별도 archive 테이블로 이동한다. 본 PR 은 수동 archive 만 — scheduler 는 PR68.
--
-- 정책:
--  - active 의 (id, actor_id, ...) 를 동일 row 로 archive table 로 옮긴다.
--  - 운영자 nickname 은 snapshot 으로 박아 (`actor_nickname_snapshot`) 사용자 이름 변경 후에도
--    archive 시점의 nickname 을 보존.
--  - archive 자체에는 actor FK 만 유지 (사용자 row 변경 영향을 최소화).
--  - `original_id` 는 active 에 있던 PK — 운영자가 원본 추적 시 활용. archive 본인 PK 는 별도.
--  - 같은 original_id 가 두 번 들어가지 않도록 unique key 추가 (재실행 안전망).
--  - read-only 운영 — UPDATE/DELETE 는 application 코드에서 막고 schema 자체는 자유로 둔다.
-- ---------------------------------------------------------------

CREATE TABLE moderation_audit_log_archive (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    original_id                 BIGINT NOT NULL,
    actor_id                    BIGINT NOT NULL,
    actor_nickname_snapshot     VARCHAR(64) NOT NULL,
    action                      VARCHAR(50) NOT NULL,
    target_type                 VARCHAR(20) NULL,
    target_id                   BIGINT NULL,
    before_value                TEXT NULL,
    after_value                 TEXT NULL,
    reason                      VARCHAR(500) NULL,
    original_created_at         DATETIME(6) NOT NULL,
    archived_at                 DATETIME(6) NOT NULL,
    archived_by                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_moderation_audit_log_archive_original UNIQUE (original_id),
    CONSTRAINT fk_moderation_audit_log_archive_actor    FOREIGN KEY (actor_id)    REFERENCES users(id),
    CONSTRAINT fk_moderation_audit_log_archive_archiver FOREIGN KEY (archived_by) REFERENCES users(id),
    INDEX idx_moderation_audit_log_archive_original_created_at (original_created_at),
    INDEX idx_moderation_audit_log_archive_action (action, original_created_at),
    INDEX idx_moderation_audit_log_archive_target (target_type, target_id),
    INDEX idx_moderation_audit_log_archive_archived_at (archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
