# T-001 QA 리포트 — users·user_consents 스키마와 엔티티

- 작성: 2026-09-18
- 검증 대상: 브랜치 `feature/F-QJXRMD-schema`, 커밋 `a449973`·`7ebc592`
- 입력: `_workspace/t001_00_input.md`, `_workspace/t001_backend_summary.md`, `docs/09-db-design.md` 0·2.1·2.2·9장, `docs/02-requirements-features.md` F-QJXRMD
- 검증 방식: Sprint 0 때와 같이 요약을 근거로 삼지 않고 모든 명령을 직접 재실행했다. 스키마는 `ddl-auto: validate`에 맡기지 않고 별도 PostgreSQL 16 컨테이너를 띄워 마이그레이션을 직접 적용한 뒤 `information_schema`·`pg_constraint`·`pg_indexes`를 읽고 제약 위반 INSERT를 실제로 날려 확인했다.

## 종합

완료 기준 두 가지가 모두 실제로 통과한다. backend-engineer가 요약에 적은 실행 결과 중 사실과 다른 것은 없었다. `09-db-design.md` 2.1·2.2절의 컬럼 8개와 5개, 타입, NULL 허용, 길이, CHECK 값 목록, UNIQUE, FK 삭제 규칙, 인덱스 두 개가 마이그레이션 SQL과 한 글자도 어긋나지 않는다. 엔티티 필드도 마찬가지다. 범위를 넘는 컨트롤러·서비스·DTO·JWT 코드는 한 줄도 들어오지 않았고, 회귀도 없다.

문제는 코드가 아니라 두 군데의 서술에 있다. 하나는 요약이 "고치지 않았다"고 적은 README가 실제로는 수정된 채 커밋되지 않고 작업 트리에 떠 있다는 점이고, 다른 하나는 `User`에 컬렉션 매핑을 두지 않은 근거로 적은 Hibernate 동작 설명이 사실과 다르다는 점이다. 결론 자체는 둘 다 옳아서 코드를 되돌릴 이유는 없지만, 근거가 틀린 채 남으면 다음 Task에서 잘못된 전제로 재사용된다.

| 구분 | 건수 |
|---|---|
| PASS | 9 |
| FIX | 2 |
| REDO | 0 |
| 미검증 | 1 |

---

## 1. 스키마 교차 비교

### 1-1. `users` 컬럼 정의 — 문서 vs 마이그레이션 SQL vs 실제 DB

**판정: PASS**

마이그레이션을 빈 PostgreSQL 16에 직접 적용하고 카탈로그를 읽었다.

```
$ docker run -d --name qa-t001 -e POSTGRES_PASSWORD=p -e POSTGRES_DB=qa postgres:16-alpine
$ docker exec -i qa-t001 psql -U postgres -d qa -v ON_ERROR_STOP=1 \
    < src/main/resources/db/migration/V202609181920__create_users.sql
CREATE TABLE / CREATE INDEX / CREATE TABLE / CREATE INDEX

$ SELECT table_name, column_name, data_type, character_maximum_length, is_nullable, column_default, is_identity ...
 users | id                   | bigint                   |     | NO  |        | YES
 users | provider             | character varying        |  10 | NO  |        | NO
 users | provider_user_id     | character varying        | 255 | NO  |        | NO
 users | email                | character varying        | 320 | YES |        | NO
 users | character_type       | character varying        |  10 | YES |        | NO
 users | character_changed_at | timestamp with time zone |     | YES |        | NO
 users | joined_at            | timestamp with time zone |     | NO  | now()  | NO
 users | last_login_at        | timestamp with time zone |     | YES |        | NO
```

`09-db-design.md` 2.1절의 8행과 컬럼명·타입·길이·NULL 허용이 전부 일치한다. `id`가 identity이고 `joined_at`만 DEFAULT를 가진 것도 문서 그대로다. 명세에 없는 컬럼이 추가되지 않았고 비밀번호 컬럼도 없다(F-QJXRMD rules "서비스는 자체 비밀번호를 저장하지 않는다").

