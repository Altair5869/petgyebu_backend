# T-014 구현 요약: `transactions`·`transfer_links`·`sync_attempts` 스키마와 엔티티

- 기능 ID: F-OAVYWT (거래 동기화)
- 브랜치: `feature/F-OAVYWT-schema`
- 범위: 마이그레이션 + 엔티티 + 리포지토리 + 스키마 테스트. 동기화 연동(T-015), 중복 판정(T-016),
  이체 탐색·연결(T-017·T-018), 자동 분류(T-019), 거래 API는 범위 밖이라 손대지 않았다.

## 항목별 결과

- [완료] 마이그레이션: `src/main/resources/db/migration/V202609211227__create_transactions.sql`
  — 세 테이블, CHECK 7개, UNIQUE 3개, 인덱스 7개(부분 1, DESC 2)
- [완료] 엔티티: `transaction/domain/{Transaction,TransferLink,TxnType,ClassificationSource,
  TransferStatus,RefundStatus,LinkStatus}.java`, `sync/domain/{SyncAttempt,TriggerType,SyncResult}.java`
- [완료] 리포지토리: `transaction/repository/{TransactionRepository,TransferLinkRepository}.java`,
  `sync/repository/SyncAttemptRepository.java`
- [완료] 스키마 테스트: `src/test/java/com/petgyebu/telo/transaction/TransactionSchemaTest.java`(19건),
  `src/test/java/com/petgyebu/telo/sync/SyncAttemptSchemaTest.java`(8건)

## 만든 파일

```
src/main/resources/db/migration/V202609211227__create_transactions.sql
src/main/java/com/petgyebu/telo/transaction/domain/Transaction.java
src/main/java/com/petgyebu/telo/transaction/domain/TransferLink.java
src/main/java/com/petgyebu/telo/transaction/domain/TxnType.java
src/main/java/com/petgyebu/telo/transaction/domain/ClassificationSource.java
src/main/java/com/petgyebu/telo/transaction/domain/TransferStatus.java
src/main/java/com/petgyebu/telo/transaction/domain/RefundStatus.java
src/main/java/com/petgyebu/telo/transaction/domain/LinkStatus.java
src/main/java/com/petgyebu/telo/transaction/repository/TransactionRepository.java
src/main/java/com/petgyebu/telo/transaction/repository/TransferLinkRepository.java
src/main/java/com/petgyebu/telo/sync/domain/SyncAttempt.java
src/main/java/com/petgyebu/telo/sync/domain/TriggerType.java
src/main/java/com/petgyebu/telo/sync/domain/SyncResult.java
src/main/java/com/petgyebu/telo/sync/repository/SyncAttemptRepository.java
src/test/java/com/petgyebu/telo/transaction/TransactionSchemaTest.java
src/test/java/com/petgyebu/telo/sync/SyncAttemptSchemaTest.java
```

기존 네 스키마 테스트(`UserSchemaTest`·`BudgetSchemaTest`·`AccountSchemaTest`·`CategorySchemaTest`)는
건드리지 않았다.

## 판단이 필요했던 것

### 1. `transactions.user_id`↔`account_id` 정합성을 DB로 강제하지 않는다

강제하려면 `accounts`에 `UNIQUE (id, user_id)`를 새로 걸고 `transactions (account_id, user_id)`가
그것을 참조하는 복합 FK를 만들어야 한다. **하지 않았다.** 근거 셋.

1. `docs/09-db-design.md` 3.4절이 정한 방어선은 DB 제약이 아니라 "거래 저장은 반드시 계좌 조회를
   거친 경로로만 수행한다"는 코드 규율이다. 문서에 없는 제약을 스키마에 추가하는 것은 이 Task의
   범위를 넘는다.
2. 대가가 `transactions` 한 테이블에 그치지 않는다. 이미 병합된 `accounts`(T-006)의 스키마를
   바꿔야 하고, 그 UNIQUE는 `accounts.id`가 PK인 이상 순수한 중복 인덱스다.
