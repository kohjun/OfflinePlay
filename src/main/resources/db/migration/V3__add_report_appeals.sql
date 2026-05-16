-- ---------------------------------------------------------------
-- Report appeal flow (PR52)
--
-- 자동 숨김(hidden_at IS NOT NULL) 된 대상의 작성자/소유자가 ADMIN 에게 복구 요청을
-- 제기. ADMIN 승인 시 대상 unhide, 거절 시 hidden 유지 + reject_reason 보존.
--
-- 같은 requester 가 같은 (target_type, target_id) 에 PENDING appeal 을 중복 생성하지
-- 못하도록 service-level guard + 부분 unique 효과를 노리는 인덱스를 둔다. MySQL 8 은
-- 부분 인덱스를 직접 지원하지 않으므로 (target_type, target_id, requester_id, status)
-- 복합 인덱스로 빠른 조회 + service 가 status='PENDING' 만 검사.
-- ---------------------------------------------------------------

CREATE TABLE report_appeals (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    target_type     VARCHAR(20) NOT NULL,
    target_id       BIGINT NOT NULL,
    requester_id    BIGINT NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    reviewed_by     BIGINT NULL,
    reviewed_at     DATETIME(6) NULL,
    reject_reason   VARCHAR(500) NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_report_appeals_requester FOREIGN KEY (requester_id) REFERENCES users(id),
    CONSTRAINT fk_report_appeals_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    INDEX idx_report_appeals_target (target_type, target_id, status),
    INDEX idx_report_appeals_requester (requester_id, created_at),
    INDEX idx_report_appeals_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
