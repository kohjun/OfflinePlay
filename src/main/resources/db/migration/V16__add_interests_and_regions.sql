-- ---------------------------------------------------------------
-- Interests & Regions taxonomy (PR147)
--
-- 관심사 + 한국 행정구역(시/도, 시/군/구) 카탈로그. user_profiles / events 와의 다대다 join + FK 컬럼.
--
-- 정책:
--  - interests.slug : 영문 stable identifier (예: 'BOARD_GAME'). 운영 중 label 만 바뀔 수 있고 slug 는 불변.
--  - regions.code   : 행정안전부 법정동 코드 prefix. sido = 2자리, sigungu = 5자리.
--                     parent_code 로 계층 표현. level: 1=시도, 2=시군구.
--  - user_interests / event_interests : composite PK (user_id, interest_id) / (event_id, interest_id).
--  - events.region_code / user_profiles.region_code : 신규 nullable FK. 기존 free-form Event.location 와
--    user_profiles.region_sido / region_sigungu 는 점진 backfill 대상 (PR147 이후 cycle 에서 정리).
--
-- 본 PR 은 catalog seed 까지 포함한다 — 운영 환경에서 V16 실행 직후 바로 사용 가능.
-- ---------------------------------------------------------------

-- 1) interests catalog ------------------------------------------------------------
CREATE TABLE interests (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    slug          VARCHAR(50) NOT NULL,
    label         VARCHAR(50) NOT NULL,
    category      VARCHAR(30) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_interests_slug UNIQUE (slug),
    INDEX idx_interests_category_order (category, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO interests (slug, label, category, display_order, created_at) VALUES
    -- 운동 / 액티비티
    ('HIKING',        '등산',           'ACTIVITY', 10, NOW(6)),
    ('RUNNING',       '러닝',           'ACTIVITY', 20, NOW(6)),
    ('CYCLING',       '자전거',         'ACTIVITY', 30, NOW(6)),
    ('GYM',           '헬스/피트니스',  'ACTIVITY', 40, NOW(6)),
    ('YOGA',          '요가/필라테스',  'ACTIVITY', 50, NOW(6)),
    ('GOLF',          '골프',           'ACTIVITY', 60, NOW(6)),
    ('TENNIS',        '테니스/배드민턴', 'ACTIVITY', 70, NOW(6)),
    ('SWIMMING',      '수영/서핑',      'ACTIVITY', 80, NOW(6)),
    -- 문화 / 예술
    ('MOVIE',         '영화',           'CULTURE',  10, NOW(6)),
    ('PERFORMANCE',   '공연/뮤지컬',    'CULTURE',  20, NOW(6)),
    ('EXHIBITION',    '전시/미술관',    'CULTURE',  30, NOW(6)),
    ('MUSIC',         '음악/콘서트',    'CULTURE',  40, NOW(6)),
    ('READING',       '독서',           'CULTURE',  50, NOW(6)),
    ('WRITING',       '글쓰기',         'CULTURE',  60, NOW(6)),
    -- 푸드 / 취미
    ('FOOD',          '맛집 탐방',      'FOOD',     10, NOW(6)),
    ('WINE',          '와인/위스키',    'FOOD',     20, NOW(6)),
    ('COFFEE',        '카페/디저트',    'FOOD',     30, NOW(6)),
    ('BAKING',        '베이킹',         'FOOD',     40, NOW(6)),
    ('COOKING',       '요리',           'FOOD',     50, NOW(6)),
    -- 게임 / 엔터
    ('BOARD_GAME',    '보드게임',       'GAME',     10, NOW(6)),
    ('VIDEO_GAME',    '비디오/온라인',  'GAME',     20, NOW(6)),
    ('ANIMATION',     '애니/만화',      'GAME',     30, NOW(6)),
    -- 자기계발
    ('STUDY',         '스터디',         'GROWTH',   10, NOW(6)),
    ('LANGUAGE',      '외국어',         'GROWTH',   20, NOW(6)),
    ('CODING',        '코딩/IT',        'GROWTH',   30, NOW(6)),
    ('INVESTING',     '투자/재테크',    'GROWTH',   40, NOW(6)),
    -- 여행 / 탐방
    ('DOMESTIC_TRIP', '국내여행',       'TRAVEL',   10, NOW(6)),
    ('OVERSEAS_TRIP', '해외여행',       'TRAVEL',   20, NOW(6)),
    ('CAMPING',       '캠핑/차박',      'TRAVEL',   30, NOW(6)),
    -- 사교 / 네트워킹
    ('SOCIAL',        '친목/네트워킹',  'SOCIAL',   10, NOW(6)),
    ('BUSINESS',      '비즈니스',       'SOCIAL',   20, NOW(6)),
    ('DATING',        '데이팅/소개팅',  'SOCIAL',   30, NOW(6));


-- 2) regions catalog (sido + sigungu) ---------------------------------------------
-- 행정안전부 법정동 코드 prefix 사용:
--   level 1 (sido) = 2자리
--   level 2 (sigungu) = 5자리, parent_code = 해당 sido 2자리
CREATE TABLE regions (
    code        VARCHAR(10) NOT NULL,
    name        VARCHAR(50) NOT NULL,
    parent_code VARCHAR(10) NULL,
    level       TINYINT NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT fk_regions_parent FOREIGN KEY (parent_code) REFERENCES regions(code),
    INDEX idx_regions_parent_level (parent_code, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 시도 17건 (level=1, parent_code=NULL)
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('11', '서울특별시',         NULL, 1),
    ('26', '부산광역시',         NULL, 1),
    ('27', '대구광역시',         NULL, 1),
    ('28', '인천광역시',         NULL, 1),
    ('29', '광주광역시',         NULL, 1),
    ('30', '대전광역시',         NULL, 1),
    ('31', '울산광역시',         NULL, 1),
    ('36', '세종특별자치시',     NULL, 1),
    ('41', '경기도',             NULL, 1),
    ('43', '충청북도',           NULL, 1),
    ('44', '충청남도',           NULL, 1),
    ('46', '전라남도',           NULL, 1),
    ('47', '경상북도',           NULL, 1),
    ('48', '경상남도',           NULL, 1),
    ('50', '제주특별자치도',     NULL, 1),
    ('51', '강원특별자치도',     NULL, 1),
    ('52', '전북특별자치도',     NULL, 1);

-- 시군구 (level=2). 같은 시도 안에서 displayName 기준 정렬 권장 — UI 의 cascade picker 가 그대로 노출한다.
-- 본 seed 는 자치구가 있는 시(수원/성남/안양/안산/고양/용인 등) 의 본청 + 산하 자치구를 모두 포함.

-- 서울특별시 (11) — 25 자치구
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('11110', '종로구',     '11', 2),
    ('11140', '중구',       '11', 2),
    ('11170', '용산구',     '11', 2),
    ('11200', '성동구',     '11', 2),
    ('11215', '광진구',     '11', 2),
    ('11230', '동대문구',   '11', 2),
    ('11260', '중랑구',     '11', 2),
    ('11290', '성북구',     '11', 2),
    ('11305', '강북구',     '11', 2),
    ('11320', '도봉구',     '11', 2),
    ('11350', '노원구',     '11', 2),
    ('11380', '은평구',     '11', 2),
    ('11410', '서대문구',   '11', 2),
    ('11440', '마포구',     '11', 2),
    ('11470', '양천구',     '11', 2),
    ('11500', '강서구',     '11', 2),
    ('11530', '구로구',     '11', 2),
    ('11545', '금천구',     '11', 2),
    ('11560', '영등포구',   '11', 2),
    ('11590', '동작구',     '11', 2),
    ('11620', '관악구',     '11', 2),
    ('11650', '서초구',     '11', 2),
    ('11680', '강남구',     '11', 2),
    ('11710', '송파구',     '11', 2),
    ('11740', '강동구',     '11', 2);

-- 부산광역시 (26) — 15 구 + 1 군
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('26110', '중구',       '26', 2),
    ('26140', '서구',       '26', 2),
    ('26170', '동구',       '26', 2),
    ('26200', '영도구',     '26', 2),
    ('26230', '부산진구',   '26', 2),
    ('26260', '동래구',     '26', 2),
    ('26290', '남구',       '26', 2),
    ('26320', '북구',       '26', 2),
    ('26350', '해운대구',   '26', 2),
    ('26380', '사하구',     '26', 2),
    ('26410', '금정구',     '26', 2),
    ('26440', '강서구',     '26', 2),
    ('26470', '연제구',     '26', 2),
    ('26500', '수영구',     '26', 2),
    ('26530', '사상구',     '26', 2),
    ('26710', '기장군',     '26', 2);

-- 대구광역시 (27) — 7 구 + 1 군
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('27110', '중구',       '27', 2),
    ('27140', '동구',       '27', 2),
    ('27170', '서구',       '27', 2),
    ('27200', '남구',       '27', 2),
    ('27230', '북구',       '27', 2),
    ('27260', '수성구',     '27', 2),
    ('27290', '달서구',     '27', 2),
    ('27710', '달성군',     '27', 2);

-- 인천광역시 (28) — 8 구 + 2 군
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('28110', '중구',       '28', 2),
    ('28140', '동구',       '28', 2),
    ('28177', '미추홀구',   '28', 2),
    ('28185', '연수구',     '28', 2),
    ('28200', '남동구',     '28', 2),
    ('28237', '부평구',     '28', 2),
    ('28245', '계양구',     '28', 2),
    ('28260', '서구',       '28', 2),
    ('28710', '강화군',     '28', 2),
    ('28720', '옹진군',     '28', 2);

-- 광주광역시 (29) — 5 구
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('29110', '동구',       '29', 2),
    ('29140', '서구',       '29', 2),
    ('29155', '남구',       '29', 2),
    ('29170', '북구',       '29', 2),
    ('29200', '광산구',     '29', 2);

-- 대전광역시 (30) — 5 구
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('30110', '동구',       '30', 2),
    ('30140', '중구',       '30', 2),
    ('30170', '서구',       '30', 2),
    ('30200', '유성구',     '30', 2),
    ('30230', '대덕구',     '30', 2);

-- 울산광역시 (31) — 4 구 + 1 군
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('31110', '중구',       '31', 2),
    ('31140', '남구',       '31', 2),
    ('31170', '동구',       '31', 2),
    ('31200', '북구',       '31', 2),
    ('31710', '울주군',     '31', 2);

-- 세종특별자치시 (36) — 단일
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('36110', '세종시',     '36', 2);

-- 경기도 (41) — 31 (자치구 포함 시는 본청 + 산하 자치구)
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('41110', '수원시',          '41', 2),
    ('41130', '성남시',          '41', 2),
    ('41150', '의정부시',        '41', 2),
    ('41170', '안양시',          '41', 2),
    ('41190', '부천시',          '41', 2),
    ('41210', '광명시',          '41', 2),
    ('41220', '평택시',          '41', 2),
    ('41250', '동두천시',        '41', 2),
    ('41270', '안산시',          '41', 2),
    ('41280', '고양시',          '41', 2),
    ('41290', '과천시',          '41', 2),
    ('41310', '구리시',          '41', 2),
    ('41360', '남양주시',        '41', 2),
    ('41370', '오산시',          '41', 2),
    ('41390', '시흥시',          '41', 2),
    ('41410', '군포시',          '41', 2),
    ('41430', '의왕시',          '41', 2),
    ('41450', '하남시',          '41', 2),
    ('41460', '용인시',          '41', 2),
    ('41480', '파주시',          '41', 2),
    ('41500', '이천시',          '41', 2),
    ('41550', '안성시',          '41', 2),
    ('41570', '김포시',          '41', 2),
    ('41590', '화성시',          '41', 2),
    ('41610', '광주시',          '41', 2),
    ('41630', '양주시',          '41', 2),
    ('41650', '포천시',          '41', 2),
    ('41670', '여주시',          '41', 2),
    ('41800', '연천군',          '41', 2),
    ('41820', '가평군',          '41', 2),
    ('41830', '양평군',          '41', 2);

-- 강원특별자치도 (51) — 18
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('51110', '춘천시',     '51', 2),
    ('51130', '원주시',     '51', 2),
    ('51150', '강릉시',     '51', 2),
    ('51170', '동해시',     '51', 2),
    ('51190', '태백시',     '51', 2),
    ('51210', '속초시',     '51', 2),
    ('51230', '삼척시',     '51', 2),
    ('51720', '홍천군',     '51', 2),
    ('51730', '횡성군',     '51', 2),
    ('51750', '영월군',     '51', 2),
    ('51760', '평창군',     '51', 2),
    ('51770', '정선군',     '51', 2),
    ('51780', '철원군',     '51', 2),
    ('51790', '화천군',     '51', 2),
    ('51800', '양구군',     '51', 2),
    ('51810', '인제군',     '51', 2),
    ('51820', '고성군',     '51', 2),
    ('51830', '양양군',     '51', 2);

-- 충청북도 (43) — 11
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('43110', '청주시',     '43', 2),
    ('43130', '충주시',     '43', 2),
    ('43150', '제천시',     '43', 2),
    ('43720', '보은군',     '43', 2),
    ('43730', '옥천군',     '43', 2),
    ('43740', '영동군',     '43', 2),
    ('43745', '증평군',     '43', 2),
    ('43750', '진천군',     '43', 2),
    ('43760', '괴산군',     '43', 2),
    ('43770', '음성군',     '43', 2),
    ('43800', '단양군',     '43', 2);

-- 충청남도 (44) — 15
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('44130', '천안시',     '44', 2),
    ('44150', '공주시',     '44', 2),
    ('44180', '보령시',     '44', 2),
    ('44200', '아산시',     '44', 2),
    ('44210', '서산시',     '44', 2),
    ('44230', '논산시',     '44', 2),
    ('44250', '계룡시',     '44', 2),
    ('44270', '당진시',     '44', 2),
    ('44710', '금산군',     '44', 2),
    ('44760', '부여군',     '44', 2),
    ('44770', '서천군',     '44', 2),
    ('44790', '청양군',     '44', 2),
    ('44800', '홍성군',     '44', 2),
    ('44810', '예산군',     '44', 2),
    ('44825', '태안군',     '44', 2);

-- 전북특별자치도 (52) — 14
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('52110', '전주시',     '52', 2),
    ('52130', '군산시',     '52', 2),
    ('52140', '익산시',     '52', 2),
    ('52180', '정읍시',     '52', 2),
    ('52190', '남원시',     '52', 2),
    ('52210', '김제시',     '52', 2),
    ('52710', '완주군',     '52', 2),
    ('52720', '진안군',     '52', 2),
    ('52730', '무주군',     '52', 2),
    ('52740', '장수군',     '52', 2),
    ('52750', '임실군',     '52', 2),
    ('52770', '순창군',     '52', 2),
    ('52790', '고창군',     '52', 2),
    ('52800', '부안군',     '52', 2);

-- 전라남도 (46) — 22
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('46110', '목포시',     '46', 2),
    ('46130', '여수시',     '46', 2),
    ('46150', '순천시',     '46', 2),
    ('46170', '나주시',     '46', 2),
    ('46230', '광양시',     '46', 2),
    ('46710', '담양군',     '46', 2),
    ('46720', '곡성군',     '46', 2),
    ('46730', '구례군',     '46', 2),
    ('46770', '고흥군',     '46', 2),
    ('46780', '보성군',     '46', 2),
    ('46790', '화순군',     '46', 2),
    ('46800', '장흥군',     '46', 2),
    ('46810', '강진군',     '46', 2),
    ('46820', '해남군',     '46', 2),
    ('46830', '영암군',     '46', 2),
    ('46840', '무안군',     '46', 2),
    ('46860', '함평군',     '46', 2),
    ('46870', '영광군',     '46', 2),
    ('46880', '장성군',     '46', 2),
    ('46890', '완도군',     '46', 2),
    ('46900', '진도군',     '46', 2),
    ('46910', '신안군',     '46', 2);

-- 경상북도 (47) — 22
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('47110', '포항시',     '47', 2),
    ('47130', '경주시',     '47', 2),
    ('47150', '김천시',     '47', 2),
    ('47170', '안동시',     '47', 2),
    ('47190', '구미시',     '47', 2),
    ('47210', '영주시',     '47', 2),
    ('47230', '영천시',     '47', 2),
    ('47250', '상주시',     '47', 2),
    ('47280', '문경시',     '47', 2),
    ('47290', '경산시',     '47', 2),
    ('47720', '군위군',     '47', 2),
    ('47730', '의성군',     '47', 2),
    ('47750', '청송군',     '47', 2),
    ('47760', '영양군',     '47', 2),
    ('47770', '영덕군',     '47', 2),
    ('47820', '청도군',     '47', 2),
    ('47830', '고령군',     '47', 2),
    ('47840', '성주군',     '47', 2),
    ('47850', '칠곡군',     '47', 2),
    ('47900', '예천군',     '47', 2),
    ('47920', '봉화군',     '47', 2),
    ('47930', '울진군',     '47', 2);

-- 경상남도 (48) — 18
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('48121', '창원시',     '48', 2),
    ('48170', '진주시',     '48', 2),
    ('48220', '통영시',     '48', 2),
    ('48240', '사천시',     '48', 2),
    ('48250', '김해시',     '48', 2),
    ('48270', '밀양시',     '48', 2),
    ('48310', '거제시',     '48', 2),
    ('48330', '양산시',     '48', 2),
    ('48720', '의령군',     '48', 2),
    ('48730', '함안군',     '48', 2),
    ('48740', '창녕군',     '48', 2),
    ('48820', '고성군',     '48', 2),
    ('48840', '남해군',     '48', 2),
    ('48850', '하동군',     '48', 2),
    ('48860', '산청군',     '48', 2),
    ('48870', '함양군',     '48', 2),
    ('48880', '거창군',     '48', 2),
    ('48890', '합천군',     '48', 2);

-- 제주특별자치도 (50) — 2
INSERT INTO regions (code, name, parent_code, level) VALUES
    ('50110', '제주시',     '50', 2),
    ('50130', '서귀포시',   '50', 2);


-- 3) user_interests / event_interests (다대다) ---------------------------------
CREATE TABLE user_interests (
    user_id     BIGINT NOT NULL,
    interest_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, interest_id),
    CONSTRAINT fk_user_interests_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_interests_interest
        FOREIGN KEY (interest_id) REFERENCES interests(id),
    INDEX idx_user_interests_interest (interest_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_interests (
    event_id    BIGINT NOT NULL,
    interest_id BIGINT NOT NULL,
    PRIMARY KEY (event_id, interest_id),
    CONSTRAINT fk_event_interests_event
        FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT fk_event_interests_interest
        FOREIGN KEY (interest_id) REFERENCES interests(id),
    INDEX idx_event_interests_interest (interest_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 4) events.region_code / user_profiles.region_code 컬럼 추가 -----------------
ALTER TABLE events
    ADD COLUMN region_code VARCHAR(10) NULL,
    ADD CONSTRAINT fk_events_region FOREIGN KEY (region_code) REFERENCES regions(code),
    ADD INDEX idx_events_region (region_code);

ALTER TABLE user_profiles
    ADD COLUMN region_code VARCHAR(10) NULL,
    ADD CONSTRAINT fk_user_profiles_region FOREIGN KEY (region_code) REFERENCES regions(code),
    ADD INDEX idx_user_profiles_region (region_code);
