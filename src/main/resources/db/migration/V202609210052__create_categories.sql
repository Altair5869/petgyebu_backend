-- F-OAVYWT 거래 동기화의 카테고리 분류 층.
-- 설계 근거: docs/09-db-design.md 3.2·3.3절.

-- identity를 쓰지 않고 id를 직접 박는 유일한 테이블이다. 값이 고정이고 시드로 관리하므로
-- ID가 환경마다 달라지면 안 된다(docs/09-db-design.md 3.2절).
CREATE TABLE categories (
    id         SMALLINT    PRIMARY KEY,
    code       VARCHAR(30) NOT NULL,
    name       VARCHAR(30) NOT NULL,
    sort_order SMALLINT    NOT NULL,
    CONSTRAINT uq_categories_code UNIQUE (code)
);

-- 고정 10개. 사용자가 추가·삭제할 수 없다(docs/09-db-design.md 3.2절, Q7).
-- 미분류가 99인 것은 나중에 카테고리를 추가할 여지를 남기기 위함이다.
-- transactions.category_id가 DEFAULT 99로 이 값을 참조한다(T-014). 바꾸지 마라.
-- sort_order는 명세에 값이 없어 id와 같은 값을 쓴다. 미분류는 99라 자연히 맨 뒤다.
INSERT INTO categories (id, code, name, sort_order) VALUES
    (1,  'FOOD',              '식비',      1),
    (2,  'CAFE_SNACK',        '카페·간식', 2),
    (3,  'TRANSPORT',         '교통',      3),
    (4,  'SHOPPING',          '쇼핑',      4),
    (5,  'MEDICAL',           '의료·건강', 5),
    (6,  'CULTURE',           '문화·여가', 6),
    (7,  'HOUSING_COMM',      '주거·통신', 7),
    (8,  'FINANCE_INSURANCE', '금융·보험', 8),
    (9,  'SOCIAL_EVENT',      '경조사비',  9),
    (99, 'UNCLASSIFIED',      '미분류',    99);

CREATE TABLE merchant_keyword_rules (
    id          BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- ON DELETE CASCADE를 걸지 않는다. 카테고리는 사용자 소유 데이터가 아니라 고정 시드이고,
    -- 참조 중인 카테고리 삭제는 거부되는 것이 옳다. 기본값 NO ACTION이다.
    category_id SMALLINT NOT NULL REFERENCES categories (id),
    -- 가맹점명 키워드 배열. 배열 포함 검색을 하므로 GIN 인덱스를 건다.
    keywords    JSONB    NOT NULL,
    -- 여러 룰이 걸릴 때 높은 값 우선.
    priority    SMALLINT NOT NULL DEFAULT 0
);

-- 가맹점명 매칭 시 keywords 배열을 뒤진다. B-tree로는 JSONB 포함 연산을 태울 수 없다.
CREATE INDEX ix_merchant_keyword_rules_keywords ON merchant_keyword_rules USING GIN (keywords);

-- 룰 시드는 넣지 않는다. 실제 키워드 목록이 어느 문서에도 확정돼 있지 않다
-- (docs/09-db-design.md 3.3절은 "시드 데이터로 관리"까지만 적었다).
-- 자동 분류를 구현하는 T-019에서 룰을 정하고 별도 마이그레이션으로 넣는다.
