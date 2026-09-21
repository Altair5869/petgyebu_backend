# T-014 QA 리포트 — `transactions`·`transfer_links`·`sync_attempts` 스키마와 엔티티

- 기능 ID: F-OAVYWT (거래 동기화)
- 대상: `feature/F-OAVYWT-schema` 로컬 커밋 `cbf96d2`, `8726d8b` (푸시 전)
- 판정 요약: **PASS 13 / FIX 3 / REDO 0**
- 검증자가 직접 실행한 것: `./gradlew build --rerun-tasks`(본체), `bash scripts/check-migration-order.sh`, 격리 워크트리에서 변이 **4회 실행 / 변이 12종**(보고 A·G·H 재현 + 검증자 자체 변이 9종)
- 워크트리는 `git worktree remove --force`로 정리했고 본체 `git status`는 클린이다

---

## 1. 마이그레이션 SQL ↔ `docs/09-db-design.md` 3.4 (`transactions`)
- 판정: PASS
- 검증 대상: `V202609211227__create_transactions.sql:4-67` vs `docs/09-db-design.md` 3.4절 표
- 사유: 17개 컬럼을 한 줄씩 대조했다. 컬럼명·타입·NULL·DEFAULT 전부 일치하고 명세에 없는 컬럼·제약·인덱스를 덧붙이지 않았다.
  - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`(SQL:5)
  - `user_id`(SQL:12)·`account_id`(SQL:13) 둘 다 `NOT NULL` + `ON DELETE CASCADE` — 비정규화 중복 FK가 명세 그대로다
  - `codef_transaction_id VARCHAR(255) NOT NULL`(SQL:15), `transacted_at TIMESTAMPTZ NOT NULL`(SQL:16), `amount BIGINT NOT NULL`(SQL:18), `merchant VARCHAR(255)` NULL 허용(SQL:19), `txn_type VARCHAR(10) NOT NULL`(SQL:20)
  - `category_id SMALLINT NOT NULL DEFAULT 99 REFERENCES categories (id)`(SQL:24) — `ON DELETE` 절 없음(= NO ACTION), 명세와 일치. 99는 T-013 시드의 `UNCLASSIFIED`
  - `initial_classification_source VARCHAR(20) NOT NULL`(SQL:26), `transfer_status VARCHAR(20) NOT NULL DEFAULT 'NONE'`(SQL:27), `refund_status VARCHAR(20) NOT NULL DEFAULT 'NONE'`(SQL:28)
  - `linked_refund_transaction_id BIGINT REFERENCES transactions (id)`(SQL:31) — **자기참조, `ON DELETE` 절 없음**. 명세에 없는 절을 추측해 붙이지 않았다
  - `memo VARCHAR(255)`(SQL:32), `created_at`·`updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`(SQL:33-34)
  - CHECK 5종(SQL:35-42): `amount > 0`, `txn_type IN ('INCOME','EXPENSE')`, `initial_classification_source IN ('AUTO_MATCHED','UNCLASSIFIED')`, `transfer_status` **5종 전부**(`NONE`·`PENDING_CONFIRM`·`AUTO_LINKED`·`USER_CONFIRMED`·`UNLINKED`, SQL:39-40), `refund_status IN ('NONE','ORIGINAL','REFUND')`
  - `uq_transactions_account_codef_txn_id UNIQUE (account_id, codef_transaction_id)`(SQL:44)

## 2. 마이그레이션 SQL ↔ `docs/09-db-design.md` 3.5 (`transfer_links`)
- 판정: PASS
- 검증 대상: `V202609211227__create_transactions.sql:69-84` vs 3.5절 표
- 사유: 7개 컬럼 전부 일치. `withdrawal_transaction_id`·`deposit_transaction_id` 모두 `NOT NULL` + `ON DELETE CASCADE`(SQL:71-72), `link_status VARCHAR(20) NOT NULL` + CHECK(SQL:73, 79), `match_reason VARCHAR(255)` NULL, `confirmed_at TIMESTAMPTZ` NULL, `created_at NOT NULL DEFAULT now()`. **UNIQUE 둘이 각각 독립이다**(SQL:82-83) — 복합이 아니다. 명세가 요구한 그대로다. `updated_at`은 명세에 없고 실제로도 없다.

## 3. 마이그레이션 SQL ↔ `docs/09-db-design.md` 3.6 (`sync_attempts`)
- 판정: PASS
- 검증 대상: `V202609211227__create_transactions.sql:86-110` vs 3.6절 표
- 사유: 9개 컬럼 일치. `account_id BIGINT REFERENCES accounts (id) ON DELETE SET NULL`(SQL:90) — NULL 허용 + SET NULL이 명세 그대로다. `bank_code VARCHAR(10) NOT NULL`, `trigger_type VARCHAR(20) NOT NULL`, `attempted_at TIMESTAMPTZ NOT NULL`, `result VARCHAR(10) NOT NULL`, `failure_reason VARCHAR(500)` NULL, `retry_count SMALLINT NOT NULL DEFAULT 0`. CHECK 2종(SQL:99-100). **`updated_at`이 없다**(SQL:102 주석) — append-only 명세 준수.

## 4. 인덱스 7개 대조
- 판정: PASS
- 검증 대상: SQL:44, 48-67, 82-83, 105-110 vs 3.4·3.6절 인덱스 표
- 사유: 명세가 요구한 것이 전부 있고 명세에 없는 것이 없다.

  | 명세 | SQL 라인 | 일치 |
  |---|---|---|
  | `UNIQUE (account_id, codef_transaction_id)` | 44 | ✓ |
  | `(user_id, transacted_at DESC)` | 48-49 | ✓ DESC 포함 |
  | `(user_id, transacted_at, category_id)` | 52-53 | ✓ |
  | `(account_id, transacted_at)` | 56-57 | ✓ |
  | `(account_id, amount, transacted_at)` | 60-61 | ✓ |
  | `(transfer_status) WHERE transfer_status = 'PENDING_CONFIRM'` | 65-67 | ✓ 부분 조건 값까지 |
  | `transfer_links` 양쪽 UNIQUE | 82-83 | ✓ 독립 2개 |
  | `sync_attempts (account_id, attempted_at DESC)` | 105-106 | ✓ DESC 포함 |
  | `sync_attempts (trigger_type, attempted_at)` | 109-110 | ✓ |

## 5. 엔티티 매핑 ↔ 마이그레이션
- 판정: PASS
- 검증 대상: `transaction/domain/Transaction.java:53-153`, `TransferLink.java:37-85`, `sync/domain/SyncAttempt.java:41-100` vs SQL
- 사유:
  - `Transaction`: `user`·`account` 둘 다 `@ManyToOne(LAZY, optional = false)` + `@JoinColumn(nullable = false)`(`:58-64`) ↔ 중복 FK. `amount`가 `long` ↔ `BIGINT`. `category`가 `@ManyToOne` `nullable = false`(`:86-88`) ↔ `SMALLINT NOT NULL`(타입은 `Category.id`가 `Short`라 정합). `initialClassificationSource`에 **`updatable = false`**(`:92`) — 명세의 "사용자 수정 시에도 갱신 금지"를 엔티티 층에서 실제로 막는다. 세터가 없다. `linkedRefundTransaction`이 같은 타입 `@ManyToOne`(`:104-106`) ↔ 자기참조. 생성자(`:128-153`)가 `transferStatus`·`refundStatus`를 `NONE`으로 고정하고 `memo`·`linkedRefundTransaction`을 비워 둔다 ↔ DB DEFAULT와 어긋나지 않는다.
  - `TransferLink`: `@JoinColumn(..., unique = true)` 둘(`:42`, `:46`) ↔ 독립 UNIQUE 2개. `confirmedAt`·`matchReason` NULL 허용.
  - `SyncAttempt`: `account`가 `optional`을 안 주고 `@JoinColumn`에 `nullable` 지정도 없다(`:50-52`) ↔ NULL 허용. **`updatedAt` 필드가 없다**. `retryCount`가 `short` ↔ `SMALLINT`.
  - `ddl-auto: validate`(`application.yaml:9`)가 켜진 채 `@SpringBootTest` 27건이 기동했으므로 실제 테이블과 대조를 통과했다.
  - 리포지토리 3개는 전부 `JpaRepository`만 상속하고 조회 메서드가 없다 — 관례 일치 + 선점 없음.

## 6. 완료 기준 8 — `./gradlew build --rerun-tasks`
- 판정: PASS
- 사유: **검증자가 직접 실행했다.**
  ```
  > Task :test
  > Task :check
  > Task :build

  BUILD SUCCESSFUL in 21s
  7 actionable tasks: 7 executed
  ```
  `build/test-results/test/*.xml`에서 직접 읽은 실행 건수 — 총 72건, 실패·에러·스킵 0.
  ```
  transaction.TransactionSchemaTest   tests="19" skipped="0" failures="0" errors="0"
  sync.SyncAttemptSchemaTest          tests="8"  skipped="0" failures="0" errors="0"
  category.CategorySchemaTest         tests="10" ...   account.AccountSchemaTest  tests="10" ...
  budget.BudgetSchemaTest             tests="10" ...   user.UserSchemaTest        tests="6"  ...
  migration.PostgresMigrationTest     tests="2"  ...   common.time.AppZoneTest    tests="5"  ...
  codef.EasyCodefUtilJdk25Test        tests="1"  ...   TeloApplicationTests       tests="1"  ...
  ```
  보고된 27건과 일치한다. 마이그레이션 순서도 검증자가 직접 돌렸다.
  ```
  $ bash scripts/check-migration-order.sh
  기준: origin/main (최대 버전 202609210052)
    [통과] V202609211227__create_transactions.sql (버전 202609211227)
  마이그레이션 순서 검사 통과.
  exit=0
  ```
  `[통과]` + 파일명이 찍혔으므로 트러블슈팅 16번의 "건너뛴다 = 통과" 함정이 아니다.

## 7. 검증 축 6개가 세 테이블에 적용됐는가
- 판정: **FIX**
- 검증 대상: `t014_backend_summary.md:199-208`(자기 보고 표) vs 검증자가 테스트 소스에서 직접 센 것 + 변이 실행 결과
- 사유: **여섯 축 자체는 세 테이블에 모두 들어가 있다. 보고 표는 사실이다.** 그러나 두 축의 **적용 범위**가 좁아 실제 구멍이 남았고, 아래 9번의 변이로 실증됐다.

  | 축 | transactions | transfer_links | sync_attempts |
  |---|---|---|---|
  | 유니크 범위(거부+허용) | ✓ 거부1(`TransactionSchemaTest.java:85-99`) + 허용2(`:102-112`, `:115-129`) | ✓ 거부2(`:282-303`, `:306-327`) + 허용1(`:330-344`) | N/A(UNIQUE 없음) |
  | 제약 이름 | ✓ UNIQUE1 + CHECK5 | ✓ UNIQUE2 + CHECK1 | ✓ CHECK2(`SyncAttemptSchemaTest.java:133,142`) |
  | 인덱스 존재·열 구성 | ✓ 6개(`:378-400`) | ✓ 2개(`:402-405`) | ✓ 2개(`SyncAttemptSchemaTest.java:169-173`) |
  | 인덱스 종류(`USING btree`) | ✓ | ✓ | ✓ |
  | 컬럼 타입(`udt_name`) | △ 5열(`:413-418`) — **`VARCHAR` 길이 미고정** | △ 1열(`confirmed_at`만) | △ 4열 — **`VARCHAR` 길이 미고정** |
  | `NOT NULL` 맵 통째 비교 | ✓ 17열(`:424-440`) | ✓ 7열(`:442-450`) | ✓ 9열(`SyncAttemptSchemaTest.java:191-200`) |
  | **(파생) CHECK 범위 — 허용 케이스** | ✗ | ✗ | ✗ |

  - **CHECK에 허용 케이스가 없다.** "유니크 범위" 축의 원리(거부만 보면 제약을 과도하게 좁혀도 통과한다, T-023에서 얻은 교훈)가 UNIQUE에만 적용되고 CHECK 7종에는 옮겨지지 않았다. CHECK 테스트(`TransactionSchemaTest.java:151-166`, `SyncAttemptSchemaTest.java:121-143`)는 전부 **정의되지 않은 값의 거부**만 본다. 정의된 값이 실제로 통과하는지는 보지 않는다.
  - **`udt_name`은 `VARCHAR` 길이를 구분하지 못한다.** `VARCHAR(10)`과 `VARCHAR(255)`의 `udt_name`은 둘 다 `varchar`다. T-013에서 `SMALLINT`→`INTEGER`를 잡으려고 도입한 축인데, 문자열 컬럼에 대해서는 같은 구멍이 그대로 남는다. `information_schema.columns.character_maximum_length`를 함께 봐야 한다.
- 수정 지시:
  1. `TransactionSchemaTest.java:151-166`의 `undefinedEnumValuesAreRejected` 옆에 **허용 케이스** 테스트를 추가한다. `txn_type`에 `'INCOME'`, `transfer_status`에 `'PENDING_CONFIRM'`·`'AUTO_LINKED'`·`'USER_CONFIRMED'`·`'UNLINKED'`, `refund_status`에 `'ORIGINAL'`, `initial_classification_source`에 `'AUTO_MATCHED'`를 raw INSERT/UPDATE로 넣어 **성공하는지** 단언한다. `SyncAttemptSchemaTest.java:121-143`도 같다(`'MANUAL'`·`'SUCCESS'`·`'FAILURE'`). `transfer_links`는 `'USER_CONFIRMED'`.
  2. `TransactionSchemaTest.java:404-420`의 `assertColumnType`에 `character_maximum_length` 인자를 더한다. 이미 `information_schema.columns`를 조회하고 있으므로 `SELECT udt_name, character_maximum_length`로 컬럼 하나만 늘리면 된다. `codef_transaction_id`(255)·`txn_type`(10)·`transfer_status`(20)·`memo`(255), `sync_attempts.bank_code`(10)·`result`(10)·`failure_reason`(500), `transfer_links.link_status`(20)·`match_reason`(255)에 적용한다.

## 8. 보고된 변이 A·G·H 재현
- 판정: PASS (세 건 모두 보고 그대로 재현됨, A의 서술도 사실)
- 검증 대상: `t014_backend_summary.md:109-195` vs 검증자의 격리 워크트리 실행
- 사유:
  - **A(두 UNIQUE → 복합)** — 보고 "3건 FAILED"와 **정확히 일치**한다.
    ```
    $ # uq_transfer_links_withdrawal UNIQUE (withdrawal_transaction_id, deposit_transaction_id)
    > 하나의 입금은 하나의 출금과만 엮인다 — 입금 열 단독 UNIQUE FAILED
    > 하나의 출금은 하나의 입금과만 엮인다 — 출금 열 단독 UNIQUE FAILED
    > 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향·부분 조건까지 FAILED
    27 tests completed, 3 failed
    ```
    **"각각 다른 상대 거래로 두 번째 연결을 시도한다"는 주장은 사실이다.** 검증자가 테스트 소스를 직접 읽어 확인했다. `oneWithdrawalCannotLinkToTwoDeposits`(`TransactionSchemaTest.java:282-303`)는 `firstDeposit`·`secondDeposit` **두 개의 서로 다른 입금 거래**를 만들어(`:288-292`) 같은 출금에 각각 붙인다(`:294`, `:298-299`). `oneDepositCannotLinkToTwoWithdrawals`(`:306-327`)도 대칭으로 `firstWithdrawal`·`secondWithdrawal`을 쓴다. 같은 쌍을 두 번 넣는 구성이었다면 복합 UNIQUE에서도 막혀 독립성이 증명되지 않았을 텐데, 그 함정을 피했다. 재현 결과 두 테스트가 실제로 FAILED가 나므로 구성과 결과가 일치한다.
  - **G(자기참조 FK에 CASCADE)** — 1건 FAILED, 보고와 일치. **1건으로 충분하다.** 깨지는 테스트 `deletingLinkedOriginalTransactionIsRejected`(`TransactionSchemaTest.java:227-250`)가 혼자서 두 겹으로 증명한다. (1) 참조 중인 원거래 DELETE가 `DataIntegrityViolationException`으로 거부되는지(`:243-247`), (2) 거부된 뒤 환불 행이 그대로 남아 있는지(`:248-250`). CASCADE가 붙으면 첫 겹이 깨지고 둘째 겹도 함께 깨진다(행이 사라진다). 증명이 다른 테스트의 부수 효과에 기대지 않으므로 건수가 1인 것은 결함이 아니다.
  - **H(`sync_attempts`에 `updated_at` 추가)** — 1건 FAILED, 보고와 일치. **nullable 맵 비교로 잡힌 것이 맞다.** `assertNullability`(`SyncAttemptSchemaTest.java:217-231`)가 `containsExactlyInAnyOrderEntriesOf`로 맵을 **통째로** 비교하므로 기대 맵에 없는 컬럼이 늘어나면 실패한다. 컬럼별 단건 조회였다면 통과했다. 실패 테스트 이름(`NOT NULL 구성이 설계와 정확히 일치한다 — updated_at이 없는 것까지`)도 보고와 한 글자도 다르지 않다.
  - G·H를 한 번에 적용해 돌린 결과:
    ```
    > 환불이 가리키는 원거래는 지울 수 없다 — 자기참조 FK는 NO ACTION이다 FAILED
    > NOT NULL 구성이 설계와 정확히 일치한다 — updated_at이 없는 것까지 FAILED
    27 tests completed, 2 failed
    ```

## 9. 검증자 자체 변이 — 여섯 종이 미검출이다
- 판정: **FIX**
- 검증 대상: 격리 워크트리에서 검증자가 직접 작성·실행한 변이 9종
- 사유: **아홉 종 중 여섯 종이 잡히지 않는다.** 여섯을 **동시에** 넣고 돌렸는데 `BUILD SUCCESSFUL`이었다.

  | 변이 | 마이그레이션 변경 | 결과 |
  |---|---|---|
  | Q1 | `ck_transactions_txn_type`에서 `'INCOME'` 제거 | **미검출** |
  | Q2 | `ck_transactions_transfer_status`에서 `'UNLINKED'` 제거 | **미검출** |
  | Q3 | `ck_transactions_refund_status`에서 `'ORIGINAL'` 제거 | **미검출** |
  | Q4 | `codef_transaction_id` `VARCHAR(255)` → `VARCHAR(30)` | **미검출** |
  | Q5 | `category_id`의 `REFERENCES categories (id)` 제거 | **미검출** |
  | Q6 | `sync_attempts.bank_code` `VARCHAR(10)` → `VARCHAR(100)` | **미검출** |
  | Q7 | `(user_id, transacted_at, category_id)` → `(category_id, transacted_at, user_id)` | 검출 |
  | Q8 | 부분 인덱스 조건 `'PENDING_CONFIRM'` → `'AUTO_LINKED'` | 검출 |
  | Q9 | `transactions.user_id` FK에서 `ON DELETE CASCADE` 제거 | 검출 |

  Q1~Q6 동시 적용 실행 결과 — 실패 0.
  ```
  $ # txn_type IN ('EXPENSE') / transfer_status 4종 / refund_status 2종
  $ # codef_transaction_id VARCHAR(30) / category_id FK 없음 / bank_code VARCHAR(100)
  $ ./gradlew test --tests '*TransactionSchemaTest' --tests '*SyncAttemptSchemaTest' --rerun-tasks
  BUILD SUCCESSFUL in 10s
  ```
  Q7~Q9 동시 적용 실행 결과 — 의도대로 잡힌다.
  ```
  > 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향·부분 조건까지 FAILED
  > 사용자를 지우면 거래도 함께 지워진다 — 비정규화한 user_id의 CASCADE FAILED
  27 tests completed, 2 failed
  ```
  개별 영향:
  - **Q1이 가장 무겁다.** 수입 거래를 DB가 통째로 거부하는 스키마인데 27건이 전부 초록이다. `txn_type`의 `INCOME`/`EXPENSE` 구분은 F-OAVYWT의 기본 축이고 `amount > 0` + 방향 분리 설계가 여기 얹혀 있다(`docs/09-db-design.md` 3.4).
  - **Q2는 입력 명세가 명시적으로 확인을 요구한 항목이다.** `t014_00_input.md:53`이 `transfer_status` 5종을 나열했는데, 그중 `UNLINKED`("자동 연결을 사용자가 해제")가 CHECK에서 빠져도 아무도 모른다. T-018·T-029의 연결 해제가 런타임에 처음 깨진다.
  - **Q5는 직전 Task의 관례에서 퇴행한 것이다.** T-013은 `merchant_keyword_rules.category_id`의 FK를 `deletingReferencedCategoryIsRejected`(`CategorySchemaTest.java:191-208`)로 못 박았고, 그 단언이 없으면 FK가 사라져도 통과한다는 것을 T-013 QA가 변이 M8로 확인했다. `transactions.category_id`에는 대응 단언이 없다. Hibernate `validate`는 FK를 보지 않는다.
  - Q4·Q6은 7번의 `character_maximum_length` 공백과 같은 뿌리다. Q4는 코드에프 식별자가 30자를 넘으면 수집이 런타임에 깨지는 변이다.
- 수정 지시:
  1. Q1~Q3 — 7번 수정 지시 1과 같다. CHECK 허용 케이스 단언을 추가하면 세 변이가 한꺼번에 잡힌다.
  2. Q4·Q6 — 7번 수정 지시 2와 같다.
  3. Q5 — `TransactionSchemaTest.java`에 `transactions.category_id`의 FK를 못 박는 테스트를 추가한다. `CategorySchemaTest.java:191-208`과 같은 형태로, 참조 중인 카테고리 DELETE가 `DataIntegrityViolationException`으로 거부되는지 본다. 미분류(99)는 다른 테스트가 계속 쓰므로 새 카테고리를 만들어 쓰거나, 존재하지 않는 `category_id`로 raw INSERT가 거부되는지 보는 편이 간섭이 없다.

## 10. 범위 준수 — 선점도 누락도 없다
- 판정: PASS
- 검증 대상: `git show --stat cbf96d2` vs `t014_00_input.md:8-17`
- 사유: 커밋이 만든 파일 16개(+`_workspace` 2개)가 전부 마이그레이션·엔티티·열거형·리포지토리·테스트다. **기존 파일을 하나도 고치지 않았다**(전부 신규, `1832 insertions(+)` / 삭제 0).
  - **선점 없음**: `codef` 패키지에 새 파일이 없다(T-015). 중복 판정 로직 없음(T-016) — `TransactionRepository`에 `findByAccountIdAndCodefTransactionId` 류 메서드가 하나도 없다. 이체 후보 탐색 없음(T-017) — `TransferLinkRepository`도 빈 `JpaRepository`다. 자동 분류 없음(T-019) — `ClassificationSource`는 열거형 선언뿐이고 매칭 코드가 없다. 서비스·컨트롤러·DTO가 `transaction`·`sync` 어느 패키지에도 없다. 상태를 바꾸는 메서드가 `Transaction`·`TransferLink`·`SyncAttempt` 셋 다 없다(생성자만).
  - **`transaction_edit_histories` 없음**: 마이그레이션에 그 테이블이 없고 엔티티도 없다. 명시적으로 별도 Task라고 못 박힌 것을 지켰다.
  - **누락 없음**: 테이블 3개, 인덱스 7개, CHECK 7종, UNIQUE 3종, 엔티티 3개 + 열거형 6개, 리포지토리 3개가 모두 있다. 완료 기준 1~8에 대응하는 테스트도 전부 있다(2번은 인덱스 단언 9건, 3번은 독립 UNIQUE 거부 2 + 허용 1, 4번은 `deletingAccountSetsAccountIdToNull`, 5번은 부분 인덱스 조건 단언, 6번은 `categoryIdDefaultsToUnclassified`).

## 11. 기존 관례 일치 — 그리고 `sync`를 따로 뺀 판단
- 판정: PASS
- 검증 대상: `transaction/**`·`sync/**` vs `user/**`·`budget/**`·`account/**`·`category/**`
- 사유:
  - **다섯 도메인이 일관된다.** 패키지 `com.petgyebu.telo.{도메인}.domain`/`.repository`, 엔티티는 `@Entity` + `@Table` + `@Getter` + `@NoArgsConstructor(PROTECTED)` + 공개 생성자 + `Objects.requireNonNull("필드명")`, 연관은 `@ManyToOne(LAZY, optional = false)` + `@JoinColumn(nullable = false)`, 열거형은 `@Enumerated(STRING)` + DB CHECK + 대문자 스네이크, `TIMESTAMPTZ` ↔ `OffsetDateTime`, 리포지토리는 `JpaRepository`만, 마이그레이션 파일명·버전 규칙 준수, 테스트는 Testcontainers `@SpringBootTest` + `@Container @ServiceConnection` + `postgres:16-alpine` + 한글 `@DisplayName`, 시각은 `AppZone.clock()`(`TransactionSchemaTest.java:566` 등).
  - **달라야 하는 곳이 옳게 다르다.** `sync_attempts.account_id`가 `optional`·`nullable` 없이 선언된 것(`SyncAttempt.java:50-52`)은 관례(전부 `optional = false`)를 복사하지 않은 결과로, `ON DELETE SET NULL`과 맞다. `SyncAttempt`에 `updatedAt`이 없는 것도 같다. `initialClassificationSource`의 `updatable = false`(`Transaction.java:92`)는 이 프로젝트에 처음 나오는 표현인데 명세가 요구한 불변성을 정확히 표현한다.
  - **`sync`를 별도 패키지로 나눈 판단은 타당하다.** 문서에 패키지 레이아웃 규칙이 없어(저장소 문서 전수 검색 결과 `docs/09-db-design.md:29`의 `common.time.AppZone` 한 줄 외에 패키지 언급이 없다) 구현자 재량이다. `sync_attempts`는 거래 레코드가 아니라 **수집 시도의 운영 기록**이고, 후속 T-020(스케줄러·분산락)·T-021(수동 새로고침)이 같은 관심사라 붙을 자리가 생긴다. `transaction`에 넣으면 거래 도메인이 운영 기록까지 떠안고, `account`에 넣으면 KPI 집계 코드가 계좌 패키지로 들어간다. 세 선택지 중 가장 응집도가 높다.

## 12. backend-engineer 판단 1 — `user_id`↔`account_id` 복합 FK를 걸지 않은 것
- 판정: PASS (타당. **검증자가 명세를 직접 확인했고, 명세는 그것을 요구하지 않는다**)
- 검증 대상: `t014_backend_summary.md:45-60` vs `docs/09-db-design.md` 3.4절 본문
- 사유: 3.4절이 비정규화를 설명한 문단의 마지막 문장은 **"대신 `account_id`와 `user_id`가 어긋날 수 있으므로 거래 저장은 반드시 계좌 조회를 거친 경로로만 수행한다"**이다. 방어선을 **코드 경로**로 명시했지 DB 제약을 요구하지 않았다. 같은 절의 "제약·인덱스" 표에도 복합 FK가 없고, `accounts`를 다루는 3.1절에도 `UNIQUE (id, user_id)`가 없다. 입력 명세(`t014_00_input.md:76`)도 "명세에 없는 것을 임의로 추가하지 마라. 판단만 하고 근거를 남기면 된다"고 못 박았다. **넣었다면 그것이 명세 위반이었다.**
  - 근거 셋도 각각 성립한다. (2)의 "`accounts.id`가 PK인 이상 순수한 중복 인덱스"는 맞다 — PostgreSQL에서 `UNIQUE (id, user_id)`는 PK 인덱스와 겹치는 추가 인덱스를 만든다. (3)은 `Transaction` 생성자가 `user`와 `account`를 둘 다 요구한다(`Transaction.java:128-140`)는 사실과 일치한다.
  - **판단을 코드에 남긴 것도 확인했다.** `Transaction.java:30-33` Javadoc과 `V202609211227__create_transactions.sql:6-11` 주석 양쪽에 "DB는 이것을 막지 않는다"와 그 이유가 적혀 있다. 후속 Task가 모르고 지나칠 위험을 줄였다.

## 13. backend-engineer 판단 2 — `assertIndex` 헬퍼를 새로 쓴 것
- 판정: PASS (타당. **기존 헬퍼로는 실제로 불가능하다** — 검증자가 소스로 확인)
- 검증 대상: `CategorySchemaTest.java`의 `assertIndex` vs `TransactionSchemaTest.java:535-561`
- 사유: `CategorySchemaTest.assertIndex`의 마지막 단언은 `.endsWith("USING " + method + " (" + expectedColumns + ")")`다. 부분 인덱스의 `indexdef`는
  ```
  CREATE INDEX ix_transactions_transfer_status_pending ON public.transactions
    USING btree (transfer_status) WHERE ((transfer_status)::text = 'PENDING_CONFIRM'::text)
  ```
  로 열 목록 뒤에 `WHERE ...`가 더 붙는다. `endsWith`이므로 **정의가 정확해도 무조건 실패한다.** 주장이 추측이 아니라 사실이다.
  - 새 헬퍼가 실효도 있다. `expectedPredicate`가 `null`이면 `WHERE` 절이 붙은 인덱스를 거부하고, 값이 있으면 조건 문자열까지 못 박는다. 검증자가 변이 Q8(조건 값을 `'AUTO_LINKED'`로)로 확인했다 — 잡힌다. DESC도 열 인자에 문자열로 들어가 Q7(열 순서 뒤집기)과 함께 잡힌다.
  - **기존 네 테스트 파일을 건드리지 않았다**는 주장도 사실이다(`git show --stat cbf96d2`에 신규 파일만 있다). 외과적 변경 원칙에 맞다.
  - 참고: 이제 `assertIndex`가 **세 벌**이다(`AccountSchemaTest`: 열만, `CategorySchemaTest`·`BudgetSchemaTest`: 열+종류, `TransactionSchemaTest`·`SyncAttemptSchemaTest`: 열+종류+조건). T-013 QA 리포트 6번이 이미 통일을 후속 정리 Task로 권고했고, 이번에 한 벌이 더 늘었다. **이번 Task의 지적은 아니다.**

## 14. backend-engineer 판단 3 — 제약·인덱스 이름
- 판정: PASS
- 검증 대상: `t014_backend_summary.md:70-73` vs 기존 네 마이그레이션
- 사유: `uq_`/`ck_`/`ix_` + 테이블 + 열 관례를 따랐고 문서에 규칙이 없다는 서술도 맞다. 이름이 제약 이름 단언(`hasMessageContaining`)에 그대로 쓰이므로 테스트와 스키마가 서로를 고정한다. 인덱스 이름 접미사를 줄인 것(`ix_transactions_user_transacted_at`)도 PostgreSQL 식별자 63자 제한을 고려한 합리적 축약이고, `assertIndex`가 이름으로 조회하므로 오타가 있으면 바로 깨진다.

## 15. 트러블슈팅 기록을 추가하지 않은 판단
- 판정: PASS
- 검증 대상: `t014_backend_summary.md:213-216` vs `CLAUDE.md` "트러블슈팅 기록 (자동 갱신)"
- 사유: 기록 대상 6종 중 어느 것도 발생하지 않았다. (1) 한 단계 이상 추론이 필요했던 문제 — 없다. 변이 8종은 디버깅이 아니라 의도된 양방향 검증이다. (2) 겉보기 성공 뒤의 실패 — 없다. 첫 빌드부터 27건이 통과했고, 부분 인덱스에 기존 헬퍼가 통하지 않는 것은 **입력 명세(`t014_00_input.md:140`)가 미리 경고한 것**을 코드 읽기 단계에서 처리한 것이지 실패를 겪고 고친 것이 아니다. (3) 라이브러리 좌표 차이 — 이번 Task에 의존성 변경이 없다. (4) 보안 — 없다. (5) 고쳤는데 증상이 남은 경우 — 없다. (6) 검증 방법 자체의 오류 — 9번의 미검출 여섯 종이 여기 걸릴 후보이나, 이것은 **작업 중 겪고 해결한 사건이 아니라 검증자가 사후에 발견한 커버리지 공백**이다. CLAUDE.md는 "해결하지 못한 것은 트러블슈팅이 아니라 백로그 Task로 등록한다"고 정했으므로, 7·9번의 수정 지시를 반영하는 것이 맞는 처리다.
  `docs/11-troubleshooting-log.md`를 이번 커밋이 건드리지 않았으므로 문서 안의 건수 정합성도 그대로다.

## 16. 후속 Task 번호 오기 두 건
- 판정: **FIX** (우선순위 낮음. 코드 영향 없음)
- 검증 대상: `TransferLink.java:28-29`, `SyncAttempt.java:33` vs `docs/10-task-backlog.md:79-84, 99-100`
- 사유: T-013 QA 리포트 14번이 같은 종류의 오기(`T-016`↔`T-019`)를 지적하며 "남겨 두면 엉뚱한 Task를 찾게 된다"고 했는데, 이번 Task에 두 건이 새로 들어왔다.
  - `TransferLink.java:29` — "연결을 해제하면 ... 그 로직은 **T-018** 범위라 여기에 아직 없다". 백로그상 T-018은 **환불 순액 처리**(`:82`)이고, **이체 확인·연결 해제 API는 T-029**(`:100`)다.
  - `SyncAttempt.java:33` — "동기화 스케줄러와 재시도는 **T-015** 범위라 여기에 아직 없다". T-015는 **코드에프 거래 조회 연동과 90일 페이지네이션**(`:79`)이고, **스케줄러와 분산락·재시도 3회 지수 백오프는 T-020**(`:84`)이다.
  - 나머지 참조는 맞다: `Transaction.java:44`의 T-016(중복 판정)·T-017·T-018·T-019, 마이그레이션 SQL:59의 T-017(이체 후보 탐색), SQL:11과 `Transaction.java:33`의 T-015(수집 경로).
- 수정 지시: `TransferLink.java:29`의 `T-018` → `T-029`, `SyncAttempt.java:33`의 `T-015` → `T-020`. 푸시 전 같은 커밋에 넣는 편이 낫다.

---

## 리더 확인 요청

- **스키마·엔티티 자체에는 결함이 없다.** 마이그레이션은 `docs/09-db-design.md` 3.4·3.5·3.6과 컬럼·타입·NULL·DEFAULT·CHECK·UNIQUE·인덱스가 한 줄도 어긋나지 않고(1~4번), 엔티티 매핑도 일치하며(5번), 범위 준수(10번)와 관례 일치(11번)에도 문제가 없다. 자기 보고의 변이 A·G·H는 전부 재현됐고 A의 독립성 주장도 사실이다(8번). **푸시를 막을 사유는 없다.**
- **FIX 3건은 전부 테스트 커버리지 또는 주석이다.**
  - FIX 7·9번(같은 뿌리) — CHECK **허용 케이스** 단언 추가 + `character_maximum_length` 단언 추가 + `transactions.category_id` FK 단언 추가. 검증자 변이 **Q1~Q6 여섯 종이 동시에 들어가도 `BUILD SUCCESSFUL`**이다. 특히 Q1(수입 거래를 DB가 거부하는 스키마)과 Q2(입력 명세가 확인을 요구한 `transfer_status` 5종 중 하나가 사라짐)는 런타임까지 숨는다.
  - FIX 16번 — Javadoc 두 줄의 Task 번호.
- **새 검증 축 제안 2개**: "CHECK 범위 — 허용 케이스"와 "`VARCHAR` 길이(`character_maximum_length`)". 둘 다 기존 축의 일반화이며, 앞으로의 Task 입력에 축 7·8로 올릴 만하다. 전자는 T-023에서 UNIQUE에 대해 얻은 교훈을 CHECK로 옮기는 것이고, 후자는 T-013에서 `udt_name`을 도입한 이유가 문자열 컬럼에서는 아직 안 막힌다는 것이다.
- **이월 사항 1건**: `assertIndex` 헬퍼가 세 벌로 갈렸다(13번 참고). T-013 QA 6번의 통일 권고가 그대로 남아 있고 이번에 한 벌이 늘었다. 별도 정리 Task로 묶을지 리더가 정하면 된다.
- **백로그 미갱신**: `docs/10-task-backlog.md:78`의 T-014가 아직 `[ ]`다. 푸시·PR 시점에 T-006·T-013과 같은 형식으로 갱신해야 한다.
