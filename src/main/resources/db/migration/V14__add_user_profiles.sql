-- ---------------------------------------------------------------
-- User profiles (PR144)
--
-- 공개 프로필 + 본인 확장 프로필. 정책:
--  - users 1 : user_profiles 1 (선택적). User 는 인증 hot path 이므로 wide nullable column 추가
--    대신 1:1 분리. 첫 PATCH 시점에 lazy create 한다.
--  - visibility 는 ENUM 대신 VARCHAR(20) 로 운영 안전성 확보 (값 추가 시 migration 불필요).
--      'PUBLIC'  : 모두에게 모든 공개 필드 노출 (default)
--      'MEMBERS' : 로그인 사용자에게만 (현재 cycle 에선 PUBLIC 과 동일하게 다루고 후속 PR 에서
--                  실제 분기 — 컬럼만 미리 확보)
--      'PRIVATE' : 공개 프로필은 nickname / role / joinedAt 만 노출.
--  - region_sido / region_sigungu 는 PR147 의 regions 도입 전 임시 free-form. PR147 이후
--    region_code FK 컬럼이 추가되고 본 컬럼은 deprecate.
--  - interests 다대다 join 은 PR147 의 user_interests 로. 본 PR 에선 컬럼 추가 안 함.
-- ---------------------------------------------------------------

CREATE TABLE user_profiles (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    user_id             BIGINT NOT NULL,
    avatar_url          VARCHAR(500) NULL,
    bio                 VARCHAR(500) NULL,
    region_sido         VARCHAR(50) NULL,
    region_sigungu      VARCHAR(50) NULL,
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
