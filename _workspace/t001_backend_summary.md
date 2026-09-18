# T-001 구현 요약 — users·user_consents 스키마와 엔티티

- 작성: 2026-09-18
- 브랜치: `feature/F-QJXRMD-schema` (커밋 `a449973`, 원격 push·PR 없음)
- 근거: `_workspace/t001_00_input.md`, `docs/09-db-design.md` 0·2.1·2.2·9장, `docs/02-requirements-features.md` F-QJXRMD

## 만든 파일

| 파일 | 역할 |
|---|---|
| `src/main/resources/db/migration/V202609181920__create_users.sql` | `users`·`user_consents` 두 테이블과 CHECK·UNIQUE·FK·인덱스를 만드는 첫 마이그레이션 |
| `src/main/java/com/petgyebu/telo/user/domain/AuthProvider.java` | 소셜 공급자 열거형 (`KAKAO`, `APPLE`) |
| `src/main/java/com/petgyebu/telo/user/domain/CharacterType.java` | 캐릭터 열거형 (`DOG`, `CAT`) |
| `src/main/java/com/petgyebu/telo/user/domain/ConsentType.java` | 동의 항목 열거형 4종 |
| `src/main/java/com/petgyebu/telo/user/domain/User.java` | `users` 엔티티 |
| `src/main/java/com/petgyebu/telo/user/domain/UserConsent.java` | `user_consents` 엔티티, `User`로의 다대일 연관 |
| `src/main/java/com/petgyebu/telo/user/repository/UserRepository.java` | `JpaRepository` 상속만 한 빈 인터페이스 |
| `src/main/java/com/petgyebu/telo/user/repository/UserConsentRepository.java` | 위와 같음 |
| `src/test/java/com/petgyebu/telo/user/UserSchemaTest.java` | Testcontainers 기반 스키마 제약 검증 4건 |

마이그레이션 파일명의 타임스탬프는 `TZ=Asia/Seoul date +%Y%m%d%H%M`를 실제로 실행해 얻은
`202609181920`이다.

## 패키지 구조를 이렇게 정한 근거

`com.petgyebu.telo.{도메인}.{계층}` 으로 잡았다. 이번 것은 `user.domain`과 `user.repository`이고,
이어질 Task는 `account.domain`, `transaction.domain`, `budget.domain`처럼 같은 모양으로 붙는다.

계층을 최상위에 두는 방식(`telo.domain.user`, `telo.repository.user`)도 있지만 선택하지 않았다.
`docs/06-sprint-plan.md`와 `docs/10-task-backlog.md`가 스프린트를 도메인 단위로 쪼개 놓았고
`docs/07-branch-strategy.md`가 기능별 브랜치를 전제로 하는데, 계층이 최상위면 한 기능을 건드릴 때마다
서로 떨어진 디렉터리를 동시에 수정하게 되고 병렬 브랜치끼리 같은 디렉터리에서 부딪힌다. 도메인이
최상위면 브랜치 하나가 대체로 디렉터리 하나 안에서 끝난다.

`docs/09-db-design.md`가 테이블을 계정·계좌·거래·예산·푸시·리워드로 묶어 장을 나눈 것과도 경계가
같아서, 문서의 장 번호와 패키지가 대체로 1:1로 대응한다.

열거형은 별도 패키지를 두지 않고 엔티티와 같은 `user.domain`에 뒀다. 세 개뿐이고 전부 `users` 혹은
`user_consents`의 컬럼값이라 엔티티에서 떼어 놓을 이유가 없다. 여러 도메인이 공유하는 열거형이
생기면 그때 공용 위치를 만든다.

## Lombok 사용 여부와 근거

