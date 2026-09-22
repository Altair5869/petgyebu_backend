-- FK 자식 컬럼에 빠져 있던 인덱스를 추가한다.
--
-- PostgreSQL은 FK의 참조하는 쪽(자식) 컬럼에 인덱스를 자동으로 만들지 않는다.
-- 부모 행을 지울 때마다 "이 행을 가리키는 자식이 있나"를 확인해야 하는데,
-- 인덱스가 없으면 자식 테이블 전체를 훑는다. 삭제되는 행마다 반복된다.
--
-- 측정 (PostgreSQL 16, 거래 2000건 삭제):
--   배경 5만 건,  인덱스 없음 -> 3,005 ms
--   배경 5만 건,  인덱스 있음 ->     8 ms
--   배경 20만 건, 인덱스 없음 -> 12,096 ms   (배경 4배 -> 시간 4배, 선형)
--
-- 탈퇴는 요구사항상 "즉시 전체 삭제"다(docs/02-requirements-features.md R-XDDPGW).
-- 사용자가 기다리는 화면에서 이 스캔이 돈다.
--
-- 설계 문서(docs/09-db-design.md)의 인덱스 표에 이 넷이 빠져 있었다. 같은 PR에서 문서도 고쳤다.

-- 자기참조 FK. transactions가 가장 큰 테이블이고 계좌 해제·탈퇴로 대량 삭제된다.
-- 위 측정이 이 인덱스에 대한 것이다.
CREATE INDEX ix_transactions_linked_refund ON transactions (linked_refund_transaction_id);

-- 탈퇴 시 users -> sync_attempts CASCADE에서 스캔된다.
-- 기존 인덱스는 (account_id, attempted_at)과 (trigger_type, attempted_at)이라 user_id를 덮지 않는다.
CREATE INDEX ix_sync_attempts_user_id ON sync_attempts (user_id);

-- 탈퇴 시 users -> push_logs CASCADE에서 스캔된다.
-- uq_push_logs_period_threshold는 budget_period_id가 선행이라 user_id를 덮지 않는다.
CREATE INDEX ix_push_logs_user_id ON push_logs (user_id);

-- 예산 기간 삭제마다 스캔된다. 탈퇴 시 기간 수만큼 반복된다.
-- uq_reward_grants_user_period_condition은 user_id가 선행이라 budget_period_id를 덮지 않는다.
CREATE INDEX ix_reward_grants_budget_period_id ON reward_grants (budget_period_id);

-- 넣지 않은 FK 셋 (참조 테이블이라 부모 삭제가 일어나지 않는다):
--   transactions.category_id, merchant_keyword_rules.category_id -> categories (고정 시드 10개)
--   user_items.shop_item_id -> shop_items (is_active=false로 판매 중단만 하고 삭제하지 않는다)
-- 인덱스는 쓰기 비용이 있으므로 필요 없는 것은 넣지 않는다.
