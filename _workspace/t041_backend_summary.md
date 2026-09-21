# T-041 구현 요약: 보상·상점 스키마

- 기능 ID: F-EZZFNU(절약 보상), F-HPWCNJ(상점)
- 브랜치: `feature/F-HPWCNJ-schema`
- 출처: `docs/09-db-design.md` 5.1~5.4, `_workspace/t041_00_input.md`

## 항목별 결과

- [완료] `credit_balances`·`reward_grants`·`shop_items`·`user_items` 마이그레이션: `src/main/resources/db/migration/V202609212143__create_reward_and_shop.sql` — 테이블 4개, CHECK 6개, UNIQUE 4개(부분 유니크 인덱스 1개 포함), FK 5개
- [완료] 엔티티: `CreditBalance`·`RewardGrant`·`RewardConditionType`(reward), `ShopItem`·`UserItem`·`ItemType`·`AcquisitionType`(shop)
- [완료] 리포지토리 4개: `CreditBalanceRepository`·`RewardGrantRepository`·`ShopItemRepository`·`UserItemRepository`
- [완료] 스키마 테스트: `RewardSchemaTest` 14건, `ShopSchemaTest` 19건 — 검증 축 6가지를 네 테이블 전부에 적용
- [보류] `shop_items` 시드: 아래 "문서와 다르게 결정한 것" 1번 참고. 근거 없는 아이템이 들어오면 깨지도록 단언을 걸어 두었다

## 만든 파일

**마이그레이션**

- `src/main/resources/db/migration/V202609212143__create_reward_and_shop.sql`

**엔티티·리포지토리**

- `src/main/java/com/petgyebu/telo/reward/domain/CreditBalance.java`
- `src/main/java/com/petgyebu/telo/reward/domain/RewardGrant.java`
- `src/main/java/com/petgyebu/telo/reward/domain/RewardConditionType.java`
- `src/main/java/com/petgyebu/telo/reward/repository/CreditBalanceRepository.java`
- `src/main/java/com/petgyebu/telo/reward/repository/RewardGrantRepository.java`
- `src/main/java/com/petgyebu/telo/shop/domain/ShopItem.java`
- `src/main/java/com/petgyebu/telo/shop/domain/UserItem.java`
- `src/main/java/com/petgyebu/telo/shop/domain/ItemType.java`
- `src/main/java/com/petgyebu/telo/shop/domain/AcquisitionType.java`
- `src/main/java/com/petgyebu/telo/shop/repository/ShopItemRepository.java`
- `src/main/java/com/petgyebu/telo/shop/repository/UserItemRepository.java`

**테스트**

- `src/test/java/com/petgyebu/telo/reward/RewardSchemaTest.java`
- `src/test/java/com/petgyebu/telo/shop/ShopSchemaTest.java`

**기존 여섯 스키마 테스트 파일은 건드리지 않았다.** `git status`에 수정된 파일이 없고 새 파일만 나온다.

## 문서와 다르게 결정한 것과 이유

### 1. `shop_items` 시드를 넣지 않았다

백로그의 완료 기준은 "슬롯 4종 시드"라고 적었으나 이것은 `item_type`의 CHECK 값 4종
(`WALLPAPER`·`FLOOR`·`HOUSE`·`TOY`)을 가리키는 것이지 상점 아이템 4개가 아니다.

문서에 있는 것은 슬롯 4종과 렌더링 순서, 가격대 100~500, `image_url`이 Cloud Storage
경로라는 사실까지다. 실제 아이템의 `code`·`name`·`image_url`·`price_credits`는 어디에도
없고 `docs/09-db-design.md` 5.3은 "시드 데이터로 관리한다(Q12)"까지만 적었다. `image_url`이
NOT NULL이라 이미지 에셋 없이는 채울 수도 없다. T-013의 키워드 룰과 같은 상황이다.

대신 **지어낸 아이템이 마이그레이션으로 들어오면 깨지는 단언**을 걸었다
(`ShopSchemaTest.shopItemsAreNotSeeded`). 실제 시드는 에셋과 함께 T-044에서 정한다.

### 2. `credit_balances`는 `@MapsId`를 골랐다

명세가 `@MapsId` 또는 `@Id` + `@OneToOne` 중 하나를 쓰라고 했다. `@MapsId`를 골랐다.

