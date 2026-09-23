# 트러블슈팅 기록

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱 (Java 25 / Spring Boot 4.0 / PostgreSQL 16)
- 최종 갱신: 2026-09-23
- 목적: 개발 과정에서 실제로 마주친 문제와 진단·해결 과정을 기록한다. 증상과 해결만이 아니라 **어떻게 원인에 도달했고 왜 그 방법을 골랐는지**를 남긴다.

각 항목은 관련 PR 번호를 달았다. 실제 커밋과 CI 실행 기록으로 확인할 수 있다.

**이 문서는 자동으로 갱신된다.** 디버깅이나 오류 해결이 끝나면 요청 없이도 항목이 추가된다. 기록 기준과 항목 형식은 `CLAUDE.md`의 "트러블슈팅 기록 (자동 갱신)" 절에 정의돼 있다. 요약하면 원인을 찾는 데 추론이 필요했던 문제, 겉보기 성공 뒤에 숨은 실패, 좌표·API가 관례와 달랐던 경우, 보안 문제, 검증 방법 자체의 오류를 남긴다. 오타나 즉시 보이는 컴파일 에러는 남기지 않고, 해결하지 못한 것은 여기가 아니라 `10-task-backlog.md`에 Task로 등록한다.

---

## 요약

| # | 문제 | 분류 | PR |
|---|---|---|---|
| 1 | `firebase-admin` 버전 생략으로 빌드 실패 | 의존성 | #3 |
| 2 | `spring-boot-starter-aop`가 Spring Boot 4.0에 존재하지 않음 | 메이저 버전 전환 | #3 |
| 3 | QueryDSL이 Q클래스를 조용히 생성하지 않음 | **조용한 실패** | #3 |
| 4 | Flyway가 조용히 아무것도 하지 않음 | **조용한 실패** | #3 |
| 5 | Testcontainers 2.x 좌표·패키지 변경 | 메이저 버전 전환 | #8 |
| 6 | 자동 생성 비밀번호가 로그에 노출 | 보안 | #3 |
| 7 | 인증 수단 제거 후 401이 403으로 바뀜 | 부작용 | #3 |
| 8 | Docker 이미지에 145MB 중복 레이어 | 성능 | #3 |
| 9 | CI가 자격증명 없이 조용히 성공 | **조용한 실패** | #3에서 발견, 미해결(T-048) |
| 10 | 테스트가 운영 스키마 경로를 전혀 검증하지 않음 | **조용한 실패** / 테스트 설계 | #8 |
| 11 | out-of-order 마이그레이션 — 빈 DB에서 재현 불가 | **조용한 실패** | #10 |
| 12 | GitHub Actions 스크립트 인젝션 | 보안 | #10 |
| 13 | 기준 타임존이 런타임에 강제되지 않음 | 정합성 | #12 |
| 14 | 포트 점유로 이전 컨테이너 응답을 오인 | 검증 방법 | — |
| 15 | `git reset --hard`로 커밋 전 작업 유실 | 작업 실수 | — |
| 16 | 마이그레이션 순서 검사가 커밋 전에는 조용히 건너뛴다 | 검증 방법 | #16 |
| 17 | 인덱스를 지워도 빌드와 스키마 테스트가 전부 통과한다 | **조용한 실패** / 테스트 설계 | #18 |
| 18 | `NOT NULL`을 지워도 빌드와 스키마 테스트가 전부 통과한다 | **조용한 실패** / 테스트 설계 | #19 |
| 19 | 축은 있는데 적용 범위가 좁다 — CHECK 값 제거·CHECK 통째 삭제·`VARCHAR` 길이 변경이 새어 나간다 | **조용한 실패** / 테스트 설계 | #20 |
| 20 | 축이 DB 제약만 본다 — 엔티티 생성자를 뒤집어도 스키마 테스트가 전부 통과한다 | **조용한 실패** / 테스트 설계 | #21 |
| 21 | 동작 단언 셋으로는 유니크의 열 구성이 고정되지 않는다 — `user_id`를 더해도 셋이 그대로 통과한다 | 검증 방법 / 테스트 설계 | #22 |
| 22 | Spring Batch 6.0이 DataSource가 있어도 메타데이터를 DB에 쓰지 않는다 — Job은 `COMPLETED`로 끝난다 | **조용한 실패** / 메이저 버전 전환 | #23 |
| 23 | FK 자식 컬럼에 인덱스가 없어 부모 삭제가 자식 테이블 전체를 훑는다 | **조용한 실패** / 설계 문서 누락 | #24 |
| 24 | 비정규화한 `user_id`가 부모의 소유자와 어긋나도 DB가 막지 않는다 — 집계에 남의 데이터가 섞인다 | **조용한 실패** / 설계 결정 | #25 |
| 25 | 인증 빈 하나를 추가했더니 무관해 보이는 스키마 테스트 25개가 컨텍스트 기동 실패로 깨졌다 | 부작용 / 설정 | — (T-003a, 미병합) |

**가장 많이 나온 유형은 "조용한 실패"다.** 빌드도 기동도 성공하는데 기능만 비어 있는 경우가 12건이었다. 이 유형이 위험한 이유는 아래 마지막 절에 정리했다.

---

## 1. `firebase-admin` 버전 생략으로 빌드 실패

**증상**

```
Could not find com.google.firebase:firebase-admin:.
Required by: root project 'telo'
```

**원인**

Spring Boot의 `dependency-management` 플러그인이 관리하는 라이브러리는 버전을 생략할 수 있다. 그러나 `firebase-admin`은 Spring Boot BOM 밖의 서드파티라 관리 대상이 아니다. 버전을 생략하면 해석 자체가 실패한다.

**해결**

Maven Central의 `maven-metadata.xml`을 직접 조회해 최신 릴리스 `9.10.0`을 확인하고 명시했다. 추측하지 않고 실제 값을 확인한 이유는, 존재하지 않는 버전을 적으면 같은 에러가 다시 나기 때문이다.

**배운 것**

BOM 관리 대상과 아닌 것을 구분하는 것이 Spring Boot 프로젝트의 기본기다. 이 프로젝트는 인프라 문서에 "QueryDSL·ShedLock·jjwt·easycodef-java·Cloud SQL 소켓 팩토리·Resilience4j·Firebase Admin은 전부 BOM 밖이라 버전 생략 시 해석 실패한다"고 미리 적어 두었는데, 정작 그 목록의 항목 하나가 누락된 채 커밋돼 있었다. **문서에 적어 두는 것만으로는 부족하고 빌드가 강제해야 한다.**

---

## 2. `spring-boot-starter-aop`가 Spring Boot 4.0에 존재하지 않음

**증상**

인프라 문서에 적힌 대로 의존성을 추가했더니 빌드가 깨졌다.

```
Could not find org.springframework.boot:spring-boot-starter-aop:.
```

**진단**

버전 생략 문제(1번)와 증상이 같아 처음에는 같은 원인으로 의심했다. 그러나 이 아티팩트는 Spring Boot BOM 관리 대상이므로 버전 생략이 정상이다. Maven Central을 확인하니 **마지막 배포 버전이 `4.0.0-M2`**였고, `spring-boot-dependencies:4.0.8` BOM에도 들어 있지 않았다.

**원인**

Spring Boot 4.0에서 AOP 스타터가 `spring-boot-starter-aspectj`로 이름이 바뀌었다.

**해결**

`spring-boot-starter-aspectj`로 교체하고, 인프라 문서의 의존성 목록을 정정했다. 문서에 "이 아티팩트는 4.0에 존재하지 않는다"는 사실과 실제 에러 메시지를 함께 남겨, 다음 사람이 같은 경로를 밟지 않게 했다.

**배운 것**

같은 에러 메시지가 다른 원인에서 나올 수 있다. "버전을 명시하면 된다"고 넘어갔다면 존재하지 않는 아티팩트에 버전을 붙이는 잘못된 수정을 했을 것이다.

---

## 3. QueryDSL이 Q클래스를 조용히 생성하지 않음

**증상**

`./gradlew build`가 정상 종료(exit 0)하는데 Q클래스가 하나도 생성되지 않았다. 경고도 에러도 없었다.

**진단**

애노테이션 프로세서가 아예 동작하지 않는다고 보고 jar 내부를 확인했다. classifier 없는 `querydsl-apt-7.5.jar`에는 `META-INF/services/javax.annotation.processing.Processor` 파일이 **아예 없었다.** 자바 애노테이션 프로세서는 이 파일로 등록되므로, 없으면 컴파일러가 프로세서의 존재를 모르고 그냥 지나간다. 에러가 없는 이유가 여기 있다.

`:jpa`와 `:jakarta` classifier jar를 받아 비교해 보니 **md5가 동일한 같은 파일**이었고 둘 다 `JPAAnnotationProcessor`를 등록하고 있었다. 이 포크는 이미 네이티브 jakarta라 javax 대응 jar가 따로 없고 `jakarta`가 `jpa`의 별칭이다.

**해결**

```groovy
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:7.5:jakarta'
```

의도를 분명히 하려고 `:jakarta`를 썼다.

**검증 — 양방향으로 확인했다**

통과만 확인하면 의미가 없다고 보고 반대 방향을 함께 검증했다.

| 조건 | 결과 |
|---|---|
| classifier 있음 | Q클래스 생성됨 |
| classifier 제거 | **빌드 exit 0, Q클래스 0개** |

**재발 방지**

인프라 문서의 의존성 표에 "classifier가 빠지면 빌드는 정상 종료하면서 Q클래스만 생성되지 않는다"는 사실을 강조해 남겼다. 또 한 가지 함정을 함께 적었다 — **`@Entity`가 하나도 없으면 QueryDSL은 Q클래스를 만들지 않는다.** 첫 엔티티가 생기기 전까지 "Q클래스 없음"은 정상이므로 설정이 깨진 것으로 오인하지 말아야 한다.

---

## 4. Flyway가 조용히 아무것도 하지 않음

**증상**

`flyway-core` 의존성을 추가하고 `spring.flyway.enabled: true`까지 설정했는데 마이그레이션이 실행되지 않았다. 애플리케이션은 정상 기동하고 로그에 Flyway 관련 줄이 **한 줄도 없었다.**

**진단**

로그가 없다는 것이 단서였다. Flyway가 실패한 것이 아니라 **아예 실행되지 않았다**는 뜻이다. 자동설정이 붙지 않았다고 보고 jar를 확인하니, `FlywayAutoConfiguration`이 `spring-boot-autoconfigure-4.0.8.jar`에 없고 `spring-boot-flyway-4.0.8.jar`에만 있었다.

**원인**

Spring Boot 4.0이 자동설정을 모듈 단위로 분리하면서 Flyway 자동설정이 별도 아티팩트로 빠졌다.

**해결**

```groovy
implementation 'org.springframework.boot:spring-boot-flyway'  // 자동설정 모듈
implementation 'org.flywaydb:flyway-core'
runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

**검증 — 역시 양방향**

`spring-boot-flyway` 한 줄만 제거하고 실행했다.

| 조건 | 결과 |
|---|---|
| 있음 | `flyway_schema_history` 생성, 마이그레이션 적용 |
| 제거 | **Flyway 로그 0줄, 경고 0건, 기동 성공, DB 테이블 0개.** `spring.flyway.enabled: true`도 무시됨 |

**배운 것**

3번과 성질이 완전히 같다. 빌드도 기동도 성공하는데 기능만 비어 있다. Spring Boot 4.0의 자동설정 모듈 분리 때문에 **다른 기능을 붙일 때도 같은 확인이 필요하다**는 점을 문서에 남겼다.

이 문제는 테스트로도 막았다. 마이그레이션 검증 테스트가 `flyway_schema_history` 테이블의 존재를 직접 단언한다. 컨텍스트가 뜨는 것만으로는 판별할 수 없기 때문이다.

---

## 5. Testcontainers 2.x 좌표·패키지 변경

**증상**

관례적인 좌표로 의존성을 추가했더니 해석이 실패했다.

```
org.testcontainers:junit-jupiter FAILED
org.testcontainers:postgresql FAILED
```

반면 `spring-boot-testcontainers`는 `2.0.5`로 정상 해석됐다.

**진단**

Spring Boot BOM이 Testcontainers 2.0.5를 관리하는데 두 모듈만 실패하는 상황이었다. Maven Central 메타데이터를 조회하니 `org.testcontainers:postgresql`과 `:junit-jupiter`는 **1.21.x에서 멈춰 있었다.** 코어 jar(2.0.5) 내부를 열어 보니 postgres·junit 관련 클래스가 하나도 없어, 모듈이 통합된 것도 아니었다.

저장소 디렉터리 목록을 훑어 `testcontainers-postgresql`, `testcontainers-junit-jupiter` 아티팩트를 찾았고 둘 다 2.0.5가 있었다.

**원인**

Testcontainers 2.x에서 모듈 좌표에 `testcontainers-` 접두사가 붙었다. 구 좌표는 1.x 라인에서 유지보수만 되고 있었다.

**추가로 발견한 것**

좌표를 고친 뒤 컴파일에 deprecation 경고가 남았다. 확인해 보니 `PostgreSQLContainer`가 `org.testcontainers.postgresql` 패키지로 옮겨졌고 **제네릭 self-type도 사라졌다.** 기존 `org.testcontainers.containers.PostgreSQLContainer`는 deprecated 호환 클래스였다.

**해결**

```java
import org.testcontainers.postgresql.PostgreSQLContainer;

