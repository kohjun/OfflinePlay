-- ---------------------------------------------------------------
-- System actor seed (PR69)
--
-- PR68 의 scheduler archive 가 system actor 부재로 audit log 에 남지 않았다. 본 PR 은 자동
-- 작업 전용 user row 를 1개 seed 해 scheduler 등 background job 이 audit 에 같은 형식으로
-- 기록하도록 한다.
--
-- 정책:
--  - email: `system@contenido.local` — UNIQUE 제약으로 단일 row 보장.
--  - password: bcrypt 가 매칭 못 하는 sentinel 문자열 (`$2a$...` 형식이 아님) → 정상적인 로그인
--    경로로는 절대 인증 통과 불가.
--  - role: PARTICIPANT — 실제 권한 흐름 (PreAuthorize hasRole('ADMIN') 등) 에서 ADMIN 으로
--    오인되지 않도록 가장 낮은 권한.
--  - phone_number: placeholder. UNIQUE 제약이 없어 일반 사용자 가입 흐름과 충돌 없음.
--  - deleted_at: NULL — 일반 findById 가 그대로 가져갈 수 있어야 audit 기록 시 lookup 가능.
--
-- 안전판: V9 가 적용되기 전에 이미 같은 email 이 운영자 실수로 들어가 있을 가능성 → `INSERT
-- IGNORE` 로 멱등 처리. 새 row 의 id 는 AUTO_INCREMENT 가 부여하고, SystemActorService 가
-- email 로 lookup 한다 (id 를 hardcode 하지 않는다).
-- ---------------------------------------------------------------

INSERT IGNORE INTO users (
    email, password, nickname, phone_number, role, created_at, updated_at, deleted_at
) VALUES (
    'system@contenido.local',
    '__SYSTEM_ACTOR_NO_LOGIN__',
    'System',
    '00000000000',
    'PARTICIPANT',
    NOW(6),
    NOW(6),
    NULL
);
