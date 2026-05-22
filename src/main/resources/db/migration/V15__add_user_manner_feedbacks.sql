-- ---------------------------------------------------------------
-- User manner feedbacks (PR146)
--
-- 이벤트 종료 후 host ↔ participant 1:1 매너 평가. Report 와 별도:
--   Report      : 콘텐츠/사용자 위반 신고 (운영 조치 대상)
--   MannerFeedback : 사용자 간 매너 평가 (긍정/부정 누적, 운영 조치 무관)
--
-- 정책:
--  - (reviewer_id, reviewee_id, event_id) UNIQUE — 같은 이벤트에 같은 상대 평가는 1건만.
--  - tags 는 TEXT (JSON 직렬화). MySQL native JSON 대신 TEXT 로 둬 H2 (test) 와 column 타입 일치
--    + converter (MannerTagsConverter) 가 List<String> 양방향 변환. 운영 정의 태그 set 은 frontend
--    가 고정 (FRIENDLY/PUNCTUAL 등).
--  - rating 은 TINYINT 1~5. service 에서 범위 검증.
--  - comment 는 optional, 500자 제한.
--  - 본 PR 은 created_at 만 — 추후 hidden / 신고 기능 도입 시 컬럼 확장.
--
-- 공개는 누적 3건 이상에서만 manner summary 응답이 채워진다 (MannerFeedbackService 정책).
-- ---------------------------------------------------------------

CREATE TABLE user_manner_feedbacks (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    reviewer_id   BIGINT NOT NULL,
    reviewee_id   BIGINT NOT NULL,
    event_id      BIGINT NOT NULL,
    rating        TINYINT NOT NULL,
    tags          TEXT NULL,
    comment       VARCHAR(500) NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_manner_feedbacks_event_pair
        UNIQUE (reviewer_id, reviewee_id, event_id),
    CONSTRAINT fk_user_manner_feedbacks_reviewer
        FOREIGN KEY (reviewer_id) REFERENCES users(id),
    CONSTRAINT fk_user_manner_feedbacks_reviewee
        FOREIGN KEY (reviewee_id) REFERENCES users(id),
    CONSTRAINT fk_user_manner_feedbacks_event
        FOREIGN KEY (event_id) REFERENCES events(id),
    INDEX idx_user_manner_feedbacks_reviewee (reviewee_id),
    INDEX idx_user_manner_feedbacks_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