static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
```

**배운 것**

"관례적인 좌표"가 메이저 버전 전환에서 가장 먼저 깨진다. 이 프로젝트에서 좌표 함정은 이것으로 네 번째였다(1·2·4·5번). Spring Boot 4.0과 최신 라이브러리 조합에서는 **의존성을 추가할 때마다 실제 좌표를 확인하는 편이 빠르다.**

---

## 6. 자동 생성 비밀번호가 로그에 노출 — 한 줄 수정으로 부족했던 사례

**증상**

QA 검증에서 발견됐다. Spring Boot가 자동 생성한 계정이 **실제로 인증에 성공**하고 있었다.

```
기동 로그: Using generated security password: 353e2e45-...
curl -u user:<pw> /actuator  →  HTTP 200
```

Cloud Run에 배포하면 이 비밀번호가 Cloud Logging에 그대로 남는다.

**1차 수정과 그 실패**

`SecurityConfig`의 `.httpBasic(basic -> {})`을 제거했다. 그런데 실행해 보니 **`Using generated security password:` 로그가 그대로 남았다.**

`UserDetailsServiceAutoConfiguration`은 HTTP Basic 설정과 무관하게 `UserDetailsService` 빈이 없으면 무조건 인메모리 계정을 만들고 비밀번호를 표준출력에 찍는다. 인증 경로는 닫혔지만(401) **노출 문제는 그대로였다.**

**최종 해결**

자동설정 자체를 제외했다.

```java
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
```

Spring Boot 4.0에서 FQN이 바뀐 점에 주의해야 했다. `org.springframework.boot.autoconfigure.security.servlet.*`가 아니라 **`org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`**이고 `spring-boot-security-4.0.8.jar`에 있다.

**배운 것**

지적받은 대로만 고치고 검증을 생략했다면 "고쳤다"고 보고했을 것이다. 실제로는 문제의 절반만 해결된 상태였다. **수정 후 원래 증상이 사라졌는지 확인하는 것과, 지적 사항을 반영하는 것은 다른 일이다.**

---

## 7. 인증 수단 제거 후 401이 403으로 바뀜

**증상**

6번 수정의 부작용이다. HTTP Basic을 제거하자 보호된 경로가 401이 아니라 **403**을 반환하기 시작했다.

**원인**

Spring Security는 인증 수단(엔트리포인트)이 하나도 없으면 기본값으로 `Http403ForbiddenEntryPoint`를 쓴다. 401은 "인증하면 접근 가능"이고 403은 "인증해도 안 됨"이라 의미가 다르다.

**해결**

```java
.exceptionHandling(handling -> handling
        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
```

JWT 필터가 들어올 이후 단계에서도 그대로 쓰는 구성이다.

**배운 것**

보안 설정은 한 항목을 끄면 다른 기본값이 튀어나온다. 그래서 수정 후 **경로별 상태 코드를 표로 만들어 전부 확인했다** — `/actuator/health` 200, `/actuator/env`·`/v3/api-docs`·`/swagger-ui`·존재하지 않는 경로 전부 401.

---

## 8. Docker 이미지에 145MB 중복 레이어

**증상**

런타임 이미지가 672MB로 예상보다 컸다.

**진단**

`docker history`로 레이어별 크기를 보니 원인이 명확했다.

```
COPY ... app.jar        145MB
RUN chown -R app:app    145MB   ← jar 전체가 다시 복사됨
```

**원인**

OverlayFS에서 `RUN chown -R`은 파일의 메타데이터만 바꿔도 **해당 파일 전체를 새 레이어에 복제**한다. 145MB jar의 소유자를 바꾸느라 145MB가 그대로 추가됐다.

**해결**

```dockerfile
COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
```

`COPY`에 `--chown`을 주면 복사 시점에 소유자가 정해져 추가 레이어가 생기지 않는다.

**결과**: 672MB → **527MB**. 145MB 감소로 예측과 일치했다.

함께 `.dockerignore`도 추가했다. 없어서 `.git`, `build`, `.gradle`이 빌드 컨텍스트에 전부 들어가고 있었다.

---

## 9. CI가 자격증명 없이 조용히 성공 (미해결)

**증상**

GCP 자격증명이 설정되지 않은 상태에서 배포 잡의 5개 스텝이 전부 스킵되고 **잡이 성공으로 끝난다.**

**판단**

현 단계에서는 의도된 동작이다. GCP 프로비저닝 전이라 배포할 대상이 없다. 그러나 **GCP를 연결한 뒤 시크릿이 만료되면 배포와 검증이 조용히 사라진 채 CI는 계속 초록불로 남는다.** Sprint 0의 목적("배포 플래그가 실제로 적용됐는지 CI가 검증한다")과 정반대 상황이 된다.

**처리**

지금 고치지 않고 **인수인계 항목으로 명시했다.** GCP 프로비저닝 시점에 자격증명 판별 스텝의 `else` 분기를 `exit 1`로 바꾸거나 `vars.DEPLOY_ENABLED` 구조로 전환한다. 인프라 문서의 Action Item에 등록해 두었다(T-048).

함께 발견된 것: 플래그 검증 스크립트가 `spec.template.metadata.annotations`(희망 상태)만 보고 **실제 트래픽을 받는 리비전을 보지 않는다.** 배포가 부분 실패해도 통과할 수 있다. 이것도 같은 시점에 처리하도록 기록했다(T-049).

**배운 것**

"지금은 문제가 아니지만 조건이 바뀌면 문제가 되는 것"을 발견했을 때, 고치지 않기로 했다면 **언제 누가 무엇을 해야 하는지까지 적어야** 기록이 의미를 갖는다.

---

## 10. 테스트가 운영 스키마 경로를 전혀 검증하지 않음

**증상**

테스트가 `local` 프로필(H2, Flyway 비활성)로만 실행되고 있었다. 즉 **Flyway와 `ddl-auto: validate`가 한 번도 실행되지 않았다.**

**영향**

엔티티를 추가하고 마이그레이션을 빠뜨려도 `./gradlew test`가 통과한다. PostgreSQL에 배포하는 시점에 `SchemaManagementException: Schema validation: missing table`로 처음 죽는다. **PR은 초록불이고 배포에서 터지는 구조다.**

**해결**

Testcontainers로 PostgreSQL 16을 띄우고 **기본 프로필 그대로** 기동하는 테스트를 추가했다. 데이터소스만 `@ServiceConnection`이 컨테이너로 바꿔치기한다.

단언은 두 개다.
- `flyway_schema_history` 테이블 존재 — 4번의 조용한 실패를 잡는다
- 실패한 마이그레이션 0건

`validate` 통과는 테스트가 뜬 것 자체로 증명된다.

**검증 — 실제로 잡는지 확인했다**

마이그레이션 없이 `@Entity`만 추가한 상태로 두 테스트를 돌렸다.

| 테스트 | 결과 |
|---|---|
| 기존 `local` 프로필 테스트 | **통과** — 문제를 못 잡는다 |
| 신규 Testcontainers 테스트 | **실패** — `Schema validation: missing table [probe_entity]` |

**의도적으로 하지 않은 것**

Docker가 없을 때 조건부 스킵을 넣지 않았다. 스킵되면 CI가 초록불인 채로 정작 막으려던 검증이 사라져, 이 테스트를 만든 이유 자체가 없어진다.

---

## 11. out-of-order 마이그레이션 — 빈 DB에서 재현되지 않는 실패

**문제**

브랜치 A가 `V...1000`을, B가 `V...1030`을 만들고 B가 먼저 병합·배포되면 이력에 1030이 적용된다. 그 뒤 A가 병합되면 1000은 이미 적용된 1030보다 낮은 버전이라 Flyway가 `FlywayValidateException`으로 기동을 거부한다.

**가장 고약한 점**

**이 실패는 빈 DB에서 절대 재현되지 않는다.** 새 DB는 어떤 순서로 만들어졌든 오름차순으로 적용하므로 10번에서 만든 마이그레이션 테스트도 통과한다. 이미 마이그레이션이 적용된 DB, 즉 운영에서만 드러난다.

**결정**

`spring.flyway.out-of-order: false`(기본값이지만 명시)를 유지하고 **병합 전 재타임스탬프**를 원칙으로 했다.

`out-of-order: true`를 택하지 않은 이유는 환경마다 실제 적용 순서가 달라지기 때문이다. 기존 DB는 1030→1000, 새 DB는 1000→1030으로 적용되어 두 마이그레이션이 같은 테이블이나 제약을 건드리면 결과가 갈릴 수 있다.

**해결 — DB 없이 판정하는 정적 검사**

수동 규율만으로는 잊어버리고, 잊으면 운영에서만 드러난다. 그래서 파일명만으로 판정하는 검사를 CI에 넣었다. PR이 추가한 마이그레이션의 버전이 기준 브랜치의 최대 버전보다 크지 않으면 빌드를 실패시킨다.

격리된 스크래치 저장소에서 네 가지 경우를 확인했다.

| 케이스 | 결과 |
|---|---|
| 기준보다 낮은 버전 | exit 1, 어떤 파일이 왜 문제인지 출력 |
| 기준보다 높은 버전 | exit 0 |
| 파일명 형식 위반 | exit 1 |
| 마이그레이션 없음 | exit 0, 건너뜀 |

**실전 확인**: 첫 마이그레이션 PR의 CI에서 실제로 동작했다.

```
기준: origin/main (최대 버전 0)
  [통과] V202609181920__create_users.sql (버전 202609181920)
```

---

## 12. GitHub Actions 스크립트 인젝션

**증상**

11번의 CI 가드를 추가한 커밋에서 보안 리뷰가 잡았다.

```yaml
run: |
  git fetch origin "${{ github.base_ref }}" --depth=0
```

**원인**

`${{ }}` 표현식은 셸이 실행되기 **전에** 문자열로 치환된다. 브랜치 이름에 셸 메타문자가 들어가면 그대로 실행된다. GitHub Actions의 전형적인 인젝션 경로다.

**해결**

환경변수로 받아 따옴표로 감싸 참조한다.

```yaml
env:
  BASE_REF: ${{ github.base_ref }}
run: |
  git fetch origin "$BASE_REF"
```

환경변수는 셸 치환이 아니라 프로세스 환경으로 전달되므로 값이 코드로 해석되지 않는다.

**추가 확인**

워크플로 전체를 스캔해 `run:` 블록에 직접 보간된 표현식이 더 없는지 확인했다. 나머지는 전부 `env:`/`with:` 블록이라 안전한 패턴이었다.

**배운 것**

방어 코드를 추가하면서 새로운 취약점을 만들었다. 자동 보안 리뷰가 없었다면 그대로 병합됐을 것이다.

---

## 13. 기준 타임존이 런타임에 강제되지 않음

**증상**

설계 문서가 "모든 시각 계산의 기준 타임존은 KST"로 정했는데 런타임에 강제하는 설정이 어디에도 없었다. `hibernate.jdbc.time_zone`, JVM `user.timezone`, Dockerfile `TZ` 전부 없었다.

**영향 분석**

**저장은 문제가 없다.** 모든 시각 컬럼이 `TIMESTAMPTZ`이고 엔티티가 `OffsetDateTime`이라 절대 시각이 그대로 보존된다. 문제는 그 시각을 **날짜로 환산**할 때다. KST는 UTC+9라 한국 시각 00:00~09:00은 UTC로는 아직 전날이다.

```
거래 절대 시각        : 2026-09-30T16:00:00Z
한국 사용자가 본 시각 : 2026-10-01 01:00   (10월 1일 새벽)

UTC 기준 날짜         : 2026-09-30   → 9월 예산에 집계된다
KST 기준 날짜         : 2026-10-01   → 10월이 맞다
```

매달 1일 00:00~09:00에 9시간짜리 구멍이 생긴다. 월간 예산 경계, 배치 실행 시각, 가입 코호트 집계가 전부 해당한다.

**컨테이너에서 실측**

```
TZ 없음        → JVM 기본 존: Etc/UTC      → 날짜: 2026-09-30
TZ=Asia/Seoul  → JVM 기본 존: Asia/Seoul   → 날짜: 2026-10-01
```

**해결 — 세 층**

| 층 | 구현 | 역할 |
|---|---|---|
| 코드 | `AppZone.KST` 단일 출처, 존 명시 | 기본 존과 무관하게 항상 옳다. **이것이 먼저다** |
| 운영 컨테이너 | `ENV TZ=Asia/Seoul` | 실수했을 때 피해를 줄이는 보험 |
| 테스트 | `user.timezone=UTC` | **일부러 운영과 다른 존으로 돌린다** |

세 번째가 핵심이다. 테스트도 KST로 맞추면 존을 빠뜨린 코드가 개발자 노트북에서도 CI에서도 통과해 **운영에서만 틀린 답**을 낸다. UTC로 돌리면 그런 코드가 테스트에서 깨진다.

테스트가 UTC로 돈다는 사실 자체도 단언으로 고정했다. 빌드 설정이 나중에 사라지면 그 테스트가 깨져 알려준다.

**부수 확인**: 기존 테스트 8건이 UTC에서도 그대로 통과했다. 현재 코드에 기본 존 의존이 없다는 뜻이다.

---

## 14. 포트 점유로 이전 컨테이너 응답을 오인

**증상**

컨테이너 검증 중 헬스체크가 200을 반환했다. 그런데 앱 컨테이너 실행 명령은 실패한 상태였다.

```
docker: Bind for 0.0.0.0:18080 failed: port is already allocated
...
{"status":"UP"} [HTTP 200]
```

**원인**

이전 검증에서 남은 컨테이너가 같은 포트를 점유하고 있었다. 새 컨테이너는 뜨지 못했고, **200 응답은 옛 컨테이너가 준 것이었다.**

**해결**

포트를 바꿔 다시 실행하고 확인했다. 이후 검증에서는 실행 중인 컨테이너 목록을 먼저 확인하고, 끝나면 컨테이너·네트워크·이미지를 정리하는 절차를 넣었다.

**배운 것**

**"응답이 왔다"와 "내가 띄운 것이 응답했다"는 다르다.** 이 차이를 놓치면 고치지도 않은 것을 고쳤다고 판단한다. 검증 결과를 신뢰하려면 검증 환경 자체가 깨끗해야 한다.

---

## 15. `git reset --hard`로 커밋 전 작업 유실

**증상**

CI 가드 스크립트를 테스트하느라 만든 프로브 파일을 정리하려고 `git reset --hard origin/main`을 실행했다. 프로브와 함께 **아직 커밋하지 않은 작업물 3개 파일이 사라졌다.**

**대응**

세 파일을 다시 작성하고 검증을 다시 했다. 이후 스크립트 검증은 저장소를 건드리지 않는 별도 스크래치 저장소에서 수행했다.

**배운 것**

검증을 위해 저장소 상태를 조작하는 것 자체가 위험하다. 특히 실패 케이스를 재현하려고 일부러 잘못된 상태를 만들 때 그렇다. **검증은 격리된 곳에서 하고 작업 저장소는 건드리지 않는 것이 맞다.**

### 덧 — 같은 사고를 한 번 더 냈다 (2026-09-23)

`check-migration-order.sh`에 불변성 검사를 추가하고, 세 경우(수정·삭제·정상 추가)를 변이로 확인하려 했다. 각 경우마다 프로브를 커밋하고 `git reset --hard <직전 커밋>`으로 되돌리는 방식이었다.

**그 reset이 아직 커밋하지 않은 스크립트 수정 자체를 지웠다.**

```
스크립트 수정 (커밋 안 함)
  → 프로브 커밋 → 검사 실행 → reset --hard HEAD~1   ← 여기서 수정이 날아감
  → 이후 검사가 전부 옛 스크립트로 돌았다
```

②(삭제) 케이스가 안 잡히길래 `--diff-filter` 문제인 줄 알고 디버깅했는데, 원인은 **실행되던 스크립트가 옛 버전**이었다. 출력이 "추가·수정된 마이그레이션이 없다"가 아니라 옛 문구인 "추가된 마이그레이션이 없다"인 것이 단서였다.

다시 작성하고 **먼저 커밋한 뒤** 검증하니 세 경우 전부 의도대로 나왔다.

**공교롭게도 원래 사고(2026-09-18)도 같은 스크립트를 테스트하다 났다.** 이 스크립트는 "저장소 상태를 조작해야만 검증되는" 성격이라 구조적으로 같은 사고를 부른다.

**추가로 배운 것**

- 위 교훈("격리된 곳에서 검증")을 **읽고도 피하지 못했다.** 기록이 있다는 것만으로는 반복을 막지 못한다.
- `reset --hard`가 지우는 범위를 "프로브만"으로 좁게 본 것이 원인이다. 그 명령은 **워킹트리 전체**를 되돌린다.
- 실용적인 방어: **검증 대상을 먼저 커밋한다.** 격리 저장소를 만들지 않더라도 이것만으로 이번 사고는 막혔다.
- 변이 검증 중 결과가 예상과 다르면 **변이가 아니라 도구를 먼저 의심한다.** "정말 내가 고친 코드가 돌고 있나"를 확인하는 데 1분이면 됐다.

---

## 16. 마이그레이션 순서 검사가 커밋 전에는 조용히 건너뛴다

**증상**

T-023의 새 마이그레이션 `V202609202314__create_budget_periods.sql`을 작성한 직후
`scripts/check-migration-order.sh`를 돌렸더니 이렇게 나왔다.

```
추가된 마이그레이션이 없다. 검사를 건너뛴다.
```

종료 코드는 0이다. 방금 파일을 만들었는데 "추가된 마이그레이션이 없다"고 한다.

**진단**

스크립트가 추가분을 찾는 방식이 단서였다.

```bash
added=$(git diff --name-only --diff-filter=A "$BASE...HEAD" -- "$MIGRATION_DIR" || true)
```

기준은 `origin/main...HEAD`다. 즉 **커밋된 것만** 본다. 파일은 아직 untracked라
`git diff`의 시야 밖이었다. `git status --short`에 `??`로 떠 있는 것을 보고 확인했다.

**원인**

검사 대상이 작업 트리가 아니라 커밋 이력이다. 커밋 전에 돌리면 비교할 추가분이 0건이고,
스크립트는 그때 "건너뛴다"며 0으로 끝난다. 설계상 맞는 동작이지만, **실행 시점이 틀리면
검사를 통과한 것과 구분되지 않는 출력이 나온다.**

**해결**

스크립트는 고치지 않았다. CI에서는 항상 커밋된 상태로 돌아가므로 원래 목적에는 문제가 없다.
대신 **커밋한 뒤에 다시 돌려** 실제 판정을 받았다.

```
기준: origin/main (최대 버전 202609181920)
  [통과] V202609202314__create_budget_periods.sql (버전 202609202314)
마이그레이션 순서 검사 통과.
```

**검증**

같은 스크립트를 커밋 전/후로 각각 돌려 출력이 "건너뛴다"에서 "[통과]"로 바뀌는 것을 확인했다.
두 경우 모두 종료 코드는 0이라 **종료 코드만으로는 구분할 수 없다.**

**배운 것**

"exit 0"과 "검사가 실제로 수행됐다"는 다른 말이다. 11번(out-of-order 마이그레이션)을 막으려고
만든 검사인데, 잘못된 시점에 돌리면 그 검사 자체가 조용히 건너뛰어진다. 검사 스크립트를
돌릴 때는 **무엇을 검사했는지 출력에 이름이 찍혔는지**까지 봐야 한다.

---

## 17. 인덱스를 지워도 빌드와 스키마 테스트가 전부 통과한다

**증상**

T-006(`accounts` 스키마)의 QA 검증 중, 마이그레이션에서 `CREATE INDEX` 2줄을 통째로
지우고 돌렸는데 아무것도 빨간불이 되지 않았다.

```
$ # V202609202355__create_accounts.sql 에서 CREATE INDEX 2줄 삭제
BUILD SUCCESSFUL
9 tests completed, 0 failed
```

`PostgresMigrationTest`(Flyway + `ddl-auto: validate`)도, 제약 테스트 8건도 전부 통과했다.
인덱스가 스키마에서 사라졌는데 검증망 어디에도 걸리지 않는다.

**진단**

QA가 유니크 제약 쪽에서는 변이 5회로 **각 열까지 고정돼 있음**을 확인한 직후였다.
같은 방식(열 하나씩 빼고 돌리기)을 인덱스에 적용했더니 전부 초록이었다. 제약은
잡히는데 인덱스만 안 잡힌다는 비대칭이 단서였다.

두 가지를 차례로 확인했다.

1. `ddl-auto: validate`의 검사 범위 — 테이블·열·타입은 보지만 인덱스는 보지 않는다.
   (**정정**: 이때는 여기에 nullable도 포함된다고 적었는데 틀렸다. 18번에서 확인했듯
   `validate`는 nullability를 보지 않는다.)
   `bank_code`를 `BIGINT`로 바꿨을 때는 컨텍스트 로딩 단계에서 9건 전부 죽었는데
   (`String` 필드 ↔ `BIGINT` 열 불일치), 인덱스를 지울 때는 아무 반응이 없었다
2. 제약 테스트가 무엇을 보는가 — 전부 "이 INSERT가 거부되는가 / 허용되는가"다.
   즉 **결과**를 본다

그래서 원인 축이 여기 있다는 결론에 닿았다. **제약은 틀린 답을 내게 하지만, 인덱스는
느린 답을 내게 한다.**

**원인**

인덱스 부재는 쿼리 결과를 바꾸지 않는다. 같은 행이 같은 순서로 나오고, 다만 풀스캔이
될 뿐이다. 결과를 단언하는 테스트로는 **원리적으로** 잡을 수 없다. 그리고 `validate`의
검사 범위에도 인덱스가 없으므로, 마이그레이션에서 인덱스가 조용히 사라져도 빌드는
끝까지 초록이다.

`accounts`의 `ix_accounts_consent_status_last_synced_at`는 동기화 스케줄러가 대상 계좌를
고르는 경로다. 이것이 사라진 채 병합됐다면 계좌가 늘어난 뒤 운영에서야 드러났을 것이다.

**해결**

결과가 아니라 **스키마 자체**를 조회해 단언했다. `pg_indexes`의 `indexdef`를 읽어
이름과 대상 열 구성을 함께 본다.

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

`endsWith`로 괄호 안 열 목록을 통째로 맞춰 보므로 열의 **순서**와 **정렬 방향(DESC)**까지
고정된다. 세 스키마 테스트(`UserSchemaTest`·`BudgetSchemaTest`·`AccountSchemaTest`)에
같은 모양으로 넣어 인덱스 6개를 덮었다. 테스트 간 공유 유틸리티 클래스는 만들지 않았다.
기존 세 테스트가 각각 독립적인 구조라 그 구조를 유지했다.

**검증**

인덱스 6개를 각각 **삭제**(3회)하거나 **이름은 그대로 둔 채 대상 열만 변경**(3회)해
돌렸다. 마이그레이션은 매회 백업에서 원복했다.

```
### 변이1: ix_users_joined_at 삭제
users·user_consents 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
5 tests completed, 1 failed / BUILD FAILED in 37s

### 변이2: ix_user_consents_user_id_type_consented_at 의 DESC 제거 (이름 유지)
5 tests completed, 1 failed / BUILD FAILED in 37s

### 변이3: ix_budget_periods_user_id_status 삭제
9 tests completed, 1 failed / BUILD FAILED in 37s

### 변이4: ix_budget_periods_status_period_end (status, period_end) → (period_end, status) (이름 유지)
9 tests completed, 1 failed / BUILD FAILED in 37s

### 변이5: ix_accounts_user_id 삭제
9 tests completed, 1 failed / BUILD FAILED in 37s

### 변이6: ix_accounts_consent_status_last_synced_at 에서 last_synced_at 열 제거 (이름 유지)
9 tests completed, 1 failed / BUILD FAILED in 37s
```

**반영 전에는 같은 변이가 `BUILD SUCCESSFUL`이었다.** 이것이 양방향의 한쪽이다.

다른 한쪽은 실패 건수에 있다. 6회 모두 실패한 것은 **새 인덱스 테스트 하나뿐**이다
(`9 tests completed, 1 failed`). 다른 테스트는 인덱스에 전혀 반응하지 않는다는 뜻이고,
곧 이 구멍은 별도 단언 없이는 메울 수 없다는 증거다.

반영 후 전체 빌드는 초록이다.

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 45s
7 actionable tasks: 7 executed
```

세 스키마 테스트가 각각 1건씩 늘어 29 → 32건이 됐다.

**배운 것**

**이름 단언과 열 구성 단언은 서로를 대체하지 않는다.** 변이 2·4·6은 인덱스 이름을
유지한 채 열 구성만 바꿨다. 존재 여부만 봤다면 전부 통과했을 것이다. 같은 교훈이
T-023의 유니크 제약에서도 나왔는데(이름만 확인하면 제약을 `UNIQUE (user_id)`로 좁혀도
중복 거부 테스트는 통과한다), 그때는 제약 이름과 열 범위의 문제였고 여기서는 인덱스
이름과 열 구성의 문제다. **식별자가 맞다는 것과 정의가 맞다는 것은 다른 말이다.**

더 일반적으로는, **테스트가 보는 축이 하나 부족하면 그 축의 결함은 통째로 안 보인다.**
제약 테스트는 "결과" 축만 봤고, 성능에만 영향을 주는 스키마 요소는 그 축에 아예
투영되지 않았다. 10번(local 프로필 테스트가 운영 스키마 경로를 검증하지 않음)과 같은
모양의 실패다.

---

## 18. `NOT NULL`을 지워도 빌드와 스키마 테스트가 전부 통과한다

**증상**

T-013(`categories`·`merchant_keyword_rules` 스키마)의 QA 검증 중, 마이그레이션에서
`keywords JSONB NOT NULL`의 `NOT NULL`을 지우고 돌렸는데 아무것도 빨간불이 되지 않았다.

```
$ # V202609210052__create_categories.sql 에서 keywords 의 NOT NULL 제거
BUILD SUCCESSFUL
9 tests completed, 0 failed
```

엔티티에는 `@Column(nullable = false)`가 분명히 붙어 있고, `PostgresMigrationTest`는
`ddl-auto: validate`로 뜬다. 그런데도 제약이 사라진 것을 아무도 모른다.

**진단**

17번(인덱스)과 같은 계열로 보였지만 결정적으로 다른 점이 있었다. 인덱스는 **느린 답**을
내지만 `NOT NULL`은 **틀린 데이터**를 들인다. 그래서 왜 안 잡히는지를 따로 따져야 했다.

세 겹이 동시에 비어 있었다.

1. **`ddl-auto: validate`가 nullability를 보지 않는다.** 17번에서 "테이블·열·타입·nullable은
   본다"고 적었는데 이것이 틀렸다. 실제로는 타입까지다. 타입을 바꿨을 때
   (`bank_code`를 `BIGINT`로) 컨텍스트 로딩 단계에서 전부 죽었던 경험을 nullability에도
   적용된다고 넘겨짚은 것이었다. `NOT NULL`을 지우고 돌리니 컨텍스트가 멀쩡히 떴다
2. **엔티티의 `@Column(nullable = false)`는 DDL 생성용이다.** 이 프로젝트는 Flyway가
   테이블을 만들고 Hibernate는 검증만 하므로, 이 속성은 운영 경로에서 **아무 일도 하지
   않는다.** 문서 역할만 한다
3. **제약 테스트는 늘 제대로 된 값을 넣는다.** 빈 값을 막는 규칙이 있든 없든 INSERT는
   성공하고 결과도 같다. 17번의 "결과 축" 문제가 여기서도 반복된다

**원인**

세 겹이 전부 같은 축을 비켜 간다. 검증망 어디에도 "이 열이 null을 거부하는가"를 보는
눈이 없었다. 그래서 마이그레이션에서 `NOT NULL`이 사라져도 빌드는 끝까지 초록이다.

인덱스와 달리 이것은 성능 문제가 아니다. `merchant_keyword_rules.keywords`가 null을
허용하면 키워드가 빈 룰이 들어오고, T-019 자동 분류가 그 행에서 NPE를 내거나 조용히
건너뛴다. `accounts.codef_connected_id`가 null을 허용하면 해지할 커넥티드아이디가 없는
계좌가 생겨 탈퇴(F-ZPNVKT)가 반쯤 실패한다.

**해결**

`information_schema.columns`에서 테이블 전체의 `column_name → is_nullable` 맵을 한 번에
읽어 기대 맵과 **통째로** 비교한다. 테이블당 쿼리 하나, 단언 하나다.

```java
private void assertNullability(String tableName, Map<String, String> expected) {
	List<Map<String, Object>> columns = new JdbcTemplate(dataSource).queryForList(
			"SELECT column_name, is_nullable FROM information_schema.columns "
					+ "WHERE table_schema = 'public' AND table_name = ?",
			tableName);

	Map<String, String> actual = columns.stream().collect(Collectors.toMap(
			column -> (String) column.get("column_name"),
			column -> (String) column.get("is_nullable")));

	assertThat(actual)
			.as("%s 테이블의 NOT NULL 구성이 설계와 다르다", tableName)
			.containsExactlyInAnyOrderEntriesOf(expected);
}
```

**열을 하나씩 단언하지 않은 것이 요점이다.** 하나씩 보면 `NOT NULL`이 사라지는 쪽만
막힌다. 통째로 비교하면 반대 방향 — 원래 null을 허용하던 열(`users.email`,
`accounts.account_name`, `status_thresholds.end_rate` 등)에 실수로 `NOT NULL`이 붙는 것 —
도 함께 걸리고, 열이 늘거나 없어지는 것까지 걸린다. null 허용은 의도적 설계다.
`users.email`은 애플의 이메일 가리기 때문에, `status_thresholds.end_rate`는 `OVER_BUDGET`에
상한이 없기 때문에 null이다. 이쪽이 막히는 것도 결함이다.

네 스키마 테스트(`UserSchemaTest`·`BudgetSchemaTest`·`AccountSchemaTest`·`CategorySchemaTest`)에
같은 모양으로 넣어 테이블 7개를 덮었다. 17번과 마찬가지로 공유 유틸리티 클래스는 만들지
않고 각 파일에 같은 모양의 헬퍼를 뒀다.

**검증**

테이블 7개 전부에 대해 (a) `NOT NULL` 열에서 `NOT NULL` 제거, null 허용 열이 있는 테이블
3개에 대해 (b) null 허용 열에 `NOT NULL` 추가 — 합 10회. 마이그레이션은 매회 백업에서
원복했다.

```
### (a) users.provider NOT NULL 제거
users·user_consents 스키마 제약 검증 > NOT NULL 구성이 설계와 정확히 일치한다 — 빠진 것도 더 붙은 것도 없다 FAILED
6 tests completed, 1 failed / BUILD FAILED in 7s

### (a) user_consents.consent_version NOT NULL 제거
6 tests completed, 1 failed / BUILD FAILED in 7s

### (a) budget_periods.target_amount NOT NULL 제거
10 tests completed, 1 failed / BUILD FAILED in 7s

### (a) status_thresholds.start_rate NOT NULL 제거
10 tests completed, 1 failed / BUILD FAILED in 7s

### (a) accounts.codef_connected_id NOT NULL 제거
10 tests completed, 1 failed / BUILD FAILED in 7s

### (a) categories.code NOT NULL 제거
10 tests completed, 1 failed / BUILD FAILED in 7s

### (a) merchant_keyword_rules.keywords NOT NULL 제거
10 tests completed, 1 failed / BUILD FAILED in 7s

### (b) users.email 에 NOT NULL 추가
6 tests completed, 4 failed / BUILD FAILED in 7s

### (b) status_thresholds.end_rate 에 NOT NULL 추가
10 tests completed, 3 failed / BUILD FAILED in 7s

### (b) accounts.account_name 에 NOT NULL 추가
10 tests completed, 9 failed / BUILD FAILED in 7s
```

**반영 전에는 (a) 7회가 전부 `BUILD SUCCESSFUL`이었다.** 이것이 양방향의 한쪽이다.

(a)에서 실패한 것이 매회 **새 단언 하나뿐**(`... 1 failed`)이라는 점이 다른 한쪽이다.
기존 테스트는 `NOT NULL`의 유무에 전혀 반응하지 않는다는 뜻이고, 곧 이 구멍은 별도
단언 없이는 메울 수 없다는 증거다.

(b)는 실패 건수가 더 크다. 원래 null을 넣던 기존 테스트들이 함께 깨지기 때문이다.
`accounts.account_name`이 9건까지 번진 것은 이 테스트의 `account()` 헬퍼가 모든 경우에
`accountName`을 null로 넘기기 때문이다. 다만 **기존 테스트만으로는 방향이 모호하다.**
"account_name에 NOT NULL이 붙었다"가 아니라 "계좌 저장이 깨졌다"로만 읽힌다. 새 단언이
어느 열이 어떻게 달라졌는지를 짚어 준다.

반영 후 전체 빌드는 초록이다.

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 16s
```

네 스키마 테스트가 각각 1건씩 늘어 전체 41 → 45건이 됐다.

**배운 것**

**17번에서 내가 적은 `validate`의 검사 범위가 틀렸다.** "테이블·열·타입·nullable은 본다"고
썼는데 nullable은 보지 않는다. 타입 변이 한 번이 컨텍스트를 죽인 것을 보고 그 옆 칸까지
같이 덮였다고 넘겨짚은 것이다. **한 축이 막혔다는 관측은 옆 축이 막혔다는 근거가 되지
않는다.** 축마다 따로 변이를 돌려야 알 수 있다.

**엔티티에 적힌 제약이 반드시 강제되는 제약은 아니다.** `@Column(nullable = false)`는
스키마를 Hibernate가 만들 때만 의미가 있다. Flyway로 옮긴 순간 이 속성은 주석이 됐는데,
코드만 읽으면 여전히 강제되는 것처럼 보인다. **어느 층이 그 규칙을 실제로 집행하는지를
층 단위로 따져야 한다.**

**같은 계열이어도 피해의 성격이 다르면 우선순위가 다르다.** 17번은 느려지는 문제였고
이것은 틀린 데이터가 들어오는 문제다. 인덱스가 빠진 채 배포되면 나중에 느려질 뿐이지만
`NOT NULL`이 빠진 채 배포되면 그 사이 들어온 null 행이 남는다. 마이그레이션으로 제약을
되돌릴 때 기존 행 때문에 실패하므로 정리 작업이 따로 필요해진다.

---

## 19. 축은 있는데 적용 범위가 좁다 — CHECK 값 제거·CHECK 통째 삭제·`VARCHAR` 길이 변경이 새어 나간다

**증상**

T-014를 QA가 검증하면서 마이그레이션을 변이시켰더니, 명백한 결함 여섯 가지가 전부
`BUILD SUCCESSFUL`이었다.

```
ck_users_character_type 에서 'CAT' 제거            → BUILD SUCCESSFUL
ck_accounts_consent_status 에서 'REVOKED' 제거     → BUILD SUCCESSFUL
ck_budget_periods_status 에서 'CLOSED' 제거        → BUILD SUCCESSFUL
ck_status_thresholds_status_code 에서 'WAKE' 제거  → BUILD SUCCESSFUL
accounts.bank_code VARCHAR(10) → VARCHAR(100)      → BUILD SUCCESSFUL
users.email VARCHAR(320) → VARCHAR(255)            → BUILD SUCCESSFUL
```

`'CAT'`을 뺀 스키마는 고양이 캐릭터를 고를 수 없고, `'REVOKED'`를 뺀 스키마는 계좌 연결
해제 상태를 저장할 수 없다. `'CLOSED'`를 뺀 스키마는 s9 배치가 예산 기간을 종료하지 못한다.
기능이 통째로 막히는데 테스트 76건이 전부 초록이었다.

**진단**

17번(인덱스)·18번(`NOT NULL`)과 같은 계열로 보였지만 성격이 달랐다. **그 둘은 축이 아예
없었고, 이번은 축이 이미 있었다.**

- CHECK 단언은 있었다. 그런데 **거부 케이스만** 봤다. 정의되지 않은 값(`'PENDING'`,
  `'RETRY'`)이 막히는지는 확인하면서 정의된 값이 통과하는지는 아무도 보지 않았다.
  CHECK 목록에서 값을 빼는 변이는 거부 단언을 그대로 통과한다.
  (여기서 "있었다"는 `AccountSchemaTest`·`TransactionSchemaTest` 이야기다.
  `UserSchemaTest`·`BudgetSchemaTest`는 거부 단언조차 없었는데, 그 사실은 이 항목을 쓰고
  난 뒤에야 드러났다. 아래 "같은 실수가 한 번 더" 절을 보라.)
- 타입 단언도 있었다. T-013에서 `SMALLINT`→`INTEGER`를 잡으려고 `udt_name`을 넣었다.
  그런데 `VARCHAR(10)`과 `VARCHAR(320)`의 `udt_name`은 **둘 다 `varchar`다.** 문자열
  컬럼에서는 이 축이 사실상 아무것도 보지 않는다.

더 뼈아픈 것은 **두 해법이 이미 프로젝트 안에 있었다는 점이다.** "허용 케이스도 함께
본다"는 원리는 T-023에서 유니크 제약에 대해 얻었고(유니크를 과도하게 좁혀도 거부 단언은
통과한다), 그 자리에는 `sameBankDifferentMaskedNoForSameUserIsAllowed` 같은 허용 케이스가
짝으로 들어가 있다. 그 원리를 CHECK로 옮기지 않았을 뿐이다.

**원인**

**있는 축을 믿고 그 옆을 보지 않았다.** "CHECK 테스트가 있다", "타입 단언이 있다"는 사실이
"그 축이 변이를 잡는다"는 확인을 대신했다. 축의 존재와 축의 적용 범위는 다른 문제인데
목록에 체크가 들어간 것으로 만족했다.

`assertIndex` 헬퍼가 세 벌로 갈라져 있던 것도 같은 뿌리다. T-013에서 인덱스 종류를,
T-014에서 부분 조건을 새로 보기 시작했지만 **앞서 만든 파일로 되돌아가지 않았다.** 그래서
같은 이름의 헬퍼가 파일마다 다른 것을 보고 있었다.

| 파일 | `assertIndex`가 보던 것 |
|---|---|
| `UserSchemaTest`·`BudgetSchemaTest`·`AccountSchemaTest` | 열 구성만 |
| `CategorySchemaTest` | + 인덱스 종류(`USING gin`/`btree`) |
| `TransactionSchemaTest`·`SyncAttemptSchemaTest` | + 부분 조건(`WHERE ...`) |

**해결**

세 가지를 다섯 파일에 한 번에 맞췄다.

1. **CHECK 허용 케이스.** 각 마이그레이션의 CHECK 목록을 읽어 정의된 값을 raw INSERT로
   하나씩 넣고, 저장된 뒤 되읽어 값까지 확인한다. 9종 전부에 넣었다(`categories`는 CHECK가
   없어 해당 없음).
2. **`VARCHAR` 길이.** `assertColumnType`이 `character_maximum_length`를 함께 본다.
   문자열이 아닌 타입은 **null을 기대하게** 해서, 숫자·시각 컬럼이 문자열로 바뀌는 반대
   방향 변이도 같은 단언에 걸리게 했다.
3. **`assertIndex` 통일.** 가장 넓은 것(부분 조건까지 보는 T-014 버전)으로 다섯 파일을
   맞췄다. 조건 인자가 null이면 `WHERE` 절이 **없는 것**까지 단언한다.

```java
String expectedTail = "USING " + method + " (" + expectedColumns + ")"
		+ (expectedPredicate == null ? "" : " WHERE " + expectedPredicate);
assertThat(definitions.get(0))
		.as("인덱스 %s의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다", indexName)
		.endsWith(expectedTail);
```

17·18번과 마찬가지로 공유 유틸리티 클래스는 만들지 않고 각 파일에 같은 모양의 헬퍼를 뒀다.
테스트 파일끼리 의존하면 한 테스트를 고칠 때 다른 테스트가 함께 흔들린다.

**검증**

변이 7종을 **동시에** 넣고 소급 전후를 한 번씩 돌렸다. 마이그레이션은 `git checkout`으로
원복했다.

```
--- BEFORE (소급 전 테스트 + 변이 7종) ---
accounts 스키마 제약 검증 > 설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지 FAILED
76 tests completed, 1 failed

--- AFTER (소급 후 테스트 + 같은 변이 7종) ---
accounts … > 컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지 FAILED
accounts … > 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향·부분 조건까지 FAILED
accounts … > 명세에 있는 열거형 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다 FAILED
budget_periods·status_thresholds … > 명세에 있는 열거형 값은 전부 저장된다 … FAILED
users·user_consents … > 컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지 FAILED
users·user_consents … > 설계한 인덱스가 실제로 만들어져 있다 … FAILED
users·user_consents … > 명세에 있는 열거형 값은 전부 저장된다 … FAILED
83 tests completed, 7 failed
```

**7건 중 6건이 소급 전에는 조용했다.** 유일하게 잡힌 하나는 `ix_accounts_user_id`에
`WHERE` 절을 붙인 변이인데, 이것도 옛 헬퍼가 조건을 봐서가 아니라 `endsWith("(user_id)")`가
우연히 어긋나서였다. 반대 방향(조건을 **빼는** 변이)이었다면 그대로 통과했을 것이다.

실패 메시지가 어느 축인지 짚어 준다.

```
[users.email의 길이가 설계와 다르다]
expected: 320
 but was: 255

[인덱스 ix_users_joined_at의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다]
Expecting actual:
  "CREATE INDEX ix_users_joined_at ON public.users USING brin (joined_at)"
to end with:
  "USING btree (joined_at)"

ERROR: new row for relation "users" violates check constraint "ck_users_character_type"
```

원복 후 전체 빌드는 초록이다.

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 21s
```

스키마 테스트가 `users` 6→8, `budget` 10→12, `accounts` 10→12, `categories` 10→11건으로
늘었다(아래 거부 케이스까지 더한 최종값은 `users` 9, `budget` 13이다).

**한 가지 더 나온 사실.** `ck_users_provider`의 `'APPLE'`과 `ck_status_thresholds_status_code`의
`'OVER_BUDGET'`은 소급 전에도 잡혔다. 기존 테스트가 마침 그 값을 쓰고 있었기 때문이다
(`sameProviderUserIdOnAnotherProviderIsAllowed`, `overBudgetThresholdHasNoEndRate`).
**우연한 커버리지다.** 어떤 값이 우연히 덮이고 어떤 값이 새는지는 테스트를 하나하나 읽기
전에는 알 수 없으므로, 목록 전체를 명시적으로 단언하는 편이 싸다.

**같은 실수가 한 번 더 — 허용 케이스를 채우면서 거부 케이스가 빈 것을 못 봤다**

위 소급 작업을 끝내고 푸시 전 점검에서 리더가 한 가지를 더 찾았다. 내가 채운 것은
**허용 케이스**뿐이었고, `UserSchemaTest`·`BudgetSchemaTest`·`CategorySchemaTest`에는
**거부 케이스가 처음부터 없었다.** 제약 이름으로 거부를 단언하는 곳을 세면 이랬다.

```
TransactionSchemaTest   7
AccountSchemaTest       2
SyncAttemptSchemaTest   2
UserSchemaTest          0
BudgetSchemaTest        0
CategorySchemaTest      0
```

리더가 `ck_users_provider`와 `ck_users_character_type`을 **통째로 삭제**하고 돌리자 그대로
통과했다.

```
$ ./gradlew test --rerun-tasks --tests 'com.petgyebu.telo.user.UserSchemaTest'
BUILD SUCCESSFUL in 10s
```

**허용 케이스와 거부 케이스는 서로를 대체하지 않는다.** 제약이 통째로 사라지면 *모든* 값이
통과하므로 허용 단언은 전부 초록이다. 반대로 목록에서 값 하나만 빠지면 거부 단언은 그대로
통과한다. 둘은 서로 다른 변이를 잡는다.

`UserSchemaTest`에 CHECK 3종, `BudgetSchemaTest`에 CHECK 4종(값 범위 둘 포함)의 거부
케이스를 `JdbcTemplate` raw INSERT로 추가하고 제약 이름까지 단언했다. `@Enumerated(STRING)`
때문에 JPA로는 잘못된 값 자체를 넣을 수 없어 raw SQL이 필요하다. `categories`와
`merchant_keyword_rules`에는 CHECK가 하나도 없어 넣지 않았다 — 없는 것을 만들지 않는다.

증명은 네 갈래로 했다.

```
### CHECK 4개 통째 삭제 + 거부 케이스 없던 테스트 (소급 전)
$ ./gradlew test --rerun-tasks --tests '*UserSchemaTest' --tests '*BudgetSchemaTest'
BUILD SUCCESSFUL in 10s

### 같은 변이 + 거부 케이스 추가 후
budget_periods·status_thresholds … > 정의되지 않은 status·status_code는 저장할 수 없다 — CHECK 제약 FAILED
users·user_consents … > 정의되지 않은 열거형 값은 저장할 수 없다 — CHECK 제약 셋 FAILED
22 tests completed, 2 failed
AssertionError: Expecting code to raise a throwable.
```

그리고 두 변이가 **서로 다른 테스트에 걸린다는 것**을 같은 CHECK 하나로 보였다.

```
### (i) ck_budget_periods_status 에서 'CLOSED' 값 하나만 제거
… > 명세에 있는 열거형 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다 FAILED
13 tests completed, 1 failed

### (ii) ck_budget_periods_status 통째 삭제
… > 정의되지 않은 status·status_code는 저장할 수 없다 — CHECK 제약 FAILED
13 tests completed, 1 failed
```

(i)에서는 거부 테스트가, (ii)에서는 허용 테스트가 각각 멀쩡히 통과한다. 한쪽만 있으면
다른 쪽 변이는 그대로 새어 나간다.

**배운 것**

**축이 있다는 것과 축이 넓다는 것은 다르다.** 17·18번은 "없는 축을 만든" 일이었고 이번은
"있는 축의 사각지대"였다. 후자가 더 잡기 어렵다. 검증 목록에 이미 체크가 들어가 있어
다시 들여다볼 이유가 없어 보이기 때문이다. **축을 새로 추가할 때는 그 축이 못 보는 것이
무엇인지를 같이 적어야 한다.** `udt_name`을 넣을 때 "길이는 보지 못한다"를 함께 적었다면
T-013에서 끝났을 일이다.

**원리를 얻은 자리와 원리를 적용할 자리는 다르다.** "허용 케이스도 본다"는 T-023에서
유니크에 대해 얻었지만 정작 필요한 곳은 CHECK였다. 교훈을 그 자리에만 반영하면 옆 칸은
그대로 빈다. 새 원리를 얻으면 **같은 성질의 제약이 어디에 또 있는지** 한 번 훑어야 한다.

**이 세션에서 같은 모양의 실수가 다섯 번 나왔다.** 여섯 번째는 다음 Task(T-041)에서
나왔고 별도 항목(20번)으로 적었다. 축을 하나 채우면서 그 축의 반대편을
매번 비워 뒀다.

| 축 | 채운 쪽 | 비워 둔 쪽 | 드러난 계기 |
|---|---|---|---|
| 유니크 | 제약 이름 | 허용 범위 | T-023 |
| 인덱스 | 존재 | 열 구성·종류 | 17번, T-013 |
| 타입 | `udt_name` | VARCHAR 길이 | T-014 QA |
| CHECK | 허용 케이스 | 거부 케이스 | 푸시 전 점검 |
| FK CASCADE | 출금 쪽 | **입금 쪽** | 푸시 전 전수 점검 |
| 엔티티 | DB 제약 경로 | 엔티티 경로 | T-041 QA (20번) |

**다섯 번째는 앞의 넷과 성격이 다르다.** 앞의 넷은 *다른 종류의 단언*이 빠진 것이었다.
이번은 **같은 단언을 대칭 위치에 적용하지 않은 것**이다. `transfer_links`는 두 컬럼에
똑같이 `ON DELETE CASCADE`가 걸려 있는데 테스트는 출금 거래만 지웠다. 입금 쪽 FK의
`ON DELETE` 규칙은 한 번도 실행되지 않아, 그 CASCADE를 떼도 전체 빌드가 초록이었다.

```
### 입금 쪽 ON DELETE CASCADE만 제거 (대칭 테스트 추가 전)
$ ./gradlew test --rerun-tasks --tests '*TransactionSchemaTest'
BUILD SUCCESSFUL in 8s
```

**같은 제약이 두 컬럼에 걸려 있으면 한쪽만 확인해서는 안 된다.** 대칭 테스트를 넣고 두
변이를 따로 돌리자 각각 제 짝만 깨진다.

```
### (A) 입금 쪽만 제거
… > 입금 거래를 지워도 이체 연결이 함께 지워진다 — 입금 쪽 ON DELETE CASCADE FAILED
23 tests completed, 1 failed
ERROR: … violates foreign key constraint "transfer_links_deposit_transaction_id_fkey"

### (B) 출금 쪽만 제거
… > 출금 거래를 지우면 이체 연결도 함께 지워진다 — 출금 쪽 ON DELETE CASCADE FAILED
23 tests completed, 1 failed
ERROR: … violates foreign key constraint "transfer_links_withdrawal_transaction_id_fkey"
```

**발견 방법도 앞의 넷과 달랐다.** 넷은 QA나 변이가 우연히 그 자리를 건드려 드러났지만,
이것은 **마이그레이션에 실재하는 구조를 종류별로 세어 단언 유무와 대조하는 전수 점검**에서
나왔다. 구멍을 쫓는 대신 목록을 만들어 맞춰 보는 쪽이 남은 사각지대를 찾는 데 효율적이다.

다섯 번 다 "한쪽을 넣었다"에서 작업이 끝났다. **한 축을 채우는 작업이 곧 그 축의 반대편을
보는 계기가 되어야 한다.** 제약을 검증하는 단언은 거의 언제나 쌍이다 — 막아야 할 것이
막히는가와 통과해야 할 것이 통과하는가, 있어야 할 것이 있는가와 없어야 할 것이 없는가.
한쪽만 쓰면 반대 방향 변이가 그대로 지나간다. 다음부터는 단언을 추가할 때 "이것의 반대
방향은 무엇이고 그것은 누가 잡는가"를 같은 자리에서 답하고 넘어간다.

**헬퍼가 갈라지는 것은 기능 차이가 아니라 시간 차이 때문이다.** 세 벌로 갈린 `assertIndex`는
어느 것도 틀리지 않았다. 그저 만들어진 시점이 달랐을 뿐이다. 파일 간 복사로 관례를 퍼뜨리는
구조에서는 나중에 넓어진 헬퍼가 앞 파일로 돌아가지 않는다. **복사로 전파하기로 했다면
갱신도 복사로 전파해야 한다.**

---

## 20. 축이 DB 제약만 본다 — 엔티티 생성자를 뒤집어도 스키마 테스트가 전부 통과한다

**증상**

T-041(`credit_balances`·`reward_grants`·`shop_items`·`user_items`)을 QA가 검증하면서
**마이그레이션이 아니라 엔티티를** 변이시켰다. 두 줄을 고쳤는데 둘 다 조용했다.

```
UserItem 생성자의 this.isPlaced = false  →  true      → tests=19 failures=0
RewardGrant 생성자의 판정 근거 두 값을 서로 뒤바꿈     → tests=14 failures=0
```

첫 번째가 뒤집힌 스키마는 **구매 즉시 아이템이 방에 배치된다.** F-HPWCNJ는 "획득 직후는
보관함"이다(`docs/02-requirements-features.md:427-428`). 게다가 배치된 아이템은 슬롯당 1개라,
같은 슬롯의 두 번째 아이템은 **구매 자체가 부분 유니크 인덱스에 막혀 실패한다.** T-044·T-045에서
터지면 증상이 구매 API의 결함처럼 보인다.

두 번째가 뒤집힌 스키마는 T-043의 "왜 보상을 못 받았는지" 조회에서 **판정 근거 두 값이 통째로
거꾸로** 나온다. 두 값은 타입이 같아 예외가 어디서도 나지 않는다.

**진단**

17·18·19번과 같은 "조용한 실패" 계열로 보였지만 **축의 종류가 다르다.** 앞의 셋은 전부
*DB 제약을 보는 축*의 문제였다 — 인덱스 단언이 없었고(17번), `NOT NULL` 단언이 없었고(18번),
CHECK·타입 단언이 좁았다(19번). 이번에는 **DB 제약을 보는 축이 여섯 개 다 있었고 12종 변이를
전부 잡았다.** 비어 있던 것은 축의 *대상*이었다. 엔티티가 어느 축에도 들어 있지 않았다.

단서는 리포지토리 사용처를 세면서 나왔다.

```
RewardGrantRepository  사용처 0    ← 저장소 전체에서 유일
나머지 리포지토리 12개  사용처 1 이상
```

`RewardGrant`를 `new`로 만드는 코드가 저장소 어디에도 없었다. `reward_grants` 단언이 전부
`insertGrant`라는 **JDBC raw INSERT 헬퍼** 경로였기 때문이다. `user_items`도 같았다. 배치
관련 단언이 전부 `insertUserItem` 헬퍼 경로였고, `is_placed`를 **읽는** 단언이 하나도 없었다.
엔티티로 행을 만드는 `purchase()` 헬퍼는 있었지만 그것이 만드는 행은 전부 `item_type`이
달라(HOUSE·TOY) 부분 유니크에도 걸리지 않았다.

**원인**

**JDBC raw INSERT가 관례가 된 데서 왔다.** 그 관례 자체는 19번에서 정당하게 생겼다 —
`@Enumerated(STRING)` 때문에 JPA로는 CHECK에 걸릴 잘못된 값을 애초에 넣을 수 없어, CHECK
거부 케이스는 raw SQL로 쓸 수밖에 없었다. 문제는 그 뒤 **DB 제약을 보는 단언이 전부 그 경로로
쏠린 것이다.** raw INSERT는 엔티티를 지나가지 않으므로 생성자가 정하는 기본값과 인자 순서는
어느 축에도 들어가지 않는다.

`ddl-auto: validate`가 메워 줄 것처럼 보이지만 아니다. **컬럼의 존재와 타입만 본다.**
생성자 인자 순서, `@Enumerated(STRING)`이 실제로 문자열을 쓰는지, 첫 기간의 null이 정말
NULL로 내려가는지는 전부 그 바깥이다.

**앞선 여섯 Task는 우연히 충족했다.** 각 스키마 테스트가 `givenUser`·`givenAccount` 같은
픽스처를 **엔티티 리포지토리로** 만들어 왔기 때문에 엔티티 경로가 늘 한 번은 지나갔다.
T-041에서 처음 깨진 이유는, `reward_grants`의 판정 근거와 `user_items`의 배치 상태가
**픽스처에 필요 없는 값**이라 raw INSERT로만 채워졌기 때문이다. 우연한 커버리지가 끊긴 자리다
(19번의 `'APPLE'`·`'OVER_BUDGET'`이 우연히 덮여 있던 것과 같은 구조이며, 이번은 그 반대 방향이다).

**해결**

테스트 파일 둘에만 손댔다. 마이그레이션과 엔티티 로직은 그대로다.

1. `ShopSchemaTest.userItemCopiesItemTypeFromShopItem`에 한 줄. 이미 엔티티로 저장하고 있던
   테스트라 새 테스트를 만들지 않았다.
   ```java
   assertThat(saved.isPlaced())
           .as("구매 직후 아이템이 방에 배치돼 있다. 획득 직후는 보관함이어야 한다")
           .isFalse();
   ```
2. `RewardSchemaTest`에 `RewardGrantRepository`를 주입하고 엔티티 경로 테스트 둘을 추가했다.
   `saveAndFlush(new RewardGrant(...))`로 저장한 뒤 JDBC로 되읽어 `condition_type`이 문자열로
   남는지, 판정 근거 두 값이 넘긴 그대로인지, 첫 기간의 null이 NULL로 남는지를 본다.

**검증**

QA가 미검출로 보고한 두 변이를 고치기 **전후로** 한 번씩 돌렸다. 엔티티 파일은 매번 원복했다.

```
--- BEFORE (QA 보고) ---
E1 UserItem.isPlaced = false → true      : tests=19 failures=0
E2 RewardGrant 판정 근거 두 값 뒤바꾸기   : tests=14 failures=0

--- AFTER (같은 변이) ---
=== MUTATION E1 => gradle exit 1
tests=19 failures=1 errors=0
  FAILED: UserItem은 shop_items의 item_type을 그대로 복사한다
          [구매 직후 아이템이 방에 배치돼 있다. 획득 직후는 보관함이어야 한다]
          Expecting value to be false but was true

=== MUTATION E2 => gradle exit 1
tests=16 failures=2 errors=0
  FAILED: RewardGrant 엔티티로 저장한 값이 그대로 내려간다 — 판정 근거 두 컬럼까지
          [해당 기간 지출 합계가 넘긴 값과 다르다. 생성자 인자 순서가 뒤바뀌었다]
          expected: 360000L but was: 400000L
  FAILED: 첫 기간은 직전 기간 지출이 NULL로 내려간다 — 엔티티 경로
          [첫 기간인데 직전 기간 지출이 NULL이 아니다]
          expected: null but was: 360000L
```

원복 후 전체 빌드는 초록이다. 테스트가 119 → 121건으로 늘었다.

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 25s
TOTAL 121 tests, 0 failures, 0 errors
```

**배운 것**

**축의 폭만이 아니라 축의 경로도 봐야 한다.** 19번에서 "축이 있다는 것과 축이 넓다는 것은
다르다"를 배웠는데, 이번 것은 그 축이 **어느 코드 경로를 지나가는가**의 문제였다. 검증 축
여섯 개가 모두 있었고 DB 제약 변이 12종을 전부 잡았는데도, 그 축들이 하나같이 JDBC를 통해
DB에 직접 닿아 엔티티를 건너뛰었다. 단언을 추가할 때 "이 단언은 어떤 코드를 실행하는가"를
같이 물어야 한다.

**스키마 Task의 산출물은 마이그레이션만이 아니다.** 엔티티도 같은 커밋의 산출물인데 검증
대상에서 빠졌다. Task의 이름("스키마")이 검증 범위를 좁힌 셈이다.

**사용처 0인 리포지토리는 그 자체로 신호다.** `RewardGrantRepository`가 아무 데서도 쓰이지
않는다는 사실은 "아직 기능이 없어서"로 설명되지만, 동시에 **그 엔티티를 지나가는 테스트가
하나도 없다**는 뜻이기도 하다. 다음 스키마 Task부터는 엔티티마다 저장→되읽기 한 건을 넣고,
리포지토리 사용처를 세어 0이 없는지 확인한다.

**우연한 커버리지는 양쪽으로 작동한다.** 19번에서는 우연히 덮여 있던 값이 있었고, 이번에는
여섯 Task 동안 우연히 덮여 있던 경로가 끊겼다. 어느 쪽이든 "지금까지 통과했으니 덮여 있다"는
추론이 성립하지 않는다.

---

## 21. 동작 단언 셋으로는 유니크의 열 구성이 고정되지 않는다 — `user_id`를 더해도 셋이 그대로 통과한다

**증상**

T-031(`push_device_tokens`·`push_logs`)의 완료 기준 2는 `UNIQUE (budget_period_id,
threshold_type)`가 실제로 동작하는지를 **동작 세 방향**으로 요구했다. 세 단언을 다 넣고,
입력 명세가 지시한 대로 "유니크에 `user_id`를 더하는" 변이를 돌렸다.

```
CONSTRAINT uq_push_logs_period_threshold UNIQUE (budget_period_id, threshold_type)
  →                                      UNIQUE (user_id, budget_period_id, threshold_type)

tests=18 failures=1
  FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지
```

동작 단언 **셋은 전부 통과했다.** 재발송 거부도, 같은 기간의 두 임계값 공존도, 다음 달
재발송도 3열 유니크에서 그대로 성립한다. 잡은 것은 `pg_indexes`의 `indexdef`를 보는 **구조
단언 하나뿐**이었다.

**진단**

**여기서 오판을 했고, QA가 실험으로 반증했다.** 처음에는 이렇게 결론지었다.

> 3열 유니크는 2열보다 느슨하다. 두 유니크의 동작이 갈리려면 `budget_period_id`가 같은데
> `user_id`가 다른 두 행이 필요한데, `budget_periods` 행에 `user_id`가 이미 박혀 있어
> **그런 두 행을 만들 수 없다.** 따라서 두 제약은 동작이 구별되지 않고, 구조 단언이 유일한
> 방어선이다.

**틀렸다.** `push_logs`에는 `user_id`와 `budget_period_id`의 일치를 강제하는 **복합 FK도
CHECK도 없다**(`V202609212337__create_push.sql:23-26`). 두 FK가 각각 `users`와
`budget_periods`를 가리킬 뿐이다. "`budget_period_id`가 사용자를 함의한다"는 **애플리케이션
의미이지 스키마 보장이 아니다.** 남의 기간 id에 내 `user_id`를 붙인 행은 DB 수준에서 만들어진다.

QA가 그 행을 실제로 넣는 프로브를 붙이고 양방향으로 돌렸다.

```
(무변이 + 프로브)        => exit 0; tests=19 failures=0    ← 2열 유니크가 거부한다
(M4 3열 유니크 + 프로브) => exit 1; tests=19 failures=2
     FAILED: QA PROBE: 같은 기간을 다른 user_id로 두 번 기록하면 거부된다
     FAILED: 설계한 인덱스가 실제로 만들어져 있다
```

**2열과 3열은 동작으로 구별된다.** 맞는 서술은 "완료 기준 2가 열거한 **세 방향만으로는**
고정되지 않고, **네 번째 방향**(같은 기간을 다른 `user_id`로)을 추가하면 동작으로도 고정된다"이다.

걸려 있는 것도 가볍지 않다. 3열이면 `user_id`만 달리한 같은 기간·같은 임계값 기록이 두 번
남아, R-ENPLNB 결정 6의 "기간당 1회"가 "사용자별 기간당 1회"로 조용히 바뀐다.

한편 **열을 더하는 변이와 빼는 변이가 대칭이 아니라는 관찰 자체는 맞았다.** 열을 빼는 M3은
18건 중 8건을 깼고, 더하는 M4는 (프로브 전에는) 1건만 깼다. 느슨해지는 변이는 그 느슨함이
드러나는 **한 방향**을 정확히 찔러야 잡힌다.

**원인**

오판의 형태는 **"만들 수 없다"를 검증 없이 단정한 것**이다. 두 열 사이의 일관성을 강제하는
제약이 스키마에 있는지 확인하지 않고, 애플리케이션이 그렇게 쓸 것이라는 의미론을 DB 보장으로
넘겨짚었다. 스키마 테스트는 애플리케이션을 지나가지 않고 DB에 직접 닿으므로, 이 구분이 바로
결론을 갈랐다.

그 단정이 "도달 불가능한 상태가 있어 두 제약의 동작이 같다"로 이어졌고, 다시 "구조 단언이
유일한 방어선"이라는 결론으로 이어졌다. **전제 하나가 틀리면서 결론 둘이 함께 틀렸는데,
증상 단계의 관측(`failures=1`, 구조 단언만 깨짐)은 그대로 사실이었다.** 관측이 맞았기 때문에
설명이 틀렸다는 것을 스스로 눈치채지 못했다.

**해결**

`PushSchemaTest`에 네 번째 방향의 단언을 추가했다. 마이그레이션과 엔티티 로직은 그대로다.

```java
@DisplayName("같은 기간을 다른 user_id로 두 번 기록해도 거부된다 — 유니크에 user_id가 없다")
void sameThresholdWithDifferentUserIdIsRejected() {
    BudgetPeriod period = givenBudgetPeriod("push-uq-otheruser-1");
    User otherUser = givenUser("push-uq-otheruser-2");
    insertPushLog(period, "OVER_BUDGET");

    assertThatThrownBy(() -> /* otherUser.getId()로 같은 period·같은 임계값 INSERT */)
            .as("user_id만 다른 중복 기록이 저장됐다. 유니크에 user_id가 들어가 있다")
            .isInstanceOf(DataIntegrityViolationException.class)
            .rootCause()
            .hasMessageContaining("uq_push_logs_period_threshold");
}
```

구조 단언(`assertIndex`)도 그대로 둔다. 열 순서가 바뀌는 변이처럼 동작으로는 드러나지 않는
것이 여전히 있고, 두 단언이 같은 변이에 **각각** 걸리는 편이 원인을 빨리 좁혀 준다.

**검증**

단언 추가 **후** 같은 M4 변이를 다시 돌렸다. 전에는 구조 단언 하나만 깨졌고, 후에는 둘이 깨진다.

```
--- BEFORE (단언 추가 전) ---
### M4 유니크에 user_id 추가 (3열)       → 18 tests, 1 failed
    설계한 인덱스가 실제로 만들어져 있다   ← 동작 단언 셋은 전부 통과

--- AFTER (네 번째 방향 추가) ---
### FIX-A M4 재확인: 유니크에 user_id 추가 (3열)
19 tests completed, 2 failed
  FAILED: 같은 기간을 다른 user_id로 두 번 기록해도 거부된다 — 유니크에 user_id가 없다
  FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지
```

열을 빼는 변이가 여전히 동작으로 드러나는 것도 함께 확인했다(참고용, 단언 추가 전 수치다).

```
### M1 push_logs 유니크 통째 제거        → 18 tests, 2 failed
### M2 유니크를 (budget_period_id) 1열로 → 18 tests, 3 failed
    같은 기간에 STRONG_WARNING과 OVER_BUDGET은 각각 한 번씩 저장된다 (외 2)
### M3 유니크를 (threshold_type) 1열로   → 18 tests, 8 failed
    기간이 다르면 같은 임계값을 다시 보낼 수 있다 (외 7)
```

**배운 것**

**"그 상태는 만들 수 없다"는 스키마에서 확인하기 전까지 가설이다.** 두 열의 일관성을 강제하는
것은 복합 FK나 CHECK지, 각각 걸린 FK 둘이 아니다. 애플리케이션이 그렇게 쓸 것이라는 의미를
DB 보장과 섞는 순간 "동작으로 구별 불가"라는 잘못된 결론이 나온다. **도달 불가능성을 주장하려면
그 상태를 실제로 만들어 보고 거부당하는 것을 봐야 한다** — QA가 한 일이 정확히 그것이다.

**완료 기준이 열거한 방향이 전부라고 가정하지 않는다.** 완료 기준 2는 세 방향을 지시했고 세
방향 모두 넣었지만, 세 방향으로 덮이지 않는 변이가 남아 있었다. 변이를 돌려 "이 변이를 어느
단언이 잡는가"를 물었을 때 답이 구조 단언 하나였다면, 그것은 **동작 단언을 더 만들라는 신호**로
읽었어야 했다. 구조 단언이 잡았으니 됐다고 결론지은 것이 두 번째 실수다.

**관측이 맞아도 설명은 틀릴 수 있다.** `failures=1`이라는 수치는 처음부터 끝까지 사실이었다.
틀린 것은 그것을 설명하는 인과였고, 그 인과가 "더 할 일이 없다"는 판단을 낳았다. 설명이
"불가능하다"로 끝날 때는 한 번 더 의심하는 편이 낫다.

**"두 테이블이 비슷하니 제약도 맞추자"가 위험한 것은 여전히 맞다.** `reward_grants`에 맞춰
`user_id`를 더하면 이제 두 단언이 깨지지만, 그 단언들이 없었다면 규칙이 조용히 바뀌었을 것이다.
그래서 마이그레이션 주석과 테스트 주석 양쪽에 "`reward_grants`와 달리 2열"과 그 근거를 남겼다.

---

## 22. Spring Batch 6.0은 DataSource가 있어도 메타데이터를 DB에 쓰지 않는다 — Job은 `COMPLETED`로 끝난다

**증상**

T-040에서 Spring Batch 메타 테이블 마이그레이션을 넣고, 테이블이 만들어진 것만으로는
부족하다는 판단에 따라 Testcontainers로 Job 하나를 실제로 돌리는 테스트를 붙였다.
Job은 정상적으로 끝났는데 DB 조회에서 깨졌다.

```
Spring Batch 메타 테이블 위에서 Job이 실제로 실행된다 > Job이 COMPLETED로 끝나고 BATCH_JOB_EXECUTION에 기록이 남는다 FAILED
    org.springframework.dao.EmptyResultDataAccessException at BatchJobExecutionTest.java:87
```

`assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED)`는 **통과했다.**
바로 다음 줄의 `SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?`가
`Incorrect result size: expected 1, actual 0`으로 깨졌다. 예외도 경고 로그도 없었다.

**진단**

처음 의심한 것은 셋이었다.

1. `execution.getId()`가 null이라 `WHERE ... = NULL`이 0행을 돌려준 것 — `javap`로
   `org.springframework.batch.core.Entity.getId()`가 **primitive `long`**임을 확인해 기각했다.
2. `JdbcTemplate`이 다른 DataSource를 본 것 — 컨텍스트는 클래스당 하나고 `@ServiceConnection`이
   바꿔치기한 DataSource도 하나뿐이라 기각했다.
3. 테이블 이름 대소문자 — 따옴표 없는 식별자는 PostgreSQL이 소문자로 접으므로 DDL과 조회가
   같은 규칙을 탄다. 기각했다.

셋이 다 아니면 **애초에 DB에 쓰이지 않은 것**이다. 그러면 JobRepository가 JDBC가 아니라는
뜻이 된다. Boot 4의 `spring-boot-batch-4.0.8.jar`를 풀어 `BatchAutoConfiguration`을 보니
내부 설정 클래스가 `DefaultBatchConfiguration`을 상속하고 있었다.

```
class org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration$SpringBootBatchDefaultConfiguration
    extends org.springframework.batch.core.configuration.support.DefaultBatchConfiguration
