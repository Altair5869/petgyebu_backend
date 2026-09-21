# T-014 입력: `transactions`·`transfer_links`·`sync_attempts` 스키마와 엔티티

- 기능 ID: F-OAVYWT (거래 동기화)
- 브랜치: `feature/F-OAVYWT-schema`
- 의존: T-006(완료, PR #18), T-013(완료, PR #19)
- 출처: `docs/09-db-design.md` 3.4·3.5·3.6

## 범위

**스키마·엔티티·리포지토리까지다.** 만들지 않는 것:
- 코드에프 거래 조회 연동, 90일 페이지네이션 (T-015, 코드에프 데모 승인 대기)
- 중복 거래 판정 로직 (T-016)
- 이체 후보 탐색·자동 연결 로직 (T-017·T-018)
- 자동 카테고리 분류 (T-019)
- 거래 목록·수정 API

`transaction_edit_histories`는 이 Task가 아니다. 별도 Task다.

## 이 Task에서 처음 나오는 패턴 여섯

지금까지(T-001·T-023·T-006·T-013) 없던 것이다. 관례 복사로 처리되지 않는다.

1. **비정규화된 중복 FK** — `transactions`가 `user_id`와 `account_id`를 둘 다 갖는다
2. **자기참조 FK** — `linked_refund_transaction_id`가 같은 테이블을 가리킨다
3. **0..1 관계** — `transfer_links`가 양쪽 거래 식별자에 각각 UNIQUE를 건다
4. **`ON DELETE SET NULL`** — `sync_attempts.account_id`. 지금까지 CASCADE 아니면 NO ACTION뿐이었다
5. **부분 인덱스** — `WHERE transfer_status = 'PENDING_CONFIRM'`
6. **DESC 인덱스** — `(user_id, transacted_at DESC)` 등

## 테이블 1: `transactions`

가장 큰 테이블이다. 사용자당 최초 90일치가 한 번에 들어오고 이후 계속 쌓인다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE | **비정규화** |
| `account_id` | BIGINT | NOT NULL, FK→accounts ON DELETE CASCADE | |
| `codef_transaction_id` | VARCHAR(255) | NOT NULL | 대행사 거래 식별자 |
| `transacted_at` | TIMESTAMPTZ | NOT NULL | 거래 일시 |
| `amount` | BIGINT | NOT NULL, CHECK (amount > 0) | **항상 양수.** 방향은 `txn_type`이 정한다 |
| `merchant` | VARCHAR(255) | NULL | 거래처 |
| `txn_type` | VARCHAR(10) | NOT NULL, CHECK IN ('INCOME','EXPENSE') | |
| `category_id` | SMALLINT | NOT NULL, FK→categories, DEFAULT 99 | 미매칭은 99(미분류) |
| `initial_classification_source` | VARCHAR(20) | NOT NULL, CHECK IN ('AUTO_MATCHED','UNCLASSIFIED') | **최초 값 고정. 사용자 수정 시에도 갱신 금지** |
| `transfer_status` | VARCHAR(20) | NOT NULL, DEFAULT 'NONE', CHECK | 아래 5종 |
| `refund_status` | VARCHAR(20) | NOT NULL, DEFAULT 'NONE', CHECK IN ('NONE','ORIGINAL','REFUND') | |
| `linked_refund_transaction_id` | BIGINT | NULL, FK→transactions | **자기참조** |
| `memo` | VARCHAR(255) | NULL | |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

`transfer_status` 5종: `NONE`(이체 아님), `PENDING_CONFIRM`(후보, 사용자 확인 대기), `AUTO_LINKED`(자동 연결), `USER_CONFIRMED`(사용자가 이체로 확인), `UNLINKED`(자동 연결을 사용자가 해제).

**인덱스**

| 인덱스 | 용도 |
|---|---|
| `UNIQUE (account_id, codef_transaction_id)` | 중복 수집 방지 |
| `(user_id, transacted_at DESC)` | 거래 목록 조회, 기간별 집계 |
| `(user_id, transacted_at, category_id)` | 카테고리별 집계 |
| `(account_id, transacted_at)` | 계좌 단위 조회 |
| `(account_id, amount, transacted_at)` | 이체 후보 탐색 |
| `(transfer_status)` WHERE `transfer_status = 'PENDING_CONFIRM'` | **부분 인덱스** |

### `category_id DEFAULT 99`

99는 `categories`의 `UNCLASSIFIED`다. T-013이 시드로 넣었고(`V202609210052__create_categories.sql`) `CategorySchemaTest`가 단언으로 고정했다. **이 값을 바꾸지 마라.**

### `user_id`를 비정규화한 이유와 그 대가

집계가 전부 "사용자 + 기간" 기준이라 `accounts`를 거쳐 조인하면 모든 집계 쿼리에 조인이 하나씩 붙는다. 사용자당 거래가 수천 건 이상 쌓이는 구조라 손해가 크다.

**대가**: `account_id`와 `user_id`가 어긋날 수 있다. 설계 문서는 "거래 저장은 반드시 계좌 조회를 거친 경로로만 수행한다"고 적었지만 **DB가 막아주지 않는다.**

이번 Task에서 이 정합성을 DB 제약으로 강제할지 판단하고, 하지 않기로 한다면 그 근거를 요약에 남겨라. 복합 FK(`accounts (id, user_id)`에 UNIQUE를 걸고 `transactions (account_id, user_id)`가 그것을 참조)로 강제할 수 있으나 `accounts`에 제약을 추가해야 한다. **명세에 없는 것을 임의로 추가하지 마라.** 판단만 하고 근거를 남기면 된다.

### 환불 자기참조

원거래와 환불을 각각 행으로 저장하고 `linked_refund_transaction_id`로 잇는다. 목록에는 순액만 보이지만 **저장은 두 행 그대로** 한다. 순액은 집계 시점에 계산한다.

`ON DELETE` 절이 명세에 없다. 기본값 `NO ACTION`이다. 추측해서 CASCADE를 붙이지 마라.

## 테이블 2: `transfer_links`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, identity |
| `withdrawal_transaction_id` | BIGINT | NOT NULL, **UNIQUE**, FK→transactions ON DELETE CASCADE |
| `deposit_transaction_id` | BIGINT | NOT NULL, **UNIQUE**, FK→transactions ON DELETE CASCADE |
| `link_status` | VARCHAR(20) | NOT NULL, CHECK IN ('AUTO','USER_CONFIRMED') |
| `match_reason` | VARCHAR(255) | NULL |
| `confirmed_at` | TIMESTAMPTZ | NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |

**양쪽 거래 식별자에 각각 UNIQUE를 건다.** 하나의 출금이 여러 입금과 엮이는 상황을 DB가 막는다. 두 컬럼을 묶은 복합 UNIQUE가 **아니다** — 각각 독립 UNIQUE다. 이것을 복합으로 만들면 하나의 출금이 여러 입금과 엮이는 것이 허용된다.

## 테이블 3: `sync_attempts`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE | |
| `account_id` | BIGINT | NULL, FK→accounts **ON DELETE SET NULL** | 계좌 해제 후에도 기록은 남긴다 |
| `bank_code` | VARCHAR(10) | NOT NULL | 계좌가 지워져도 은행은 알 수 있게 |
| `trigger_type` | VARCHAR(20) | NOT NULL, CHECK IN ('SCHEDULED','MANUAL') | KPI 모수 계산용 |
| `attempted_at` | TIMESTAMPTZ | NOT NULL | |
| `result` | VARCHAR(10) | NOT NULL, CHECK IN ('SUCCESS','FAILURE') | |
| `failure_reason` | VARCHAR(500) | NULL | |
| `retry_count` | SMALLINT | NOT NULL, DEFAULT 0 | |

**인덱스**: `(account_id, attempted_at DESC)`, `(trigger_type, attempted_at)`

**append-only라 `updated_at`이 없다.** 지금까지 네 테이블이 전부 `created_at`+`updated_at`을 가졌지만 여기는 다르다. 임의로 추가하지 마라.

## 기존 코드 관례

- 패키지: `com.petgyebu.telo.{도메인}.domain` / `.repository`
- 엔티티: `@Getter` + `@NoArgsConstructor(PROTECTED)` + `Objects.requireNonNull`, `@ManyToOne(LAZY, optional = false)`
- 열거형: `VARCHAR` + `CHECK`, `@Enumerated(STRING)`, 대문자 스네이크
- `TIMESTAMPTZ` ↔ `OffsetDateTime`
- 리포지토리는 `JpaRepository`만
- 마이그레이션 파일명 `V{yyyyMMddHHmm}__{설명}.sql`, 버전은 `origin/main` 최대값보다 커야 함
- 테스트는 Testcontainers
- KST 단일 출처 `com.petgyebu.telo.common.time.AppZone`

## 검증 축 — 이번 세션에서 쌓인 것 전부 적용한다

Task를 거치며 하나씩 추가된 축이다. **처음부터 전부 넣어라.** 각각이 실제 구멍을 막았다.

| 축 | 방법 | 없으면 |
|---|---|---|
| 유니크 **범위** | 거부 케이스 + **허용 케이스** 단언 | 제약을 과도하게 좁혀도 통과 (T-023) |
| 제약 **이름** | `.rootCause().hasMessageContaining("uq_...")` | 다른 제약 위반으로 실패해도 초록 |
| 인덱스 **존재·열 구성** | `pg_indexes`의 `indexdef` | 인덱스 지워도 통과 (T-006, 트러블슈팅 17) |
| 인덱스 **종류** | `indexdef`에 `USING btree` 포함 확인 | 종류가 바뀌어도 통과 (T-013) |
| 컬럼 **타입** | `information_schema.columns.udt_name` | `SMALLINT`→`INTEGER` 통과 (T-013) |
| **`NOT NULL` 구성** | 테이블별 nullable 맵 통째 비교 | null 데이터 유입 (트러블슈팅 18) |

**DESC와 부분 인덱스는 `indexdef` 문자열이 다르다.** `(user_id, transacted_at DESC)`와 부분 인덱스의 `WHERE` 절이 단언에 포함되는지 확인하라. 기존 `assertIndex` 헬퍼가 그대로 통하지 않을 수 있다. 통하지 않으면 이 테스트에 맞게 조정하되 **기존 네 테스트 파일은 건드리지 마라.**

## 완료 기준

1. 마이그레이션 적용, `PostgresMigrationTest` 통과
2. **유니크·부분 인덱스 전부 생성 확인** (백로그 명시 기준)
3. `transfer_links`의 **두 UNIQUE가 각각 독립**임을 증명하라 — 복합 UNIQUE로 바꾸면 FAILED가 나야 한다. 하나의 출금이 여러 입금과 엮이는 것을 막는 것이 이 제약의 목적이다
4. `sync_attempts.account_id`의 **`ON DELETE SET NULL`이 실제로 동작**함을 증명하라 — 계좌를 지우면 행이 남고 `account_id`만 null이 된다. CASCADE로 바꾸면 행이 사라져 FAILED가 나야 한다
5. **부분 인덱스의 `WHERE` 절**이 단언에 포함되는지 확인하라. 조건이 빠진 전체 인덱스로 바뀌어도 통과하면 안 된다
6. `category_id`의 `DEFAULT 99`가 실제로 발화하는지 확인하라 — 이 컬럼은 다른 DEFAULT와 달리 실제 사용처가 있다(미매칭 거래)
7. 위 검증 축 6개를 세 테이블 전부에 적용
8. `./gradlew build --rerun-tasks` 통과
