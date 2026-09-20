# T-006 구현 요약: `accounts` 스키마와 엔티티

- 기능 ID: F-TEDWWF (은행 계좌 연결)
- 브랜치: `feature/F-TEDWWF-schema`
- 커밋: `b33d422 feat: accounts 스키마와 엔티티 (T-006)`
- 푸시·PR 없음 (리더 지시 대기)

## 항목별 결과

- [완료] `accounts` 마이그레이션: `src/main/resources/db/migration/V202609202355__create_accounts.sql` — 14개 컬럼, CHECK 2개, UNIQUE 1개, INDEX 2개
- [완료] 엔티티·열거형: `src/main/java/com/petgyebu/telo/account/domain/Account.java`, `ConsentStatus.java`, `DisplayMode.java`
- [완료] 리포지토리: `src/main/java/com/petgyebu/telo/account/repository/AccountRepository.java` — `JpaRepository`만, 조회 메서드 없음
- [완료] 스키마 테스트: `src/test/java/com/petgyebu/telo/account/AccountSchemaTest.java` — 8개
- [해당 없음] 코드에프 연동, 계좌 연결 API, 2-way 추가인증, 동기화 스케줄러 — T-009·T-015 범위라 만들지 않았다

## 만든 파일

| 파일 | 내용 |
|---|---|
| `src/main/resources/db/migration/V202609202355__create_accounts.sql` | `accounts` 테이블 |
| `src/main/java/com/petgyebu/telo/account/domain/Account.java` | 엔티티 |
| `src/main/java/com/petgyebu/telo/account/domain/ConsentStatus.java` | `ACTIVE`/`EXPIRED`/`REVOKED` |
| `src/main/java/com/petgyebu/telo/account/domain/DisplayMode.java` | `BADGE`/`HIDDEN` |
| `src/main/java/com/petgyebu/telo/account/repository/AccountRepository.java` | `JpaRepository<Account, Long>` |
| `src/test/java/com/petgyebu/telo/account/AccountSchemaTest.java` | 스키마 제약 검증 8개 |

기존 파일은 고치지 않았다.

## 문서와 다르게 결정한 것

없다. `docs/09-db-design.md` 3.1절과 입력 명세의 컬럼·제약·인덱스를 그대로 옮겼다.

명세에 없어 판단한 것 세 가지:

1. **엔티티 생성자 인자** — `user`, `bankCode`, `codefConnectedId`, `maskedAccountNo`,
   `accountName`, `consentExpiresAt`, `now` 일곱 개만 받는다. `consentStatus = ACTIVE`,
   `includedInBudget = true`, `displayMode = BADGE`, `reauthRequired = false`는 연결 직후
   상태가 하나뿐이라 생성자가 고정한다. `lastSyncedAt`은 동기화 전이라 null이다.
   이 값들을 바꾸는 경로(해제, 만료, 재인증 요구, 집계 제외 토글)는 뒤 Task 몫이라 두지 않았다.
2. **`createdAt`/`updatedAt`을 애플리케이션이 채운다** — DB에 `DEFAULT now()`가 있지만
   `User.joinedAt`·`BudgetPeriod.createdAt`과 같은 이유로 엔티티가 값을 넣는다.
   `insertable = false`로 두면 저장 직후 객체 필드가 null이라 응답에 쓸 수 없다.
3. **`account_name`의 `length = 100`** — 명세의 `VARCHAR(100)`을 엔티티에도 명시했다.
   `ddl-auto: validate`가 길이까지 보지는 않지만 T-023 관례를 따랐다.

## 검증

