-- ---------------------------------------------------------------
-- User notification preferences (PR95)
--
-- 사용자가 NotificationType 별로 수신 여부를 설정한다. 정책:
--  - row 가 없는 NotificationType 은 enabled = TRUE 로 간주 (fail-open + 기존 사용자 회귀
--    방지). DB default 도 TRUE.
--  - upsert 는 (user_id, notification_type) UNIQUE 로 멱등.
--  - 알림 발송 호출자는 NotificationPreferenceService.isEnabled 만 체크하면 된다.
--
-- audit 기록은 본 PR 범위 밖 — preference 변경 자체는 moderation_audit_logs 에 남기지 않는다.
-- ---------------------------------------------------------------

CREATE TABLE user_notification_preferences (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    user_id             BIGINT NOT NULL,
    notification_type   VARCHAR(50) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_notification_preferences_user_type
        UNIQUE (user_id, notification_type),
    CONSTRAINT fk_user_notification_preferences_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user_notification_preferences_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
