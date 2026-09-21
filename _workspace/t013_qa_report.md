# T-013 QA 리포트 — `categories`·`merchant_keyword_rules` 스키마와 시드

- 기능 ID: F-OAVYWT (거래 동기화) 중 카테고리 분류 층
- 대상: `feature/F-OAVYWT-category-schema` 로컬 커밋 `a86c5f3`, `6713dee` (푸시 전)
- 판정 요약: **PASS 12 / FIX 2 / REDO 0**
- 검증자가 직접 실행한 명령: `./gradlew build --rerun-tasks`(본체), `bash scripts/check-migration-order.sh`, 격리 워크트리에서 변이 **17회**(보고 5종 재현 + 검증자 자체 변이 12종)
- 워크트리는 `git worktree remove --force`로 정리했고, 본체는 `git status` 클린이다

---

## 1. 마이그레이션 SQL ↔ `docs/09-db-design.md` 3.2·3.3 (컬럼·타입·제약·인덱스)
- 판정: PASS
- 검증 대상: `src/main/resources/db/migration/V202609210052__create_categories.sql:6-42` vs `docs/09-db-design.md` 3.2·3.3절 표
- 사유: 한 줄씩 대조했다. 누락·추가 컬럼 없음, 추가 제약 없음.
  - `categories`: `id SMALLINT PRIMARY KEY`(SQL:7, **identity 아님** — 명세 "PK, 명시적 값"과 일치), `code VARCHAR(30) NOT NULL`(SQL:8), `name VARCHAR(30) NOT NULL`(SQL:9), `sort_order SMALLINT NOT NULL`(SQL:10), `uq_categories_code UNIQUE (code)`(SQL:11).
  - `merchant_keyword_rules`: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`(SQL:31), `category_id SMALLINT NOT NULL REFERENCES categories (id)`(SQL:34, `ON DELETE` 절 없음 = `NO ACTION`), `keywords JSONB NOT NULL`(SQL:36), `priority SMALLINT NOT NULL DEFAULT 0`(SQL:38).
  - 인덱스: `ix_merchant_keyword_rules_keywords ... USING GIN (keywords)`(SQL:42) — 명세 3.3 "인덱스: `keywords`에 GIN 인덱스"와 일치.
  - 명세에 없는 인덱스·CHECK·타임스탬프 컬럼을 덧붙이지 않았다. `merchant_keyword_rules`에 `created_at`/`updated_at`이 없는 것도 명세 그대로다.

## 2. 시드 10건 ↔ `docs/09-db-design.md` 3.2 시드 표 (한 행씩)
- 판정: PASS
- 검증 대상: `V202609210052__create_categories.sql:18-28` vs `docs/09-db-design.md` 3.2절 시드 표(10행)
- 사유: 10행 전부 `id`·`code`·`name`이 한 글자도 다르지 않다. 중점(`·`)·가운뎃점 표기까지 같다.

  | id | code | name | SQL 라인 |
  |---|---|---|---|
  | 1 | `FOOD` | 식비 | 19 |
  | 2 | `CAFE_SNACK` | 카페·간식 | 20 |
  | 3 | `TRANSPORT` | 교통 | 21 |
  | 4 | `SHOPPING` | 쇼핑 | 22 |
  | 5 | `MEDICAL` | 의료·건강 | 23 |
  | 6 | `CULTURE` | 문화·여가 | 24 |
  | 7 | `HOUSING_COMM` | 주거·통신 | 25 |
  | 8 | `FINANCE_INSURANCE` | 금융·보험 | 26 |
  | 9 | `SOCIAL_EVENT` | 경조사비 | 27 |
  | 99 | `UNCLASSIFIED` | 미분류 | 28 |

  미분류가 **99**로 들어갔다(SQL:28). `transactions.category_id DEFAULT 99`(T-014)가 이 값에 의존하므로 핵심 항목이다. `sort_order`는 `id`와 같은 값이며, 명세에 값이 없어 구현자가 정한 것이고 요약(`t013_backend_summary.md:34`)에 근거가 남아 있다 — 입력 명세 `t013_00_input.md:44`가 허용한 범위다.

## 3. 엔티티 매핑 ↔ 마이그레이션
- 판정: PASS
- 검증 대상: `category/domain/Category.java:28-46`, `category/domain/MerchantKeywordRule.java:38-59` vs SQL:6-39
- 사유:
  - `Category.id`는 `@Id`만 있고 **`@GeneratedValue`가 없다**(`Category.java:29-30`) — SQL의 non-identity와 대응한다. 타입 `Short` ↔ `SMALLINT`.
  - `code`/`name`: `@Column(nullable = false, length = 30)` ↔ `VARCHAR(30) NOT NULL`.
  - `sortOrder`: `short` + `@Column(nullable = false)`, 컬럼명은 스프링 기본 네이밍 전략이 `sort_order`로 변환. `ddl-auto: validate`(`application.yaml:9`)가 켜진 채 `@SpringBootTest` 9건이 기동했으므로 실제 테이블과 대조를 통과했다.
  - `MerchantKeywordRule.id`: `@GeneratedValue(IDENTITY)` ↔ `GENERATED ALWAYS AS IDENTITY`.
  - `category`: `@ManyToOne(LAZY, optional = false)` + `@JoinColumn(name = "category_id", nullable = false)` — T-006 관례와 같다.
  - `keywords`: `@JdbcTypeCode(SqlTypes.JSON) List<String>` ↔ `JSONB`. 왕복이 테스트로 실증된다(`CategorySchemaTest.java:165-187`).
  - `priority`: `short` ↔ `SMALLINT NOT NULL`. DB `DEFAULT 0`은 엔티티에 표현하지 않았는데, 생성자가 항상 값을 받으므로 두 경로가 어긋나지 않는다.

## 4. 완료 기준 1 — 마이그레이션 적용, `PostgresMigrationTest` 통과
- 판정: PASS
- 사유: 검증자가 직접 실행했다. `운영 프로필(PostgreSQL + Flyway + validate) 기동 검증 tests="2" skipped="0" failures="0" errors="0"`.
  ```
  $ bash scripts/check-migration-order.sh
  기준: origin/main (최대 버전 202609202355)
    [통과] V202609210052__create_categories.sql (버전 202609210052)
  마이그레이션 순서 검사 통과.
  exit=0
  ```
  `[통과]`와 파일명이 찍혔으므로 트러블슈팅 16번의 "건너뛴다 = 통과" 함정에 빠지지 않았다.

## 5. 완료 기준 6 — `./gradlew build --rerun-tasks`
- 판정: PASS
- 사유: 검증자가 직접 실행했다.
  ```
  > Task :test
  > Task :check
  > Task :build

  BUILD SUCCESSFUL in 17s
  7 actionable tasks: 7 executed
  ```
  `build/test-results/test/*.xml`에서 직접 읽은 실행 건수 — 총 41건, 실패·에러·스킵 0.
  ```
  com.petgyebu.telo.TeloApplicationTests            tests="1" skipped="0" failures="0" errors="0"
  accounts 스키마 제약 검증                          tests="9" skipped="0" failures="0" errors="0"
  budget_periods·status_thresholds 스키마 제약 검증  tests="9" skipped="0" failures="0" errors="0"
  categories·merchant_keyword_rules 스키마 제약 검증 tests="9" skipped="0" failures="0" errors="0"
  com.petgyebu.telo.codef.EasyCodefUtilJdk25Test    tests="1" skipped="0" failures="0" errors="0"
  기준 타임존 KST 규칙                               tests="5" skipped="0" failures="0" errors="0"
  운영 프로필(PostgreSQL + Flyway + validate) 기동   tests="2" skipped="0" failures="0" errors="0"
  users·user_consents 스키마 제약 검증               tests="5" skipped="0" failures="0" errors="0"
  ```
  backend-engineer가 보고한 수치와 일치한다. `accounts`가 9건인 것으로 T-006 FIX(인덱스 단언 추가)가 반영됐음도 확인했다.

## 6. GIN 인덱스 종류 단언 — 확장판 헬퍼가 필요했는가, 실제로 종류를 고정하는가
- 판정: PASS (판단 타당, 헬퍼 실효 확인)
- 검증 대상: `CategorySchemaTest.java:233-246`(확장판) vs `AccountSchemaTest.java:231-244`(기존)
- 사유: **검증자가 독립적으로 변이를 돌려 확인했다.** `USING GIN` → `USING BTREE` 변이의 실패 메시지에 실제 `indexdef`가 찍힌다.
  ```
  [인덱스 ix_merchant_keyword_rules_keywords의 종류나 대상 열 구성이 설계와 다르다]
  Expecting actual:
    "CREATE INDEX ix_merchant_keyword_rules_keywords ON public.merchant_keyword_rules USING btree (keywords)"
  to end with:
    "USING gin (keywords)"
      at CategorySchemaTest.assertIndex(CategorySchemaTest.java:245)
  ```
  실제 문자열이 `...USING btree (keywords)`로 끝난다. `AccountSchemaTest.assertIndex`의 `endsWith("(" + columns + ")")`(`AccountSchemaTest.java:243`)는 이 문자열을 **통과시킨다** — 즉 backend-engineer의 판단("열 목록만 보는 단언은 GIN→B-tree 변이를 못 잡는다")은 추측이 아니라 사실이다. 확장판은 `endsWith("USING gin (keywords)")`로 종류와 열을 한 덩어리로 못 박으므로 그 변이를 실제로 잡는다.
- 참고: 기존 세 테스트의 헬퍼를 건드리지 않고 이 테스트에만 확장판을 둔 것은 외과적 변경 원칙에 맞다. 다만 헬퍼가 두 벌로 갈렸으므로, 리더가 원하면 후속 정리 Task에서 `AccountSchemaTest` 쪽도 `method` 인자를 받도록 통일하는 편이 낫다(B-tree 2건은 `"btree"`를 넘기면 그만이다). **이번 Task의 지적은 아니다.**

## 7. 보고된 변이 5종 재현 — 변이4의 서술이 실제와 다르다
- 판정: **FIX** (코드 결함 아님 — 요약 문서의 재현 절차 누락)
- 검증 대상: `_workspace/t013_backend_summary.md:88-96, 104` vs 검증자의 격리 워크트리 실행 결과
- 사유: 변이 1·2·3·5는 실패 테스트 이름과 건수까지 **정확히 일치**했다.

  | 변이 | 마이그레이션 변경 | 보고 | 검증자 재현 |
  |---|---|---|---|
  | 변이1 | `'TRANSPORT'` → `'TRANSIT'` | 1 failed | **1 failed, 동일 테스트** |
  | 변이2 | `USING GIN` → `USING BTREE` | 1 failed | **1 failed, 동일 테스트** |
  | 변이3 | `uq_categories_code` 제거 | 1 failed | **1 failed, 동일 테스트** |
  | 변이4 | "`categories.id`를 `GENERATED ALWAYS AS IDENTITY`로" | 5 failed | **9 failed** ← 불일치 |
  | 변이5 | FK에 `ON DELETE CASCADE` 추가 | 1 failed | **1 failed, 동일 테스트** |

  변이4를 **적힌 그대로** 적용하면 마이그레이션의 시드 `INSERT`가 명시적 id를 넣으므로 `GENERATED ALWAYS`가 거부한다 → Flyway 실패 → 스프링 컨텍스트 기동 실패 → **9건 전부 FAILED**다. 보고서의 5건이 나오려면 시드 `INSERT`에 `OVERRIDING SYSTEM VALUE`를 함께 넣어야 한다. 검증자가 그 가설로 다시 돌린 결과(`변이4b`) 실패 테스트 **이름 5개가 보고서와 한 글자도 다르지 않게** 재현됐다.
  ```
  ### M4b identity + OVERRIDING SYSTEM VALUE
  > categories.id는 identity가 아니다 — 값을 주지 않으면 INSERT가 실패한다 FAILED
  > 룰이 참조 중인 카테고리는 지울 수 없다 — FK는 CASCADE가 아니라 NO ACTION이다 FAILED
  > code가 다르면 카테고리를 얼마든지 추가할 수 있다 FAILED
  > keywords는 JSONB이며 배열이 그대로 오간다 FAILED
  > 같은 code를 가진 카테고리를 둘 만들 수 없다 FAILED
  9 tests completed, 5 failed
  ```
  즉 변이 자체는 실제로 돌렸고 결과도 진짜지만, **요약에 적힌 변이 서술로는 그 결과가 재현되지 않는다.** 양방향 증명 기록은 재현 가능해야 가치가 있다.
- 수정 지시: `_workspace/t013_backend_summary.md:88`의 변이4 서술을 실제 적용한 변경으로 고친다. 예: "변이4: `categories.id`를 `GENERATED ALWAYS AS IDENTITY`로 + 시드 `INSERT`에 `OVERRIDING SYSTEM VALUE` 추가(그러지 않으면 마이그레이션 자체가 실패해 9건 전부 죽어 단언의 실효를 볼 수 없다)". 같은 파일 `:104`의 "변이4는 의도한 테스트 외에 4건이 더 깨진다" 문장에도 그 전제를 덧붙인다.

## 8. 변이4의 5건 동시 실패가 과잉 결합인가
- 판정: PASS (과잉 결합 아님)
- 사유: `categoryIdIsNotGenerated`(`CategorySchemaTest.java:102-123`)가 **혼자서** 두 겹으로 증명한다. (1) id 없는 `INSERT`가 `DataIntegrityViolationException`으로 거부되는지(`:107-113`), (2) `information_schema.columns.is_identity = 'NO'`인지(`:116-122`). 둘째 겹은 다른 테스트의 부수 효과와 무관하게 컬럼 정의 자체를 못 박는다. 나머지 4건은 헬퍼 `category(...)`가 명시적 id로 INSERT하기 때문에 따라 죽는 부수 효과일 뿐, **증명이 그 4건에 기대고 있지 않다.** 변이4b 결과에서 의도한 테스트가 실패 목록 맨 앞에 그대로 있으므로 증명은 성립한다.

## 9. 검증자 자체 변이 12종 — 전부 검출되는가
- 판정: PASS (10종 검출), 다만 3종은 **미검출**이라 아래 10·11번으로 분리
- 사유: 격리 워크트리에서 돌린 결과다.

  | 변이 | 내용 | 결과 | 깨진 테스트 |
  |---|---|---|---|
  | M6 | `sort_order` 10행 전부 `0`으로 | 1 failed | `sort_order는 id와 같은 값이다` |
  | M7 | 시드 한 행 통째로 누락(`SOCIAL_EVENT`) | 1 failed | `카테고리 10건이 명세 그대로 시드된다` |
  | M8 | FK(`REFERENCES categories (id)`) 자체 제거 | 1 failed | `룰이 참조 중인 카테고리는 지울 수 없다` |
  | M9 | `keywords` JSONB → TEXT (+인덱스 B-tree) | 9 failed | `validate` 불일치로 컨텍스트 기동 실패 |
  | M10 | GIN 인덱스 `CREATE INDEX` 줄 삭제 | 1 failed | `keywords에 GIN 인덱스가 있다` |
  | M11 | 미분류 id `99` → `10` | 1 failed | `카테고리 10건이 명세 그대로 시드된다` |
  | M13 | 시드 11번째 행 추가(id 11) | 1 failed | `카테고리 10건이 명세 그대로 시드된다` |
  | M12 | `categories.id` `SMALLINT` → `INTEGER` | **BUILD SUCCESSFUL** | **없음 → 10번** |
  | M14 | 시드 행 추가(id 2000, 테스트 대역 밖) | **BUILD SUCCESSFUL** | **없음 → 11번** |
  | M15 | `priority DEFAULT 0` → `DEFAULT 5` | **BUILD SUCCESSFUL** | **없음 → 11번** |
  | M16 | `keywords`의 `NOT NULL` 제거 | **BUILD SUCCESSFUL** | **없음 → 11번** |
  | M17 | (테스트 측) `CAST(id AS integer)` 제거 | BUILD SUCCESSFUL | 12번 판정 근거 |

  M10은 T-006 FIX가 지적했던 "인덱스가 지워져도 아무도 모른다" 사각지대가 이번 Task에는 **처음부터** 없음을 보여준다. M11은 `transactions.category_id DEFAULT 99`(T-014)가 기대는 값이 단언으로 고정돼 있음을 보여주는 핵심 변이다.

## 10. `categories.id`의 컬럼 타입이 어떤 단언으로도 고정되지 않았다
- 판정: **FIX** (우선순위 낮음)
- 검증 대상: `V202609210052__create_categories.sql:7` vs `CategorySchemaTest.java` 전체
- 사유: 변이 M12로 직접 확인했다. `id SMALLINT PRIMARY KEY`를 `id INTEGER PRIMARY KEY`로 바꿔도 **9건 전부 통과하고 `BUILD SUCCESSFUL`**이다.
  - Hibernate `validate`는 `Short` ↔ `integer`를 문제 삼지 않는다.
  - `merchant_keyword_rules.category_id SMALLINT`가 `integer`를 참조해도 PostgreSQL은 `smallint = integer` 연산자가 있어 FK를 허용한다.
  - 시드 단언은 `CAST(id AS integer)`로 읽으므로 타입 차이를 볼 수 없고, `is_identity`만 보는 단언(`CategorySchemaTest.java:116-122`)도 타입은 보지 않는다.
  - 명세(`docs/09-db-design.md` 3.2, `t013_00_input.md:24`)는 `SMALLINT`를 명시했고, 같은 테스트가 `keywords`에 대해서는 이미 `information_schema`로 타입을 못 박고 있다(`CategorySchemaTest.java:180-186`). 이 축만 비어 있다.
- 수정 지시: `CategorySchemaTest.java:116-122`에 이미 `information_schema.columns`를 `table_name='categories' AND column_name='id'`로 조회하는 쿼리가 있다. 같은 쿼리에 `udt_name`(또는 `data_type`)을 하나 더 뽑아 `"int2"`(또는 `"smallint"`)를 단언하면 한 줄로 끝난다. 예:
  ```
  SELECT is_identity, udt_name FROM information_schema.columns
   WHERE table_name = 'categories' AND column_name = 'id'
  ```
  이 단언이 있었다면 M12가 잡혔다.

## 11. 미검출로 남는 나머지 세 축 (이번 Task 신규 결함 아님)
- 판정: PASS (지적 아님, 이월 사항으로 기록)
- 사유:
  - **M15 — DB `DEFAULT` 값**: `priority DEFAULT 0`을 `DEFAULT 5`로 바꿔도 통과한다. 명세 3.3이 `DEFAULT 0`을 명시했지만 단언이 없다. 이것은 **T-006 QA 리포트 8번의 수정 지시 2번(우선순위 낮음)과 같은 축**이며, 그 지시는 리더 판정 대기로 남아 있다(`AccountSchemaTest`가 9건이 된 것은 인덱스 단언만 반영된 결과다). 이번 Task만 고치면 관례가 갈린다.
  - **M16 — 컬럼 `NOT NULL`**: `keywords`의 `NOT NULL`을 지워도 통과한다. `UserSchemaTest`·`BudgetSchemaTest`·`AccountSchemaTest` 어디에도 `NOT NULL` 위반을 단언하는 테스트가 없다(grep으로 확인). 네 테스트 공통의 **기존** 사각지대다.
  - **M14 — 테스트 대역 밖 시드 행**: `id >= 1000`인 시드 행이 추가돼도 잡히지 않는다. 시드 단언이 `WHERE id < 1000`으로 걸러내기 때문이다(`CategorySchemaTest.java:68`). 다만 테스트가 스스로 만든 카테고리와 시드를 분리하려면 이 필터가 필요하고(테스트에 `@Transactional` 롤백이 없어 행이 남는다), 실제로 카테고리를 늘릴 자리는 10~98이라 M13이 그 경로를 막는다. **잔여 위험은 낮다.**
- 위 셋 모두 T-013이 새로 만든 결함이 아니라 기존 관례의 범위이므로 FIX로 올리지 않았다. 리더가 별도 Task로 묶을지 정하면 된다.

## 12. backend-engineer 자기 판단 5건 검토
- 판정: PASS (5건 모두 타당)
- (1) **`categories.id`에 identity를 쓰지 않음** (`t013_backend_summary.md:31`, `Category.java:29-30`, SQL:7) — **타당하며 명세가 요구한 것이다.** `docs/09-db-design.md:25`(3.2절)이 "**identity를 쓰지 않고 id를 직접 박는 유일한 테이블이다**"라고 못 박았다. 관례(앞선 3테이블 전부 identity)를 그대로 따랐다면 명세 위반이었다. 근거도 정확하다 — 시드 ID가 환경마다 달라지면 `transactions.category_id DEFAULT 99`(T-014)가 성립하지 않는다.
- (2) **시드 단언에서 `CAST(id AS integer)`** (`CategorySchemaTest.java:67`) — **타당하나 테스트를 약하게 만들지는 않는다. 동시에 강하게 만들지도 않는다.** 검증자가 변이 M17로 캐스팅을 제거하고 돌려 본 결과 그대로 `BUILD SUCCESSFUL`이다. PostgreSQL JDBC 드라이버는 `smallint`를 어차피 `Integer`로 돌려주므로 캐스팅은 현재 no-op이며, 드라이버 동작이 바뀌어도 단언이 안 깨지게 하는 방어일 뿐이다. 소스 코드 주석(`:64-65`)의 취지와 실제 효과가 어긋나지 않는다. **다만 이 캐스팅이 컬럼 타입을 보호해 주는 것은 아니다** — 그 공백은 10번(FIX)에서 따로 잡았다. 캐스팅을 없앤다고 M12가 잡히는 것도 아니므로, 캐스팅이 원인은 아니다.
- (3) **`assertIndex`에 `method` 인자를 더한 확장판** — **타당하며 실증됐다.** 6번 참조. 판단 근거가 실제 `indexdef` 문자열과 일치함을 검증자가 독립 변이로 확인했다.
- (4) **`sort_order`를 `id`와 같은 값으로** (SQL:19-28) — 타당. 명세에 값이 없고(`t013_00_input.md:44`가 명시적으로 허용), 근거를 요약에 남겼으며, 미분류 99가 자연히 맨 뒤로 간다는 설명도 맞다. `sortOrderFollowsId`(`CategorySchemaTest.java:90-98`)가 이 결정을 실제로 고정한다(M6로 확인).
- (5) **테스트 카테고리를 id 1000번대로 분리** (`CategorySchemaTest.java:48`) — **타당하며 필요하다. 약화 효과는 미미하다.** 이 테스트 클래스에는 `@Transactional` 롤백이 없어 각 테스트가 만든 카테고리가 같은 컨테이너에 그대로 남는다. 대역 분리가 없으면 시드 단언(`containsExactly` 10건)이 앞서 실행된 다른 테스트의 잔여 행 때문에 **실행 순서에 따라 깨지는** 불안정 테스트가 된다. 잔여 사각지대는 "id ≥ 1000인 시드 행"뿐이고(11번, M14), 실제 카테고리 증설 자리는 10~98이라 M13이 그 경로를 막는다.

## 13. 범위 준수
- 판정: PASS
- 사유: 커밋 `a86c5f3`이 건드린 파일은 6개뿐이고 전부 마이그레이션·엔티티·리포지토리·테스트다. 기존 파일은 하나도 고치지 않았다(`git diff --name-only b938782..HEAD`로 확인).
  - **선점 없음**: 가맹점명 매칭·자동 분류 로직이 어디에도 없다. `MerchantKeywordRule`에 매칭 메서드가 없고, `MerchantKeywordRuleRepository`는 `JpaRepository`만 상속해 `findByKeywordsContaining` 류의 조회 메서드가 하나도 없다. `category` 패키지에 서비스·컨트롤러·DTO가 없다. `initial_classification_source`(T-019) 관련 코드도 없다.
  - **키워드 룰 시드 선점 없음**: `merchant_keyword_rules`에 `INSERT`가 한 줄도 없고, 오히려 들어오면 깨지도록 `noKeywordRulesAreSeeded`(`CategorySchemaTest.java:212-221`)를 뒀다.
  - **빠진 것 없음**: 입력 명세가 요구한 테이블 2개, 컬럼 8개, UNIQUE 1종, FK 1종, GIN 인덱스 1종, 시드 10건, 엔티티 2개, 리포지토리 2개가 모두 있다. 완료 기준 1~6에 대응하는 테스트도 모두 있다(시드 값 단언, GIN 종류 단언, UNIQUE 양방향 + 제약 이름, identity 아님 증명).

## 14. 키워드 룰 시드를 넣지 않은 판단
- 판정: PASS (타당 — 검증자가 직접 문서 전수 검색)
- 검증 대상: `t013_backend_summary.md:37-41` vs 저장소 문서 전체
- 사유: `grep -rn "키워드" docs/` 로 직접 찾았다. 키워드를 언급하는 곳은 전부 "룰 테이블이 있다"·"시드로 관리한다"까지고, **실제 키워드 문자열 목록은 어디에도 없다.**
  - `docs/09-db-design.md:221` — 컬럼 설명("가맹점명 키워드 배열")까지
  - `docs/06-sprint-plan.md:100` — "룰도 시드 데이터로 관리"까지
  - `docs/04-review-log.md:52` — R-CCOEWW 결정 1, 테이블 도입 결정까지
  - `docs/04-review-log.md:488,492` — Q12, 관리자 화면 없이 시드로 관리한다는 결정까지
  - `docs/02-requirements-features.md:124,149,155` — F-OAVYWT 본문. **카테고리 10개는 여기 그대로 나열돼 있지만 키워드는 한 건도 없다.** 대비가 분명하다.
  - `docs/01-prd.md:71`, `docs/08-feature-implementation-map.md:282` — Q12 재진술까지
  - `docs/04-review-log.md:444` — "가맹점명 키워드 룰 테이블을 이 수준에서는 초기 구축할 수 있다"는 카테고리 **개수**에 대한 근거지 키워드 목록이 아니다.
  백로그 완료 기준(`docs/10-task-backlog.md:77`)도 "카테고리 10개가 시드로 들어간다. `keywords`에 GIN 인덱스가 생성된다"까지다. **못 찾은 것이 아니라 실제로 없다.** 지어내지 않은 판단이 옳다.
- 참고: 요약은 후속 Task를 **T-016**으로 적었으나(`t013_backend_summary.md:39,41`), 백로그상 자동 카테고리 분류는 **T-019**(`docs/10-task-backlog.md:83`, 의존 T-013·T-016)다. T-016은 중복 거래 판정이다. 문서 표기상의 혼동이며 코드에는 영향이 없으나, 마이그레이션 주석(SQL:46)과 엔티티 Javadoc(`MerchantKeywordRule.java:24,30`)에도 같은 번호가 박혀 있어 **나중에 룰 시드를 넣을 때 엉뚱한 Task를 찾게 된다.** 요약·주석의 `T-016`을 `T-019`로 고치기를 권한다(아래 리더 확인 요청 참조).

## 15. 기존 관례 일치 — 그리고 일부러 다르게 한 세 지점
- 판정: PASS
- 검증 대상: `category/**` vs `user/**`, `budget/**`, `account/**`
- 사유:
  - **같아야 하는 것은 같다.** 패키지 `com.petgyebu.telo.category.domain`/`.repository`, 엔티티는 `@Entity` + `@Table` + `@Getter` + `@NoArgsConstructor(PROTECTED)` + 공개 생성자 + `Objects.requireNonNull("필드명")`, 연관은 `@ManyToOne(LAZY, optional = false)` + `@JoinColumn(nullable = false)`, 리포지토리는 `JpaRepository`만이고 조회 메서드 없음, 마이그레이션 파일명 `V{yyyyMMddHHmm}__{설명}.sql`이고 버전이 `origin/main` 최대값보다 큼, 테스트는 Testcontainers `@SpringBootTest` + `@Container @ServiceConnection` + `postgres:16-alpine` + 한글 `@DisplayName` + `DataIntegrityViolationException` 단언 + **유니크 위반 시 제약 이름 확인**(`CategorySchemaTest.java:136-137`) + **유니크 양방향**(`:127-138` 거부, `:141-152` 허용). 인덱스 단언은 `pg_indexes.indexdef` 조회 방식으로 T-006 관례를 따른다. 시각 타입이 없는 테이블이라 `AppZone` 사용처가 없는 것도 맞다.
  - **달라야 하는 세 지점이 옳게 다르다.**
    1. `categories.id`에 identity·`@GeneratedValue` 없음 — `docs/09-db-design.md:25`가 요구한 그대로다(12번 (1)).
    2. FK에 `ON DELETE CASCADE` 없음 — 설계 문서에 `ON DELETE` 절이 없고, 카테고리는 사용자 소유 데이터가 아니라 고정 시드다. 관례(기존 4개 FK 전부 CASCADE)를 복사했다면 틀렸다. 단순히 안 쓴 것이 아니라 `deletingReferencedCategoryIsRejected`(`CategorySchemaTest.java:191-208`)로 **거부되는 동작 자체를 못 박았고**, 거부된 뒤 카테고리가 남아 있는지까지 본다(`:205-207`). 검증자가 M5(CASCADE 추가)와 M8(FK 제거) 양쪽으로 확인했다.
    3. 마이그레이션의 `INSERT` — 시드 10건이 값 단위로 단언되고(M7·M11·M13으로 확인), 반대로 **넣지 말아야 할 시드**(키워드 룰)도 `noKeywordRulesAreSeeded`로 고정했다. `INSERT`가 처음 들어오는 Task에서 양방향을 다 덮은 것은 적절하다.

## 16. 트러블슈팅 기록을 추가하지 않은 판단
- 판정: PASS
- 검증 대상: `t013_backend_summary.md:123` vs `CLAUDE.md` "트러블슈팅 기록 (자동 갱신)"
- 사유: CLAUDE.md의 기록 대상 6종에 해당하는 사건이 없다. (1) 원인 추적에 한 단계 이상 추론이 필요했던 문제 — 없다. 변이 실행은 디버깅이 아니라 의도된 검증이다. (2) 겉보기 성공 뒤의 실패 — 이번엔 겪은 것이 아니라 기존 기록(마이그레이션 순서 스크립트의 "건너뛴다 = 통과", 트러블슈팅 16번)을 **피해 간** 것이다. 요약 `:117`이 "커밋 전에는 untracked라 건너뛴다로 종료 코드 0이 나온다. 그래서 커밋 뒤에 돌렸다"라고 적은 것은 기존 항목의 올바른 재적용이다. (3) 라이브러리 좌표 차이 — 없다. (4) 보안 문제 — 없다. (5) 고쳤는데 증상이 남은 경우 — 없다. (6) 검증 방법 자체의 오류 — 7번에서 잡은 변이4 서술 불일치가 여기 걸릴 뻔했으나, 이는 **기록 대상인 "검증 방법의 오류"가 아니라 요약 문서의 서술 누락**이다(변이는 제대로 돌았고 결과도 진짜다). 요약을 고치는 것으로 충분하다.
  `docs/11-troubleshooting-log.md`를 이번 커밋이 건드리지 않았으므로 문서 안의 건수 정합성도 그대로다.

---

## 리더 확인 요청

- **FIX 2건 모두 스키마·엔티티 자체의 결함이 아니다.** 마이그레이션과 엔티티는 `docs/09-db-design.md` 3.2·3.3과 완전히 일치하고, 시드 10건도 한 행씩 맞다. 푸시를 막을 사유는 없다.
  - FIX 7번(요약의 변이4 서술) — 문서 한 줄 수정. 푸시 전에 같은 커밋에 넣는 편이 낫다.
  - FIX 10번(`categories.id` 타입 단언) — 테스트 한 줄 추가. 이미 있는 `information_schema` 쿼리에 컬럼 하나를 더하는 수준이다.
- **표기 정정 권고 1건**(14번 참고): 키워드 룰 시드의 후속 Task는 `T-016`(중복 거래 판정)이 아니라 **`T-019`(자동 카테고리 분류)**다. `_workspace/t013_backend_summary.md:39,41`, `V202609210052__create_categories.sql:46`, `MerchantKeywordRule.java:24,30`에 `T-016`으로 적혀 있다. FIX로 올리지 않았으나 남겨 두면 T-019에서 헤맨다.
- **이월 사항 3건**(11번): DB `DEFAULT` 값 단언(T-006 리포트 8번 지시 2와 동일 축, 여전히 미반영), 컬럼 `NOT NULL` 단언(네 테스트 공통 기존 공백), `assertIndex` 헬퍼 두 벌(6번 참고). 셋 다 이번 Task 신규 결함이 아니므로 별도 정리 Task로 묶을지 리더가 정하면 된다.
- **후속 Task 입력으로 옮길 것 1건**: T-014의 `transactions.category_id`는 `DEFAULT 99`이며, 이 값은 `V202609210052__create_categories.sql:28`의 시드와 `CategorySchemaTest.java:85`의 단언에 고정돼 있다. T-014 입력에 "99가 `UNCLASSIFIED`임을 전제로 하며 이 값을 바꾸지 않는다"를 명시적으로 옮겨야 한다.