3. 정합성이 깨지는 유일한 경로는 T-015에서 만들 수집 코드다. 그 코드가 계좌를 조회해 `user`를
   얻으므로(`Transaction` 생성자가 `user`와 `account`를 둘 다 요구한다) 어긋날 여지는 거기서
   닫는 편이 값싸다.

대신 어긋남이 **가능하다는 사실**을 `Transaction` 클래스 주석과 마이그레이션 주석에 남겨 다음
Task가 모르고 지나치지 않게 했다. 제약을 나중에 걸기로 결정한다면 별도 Task가 맞다.

### 2. `assertIndex` 헬퍼를 이 테스트용으로 다시 썼다

`CategorySchemaTest.assertIndex`는 `indexdef`가 `USING <종류> (<열>)`로 **끝난다**고 가정한다.
부분 인덱스는 그 뒤에 `WHERE ...`가 더 붙어서 정의가 맞아도 실패한다. 그래서
`TransactionSchemaTest`의 헬퍼는 조건 인자를 하나 더 받고, `null`을 주면 `WHERE` 절이 없는 것까지
단언한다(조건이 더 붙는 변이도 함께 잡힌다). DESC는 열 목록 안에 `transacted_at DESC`로 들어가므로
열 인자에 그대로 적었다. **기존 네 테스트 파일은 수정하지 않았다.**

### 3. 제약·인덱스 이름

문서에 이름 규칙이 없어 기존 관례(`uq_`/`ck_`/`ix_` + 테이블 + 열)를 따랐다. `transactions`의 열
이름이 길어 인덱스 이름은 접미사를 줄였다(`ix_transactions_user_transacted_at` 등).

## 실행한 검증

### 전체 빌드

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 21s
```

새 테스트 27건(`TransactionSchemaTest` 19, `SyncAttemptSchemaTest` 8)이 실제로 돌았다.

```
$ grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
    build/test-results/test/TEST-*Transaction*.xml build/test-results/test/TEST-*Sync*.xml
tests="19" skipped="0" failures="0" errors="0"   # TransactionSchemaTest
tests="8"  skipped="0" failures="0" errors="0"   # SyncAttemptSchemaTest
```

### 마이그레이션 순서 검사

커밋한 뒤 실행했다(스크립트가 기준 브랜치와 커밋을 대조한다).

```
$ ./scripts/check-migration-order.sh origin/main
기준: origin/main (최대 버전 202609210052)
  [통과] V202609211227__create_transactions.sql (버전 202609211227)
