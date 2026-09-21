# T-013 입력: `categories`·`merchant_keyword_rules` 스키마와 시드

- 기능 ID: F-OAVYWT (거래 동기화) 중 카테고리 분류 층
- 브랜치: `feature/F-OAVYWT-category-schema`
- 의존: T-001 (완료, PR #11)
- 출처: `docs/09-db-design.md` 3.2·3.3

## 범위

테이블 2개, 카테고리 10개 시드, GIN 인덱스까지다. 자동 분류 로직(가맹점명 매칭)은 T-016 몫이니 만들지 않는다.

## 이 Task에서 처음 나오는 것 세 가지

지금까지(T-001·T-023·T-006) 없던 패턴이다. 기존 관례를 그대로 복사하면 안 되는 지점이다.

1. **ID를 직접 박는다.** `categories`는 `GENERATED ALWAYS AS IDENTITY`를 **쓰지 않는다.** 값이 고정이고 시드로 관리하므로 ID가 환경마다 달라지면 안 된다. 엔티티도 `@GeneratedValue`를 붙이지 않는다.
2. **마이그레이션에 `INSERT`가 들어간다.** 지금까지는 한 줄도 없었다.
3. **JSONB 컬럼과 GIN 인덱스.** B-tree가 아니라 `indexdef` 모양이 다르다. 인덱스 단언 방식이 통하는지 확인이 필요하다.

## 테이블 1: `categories`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | SMALLINT | PK, **명시적 값**(identity 아님) |
| `code` | VARCHAR(30) | NOT NULL, UNIQUE |
| `name` | VARCHAR(30) | NOT NULL |
| `sort_order` | SMALLINT | NOT NULL |

시드 10건:

| id | code | name |
|---|---|---|
| 1 | `FOOD` | 식비 |
| 2 | `CAFE_SNACK` | 카페·간식 |
| 3 | `TRANSPORT` | 교통 |
| 4 | `SHOPPING` | 쇼핑 |
| 5 | `MEDICAL` | 의료·건강 |
| 6 | `CULTURE` | 문화·여가 |
| 7 | `HOUSING_COMM` | 주거·통신 |
| 8 | `FINANCE_INSURANCE` | 금융·보험 |
| 9 | `SOCIAL_EVENT` | 경조사비 |
| 99 | `UNCLASSIFIED` | 미분류 |

`sort_order`는 명세에 값이 없다. `id`와 같은 값을 쓰되(미분류는 99), 다르게 판단하면 근거를 요약에 남겨라.

**미분류를 99로 둔 것은 나중에 카테고리를 추가할 여지를 남기기 위함이다.** 이 값을 바꾸지 마라. `transactions.category_id`가 `DEFAULT 99`로 이 값을 참조한다(T-014).

## 테이블 2: `merchant_keyword_rules`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY |
| `category_id` | SMALLINT | NOT NULL, FK→categories |
| `keywords` | JSONB | NOT NULL |
| `priority` | SMALLINT | NOT NULL, DEFAULT 0 |

**인덱스**: `keywords`에 **GIN 인덱스**

## 결정: 키워드 룰 시드는 넣지 않는다

**실제 키워드 룰 내용이 어느 문서에도 없다.** `docs/09-db-design.md` 3.3은 "시드 데이터로 관리"라고만 적었고, `docs/06-sprint-plan.md:100`도 "룰도 시드 데이터로 관리"까지다. 카테고리 10개와 달리 확정된 목록이 없다.

백로그의 완료 기준도 "카테고리 10개가 시드로 들어간다. `keywords`에 GIN 인덱스가 생성된다"까지만 요구한다.

**따라서 테이블과 GIN 인덱스만 만들고 룰 행은 넣지 않는다.** 키워드를 지어내지 마라. 실제 룰은 자동 분류를 구현하는 T-016에서 정한다.

## FK 삭제 규칙 — CASCADE가 아니다

`merchant_keyword_rules.category_id`의 FK에 `ON DELETE` 절이 설계 문서에 없다. 기본값 `NO ACTION`이 맞다. 카테고리는 고정 시드라 삭제되지 않으며, 참조 중인 카테고리 삭제는 거부되는 것이 옳다.

**지금까지 4개 FK가 전부 `ON DELETE CASCADE`였다. 여기서 관례를 그대로 따라가면 틀린다.** `users`가 지워지면 그 사용자의 계좌·예산이 함께 지워지는 것은 맞지만, 카테고리는 사용자 소유 데이터가 아니다.

## 기존 코드 관례 (T-001·T-023·T-006에서 확립)

- 패키지: `com.petgyebu.telo.{도메인}.domain` / `.repository`
- 엔티티: `@Getter` + `@NoArgsConstructor(PROTECTED)` + `Objects.requireNonNull`, `@ManyToOne(LAZY, optional = false)`
- 열거형: `VARCHAR` + `CHECK`, `@Enumerated(STRING)`, 대문자 스네이크
- `TIMESTAMPTZ` ↔ `OffsetDateTime`
- 리포지토리는 `JpaRepository`만
- 마이그레이션 파일명 `V{yyyyMMddHHmm}__{설명}.sql`, 버전은 `origin/main` 최대값보다 커야 함
- 테스트는 Testcontainers (`UserSchemaTest`, `BudgetSchemaTest`, `AccountSchemaTest`)
- **인덱스 단언**: `pg_indexes`의 `indexdef`를 조회해 이름과 열 구성까지 단언한다 (T-006 PR #18에서 관례화, 트러블슈팅 17번)
- **유니크 단언**: 거부되는 경우뿐 아니라 **허용되어야 하는 경우**도 단언한다. 제약 이름도 확인한다 (T-023·T-006 교훈)
- KST 단일 출처 `com.petgyebu.telo.common.time.AppZone`

## 완료 기준

1. 마이그레이션 적용, `PostgresMigrationTest` 통과
2. **카테고리 10개가 시드로 들어간다** — 개수뿐 아니라 각 행의 `id`·`code`·`name`이 명세와 일치하는지 단언하라. 개수만 세면 값이 틀려도 통과한다
3. **`keywords`에 GIN 인덱스가 생성된다** — `pg_indexes`로 존재와 대상 열을 단언하고, **인덱스 종류가 GIN인지도 확인하라.** B-tree로 바뀌어도 이름과 열은 같으므로 종류를 보지 않으면 못 잡는다
4. `categories.code` UNIQUE 동작 — 양방향(중복 거부 + 다른 code 허용), 제약 이름 확인
5. `id`가 identity가 **아님**을 증명하라. 명시적 값 없이 INSERT하면 실패해야 한다. identity로 바뀌면 환경마다 ID가 달라지는데, 이것이 조용히 통과하면 안 된다
6. `./gradlew build --rerun-tasks` 통과