```

`spring-batch-core-6.0.5-sources.jar`에서 그 상위 클래스를 직접 열었다.

```java
93:	public JobRepository jobRepository() {
94-		return new ResourcelessJobRepository();
95-	}
```

**원인**

Spring Batch 6.0에서 `DefaultBatchConfiguration#jobRepository()`의 기본값이
`ResourcelessJobRepository`로 바뀌었다. 5.x에서는 `@EnableBatchProcessing`과 Boot 자동설정이
컨텍스트의 `DataSource`를 보고 JDBC JobRepository를 만들어 줬다. 6.0은 JDBC 인프라를
**별도 애노테이션 `@EnableJdbcJobRepository`(6.0 신규)로 명시해야** 한다.

그래서 DataSource가 멀쩡히 있고, Flyway가 메타 테이블을 다 만들어 놓았고,
`spring.batch.jdbc.initialize-schema: never`도 의도대로 설정돼 있는데,
**배치 메타데이터는 메모리에만 남고 DB에는 한 줄도 쓰이지 않는다.** Job은 예외 없이
`COMPLETED`로 끝나므로 로그만 봐서는 구별할 수 없다.

이 상태로 넘어갔다면 T-042(예산 기간 전환)·T-043(크레딧 지급)에서 재시작·중복 실행 방지가
전부 무력화된다. 메타데이터가 프로세스와 함께 사라지므로 "이미 돌았는지"를 아무도 모른다.
테이블 6개는 영원히 비어 있는 채로 남는다.