### 1. 전체 빌드 (`--rerun-tasks` 필수)

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 44s
7 actionable tasks: 7 executed
```

`7 executed`다. `--rerun-tasks` 없이 돌리면 `7 up-to-date`가 나와 테스트가 아예 실행되지
않는다(트러블슈팅 기록 참조). 테스트 결과 XML로 실행 건수를 직접 확인했다:

```
TeloApplicationTests        tests="1" skipped="0" failures="0" errors="0"
account.AccountSchemaTest   tests="8" skipped="0" failures="0" errors="0"
budget.BudgetSchemaTest     tests="8" skipped="0" failures="0" errors="0"
codef.EasyCodefUtilJdk25Test tests="1" skipped="0" failures="0" errors="0"
common.time.AppZoneTest     tests="5" skipped="0" failures="0" errors="0"
migration.PostgresMigrationTest tests="2" skipped="0" failures="0" errors="0"
user.UserSchemaTest         tests="4" skipped="0" failures="0" errors="0"
```

`PostgresMigrationTest` 2개 통과 = Flyway가 실제로 돌고 Hibernate `validate`를 통과했다.

### 2. 마이그레이션 순서 검사 (커밋 후 실행)

```
$ scripts/check-migration-order.sh
기준: origin/main (최대 버전 202609202314)
  [통과] V202609202355__create_accounts.sql (버전 202609202355)
