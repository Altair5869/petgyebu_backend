# T-041 입력: 보상·상점 스키마와 시드

- 기능 ID: F-EZZFNU(절약 보상), F-HPWCNJ(상점)
- 브랜치: `feature/F-HPWCNJ-schema`
- 의존: T-023(완료, PR #16)
- 출처: `docs/09-db-design.md` 5.1~5.4

## 범위

테이블 4개(`credit_balances`·`reward_grants`·`shop_items`·`user_items`), 제약, 부분 유니크 인덱스까지다.

만들지 않는 것: 절약 조건 판정·크레딧 지급(T-043), 상점 목록·구매 API(T-044), 아이템 배치·해제 API, `SELECT FOR UPDATE` 잠금 로직.

## 결정: `shop_items` 시드는 넣지 않는다

백로그의 완료 기준이 "슬롯 4종 시드"라고 적었으나, **이것은 `item_type`의 CHECK 값 4종**(`WALLPAPER`·`FLOOR`·`HOUSE`·`TOY`)을 가리키는 것이지 상점 아이템 4개가 아니다.

확인 결과 문서에 있는 것은 이렇다.
- `item_type` 4종과 렌더링 순서 — 확정 (`docs/09-db-design.md` 5.2 아래 표, `docs/02-requirements-features.md:393`)
- 가격대 100~500 크레딧 — 확정 (`docs/02-requirements-features.md:390`)
- `image_url`은 Cloud Storage 경로 — 확정

문서에 **없는 것**: 실제 아이템의 `code`·`name`·`image_url`·`price_credits`. `docs/09-db-design.md` 5.3은 "시드 데이터로 관리한다(Q12)"까지만 적었다.

이미지 에셋이 없으면 `image_url`(NOT NULL)을 채울 수도 없다. **T-013의 키워드 룰과 같은 상황이다.** 테이블과 제약만 만들고 `shop_items` 행은 비운다. 지어낸 아이템이 들어오면 깨지도록 단언을 건다. 실제 시드는 에셋과 함께 T-044에서 정한다.

## 이 Task에서 처음 나오는 패턴 셋

1. **1:1 공유 PK** — `credit_balances.user_id`가 PK 겸 FK다. 별도 `id`가 없다. 지금까지 모든 테이블이 독립 PK를 가졌다
2. **M:N 연결 테이블** — `user_items`가 `users`와 `shop_items`를 잇는다. **`@ManyToMany`를 쓰면 안 된다** (아래 참고)
3. **부분 유니크 인덱스** — `UNIQUE (user_id, item_type) WHERE is_placed = TRUE`. T-014의 부분 인덱스는 일반 인덱스였고, 이번은 유니크다

## 테이블 1: `credit_balances`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `user_id` | BIGINT | **PK**, FK→users ON DELETE CASCADE |
| `balance` | BIGINT | NOT NULL, DEFAULT 0, CHECK (balance >= 0) |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |

사용자당 한 행이라 `user_id`가 곧 PK다. **별도 `id`를 두지 않는다.**

엔티티 매핑은 `@MapsId` 또는 `@Id` + `@OneToOne`을 쓴다. 어느 쪽을 골랐는지와 근거를 요약에 남겨라.

`CHECK (balance >= 0)`가 잔액 부족 구매를 DB에서 막는다. 애플리케이션 검사와 `SELECT FOR UPDATE`에 더한 마지막 방어선이다. **잠금 로직은 T-043·T-044 몫이니 만들지 마라.**

## 테이블 2: `reward_grants`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE | |
| `budget_period_id` | BIGINT | NOT NULL, FK→budget_periods ON DELETE CASCADE | 판정 대상 기간 |
| `condition_type` | VARCHAR(30) | NOT NULL, CHECK IN ('WITHIN_TARGET','SAVED_10_PERCENT') | |
| `credit_amount` | BIGINT | NOT NULL | 100 또는 200 |
| `period_expense_total` | BIGINT | NOT NULL | 판정 근거: 해당 기간 지출 합계 |
| `previous_period_expense_total` | BIGINT | NULL | 직전 기간 지출. **첫 기간은 NULL** |
| `granted_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**제약**: `UNIQUE (user_id, budget_period_id, condition_type)` — 동일 조건 중복 지급 금지. 배치가 재실행돼도 두 번 지급되지 않는다.

**두 조건을 모두 충족하면 행이 2개 생긴다.** 크레딧은 합산되어 300이 된다. 조건별 지급 사유를 보여줘야 해서 한 행에 합치지 않는다. **유니크 허용 케이스로 이것을 단언하라** — 같은 사용자·같은 기간에 `condition_type`이 다른 두 행이 저장되어야 한다.

## 테이블 3: `shop_items`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, identity |
| `code` | VARCHAR(50) | NOT NULL, UNIQUE |
| `name` | VARCHAR(100) | NOT NULL |
| `item_type` | VARCHAR(20) | NOT NULL, CHECK IN ('WALLPAPER','FLOOR','HOUSE','TOY') |
| `image_url` | VARCHAR(500) | NOT NULL |
| `price_credits` | BIGINT | NOT NULL, CHECK (price_credits BETWEEN 100 AND 500) |
| `description` | VARCHAR(255) | NULL |
| `sort_order` | SMALLINT | NOT NULL, DEFAULT 0 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE |

`is_active = false`는 판매 중단이며 **이미 산 사람의 보유는 유지된다.**

## 테이블 4: `user_items`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE | |
| `shop_item_id` | BIGINT | NOT NULL, FK→shop_items | **`ON DELETE` 절이 명세에 없다.** 기본값 NO ACTION |
| `item_type` | VARCHAR(20) | NOT NULL, CHECK IN ('WALLPAPER','FLOOR','HOUSE','TOY') | **비정규화 복사본** |
| `acquisition_type` | VARCHAR(10) | NOT NULL, CHECK IN ('PURCHASE','GRANT') | |
| `price_paid` | BIGINT | NULL | 구매 시점 가격 스냅샷 |
| `acquired_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| `is_placed` | BOOLEAN | NOT NULL, DEFAULT FALSE | 방 배치 여부 |

**제약**: `UNIQUE (user_id, shop_item_id)` — 같은 아이템 중복 구매 방지

**인덱스**: `UNIQUE (user_id, item_type) WHERE is_placed = TRUE` — **부분 유니크 인덱스.** 슬롯당 1개 규칙을 DB가 보장한다

### `item_type`을 비정규화한 이유

슬롯당 1개를 DB 제약으로 막으려면 `user_items` 자체에 `item_type`이 있어야 한다. **PostgreSQL의 부분 유니크 인덱스는 해당 테이블의 컬럼만 참조할 수 있어서 `shop_items`를 조인해 만들 수 없다.**

구매·지급으로 행을 만들 때 `shop_items.item_type`을 복사해 넣고 이후 변경하지 않는다. **엔티티 매핑에서 `updatable = false`로 막아라.**

### `@ManyToMany`를 쓰지 마라

`user_items`는 `users`와 `shop_items`를 잇는 연결 테이블이지만 **고유 데이터가 5개 붙어 있다** (`acquisition_type`, `price_paid`, `is_placed`, `item_type`, `acquired_at`). `@ManyToMany`가 만드는 연결 테이블은 FK 두 개가 전부라 이것들을 담을 수 없다.

**독립 엔티티로 만들고 `@ManyToOne` 두 개를 걸어라.** 양방향 `@OneToMany`도 걸지 마라 — 지금까지 다섯 도메인이 전부 단방향이다.

## 검증 축 6가지 — 처음부터 전부 넣는다

`docs/10-task-backlog.md` 말미의 "스키마 단언 전수 점검" 절을 읽어라. 표를 그대로 적용한다.

| 축 | 방법 |
|---|---|
| 유니크 | 거부 + **허용** + 제약 이름 |
| CHECK | 거부 + **허용** + 제약 이름 |
| 인덱스 | `pg_indexes`의 `indexdef` — 종류·열 구성·정렬 방향·**부분 조건** |
| FK | CASCADE·NO ACTION 동작 테스트 |
| 컬럼 타입 | `udt_name` + `character_maximum_length` |
| `NOT NULL` | 테이블별 nullable 맵 통째 비교 |

**같은 제약이 두 컬럼에 걸려 있으면 양쪽을 각각 확인하라.** T-014에서 `transfer_links`의 입금 쪽 CASCADE가 통째로 비어 있던 것이 이 규칙이 생긴 이유다(트러블슈팅 19번).

`TransactionSchemaTest`·`SyncAttemptSchemaTest`가 가장 최신 모델이다. `assertIndex`·`assertColumnType`·`assertNullability` 헬퍼를 그대로 가져와라. **기존 여섯 테스트 파일은 건드리지 마라.**

## 완료 기준

1. 마이그레이션 적용, `PostgresMigrationTest` 통과
2. **`(user_id, item_type) WHERE is_placed` 부분 유니크가 실제로 동작** (백로그 명시 기준)
   - 배치된 같은 슬롯 두 개는 거부된다
   - **배치되지 않은(`is_placed = FALSE`) 같은 슬롯 여러 개는 허용된다** — 부분 조건이 빠지면 이것이 막힌다. 보관함에 같은 종류 아이템을 여러 개 가질 수 있어야 한다
   - 조건을 제거한 전체 유니크로 바꾸면 FAILED가 나야 한다
3. `credit_balances`의 PK가 `user_id`임을 증명하라 — 같은 사용자로 두 행을 만들 수 없다
4. `CHECK (balance >= 0)` 동작 — 음수 거부, 0 허용
5. `reward_grants`의 유니크가 **조건별로는 허용**함을 증명하라 — 같은 사용자·같은 기간에 `condition_type`이 다른 두 행이 저장된다
6. `price_credits BETWEEN 100 AND 500` 동작 — 경계값 100·500 허용, 99·501 거부
7. 위 검증 축 6가지를 네 테이블 전부에 적용
8. `./gradlew build --rerun-tasks` 통과
