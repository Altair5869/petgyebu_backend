# T-013 구현 요약: `categories`·`merchant_keyword_rules` 스키마와 시드

- 기능 ID: F-OAVYWT (거래 동기화) 중 카테고리 분류 층
- 브랜치: `feature/F-OAVYWT-category-schema`
- 커밋: `a86c5f3 feat: categories·merchant_keyword_rules 스키마와 엔티티 (T-013)` (푸시·PR 없음)

## 항목별 결과

- [완료] 마이그레이션: `src/main/resources/db/migration/V202609210052__create_categories.sql`
- [완료] 엔티티: `src/main/java/com/petgyebu/telo/category/domain/Category.java`, `.../domain/MerchantKeywordRule.java`
- [완료] 리포지토리: `src/main/java/com/petgyebu/telo/category/repository/CategoryRepository.java`, `.../repository/MerchantKeywordRuleRepository.java`
- [완료] 스키마 테스트: `src/test/java/com/petgyebu/telo/category/CategorySchemaTest.java` (9건)
- [보류] 키워드 룰 시드 — 확정된 키워드 목록이 문서에 없다. 아래 참고
- [범위 밖] 가맹점명 자동 분류 로직 — T-019

## 만든 파일

| 파일 | 내용 |
|---|---|
| `src/main/resources/db/migration/V202609210052__create_categories.sql` | `categories`(+시드 10건), `merchant_keyword_rules`, GIN 인덱스 |
| `src/main/java/com/petgyebu/telo/category/domain/Category.java` | `@Id` only, `@GeneratedValue` 없음 |
| `src/main/java/com/petgyebu/telo/category/domain/MerchantKeywordRule.java` | `@JdbcTypeCode(SqlTypes.JSON) List<String> keywords` |
| `src/main/java/com/petgyebu/telo/category/repository/CategoryRepository.java` | `JpaRepository<Category, Short>` |
| `src/main/java/com/petgyebu/telo/category/repository/MerchantKeywordRuleRepository.java` | `JpaRepository<MerchantKeywordRule, Long>` |
| `src/test/java/com/petgyebu/telo/category/CategorySchemaTest.java` | Testcontainers 스키마 제약 검증 9건 |

기존 파일은 하나도 수정하지 않았다. `AccountSchemaTest`·`BudgetSchemaTest`·`UserSchemaTest`는 그대로다.

## 기존 관례와 다르게 결정한 것

1. **`categories.id`에 identity를 쓰지 않았다.** 엔티티에도 `@GeneratedValue`가 없다. 시드로 고정된 값이라 ID가 환경마다 달라지면 `transactions.category_id DEFAULT 99`(T-014)가 근거를 잃는다. 근거: `docs/09-db-design.md` 3.2절.
2. **FK에 `ON DELETE CASCADE`를 걸지 않았다.** 지금까지 4개 FK가 전부 CASCADE였지만 카테고리는 사용자 소유 데이터가 아니라 고정 시드다. 참조 중인 카테고리 삭제는 거부되는 것이 옳아 기본값 `NO ACTION`을 썼다.
3. **`CategorySchemaTest.assertIndex`는 `AccountSchemaTest`의 것과 시그니처가 다르다.** 인덱스 종류(`method`) 인자를 하나 더 받아 `indexdef`가 `USING gin (keywords)`로 끝나는지 본다. `AccountSchemaTest`의 `endsWith("(" + columns + ")")` 방식은 GIN을 B-tree로 바꿔도 통과한다(이름과 대상 열이 그대로이기 때문). 기존 세 테스트의 헬퍼는 건드리지 않고 이 테스트에만 확장판을 뒀다.
4. **`sort_order`는 `id`와 같은 값을 썼다.** 명세에 값이 없다. 미분류가 99라 자연히 맨 뒤로 간다.
5. **시드 단언에서 `id`를 SQL로 `integer` 캐스팅했다.** `SMALLINT`를 JDBC 드라이버가 어떤 박싱 타입으로 주는지에 단언을 의존시키지 않으려고 `CAST(id AS integer)`를 썼다.

## 키워드 룰 시드를 넣지 않은 이유 (미해결로 남긴 것)

실제 키워드 룰 내용이 어느 문서에도 없다. `docs/09-db-design.md` 3.3절은 "시드 데이터로 관리"까지, `docs/06-sprint-plan.md:100`도 "룰도 시드 데이터로 관리"까지다. 카테고리 10건과 달리 확정된 목록이 없어 **키워드를 지어내지 않았다.** 테이블과 GIN 인덱스만 만들고 행은 비워 뒀다.