F-QJXRMD의 dataSpec이 요구한 "사용자 식별자, 공급자 종류, 공급자 사용자 식별자, 가입 시각, 마지막 로그인 시각"은 각각 `id`, `provider`, `provider_user_id`, `joined_at`, `last_login_at`으로 전부 있다. 리프레시 토큰 폐기 상태는 dataSpec에 있지만 Redis 담당이라 테이블을 만들지 않은 것이 맞다(`t001_00_input.md` 주의 항목).

### 1-2. `email`에 유니크가 걸리지 않았는가

**판정: PASS** — 이번 검증에서 가장 중요한 항목이라 두 방향으로 확인했다.

`pg_constraint`에 `users` 대상 유니크는 `uq_users_provider_provider_user_id` 하나뿐이고, `pg_indexes`에도 `email` 단독 인덱스가 없다.

```
 users | ck_users_character_type            | c | CHECK character_type IN ('DOG','CAT')
 users | ck_users_provider                  | c | CHECK provider IN ('KAKAO','APPLE')
 users | uq_users_provider_provider_user_id | u | UNIQUE (provider, provider_user_id)
 users | users_pkey                         | p | PRIMARY KEY (id)
```

실제로 같은 이메일을 두 계정에 넣어봤다.

```
INSERT INTO users (provider, provider_user_id, email) VALUES ('KAKAO','u1','a@b.com');  -- INSERT 0 1
INSERT INTO users (provider, provider_user_id, email) VALUES ('KAKAO','u2','a@b.com');  -- INSERT 0 1
SELECT count(*) FROM users WHERE email='a@b.com';  -- 2
```

이메일 없는 계정도 들어간다(`provider_user_id` 있고 `email` 생략 → INSERT 0 1). 애플 이메일 가리기 경로가 막히지 않는다.

### 1-3. `character_type` NULL 허용과 CHECK

**판정: PASS**

`is_nullable = YES`이고, 문서가 요구한 두 값만 통과한다.

```
INSERT ... character_type='BIRD' → ERROR: violates check constraint "ck_users_character_type"
INSERT ... character_type='DOG'  → INSERT 0 1
INSERT ... (character_type 생략)  → INSERT 0 1, 값은 null
```

`CharacterType.java`의 상수 `DOG`, `CAT`이 CHECK 값과 문자열까지 같다. `provider`도 마찬가지로 `NAVER`를 거부하고, `AuthProvider`의 `KAKAO`·`APPLE`과 일치한다.

### 1-4. `user_consents` 컬럼과 `consent_type` CHECK 4종

**판정: PASS**

컬럼 5개가 문서 2.2절과 일치한다(`id` identity, `user_id` BIGINT NOT NULL, `consent_type` VARCHAR(30) NOT NULL, `consent_version` VARCHAR(20) NOT NULL, `consented_at` TIMESTAMPTZ NOT NULL). CHECK에 4종이 전부 들어 있고 `ConsentType.java`의 상수 네 개와 문자열이 같다. 4종을 한 번에 넣으면 전부 통과하고, 목록에 없는 값은 거부된다.

```
INSERT ... unnest(ARRAY['TERMS_OF_SERVICE','PRIVACY_POLICY','CODEF_THIRD_PARTY','FINANCIAL_DATA_INQUIRY'])
→ INSERT 0 4
INSERT ... 'MARKETING' → ERROR: violates check constraint "ck_user_consents_consent_type"
```

append-only 규약에 맞게 `created_at`·`updated_at` 같은 감사 컬럼을 두지 않은 것도 0장 규약대로다.

### 1-5. FK의 ON DELETE CASCADE

**판정: PASS**

제약 정의에 실제로 붙어 있다.

```
 user_consents | user_consents_user_id_fkey | f | FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
```

JPA를 통하지 않고 순수 SQL로도 확인했다. 동의 4건이 달린 사용자 1을 지우니 0이 됐다.