`@Id @OneToOne User user`로 매핑하면 엔티티의 식별자 타입이 `User`가 되어 리포지토리가
`JpaRepository<CreditBalance, User>`가 된다. 그러면 `findById(userId)`에 사용자 ID를 바로
넘길 수 없고 `User` 인스턴스를 먼저 만들어야 한다. T-043(보상 지급)·T-044(구매)가 사용자
ID로 잔액 행을 조회하고 `SELECT ... FOR UPDATE`로 잠그는 경로라 식별자는 `Long`이어야 한다.

`@MapsId`는 `Long userId` 필드를 식별자로 두고 연관에서 값을 끌어오므로, 컬럼은
`user_id` 하나뿐이면서(PK 겸 FK) 식별자 타입은 `Long`이다. 양쪽 요구를 모두 만족한다.
`RewardSchemaTest.creditBalanceIdentifierIsUserId`가 이것을 못 박는다.

### 3. `user_items`는 `@ManyToMany`를 쓰지 않았다

연결 테이블이지만 고유 데이터가 5개 붙어 있어(`item_type`·`acquisition_type`·`price_paid`·
`acquired_at`·`is_placed`) `@ManyToMany`가 만드는 FK 두 개짜리 연결 테이블로는 담을 수 없다.
독립 엔티티로 만들고 `@ManyToOne`을 둘 걸었다. **반대편 `@OneToMany`는 걸지 않았다** —
기존 다섯 도메인이 전부 단방향이다.

`item_type`은 `@Column(updatable = false)`로 막았다. 생성자가 이 값을 인자로 받지 않고
`shopItem.getItemType()`에서 복사한다. 호출자가 다른 값을 넘길 여지를 두면 두 값이 어긋나
슬롯당 1개 규칙이 엉뚱한 슬롯에 걸린다.

### 4. `user_items.shop_item_id`의 FK에 `ON DELETE` 절을 붙이지 않았다

명세에 없으므로 기본값 `NO ACTION`이다. 다른 FK가 CASCADE라고 따라 붙이지 않았다. 상점
아이템은 `is_active = false`로 판매 중단만 하고 삭제하지 않으며, 여기에 CASCADE를 붙이면
상점에서 아이템을 지우는 순간 이미 산 사람들의 보유가 조용히 사라진다.

## 검증 축 6가지 적용 현황

| 축 | `credit_balances` | `reward_grants` | `shop_items` | `user_items` |
|---|---|---|---|---|
| 유니크 (거부+허용+이름) | PK 중복 거부 | 거부 + 조건별 허용 + `uq_reward_grants_user_period_condition` | 거부 + 허용 + `uq_shop_items_code` | 거부 + 허용 + `uq_user_items_user_shop_item`, 부분 유니크 거부+허용 |
| CHECK (거부+허용+이름) | 음수 거부 / 0·양수 허용 | 미정의 거부 / 2종 허용 | `item_type` 4종, `price_credits` 경계값 | `item_type`·`acquisition_type` 각각 |
| 인덱스 (`indexdef`) | `credit_balances_pkey` | `uq_reward_grants_user_period_condition` | `uq_shop_items_code` | 유니크 2개 + **부분 조건 `(is_placed = true)`** |
| FK 동작 | users CASCADE | users CASCADE + budget_periods CASCADE **각각** | — | users CASCADE + shop_items **NO ACTION** |
| 컬럼 타입 (`udt_name`+길이) | 3열 | 7열 | 9열 | 8열 |
| `NOT NULL` 맵 통째 비교 | O | O | O | O |

**같은 제약이 두 컬럼에 걸린 곳은 양쪽을 각각 확인했다**(트러블슈팅 19번 규칙).
`reward_grants`의 두 FK가 그렇다. 사용자를 지우면 `budget_periods`도 함께 지워져 `user_id`
쪽 CASCADE만으로도 결과가 같아지므로, **예산 기간만 지우는 경로**를 따로 두었다. 이것이
없으면 `budget_period_id` 쪽 CASCADE가 통째로 비어도 드러나지 않는다.

## 실행한 검증 명령과 실제 출력

### 전체 빌드

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 26s
7 actionable tasks: 7 executed
```

테스트 119건, 실패 0, 에러 0.

```
  1 tests  com.petgyebu.telo.TeloApplicationTests
 12 tests  com.petgyebu.telo.account.AccountSchemaTest
 13 tests  com.petgyebu.telo.budget.BudgetSchemaTest
 11 tests  com.petgyebu.telo.category.CategorySchemaTest
  1 tests  com.petgyebu.telo.codef.EasyCodefUtilJdk25Test
  5 tests  com.petgyebu.telo.common.time.AppZoneTest
  2 tests  com.petgyebu.telo.migration.PostgresMigrationTest
 14 tests  com.petgyebu.telo.reward.RewardSchemaTest
 19 tests  com.petgyebu.telo.shop.ShopSchemaTest
  9 tests  com.petgyebu.telo.sync.SyncAttemptSchemaTest
 23 tests  com.petgyebu.telo.transaction.TransactionSchemaTest
  9 tests  com.petgyebu.telo.user.UserSchemaTest