**해결**

`src/main/java/com/petgyebu/telo/config/BatchConfig.java`를 추가했다.

```java
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository
public class BatchConfig {
}
```

`@EnableJdbcJobRepository`는 `@EnableBatchProcessing`이 붙은 설정 클래스에 함께 써야 한다
(애노테이션 javadoc 명시). 기본값대로 `dataSource`·`transactionManager`·`jdbcTemplate` 빈을
쓰므로 다른 설정은 없다. 이 둘을 선언하면 Boot의 기본 배치 설정
(`@ConditionalOnMissingBean(annotation = EnableBatchProcessing.class)`)은 물러난다.

**검증**

양방향으로 확인했다. 설정 추가 **전후**로 같은 테스트를 돌렸다.

```
--- BEFORE (BatchConfig 없음) ---
4 tests completed, 1 failed
  FAILED: Job이 COMPLETED로 끝나고 BATCH_JOB_EXECUTION에 기록이 남는다
          EmptyResultDataAccessException: Incorrect result size: expected 1, actual 0
          ← Job 상태 단언은 통과한 뒤 DB 조회에서 깨졌다

--- AFTER (@EnableBatchProcessing + @EnableJdbcJobRepository) ---
BUILD SUCCESSFUL in 11s   (BatchSchemaSourceTest, BatchJobExecutionTest,
                           PostgresMigrationTest, TeloApplicationTests)
```

