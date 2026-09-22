-- F-VZFPVW 예산 사용률 연동 피드백의 푸시 층.
-- 설계 근거: docs/09-db-design.md 4.3·4.4절.

CREATE TABLE push_device_tokens (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- UNIQUE는 기기를 넘겨받은 경우(같은 토큰이 다른 사용자에게)를 충돌로 감지하기 위한 것이다.
    -- 등록 시 upsert로 소유자를 갱신하며, 그 upsert 로직은 T-034(디바이스 토큰 등록·해제 API) 몫이다.
    fcm_token  VARCHAR(512) NOT NULL,
    -- 웹 푸시를 구현하지 않기로 해서 WEB이 없다(Q9, docs/02-requirements-features.md R-ENPLNB 결정 5).
    platform   VARCHAR(10)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_push_device_tokens_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT uq_push_device_tokens_fcm_token UNIQUE (fcm_token)
);

-- 한 사용자의 모든 기기로 발송할 때 쓴다. 사용자당 여러 행이 정상이라 유니크가 아니다.
CREATE INDEX ix_push_device_tokens_user_id ON push_device_tokens (user_id);

CREATE TABLE push_logs (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 발송 판정 대상 기간. 기간이 지워지면 그 기간의 발송 기록도 의미를 잃는다.
    budget_period_id BIGINT      NOT NULL
                                 REFERENCES budget_periods (id) ON DELETE CASCADE,
    threshold_type   VARCHAR(20) NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL,
    -- 앱에서 알림을 탭하면 기록한다. 발송 직후에는 읽지 않은 상태라 NULL이다.
    -- KPI "예산 초과 경고 확인율"의 분자다(Q11).
    read_at          TIMESTAMPTZ,
    CONSTRAINT ck_push_logs_threshold_type
        CHECK (threshold_type IN ('STRONG_WARNING', 'OVER_BUDGET')),
    -- "기간당 1회, 재발송 없음"을 DB가 보장한다(Q13). Upstash 키가 유실되거나 배치가 중복
    -- 실행돼도 두 번째 발송은 여기서 막힌다. Redis는 빠른 판정용이고 DB가 최종 방어선이다.
    -- 환불이나 목표 상향으로 사용률이 임계값 아래로 내려갔다 다시 진입해도 다시 보내지 않는다.
    --
    -- reward_grants의 유니크는 3열(user_id 포함)이지만 여기는 2열이다. budget_period_id가
    -- 이미 사용자를 함의한다(docs/09-db-design.md 4.4절).
    -- threshold_type이 키에 있어 같은 기간에 90%(STRONG_WARNING)와 100%(OVER_BUDGET)가
    -- 각각 한 번씩 나갈 수 있고, budget_period_id가 있어 다음 달에 같은 경고를 다시 보낼 수 있다.
    CONSTRAINT uq_push_logs_period_threshold UNIQUE (budget_period_id, threshold_type)
);
