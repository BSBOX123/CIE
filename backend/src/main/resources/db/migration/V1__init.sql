-- 먹어도 돼? 초기 스키마 (SPEC 4.2) — MySQL 8.0
--
-- 문자셋은 DB 차원에서 utf8mb4 로 잡는다(docker-compose / RDS 파라미터그룹).
-- utf8 로는 한글 일부와 이모지가 깨진다.
--
-- 시각 컬럼은 DATETIME(6) 이며 UTC 로 저장한다. MySQL 에는 PostgreSQL 의
-- TIMESTAMPTZ 같은 타임존 인식 타입이 없어, 애플리케이션이 UTC 로 변환해
-- 넣는다(application.yml 의 hibernate.jdbc.time_zone).
--
-- 어휘 컬럼(질환/주의성분/알레르기)은 DB enum 대신 VARCHAR + CHECK 로 둔다.
-- 값이 SPEC 2장의 한글 문자열과 1:1로 일치해야 하고, 항목 추가가 잦을 수
-- 있어 마이그레이션 비용을 낮춘다.

-- ── 사용자 ─────────────────────────────────────────────────────────
CREATE TABLE app_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_id        VARCHAR(50)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE user_profile (
    user_id             BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    name                VARCHAR(50) NOT NULL,
    gender              VARCHAR(20) CHECK (gender IN ('여성','남성','밝히지 않음')),
    birth_year          SMALLINT CHECK (birth_year BETWEEN 1900 AND 2100),
    blood_type          VARCHAR(4) CHECK (blood_type IN ('A형','B형','O형','AB형')),
    chewing_difficulty  BOOLEAN NOT NULL DEFAULT FALSE,
    med_note            TEXT,
    -- 주문요청카드는 매장에 보여주는 물건이다. 복용약은 민감정보이므로
    -- 사용자가 명시적으로 켠 경우에만 카드에 인쇄한다 (SPEC 11.4).
    show_meds_on_card   BOOLEAN NOT NULL DEFAULT FALSE,
    font_scale_idx      SMALLINT NOT NULL DEFAULT 1 CHECK (font_scale_idx BETWEEN 0 AND 4),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- 'value'는 H2 등에서 예약어라 컬럼명으로 쓰지 않는다.
-- 아래 code 컬럼들은 건강정보라 암호화해 저장한다(SPEC 11.4). 암호문이
-- 평문보다 길어 컬럼을 넉넉히 잡는다.
CREATE TABLE user_disease (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code    VARCHAR(200) NOT NULL,
    PRIMARY KEY (user_id, code)
);

CREATE TABLE user_care (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code    VARCHAR(200) NOT NULL,
    -- 질환에서 자동 파생된 항목인지. 화면에 ' · 자동' 표시에 쓴다 (SPEC 2.3).
    is_auto BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, code)
);

CREATE TABLE user_allergy (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code    VARCHAR(200) NOT NULL,
    PRIMARY KEY (user_id, code)
);

CREATE TABLE user_medication (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code    VARCHAR(200) NOT NULL,
    PRIMARY KEY (user_id, code)
);

CREATE TABLE user_request (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    phrase     VARCHAR(120) NOT NULL,
    is_custom  BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order SMALLINT NOT NULL DEFAULT 0,
    UNIQUE (user_id, phrase)
);

