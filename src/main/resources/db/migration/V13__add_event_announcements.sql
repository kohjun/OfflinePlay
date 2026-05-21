-- ---------------------------------------------------------------
-- Event announcements (PR141)
--
-- 이벤트 담당자(owner / ADMIN / STAFF) 가 활성 참가자에게 보내는 공지.
--  - title VARCHAR(200) NOT NULL
--  - content TEXT NOT NULL — long-form text. 본 PR 에서는 HTML/markdown 가공 없이 그대로 표시.
--  - 인덱스: event_id (목록 조회) — created_at 정렬은 DB sort.
--
-- 알림은 EVENT_ANNOUNCEMENT NotificationType (코드 측 enum 에 추가) 로 발송된다. 본 마이그레이션은
-- 공지 row 만 다룬다.
-- ---------------------------------------------------------------

CREATE TABLE event_announcements (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    event_id    BIGINT NOT NULL,
    author_id   BIGINT NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_event_announcements_event
        FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_event_announcements_author
        FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_event_announcements_event (event_id),
    INDEX idx_event_announcements_event_created (event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
