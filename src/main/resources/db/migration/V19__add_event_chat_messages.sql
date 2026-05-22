-- ---------------------------------------------------------------
-- Event chat messages (PR160)
--
-- 이벤트별 실시간 채팅. 카카오톡식 양방향 대화 (text only MVP).
--
-- 정책:
--  - 입장 권한: owner / STAFF / ADMIN + APPROVED 참가자 (무료 또는 활성 ticket).
--    참가가 확정된 사용자만 룸이 활성화된다 — 결제 전·취소·환불 사용자는 진입 불가.
--  - is_announcement = TRUE 인 메시지는 운영자(owner/STAFF/ADMIN) 만 보낼 수 있고
--    NotificationService.notify(EVENT_ANNOUNCEMENT) 로 push 알림이 함께 발송된다.
--  - content 는 VARCHAR(500) (PR152 의 Comment.content 와 동일). 이미지/파일은 MVP 범위 밖.
--  - soft delete (deleted_at) 만 — 본인 메시지 삭제는 후속 PR.
--  - 실시간 송수신: 송신은 REST POST, 수신은 SSE 'event-chat' event (PR139 SseEmitterService 재사용).
--
-- 메시지 history 페이징은 (created_at DESC, id DESC) cursor 기반 — controller 가 `before=` 쿼리.
-- ---------------------------------------------------------------

CREATE TABLE event_chat_messages (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    event_id        BIGINT NOT NULL,
    sender_id       BIGINT NOT NULL,
    content         VARCHAR(500) NOT NULL,
    is_announcement BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6) NOT NULL,
    deleted_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_event_chat_messages_event
        FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_event_chat_messages_sender
        FOREIGN KEY (sender_id) REFERENCES users(id),
    INDEX idx_event_chat_messages_event_created (event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