TOTAL 119 tests, 0 failures, 0 errors
```

### 양방향 검증 — 마이그레이션 변이 8종

각 변이를 하나씩 넣고 해당 테스트 클래스를 돌린 뒤 원복했다. **8종 전부 FAILED가 났다.**

#### 완료 기준 2 — 부분 유니크 인덱스 (변이 둘)

`WHERE is_placed = TRUE` 조건 제거 → 전체 유니크가 된다.

```
=== MUTATION: 2a-drop-partial-condition => gradle exit 1
tests/failures/errors: ('19', '2', '0')
  FAILED: 배치하지 않은 같은 슬롯 아이템은 여러 개 보유할 수 있다 — 부분 조건이 살아 있다
          [배치하지 않은 같은 슬롯 아이템이 거부됐다. 유니크 인덱스에서 WHERE is_placed = TRUE 조건이 빠졌을 가능성이 높다]
          Expecting code not to raise a throwable but caught
            "org.springframework.dao.DuplicateKeyException: ... INSERT INTO user_items ..."
  FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·부분 조건까지
          Expecting actual:
            "CREATE UNIQUE INDEX uq_user_items_user_item_type_placed ON public.user_items USING btree (user_id, item_type)"
          to end with:
            "USING btree (user_id, item_type) WHERE (is_placed = true)"
```

`UNIQUE`를 떼어 일반 인덱스로 바꿈 → 배치 중복이 통과한다.

```
=== MUTATION: 2b-drop-slot-unique => gradle exit 1
tests/failures/errors: ('19', '1', '0')
  FAILED: 배치된 같은 슬롯 아이템 두 개는 거부된다 — 부분 유니크 인덱스
          java.lang.AssertionError: Expecting code to raise a throwable.
```

두 변이가 **서로 다른 테스트에 걸린다.** 조건만 빼면 허용 테스트가, 유니크만 빼면 거부
테스트가 깨진다. 한쪽만 있으면 다른 쪽 변이가 그대로 새어 나간다.

#### 완료 기준 3 — `credit_balances`의 PK가 `user_id`다

`PRIMARY KEY`를 `NOT NULL`로 바꿈.

```
=== MUTATION: 3-credit-balance-no-pk => gradle exit 1
tests/failures/errors: ('14', '2', '0')
  FAILED: 한 사용자에 잔액 행은 하나뿐이다 — user_id가 곧 PK다
          java.lang.AssertionError: Expecting code to raise a throwable.
  FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지
          [credit_balances 테이블에 인덱스 credit_balances_pkey가 없다]
          Expected size: 1 but was: 0 in: []
```

#### 완료 기준 4 — `CHECK (balance >= 0)`

`>= 0`을 `> 0`으로 바꿈. 잔액 0이 거부된다.

```
=== MUTATION: 4-balance-strictly-positive => gradle exit 1
tests/failures/errors: ('14', '2', '0')
  FAILED: 잔액 0과 양수는 저장된다 — CHECK가 과도하게 좁지 않다
          [잔액 0 또는 양수가 거부됐다. CHECK가 balance > 0으로 적혀 있을 가능성이 높다]
  FAILED: @MapsId 매핑이 user_id 하나를 PK 겸 FK로 쓴다 — 별도 id 컬럼이 생기지 않는다
          ERROR: new row for relation "credit_balances" violates check constraint "ck_credit_balances_balance_non_negative"
```

#### 완료 기준 5 — `reward_grants`의 유니크가 조건별로는 허용한다

유니크 키에서 `condition_type` 제거.

```
=== MUTATION: 5-unique-without-condition-type => gradle exit 1
tests/failures/errors: ('14', '3', '0')
  FAILED: 조건이 다르면 같은 사용자·같은 기간에도 두 행이 남는다 — 합산 300크레딧
          org.springframework.dao.DuplicateKeyException: ... ERROR: duplicate key value violates unique constraint "uq_reward_grants_user_period_condition"
  FAILED: 명세에 있는 condition_type 두 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다
  FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지
          Expecting actual:
            "CREATE UNIQUE INDEX uq_reward_grants_user_period_condition ON public.reward_grants USING btree (user_id, budget_period_id)"
          to end with:
            "USING btree (user_id, budget_period_id, condition_type)"
