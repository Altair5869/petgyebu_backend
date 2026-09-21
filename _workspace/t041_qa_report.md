# T-041 QA 리포트: 보상·상점 스키마와 시드

- 기능 ID: F-EZZFNU(절약 보상), F-HPWCNJ(상점)
- 대상: 브랜치 `feature/F-HPWCNJ-schema`, 커밋 `2a29f35` (**푸시 전**)
- 검증자 실행 환경: 원본 저장소 + 격리 워크트리(변이 전용, 검증 후 제거)

## 판정 요약

**PASS 14 / FIX 3 / REDO 0 / 미검증 0**

스키마·엔티티 자체에는 결함이 없다. 마이그레이션은 `docs/09-db-design.md` 5.1~5.4와 컬럼·타입·NULL·DEFAULT·CHECK·UNIQUE·인덱스가 한 줄도 어긋나지 않고, 엔티티 매핑도 일치한다. 자기 보고의 변이 8종 중 재현한 것은 전부 재현됐고, 완료 기준 2의 "두 변이가 서로 다른 테스트에 걸린다"는 주장도 사실이다. **푸시를 막을 사유는 없다.**

FIX 3건은 전부 테스트 커버리지 또는 주석이며, 그중 둘은 **엔티티 변이가 통째로 새어 나가는 구멍**이다.

---

## 1. 마이그레이션 SQL ↔ `docs/09-db-design.md` 5.1~5.4
- 판정: PASS
- 검증 대상: `V202609212143__create_reward_and_shop.sql` vs `docs/09-db-design.md` 5.1~5.4 표
- 사유: 네 테이블 29개 컬럼을 한 줄씩 대조했다. 어긋난 항목이 없다.
  - `credit_balances`(SQL:6-13) — `user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE`, `balance BIGINT NOT NULL DEFAULT 0` + `CHECK (balance >= 0)`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`. 설계대로 **독립 `id`가 없고** `GENERATED ALWAYS AS IDENTITY`도 없다.
  - `reward_grants`(SQL:15-36) — 8열 전부 일치. `previous_period_expense_total`만 NULL 허용(SQL:27), `condition_type VARCHAR(30)` + CHECK 2값(SQL:29-30), `UNIQUE (user_id, budget_period_id, condition_type)`(SQL:34-35). 두 FK 모두 `ON DELETE CASCADE`.
  - `shop_items`(SQL:38-59) — 9열 전부 일치. `description`만 NULL 허용, `sort_order SMALLINT DEFAULT 0`, `is_active BOOLEAN DEFAULT TRUE`, `CHECK (price_credits BETWEEN 100 AND 500)`, `UNIQUE (code)`.
  - `user_items`(SQL:66-87) — 8열 전부 일치. `price_paid`만 NULL 허용, `shop_item_id`의 FK에 `ON DELETE` 절 없음(= NO ACTION, 설계가 침묵한 그대로), CHECK 2개, `UNIQUE (user_id, shop_item_id)`.
  - 부분 유니크 인덱스(SQL:93-95) — `CREATE UNIQUE INDEX ... ON user_items (user_id, item_type) WHERE is_placed = TRUE`. 설계 5.4의 인덱스 항목과 동일.

## 2. 엔티티 매핑 ↔ 스키마
- 판정: PASS
- 검증 대상: `CreditBalance`·`RewardGrant`·`ShopItem`·`UserItem` vs 마이그레이션
- 사유: 타입·길이·nullable·updatable이 전부 맞는다. `VARCHAR` 길이를 엔티티가 `length`로 못 박아(`RewardGrant:55` 30, `ShopItem:43/46/50/54/61` 50/100/20/500/255, `UserItem:72/76` 20/10) Hibernate `validate`와 이중으로 고정된다. `sort_order SMALLINT` ↔ `private short sortOrder`(`ShopItem:65`)도 맞다. `is_active`·`is_placed`는 이 프로젝트에서 처음 나오는 `is_` 접두 컬럼이라 `@Column(name = ...)`을 명시했다(`ShopItem:68`, `UserItem:88`) — 기존 여섯 엔티티에는 `name=`이 한 번도 없었으나, 컬럼명이 설계에서 고정된 이상 명시가 맞다.
- 참고: `PostgresMigrationTest`(운영 프로필 + `ddl-auto: validate`)가 통과하므로 매핑 누락은 구조적으로도 막혀 있다.

## 3. 빌드 — 검증자가 직접 실행
- 판정: PASS
- 실행: `./gradlew build --rerun-tasks` (원본 저장소, 커밋 `2a29f35` 상태)

```
BUILD SUCCESSFUL in 26s
7 actionable tasks: 7 executed
```

테스트 결과 XML을 직접 집계했다. 자기 보고의 119건과 일치한다.

```
  1 tests  com.petgyebu.telo.TeloApplicationTests
 12 tests  accounts 스키마 제약 검증
 13 tests  budget_periods·status_thresholds 스키마 제약 검증
 11 tests  categories·merchant_keyword_rules 스키마 제약 검증
  1 tests  com.petgyebu.telo.codef.EasyCodefUtilJdk25Test
  5 tests  기준 타임존 KST 규칙
  2 tests  운영 프로필(PostgreSQL + Flyway + validate) 기동 검증
 14 tests  credit_balances·reward_grants 스키마 제약 검증
 19 tests  shop_items·user_items 스키마 제약 검증
  9 tests  sync_attempts 스키마 제약 검증
 23 tests  transactions·transfer_links 스키마 제약 검증
  9 tests  users·user_consents 스키마 제약 검증