```
SELECT count(*) FROM user_consents WHERE user_id=1;  -- 4
DELETE FROM users WHERE id=1;                        -- DELETE 1
SELECT count(*) FROM user_consents WHERE user_id=1;  -- 0
```

T-005 탈퇴가 `DELETE FROM users` 한 번으로 끝난다는 0장의 전제가 DB 차원에서 성립한다.

### 1-6. 인덱스

**판정: PASS**

문서가 요구한 두 개가 이름·컬럼·정렬 방향까지 그대로 있다.

```
 users         | ix_users_joined_at                         | btree (joined_at)
 user_consents | ix_user_consents_user_id_type_consented_at | btree (user_id, consent_type, consented_at DESC)
```

`consented_at DESC`가 빠지지 않은 점이 중요하다. "최신 동의 버전 조회"가 이 방향에 의존한다.

### 1-7. 엔티티 ↔ DB 매핑

**판정: PASS**

`User`의 8필드, `UserConsent`의 5필드가 컬럼과 1:1이다. 길이 지정(`length = 10/255/320/30/20`)이 SQL과 같고, `nullable = false`를 준 곳과 DB의 NOT NULL이 같다. `@Enumerated(EnumType.STRING)`이 세 열거형 컬럼에 전부 붙어 있어 0장의 "VARCHAR + CHECK, JPA `@Enumerated(STRING)`과 직결" 규약과 맞는다. 별칭 없는 `providerUserId`·`characterChangedAt`·`lastLoginAt`은 Spring의 기본 물리 명명 전략이 snake_case로 바꾸며, `ddl-auto: validate`가 도는 `PostgresMigrationTest`·`UserSchemaTest`가 통과하므로 실제로 맞물린다.

`GENERATED ALWAYS AS IDENTITY`와 `GenerationType.IDENTITY` 조합도 문제없다. Hibernate가 INSERT에서 `id`를 빼기 때문이다. 참고로 이 조합은 명시적 id 삽입을 DB가 막는다.

```
INSERT INTO users (id, provider, provider_user_id) VALUES (999,'KAKAO','u99');
→ ERROR: cannot insert a non-DEFAULT value into column "id" ... GENERATED ALWAYS
```

---

## 2. backend-engineer 주장 재현

| # | 주장 | 결과 |
|---|---|---|
| 1 | `./gradlew build` 통과 | **PASS.** `BUILD SUCCESSFUL in 11s` |
| 2 | `PostgresMigrationTest` 통과 | **PASS.** `tests="2" skipped="0" failures="0" errors="0"` |
| 3 | 복합 유니크 동작, 공급자 다르면 허용 | **PASS.** 아래 참조 |
| 4 | `ON DELETE CASCADE` 동작 | **PASS.** 1-5 참조 |
| 5 | `email` null 저장·재조회 | **PASS.** 1-2 참조 |
| 6 | 테스트 8건, skipped 0 | **PASS.** 아래 참조 |
| 7 | `check-migration-order.sh` 통과 | **PASS.** 아래 참조 |

테스트 결과 XML을 직접 열었다. 합계 8건, 스킵 0건, 실패·오류 0건이다.

```
TEST-com.petgyebu.telo.TeloApplicationTests.xml            tests="1" skipped="0" failures="0" errors="0"
TEST-com.petgyebu.telo.codef.EasyCodefUtilJdk25Test.xml    tests="1" skipped="0" failures="0" errors="0"
TEST-com.petgyebu.telo.migration.PostgresMigrationTest.xml tests="2" skipped="0" failures="0" errors="0"
TEST-com.petgyebu.telo.user.UserSchemaTest.xml             tests="4" skipped="0" failures="0" errors="0"
```

복합 유니크는 JPA 경로(테스트 통과)와 SQL 경로 양쪽에서 확인했다. 같은 `(KAKAO, u1)`은 거부되고, 공급자만 다른 `(APPLE, u1)`은 별도 행으로 들어간다. F-QJXRMD rules의 "같은 사람이 카카오와 애플로 각각 가입한 경우 별도 계정으로 취급"이 DB 차원에서 성립한다.

