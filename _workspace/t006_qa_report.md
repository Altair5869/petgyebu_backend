# T-006 QA 리포트 — `accounts` 스키마와 엔티티

- 기능 ID: F-TEDWWF (은행 계좌 연결)
- 대상: `feature/F-TEDWWF-schema` 로컬 커밋 `b33d422`, `1e5b1f9` (푸시 전)
- 판정 요약: **PASS 11 / FIX 1 / REDO 0**
- 검증자가 직접 실행한 명령: `./gradlew build --rerun-tasks`(본체), `bash scripts/check-migration-order.sh`, 격리 워크트리에서 `./gradlew test --tests '*AccountSchemaTest' --rerun-tasks` 변이 5회
- 워크트리는 검증 후 `git worktree remove --force`로 정리했고, 본체는 `git status` 클린이다

---

## 1. 마이그레이션 SQL ↔ `docs/09-db-design.md` 3.1
- 판정: PASS
- 검증 대상: `src/main/resources/db/migration/V202609202355__create_accounts.sql:4-38` vs `docs/09-db-design.md` 3.1절 표(14행)
- 사유: 14개 컬럼을 한 줄씩 대조했다. 이름·타입·NULL 허용 여부가 전부 일치한다.
  - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` (SQL:5)
  - `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE` (SQL:7)
  - `bank_code VARCHAR(10) NOT NULL` (SQL:9) — 명세의 **VARCHAR** 요구와 일치
  - `codef_connected_id VARCHAR(255) NOT NULL` (SQL:11), `masked_account_no VARCHAR(50) NOT NULL` (SQL:13), `account_name VARCHAR(100)` NULL 허용 (SQL:15)
  - `consent_status VARCHAR(20) NOT NULL` (SQL:16) + `ck_accounts_consent_status CHECK IN ('ACTIVE','EXPIRED','REVOKED')` (SQL:28)
  - `consent_expires_at`·`last_synced_at` TIMESTAMPTZ NULL (SQL:17,19)
  - `reauth_required BOOLEAN NOT NULL DEFAULT FALSE` (SQL:21), `included_in_budget BOOLEAN NOT NULL DEFAULT TRUE` (SQL:22)
  - `display_mode VARCHAR(10) NOT NULL DEFAULT 'BADGE'` (SQL:25) + `ck_accounts_display_mode CHECK IN ('BADGE','HIDDEN')` (SQL:29)
  - `created_at`·`updated_at` TIMESTAMPTZ NOT NULL DEFAULT now() (SQL:26-27)
  - `uq_accounts_user_bank_masked_no UNIQUE (user_id, bank_code, masked_account_no)` (SQL:31)
  - `ix_accounts_user_id` (SQL:35), `ix_accounts_consent_status_last_synced_at` (SQL:38)
  누락·추가 컬럼 없음. 추가 제약 없음. `display_mode`와 `included_in_budget`의 관계를 DB 제약으로 표현하지 않은 것도 명세(`docs/09-db-design.md` 3.1 말미)와 같다.

## 2. 엔티티 매핑 ↔ 마이그레이션
- 판정: PASS
- 검증 대상: `src/main/java/com/petgyebu/telo/account/domain/Account.java:46-94` vs 마이그레이션 SQL:5-27
- 사유: `VARCHAR ↔ String + length`, `TIMESTAMPTZ ↔ OffsetDateTime`, `BOOLEAN ↔ boolean`, 열거형 2종은 `@Enumerated(STRING)` + `length`(20/10)로 CHECK 폭과 맞다. `user`는 `@ManyToOne(LAZY, optional=false)` + `@JoinColumn(name="user_id", nullable=false)`. NULL 허용 컬럼(`account_name`, `consent_expires_at`, `last_synced_at`)만 `nullable=false`가 빠져 있어 정확히 대응한다. `consent_expires_at`·`last_synced_at`은 `@Column`이 없지만 Spring 기본 네이밍 전략이 스네이크로 변환하며, `ddl-auto: validate`가 켜진 상태에서 `@SpringBootTest` 8건이 기동했으므로 Hibernate가 실제 테이블과 대조해 통과한 것이다. `createdAt`에만 `updatable=false`를 건 것도 `User.joinedAt`과 같다.

## 3. 완료 기준 1 — 마이그레이션 적용, `PostgresMigrationTest` 통과
- 판정: PASS
- 사유: 직접 실행한 결과 `운영 프로필(PostgreSQL + Flyway + validate) 기동 검증 tests="2" skipped="0" failures="0" errors="0"`. 마이그레이션 순서 검사도 직접 돌렸다.
  ```
  $ bash scripts/check-migration-order.sh
  기준: origin/main (최대 버전 202609202314)
    [통과] V202609202355__create_accounts.sql (버전 202609202355)
  마이그레이션 순서 검사 통과.
  exit=0
  ```
  `[통과]`와 파일명이 찍혔으므로 트러블슈팅 16번의 "건너뛴다 = 통과" 함정에 빠지지 않았다.

## 4. 완료 기준 2 — `bank_code` VARCHAR, 앞자리 0 보존 (세 겹 검증의 실효성)
- 판정: PASS
- 검증 대상: `src/test/java/com/petgyebu/telo/account/AccountSchemaTest.java:55-82`
- 사유: 세 겹이 각각 다른 실패 모드를 실제로 막는다.
  1. `AccountSchemaTest.java:61-65` — `findById`로 읽은 엔티티 값. 다만 이것만으로는 약하다. 같은 트랜잭션·영속성 컨텍스트에서 1차 캐시가 원본 객체를 돌려줄 수 있어 DB를 거치지 않을 수 있다. 그래서 2번이 필요하다.
  2. `AccountSchemaTest.java:68-72` — `JdbcTemplate`으로 `SELECT bank_code`를 직접 읽는다. JPA 1차 캐시를 확실히 우회하므로 **DB에 실제로 들어간 값**을 본다. 컬럼이 정수형이면 `String.class`로 받는 시점에 `"4"`가 되어 깨진다. 이 한 겹이 완료 기준의 핵심 증명이다.
  3. `AccountSchemaTest.java:75-81` — `information_schema.columns.data_type = 'character varying'`. 값이 아니라 **컬럼 타입 자체**를 못 박으므로, 나중에 `'004'` 같은 값이 테스트에 없더라도 타입 변경을 잡는다.
  backend-engineer가 보고한 역방향(`BIGINT`로 바꾸면 8건 전부 실패)은 원인이 세 단언이 아니라 Hibernate `validate`의 컨텍스트 로딩 실패다. 보고서도 그렇게 적었으므로 과대평가는 아니다. 다만 그 결과가 증명하는 것은 "타입을 바꾸면 조용히 통과할 수 없다"이지 "세 단언이 각각 동작한다"가 아니다 — 후자는 위 1~3의 코드 독해로 판정했다.

## 5. 완료 기준 3 — 유니크 양방향 (독립 재현)
- 판정: PASS
- 검증 대상: `AccountSchemaTest.java:84-143` vs `uq_accounts_user_bank_masked_no`
- 사유: backend-engineer의 변이를 **격리 워크트리에서 독립적으로 재현했고, 추가 변이 3개를 더 돌렸다.** 3열 유니크의 각 열이 전부 단언으로 고정돼 있다.

  | 변이 | 마이그레이션 변경 | 결과 | 깨진 테스트 |
  |---|---|---|---|
  | M1 (보고 재현) | `UNIQUE (user_id)` | `8 tests completed, 2 failed` | 다른 은행 같은 마스킹 번호 허용, 동일 은행 복수 계좌 허용 |
  | M2 | `UNIQUE (user_id, bank_code)` — `masked_account_no` 제거 | `8 tests completed, 1 failed` | 동일 은행 복수 계좌 허용 (`AccountSchemaTest.java:101-116`) |
  | M3 | `CREATE INDEX` 2줄 삭제 | `BUILD SUCCESSFUL` | **없음 → 8번 항목 참조** |
  | M4 | `UNIQUE (bank_code, masked_account_no)` — `user_id` 제거 | `8 tests completed, 6 failed` | 사용자 간 격리 붕괴가 광범위하게 검출 |
  | M5 | `included_in_budget DEFAULT FALSE`, `display_mode DEFAULT 'HIDDEN'` | `BUILD SUCCESSFUL` | **없음 → 8번 항목 참조** |

  M1은 보고 내용과 정확히 일치한다(실패 2건, 테스트 이름까지 동일). M2는 T-023에서 지적한 "막히는 것만 보면 과도하게 좁혀도 통과한다" 사각지대가 이번엔 **처음부터** 막혔음을 보여준다 — `sameBankDifferentMaskedNoForSameUserIsAllowed`가 `count(*) = 2`까지 단언(`AccountSchemaTest.java:113-115`)하므로 저장 성공을 삼키지 않는다. T-023 FIX 지시도 반영됐다(`BudgetSchemaTest` 7건 → 8건).

## 6. 완료 기준 4 — 유니크 위반 단언의 제약 이름 확인
- 판정: PASS
- 검증 대상: `AccountSchemaTest.java:97-98`, `159-160`, `168-170`
- 사유: 유니크 위반은 `.rootCause().hasMessageContaining("uq_accounts_user_bank_masked_no")`로 제약 이름을 본다. NOT NULL이나 CHECK 위반으로 실패해도 초록이 되는 경로가 닫혔다. CHECK 2건도 같은 방식(`ck_accounts_consent_status`, `ck_accounts_display_mode`)으로 이름을 확인한다. T-023 FIX 지시 2번이 새 Task에 선제 적용됐다.
- 참고: `undefinedEnumValuesAreRejected`(`AccountSchemaTest.java:145-171`)가 엔티티를 우회해 raw SQL로 넣는 것은 옳은 선택이다. 열거형 매핑 때문에 엔티티 경로로는 CHECK가 실제로 붙었는지 알 수 없다.

## 7. 완료 기준 5 — `./gradlew build --rerun-tasks`
- 판정: PASS
- 사유: 검증자가 직접 실행했다.
  ```
  BUILD SUCCESSFUL in 44s
  7 actionable tasks: 7 executed
  ```
  테스트 결과 XML에서 실행 건수를 직접 확인했다 — 총 29건, 실패·에러·스킵 0.
  ```
  TeloApplicationTests            tests="1" skipped="0" failures="0" errors="0"
  accounts 스키마 제약 검증        tests="8" skipped="0" failures="0" errors="0"
  budget_periods·status_thresholds tests="8" skipped="0" failures="0" errors="0"
  EasyCodefUtilJdk25Test          tests="1" skipped="0" failures="0" errors="0"
  기준 타임존 KST 규칙             tests="5" skipped="0" failures="0" errors="0"
  운영 프로필 기동 검증             tests="2" skipped="0" failures="0" errors="0"
  users·user_consents 스키마 검증  tests="4" skipped="0" failures="0" errors="0"
  ```
  backend-engineer가 보고한 수치와 일치한다.

## 8. 인덱스 2종과 DB DEFAULT 값이 테스트로 고정되지 않았다
- 판정: **FIX**
- 검증 대상: `V202609202355__create_accounts.sql:35,38`(인덱스), `:21,22,25`(DEFAULT) vs `AccountSchemaTest.java` 전체
- 사유: 변이 M3·M5로 직접 확인했다.
  - **M3**: `CREATE INDEX ix_accounts_user_id`와 `ix_accounts_consent_status_last_synced_at`을 **둘 다 지워도** 8건 전부 통과하고 `BUILD SUCCESSFUL`이다. 인덱스는 `ddl-auto: validate`도 보지 않고 어떤 단언도 보지 않는다. 입력 명세와 `docs/09-db-design.md` 3.1이 인덱스 2종을 명시적 산출물로 적었는데, 지워져도 아무도 눈치채지 못하는 상태다. 특히 `(consent_status, last_synced_at)`은 T-015 동기화 스케줄러가 대상 계좌를 고를 때 쓰는 것이라, 없어지면 계좌 수가 늘어난 뒤 풀스캔으로 조용히 느려진다 — 전형적인 조용한 실패다.
  - **M5**: `included_in_budget`의 `DEFAULT TRUE`를 `FALSE`로, `display_mode`의 `DEFAULT 'BADGE'`를 `'HIDDEN'`으로 뒤집어도 8건 전부 통과한다. `newAccountStartsWithExpectedDefaults`(`AccountSchemaTest.java:188-206`)는 엔티티 생성자가 채운 값을 되읽을 뿐이라 **DB DEFAULT 값을 전혀 보지 않는다.** (DEFAULT의 *존재*는 `undefinedEnumValuesAreRejected`의 raw INSERT가 세 컬럼을 생략하므로 간접적으로 걸린다 — 없으면 NOT NULL 위반 메시지가 나와 제약 이름 단언이 깨진다. 그러나 *값*은 무방비다.)
- 수정 지시:
  1. `AccountSchemaTest.java:206` 뒤에 인덱스 존재 단언을 추가한다. 예: `SELECT indexdef FROM pg_indexes WHERE tablename = 'accounts' AND indexname = ?`로 `ix_accounts_user_id`, `ix_accounts_consent_status_last_synced_at` 두 건을 조회해 null이 아닌지, 그리고 `indexdef`에 대상 컬럼이 들어 있는지 본다(이름만 보면 컬럼이 바뀐 변이를 놓친다). 이 단언이 있었다면 M3가 잡혔다.
  2. (권장, 우선순위 낮음) `newAccountStartsWithExpectedDefaults`에 DB DEFAULT 값을 보는 단언을 더한다. `JdbcTemplate`으로 `included_in_budget`·`display_mode`·`reauth_required`를 생략한 raw INSERT를 하고 되읽어 `true`/`'BADGE'`/`false`인지 확인한다. 다만 애플리케이션 경로에서는 엔티티가 항상 값을 채우므로 DEFAULT가 실제로 발화하지 않는다 — 실질 위험은 1번보다 낮다.
- 참고: 이 사각지대는 `BudgetSchemaTest`·`UserSchemaTest`에도 같이 있다(인덱스를 보는 단언이 어느 쪽에도 없다). T-023 리포트가 이 축을 다루지 않은 것은 검증자 쪽 누락이다. 이번 Task에서만 고치면 관례가 갈리므로, 고칠 때 세 테스트에 같은 방식을 적용할지 리더가 정하는 편이 낫다.

## 9. 범위 준수
- 판정: PASS
- 사유: 커밋 `b33d422`가 건드린 파일은 6개뿐이고 전부 스키마·엔티티·열거형·리포지토리·테스트다. 선점한 것이 없다.
  - 코드에프 연동: `account` 패키지에 코드에프 클라이언트·DTO·설정이 없다. `codef_connected_id`는 컬럼과 필드로만 존재하며 값을 만드는 주체가 없다. 기존 `com.petgyebu.telo.codef.EasyCodefUtilJdk25Test`는 이번 커밋이 만든 것이 아니다(변경 파일 목록에 없음).
  - 계좌 연결 API: 컨트롤러·서비스·DTO가 하나도 없다.
  - 2-way 추가인증: `reauth_required`를 바꾸는 코드 경로가 없다. 엔티티에 setter도 상태 전이 메서드도 없다.
  - 동기화 스케줄러: `@Scheduled`·ShedLock·`last_synced_at` 갱신 코드가 없다.
  - 마이그레이션에 `INSERT` 한 줄 없다(은행 목록 같은 시드 데이터 선점 없음).
  반대 방향으로 빠진 것도 없다 — 입력 명세가 요구한 14개 컬럼, CHECK 2종, UNIQUE 1종, INDEX 2종, 엔티티 1개, 열거형 2개, 리포지토리 1개가 모두 있다.

## 10. 기존 관례 일치 (T-001 `user` · T-023 `budget`)
- 판정: PASS
- 검증 대상: `account/domain/*`, `account/repository/*` vs `user/domain/User.java`, `budget/domain/BudgetPeriod.java`
- 사유: 패키지 레이아웃이 `{도메인}/domain` + `{도메인}/repository`로 세 도메인 모두 같다. 엔티티는 `@Entity` + `@Table` + `@Getter` + `@NoArgsConstructor(PROTECTED)` + 공개 생성자 + `Objects.requireNonNull("필드명")`, 연관은 `@ManyToOne(LAZY, optional=false)` + `@JoinColumn(nullable=false)`, 열거형은 `@Enumerated(STRING)` + `length`. 마이그레이션 파일명 `V{yyyyMMddHHmm}__{설명}.sql`. 테스트는 Testcontainers `@SpringBootTest` + `@Container @ServiceConnection` + `postgres:16-alpine` + 한글 `@DisplayName` + `DataIntegrityViolationException` 단언. 시각은 `OffsetDateTime.now(AppZone.clock())`로 T-052 KST 단일 출처를 따르고 `ZoneId.of("Asia/Seoul")` 재사용이 없다. 리포지토리는 `JpaRepository`만이고 조회 메서드가 없는 것도 T-023과 같다.
- 참고(지적 아님): `AccountSchemaTest.java:210`의 `givenUser`만 `OffsetDateTime.now(AppZone.clock())`를 쓰지 않고 직접 같은 식을 쓴다 — 실은 같은 식이라 문제없다(재확인함: `OffsetDateTime.now(AppZone.clock())` 맞다).

## 11. backend-engineer 자기 판단 검토
- 판정: PASS (타당)
- 요약 파일은 판단을 **3건**으로 적었다(`t006_backend_summary.md:33-44`). 리더 지시의 "4건"과 수가 다르며, 지시가 "3번"으로 지목한 생성자 고정값은 요약의 **1번**이다. 네 번째로 볼 만한 것은 "남긴 미해결 사항"의 `display_mode`/`included_in_budget` 제약 미표현이라 판단해 함께 검토했다.
- (a) **생성자가 `consentStatus=ACTIVE`, `includedInBudget=true`, `displayMode=BADGE`, `reauthRequired=false` 4개를 고정하고 인자로 받지 않음** (`Account.java:115-119`) — **타당하며 F-TEDWWF 계좌 연결 흐름과 충돌하지 않는다.**
  - `docs/02-requirements-features.md` F-TEDWWF **trigger**·**action**에 따르면 행이 만들어지는 시점은 "인증 성공 → 거래 내역 조회 권한 동의 → 사용자가 동의한 계좌들의 연결 정보를 각각 저장"이다. 즉 **동의가 끝난 계좌만 저장된다.** `PENDING` 같은 중간 상태로 행을 먼저 만드는 단계가 명세에 없고, `ck_accounts_consent_status`도 `ACTIVE`/`EXPIRED`/`REVOKED` 셋만 허용한다. 연결 직후 상태가 `ACTIVE` 하나뿐이라는 판단이 명세와 맞다.
  - **exceptions**의 "대행사 또는 지원 은행에서 일시적 장애가 발생하면 **연결 정보를 저장하지 않고** 재시도 방법을 안내한다"가 이를 뒷받침한다 — 실패 시 행 자체가 없어야 하므로 실패 상태를 담을 필드가 필요 없다.
  - `reauthRequired=false`도 맞다. 이 플래그는 "무인 동기화 실패 대응"(명세 비고)이며, 방금 사용자 주도 인증을 통과한 계좌는 정의상 재인증이 필요 없다.
  - `includedInBudget=true` / `displayMode=BADGE`는 DB DEFAULT와 같은 값이라 두 경로가 어긋나지 않는다.
  - **다만 남는 결과**: 상태를 바꾸는 메서드가 하나도 없어 `EXPIRED`·`REVOKED`·`reauthRequired=true`·집계 제외로 가는 경로가 코드에 존재하지 않는다. 이번 Task 범위(스키마·엔티티·리포지토리)에서는 옳은 최소 구현이지만, **T-009·T-015 입력에 "상태 전이 메서드를 엔티티에 추가한다"를 명시적으로 옮겨야 한다.** 지금은 `updatedAt`을 갱신할 경로도 없어 T-023에서 지적한 `updated_at` 고정 문제가 `accounts`에도 그대로 있다.
- (b) **`createdAt`/`updatedAt`을 애플리케이션이 채움** (`Account.java:89-94`) — 타당. `User.joinedAt`의 근거(`insertable=false`면 저장 직후 객체 필드가 null이라 응답에 쓸 수 없다)를 그대로 따르며, T-001·T-023과 같은 패턴이다.
- (c) **`accountName`에 `length = 100` 명시** (`Account.java:67`) — 타당. `validate`가 길이를 보지 않더라도 엔티티가 스키마를 문서화하는 값이 있고 T-023 관례와 같다. 과잉이 아니다.
- (d) **`display_mode`/`included_in_budget` 관계를 DB 제약으로 표현하지 않음** — 타당. `docs/09-db-design.md` 3.1 말미와 입력 명세가 명시적으로 그렇게 정했다. 읽는 쪽에서 무시하는 로직이 계좌 목록 API Task 몫이라는 판단도 범위에 맞다. 이 역시 후속 Task 입력에 옮겨야 하는 항목이다.

## 12. 트러블슈팅 기록을 추가하지 않은 판단
- 판정: PASS
- 검증 대상: `t006_backend_summary.md:150-152` vs `CLAUDE.md` "트러블슈팅 기록 (자동 갱신)"
- 사유: CLAUDE.md의 기록 대상 6종에 해당하는 사건이 이번 작업에 없다. (1) 원인 추적에 한 단계 이상 추론이 필요한 문제 — 없다. 변이 실행은 디버깅이 아니라 **의도된 검증**이었다. (2) 겉보기 성공 뒤의 실패 — 이번엔 겪은 것이 아니라 이미 기록된 두 건(`--rerun-tasks` 없이는 테스트 미실행, 마이그레이션 순서 스크립트의 "건너뛴다 = 통과")을 **피해 간** 것이다. CLAUDE.md는 "해결한 문제"를 적으라고 하지 "기존 기록을 재적용했다"를 적으라고 하지 않는다. (3) 라이브러리 좌표 차이·(4) 보안 문제·(5) 고쳤는데 증상이 남은 경우·(6) 검증 방법의 오류 — 모두 해당 없음. 기존 16개 항목의 숫자 정합성도 이번 커밋이 `docs/11-troubleshooting-log.md`를 건드리지 않았으므로 그대로다.
- 참고(지적 아님): 굳이 기록할 만한 관찰이 하나 있다면 "`bank_code`를 `BIGINT`로 바꾸면 해당 테스트 1건이 아니라 클래스 전체 8건이 죽는다(Hibernate `validate`가 컨텍스트 로딩에서 잡기 때문)"인데, 이는 문제가 아니라 도구의 정상 동작이라 기록 대상이 아니다.

---

## 리더 확인 요청

- FIX 1건(8번)은 스키마 자체가 아니라 **테스트 커버리지**의 문제다. 마이그레이션·엔티티는 명세와 완전히 일치하므로 푸시를 막을 사유는 아니다. 다만 같은 사각지대가 `BudgetSchemaTest`·`UserSchemaTest`에도 있어, 이번 Task에서 고칠지 별도 Task로 뺄지 리더 판정이 필요하다.
- 후속 Task 입력으로 옮겨야 할 것 2건: (1) `accounts` 상태 전이 메서드와 `updated_at` 갱신 수단(T-009·T-015), (2) `included_in_budget = TRUE`인데 `display_mode = 'HIDDEN'`인 행을 읽는 쪽에서 무시하는 로직(계좌 목록 API Task).
