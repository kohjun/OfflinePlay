-- ---------------------------------------------------------------
-- ContENIDO baseline schema (PR49)
--
-- ddl-auto=create 로 자라온 운영 후보 스키마를 Flyway baseline 으로 freeze.
-- 이후 모든 DDL 변경은 V2__*.sql, V3__*.sql 등으로 누적한다.
--
-- 대상 DB: MySQL 8.x (utf8mb4)
-- 검증: 운영 첫 배포 시 spring.jpa.hibernate.ddl-auto=validate 로 시작해서
--       Hibernate metadata 와 컬럼/제약 차이가 없는지 확인. 차이가 있으면
--       V2 마이그레이션으로 보정 (production 데이터 보존을 위해 V1 은 절대
--       건드리지 않는다).
--
-- 신규 schema-level 제약 (PR48 service guard 의 schema 승격):
--  - reports(reporter_id, target_type, target_id) UNIQUE — 중복 신고 차단.
-- ---------------------------------------------------------------

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 1;

-- ----- users -----
CREATE TABLE users (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    nickname      VARCHAR(50) NOT NULL,
    phone_number  VARCHAR(20) NOT NULL,
    role          VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    deleted_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- channels -----
CREATE TABLE channels (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    owner_id          BIGINT NOT NULL,
    name              VARCHAR(100) NOT NULL,
    description       TEXT NOT NULL,
    category          VARCHAR(30) NOT NULL,
    thumbnail_url     VARCHAR(255) NULL,
    subscriber_count  BIGINT NOT NULL,
    is_active         BIT(1) NOT NULL,
    version           BIGINT NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_channels_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- channel_subscriptions -----
CREATE TABLE channel_subscriptions (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    subscriber_id  BIGINT NOT NULL,
    channel_id     BIGINT NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_channel_subscriptions_user_channel UNIQUE (subscriber_id, channel_id),
    CONSTRAINT fk_channel_subscriptions_subscriber FOREIGN KEY (subscriber_id) REFERENCES users(id),
    CONSTRAINT fk_channel_subscriptions_channel FOREIGN KEY (channel_id) REFERENCES channels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- channel_members -----
CREATE TABLE channel_members (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    channel_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(20) NOT NULL,
    joined_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_channel_members_channel_user UNIQUE (channel_id, user_id),
    CONSTRAINT fk_channel_members_channel FOREIGN KEY (channel_id) REFERENCES channels(id),
    CONSTRAINT fk_channel_members_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- events -----
CREATE TABLE events (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    channel_id            BIGINT NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    description           TEXT NOT NULL,
    location              VARCHAR(255) NOT NULL,
    main_image_url        VARCHAR(255) NOT NULL,
    start_at              DATETIME(6) NOT NULL,
    end_at                DATETIME(6) NOT NULL,
    max_participants      INT NOT NULL,
    participation_fee     BIGINT NOT NULL,
    refund_policy         TEXT NOT NULL,
    detail_content        TEXT NOT NULL,
    current_participants  INT NOT NULL,
    like_count            BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL,
    content_type          VARCHAR(20) NULL,
    version               BIGINT NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_events_channel FOREIGN KEY (channel_id) REFERENCES channels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- event_participations -----
CREATE TABLE event_participations (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    event_id        BIGINT NOT NULL,
    participant_id  BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    joined_at       DATETIME(6) NOT NULL,
    reviewed_at     DATETIME(6) NULL,
    reviewed_by     BIGINT NULL,
    reject_reason   VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_participations_event_participant UNIQUE (event_id, participant_id),
    CONSTRAINT fk_event_participations_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_event_participations_participant FOREIGN KEY (participant_id) REFERENCES users(id),
    CONSTRAINT fk_event_participations_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- contents -----
CREATE TABLE contents (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    title          VARCHAR(255) NOT NULL,
    description    TEXT NOT NULL,
    thumbnail_url  VARCHAR(255) NULL,
    creator_id     BIGINT NOT NULL,
    status         VARCHAR(20) NOT NULL,
    view_count     BIGINT NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_contents_creator FOREIGN KEY (creator_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- posts -----
CREATE TABLE posts (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    channel_id     BIGINT NOT NULL,
    author_id      BIGINT NOT NULL,
    title          VARCHAR(255) NOT NULL,
    content        TEXT NOT NULL,
    thumbnail_url  VARCHAR(255) NULL,
    view_count     BIGINT NOT NULL,
    like_count     BIGINT NOT NULL,
    status         VARCHAR(20) NOT NULL,
    version        BIGINT NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_posts_channel FOREIGN KEY (channel_id) REFERENCES channels(id),
    CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- comments -----
CREATE TABLE comments (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    author_id          BIGINT NOT NULL,
    target_type        VARCHAR(20) NOT NULL,
    target_id          BIGINT NOT NULL,
    content            VARCHAR(500) NOT NULL,
    parent_comment_id  BIGINT NULL,
    like_count         BIGINT NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    deleted_at         DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_comment_id) REFERENCES comments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- likes -----
CREATE TABLE likes (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    user_id      BIGINT NOT NULL,
    target_type  VARCHAR(20) NOT NULL,
    target_id    BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_likes_user_target UNIQUE (user_id, target_type, target_id),
    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- tickets -----
CREATE TABLE tickets (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    event_id      BIGINT NOT NULL,
    buyer_id      BIGINT NOT NULL,
    price         BIGINT NOT NULL,
    status        VARCHAR(20) NOT NULL,
    purchased_at  DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    used_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tickets_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_tickets_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
    INDEX idx_tickets_event (event_id),
    INDEX idx_tickets_buyer (buyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- payment_attempts -----
CREATE TABLE payment_attempts (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    event_id              BIGINT NOT NULL,
    buyer_id              BIGINT NOT NULL,
    idempotency_key       VARCHAR(64) NOT NULL,
    amount                BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL,
    provider              VARCHAR(20) NOT NULL,
    ticket_id             BIGINT NULL,
    provider_payment_key  VARCHAR(128) NULL,
    refunded_at           DATETIME(6) NULL,
    refund_reason         VARCHAR(500) NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_attempts_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_attempts_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_payment_attempts_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
    CONSTRAINT fk_payment_attempts_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    INDEX idx_payment_attempts_event (event_id),
    INDEX idx_payment_attempts_buyer (buyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- reviews -----
CREATE TABLE reviews (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    event_id    BIGINT NOT NULL,
    author_id   BIGINT NOT NULL,
    rating      INT NOT NULL,
    content     TEXT NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reviews_event_author UNIQUE (event_id, author_id),
    CONSTRAINT fk_reviews_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_reviews_author FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- reports -----
--   PR48 service guard 의 schema 승격: (reporter_id, target_type, target_id) UNIQUE.
--   같은 사용자가 같은 대상을 두 번 신고하지 못한다.
CREATE TABLE reports (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    reporter_id   BIGINT NOT NULL,
    target_type   VARCHAR(20) NOT NULL,
    target_id     BIGINT NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reports_reporter_target UNIQUE (reporter_id, target_type, target_id),
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- notifications -----
CREATE TABLE notifications (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    receiver_id  BIGINT NOT NULL,
    type         VARCHAR(30) NOT NULL,
    title        VARCHAR(255) NOT NULL,
    message      VARCHAR(255) NOT NULL,
    target_type  VARCHAR(20) NOT NULL,
    target_id    BIGINT NOT NULL,
    is_read      BIT(1) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_receiver FOREIGN KEY (receiver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----- impact_indexes -----
CREATE TABLE impact_indexes (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    planner_id          BIGINT NOT NULL,
    total_score         DOUBLE NOT NULL,
    tier                VARCHAR(20) NOT NULL,
    dim_planning        DOUBLE NOT NULL,
    dim_execution       DOUBLE NOT NULL,
    dim_satisfaction    DOUBLE NOT NULL,
    dim_fandom          DOUBLE NOT NULL,
    dim_buzz            DOUBLE NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_impact_indexes_planner UNIQUE (planner_id),
    CONSTRAINT fk_impact_indexes_planner FOREIGN KEY (planner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