```
INSERT (KAKAO,u1) → INSERT 0 1
INSERT (APPLE,u1) → INSERT 0 1
INSERT (KAKAO,u1) → ERROR: duplicate key ... "uq_users_provider_provider_user_id"
```

마이그레이션 순서 검사도 그대로다.

```
$ ./scripts/check-migration-order.sh
기준: origin/main (최대 버전 0)
  [통과] V202609181920__create_users.sql (버전 202609181920)
마이그레이션 순서 검사 통과.
EXIT=0
```

파일명 `V202609181920__create_users.sql`은 T-047이 정한 `V{YYYYMMDDHHmm}__{snake_case}` 규칙과 위치(`src/main/resources/db/migration/`)를 지킨다.

---

## 3. 범위 준수

**판정: PASS**

`git diff --name-status main..HEAD`가 10개 파일이고, 그중 소스는 엔티티 2개, 열거형 3개, 리포지토리 2개, 마이그레이션 1개, 테스트 1개다. 컨트롤러·서비스·DTO·JWT·로그인 로직은 없다.

```
$ grep -rl "RestController\|@Service\|Jwt\|jwt" src/main/java
none
```

리포지토리 두 개는 `JpaRepository`만 상속한 빈 인터페이스이고 조회 메서드를 하나도 선언하지 않았다. `findByProviderAndProviderUserId`를 미리 만들지 않은 판단에 동의한다. 그것을 쓰는 로직이 T-002에 있고, 지금 만들면 테스트 없는 미사용 코드가 된다.

---

## 4. 회귀

**판정: PASS**

`main`에서 통과하던 것들이 그대로다. 로컬 프로필로 띄워 두 엔드포인트를 확인했다.

```
$ SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
Started TeloApplication in 3.362 seconds

$ curl http://localhost:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"} HTTP 200

$ curl -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/env
401
```

엔티티가 새로 생겼는데도 `local`(H2, `ddl-auto: create-drop`) 기동이 깨지지 않는다는 점이 이번에 새로 확인된 부분이다. `users`가 H2에서 예약어 문제를 일으키지 않는다. 기존 테스트 4건(`TeloApplicationTests` 1, `EasyCodefUtilJdk25Test` 1, `PostgresMigrationTest` 2)도 전부 통과한다.

---

## 5. 설계 판단 검토

### 5-1. 패키지 구조 `com.petgyebu.telo.{도메인}.{계층}`

**판정: PASS**

동의한다. `docs/07-branch-strategy.md`가 기능별 브랜치를 전제하고 `docs/10-task-backlog.md`가 스프린트를 도메인 단위로 쪼갠 이상, 도메인을 최상위에 두면 브랜치 하나가 대체로 디렉터리 하나 안에서 끝난다. `09-db-design.md`의 장 구분(계정·계좌·거래·예산·푸시·리워드)과 경계가 같다는 점도 확인했다. 다만 이후 `transaction` 도메인은 집계 함수가 `budget`·`push`와 얽히므로(9장 1번 "집계 함수를 하나로 통일한다") 도메인 경계를 넘는 공용 계산 위치가 필요해진다. T-001 시점에 미리 만들지 않은 것은 옳고, 그 시점에 다시 판단하면 된다.

열거형을 `user.domain`에 둔 것도 적절하다. 셋 다 `users`·`user_consents`의 컬럼값이다.

### 5-2. Lombok 범위

**판정: PASS**

`@Getter` + `@NoArgsConstructor(PROTECTED)`만 쓴 것에 동의한다. `@Data`가 `toString`을 만든다는 지적은 사실이고, 그 `toString`은 `email`과 `providerUserId`를 포함한다. 두 값 모두 개인정보고 `providerUserId`는 계정 식별 키의 절반이라 로그에 나가면 안 된다. 예외 스택이나 `log.debug("{}", user)` 한 줄로 새어 나가는 경로라 미리 막는 편이 맞다.