DB에 실제로 쓰였는지는 인메모리 객체가 아니라 세 질의로 못 박았다 —
`BATCH_JOB_EXECUTION.STATUS = 'COMPLETED'`, `BATCH_JOB_INSTANCE.JOB_NAME`,
`BATCH_STEP_EXECUTION`의 완료 스텝 1건. 마지막 것이 `BATCH_STEP_EXECUTION_SEQ`까지
살아 있다는 증거를 겸한다.

**배운 것**

**"DataSource가 있으니 JDBC로 갈 것"은 5.x의 기억이다.** 메이저 버전 전환에서 바뀌는 것은
좌표와 패키지만이 아니라 **자동설정의 기본값**이고, 기본값이 바뀌면 에러가 아니라 침묵이 나온다.

그리고 이번 Task의 완료 기준이 "테이블 6개가 만들어진다"에서 멈췄다면 **이 문제를 통과시켰을
것이다.** 테이블은 정확히 만들어져 있었다. `PostgresMigrationTest`도, 원본 일치 테스트도
전부 초록불이었다. 잡은 것은 "Job을 실제로 돌리고 그 기록이 DB에 남는지 본다"는 단 하나의
단언이다. **산출물의 존재가 아니라 산출물이 쓰이는 것을 확인해야 한다.**

