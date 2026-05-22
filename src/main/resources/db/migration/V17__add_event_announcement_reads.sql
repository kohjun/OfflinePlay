-- ---------------------------------------------------------------
-- Event announcement reads + pinned (PR151)
--
-- 정책:
--  - event_announcements.pinned_at : nullable. 한 이벤트 안에 동시 pinned 1건만 service 가 보장.
--    timestamp 값 자체보다 IS NOT NULL 여부가 의미 — UI 가 pinned=true row 를 상단 고정.
--  - event_announcement_reads : 참가자별 공지 read receipt. composite PK (announcement_id, user_id).
--    같은 사용자가 같은 공지를 여러 번 POST read 해도 UPSERT 가 멱등 — read_at 만 갱신.
--  - notification.isRead 와 별개 시맨틱: 알림 push 가 꺼져 있어도 announcement 자체는 별도 read 추적.
--
-- 본 마이그레이션은 컬럼 ALTER 1건 + 새 테이블 1건. 데이터 backfill 없음.
-- ---------------------------------------------------------------

ALTER TABLE event_announcements
    ADD COLUMN pinned_at DATETIME(6) NULL,
    ADD INDEX idx_event_announcements_event_pinned (event_id, pinned_at);

CREATE TABLE event_announcement_reads (
    announcement_id BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    read_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (announcement_id, user_id),
    CONSTRAINT fk_event_announcement_reads_announcement
        FOREIGN KEY (announcement_id) REFERENCES event_announcements(id),
    CONSTRAINT fk_event_announcement_reads_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_event_announcement_reads_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