```

#### 완료 기준 6 — `price_credits BETWEEN 100 AND 500`

`BETWEEN 101 AND 499`로 바꿈.

```
=== MUTATION: 6-price-range-exclusive => gradle exit 1
tests/failures/errors: ('19', '9', '0')
  FAILED: 가격은 100~500 크레딧이다 — 경계값 100·500은 허용, 99·501은 거부
          [경계값 100 또는 500이 거부됐다. BETWEEN이 양끝을 포함하지 않는다]
          ERROR: new row for relation "shop_items" violates check constraint "ck_shop_items_price_credits"
```

이 변이만 실패가 9건으로 번진다. `ShopSchemaTest`의 아이템 생성 헬퍼가 대부분 가격 100을
쓰기 때문에 다른 테스트의 준비 단계까지 함께 깨진다. 의도한 테스트가 의도한 메시지로 깨지므로
그대로 두었다.

#### 시드 없음 단언 — 지어낸 아이템이 들어오면 깨진다

마이그레이션에 그럴듯한 아이템 한 줄(`'wallpaper_basic', '기본 벽지', 'WALLPAPER', ... 100`)을 추가.

```
=== MUTATION: 7-fabricated-seed => gradle exit 1
tests/failures/errors: ('19', '1', '0')
  FAILED: 마이그레이션은 shop_items에 아무 행도 넣지 않는다
          [shop_items에 시드 행이 들어와 있다. 근거 없는 아이템 정의가 추가됐다]
          expected: 0 but was: 1
```

#### `shop_item_id` FK가 NO ACTION이다

`ON DELETE CASCADE`를 붙임.

```
=== MUTATION: 8-shop-item-fk-cascade => gradle exit 1
tests/failures/errors: ('19', '1', '0')
  FAILED: 보유자가 있는 상점 아이템은 지워지지 않는다 — shop_item_id FK는 NO ACTION이다
          java.lang.AssertionError: Expecting code to raise a throwable.
```

변이 후 마이그레이션 파일은 매번 원복했고, 마지막에 `git status`로 수정된 추적 파일이
없음을 확인했다.

### 마이그레이션 순서 검사 (커밋 후)

```
$ scripts/check-migration-order.sh
기준: origin/main (최대 버전 202609211227)
  [통과] V202609212143__create_reward_and_shop.sql (버전 202609212143)
마이그레이션 순서 검사 통과.
```

## 남긴 미해결 사항

1. **`shop_items` 시드가 비어 있다.** T-044에서 이미지 에셋과 함께 실제 아이템을 정의해야
   한다. 그때 `ShopSchemaTest.shopItemsAreNotSeeded`를 실제 시드 검증으로 교체한다.
2. **`SELECT ... FOR UPDATE` 잠금 로직이 없다.** `docs/09-db-design.md` 5.1이 요구하는 행
   잠금은 T-043·T-044 범위다. 지금은 `CHECK (balance >= 0)`만 마지막 방어선으로 서 있다.
3. **Spring Batch 메타 테이블은 이 마이그레이션에 없다.** `docs/09-db-design.md` 6장이
   Sprint 6 묶음에 함께 넣었지만 T-041 범위 밖이라 만들지 않았다.
4. **`user_items` 배치/해제 API가 없다.** 슬롯당 1개 규칙은 지금 DB 제약만 보장한다.
   애플리케이션 경로는 T-044에서 만든다.

## 트러블슈팅 기록

**추가한 항목이 없다.** 이번 작업에서 관측된 실패가 없었다. 처음 실행한
`./gradlew test`부터 통과했고 변이 8종도 예상대로 FAILED가 났다.

작업 중 실행 전에 스스로 고친 것이 둘 있었으나 `CLAUDE.md`의 기록 기준에 맞지 않아 남기지
않았다. 증상이 관측된 적이 없기 때문이다.

- `ShopItem`의 `is_active` 필드를 `active`로 쓰면 컬럼명이 `active`가 되어 `validate`가
  즉시 죽는다 — "원인이 즉시 보이는 것"에 해당한다
- 시드 없음 단언을 처음에 `id <= 100`으로 썼는데, 같은 클래스의 다른 테스트가 만든 행이
  걸려 실행 순서에 따라 결과가 달라진다. 테스트가 만드는 행에 `t041-` 접두사를 붙이고
  `code NOT LIKE 't041-%'`로 바꿨다 — 실행 전에 발견해 증상이 없었다