마이그레이션 순서 검사 통과.
exit=0
```

이 스크립트는 `git diff --diff-filter=A origin/main...HEAD`로 추가 파일을 찾으므로
커밋 전 untracked 상태에서는 "추가된 마이그레이션이 없다. 검사를 건너뛴다"를 출력하고
똑같이 0으로 끝난다(트러블슈팅 16번). 그래서 **커밋 후에 돌렸고**, 출력에 `[통과]`와
파일명이 찍힌 것으로 실제 검사가 일어났음을 확인했다.

### 3. `bank_code` 앞자리 0 보존 — 이 Task의 명시된 완료 기준

`bankCodeKeepsLeadingZero` 테스트가 세 겹으로 본다.

1. `'004'`를 저장하고 `findById`로 읽어 `"004"`인지
2. JPA 영속성 컨텍스트를 우회해 `JdbcTemplate`으로 DB의 실제 값을 읽어 `"004"`인지
3. `information_schema.columns`에서 `bank_code`의 `data_type`이 `character varying`인지

**역방향 확인:** 마이그레이션의 `bank_code`를 `BIGINT`로 바꿔 돌렸더니 8개 테스트가
전부 실패했다. Hibernate `validate`가 `String` 필드와 `BIGINT` 컬럼의 불일치를 컨텍스트
로딩 단계에서 잡아 클래스 전체가 죽는다.

```
$ # bank_code를 BIGINT로 바꾼 상태
8 tests completed, 8 failed
BUILD FAILED in 35s
```

즉 정수로 되돌리는 변경은 조용히 통과할 수 없다. 이후 파일을 원복하고 재확인했다.

### 4. 유니크 제약 양방향 증명

`UNIQUE (user_id, bank_code, masked_account_no)`를 네 방향에서 본다.

- 막힌다: 같은 사용자·같은 은행·같은 마스킹 번호 → `DataIntegrityViolationException`,
  `.rootCause().hasMessageContaining("uq_accounts_user_bank_masked_no")`로 **제약 이름까지**
  확인한다. 이름을 안 보면 NOT NULL이나 CHECK 위반으로 실패해도 초록이 된다
- 허용된다: 같은 사용자·**같은 은행·다른 마스킹 번호** (동일 은행 복수 계좌)
- 허용된다: 같은 사용자·**다른 은행·같은 마스킹 번호** (마스킹 번호는 은행이 다르면 겹칠 수 있다)
- 허용된다: 다른 사용자·같은 계좌 식별값

**역방향 확인 (T-023 QA 교훈):** 제약을 `UNIQUE (user_id)`로 좁혀(이름은 그대로 둔 채)
돌렸더니, 중복 거부 테스트는 **여전히 통과했고** 허용 테스트 둘이 깨졌다.

```
$ # UNIQUE (user_id, bank_code, masked_account_no) → UNIQUE (user_id)
accounts 스키마 제약 검증 > 같은 사용자가 다른 은행의 같은 마스킹 번호 계좌를 함께 가질 수 있다 FAILED
accounts 스키마 제약 검증 > 같은 사용자가 같은 은행의 다른 계좌를 함께 가질 수 있다 — 동일 은행 복수 계좌 FAILED
8 tests completed, 2 failed
BUILD FAILED in 37s
```

"중복이 막힌다"만 봤다면 사용자당 계좌 1개로 제한하는 스키마가 그대로 통과했을 것이다.
허용 쪽 단언이 그것을 잡았다. 이후 파일을 원복하고 재확인했다.

### 5. 그 밖에 테스트가 보는 것

- `ON DELETE CASCADE`: 사용자를 지우면 계좌도 사라진다 (탈퇴 F-ZPNVKT가 이 동작에 의존)
- `CHECK` 2개: 열거형 때문에 엔티티로는 잘못된 값이 들어갈 수 없어, `JdbcTemplate`으로
  `consent_status = 'PENDING'`, `display_mode = 'COLLAPSED'`를 직접 넣어 거부되는지 본다.
  여기도 제약 이름(`ck_accounts_consent_status`, `ck_accounts_display_mode`)을 확인한다
- 연결 직후 기본값: `ACTIVE` / 집계 포함 / `BADGE` / 재인증 불필요 / `lastSyncedAt` null

## 남긴 미해결 사항

- **`display_mode`와 `included_in_budget`의 관계는 DB 제약으로 표현하지 않았다.**
  설계 문서가 그렇게 정했다(`included_in_budget = FALSE`일 때만 의미, 포함 상태에서는 무시).
  따라서 `included_in_budget = TRUE`인데 `display_mode = 'HIDDEN'`인 행이 DB에 존재할 수
  있다. 읽는 쪽에서 무시해야 하며, 그 로직은 계좌 목록 API Task 몫이다.
- **리포지토리에 조회 메서드가 없다.** `findByUserId` 같은 것은 실제 사용처 Task에서
  필요할 때 추가한다(T-023 관례).
- **코드에프 연동 관련 컬럼은 채워지지 않는다.** `codefConnectedId`는 NOT NULL이지만
  값을 만드는 주체(코드에프 계좌 연결)가 아직 없어 현재는 테스트에서만 채운다.
  코드에프 데모 승인 대기 중이며 T-009 범위다.
- **트러블슈팅 기록 없음.** 이번 작업에서 한 단계 이상 추론이 필요한 문제가 없었다.
  마이그레이션 순서 스크립트의 "건너뛴다 = 통과"와 `--rerun-tasks` 없이는 테스트가 돌지
  않는 문제는 이미 각각 기록돼 있고 이번에는 그대로 피해 갔다.

---

## QA 지적 반영 (FIX 1건 — 인덱스 단언 누락)

QA 결과 PASS 11 / FIX 1. FIX는 인덱스 단언 누락이며, 사용자 확인을 받아 **세 테스트 모두**
같은 방식으로 고쳤다.

### 문제

QA가 `CREATE INDEX` 2줄을 통째로 지우고 돌렸는데 `BUILD SUCCESSFUL`, 8건 전부 통과했다.
`ddl-auto: validate`는 인덱스를 보지 않고, 인덱스는 **틀린 답이 아니라 느린 답**을 내므로
제약 테스트로는 원리적으로 잡히지 않는다. 마이그레이션에서 조용히 사라져도 아무것도
빨간불이 되지 않는 구멍이었다.

### 고친 것 (테스트 파일 3개만)

마이그레이션·엔티티·프로덕션 코드는 건드리지 않았다.

| 파일 | 추가 |
|---|---|
| `src/test/java/com/petgyebu/telo/user/UserSchemaTest.java` | `designedIndexesExist` + `assertIndex` 헬퍼 |
| `src/test/java/com/petgyebu/telo/budget/BudgetSchemaTest.java` | 같음 |
| `src/test/java/com/petgyebu/telo/account/AccountSchemaTest.java` | 같음 |

세 파일이 **같은 모양의 헬퍼**를 각자 갖는다. 공유 유틸리티 클래스는 만들지 않았다 —
기존 세 테스트가 각각 독립적인 구조라 그 구조를 유지했다.

```java
private void assertIndex(String tableName, String indexName, String expectedColumns) {
    List<String> definitions = new JdbcTemplate(dataSource).queryForList(
            "SELECT indexdef FROM pg_indexes "
                    + "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
            String.class, tableName, indexName);

    assertThat(definitions).as("%s 테이블에 인덱스 %s가 없다", tableName, indexName).hasSize(1);
    assertThat(definitions.get(0))
            .as("인덱스 %s의 대상 열 구성이 설계와 다르다", indexName)
            .endsWith("(" + expectedColumns + ")");
}
```

**이름만 보지 않는다.** `pg_indexes.indexdef`의 괄호 안 열 목록을 그대로 맞춰 본다.
`endsWith`를 쓰므로 열의 **순서**와 **정렬 방향(DESC)**까지 고정된다. T-023·T-006에서
반복 확인된 교훈 — 이름을 유지한 채 열 구성만 바꾸는 변이는 이름 단언을 통과한다 — 이
여기에도 그대로 적용된다.

고정한 인덱스 6개:

| 테이블 | 인덱스 | 단언한 열 구성 |
|---|---|---|
| `users` | `ix_users_joined_at` | `joined_at` |
| `user_consents` | `ix_user_consents_user_id_type_consented_at` | `user_id, consent_type, consented_at DESC` |
| `budget_periods` | `ix_budget_periods_user_id_status` | `user_id, status` |
| `budget_periods` | `ix_budget_periods_status_period_end` | `status, period_end` |
| `accounts` | `ix_accounts_user_id` | `user_id` |
| `accounts` | `ix_accounts_consent_status_last_synced_at` | `consent_status, last_synced_at` |

DEFAULT 값 단언은 넣지 않았다. QA가 우선순위 낮음으로 판정했다 — 애플리케이션 경로에서
엔티티가 항상 값을 채워 DEFAULT가 발화하지 않는다.

### 양방향 증명 — 변이 6회 실제 출력

인덱스 6개를 각각 지우거나(3회) 이름은 그대로 둔 채 대상 열만 바꿔(3회) 돌렸다.
마이그레이션 파일은 변이 전 백업해 두고 매회 원복했다.

```
### 변이1: ix_users_joined_at 삭제
users·user_consents 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
> Task :test FAILED
5 tests completed, 1 failed
BUILD FAILED in 37s