TOTAL 119 failures 0 errors 0
```

마이그레이션 순서도 직접 확인했다.

```
$ ./scripts/check-migration-order.sh
기준: origin/main (최대 버전 202609211227)
  [통과] V202609212143__create_reward_and_shop.sql (버전 202609212143)
마이그레이션 순서 검사 통과.
```

## 4. 검증 축 6가지 — 네 테이블 전수 확인
- 판정: PASS
- 검증 대상: `RewardSchemaTest`·`ShopSchemaTest` vs `docs/10-task-backlog.md`의 "스키마 단언 전수 점검" 표
- 사유: 검증자가 표를 다시 그려 대조했다. **빠진 칸이 없다.** T-014 QA가 새 축으로 제안했던 두 가지(CHECK 허용 케이스, `character_maximum_length`)가 처음부터 들어와 있다.

| 축 | `credit_balances` | `reward_grants` | `shop_items` | `user_items` |
|---|---|---|---|---|
| 유니크 거부 | O `credit_balances_pkey` (PK 중복) | O | O | O (일반 + 부분 유니크) |
| 유니크 **허용** | — (PK라 허용 케이스 없음) | O 조건별 2행 | O 다른 code | O 다른 아이템·다른 사용자, 미배치 다중, 다른 슬롯 배치 |
| 유니크 제약 이름 | O | O | O | O ×2 |
| CHECK 거부 | O 음수 | O `SAVED_20_PERCENT` | O `CURTAIN`·99·501 | O `CURTAIN`·`REFUND` |
| CHECK **허용** | O 0·양수 | O enum 2값 순회 | O 슬롯 4종 순회 + 경계값 100·500 | O 슬롯 4종(`placedItemsInDifferentSlotsCoexist`) + `PURCHASE`/`GRANT` |
| CHECK 제약 이름 | O | O | O ×2 | O ×2 |
| 인덱스 `indexdef` | O `credit_balances_pkey` | O 3열 유니크 | O `uq_shop_items_code` | O 2개 + **부분 조건 `(is_placed = true)`** |
| FK 동작 | O users CASCADE | O users CASCADE + **budget_periods CASCADE 별도 경로** | — (FK 없음) | O users CASCADE + shop_items **NO ACTION** |
| 컬럼 타입 `udt_name` | O 3열 | O 7열 | O 9열 | O 8열 |
| `character_maximum_length` | — (VARCHAR 없음) | O 30 | O 50/100/20/500/255 | O 20/10 |
| `NOT NULL` 맵 통째 | O | O | O | O |

- "같은 제약이 두 컬럼에 걸리면 양쪽을 각각" 규칙(트러블슈팅 19번)도 지켜졌다. `reward_grants`의 두 FK, `user_items`의 두 FK가 각각 따로 검증된다.

## 5. 완료 기준 2 — 부분 유니크 인덱스 (격리 워크트리 재현)
- 판정: PASS
- 검증 대상: `t041_backend_summary.md:135-163`의 주장 vs 검증자 재현
- 사유: 두 변이를 검증자가 직접 넣고 `ShopSchemaTest`만 돌렸다. **주장대로 서로 다른 테스트에 걸린다.**

```
=== MUTATION A-drop-partial-condition => exit 1; tests=19 failures=2 errors=0
   FAILED: 배치하지 않은 같은 슬롯 아이템은 여러 개 보유할 수 있다 — 부분 조건이 살아 있다
   FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·부분 조건까지

