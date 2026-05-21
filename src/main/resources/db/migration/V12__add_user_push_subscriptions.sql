-- ---------------------------------------------------------------
-- User push subscriptions (PR139)
--
-- 브라우저 Web Push 구독을 저장한다. 정책:
--  - endpoint 는 PG/벤더 URL 이라 매우 길 수 있어 TEXT 로 저장하고, UNIQUE 키는 endpoint 의
--    SHA-256 hex (64자) 를 따로 둔다. 같은 사용자가 같은 endpoint 를 다시 등록하면 upsert.
--  - p256dh / auth 는 base64url string. Web Push 표준 길이상 255 면 충분.
--  - enabled = FALSE 는 backend 가 410/expired 등 발송 실패 시 disable 처리하는 soft-delete.
--    UI 가 명시적으로 해지하면 row 자체를 DELETE.
--  - last_seen_at 은 service worker 가 최근에 활성화됨을 기록하는 lightweight signal.
--
-- 본 마이그레이션은 발송 흐름을 바꾸지 않는다. PR140 이 NotificationService 에서 본 테이블을
-- 조회해 Web Push 를 발송한다.
-- ---------------------------------------------------------------

CREATE TABLE user_push_subscriptions (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    endpoint        TEXT NOT NULL,
    endpoint_hash   CHAR(64) NOT NULL,
    p256dh          VARCHAR(255) NOT NULL,
    auth            VARCHAR(255) NOT NULL,
    user_agent      VARCHAR(500) NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at    DATETIME(6) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_push_subscriptions_user_endpoint
        UNIQUE (user_id, endpoint_hash),
    CONSTRAINT fk_user_push_subscriptions_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_user_push_subscriptions_user_enabled (user_id, enabled),
    INDEX idx_user_push_subscriptions_endpoint_hash (endpoint_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
