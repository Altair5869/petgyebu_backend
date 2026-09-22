-- 비정규화한 user_id가 부모의 소유자와 어긋나는 것을 DB가 막는다.
--
-- 세 테이블이 부모를 통해 이미 알 수 있는 user_id를 따로 들고 있다.
--   transactions.user_id   (부모: accounts)
--   reward_grants.user_id  (부모: budget_periods)
--   push_logs.user_id      (부모: budget_periods)
--
-- 비정규화 자체는 의도다. 거래 목록의 "전체 계좌 통합 최신순"을 인덱스 하나
-- ((user_id, transacted_at DESC))로 처리하려면 이 열이 있어야 한다. 없으면 계좌
-- 수만큼 인덱스를 스캔해 병합해야 하고 페이지네이션이 깊어질수록 나빠진다.
--
-- 문제는 지금까지 어긋남을 코드 규율("거래 저장은 반드시 계좌 조회를 거친 경로로만")
-- 로만 막고 있었다는 것이다. 어긋나도 예외도 경고도 나지 않는다. 사용자 A의 예산
-- 사용률에 B의 거래가 섞여 들어가고 금액이 이상하다는 신고를 받기 전까지 모른다.
-- 실제로 push_logs에서 같은 구조의 틈이 드러난 적이 있다(트러블슈팅 21번).
--
-- 복합 FK로 물리적으로 불가능하게 만든다. 부모에 UNIQUE (id, user_id)를 걸어
-- 복합 FK의 참조 대상을 만들고, 자식이 (부모id, user_id) 쌍을 통째로 참조한다.
--
-- 측정 (PostgreSQL 16, 5만 건 INSERT — 90일치 최초 수집 상당):
--   단일 FK 둘  -> 242.9 ms
--   복합 FK     -> 248.1 ms   (+2.4%)
-- 자식에 추가되는 열은 없다. 두 열 모두 이미 있다.

-- ── 부모: 복합 FK가 참조할 대상 ──────────────────────────
-- PK와 중복이라 보이지만 복합 FK는 참조 대상에 유니크 제약이 있어야 한다.
-- 두 테이블 모두 사용자당 행 수가 적어 인덱스 비용이 작다.
ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_user UNIQUE (id, user_id);

ALTER TABLE budget_periods
    ADD CONSTRAINT uq_budget_periods_id_user UNIQUE (id, user_id);

-- ── transactions ────────────────────────────────────────
-- account_id 단독 FK를 (account_id, user_id) 복합으로 바꾼다.
-- user_id -> users FK는 그대로 둔다. 소유자를 명시적으로 남기고,
-- accounts를 거치지 않는 삭제 경로에서도 정합성이 유지된다.
ALTER TABLE transactions DROP CONSTRAINT transactions_account_id_fkey;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_account_owner
    FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id)
    ON DELETE CASCADE;

-- ── reward_grants ───────────────────────────────────────
ALTER TABLE reward_grants DROP CONSTRAINT reward_grants_budget_period_id_fkey;
ALTER TABLE reward_grants
    ADD CONSTRAINT fk_reward_grants_period_owner
    FOREIGN KEY (budget_period_id, user_id) REFERENCES budget_periods (id, user_id)
    ON DELETE CASCADE;

-- ── push_logs ───────────────────────────────────────────
ALTER TABLE push_logs DROP CONSTRAINT push_logs_budget_period_id_fkey;
ALTER TABLE push_logs
    ADD CONSTRAINT fk_push_logs_period_owner
    FOREIGN KEY (budget_period_id, user_id) REFERENCES budget_periods (id, user_id)
    ON DELETE CASCADE;

-- 자식 쪽 인덱스는 이미 있다. 복합 FK의 CASCADE 삭제는 선행 열만으로도 탄다.
--   transactions       uq_transactions_account_codef_txn_id (account_id, ...)
--   reward_grants      ix_reward_grants_budget_period_id (budget_period_id)
--   push_logs          uq_push_logs_period_threshold (budget_period_id, ...)