쓰되 `@Getter`와 `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 두 개만 썼다. 이미
`build.gradle`에 `compileOnly`/`annotationProcessor`로 들어 있어 의존성 판단은 필요 없었고, JPA가
요구하는 기본 생성자와 단순 getter는 손으로 적어도 생성 결과가 똑같아 읽을 가치가 없는 코드다.
기본 생성자를 `protected`로 막은 것은 Hibernate만 쓰게 하려는 것이다.

`@Setter`, `@Data`, `@Builder`, `@EqualsAndHashCode`는 쓰지 않았다.

- `@Setter`는 엔티티를 아무 데서나 바꿀 수 있게 만든다. 값 변경은 T-002 이후에 필요한 지점이
  드러날 때 의미가 담긴 메서드로 추가하는 편이 낫다.
- `@Data`는 위의 setter에 더해 `toString`까지 만든다. `email`과 `providerUserId`가 로그에
  딸려 나가면 곤란하다.
- `@EqualsAndHashCode`는 식별자 기반 동등성이 필요한 JPA 엔티티에서 잘못된 결과를 낸다.
  지금 필요하지도 않다.
- `@Builder`는 필수 필드가 네 개뿐이라 생성자로 충분하다. 오히려 필수값 누락을 컴파일러가
  못 잡게 만든다.

생성자는 테스트가 엔티티를 저장하려면 필요해서 넣었다. 필수 필드는 `Objects.requireNonNull`로
막고, `characterType`·`characterChangedAt`·`lastLoginAt`은 가입 시점에 값이 없으므로 받지 않는다.

## 검증 — 실제로 실행한 명령과 결과

### 1. `./gradlew build`

```
BUILD SUCCESSFUL in 20s
7 actionable tasks: 7 executed
```

테스트 결과 XML을 직접 열어 건수를 확인했다. 스킵 0건이다.

```
TeloApplicationTests            tests=1 skipped=0 failures=0 errors=0
EasyCodefUtilJdk25Test          tests=1 skipped=0 failures=0 errors=0
운영 프로필(...) 기동 검증        tests=2 skipped=0 failures=0 errors=0
users·user_consents 스키마 제약 검증  tests=4 skipped=0 failures=0 errors=0
```

### 2. `PostgresMigrationTest` 통과

위 표의 "운영 프로필 기동 검증" 2건이 그것이다. 이 테스트는 기본 프로필(Flyway 활성,
`ddl-auto: validate`)로 실제 PostgreSQL 16 컨테이너에 붙는다. 컨텍스트가 떴다는 것 자체가
`users`·`user_consents` 테이블과 두 엔티티 매핑이 어긋나지 않았다는 뜻이다. 컬럼 하나만 달라도
`SchemaManagementException`으로 로딩 단계에서 죽는다. 첫 시도에 통과했다.

### 3. `(provider, provider_user_id)` 유니크

`UserSchemaTest.duplicateProviderIdentityIsRejected` — 같은 `(KAKAO, "dup-1")` 조합을 이메일만
바꿔 두 번 `saveAndFlush`하면 두 번째에서 `DataIntegrityViolationException`이 난다. 통과했다.

덤으로 `sameProviderUserIdOnAnotherProviderIsAllowed`를 같이 뒀다. 유니크가 복합 키가 아니라
`provider_user_id` 단독으로 잘못 걸리면 이쪽이 깨진다. 공급자가 다르면 같은 식별자를 써도 별도
계정이라는 F-QJXRMD의 규칙이 그대로 검증된다.

### 4. `ON DELETE CASCADE`

`UserSchemaTest.deletingUserCascadesToConsents` — 사용자 한 명에 동의 두 건을 저장하고
`user_consents`를 JdbcTemplate으로 세어 2를 확인한 뒤, `userRepository.delete(user)` 후 다시 세어
0을 확인한다. 통과했다.

JPA `cascade` 속성이 아니라 DB의 FK 동작을 보는 것이 핵심이라 확인을 JdbcTemplate으로 했다.
엔티티에는 `cascade`나 `orphanRemoval`을 걸지 않았고, `User`에서 `UserConsent`로 가는 컬렉션
매핑도 두지 않았다.

**정정 (2026-09-18, QA 지적)**: 처음에는 "컬렉션 매핑을 두면 Hibernate가 삭제 전에 자식을 전부
로딩해 개별 DELETE를 날린다"고 적었으나 이는 사실이 아니다. 그 동작은 `cascade = REMOVE`나
`orphanRemoval = true`를 함께 걸었을 때 일어난다. `@OneToMany(mappedBy = "user")`만 두고
cascade를 걸지 않으면 Hibernate는 자식을 건드리지 않고 `delete from users`만 발행하며 뒤처리는
DB의 `ON DELETE CASCADE`가 한다. 즉 컬렉션 매핑의 존재 자체는 `09-db-design.md` 0장 규약을
깨지 않는다.

컬렉션을 두지 않은 결론은 유지한다. 근거는 다른 것이다 — cascade를 걸지 않는 한 삭제 경로는
어느 쪽이든 같고, 지금 읽을 일이 없는 양방향 연관을 미리 만들 이유가 없다. **후속 Task에서
"컬렉션 매핑 금지"로 확대 적용하지 말 것.**

### 5. `email`이 null인 사용자

`UserSchemaTest.userWithoutEmailIsStored` — 이메일 없이 애플 계정을 저장하고 다시 읽어
`email`이 null인지 확인한다. 통과했다.

### 추가로 돌린 것

`./scripts/check-migration-order.sh`

```
기준: origin/main (최대 버전 0)
  [통과] V202609181920__create_users.sql (버전 202609181920)
