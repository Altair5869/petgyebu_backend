-- F-EZZFNU 절약 보상, F-HPWCNJ 상점의 테이블 넷.
-- 설계 근거: docs/09-db-design.md 5.1~5.4절.

-- 사용자당 한 행이라 user_id가 곧 PK다. 별도 id를 두지 않는다(docs/09-db-design.md 5.1절).
-- 지금까지의 모든 테이블과 달리 GENERATED ALWAYS AS IDENTITY가 없다.
CREATE TABLE credit_balances (
    user_id    BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    balance    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 잔액 부족 구매를 DB에서 막는 마지막 방어선이다. 애플리케이션 검사와
    -- SELECT ... FOR UPDATE에 더한 것이며, 그 잠금 로직은 T-043·T-044 몫이다.
    CONSTRAINT ck_credit_balances_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE reward_grants (
    id                            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 판정 대상 기간. 기간이 지워지면 그 기간에 대한 지급 기록도 의미를 잃는다.
    budget_period_id              BIGINT      NOT NULL
                                              REFERENCES budget_periods (id) ON DELETE CASCADE,
    condition_type                VARCHAR(30) NOT NULL,
    -- 조건별 100 또는 200. 두 조건을 모두 충족하면 행이 2개 생겨 합이 300이 된다.
    credit_amount                 BIGINT      NOT NULL,
    -- 판정 근거: 해당 기간 지출 합계.
    period_expense_total          BIGINT      NOT NULL,
    -- 판정 근거: 직전 기간 지출 합계. 첫 기간은 비교 대상이 없어 NULL이다.
    previous_period_expense_total BIGINT,
    granted_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reward_grants_condition_type
        CHECK (condition_type IN ('WITHIN_TARGET', 'SAVED_10_PERCENT')),
    -- 동일 조건 중복 지급 금지. 배치가 재실행돼도 두 번 지급되지 않는다.
    -- condition_type이 키에 들어 있어 조건이 다르면 같은 사용자·같은 기간에도 두 행이 남는다.
    -- 조건별 지급 사유를 보여줘야 해서(F-EZZFNU) 한 행에 합치지 않는다.
    CONSTRAINT uq_reward_grants_user_period_condition
        UNIQUE (user_id, budget_period_id, condition_type)
);

CREATE TABLE shop_items (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 시드 데이터 식별용.
    code          VARCHAR(50)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    -- 방 꾸미기 슬롯 4종. 렌더링 순서는 벽지 → 바닥 → 집 → 캐릭터 → 장난감으로 고정이며
    -- 슬롯 순서가 곧 z-order라 별도 순서 컬럼이 없다(docs/09-db-design.md 5.2 아래 표).
    item_type     VARCHAR(20)  NOT NULL,
    -- Cloud Storage 경로.
    image_url     VARCHAR(500) NOT NULL,
    price_credits BIGINT       NOT NULL,
    description   VARCHAR(255),
    sort_order    SMALLINT     NOT NULL DEFAULT 0,
    -- 판매 중단은 이 값을 false로 내리는 것이며 행을 지우지 않는다. 이미 산 사람의 보유가
    -- 유지돼야 하기 때문이다. user_items.shop_item_id의 FK에 CASCADE가 없는 이유이기도 하다.
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_shop_items_item_type
        CHECK (item_type IN ('WALLPAPER', 'FLOOR', 'HOUSE', 'TOY')),
    -- 명세가 정한 가격대(docs/02-requirements-features.md:390).
    CONSTRAINT ck_shop_items_price_credits CHECK (price_credits BETWEEN 100 AND 500),
    CONSTRAINT uq_shop_items_code UNIQUE (code)
);

-- 시드 행을 넣지 않는다. 백로그의 "슬롯 4종 시드"는 item_type의 CHECK 값 4종을 가리키는 것이고,
-- 실제 아이템의 code·name·image_url·price_credits는 어느 문서에도 없다. image_url이 NOT NULL이라
-- 이미지 에셋 없이는 채울 수도 없다. 실제 시드는 에셋과 함께 T-044에서 정한다
-- (T-013의 merchant_keyword_rules와 같은 판단).

CREATE TABLE user_items (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- ON DELETE 절이 설계 문서에 없다. 기본값 NO ACTION 그대로 둔다. 상점 아이템은
    -- is_active = false로 판매만 중단하고 삭제하지 않으며, 누가 산 아이템이 사라지면 안 된다.
    shop_item_id     BIGINT      NOT NULL REFERENCES shop_items (id),
    -- shop_items에서 복사한 비정규화 컬럼이다. PostgreSQL의 부분 유니크 인덱스는 해당 테이블의
    -- 컬럼만 참조할 수 있어 shop_items를 조인해 슬롯당 1개 규칙을 만들 수 없다.
    -- 행을 만들 때 한 번 복사하고 이후 바꾸지 않는다(엔티티에서 updatable = false).
    item_type        VARCHAR(20) NOT NULL,
    acquisition_type VARCHAR(10) NOT NULL,
    -- 구매 시점 가격 스냅샷. 지급(GRANT)이면 NULL이다.
    price_paid       BIGINT,
    acquired_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_placed        BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_user_items_item_type
        CHECK (item_type IN ('WALLPAPER', 'FLOOR', 'HOUSE', 'TOY')),
    CONSTRAINT ck_user_items_acquisition_type
        CHECK (acquisition_type IN ('PURCHASE', 'GRANT')),
    -- 같은 아이템 중복 구매 방지.
    CONSTRAINT uq_user_items_user_shop_item UNIQUE (user_id, shop_item_id)
);

-- 부분 유니크 인덱스다. 슬롯당 1개 규칙을 DB가 보장한다. 메인 홈은 이 인덱스로 배치된 4개를
-- 한 번에 읽는다.
-- WHERE 절을 빼면 전체 유니크가 되어 "보관함에 같은 종류 아이템 여러 개"가 막힌다.
-- 배치되지 않은 행은 색인 대상이 아니므로 몇 개든 공존한다.
CREATE UNIQUE INDEX uq_user_items_user_item_type_placed
    ON user_items (user_id, item_type)
    WHERE is_placed = TRUE;