마이그레이션 순서 검사 통과.
```

### 양방향 증명 — 마이그레이션을 망가뜨리면 실제로 FAILED가 난다

각 변이를 적용하고 테스트를 돌린 뒤 원복했다. 최종 파일은 변이 전과 바이트 단위로 동일하다
(`diff` 무출력 확인).

| # | 변이 | 결과 |
|---|---|---|
| A | `transfer_links`의 두 UNIQUE → 복합 `UNIQUE (withdrawal, deposit)` | 3건 FAILED |
| B | `sync_attempts.account_id` → `ON DELETE CASCADE` | 1건 FAILED |
| C | 부분 인덱스의 `WHERE` 절 제거 | 1건 FAILED |
| D | `(user_id, transacted_at DESC)`의 DESC 제거 | 1건 FAILED |
| E | `category_id`의 `DEFAULT 99` 제거 + `sync_attempts` 인덱스 DESC 제거 | 3건 FAILED |
| F | `uq_transactions_account_codef_txn_id`와 부분 인덱스 통째로 제거 | 2건 FAILED |
| G | 자기참조 FK에 `ON DELETE CASCADE` 추가 | 1건 FAILED |
| H | `sync_attempts`에 `updated_at` 추가 | 1건 FAILED |

실제 출력.

**A — 복합 UNIQUE로 바꾸기**

```
transactions·transfer_links 스키마 제약 검증 > 하나의 입금은 하나의 출금과만 엮인다 — 입금 열 단독 UNIQUE FAILED
transactions·transfer_links 스키마 제약 검증 > 하나의 출금은 하나의 입금과만 엮인다 — 출금 열 단독 UNIQUE FAILED
transactions·transfer_links 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향·부분 조건까지 FAILED
19 tests completed, 3 failed
```

두 UNIQUE 테스트는 각각 **다른 상대 거래**로 두 번째 연결을 시도한다(출금 A↔입금 X 다음 출금
A↔입금 Y). 복합 UNIQUE에서는 통과해 버리는 케이스라, 이 구성이어야 독립 UNIQUE를 증명한다.
실패 메시지는 `Expecting code to raise a throwable.`이다 — 즉 제약이 아예 걸리지 않았다.

**B — `SET NULL`을 `CASCADE`로**

```
sync_attempts 스키마 제약 검증 > 계좌를 지워도 시도 기록은 남고 account_id만 null이 된다 — ON DELETE SET NULL FAILED
8 tests completed, 1 failed
org.springframework.dao.EmptyResultDataAccessException: Incorrect result size: expected 1, actual 0
```

행이 통째로 사라졌다. KPI 모수가 계좌 해제로 줄어드는 바로 그 증상이다.

**C — 부분 인덱스의 `WHERE` 제거**

```
java.lang.AssertionError: [인덱스 ix_transactions_transfer_status_pending의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다]
Expecting actual:
  "CREATE INDEX ix_transactions_transfer_status_pending ON public.transactions USING btree (transfer_status)"
to end with:
  "USING btree (transfer_status) WHERE ((transfer_status)::text = 'PENDING_CONFIRM'::text)"
```

**D — DESC 제거**

```
java.lang.AssertionError: [인덱스 ix_transactions_user_transacted_at의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다]
Expecting actual:
  "CREATE INDEX ix_transactions_user_transacted_at ON public.transactions USING btree (user_id, transacted_at)"
to end with:
  "USING btree (user_id, transacted_at DESC)"
