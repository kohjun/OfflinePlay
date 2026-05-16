-- ---------------------------------------------------------------
-- Moderation 자동 hide 임계치 영속화 (PR60)
--
-- PR51 에서 ReportService.AUTO_HIDE_THRESHOLDS 상수로 박혀 있던 임계치를 DB 로 옮긴다.
-- ADMIN 이 운영 지표(PR57) 를 보고 임계치를 운영 중 조정할 수 있게 한다.
--
-- 구조:
--   target_type (PK)  : ReportTargetType enum 과 1:1.
--   threshold_value   : 1..100 범위 (service 단 validation).
--   updated_at        : 마지막 수정 시각.
--
-- 변경 즉시 이후 신고부터 적용. 기존 hidden 상태는 retroactive 재계산하지 않는다.
-- audit log 는 본 PR 범위 밖 — 후속 PR.
-- ---------------------------------------------------------------

CREATE TABLE moderation_threshold_settings (
    target_type      VARCHAR(20) NOT NULL,
    threshold_value  INT NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (target_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- PR51 default seed.
INSERT INTO moderation_threshold_settings (target_type, threshold_value, updated_at) VALUES
    ('REVIEW',  3, NOW(6)),
    ('COMMENT', 3, NOW(6)),
    ('POST',    5, NOW(6)),
    ('EVENT',   5, NOW(6)),
    ('CHANNEL', 7, NOW(6));