`@EqualsAndHashCode`를 뺀 근거(JPA 엔티티에서 잘못된 결과를 낸다)도 맞다. 프록시와 미할당 식별자 때문이다. `@Builder` 판단도 동의한다. 필수 인자 네 개는 생성자 시그니처가 컴파일러 검사를 남겨 주는 편이 낫다.

### 5-3. `User`에 `UserConsent` 컬렉션 매핑을 두지 않은 것

**판정: FIX (근거 서술만, 코드는 유지)**

결론은 옳지만 요약에 적힌 근거가 사실과 다르다.

`_workspace/t001_backend_summary.md` 4절과 `UserConsent.java` 상단 주석의 취지가 "컬렉션 매핑을 두면 Hibernate가 삭제 전에 자식을 전부 로딩해 개별 DELETE를 날린다"인데, 그것은 `cascade = REMOVE`나 `orphanRemoval = true`를 함께 걸었을 때의 이야기다. `@OneToMany(mappedBy = "user")`만 두고 cascade를 걸지 않으면 Hibernate는 자식을 건드리지 않고 `delete from users`만 발행하며, 뒤처리는 DB의 `ON DELETE CASCADE`가 한다. 즉 컬렉션 매핑의 존재 자체가 `DELETE FROM users` 한 번이라는 0장 규약을 깨지는 않는다.

컬렉션을 두지 않은 선택 자체는 유지하는 것이 맞다. 다만 근거는 "cascade 없는 컬렉션이라도 삭제 방식은 같으므로, 지금 읽을 일이 없는 양방향 연관을 미리 만들 이유가 없다"여야 한다. 실제로 최신 동의 버전 조회는 `(user_id, consent_type, consented_at DESC)` 인덱스를 타는 쿼리로 하지 컬렉션 순회로 하지 않는다.

- 수정 대상: `_workspace/t001_backend_summary.md` 4절 "### 4. `ON DELETE CASCADE`" 문단 끝(엔티티 cascade 설명 부분), `src/main/java/com/petgyebu/telo/user/domain/UserConsent.java:26-27` 주석
- 수정 방향: "컬렉션 매핑을 두면 Hibernate가 자식을 로딩한다"는 서술을 지우고, cascade/orphanRemoval을 걸지 않는 한 삭제 경로는 동일하다는 점과 지금 양방향 연관이 필요 없다는 점을 근거로 바꾼다. 다음 Task가 이 문장을 근거로 "컬렉션 매핑은 금지"라고 확대 적용하는 것을 막기 위함이다.
- 코드 변경은 필요 없다.

T-005 탈퇴가 DB CASCADE에만 의존해도 되는가에 대해서는 **그렇다**. `09-db-design.md` 0장이 명시적으로 그렇게 정했고, 1-5에서 SQL로 재현했다. 다만 T-005 구현 시 두 가지를 기억해야 한다. 첫째, 코드에프 `connectedId` 해지는 삭제 **전에** 해야 한다(0장). 둘째, 같은 트랜잭션에서 `UserConsent`를 이미 로딩한 상태로 `users`를 지우면 1차 캐시에 유령 객체가 남는다. 탈퇴는 자체 트랜잭션에서 사용자만 조회해 지우면 되므로 실무상 문제는 없다.

### 5-4. `OffsetDateTime` 선택

**판정: PASS (근거는 일부 과장)**

선택 자체는 안전하다. `OffsetDateTime`은 JDBC에서 `timestamp with time zone`으로 직결되고, 1-1에서 본 대로 실제 컬럼도 `timestamp with time zone`이며 `ddl-auto: validate`가 매 빌드마다 이 대응을 확인한다.

다만 "`Instant`가 Hibernate 버전에 따라 `timestamp`와 `timestamptz` 사이에서 갈린다"는 서술은 현재형으로 읽으면 과장이다. 과거형으로는 맞다. Hibernate 5는 `Instant`를 `timestamp`로 매핑했고, Hibernate 6부터는 `Instant`가 `TIMESTAMP_UTC`로 매핑돼 PostgreSQL 방언에서 `timestamp with time zone`으로 나간다. 이 프로젝트가 쓰는 Hibernate에서는 `Instant`를 썼어도 validate를 통과했을 것이다. 그러니 "`Instant`면 깨진다"가 아니라 "둘 다 되지만 `OffsetDateTime`이 매핑 경로가 더 명시적이다" 정도가 정확하다. 코드를 바꿀 이유는 없다.

