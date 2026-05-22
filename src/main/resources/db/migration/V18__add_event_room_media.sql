-- ---------------------------------------------------------------
-- Event room media light (PR152)
--
-- 공지 + 이벤트룸 댓글에 inline 이미지 첨부.
--
-- 정책:
--  - announcement 이미지: 별도 `event_announcement_images` 테이블 (id, announcement_id FK, url,
--    display_order, created_at). 최대 3장 제한은 service / DTO validation.
--  - comment 이미지: comments 테이블에 `images` TEXT NULL 컬럼 추가. JSON 직렬화 (List<String>).
--    별도 테이블을 두지 않는 이유는 (a) 이벤트룸 댓글에서만 노출되고 (b) 첨부 수가 적어 join 비용 대비
--    column 한 개가 단순. MannerFeedback.tags 와 동일 컨버터 패턴.
--  - URL 은 기존 S3Service.upload 가 반환하는 fully qualified url 그대로 저장 (validation 없음).
-- ---------------------------------------------------------------

CREATE TABLE event_announcement_images (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    announcement_id BIGINT NOT NULL,
    url             VARCHAR(500) NOT NULL,
    display_order   TINYINT NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_event_announcement_images_announcement
        FOREIGN KEY (announcement_id) REFERENCES event_announcements(id),
    INDEX idx_event_announcement_images_announcement (announcement_id, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE comments
    ADD COLUMN images TEXT NULL;