### 변이2: ix_user_consents_user_id_type_consented_at 의 DESC 제거 (이름 유지)
users·user_consents 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
> Task :test FAILED
5 tests completed, 1 failed
BUILD FAILED in 37s

### 변이3: ix_budget_periods_user_id_status 삭제
budget_periods·status_thresholds 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 37s

### 변이4: ix_budget_periods_status_period_end 열 순서 (status, period_end) → (period_end, status) (이름 유지)
budget_periods·status_thresholds 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 37s

### 변이5: ix_accounts_user_id 삭제
accounts 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 37s

### 변이6: ix_accounts_consent_status_last_synced_at 에서 last_synced_at 열 제거 (이름 유지)
accounts 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
> Task :test FAILED
9 tests completed, 1 failed
BUILD FAILED in 37s
```

6회 모두 `BUILD FAILED`이고, **매번 실패한 것은 새 인덱스 테스트 하나뿐**이다
(`9 tests completed, 1 failed`). 이것이 두 가지를 동시에 보여준다.

1. 새 단언이 실제로 변이를 잡는다 (전에는 같은 변이가 `BUILD SUCCESSFUL`이었다)
2. 다른 테스트는 인덱스에 전혀 반응하지 않는다 — QA가 지적한 구멍이 그대로 재현된다.
   인덱스 단언을 따로 두지 않으면 잡을 방법이 없다

변이 2·4·6은 인덱스 **이름을 유지**한 채 열 구성만 바꿨다. 이름만 보는 단언이었다면
전부 통과했을 것이다.

### 반영 후 전체 빌드

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 45s
7 actionable tasks: 7 executed
```

```
TeloApplicationTests        tests="1" skipped="0" failures="0" errors="0"
account.AccountSchemaTest   tests="9" skipped="0" failures="0" errors="0"   (8 → 9)
budget.BudgetSchemaTest     tests="9" skipped="0" failures="0" errors="0"   (8 → 9)
codef.EasyCodefUtilJdk25Test tests="1" skipped="0" failures="0" errors="0"
common.time.AppZoneTest     tests="5" skipped="0" failures="0" errors="0"
migration.PostgresMigrationTest tests="2" skipped="0" failures="0" errors="0"
user.UserSchemaTest         tests="5" skipped="0" failures="0" errors="0"   (4 → 5)
```

세 스키마 테스트가 각각 1개씩 늘어 29 → 32건이 됐다. 마이그레이션 파일은 변이 후
원복돼 `git status`에서 깨끗하다.