관련해서 후속 Task에서 다뤄야 할 것이 하나 있어 아래 7절에 적었다.

### 5-5. `joined_at`을 엔티티가 채우는 것

**판정: PASS**

동의한다. DB DEFAULT에 맡기려면 `insertable = false`가 필요하고, 그러면 저장 직후 객체의 `joinedAt`이 null이라 T-002의 가입 응답에서 쓸 수 없다. DEFAULT를 남겨 둔 것도 맞다. 마이그레이션이나 운영 SQL로 직접 INSERT할 때의 안전장치다.

한 가지 덧붙인다. `joined_at`은 KPI 코호트의 기준 t=0이라 최초 저장 후 절대 바뀌면 안 되는 값이다. `09-db-design.md` 9장 2번이 `initial_classification_source`에 대해 "엔티티 매핑에서 `updatable = false`로 막는다"고 정한 것과 성격이 같다. `User.java:50`의 `@Column(nullable = false)`에 `updatable = false`를 더해 두면 좋겠다. 9장이 이 컬럼을 명시적으로 지목하지는 않았으므로 이번 판정에는 반영하지 않고 제안으로만 남긴다.

---

## 6. 요약과 실제가 어긋난 지점

### 6-1. README가 실제로는 수정돼 있고, 커밋되지 않았다

**판정: FIX**

요약 마지막 절은 `src/main/resources/db/migration/README.md`의 "현재 상태"가 아직 "마이그레이션 파일이 없다"고 적혀 있으며 "문서 갱신은 요청 범위 밖이라 고치지 않았다"고 적었다. 실제로는 파일이 수정돼 있다. 다만 커밋 `a449973`·`7ebc592` 어디에도 들어 있지 않고 작업 트리에만 떠 있다.

```
$ git status --porcelain
 M src/main/resources/db/migration/README.md
?? _workspace/t001_00_input.md

$ git diff --name-status main..HEAD   # README 없음
A  _workspace/t001_backend_summary.md
A  src/main/java/com/petgyebu/telo/user/domain/AuthProvider.java
... (총 10개, README 미포함)
```

내용 자체는 옳다. "첫 마이그레이션은 T-001의 `users`·`user_consents`다"로 바뀌었고 `BATCH_*`가 T-040 몫이라는 점도 맞다. 문제는 상태다. 이대로 PR을 올리면 README 변경이 빠진 채 병합되고, jar에 들어가는 문서가 "아직 마이그레이션 파일이 없다"고 말하는 상태로 남는다.

다만 한 가지 걸린다. 세션 시작 시점의 `git status` 스냅샷에는 이 수정이 없었고 지금은 있다. 내가 실행한 명령 중 이 파일을 건드리는 것은 없다(`./gradlew build`, `bootRun`, `psql`, 카탈로그 조회뿐). 누가 언제 고쳤는지는 확인할 수 없으므로 사실만 적는다.

- 수정 대상: `src/main/resources/db/migration/README.md` (작업 트리 미커밋 변경)
- 수정 방향: 변경을 T-001 브랜치에 커밋하든 되돌리든 하나로 정한다. 커밋하는 쪽을 권한다. 커밋한다면 요약의 마지막 절도 함께 고쳐야 한다. 삭제된 문장 중 "이 README는 디렉터리를 jar에 남기기 위한 용도도 겸한다"는 설명은 지금도 유효한 정보라 살려 두는 편이 낫다. 마이그레이션 파일이 생겼으니 디렉터리는 더 이상 비지 않지만, 그 문장은 이 파일을 지우면 안 되는 이유를 설명하는 유일한 근거였다.

### 5-3에서 다룬 Hibernate 서술도 같은 성격의 FIX다. 판정 합계에는 한 번만 센다.