```

**E — `DEFAULT 99` 제거 + `sync_attempts` DESC 제거**

```
transactions·transfer_links 스키마 제약 검증 > 정의되지 않은 열거형 값은 저장할 수 없다 — CHECK 제약 넷 FAILED
transactions·transfer_links 스키마 제약 검증 > category_id를 주지 않으면 99(미분류)가 들어간다 — DEFAULT가 실제로 발화한다 FAILED
sync_attempts 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향까지 FAILED
27 tests completed, 3 failed
```

CHECK 테스트도 함께 깨진 것은 그 테스트의 raw INSERT가 `category_id`를 생략하기 때문이다
(DEFAULT가 없으면 NOT NULL 위반으로 먼저 걸린다). 탐지에는 문제가 없지만 두 단언이 같은
DEFAULT에 얹혀 있다는 뜻이라 여기 적어 둔다.

**F — UNIQUE와 부분 인덱스 통째 제거**

```
transactions·transfer_links 스키마 제약 검증 > 같은 계좌에서 같은 대행사 거래 식별자를 두 번 수집할 수 없다 FAILED
transactions·transfer_links 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향·부분 조건까지 FAILED
19 tests completed, 2 failed
```

**G·H — 자기참조 FK에 CASCADE, `sync_attempts`에 `updated_at`**

```
transactions·transfer_links 스키마 제약 검증 > 환불이 가리키는 원거래는 지울 수 없다 — 자기참조 FK는 NO ACTION이다 FAILED
sync_attempts 스키마 제약 검증 > NOT NULL 구성이 설계와 정확히 일치한다 — updated_at이 없는 것까지 FAILED
27 tests completed, 2 failed
```

`updated_at` 추가가 잡히는 것은 nullable 맵을 통째로 비교하기 때문이다. 컬럼별로 봤다면
append-only가 조용히 무너졌을 것이다.

## 검증 축 적용 현황

| 축 | transactions | transfer_links | sync_attempts |
|---|---|---|---|
| 유니크 범위(허용 케이스 포함) | 거부 1 + 허용 2 | 거부 2 + 허용 1 | 해당 없음(UNIQUE 없음) |
| 제약 이름 | UNIQUE 1 + CHECK 5 | UNIQUE 2 + CHECK 1 | CHECK 2 |
| 인덱스 존재·열 구성 | 6개 | 2개 | 2개 |
| 인덱스 종류(`USING btree`) | 6개 | 2개 | 2개 |
| 컬럼 타입(`udt_name`) | 5개 열 | 1개 열 | 4개 열 |
| `NOT NULL` 구성(맵 통째 비교) | 17열 | 7열 | 9열 |

부분 인덱스는 여기에 더해 `WHERE` 조건까지, DESC 인덱스는 정렬 방향까지 본다.

## 남긴 것

- **미해결 없음.** 외부 승인 대기나 검증 불가 항목은 이번 Task에 없다(코드에프 연동은 T-015).
- `docs/11-troubleshooting-log.md`에 추가한 항목은 없다. 이번 작업에서 한 단계 이상 추론이
  필요했던 오류나 조용한 실패가 발생하지 않았다(첫 빌드부터 27건 전부 통과했고, 부분 인덱스에
  기존 `assertIndex`가 통하지 않는 것은 코드를 읽는 단계에서 미리 처리했다). CLAUDE.md 기준상
  기능 구현 자체는 기록 대상이 아니다.
- `transactions.user_id`↔`account_id` 복합 FK는 "하지 않기로 판단"이지 "못 함"이 아니다. 나중에
  걸기로 한다면 `accounts` 스키마 변경을 포함한 별도 Task로 올려야 한다.
- 푸시·PR은 하지 않았다(리더 지시 대기).

---

## QA FIX 반영 (PASS 13 / FIX 3 / REDO 0 → 후속 커밋)

스키마·엔티티 자체에는 변경이 없다. 마이그레이션 파일은 한 글자도 고치지 않았다. 고친 것은
테스트 커버리지 둘과 주석 둘이다.

### FIX 1 — CHECK에 허용 케이스를 더했다

거부 케이스만 보면 CHECK 목록에서 값을 하나 빼도 통과한다(T-023에서 UNIQUE에 대해 얻은 교훈을
CHECK에 옮기지 못한 자리였다). 다음을 추가했다.

- `TransactionSchemaTest.definedEnumValuesAreAccepted` — `txn_type` 2종, `initial_classification_source`
  2종, `transfer_status` 5종, `refund_status` 3종을 raw INSERT로 넣고 **되읽어** 값까지 확인한다
- `TransactionSchemaTest.userConfirmedLinkStatusIsAllowed` — `link_status = 'USER_CONFIRMED'`와
  `confirmed_at` 저장 확인
- `SyncAttemptSchemaTest.definedEnumValuesAreAccepted` — `('MANUAL','FAILURE')`·`('SCHEDULED','SUCCESS')`

거부 헬퍼와 허용 헬퍼가 같은 INSERT를 쓰도록 `rawInsert`를 뽑아 공유한다.

### FIX 2 — `udt_name`에 `character_maximum_length`를 더했다

`VARCHAR(10)`과 `VARCHAR(255)`는 `udt_name`이 똑같이 `varchar`라 길이 변이를 못 잡았다.
`assertColumnType`에 길이 인자를 추가했다(문자열이 아닌 타입은 `null`을 기대한다 — 이러면
숫자 컬럼이 문자열로 바뀌는 변이도 함께 걸린다). 적용: `codef_transaction_id`(255)·`txn_type`(10)·
`transfer_status`(20)·`memo`(255), `transfer_links.link_status`(20)·`match_reason`(255),
`sync_attempts.bank_code`(10)·`result`(10)·`failure_reason`(500).

### FIX 3 — `category_id`의 FK를 단언한다

`TransactionSchemaTest.categoryForeignKeyIsEnforced` — 없는 카테고리(1234)로 raw INSERT를 던져
`transactions_category_id_fkey` 위반을 확인한다.

### FIX 4 — 후속 Task 번호 오기 둘

- `TransferLink.java:29` T-018 → **T-029**(이체 확인·연결 해제 API)
- `SyncAttempt.java:33` T-015 → **T-020**(스케줄러·분산락·재시도)

나머지 Task 참조는 QA가 맞다고 확인해 건드리지 않았다.

### 양방향 증명 — QA가 "미검출"로 보고한 여섯을 하나씩 다시 돌렸다

전부 원복했고 최종 파일은 변이 전과 `diff` 무출력이다.

| # | 변이 | 이전 | 지금 |
|---|---|---|---|
| Q1 | `ck_transactions_txn_type`에서 `'INCOME'` 제거 | 미검출 | **1건 FAILED** |
| Q2 | `ck_transactions_transfer_status`에서 `'UNLINKED'` 제거 | 미검출 | **1건 FAILED** |
| Q3 | `ck_transactions_refund_status`에서 `'ORIGINAL'` 제거 | 미검출 | **1건 FAILED** |
| Q4 | `codef_transaction_id` `VARCHAR(255)` → `VARCHAR(30)` | 미검출 | **1건 FAILED** |
| Q5 | `sync_attempts.bank_code` `VARCHAR(10)` → `VARCHAR(100)` | 미검출 | **1건 FAILED** |
| Q6 | `category_id`의 `REFERENCES categories (id)` 제거 | 미검출 | **1건 FAILED** |

```
=== Q1 INCOME 제거 ===
> 명세에 있는 열거형 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다 FAILED
22 tests completed, 1 failed
DataIntegrityViolationException ... INSERT INTO transactions (... txn_type ...)