**덧 — 같은 교훈이 같은 커밋 안에서 한 번 더 필요했다 (QA 지적)**

위 교훈을 적어 놓고도, 이 커밋의 주석 두 곳에 **Boot 4.0에 존재하지 않는 속성**을 근거로 적었다.

```
V202609221408__create_spring_batch_metadata.sql:14
BatchConfig.java:23
  → "spring.batch.jdbc.initialize-schema: never 라서 Flyway가 만든다"
```

QA가 확인한 사실은 이렇다.

```
$ javap -p .../boot/batch/autoconfigure/BatchProperties.class
  private final org.springframework.boot.batch.autoconfigure.BatchProperties$Job job;   ← job 하나뿐
$ unzip -p spring-boot-batch-4.0.8.jar META-INF/spring-configuration-metadata.json
  ['spring.batch.job.enabled', 'spring.batch.job.name']                                 ← 이게 전부
$ spring-boot*-4.0.8.jar 전체 문자열에서 "batch.jdbc.initialize-schema" 검색 → 0건
```

재현해 보니 그대로였다. Boot 3.x의 `spring.batch.jdbc.initialize-schema`와 그것을 읽던 초기화기는
**4.0에 없다.** 동작상 피해는 없다 — Boot 4는 배치 스키마를 아예 자동 생성하지 않으므로
Flyway 소유가 그대로 유지된다. **틀린 것은 결과가 아니라 근거다.** 없는 속성을 방어선으로 적어
두면 다음 사람이 "이 설정이 막아 주니 안전하다"고 믿는다. 진짜 근거는 "Boot 4.0에는 배치 스키마
자동 생성기가 없다"이다.

