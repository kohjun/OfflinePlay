-- ---------------------------------------------------------------
-- Report 자동 조치 (PR51)
--
-- 같은 (target_type, target_id) 에 PENDING 신고가 임계치 이상 쌓이면 ReportService 가
-- 대상을 "숨김(hide)" 처리한다. 임계치/대상은 docs/deploy-checklist.md (또는 후속 정책 문서)
-- 참고. 본 마이그레이션은 hidden 필드만 추가하고, 임계치/대상 매핑은 코드 (ReportService)
-- 가 갖는다.
--
-- 정책:
--   - "숨김"은 "삭제"와 다른 의미. 작성자/Admin 이 확인할 수는 있고, 일반 사용자 조회에서만
--     제외된다.
--   - 작성자 패널티 / 계정 정지 / IP fingerprint 는 본 PR 범위 밖 — 후속 PR.
-- ---------------------------------------------------------------

ALTER TABLE reviews
    ADD COLUMN hidden_at DATETIME(6) NULL,
    ADD COLUMN hidden_reason VARCHAR(255) NULL;

ALTER TABLE comments
    ADD COLUMN hidden_at DATETIME(6) NULL,
    ADD COLUMN hidden_reason VARCHAR(255) NULL;

ALTER TABLE posts
    ADD COLUMN hidden_at DATETIME(6) NULL,
    ADD COLUMN hidden_reason VARCHAR(255) NULL;

ALTER TABLE events
    ADD COLUMN hidden_at DATETIME(6) NULL,
    ADD COLUMN hidden_reason VARCHAR(255) NULL;

ALTER TABLE channels
    ADD COLUMN hidden_at DATETIME(6) NULL,
    ADD COLUMN hidden_reason VARCHAR(255) NULL;
