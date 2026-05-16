-- ---------------------------------------------------------------
-- Audit log retention scheduler settings (PR68)
--
-- PR66/67 의 수동 archive 흐름 위에 옵션 스케줄러를 얹는다. **기본 OFF** — 운영자가 명시적으로
-- 켰을 때만 동작. 토글한 admin 의 id 는 `updated_by` 에 박혀 archive table 의 `archived_by`
-- 자리로 재사용된다 (별도 system actor 모델 도입을 피하기 위함, PR68 spec 일치).
--
-- 단일 row 패턴: id=1 fixed seed. 다중 row 진입 방지는 application 측에서 가드.
-- ---------------------------------------------------------------

CREATE TABLE audit_log_retention_scheduler_settings (
    id           BIGINT NOT NULL,
    enabled      BOOLEAN NOT NULL,
    cron         VARCHAR(64) NOT NULL,
    updated_by   BIGINT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_retention_scheduler_settings_updater
        FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 기본 seed: 스케줄러 OFF, 매일 새벽 03:30 KST. 운영자가 ON 으로 바꿔야 실제 archive 가 동작.
INSERT INTO audit_log_retention_scheduler_settings (id, enabled, cron, updated_by, updated_at)
VALUES (1, FALSE, '0 30 3 * * *', NULL, NOW(6));