죽은 속성은 이미 **네 군데**로 번져 있었다. `application.yaml:28`과 `docs/05-infra-stack.md:137`은
Sprint 0에서 들어온 것이고, 이번 커밋의 주석 둘이 그 서술을 그대로 옮겨 적으면서 둘이 더 늘었다.
T-040에서 새로 생긴 주석 두 곳만 정정했다. 앞의 둘은 이 Task의 소산이 아니라 별도 Task로 등록됐다.

**이 항목의 교훈이 자기 자신에게 적용되지 않았다.** 자동설정 기본값은 `javap`와
`spring-configuration-metadata.json`으로 확인했으면서, 설정 **속성**의 존재는 입력 문서에 적혀
있다는 이유로 확인하지 않고 옮겼다. 같은 확인 방법이 둘 다에 쓸 수 있었다.
"5.x의 기억으로 기본값을 넘겨짚지 마라"는 **속성 이름에도 똑같이 적용된다.**
"문서에 적혀 있다"는 그 속성이 존재한다는 증거가 아니다.

---

## 되짚어 보기

### 조용한 실패가 가장 많았다

24건 중 12건(3·4·9·10·11·17·18·19·20·22·23·24번)이 **빌드도 기동도 성공하는데 기능만 비어 있는** 유형이었다.

| 사례 | 겉보기 | 실제 |
|---|---|---|
| QueryDSL classifier | 빌드 exit 0 | Q클래스 0개 |
| Flyway 자동설정 모듈 | 기동 성공, 로그 0줄 | DB 테이블 0개 |
| CI 자격증명 없음 | 잡 성공 | 배포·검증 스텝 전부 스킵 |
| local 프로필 테스트 | 테스트 통과 | 운영 스키마 경로 미검증 |
| out-of-order 마이그레이션 | 새 DB에서 통과 | 운영 DB에서만 실패 |
| 인덱스 누락 | 빌드 통과, 스키마 테스트 8건 전부 통과 | 인덱스 0개, 조회가 풀스캔 |
| `NOT NULL` 누락 | 빌드 통과, `validate` 통과, 스키마 테스트 9건 전부 통과 | 열이 null을 받는다 |
| CHECK 값 제거·통째 삭제, `VARCHAR` 길이 변경 | 빌드 통과, 스키마 테스트 76건 전부 통과 | 애플 로그인·계좌 해제·기간 종료가 막히고, CHECK가 없어도 아무도 모른다 |
| 엔티티 생성자 변이 | 빌드 통과, DB 제약 변이 12종을 잡는 스키마 테스트 33건 전부 통과 | 구매 즉시 아이템이 배치되고, 보상 판정 근거 두 값이 거꾸로 저장된다 |
| Spring Batch 6.0 기본 JobRepository | Job이 `COMPLETED`로 종료, 예외·경고 0줄 | 메타 테이블 6개가 영원히 비어 있고 재시작·중복 실행 방지가 무력화된다 |
| FK 자식 컬럼 인덱스 누락 | 빌드 통과, `validate` 통과, 스키마 테스트 142건 전부 통과 | 탈퇴·계좌 해제가 자식 테이블을 통째로 훑는다. 거래 2000건 삭제에 3초, 테이블이 커지면 선형으로 늘어난다 |
| 비정규화 소유자 어긋남 | INSERT 성공, 예외·경고 0줄 | 다른 사용자의 예산 사용률에 남의 거래가 합산되고, 금액 문의를 받기 전까지 드러나지 않는다 |

공통점이 있다. **성공 신호가 있어서 더 위험하다.** 에러가 나면 고치게 되지만 이들은 "잘 되고 있다"는 잘못된 확신을 준다.

이 유형을 다루는 방법은 하나뿐이었다. **기능이 실제로 수행됐다는 증거를 직접 확인하는 것이다.** Q클래스 파일의 존재, `flyway_schema_history` 테이블의 존재처럼 결과물을 눈으로 확인해야 한다. "에러가 없다"는 증거가 되지 않는다.

### 통과 확인만으로는 부족하다 — 양방향 검증

여러 건에서 **의도적으로 깨뜨려 보는 검증**을 했다.

- QueryDSL: classifier를 빼면 정말 Q클래스가 안 생기는지
- Flyway: `spring-boot-flyway`를 빼면 정말 조용히 실패하는지
- 마이그레이션 테스트: 마이그레이션 없이 엔티티만 추가하면 정말 실패하는지
- CI 가드: 낮은 버전을 넣으면 정말 빌드가 깨지는지
- 인덱스 단언: 인덱스를 지우거나 대상 열을 바꾸면 정말 테스트가 깨지는지
- `NOT NULL` 단언: 제약을 지우면, 그리고 **반대로** null 허용 열에 제약을 붙이면 정말 테스트가 깨지는지
- CHECK 허용 케이스·`VARCHAR` 길이 단언: 같은 변이 7종을 소급 **전후**로 한 번씩 돌려, 전에는 조용하고 후에는 깨지는지
- CHECK 거부 케이스: 제약을 **통째로 삭제**하면 깨지는지, 그리고 값 하나만 빼는 변이와 **서로 다른 테스트**에 걸리는지
- 부분 유니크 인덱스: 조건을 빼는 변이와 유니크를 빼는 변이가 **서로 다른 테스트**에 걸리는지
- 엔티티 경로 단언: QA가 미검출로 보고한 엔티티 변이 둘을 단언 추가 **전후**로 한 번씩 돌려, 전에는 `failures=0`이고 후에는 깨지는지
- FK 자식 컬럼 인덱스: 추가한 인덱스 넷을 각각 지우면 해당 테스트가 하나씩 깨지는지. 그리고 **인덱스 유무만 바꿔 같은 삭제를 실측**해 3,005ms ↔ 8ms를 확인
- 복합 FK: 세 테이블 각각에서 **정상 조합은 통과하고 어긋난 조합만 거부**되는지. 복합 FK 3종을 제거하면 3건이 전부 깨지는지. 그리고 **도입 비용을 실측**해 2.4%임을 확인
- 푸시 스키마: 변이 14종(유니크 5, CHECK 1, FK 3, `NOT NULL` 1, 인덱스 1, 엔티티 3)을 하나씩 넣고 원복해, 각각 **어느 테스트가** 깨지는지까지 확인. 유니크에 열을 *더하는* 변이만 동작 단언 셋을 통과하고 구조 단언에만 걸렸는데, QA가 **네 번째 방향의 프로브**(같은 기간을 다른 `user_id`로)를 넣어 동작으로도 구별된다는 것을 보였다. 단언 추가 전후로 같은 변이를 돌려 `failures=1 → 2`를 확인했다(21번)
- Spring Batch 메타 스키마: 컬럼 이름 변이 둘(`JOB_NAME`→`JOB_TITLE`, `EXIT_MESSAGE`→`EXIT_MSG`)과 시퀀스 삭제 변이 하나를 넣고 원복해, **원본 일치 테스트와 Job 실행 테스트가 각각 어디서** 깨지는지 확인. `PostgresMigrationTest`는 DDL이 통과하는 변이를 전혀 잡지 못한다(22번)
- JDBC JobRepository 설정: `BatchConfig` 추가 **전후**로 같은 테스트를 돌려, 전에는 Job이 `COMPLETED`인데 `BATCH_JOB_EXECUTION`이 0행이고 후에는 통과하는지(22번)

통과만 확인하면 "원래부터 통과했을" 가능성을 배제할 수 없다. **막으려던 것이 실제로 막히는지 확인해야 그 방어가 작동한다고 말할 수 있다.**

### 메이저 버전 전환의 비용은 좌표에서 나온다

Java 25 + Spring Boot 4.0 조합에서 좌표·패키지·기본값 문제가 다섯 번 나왔다(1·2·4·5·22번). 코드 문법이 아니라 **"어떤 아티팩트를 어떤 이름으로 가져오는가"**, 그리고 **"자동설정이 무엇을 기본값으로 주는가"**에서 깨졌다.

관례적인 좌표를 그대로 쓰면 실패하거나, 더 나쁘게는 조용히 동작하지 않는다. 의존성을 추가할 때마다 Maven Central에서 실제 좌표를 확인하는 편이 결과적으로 빨랐다.

### 문서는 강제되지 않으면 지켜지지 않는다

1번은 인프라 문서에 함정이 이미 적혀 있었는데도 발생했다. 11번은 "병합 전 재타임스탬프"를 문서에만 적으면 잊어버릴 것이 분명했다.

그래서 이 프로젝트는 규칙을 가능한 한 **검사로 바꿨다.**

| 규칙 | 강제 수단 |
|---|---|
| 마이그레이션 순서 | CI 정적 검사 |
| 엔티티와 스키마 일치 | Testcontainers + `ddl-auto: validate` |
| 푸시 기간당 1회 | DB 유니크 제약 |
| 보상 중복 지급 금지 | DB 유니크 제약 |
| 기준 타임존 | 테스트를 운영과 다른 존으로 실행 |

DB 제약으로 막을 수 있는 것은 DB에서 막는 것이 가장 확실하다. 애플리케이션 검사만 있으면 동시 요청에서 뚫린다.

### 리뷰가 잡아낸 것들

혼자 작성하고 스스로 검증했다면 놓쳤을 문제가 있었다.

- 자동 생성 비밀번호 노출(6번) — 검증 단계에서 발견
- 스크립트 인젝션(12번) — 자동 보안 리뷰가 발견
- 타임존 미강제(13번) — 스키마 검증 중 부수적으로 발견

특히 6번은 **지적받은 대로 고친 뒤 재확인하지 않았다면 절반만 해결된 채로 넘어갔을** 사례다. 지적을 반영하는 것과 문제가 사라졌는지 확인하는 것은 다른 일이다.

---

## 23. FK 자식 컬럼에 인덱스가 없어 부모 삭제가 자식 테이블 전체를 훑는다

**증상**

스키마 Task 일곱 개가 전부 끝난 뒤 전체 검토에서 발견했다. 증상이 아직 나타나지 않은 상태다 — 데이터가 0건이라 전체 스캔이 즉시 끝난다. 빌드·`ddl-auto: validate`·스키마 테스트 142건이 전부 통과한다.

**진단**

테이블별 검토가 아니라 **가로지르는 축**으로 훑다가 나왔다. 마이그레이션에서 FK 13개를 뽑아 각 자식 컬럼에 선행 인덱스가 있는지 기계적으로 대조했다.

PostgreSQL은 FK의 **참조하는 쪽(자식) 컬럼에 인덱스를 자동으로 만들지 않는다.** PK 쪽에만 생긴다. 부모 행을 지울 때 "이 행을 가리키는 자식이 있나"를 확인해야 하는데, 인덱스가 없으면 자식 테이블 전체를 훑는다.

복합 인덱스가 덮는지 판단할 때 **선행 열**을 봐야 한다는 것이 함정이었다. `reward_grants`의 `UNIQUE (user_id, budget_period_id, condition_type)`는 `user_id`는 덮지만 `budget_period_id`는 덮지 않는다. `push_logs`의 `UNIQUE (budget_period_id, threshold_type)`는 그 반대다. 둘 다 "유니크가 있으니 덮이겠지"로 넘어갈 수 있는 모양이었다.

**원인**

`docs/09-db-design.md`의 인덱스 표가 이 넷을 빠뜨렸다. 우리는 문서를 정확히 따랐고 문서가 불완전했다. 설계 표의 인덱스는 전부 **조회 성능**을 위한 것이었고, **삭제 경로**는 아무도 보지 않았다.

기존 인덱스 단언(축 3)도 잡지 못한다. 그 축은 "설계에 있는 인덱스가 실제로 있는가"를 보는데, 이건 설계에 없었다.

**해결**

네 곳에 인덱스를 추가했다.

| FK | 언제 스캔되나 |
|---|---|
| `transactions.linked_refund_transaction_id` | 거래 삭제마다 (자기참조) |
| `sync_attempts.user_id` | 탈퇴 시 |
| `push_logs.user_id` | 탈퇴 시 |
| `reward_grants.budget_period_id` | 예산 기간 삭제마다 |

참조 테이블(`categories`·`shop_items`)을 가리키는 FK 셋은 넣지 않았다. 그 부모는 삭제되지 않으며 인덱스에는 쓰기 비용이 있다.

`docs/09-db-design.md` 0장에 원칙을 추가했다. 개별 표만 고치면 다음 테이블에서 또 빠진다.

**검증**