백로그 완료 기준도 "카테고리 10개가 시드로 들어간다. `keywords`에 GIN 인덱스가 생성된다"까지만 요구한다. 실제 룰은 자동 분류를 구현하는 **T-019**에서 정하고 별도 마이그레이션으로 넣는다. 지어낸 시드가 들어오면 깨지도록 `noKeywordRulesAreSeeded` 단언을 뒀다.

## 검증

### 1. `./gradlew build --rerun-tasks`

```
> Task :test
BUILD SUCCESSFUL in 17s
```

테스트 결과(`build/test-results/test`):

```
com.petgyebu.telo.TeloApplicationTests tests=1 failures=0 errors=0 skipped=0
accounts 스키마 제약 검증 tests=9 failures=0 errors=0 skipped=0
budget_periods·status_thresholds 스키마 제약 검증 tests=9 failures=0 errors=0 skipped=0
categories·merchant_keyword_rules 스키마 제약 검증 tests=9 failures=0 errors=0 skipped=0
com.petgyebu.telo.codef.EasyCodefUtilJdk25Test tests=1 failures=0 errors=0 skipped=0
기준 타임존 KST 규칙 tests=5 failures=0 errors=0 skipped=0
운영 프로필(PostgreSQL + Flyway + validate) 기동 검증 tests=2 failures=0 errors=0 skipped=0
users·user_consents 스키마 제약 검증 tests=5 failures=0 errors=0 skipped=0
```

### 2. 양방향 증명 — 마이그레이션을 망가뜨리면 실제로 FAILED가 난다

각 변이 후 `./gradlew test --rerun-tasks --tests 'com.petgyebu.telo.category.CategorySchemaTest'`.

```
### 변이1: 시드 code 'TRANSPORT' -> 'TRANSIT'
categories·merchant_keyword_rules 스키마 제약 검증 > 카테고리 10건이 명세 그대로 시드된다 — id·code·name을 각각 단언 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 7s

### 변이2: GIN 인덱스를 B-tree로 (이름·대상 열 동일)
categories·merchant_keyword_rules 스키마 제약 검증 > keywords에 GIN 인덱스가 있다 — 이름·대상 열·인덱스 종류까지 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 7s

### 변이3: uq_categories_code UNIQUE 제거
categories·merchant_keyword_rules 스키마 제약 검증 > 같은 code를 가진 카테고리를 둘 만들 수 없다 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 7s

### 변이4: categories.id를 GENERATED ALWAYS AS IDENTITY로 (시드 INSERT에 OVERRIDING SYSTEM VALUE 동반)
categories·merchant_keyword_rules 스키마 제약 검증 > categories.id는 identity가 아니다 — 값을 주지 않으면 INSERT가 실패한다 FAILED
categories·merchant_keyword_rules 스키마 제약 검증 > 룰이 참조 중인 카테고리는 지울 수 없다 — FK는 CASCADE가 아니라 NO ACTION이다 FAILED
categories·merchant_keyword_rules 스키마 제약 검증 > code가 다르면 카테고리를 얼마든지 추가할 수 있다 FAILED
categories·merchant_keyword_rules 스키마 제약 검증 > keywords는 JSONB이며 배열이 그대로 오간다 FAILED
categories·merchant_keyword_rules 스키마 제약 검증 > 같은 code를 가진 카테고리를 둘 만들 수 없다 FAILED
> Task :test FAILED
9 tests completed, 5 failed
BUILD FAILED in 7s

### 변이5: merchant_keyword_rules FK에 ON DELETE CASCADE 추가
categories·merchant_keyword_rules 스키마 제약 검증 > 룰이 참조 중인 카테고리는 지울 수 없다 — FK는 CASCADE가 아니라 NO ACTION이다 FAILED
9 tests completed, 1 failed
BUILD FAILED in 7s

### 변이6: categories.id를 SMALLINT -> INTEGER (QA 지적 반영 후 추가)
categories·merchant_keyword_rules 스키마 제약 검증 > categories.id는 identity가 아니다 — 값을 주지 않으면 INSERT가 실패한다 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 7s
```

**변이4는 컬럼 정의만 바꿔서는 재현되지 않는다.** `id`를 `GENERATED ALWAYS AS IDENTITY`로 바꾸면 같은 마이그레이션 안의 시드 INSERT가 `id`에 명시적 값을 넣으므로 PostgreSQL이 그 INSERT를 거부하고 Flyway가 죽는다. 그러면 컨텍스트 로딩 단계에서 **9건이 전부 실패**해, 정작 보려던 `categoryIdIsNotGenerated` 단언이 실제로 동작하는지를 볼 수 없다. 그래서 변이4는 두 곳을 함께 바꿔야 한다.