-- ── 음식 정규화 (SPEC 4.2 dish) ─────────────────────────────────────
-- 전국 13,495개 식당의 메뉴 인스턴스는 약 5만 건이지만 고유 음식명은 훨씬
-- 적다. dish 단위로 1회만 태깅하면 LLM 비용이 줄고, 같은 음식은 어디서나
-- 같은 판정이 나온다.
CREATE TABLE dish (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    normalized_name    VARCHAR(100) NOT NULL UNIQUE,
    aliases            JSON,
    nutrition_food_cd  VARCHAR(30),
    food_category      VARCHAR(50),   -- 식약처 FOOD_CAT1_NM. 1회 섭취량 결정용
    nutrition          JSON,         -- 100g 기준 영양소. NULL은 미조회
    portion_g_override NUMERIC(6,1),  -- 식품군 기본값을 덮어쓸 때 (예: 물회)
    tagged_at          DATETIME(6),
    model_id           VARCHAR(50),
    needs_review       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE dish_tag (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    dish_id    BIGINT NOT NULL REFERENCES dish(id) ON DELETE CASCADE,
    tag_type   VARCHAR(10) NOT NULL CHECK (tag_type IN ('CARE','ALLERGEN')),
    tag_value  VARCHAR(30) NOT NULL,
    source     VARCHAR(20) NOT NULL
               CHECK (source IN ('ADMIN_VERIFIED','NUTRITION_DB','COMMUNITY','LLM')),
    confidence NUMERIC(3,2) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    evidence   JSON,
    model_id   VARCHAR(50),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (dish_id, tag_type, tag_value)
);
CREATE INDEX idx_dish_tag_lookup ON dish_tag (dish_id, tag_type);

-- ── 식당 · 메뉴 ────────────────────────────────────────────────────
CREATE TABLE restaurant (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    content_id          VARCHAR(20) NOT NULL UNIQUE,  -- KorService2 contentid
    name                VARCHAR(200) NOT NULL,
    area                VARCHAR(200),
    addr1               VARCHAR(300),
    addr2               VARCHAR(200),
    area_code           SMALLINT,
    sigungu_code        SMALLINT,
    lat                 NUMERIC(10,7),   -- mapy
    lng                 NUMERIC(10,7),   -- mapx
    tel                 VARCHAR(50),
    open_time           VARCHAR(200),
    rest_date           VARCHAR(200),
    parking             VARCHAR(100),
    packing             VARCHAR(100),
    reservation         VARCHAR(100),
    first_image         VARCHAR(500),
    raw_menu_text       TEXT,            -- firstmenu + treatmenu 원문 보존
    source_modified_at  VARCHAR(20),     -- modifiedtime. 증분 인제스트 판단용
    ingested_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
-- 위치 기반 조회가 주 경로다 (SPEC 1.1.1 GPS)
CREATE INDEX idx_restaurant_location ON restaurant (lat, lng);
CREATE INDEX idx_restaurant_region ON restaurant (area_code, sigungu_code);

CREATE TABLE restaurant_flag (
    restaurant_id BIGINT NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    flag          VARCHAR(30) NOT NULL,
    -- 공공데이터로 채울 수 없어 후기/관리자에서 온다 (SPEC 6.5)
    source        VARCHAR(20) NOT NULL CHECK (source IN ('PUBLIC_API','COMMUNITY','ADMIN')),
    PRIMARY KEY (restaurant_id, flag)
);

CREATE TABLE menu (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id     BIGINT NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    dish_id           BIGINT REFERENCES dish(id),
    raw_name          VARCHAR(200) NOT NULL,
    -- 어떤 공공 API에도 메뉴 가격이 없다. 항상 NULL로 시작한다 (SPEC 12.1).
    price             INTEGER,
    is_representative BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (restaurant_id, raw_name)
);
CREATE INDEX idx_menu_dish ON menu (dish_id);

-- 특정 식당만 다른 경우 (예: "짜지 않은 순두부")
CREATE TABLE menu_tag_override (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    menu_id    BIGINT NOT NULL REFERENCES menu(id) ON DELETE CASCADE,
    tag_type   VARCHAR(10) NOT NULL CHECK (tag_type IN ('CARE','ALLERGEN')),
    tag_value  VARCHAR(30) NOT NULL,
    removed    BOOLEAN NOT NULL DEFAULT FALSE,  -- true면 dish 태그를 제거
    note       VARCHAR(300),
    UNIQUE (menu_id, tag_type, tag_value)
);

-- ── 지역 음식 ──────────────────────────────────────────────────────
CREATE TABLE local_food (
    id           VARCHAR(50) PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    dish_id      BIGINT REFERENCES dish(id),
    area_code    SMALLINT,
    sigungu_code SMALLINT,
    region_label VARCHAR(50),
    description  TEXT,
    verified     BOOLEAN NOT NULL DEFAULT FALSE  -- LLM 생성 후 관리자 검수 여부
);
CREATE INDEX idx_local_food_region ON local_food (area_code, sigungu_code);

CREATE TABLE local_food_tip (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_id    VARCHAR(50) NOT NULL REFERENCES local_food(id) ON DELETE CASCADE,
    phrase     VARCHAR(120) NOT NULL,
    sort_order SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE restaurant_local_food (
    restaurant_id BIGINT NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    food_id       VARCHAR(50) NOT NULL REFERENCES local_food(id) ON DELETE CASCADE,
    PRIMARY KEY (restaurant_id, food_id)
);

-- ── 방문 기록 · 후기 ───────────────────────────────────────────────
CREATE TABLE visit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    restaurant_id BIGINT REFERENCES restaurant(id) ON DELETE SET NULL,
    restaurant_name VARCHAR(200) NOT NULL,  -- 식당이 사라져도 기록은 남는다
    visited_on    DATE NOT NULL,
    is_ok         BOOLEAN NOT NULL,
    note          TEXT,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_visit_log_user ON visit_log (user_id, visited_on DESC);

-- 그 시점의 요청·알레르기를 스냅샷으로 보존한다. 프로필이 바뀌어도
-- "그때 무엇을 요청했는지"가 남아야 한다.
CREATE TABLE visit_log_request (
    log_id BIGINT NOT NULL REFERENCES visit_log(id) ON DELETE CASCADE,
    phrase VARCHAR(120) NOT NULL,
    PRIMARY KEY (log_id, phrase)
);

CREATE TABLE visit_log_allergy (
    log_id BIGINT NOT NULL REFERENCES visit_log(id) ON DELETE CASCADE,
    code   VARCHAR(30) NOT NULL,
    PRIMARY KEY (log_id, code)
);

CREATE TABLE review (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
    user_id       BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    is_ok         BOOLEAN NOT NULL,
    note          TEXT,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_review_restaurant ON review (restaurant_id, created_at DESC);

-- 후기 피드백 문구는 restaurant_flag 도출의 근거가 된다 (SPEC 6.5)
CREATE TABLE review_feedback (
    review_id BIGINT NOT NULL REFERENCES review(id) ON DELETE CASCADE,
    phrase    VARCHAR(120) NOT NULL,
    PRIMARY KEY (review_id, phrase)
);

-- ── 주문요청카드 ───────────────────────────────────────────────────
CREATE TABLE order_card (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    restaurant_id BIGINT REFERENCES restaurant(id) ON DELETE SET NULL,
    menu_id       BIGINT REFERENCES menu(id) ON DELETE SET NULL,
    menu_label    VARCHAR(300),
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- 카드에 찍히는 순서가 의미를 가지므로 순번을 둔다.
CREATE TABLE order_card_request (
    card_id    BIGINT NOT NULL REFERENCES order_card(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    phrase     VARCHAR(120) NOT NULL,
    PRIMARY KEY (card_id, sort_order)
);

-- ── 설정 (임계값 조정을 재배치 없이) ────────────────────────────────
-- SPEC 7.3: 임계값·1회 섭취량은 영양 전문가 검토 대상이므로 코드 상수가
-- 아니라 데이터로 둔다.
CREATE TABLE care_threshold (
    care_value        VARCHAR(30) PRIMARY KEY,
    nutrient          VARCHAR(30) NOT NULL,
    limit_per_serving NUMERIC(10,2) NOT NULL,
    unit              VARCHAR(10) NOT NULL,
    rationale         VARCHAR(300),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE portion_size (
    food_category VARCHAR(50) PRIMARY KEY,
    grams         NUMERIC(6,1) NOT NULL,
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