PostgreSQL 16 컨테이너에서 실측했다. 삭제 대상은 세 경우 모두 거래 2000건으로 동일하고 배경 데이터와 인덱스만 바꿨다.

| 배경 데이터 | 인덱스 | 소요 |
|---|---|---|
| 5만 건 | 없음 | 3,005 ms |
| 5만 건 | 있음 | **8 ms** |
| 20만 건 | 없음 | 12,096 ms |

363배 차이다. 그리고 배경이 4배가 되니 시간도 4배 — 지우는 행 수가 같은데 테이블이 커질수록 느려진다는 뜻이다.

양방향으로도 확인했다. 추가한 인덱스 네 개를 각각 지우니 해당 스키마 테스트가 정확히 하나씩 FAILED를 냈다(`transactions` 1건, 나머지 셋을 한꺼번에 지웠을 때 3건).

**배운 것**

**인덱스 설계는 조회만 보면 절반이다.** 설계 표의 여섯 인덱스는 전부 "어떻게 찾을 것인가"였고 "어떻게 지울 것인가"는 없었다. CASCADE가 걸린 FK는 그 자체가 삭제 경로다.

**개별 Task 검토로는 안 잡힌다.** 각 스키마 Task는 자기 테이블의 설계 표를 정확히 구현했고 QA도 그 표와 대조했다. 문서가 빠뜨린 것은 문서를 기준으로 보는 한 드러나지 않는다. 일곱 Task가 끝난 뒤 **가로지르는 축으로 다시 훑은 것**이 유일한 발견 경로였다.

17번(인덱스를 지워도 통과한다)과 같은 계열이되 한 겹 더 깊다. 17번은 "설계에 있는 인덱스가 사라져도 모른다"였고, 이번은 **"설계에 없는 인덱스가 필요한 것을 모른다"**다. 단언은 설계를 기준으로 하므로 설계가 틀리면 단언도 함께 틀린다.

---

## 24. 비정규화한 `user_id`가 부모의 소유자와 어긋나도 DB가 막지 않는다

**증상**

증상이 나타난 적은 없다. 사용자가 `Transaction.java`의 주석을 읽다가 던진 질문에서 나왔다.

```java
 * <p>그 대가로 {@code account}의 주인과 {@code user}가 어긋날 수 있다. DB는 이것을 막지 않는다.
 * (…) 문서가 정한 방어선은 "거래 저장은 반드시 계좌 조회를 거친 경로로만 수행한다"이며,
 * 그 경로는 T-015에서 만든다.
```

> 문서가 정한 방어선은 신경 쓰지 않는다면 이건 괜찮은 설계인가?

**진단**

두 가지를 나눠 봤다.

**비정규화 자체는 옳았다.** 다만 문서가 댄 이유가 약했다. "조인이 하나 붙는다"는 근거였는데, 사용자당 계좌가 1~5개라 중첩 루프 서너 번이고 비정규화할 만한 이유가 못 된다. 진짜 값은 **정렬**이다 — 거래 목록은 전체 계좌를 통합해 최신순으로 보여주는 앱 메인 화면이고, `user_id`가 있으면 인덱스 `(user_id, transacted_at DESC)` 하나를 위에서부터 읽는다. 없으면 계좌 수만큼 스캔해 병합해야 하고 페이지네이션이 깊어질수록 나빠진다.

**강제를 안 한 것이 약했다.** 주석은 "설계 문서에 없는 제약이라 넣지 않았다"고 적었는데 이는 기술적 근거가 아니라 절차적 근거다. 그 제약은 T-014 입력 명세의 "명세에 없는 것을 임의로 추가하지 마라"에서 왔고, 보수적이었다.

세 테이블이 같은 구조였다 — `transactions`(부모 `accounts`), `reward_grants`·`push_logs`(부모 `budget_periods`).

**원인**

**코드 규율이 약한 방어선인 이유는 어긋나도 예외도 경고도 나지 않기 때문이다.**

```
영희의 이번 달 예산 사용률 계산
  → user_id = 영희인 거래를 전부 더한다
  → 철수가 쓴 금액이 딸려 들어온다
  → 영희 화면에 예산 초과 경고, 푸시 알림까지 발송
```

로그에도 안 남고 에러도 없다. 영희가 금액을 문의할 때까지 아무도 모른다. 금융 데이터라 더 나쁘다.

그리고 규율은 **앞으로 이 테이블에 쓰는 모든 코드**가 알아야 한다 — T-015 수집, T-016 중복 판정, 배치 재처리, 장애 대응 중 급하게 돌리는 보정 SQL. 하나라도 모르면 조용히 틀린다.

**같은 구조에서 이미 한 번 드러났다.** T-031에서 `push_logs`의 유니크를 2열에서 3열로 바꿔도 동작 단언이 전부 통과했는데, 같은 기간을 다른 `user_id`로 기록하는 행을 만들 수 있었기 때문이다(21번). 그때 "그런 행은 만들 수 없다"고 넘겨짚었다가 QA가 실험으로 반증했다. **그 관측이 이 항목의 예고편이었는데 당시에는 유니크 열 구성 문제로만 읽었다.**

**해결**

부모에 `UNIQUE (id, user_id)`를 걸어 복합 FK의 참조 대상을 만들고, 자식이 그 쌍을 통째로 참조하게 했다.

| 자식 | 복합 FK |
|---|---|
| `transactions` | `(account_id, user_id)` → `accounts (id, user_id)` |
| `reward_grants` | `(budget_period_id, user_id)` → `budget_periods (id, user_id)` |
| `push_logs` | `(budget_period_id, user_id)` → `budget_periods (id, user_id)` |

전에는 DB가 "이 계좌 있나" "이 사용자 있나"를 **따로** 봤다. 이제 "(계좌, 사용자) 이 짝이 있나"를 본다.

**검증**

**도입 비용을 먼저 쟀다.** "대량 INSERT에서 검사가 행마다 돈다"를 우려로 꺼냈으므로 말로 끝내지 않았다. PostgreSQL 16, 5만 건 INSERT(90일치 최초 수집 상당).

| 구조 | 소요 |
|---|---|
| 단일 FK 둘 | 242.9 ms |
| 복합 FK | 248.1 ms |

2.4%다. 자식에 추가되는 열은 없다 — 두 열 모두 이미 있었고 DB에 "이 둘을 같이 보라"고 말한 것뿐이다.

`OwnerIntegrityTest` 3건으로 세 테이블 각각에서 **정상 조합은 통과하고 어긋난 조합만 거부**되는지 본다. 거부만 보면 제약을 과도하게 좁혀도 통과한다(T-023 교훈).

```
ERROR: violates foreign key constraint "fk_transactions_account_owner"
DETAIL: Key (account_id, user_id)=(1, 2) is not present in table "accounts".
```

양방향 — 복합 FK 3종을 제거하는 변이:

```
3 tests completed, 3 failed
```

**배운 것**

**"조심해서 쓰자"는 방어선이 아니다.** 규율은 지키는 사람이 규칙을 아는 동안만 유효하고, 어겼을 때 아무 신호도 없으면 어긴 줄도 모른다. 같은 것을 구조로 막을 수 있고 비용이 2.4%라면 구조를 고른다.

**비정규화의 근거가 약하게 적혀 있으면 그 자체가 신호다.** "조인 하나를 없앤다"는 이유로는 이 설계를 정당화할 수 없었고, 근거를 다시 따져보니 진짜 이유(정렬)가 따로 있었다. 문서의 근거가 실제보다 약하면 **결정이 충분히 검토되지 않았을 가능성**이 있다.

**같은 관측을 다른 각도에서 다시 읽어야 할 때가 있다.** 21번에서 "같은 기간을 다른 `user_id`로 기록할 수 있다"를 이미 관측했지만 유니크 열 구성 문제로만 해석했다. 그것이 정합성 구멍이라는 것은 두 달 뒤가 아니라 그때 알 수 있었다.

23번과 같은 계열이다. 둘 다 **설계 문서가 정한 것을 정확히 구현했는데 설계가 불완전했던 경우**다. 23번은 필요한 인덱스가 표에 없었고, 이번은 필요한 제약이 "명세에 없다"는 이유로 빠졌다.

---

### 덧 — 23번의 설명이 CASCADE에 대해 틀렸다 (2026-09-23 정정)

23번 본문과 PR #24가 "인덱스가 없으면 자식 테이블을 훑고 **삭제되는 행마다 반복된다**"고 적었다. **`NO ACTION`에만 맞고 `CASCADE`에는 틀렸다.**

실측으로 확인했다. 삭제되는 자식 행 수를 같게 두고 부모 행 수만 바꿨다.

| 삭제 | 자식 행 | 소요 |
|---|---|---|
| 부모 1행 | 3,000 | 8.6 ms |
| 부모 99행 | 2,970 | **601.9 ms** |

**CASCADE는 부모 한 행마다** `DELETE FROM 자식 WHERE fk = ?`를 한 번씩 돌린다. 자식 행 수가 아니라 부모 행 수에 비례한다.

| 규칙 | 스캔 횟수 |
|---|---|
| `NO ACTION` · `RESTRICT` | 삭제되는 **자식** 행마다 |
| `CASCADE` · `SET NULL` | 삭제되는 **부모** 행마다 |

23번이 잰 363배(3,005ms ↔ 8ms)는 자기참조 `NO ACTION` FK의 것이라 그 수치는 맞다. **관측은 맞았고 그 설명을 CASCADE 셋에 잘못 일반화했다.** 21번이 적은 "관측이 맞아도 설명은 틀릴 수 있다"가 같은 문서 안에서 한 번 더 나왔다.

추가한 인덱스 넷은 전부 유효하다. 근거만 다르다.

| 인덱스 | 규칙 | 실제 비용 |
|---|---|---|
| `transactions.linked_refund_transaction_id` | NO ACTION | 삭제되는 거래마다 (363배) |
| `sync_attempts.user_id` | CASCADE | 탈퇴 1회당 큰 테이블 1회 풀스캔 |
| `push_logs.user_id` | CASCADE | 〃 |
| `reward_grants.budget_period_id` | CASCADE | 탈퇴 시 예산 기간 수(연 12개)만큼 — 셋 중 최악 |

**마이그레이션 파일의 주석은 고치지 않았다.** `V202609221500__add_missing_fk_indexes.sql`은 이미 병합됐고, 같은 날 추가한 불변성 가드가 병합된 마이그레이션의 수정을 막는다. 파일을 고치면 적용된 DB에서 checksum 불일치로 기동이 실패한다. 정정은 이 항목과 `docs/09-db-design.md` 0장에 둔다.

---

## 25. 인증 빈 하나를 추가했더니 무관해 보이는 스키마 테스트 25개가 한꺼번에 깨졌다

**증상**

T-003a(액세스 토큰)에서 `TokenService`를 추가하고 해당 테스트 5개가 전부 통과한 뒤 `./gradlew build`를 돌렸더니, 토큰과 아무 상관 없는 테스트들이 무더기로 깨졌다.

```
accounts 스키마 제약 검증 > 컬럼 타입이 설계와 일치한다 ... FAILED
budget_periods·status_thresholds 스키마 제약 검증 > ... FAILED
Spring Batch 메타 테이블 위에서 Job이 실제로 실행된다 > ... FAILED
```

**진단**

실패 목록이 특정 도메인에 몰려 있지 않고 **Testcontainers를 쓰는 테스트 전부**였다. 스키마를 건드리지 않았으니 테스트 본문의 문제가 아니라 컨텍스트 기동 문제라고 보고 JUnit XML의 스택트레이스를 열었다.

```
Failed to load ApplicationContext for [... BudgetSchemaTest ..., activeProfiles = []]
Caused by: BeanCreationException: Error creating bean with name 'tokenService'
Caused by: PlaceholderResolutionException: Could not resolve placeholder
  'TELO_ACCESS_TOKEN_SECRET' in value "${TELO_ACCESS_TOKEN_SECRET}"
  <-- "${telo.auth.access-token-secret}"
```

`activeProfiles = []`가 결정적이었다. 토큰 테스트는 `@ActiveProfiles("local")`이라 `application-local.yaml`의 더미 키를 읽어 통과했지만, 스키마 테스트들은 **프로필을 지정하지 않아 base `application.yaml`만 읽는다.** 거기에는 기본값 없는 `${TELO_ACCESS_TOKEN_SECRET}`만 있었다.

**원인**

서명 키에 기본값을 일부러 주지 않았다. 기본값을 두면 운영에서 주입을 빠뜨렸을 때 **저장소에 공개된 키로 토큰을 찍어내며 조용히 정상 동작**하기 때문이다. 그 판단 자체는 유지할 값이었지만, 키를 더미 값으로 채워 둔 곳이 `local` 프로필뿐이라 프로필 없이 도는 테스트에는 값이 닿지 않았다.

`TokenService`는 `@Service`라 컨텍스트 기동 시 항상 만들어진다. **토큰을 전혀 쓰지 않는 테스트도 이 빈의 생성 실패에 함께 끌려간다.**

**해결**

`build.gradle`의 `test` 블록에서 **운영과 같은 경로인 환경변수**로 더미 키를 넣었다. 테스트용 프로퍼티 파일을 새로 두는 방법도 있었지만, `src/test/resources/application.yaml`은 클래스패스에서 main의 같은 이름 파일을 통째로 가려 base 설정이 테스트에서만 달라지는 함정을 만든다. 환경변수는 실제 주입 경로와 같아서 그 경로가 동작한다는 것까지 겸사겸사 확인된다.

```gradle
environment 'TELO_ACCESS_TOKEN_SECRET', 'test-only-dummy-hs256-signing-key-32b+'
```

base `application.yaml`은 기본값 없이 그대로 뒀다. 기동 실패로 막는 성질을 잃지 않는 것이 이 항목의 핵심이다.

**검증**

- 고치기 전: `./gradlew build` → 25 failed (컨텍스트 기동 실패)
- 고친 뒤: `./gradlew build` → 152 tests, 0 failed
- 되돌리면: `environment` 줄을 지우면 같은 25개가 같은 `PlaceholderResolutionException`으로 다시 깨진다

**배운 것**

**기동 시점에 실패하도록 만든 설정은 기동하는 모든 경로를 함께 봐야 한다.** 새 필수 설정을 추가할 때 실제로 늘어나는 것은 "그 기능을 쓰는 경로"가 아니라 "컨텍스트를 띄우는 모든 경로"다.

그리고 **기능 테스트만 돌려 보고 끝내면 이런 것이 남는다.** 토큰 테스트 5개는 `local` 프로필이라 처음부터 끝까지 초록이었다. 전체 빌드를 돌리지 않았다면 이 상태로 커밋했을 것이다.