=== MUTATION B-drop-unique-keep-index => exit 1; tests=19 failures=1 errors=0
   FAILED: 배치된 같은 슬롯 아이템 두 개는 거부된다 — 부분 유니크 인덱스
           java.lang.AssertionError: Expecting code to raise a throwable.
```

- 교집합이 없다. A는 허용 테스트 + `indexdef`, B는 거부 테스트 하나뿐이다.
- **검증자가 추가로 확인한 것**: B(`CREATE UNIQUE INDEX` → `CREATE INDEX`)를 `assertIndex`가 잡지 못한다. `endsWith`로 꼬리만 비교하는데 `UNIQUE`는 문자열 **앞쪽**에 있기 때문이다. 즉 B는 **거부 테스트 하나에만 매달려 있다.** 그 테스트가 사라지면 유니크가 통째로 풀려도 초록불이 된다. 지금은 그 테스트가 있으므로 PASS지만, 헬퍼의 한계로 기록해 둔다.
- 검증자가 추가한 세 번째 변이 — 조건을 `WHERE is_placed = FALSE`로 뒤집기: 3건 실패(거부·허용·`indexdef` 전부). 잡힌다.

## 6. backend-engineer 판단 1 — `@MapsId` 선택
- 판정: PASS (결론), 근거 서술은 아래 단서 참고
- 검증 대상: `t041_backend_summary.md:57-68`, `CreditBalance.java:25-29` vs 검증자 실험

- 사유: **검증자가 직접 대안을 구현해 확인했다.** 격리 워크트리에서 `CreditBalance`를 `@MapsId` 없이 `@Id @OneToOne(fetch = LAZY) @JoinColumn(name = "user_id") private User user;`로 바꾸고(편의 `getUserId()` 추가로 테스트는 그대로 컴파일), `CreditBalanceRepository extends JpaRepository<CreditBalance, Long>`은 손대지 않은 채 `RewardSchemaTest`를 돌렸다. **애플리케이션 컨텍스트가 아예 뜨지 않는다.**

```
=== MUTATION E4 (@Id @OneToOne, 리포지토리 식별자는 Long 유지) => BUILD FAILED
tests 14 failures 14 errors 0
java.lang.IllegalStateException: Failed to load ApplicationContext ... RewardSchemaTest
  Caused by: org.springframework.beans.factory.BeanCreationException (creditBalanceRepository)
    Caused by: java.lang.IllegalArgumentException at AbstractIdentifiableType.java:218
```

  `AbstractIdentifiableType:218`은 Hibernate 메타모델이 **요청한 식별자 타입과 실제 식별자 타입이 다를 때** 던지는 지점이다. Spring Data가 `JpaRepository<CreditBalance, Long>`의 `Long`을 메타모델에 조회하다 죽는다. 즉 `@Id @OneToOne`을 쓰면 리포지토리를 `JpaRepository<CreditBalance, User>`로 바꿔야 하고, 그러면 `findById(userId)`에 사용자 ID를 바로 넘길 수 없다는 **자기 보고의 서술이 실측으로 확인됐다.** 추측이 아니다.
- T-043·T-044가 사용자 ID로 잔액을 조회·잠근다는 전제도 문서가 뒷받침한다(`docs/08-feature-implementation-map.md`의 "크레딧 잔액 갱신에 `SELECT FOR UPDATE`를 쓴다", `docs/09-db-design.md` 5.1). `@MapsId`가 컬럼 하나(`user_id`)를 PK 겸 FK로 유지하면서 식별자만 `Long`으로 남기므로 양쪽 요구를 다 만족한다. `creditBalanceIdentifierIsUserId`(`RewardSchemaTest:92-104`)가 `findById(user.getId())`로 이것을 못 박는다.

## 7. backend-engineer 판단 2 — `budget_period_id` 쪽 CASCADE에 별도 경로가 필요했다
- 판정: PASS (통찰이 맞다)
- 검증 대상: `t041_backend_summary.md:98-101`, `RewardSchemaTest.deletingBudgetPeriodCascadesToRewardGrants`(`:252-268`) vs 검증자 변이
- 사유: `budget_periods` 쪽 CASCADE**만** 떼고 `RewardSchemaTest`를 돌렸다. 해당 테스트 하나가 정확히 걸린다.

```
=== MUTATION C-budget-period-fk-no-cascade => exit 1; tests=14 failures=1 errors=0
   FAILED: 예산 기간을 지우면 지급 기록도 함께 지워진다 — budget_period_id FK의 ON DELETE CASCADE
           org.springframework.dao.DataIntegrityViolationException: ... DELETE FROM budget_periods ...
