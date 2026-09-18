# 데이터베이스 마이그레이션

이 디렉터리가 Flyway의 마이그레이션 위치(`classpath:db/migration`)다. 스키마 변경은 전부 여기를
거친다. 운영 프로필의 `spring.jpa.hibernate.ddl-auto`는 `validate`로 고정돼 있어서 Hibernate는
테이블을 만들지 않는다.

**대상 DBMS는 PostgreSQL 16 하나다.** JSONB, GIN 인덱스, 윈도우 함수 같은 PostgreSQL 전용 문법을
제약 없이 쓴다. `local` 프로필(H2)에서는 Flyway를 끄고 Hibernate가 스키마를 만들기 때문에, 이
디렉터리의 SQL이 H2에서 도는 일은 없다.

## 파일 이름 규칙

```
V{YYYYMMDDHHmm}__{snake_case_설명}.sql
```

예시.

```
V202609201430__create_account.sql
V202609211015__add_account_reauth_required.sql
```

- 접두사 `V`는 한 번만 적용되는 버전 마이그레이션이다. 반복 실행되는 `R__` 마이그레이션은 쓰지 않는다.
- 버전은 분 단위 타임스탬프(UTC+9 기준 작성 시각)를 쓴다. 순번(`V1`, `V2`)을 쓰지 않는 이유는
  `docs/07-branch-strategy.md`대로 기능(F-XXXXX)마다 브랜치를 따로 파고 Sprint 3처럼 병렬로
  진행하는 구간이 있기 때문이다. 순번을 쓰면 두 브랜치가 같은 번호를 잡아 병합할 때 충돌한다.
- 설명은 소문자 snake_case로, 무엇을 하는지 동사로 적는다. 구분자는 밑줄 **두 개**다.
- 적용된 마이그레이션 파일은 절대 수정하지 않는다. Flyway가 체크섬으로 검증해 기동이 실패한다.
  잘못된 것을 고칠 때는 새 마이그레이션을 추가한다.
- **병합 전에 재타임스탬프한다.** 브랜치를 오래 들고 있었다면 병합 직전에 파일명을 현재 시각으로
  다시 짓는다. 이미 적용된 것보다 낮은 버전이 나중에 들어오면 `FlywayValidateException`으로
  기동이 실패하기 때문이다(`spring.flyway.out-of-order: false`).

  ```
  git mv V202609201000__create_account.sql \
         V$(TZ=Asia/Seoul date +%Y%m%d%H%M)__create_account.sql
  ```

  `scripts/check-migration-order.sh`가 CI에서 이를 검사한다. 로컬에서 미리 돌려볼 수도 있다.

  ```
  ./scripts/check-migration-order.sh
  ```

  **이 실패는 빈 DB에서 재현되지 않는다.** 새 DB는 어떤 순서로 만들어졌든 오름차순으로 적용해서
  테스트가 통과한다. 이미 마이그레이션이 적용된 DB에서만 드러나므로 정적 검사가 필요하다.

## 현재 상태

첫 마이그레이션은 T-001의 `users`·`user_consents`다. Sprint 1의 Account가 첫 대상일 것으로
적어 뒀었으나, 계정이 모든 테이블의 뿌리라 순서가 바뀌었다.

Spring Batch 메타 테이블(`BATCH_*`)은 아직 들어오지 않았다. T-040에서 Spring Batch 배포본의
`schema-postgresql.sql`을 마이그레이션 파일로 옮긴다. **직접 작성하지 않는다.**

남은 마이그레이션 순서는 `docs/10-task-backlog.md`의 스키마 Task를 따른다.

이 README는 디렉터리를 jar에 남기기 위한 용도도 겸한다. Gradle이 빈 디렉터리를 산출물에 넣지
않기 때문이다. 마이그레이션 파일이 생긴 지금도 이 파일을 지우지 않는 이유는 규칙 문서로서의
역할이 남아 있어서다. Flyway는 `V`/`U`/`R` 접두사가 붙은 `.sql` 파일만 읽으므로 무시된다.