마이그레이션 순서 검사 통과.
```

이 스크립트는 커밋된 파일만 보기 때문에 커밋 후에 실행했다.

## 판단이 필요했던 지점

**시각 타입.** `TIMESTAMPTZ` 컬럼에 `OffsetDateTime`을 썼다. `Instant`도 되지만 Hibernate가
`Instant`를 매핑하는 경로는 버전에 따라 `timestamp`와 `timestamptz` 사이에서 달라진 전례가 있고,
`OffsetDateTime`은 JDBC 레벨에서 `timestamp with time zone`으로 곧장 간다. `ddl-auto: validate`가
이 대응을 매 빌드마다 확인해 준다.

**`joined_at`을 누가 채우는가.** 컬럼에 `DEFAULT now()`가 있지만 엔티티가 값을 채우게 했다.
DB 기본값에 맡기려면 `insertable = false`를 걸어야 하는데, 그러면 저장 직후 객체의 `joinedAt`이
null이라 다시 읽기 전에는 쓸 수 없다. DEFAULT는 SQL로 직접 넣는 경로를 위한 안전장치로 남겼다.

**테스트 클래스를 새로 만들 것인가.** `PostgresMigrationTest`에 메서드를 더하는 대신 별도
클래스를 만들었다. 저쪽은 "Flyway가 돌았는가, validate를 통과했는가"라는 프로젝트 전체의 관심사고
이번 4건은 `users` 도메인의 관심사다. 다만 컨테이너가 하나 더 떠서 테스트 시간이 늘어난다.
도메인별 스키마 테스트가 계속 늘어날 예정이라, 컨테이너를 공유하는 공통 베이스 클래스를 두는
편이 나아지는 시점이 곧 온다. 지금 미리 만들지는 않았다.

**`@ManyToOne(optional = false)`.** `UserConsent`는 `User` 참조를 `LAZY`로 두되 `optional = false`를
줬다. DB의 `NOT NULL`과 맞추기 위한 것이고, Hibernate가 프록시를 쓸 수 있는 조건이기도 하다.

## 범위 밖이라 손대지 않은 것

- 카카오·애플 소셜 로그인 API (T-002, B-KAKAO 차단 중)
- JWT 발급·검증·회전, Redis 폐기 상태 저장 (T-003)
- 약관 동의 API (T-004, B-TERMS 차단 중)
- 회원 탈퇴 API와 코드에프 `connectedId` 해지 (T-005)
- 컨트롤러·서비스·DTO 일체. 리포지토리는 `JpaRepository`만 상속한 빈 인터페이스이며 조회
  메서드를 하나도 선언하지 않았다. `findByProviderAndProviderUserId` 같은 것은 그것을 쓰는
  로그인 로직과 함께 T-002에서 들어오는 편이 맞다.
- `docs/09-db-design.md` 9장의 구현 규칙 대부분은 거래·예산·크레딧 테이블에 걸린 것이라 이번
  범위에 해당 항목이 없다. 이번에 지킨 것은 0장의 공통 규약(열거형 VARCHAR+CHECK, TIMESTAMPTZ,
  소프트 삭제 없음, 사용자 FK 전부 CASCADE)이다.
- `src/main/resources/db/migration/README.md`의 "현재 상태" 절이 아직 "마이그레이션 파일이
  없다"고 적혀 있고 첫 마이그레이션을 Sprint 1의 Account로 예고한다. 실제로는 이번 것이 첫
  마이그레이션이라 내용이 어긋나지만, 문서 갱신은 요청 범위 밖이라 고치지 않았다. 리더 판단이
  필요한 항목으로 남긴다.