```

  주장의 핵심("사용자 삭제는 `budget_periods`도 함께 지우므로 `user_id` 쪽 CASCADE만으로 결과가 같아진다")도 사실이다. `deletingUserCascadesToRewardGrants` 하나만 있었다면 이 변이가 통째로 새어 나간다. **T-014의 `transfer_links` 입금 쪽 CASCADE 누락(트러블슈팅 19번)과 같은 계열을 미리 막은 것이 맞다.**
- 검증자가 대칭 항목도 확인했다: `credit_balances`의 CASCADE만 제거 → `사용자를 지우면 잔액 행도 함께 지워진다` 1건 실패. 양쪽 다 무방비가 아니다.

## 8. backend-engineer 판단 3 — `UserItem` 생성자가 `itemType`을 인자로 받지 않는다
- 판정: PASS (설계는 맞다). 단 같은 생성자의 **다른 줄**에 구멍이 있다 → 11번 FIX-1 참고
- 검증 대상: `UserItem.java:100-113`, `t041_backend_summary.md:77-79`
- 사유: 생성자가 `this.itemType = shopItem.getItemType()`(`UserItem:108`)로만 값을 얻는다. 호출자가 다른 값을 넘길 **경로 자체가 없다.** 비정규화 복사본이 어긋날 여지를 언어 수준에서 막는 것이 맞고, `updatable = false`(`UserItem:72`)가 이후 UPDATE도 막는다.
- 검증자 변이: `updatable = false`를 제거 → `denormalizedItemTypeIsNotUpdatable` 1건 실패. 리플렉션 단언이 실효가 있다.
- 다만 `updatable = false`는 **JPA 경로만** 막는다. DB에는 `item_type`을 바꾸는 것을 막는 제약이 없어 raw SQL UPDATE로는 어긋날 수 있다. 설계(`09-db-design.md` 5.4)가 "엔티티 매핑에서 막는다"까지만 요구했으므로 범위 내 처리로 본다.

## 9. backend-engineer 판단 4 — `shop_item_id` FK를 NO ACTION으로 둔 것
- 판정: PASS
- 검증 대상: `t041_backend_summary.md:81-85`, SQL:71 vs `docs/09-db-design.md` 5.3·5.4
- 사유: 설계 5.4의 `shop_item_id` 행에 `ON DELETE` 절이 없다. 명세가 침묵한 곳에 다른 FK를 따라 CASCADE를 붙이지 않은 것이 옳다. 설계 5.3이 "`is_active = false`는 판매 중단이며 이미 산 사람의 보유는 유지된다"고 명시하므로, CASCADE는 그 문장과 정면으로 충돌한다. `deletingOwnedShopItemIsRejected`(`ShopSchemaTest:346-361`)가 동작을 못 박는다.

## 10. backend-engineer 판단 5 — `shop_items` 시드를 넣지 않은 것
- 판정: PASS
- 검증 대상: 검증자가 `docs/` 전체를 직접 검색
- 사유: `code`·`name`·`image_url`·`price_credits`를 가진 실제 아이템 목록이 **어느 문서에도 없다**는 주장이 사실이다. 검증자가 `docs/*.md` 전체에서 `price_credits|image_url|벽지|장난감|크레딧`을 검색했다. 나온 것은 전부 슬롯 4종·가격대(100~500)·렌더링 순서뿐이다.
  - `docs/02-requirements-features.md:390, 393`, `docs/04-review-log.md:551-556`, `docs/09-db-design.md` 5.2 아래 표 — 슬롯 4종과 가격대까지만.
  - `docs/06-sprint-plan.md:190` — "상점 아이템 시드 데이터 (100~500 크레딧 등급별)"라고만 적었고 구체 목록이 없다.
  - **검증자가 찾은 독립 근거**: `docs/06-sprint-plan.md:195`가 Sprint 6의 **"선행 조건(코드 작업 아님): 꾸미기 아이템 에셋 설계"**를 명시한다. 사용자 메모리의 "외부 선행 작업 3건 미착수"와도 일치한다. `image_url`이 NOT NULL인 이상 에셋 없이는 시드를 채울 수 없다는 주장은 문서가 뒷받침한다.
  - 백로그 완료 기준의 "슬롯 4종 시드"를 `item_type` CHECK 값 4종으로 읽은 해석도, `docs/09-db-design.md` 5.3이 "시드 데이터로 관리한다(Q12)"까지만 적었다는 서술도 사실이다.
- `shopItemsAreNotSeeded`(`ShopSchemaTest:71-85`)가 지어낸 시드를 거부한다는 것도 자기 보고의 변이 7이 증명했고, 테스트가 만드는 행에 `t041-` 접두사를 붙여 실행 순서 의존을 제거한 것도 확인했다(`ShopSchemaTest:536`).

## 11. 검증자가 직접 찾은 변이 — 미검출 2종
- 판정: **FIX**
- 검증 대상: 검증자가 설계한 변이 14종 vs `RewardSchemaTest`·`ShopSchemaTest`
- 사유: 지시받은 후보 9종을 포함해 14종을 돌렸다. DB 제약 변이는 **전부 잡힌다.** 잡히지 않은 것은 **엔티티 변이 2종**과 DEFAULT 4종이다.

| 변이 | 결과 | 걸린 테스트 |
|---|---|---|
| A 부분 조건 `WHERE is_placed = TRUE` 제거 | 잡힘 (2건) | 허용 테스트 + `indexdef` |
| B `UNIQUE` 제거(인덱스는 유지) | 잡힘 (1건) | 배치 거부 테스트 |
| C `budget_period_id` CASCADE만 제거 | 잡힘 (1건) | 기간 삭제 CASCADE 테스트 |
| D1 `UNIQUE (user_id, shop_item_id)` 제거 | 잡힘 (2건) | 중복 구매 거부 + `indexdef` |
| D2 `credit_balances.balance` DEFAULT 0 → 999 | **미검출** | — |
| D3 `is_placed` DEFAULT FALSE → TRUE | **미검출** | — |
| D4 `price_paid`를 NOT NULL로 | 잡힘 (2건) | `GRANT` 허용 + nullable 맵 |
| D5 `user_items.item_type` CHECK에서 `TOY` 제거 | 잡힘 (5건) | 4슬롯 배치 등 |
| D6 `ck_user_items_acquisition_type` 통째 삭제 | 잡힘 (1건) | `REFUND` 거부 |
| D7 `sort_order` DEFAULT 0 → 5 | **미검출** | — |
| D8 `is_active` DEFAULT TRUE → FALSE | **미검출** | — |
| D9 부분 조건을 `is_placed = FALSE`로 뒤집기 | 잡힘 (3건) | 거부·허용·`indexdef` |
| D10 `shop_items.item_type` CHECK에서 `HOUSE` 제거 | 잡힘 (6건) | 슬롯 4종 허용 등 |
| D11 `credit_balances`의 CASCADE 제거 | 잡힘 (1건) | 잔액 CASCADE 테스트 |
| **E1 `UserItem` 생성자 `isPlaced = false` → `true`** | **미검출** | — |
| **E2 `RewardGrant` 생성자가 판정 근거 두 값을 뒤바꿔 저장** | **미검출** | — |
| E3 `UserItem.itemType`의 `updatable = false` 제거 | 잡힘 (1건) | 리플렉션 단언 |

**DEFAULT 4종(D2·D3·D7·D8)은 지적이 아니다.** `docs/10-task-backlog.md`의 "단언하지 않기로 한 것" 표가 DEFAULT 18개를 "엔티티가 항상 값을 채워 DEFAULT가 발화하지 않는다"는 근거로 제외했고, 검증자가 네 엔티티를 확인한 결과 실제로 일곱 DEFAULT 모두 엔티티가 값을 채운다. 정책에 맞는 상태다. 다만 같은 표가 **"raw SQL이나 배치 INSERT가 생기면 그때 재검토한다"**고 적었는데, **T-044의 `shop_items` 시드가 바로 그 raw INSERT**다. 시드가 `is_active`·`sort_order`를 생략하면 D7·D8이 그대로 실사용 경로가 된다. T-044 입력 명세에 이 재검토를 넣는 것을 권한다(이번 Task의 FIX는 아니다).

### FIX-1 — `UserItem` 생성자가 구매 직후 배치 상태를 만들어도 19건이 전부 통과한다
- 파일: `src/main/java/com/petgyebu/telo/shop/domain/UserItem.java:112` (`this.isPlaced = false;`)
- 변이: `false` → `true`. **`exit 0; tests=19 failures=0 errors=0`.**
- 왜 문제인가: 이 한 줄이 F-HPWCNJ의 "획득 직후에는 보관함"(`docs/02-requirements-features.md:427-428`, 배치는 별도 동작)을 지탱한다. 뒤집히면 구매한 아이템이 즉시 방에 배치되고, 게다가 같은 슬롯 두 번째 아이템을 사는 순간 **구매 자체가 부분 유니크 인덱스에 막혀 실패한다.** T-044·T-045가 이 생성자를 쓰는 순간 드러나겠지만, 그때는 원인이 구매 API처럼 보인다.
- 왜 지금 테스트가 못 잡나: 기존 테스트가 `purchase()` 헬퍼로 만드는 행은 전부 **서로 다른 `item_type`**이라(`differentItemsAndUsersCoexist`는 HOUSE·TOY) 부분 유니크에 걸리지 않고, `is_placed` 값을 읽는 단언이 하나도 없다. 배치 관련 단언은 전부 JDBC 헬퍼 `insertUserItem`(`ShopSchemaTest:515`) 경로라 엔티티를 지나가지 않는다.
- 수정 지시: `ShopSchemaTest.userItemCopiesItemTypeFromShopItem`(`:378-390`)에 한 줄을 더한다 — `assertThat(saved.isPlaced()).as("구매 직후 아이템이 방에 배치돼 있다. 획득 직후는 보관함이어야 한다").isFalse();`. 또는 DB 값으로 `SELECT is_placed ...`를 확인해도 된다. 새 테스트를 만들 필요는 없다.

### FIX-2 — `RewardGrant` 엔티티가 어느 테스트에서도 저장되지 않는다
- 파일: `src/main/java/com/petgyebu/telo/reward/domain/RewardGrant.java:79-94`(생성자), `src/main/java/com/petgyebu/telo/reward/repository/RewardGrantRepository.java`
- 변이: 생성자에서 `periodExpenseTotal`과 `previousPeriodExpenseTotal`을 뒤바꿔 대입. **`exit 0; tests=14 failures=0 errors=0`.**
- 근거: `RewardSchemaTest`의 `reward_grants` 단언은 전부 `insertGrant` JDBC 헬퍼(`:376-383`) 경로다. **`RewardGrant`를 `new`로 만드는 코드가 저장소 전체에 없고, `RewardGrantRepository`는 사용처가 0이다.** 검증자가 전 리포지토리의 사용처를 세어 확인했다 — 13개 중 이것 하나만 0이다(다른 것은 최소 1건 이상 테스트가 쓴다). 지금까지 여섯 Task에서 없던 상태다.
- 무엇이 새어 나가나: `ddl-auto: validate`는 컬럼 존재·타입만 본다. 생성자 인자 순서, `@Enumerated(EnumType.STRING)`이 실제로 문자열을 쓰는지, `previousPeriodExpenseTotal`이 첫 기간에 정말 NULL로 내려가는지 — 전부 검증 밖이다. 위 변이는 T-043이 "왜 보상을 못 받았는지" 조회할 때 **판정 근거 두 값이 통째로 뒤바뀐 채** 나타나며, 어디에서도 예외가 나지 않는다.
- 수정 지시: `RewardSchemaTest`에 `RewardGrantRepository.saveAndFlush(new RewardGrant(...))` 한 건을 저장하고 JDBC로 되읽어 `condition_type`이 문자열로 남는지, `period_expense_total`·`previous_period_expense_total`이 넘긴 그대로인지, 첫 기간(`previousPeriodExpenseTotal = null`)이 NULL로 남는지를 단언하는 테스트를 하나 더한다. `RewardSchemaTest`에 `RewardGrantRepository`를 주입하면 리포지토리의 사용처 0도 함께 해소된다. (다른 세 엔티티는 이미 리포지토리 경로로 저장된다 — `creditBalanceIdentifierIsUserId`, `givenShopItem`, `purchase`.)

## 12. 범위 준수
- 판정: PASS
- 검증 대상: `_workspace/t041_00_input.md:12` "만들지 않는 것" vs 실제 변경 파일 16개
- 사유: 앞서간 것도, 빠진 것도 없다.
  - **앞서가지 않았다**: 서비스·컨트롤러·DTO가 0개다. `SELECT FOR UPDATE`·`@Lock`·`@Query`가 저장소 전체에 없다(리포지토리 넷 다 빈 인터페이스). 절약 판정 로직(T-043), 상점 목록·구매(T-044), 배치·해제(T-045)에 해당하는 코드가 없다. 크레딧 금액 100/200도 상수로 박지 않고 T-043에 남겼다(`RewardGrant:58`).
  - **빠지지 않았다**: 테이블 4개, CHECK 6개, UNIQUE 4개(부분 유니크 포함), FK 5개가 모두 들어왔다. 엔티티 4개 + enum 3개 + 리포지토리 4개.
  - Spring Batch 메타 테이블(`docs/09-db-design.md` 6장)을 넣지 않은 것도 맞다. T-042 몫이다.

## 13. 관례 일치
- 판정: PASS
- 검증 대상: 여덟 도메인 패키지 vs 기존 여섯
- 사유:
  - 패키지 구조가 `{domain}/domain` + `{domain}/repository`로 여덟 개 모두 동일하다. `user`·`budget`·`account`·`category`·`transaction`·`sync`·`reward`·`shop`.
  - **`reward`와 `shop`을 나눈 판단은 타당하다.** 기능 ID가 둘(F-EZZFNU / F-HPWCNJ)이고, `docs/08-feature-implementation-map.md`가 API 경로도 `/api/v1/rewards`와 `/api/v1/shop/...`로 나눠 둔다. 백로그의 Task도 T-043(reward)·T-044/T-045(shop)로 갈린다. `credit_balances`를 `reward`에 둔 것은 잔액이 "F-EZZFNU와 공유하는 단일 잔액 필드"(`docs/02-requirements-features.md:433`)이고 지급이 생성 주체이므로 자연스럽다.
  - `@ManyToMany`·양방향 `@OneToMany`가 **소스 전체에 한 개도 없다**(주석의 언급만 있다). 검증자가 `src/main/java` 전체를 검색해 확인했다.
  - `uq_`/`ck_` 접두 제약 이름, `@Enumerated(EnumType.STRING)` + `length`, Lombok `@Getter` + `@NoArgsConstructor(PROTECTED)`, `Objects.requireNonNull` 생성자, `OffsetDateTime now`를 호출자가 넘기는 규칙 — 전부 기존과 같다.
- 이월 사항(이번 Task의 지적 아님): `assertIndex` 헬퍼가 이제 **다섯 벌**이다. T-013 QA 6번·T-014 QA 13번이 이미 통일을 권고했고 이번에 두 벌이 늘었다. 입력 명세가 "`TransactionSchemaTest`에서 그대로 가져와라"고 지시했으므로 지시 이행이지, 위반이 아니다.

## 14. Task 번호 오기
- 판정: **FIX** (우선순위 낮음. 코드 영향 없음)
- 검증 대상: `UserItem.java:46` vs `docs/10-task-backlog.md:140-141`
- 사유: T-013 QA 14번·T-014 QA 16번이 같은 종류의 오기를 지적했는데 이번에 한 건이 새로 들어왔다.
  - `UserItem.java:46` — "구매·**배치·해제** API는 T-044 범위라 여기에 아직 없다". 백로그상 T-044는 **상점 목록·구매 API**(`:140`)이고, **보관함·방 배치 API는 T-045**(`:141`)다. 구매만 T-044이고 배치·해제는 T-045다.
  - 같은 오기가 `_workspace/t041_backend_summary.md:268-269`("`user_items` 배치/해제 API ... 애플리케이션 경로는 T-044에서 만든다")에도 있다.
  - 나머지 참조는 전부 맞다: `CreditBalance:27,32`·`CreditBalanceRepository:9`·SQL:11의 T-043·T-044(지급·구매 모두 잔액을 잠근다), `RewardGrant:33,58`의 T-043, `ShopItem:28,30`·SQL:63의 T-044(시드·상점 목록·구매).
- 수정 지시: `UserItem.java:46`을 "구매는 T-044, 배치·해제는 T-045 범위라 여기에 아직 없다"로 고친다. `_workspace/t041_backend_summary.md:268-269`도 같이 고친다. 푸시 전 같은 커밋에 넣는 편이 낫다.

## 15. 트러블슈팅 기록을 추가하지 않은 판단
- 판정: PASS
- 검증 대상: `t041_backend_summary.md:271-283` vs `CLAUDE.md` "트러블슈팅 기록 (자동 갱신)"
- 사유: 기록 대상 6종 중 어느 것도 발생하지 않았다. (1) 추론이 필요했던 문제 — 없다. 변이 8종은 디버깅이 아니라 의도된 양방향 검증이다. (2) 겉보기 성공 뒤의 실패 — 없다. 첫 빌드부터 119건이 통과했다. (3) 라이브러리 좌표 — 이번 Task에 의존성 변경이 없다. (4) 보안 — 없다. (5) 고쳤는데 증상이 남은 경우 — 없다. (6) 검증 방법 자체의 오류 — 11번의 미검출 2종이 후보이나, 이것은 **작업 중 겪고 해결한 사건이 아니라 검증자가 사후에 찾은 커버리지 공백**이다. T-014 QA 15번과 같은 판단이다.
  - 스스로 고친 둘(`active` 필드명, 시드 단언의 `id <= 100`)을 남기지 않은 것도 맞다. 둘 다 실행 전에 발견해 증상이 관측된 적이 없고, CLAUDE.md가 "오타·즉시 보이는 것"과 "관측된 실패"를 기준으로 삼는다. 다만 두 번째(실행 순서에 따라 결과가 달라지는 단언)는 **검증 방법 자체의 설계 결함**에 가까워, 실행해 깨졌다면 기록 대상이었다. 스스로 요약에 남겨 둔 것으로 충분하다고 본다.
  - `docs/11-troubleshooting-log.md`를 이번 커밋이 건드리지 않았으므로 문서 안의 건수 정합성도 그대로다.

## 16. 요구사항 문서 ↔ 스키마 — 잔여 항목 하나 (지적 아님, 기록용)
- 판정: PASS (T-041 범위 밖)
- 검증 대상: `docs/02-requirements-features.md:413` dataSpec vs `docs/09-db-design.md` 5.2
- 사유: F-EZZFNU의 dataSpec이 "보상 지급 기록에는 ... 지급 시각, **지급 상태**를 저장한다"고 적었는데 `reward_grants`에 상태 컬럼이 없다. 이것은 **T-041이 만든 차이가 아니라 `09-db-design.md` 5.2가 설계 단계에서 이미 내린 결정**이다(지급이 배치 트랜잭션 안에서 완결되므로 중간 상태가 없다는 해석). T-041은 설계 문서를 정확히 따랐다. 설계 문서에 이 해석을 한 줄 남길지는 리더가 정하면 된다.

---

## 리더 확인 요청

- **푸시를 막을 사유는 없다.** 스키마·엔티티·범위·관례에 결함이 없고, 검증 축 6가지가 네 테이블 전부에 빠짐없이 들어와 있으며, DB 제약 변이 12종이 전부 잡힌다.
- **FIX 3건 중 둘은 같은 뿌리다** — 단언이 JDBC 경로에만 있고 **엔티티 경로가 비어 있다**. `UserItem` 생성자의 `isPlaced`와 `RewardGrant` 엔티티 전체가 그렇다. 스키마 Task라 DB 제약에 집중한 결과인데, 엔티티도 이번 Task의 산출물이다. **새 검증 축 9 제안: "이번 Task가 만든 엔티티는 최소 한 번 리포지토리로 저장하고 되읽어 확인한다."** 앞 여섯 Task는 우연히 전부 그렇게 돼 있었고, 이번에 처음 깨졌다.
- **T-044 입력 명세에 넣을 것**: 백로그의 DEFAULT 제외 근거가 "raw SQL INSERT가 생기면 재검토"인데, T-044의 `shop_items` 시드가 정확히 그 경우다. 시드가 `is_active`·`sort_order`를 생략하면 DEFAULT가 실사용 경로가 된다.
- **이월 사항**: `assertIndex`가 다섯 벌로 늘었다(T-013 QA 6번, T-014 QA 13번의 통일 권고가 그대로 남아 있다). 또한 이 헬퍼는 `endsWith` 비교라 `CREATE UNIQUE INDEX` → `CREATE INDEX` 변이를 구조적으로 잡지 못한다 — 통일 Task를 할 때 `indexdef` 전체 비교로 바꾸면 함께 해소된다.
- **백로그 미갱신**: `docs/10-task-backlog.md:137`의 T-041이 아직 `[ ]`다. 푸시·PR 시점에 T-006·T-013·T-014와 같은 형식으로 갱신해야 한다.
