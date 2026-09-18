-- F-QJXRMD 계정 생성 및 로그인 / F-ZPNVKT 회원 탈퇴의 기반 테이블.
-- 설계 근거: docs/09-db-design.md 2.1·2.2절.

CREATE TABLE users (
    id                   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider             VARCHAR(10)  NOT NULL,
    provider_user_id     VARCHAR(255) NOT NULL,
    email                VARCHAR(320),
    character_type       VARCHAR(10),
    character_changed_at TIMESTAMPTZ,
    joined_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at        TIMESTAMPTZ,
    CONSTRAINT ck_users_provider CHECK (provider IN ('KAKAO', 'APPLE')),
    CONSTRAINT ck_users_character_type CHECK (character_type IN ('DOG', 'CAT')),
    -- 계정 식별 키. email에는 유니크를 걸지 않는다. 애플의 이메일 가리기와
    -- 카카오의 선택 동의 때문에 null이거나 중복일 수 있다.
    CONSTRAINT uq_users_provider_provider_user_id UNIQUE (provider, provider_user_id)
);

-- 가입 후 24시간·7일 코호트 집계용.
CREATE INDEX ix_users_joined_at ON users (joined_at);

CREATE TABLE user_consents (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 탈퇴는 DELETE FROM users 한 번으로 끝난다(docs/09-db-design.md 0장).
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    consent_type     VARCHAR(30) NOT NULL,
    consent_version  VARCHAR(20) NOT NULL,
    consented_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_user_consents_consent_type CHECK (
        consent_type IN (
            'TERMS_OF_SERVICE',
            'PRIVACY_POLICY',
            'CODEF_THIRD_PARTY',
            'FINANCIAL_DATA_INQUIRY'
        )
    )
);

-- 최신 동의 버전 조회용.
CREATE INDEX ix_user_consents_user_id_type_consented_at
    ON user_consents (user_id, consent_type, consented_at DESC);
