-- F-FZUVLV 예산 기간 및 목표 설정의 기반 테이블.
-- 설계 근거: docs/09-db-design.md 4.1·4.2절.

CREATE TABLE budget_periods (
    id                     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 탈퇴는 DELETE FROM users 한 번으로 끝난다(docs/09-db-design.md 0장).
    user_id                BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 매월 1일 / 해당 월 말일. 경계는 KST 기준이며 계산은 AppZone.KST가 단일 출처다.
    period_start           DATE        NOT NULL,
    period_end             DATE        NOT NULL,
    -- 현재 목표 금액. 사용률·캐릭터 상태 계산에 쓰고 사용자가 수정하면 바뀐다.
    target_amount          BIGINT      NOT NULL,
    -- 기간 시작 시점 목표. 절약 보상 판정 기준이며 이후 바뀌지 않는다.
    target_amount_snapshot BIGINT      NOT NULL,
    status                 VARCHAR(10) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_budget_periods_target_amount CHECK (target_amount > 0),
    CONSTRAINT ck_budget_periods_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    -- 같은 달에 예산 기간이 둘 생기는 것을 막는다. s9 배치가 중복 실행돼도 안전하다.
    CONSTRAINT uq_budget_periods_user_id_period_start UNIQUE (user_id, period_start)
);

-- 활성 예산 조회용.
CREATE INDEX ix_budget_periods_user_id_status ON budget_periods (user_id, status);

-- s9 배치가 종료된 기간을 찾을 때.
CREATE INDEX ix_budget_periods_status_period_end ON budget_periods (status, period_end);

CREATE TABLE status_thresholds (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    budget_period_id BIGINT       NOT NULL REFERENCES budget_periods (id) ON DELETE CASCADE,
    status_code      VARCHAR(20)  NOT NULL,
    start_rate       NUMERIC(5,2) NOT NULL,
    -- 구간 끝. OVER_BUDGET만 NULL이다(상한 없음).
    end_rate         NUMERIC(5,2),
    sort_order       SMALLINT     NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_status_thresholds_status_code CHECK (
        status_code IN (
            'REST',
            'WAKE',
            'INTEREST',
            'ANXIOUS',
            'STRONG_WARNING',
            'OVER_BUDGET'
        )
    ),
    CONSTRAINT ck_status_thresholds_start_rate CHECK (start_rate >= 0),
    -- 한 기간에 같은 상태가 둘일 수 없다.
    CONSTRAINT uq_status_thresholds_period_status UNIQUE (budget_period_id, status_code)
);

-- 구간 검증(0% 고정, 오름차순, 중복·공백 없음, 6단계 전부 존재)과 오른쪽 닫힘 경계값 판정은
-- SQL 제약으로 표현하기 번거로워 애플리케이션이 맡는다(docs/09-db-design.md 4.2절).
