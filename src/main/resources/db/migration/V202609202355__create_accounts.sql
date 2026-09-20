-- F-TEDWWF 은행 계좌 연결의 기반 테이블.
-- 설계 근거: docs/09-db-design.md 3.1절.

CREATE TABLE accounts (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 탈퇴는 DELETE FROM users 한 번으로 끝난다(docs/09-db-design.md 0장).
    user_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- VARCHAR다. 은행 조직코드는 '004'처럼 앞자리가 0일 수 있어 정수로 저장하면 0이 날아간다.
    bank_code          VARCHAR(10)  NOT NULL,
    -- 탈퇴·계좌 해제 시 코드에프 커넥티드아이디 해지에 필요하다.
    codef_connected_id VARCHAR(255) NOT NULL,
    -- 마스킹된 계좌번호. 원본 계좌번호는 저장하지 않는다.
    masked_account_no  VARCHAR(50)  NOT NULL,
    -- 코드에프가 주면 저장한다. 안 주는 은행이 있어 NULL을 허용한다.
    account_name       VARCHAR(100),
    consent_status     VARCHAR(20)  NOT NULL,
    consent_expires_at TIMESTAMPTZ,
    -- 마지막 성공 동기화 시각. 한 번도 성공하지 않았으면 NULL이다.
    last_synced_at     TIMESTAMPTZ,
    -- 2-way 추가인증 은행의 무인 동기화 실패 대응.
    reauth_required    BOOLEAN      NOT NULL DEFAULT FALSE,
    included_in_budget BOOLEAN      NOT NULL DEFAULT TRUE,
    -- included_in_budget = FALSE일 때만 의미가 있다. 포함 상태에서는 무시하며,
    -- 이 조건은 DB 제약으로 표현하지 않는다(docs/09-db-design.md 3.1절).
    display_mode       VARCHAR(10)  NOT NULL DEFAULT 'BADGE',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_consent_status CHECK (consent_status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_accounts_display_mode CHECK (display_mode IN ('BADGE', 'HIDDEN')),
    -- 같은 계좌를 두 번 연결하는 것을 막는다. 동일 은행의 복수 계좌는 마스킹 번호가 달라 허용된다.
    CONSTRAINT uq_accounts_user_bank_masked_no UNIQUE (user_id, bank_code, masked_account_no)
);

-- 계좌 목록 조회용.
CREATE INDEX ix_accounts_user_id ON accounts (user_id);

-- 스케줄러가 동기화 대상 계좌를 고를 때.
CREATE INDEX ix_accounts_consent_status_last_synced_at ON accounts (consent_status, last_synced_at);