---

## 7. 관찰 — T-001 결함은 아니지만 남겨 둘 것

**기준 타임존 KST를 강제하는 설정이 아직 없다.** `09-db-design.md` 0장이 "애플리케이션 기준 타임존은 KST 고정"이라고 정했는데, `hibernate.jdbc.time_zone`도 JVM `user.timezone`도 `Dockerfile`의 `TZ`도 어디에도 없다.

```
$ grep -rn "time_zone\|Asia/Seoul\|TimeZone\|timezone" src/main docs/05-infra-stack.md
src/main/resources/db/migration/README.md:37: V$(TZ=Asia/Seoul date +%Y%m%d%H%M)__create_account.sql
docs/05-infra-stack.md:151: V$(TZ=Asia/Seoul date +%Y%m%d%H%M)__설명.sql
```

두 건 모두 마이그레이션 파일명 규칙일 뿐 런타임 설정이 아니다. PostgreSQL의 `timestamptz`는 오프셋을 저장하지 않고 UTC로 보관하므로 `OffsetDateTime`으로 읽으면 오프셋이 세션 타임존을 따라간다. 시각의 절대값은 맞으므로 T-001의 저장·조회는 문제없다. 드러나는 것은 그 뒤다. `joined_at` 인덱스를 쓰는 "가입 후 24시간·7일 코호트" 집계나 월간 예산 기간 경계처럼 날짜 단위로 자르는 계산에서 서버 타임존이 UTC면 KST 기준과 하루가 어긋난다.

T-001의 입력 문서가 요구한 항목이 아니므로 판정에 넣지 않는다. 배치·예산 Task 전에 별도 항목으로 잡을 것을 리더에게 제안한다.

부수적인 것 두 가지도 적어 둔다. 첫째, `UserSchemaTest`가 `@Transactional` 없이 돌아 테스트 사이에 행이 남는다. 지금은 테스트마다 `provider_user_id`가 달라 충돌하지 않지만, 도메인 스키마 테스트가 늘어나면 컨테이너 공유 베이스 클래스를 만드는 시점에 같이 정리하는 편이 낫다. 요약도 이 점을 스스로 지적했고 지금 만들지 않은 판단에 동의한다. 둘째, `UserSchemaTest`가 컨테이너를 하나 더 띄워 빌드 시간이 늘지만 11초라 아직 문제가 아니다.

---

## 8. 미검증

**애플·카카오 실제 로그인 경로에서 `email`이 null 또는 중복으로 들어오는 상황.** DB가 그 값을 받아들이는 것은 1-2에서 확인했지만, 공급자가 실제로 무엇을 주는지는 T-002의 소셜 로그인 연동이 붙어야 확인할 수 있다. B-KAKAO 차단이 풀리기 전에는 검증할 수 없다. PASS로 처리하지 않고 미검증으로 남긴다.

---

## 9. 판정 요약

| 항목 | 판정 |
|---|---|
| 1-1 `users` 컬럼 정의 | PASS |
| 1-2 `email` 유니크 없음 | PASS |
| 1-3 `character_type` NULL·CHECK | PASS |
| 1-4 `user_consents` 컬럼·CHECK 4종 | PASS |
| 1-5 FK ON DELETE CASCADE | PASS |
| 1-6 인덱스 2개 | PASS |
| 1-7 엔티티 ↔ DB 매핑 | PASS |
| 2 주장 7건 재현 | PASS |
| 3 범위 준수 | PASS |
| 4 회귀 | PASS |
| 5-3 컬렉션 매핑 미도입 근거 서술 | FIX |
| 6-1 README 미커밋 변경 | FIX |
| 8 공급자 이메일 실동작 | 미검증 |

완료 기준 두 가지("마이그레이션 적용 + `PostgresMigrationTest` 통과", "`(provider, provider_user_id)` 유니크 동작")는 모두 충족한다. FIX 두 건은 모두 서술·커밋 상태 문제라 코드 되돌림 없이 처리할 수 있고, T-002 착수를 막을 이유가 없다.
