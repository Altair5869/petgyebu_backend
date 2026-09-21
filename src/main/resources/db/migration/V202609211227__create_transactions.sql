-- F-OAVYWT 거래 동기화의 본체 테이블 셋.
-- 설계 근거: docs/09-db-design.md 3.4·3.5·3.6절.

CREATE TABLE transactions (
    id                            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 비정규화다. 집계가 전부 "사용자 + 기간" 기준이라 accounts를 거쳐 조인하면 모든 집계
    -- 쿼리에 조인이 하나씩 붙는다(docs/09-db-design.md 3.4절).
    -- 그 대가로 account_id와 user_id가 어긋날 수 있다. 이것을 DB 제약(복합 FK)으로 막으려면
    -- accounts에 UNIQUE (id, user_id)를 새로 걸어야 하는데 설계 문서에 없는 제약이라
    -- 추가하지 않았다. 문서가 정한 방어선은 "거래 저장은 반드시 계좌 조회를 거친 경로로만
    -- 수행한다"이며, 그 경로는 T-015에서 만든다.
    user_id                       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id                    BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- 대행사(코드에프)가 준 거래 식별자. 중복 수집 방지의 이중 방어에 쓴다.
    codef_transaction_id          VARCHAR(255) NOT NULL,
    transacted_at                 TIMESTAMPTZ  NOT NULL,
    -- 항상 양수다. 수입·지출 방향은 txn_type이 정한다.
    amount                        BIGINT       NOT NULL,
    merchant                      VARCHAR(255),
    txn_type                      VARCHAR(10)  NOT NULL,
    -- 미매칭 거래는 99(UNCLASSIFIED)로 들어온다. 99는 categories 시드의 고정값이다
    -- (V202609210052__create_categories.sql). CASCADE를 걸지 않는 이유는 카테고리가
    -- 사용자 소유 데이터가 아니라 고정 시드이기 때문이다. 기본값 NO ACTION이다.
    category_id                   SMALLINT     NOT NULL DEFAULT 99 REFERENCES categories (id),
    -- 최초 분류 경로. 사용자가 카테고리를 바꿔도 이 값은 갱신하지 않는다(자동 분류 정확도 KPI 모수).
    initial_classification_source VARCHAR(20)  NOT NULL,
    transfer_status               VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    refund_status                 VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    -- 자기참조. 원거래와 환불을 각각 행으로 저장하고 이 열로 잇는다. 순액은 집계 시점에 계산한다.
    -- ON DELETE 절이 설계 문서에 없다. 기본값 NO ACTION 그대로 둔다.
    linked_refund_transaction_id  BIGINT       REFERENCES transactions (id),
    memo                          VARCHAR(255),
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transactions_txn_type CHECK (txn_type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_transactions_initial_classification_source
        CHECK (initial_classification_source IN ('AUTO_MATCHED', 'UNCLASSIFIED')),
    CONSTRAINT ck_transactions_transfer_status CHECK (transfer_status IN (
        'NONE', 'PENDING_CONFIRM', 'AUTO_LINKED', 'USER_CONFIRMED', 'UNLINKED')),
    CONSTRAINT ck_transactions_refund_status
        CHECK (refund_status IN ('NONE', 'ORIGINAL', 'REFUND')),
    -- 같은 계좌에서 같은 대행사 거래 식별자를 두 번 수집하는 것을 막는다.
    CONSTRAINT uq_transactions_account_codef_txn_id UNIQUE (account_id, codef_transaction_id)
);

-- 거래 목록 조회, 기간별 집계. 목록이 최신순이라 DESC로 만든다.
CREATE INDEX ix_transactions_user_transacted_at
    ON transactions (user_id, transacted_at DESC);

-- 카테고리별 집계.
CREATE INDEX ix_transactions_user_transacted_at_category
    ON transactions (user_id, transacted_at, category_id);

-- 계좌 단위 조회, 동기화 시 최근 거래 확인.
CREATE INDEX ix_transactions_account_transacted_at
    ON transactions (account_id, transacted_at);

-- 이체 후보 탐색. 금액 완전 일치 + 10분 이내 조건을 이 인덱스로 좁힌다(T-017).
CREATE INDEX ix_transactions_account_amount_transacted_at
    ON transactions (account_id, amount, transacted_at);

-- 부분 인덱스다. 확인 대기 후보만 모아 보는 화면용이라 나머지 상태는 색인하지 않는다.
-- WHERE 절을 빼면 전체 거래를 색인하는 훨씬 큰 인덱스가 되면서 목적을 잃는다.
CREATE INDEX ix_transactions_transfer_status_pending
    ON transactions (transfer_status)
    WHERE transfer_status = 'PENDING_CONFIRM';

CREATE TABLE transfer_links (
    id                        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    withdrawal_transaction_id BIGINT       NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    deposit_transaction_id    BIGINT       NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    link_status               VARCHAR(20)  NOT NULL,
    -- 판단 근거(금액·시각 차이 등).
    match_reason              VARCHAR(255),
    -- 사용자 확인 시각. 자동 연결만 된 상태에서는 NULL이다.
    confirmed_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfer_links_link_status CHECK (link_status IN ('AUTO', 'USER_CONFIRMED')),
    -- 두 UNIQUE는 각각 독립이다. 복합 UNIQUE가 아니다. 복합으로 묶으면 하나의 출금이 여러
    -- 입금과 엮이는 것이 허용돼 제약의 목적이 무너진다(docs/09-db-design.md 3.5절).
    CONSTRAINT uq_transfer_links_withdrawal UNIQUE (withdrawal_transaction_id),
    CONSTRAINT uq_transfer_links_deposit UNIQUE (deposit_transaction_id)
);

CREATE TABLE sync_attempts (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SET NULL이다. 계좌를 해제해도 동기화 성공률 KPI 모수는 남아야 한다.
    account_id     BIGINT       REFERENCES accounts (id) ON DELETE SET NULL,
    -- 계좌가 지워져도 어느 은행이었는지는 알 수 있게 따로 들고 있는다.
    bank_code      VARCHAR(10)  NOT NULL,
    -- 수집 성공률 95%는 "예정된 동기화" 기준이라 수동 요청과 구분해야 한다.
    trigger_type   VARCHAR(20)  NOT NULL,
    attempted_at   TIMESTAMPTZ  NOT NULL,
    result         VARCHAR(10)  NOT NULL,
    failure_reason VARCHAR(500),
    retry_count    SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_sync_attempts_trigger_type CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_sync_attempts_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);
-- updated_at이 없다. append-only 기록이라 한 번 쓰면 고치지 않는다(docs/09-db-design.md 3.6절).

-- 계좌별 최근 시도 조회. 최신순이라 DESC다.
CREATE INDEX ix_sync_attempts_account_attempted_at
    ON sync_attempts (account_id, attempted_at DESC);

-- 수집 성공률 95% 지표 집계용.
CREATE INDEX ix_sync_attempts_trigger_type_attempted_at
    ON sync_attempts (trigger_type, attempted_at);