```sql
-- 1) 컬럼 정의
id SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
-- 2) 시드 INSERT (이게 없으면 Flyway가 죽어 9건 전부 실패한다)
INSERT INTO categories (id, code, name, sort_order) OVERRIDING SYSTEM VALUE VALUES
```

위 출력은 둘을 함께 적용한 결과다. 의도한 테스트 외에 4건이 더 깨지는 것은 `GENERATED ALWAYS`가 테스트 쪽의 명시적 ID INSERT도 거부하기 때문이다. 의도한 `categories.id는 identity가 아니다`가 FAILED이므로 증명은 성립한다.

변이6은 QA 지적(FIX 2)으로 추가한 것이다. 지적 전에는 `id SMALLINT` → `INTEGER`로 바꿔도 9건 전부 통과했다. Hibernate `validate`가 `Short`↔`integer`를 문제 삼지 않고, PostgreSQL은 FK를 `smallint = integer`로 허용하며, 시드 단언은 `CAST(id AS integer)`로 읽어 타입을 보지 못하기 때문이다. `categoryIdIsNotGenerated`에 `udt_name = 'int2'` 단언을 더해 이 축을 막았다.

전 변이 원복 후 파일 diff 차이 없음을 확인했고, `./gradlew build --rerun-tasks`를 다시 돌려 `BUILD SUCCESSFUL`을 확인했다.

### 3. 마이그레이션 순서 검사 (커밋 후)

```
$ ./scripts/check-migration-order.sh
기준: origin/main (최대 버전 202609202355)
  [통과] V202609210052__create_categories.sql (버전 202609210052)
마이그레이션 순서 검사 통과.
```

커밋 전에는 untracked라 "추가된 마이그레이션이 없다. 검사를 건너뛴다"로 종료 코드 0이 나온다. 그래서 커밋 뒤에 돌렸다.

## 남긴 미해결 사항

- **키워드 룰 시드 없음** (위 참고). T-019에서 룰 목록을 확정하고 별도 마이그레이션으로 넣는다.
- **`merchant_keyword_rules.category_id`에 인덱스를 두지 않았다.** 설계 문서에 없고, 룰 건수가 작아 전수 스캔으로 충분하다. 매칭 로직을 만드는 T-019에서 실제 접근 패턴을 보고 판단한다.
- **트러블슈팅 기록 없음.** 이번 작업에서 한 단계 이상 추론이 필요했던 문제가 없었다. 첫 빌드부터 통과했고, 관례와 달라야 하는 세 지점은 전부 사전에 문서화된 제약이었다.

---

## 후속: `NOT NULL` 단언 추가 (사용자 확인 후)

QA가 미검출로 남긴 것 중 `keywords JSONB NOT NULL`의 `NOT NULL`을 지워도 9건 전부 통과하는 문제를 네 스키마 테스트 전부에 반영했다. `DEFAULT`는 범위 밖이라 손대지 않았다.

- `src/test/java/com/petgyebu/telo/user/UserSchemaTest.java` (`users`, `user_consents`)
- `src/test/java/com/petgyebu/telo/budget/BudgetSchemaTest.java` (`budget_periods`, `status_thresholds`)
- `src/test/java/com/petgyebu/telo/account/AccountSchemaTest.java` (`accounts`)
- `src/test/java/com/petgyebu/telo/category/CategorySchemaTest.java` (`categories`, `merchant_keyword_rules`)

네 파일에 같은 모양의 `assertNullability(String, Map<String, String>)` 헬퍼를 뒀다. 공유 유틸리티 클래스는 만들지 않았다(`assertIndex`와 같은 방식). 테이블 전체의 `column_name → is_nullable` 맵을 한 번에 읽어 `containsExactlyInAnyOrderEntriesOf`로 통째로 비교하므로, `NOT NULL`이 사라지는 것뿐 아니라 null 허용 열에 `NOT NULL`이 붙는 것, 열이 늘거나 없어지는 것도 걸린다. 기대값은 네 마이그레이션 SQL을 읽어 채웠다.

전체 41 → 45건. `./gradlew build --rerun-tasks` `BUILD SUCCESSFUL in 16s`.

양방향 증명 10회(테이블 7개에 (a) `NOT NULL` 제거, null 허용 열이 있는 테이블 3개에 (b) `NOT NULL` 추가)는 전부 FAILED를 냈다. 실제 출력은 `docs/11-troubleshooting-log.md` 18번에 있다.

트러블슈팅 18번을 추가했다. 17번(인덱스)과 같은 "조용한 실패" 계열이되 결과가 느려지는 것이 아니라 틀린 데이터가 들어온다는 점이 다르다. 그 과정에서 17번 본문의 `ddl-auto: validate` 검사 범위 서술이 틀린 것(nullable을 본다고 적혀 있다)을 발견해 정정 주석을 달았다.