=== Q2 UNLINKED 제거 / Q3 ORIGINAL 제거 ===
> 명세에 있는 열거형 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다 FAILED
22 tests completed, 1 failed

=== Q4 codef_transaction_id VARCHAR(30) ===
> 컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지 FAILED
AssertionFailedError: [transactions.codef_transaction_id의 길이가 설계와 다르다]
expected: 255
 but was: 30

=== Q5 bank_code VARCHAR(100) ===
> 컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지 FAILED
AssertionFailedError: [sync_attempts.bank_code의 길이가 설계와 다르다]
expected: 10
 but was: 100

=== Q6 category_id FK 제거 ===
> category_id는 categories를 참조한다 — 없는 카테고리는 저장할 수 없다 FAILED
AssertionError: Expecting code to raise a throwable.
```

덤으로 `'AUTO_MATCHED'`·`'MANUAL'`·`link_status 'USER_CONFIRMED'`를 동시에 빼는 변이도 돌렸다.

```
> 사용자가 확인한 연결도 저장된다 — link_status CHECK가 'AUTO'만 남지 않았다 FAILED
> 명세에 있는 열거형 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다 FAILED
> 명세에 있는 trigger_type·result 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다 FAILED
> 계좌 없이도 기록을 남길 수 있다 — account_id는 NULL 허용이다 FAILED
31 tests completed, 4 failed
```

### FIX 반영 후 검증

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 24s
TransactionSchemaTest  tests="22" failures="0" errors="0"
SyncAttemptSchemaTest  tests="9"  failures="0" errors="0"
```

기존 네 스키마 테스트는 건드리지 않았다. 두 새 축(CHECK 허용 케이스, VARCHAR 길이)의 소급
적용 여부는 리더 판단으로 남긴다. 트러블슈팅 로그는 리더·QA 판정대로 추가하지 않았다.
