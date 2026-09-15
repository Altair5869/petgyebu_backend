# Sprint 0 QA 리포트 (인프라 골격)

- 작성: 2026-09-15
- 검증 대상: 브랜치 `chore/sprint0-infra-skeleton`, 커밋 `0725721`
- 입력: `_workspace/sprint0_00_input.md`, `_workspace/sprint0_backend_summary.md`, `docs/05-infra-stack.md`, `docs/06-sprint-plan.md`
- 검증 방식: backend-engineer의 요약을 근거로 삼지 않고 모든 명령을 직접 재실행했다.

## 종합

성공 기준 6개 중 5개가 실제로 통과했고, 6번(배포 플래그)은 파일에 명시돼 있다는 점까지만 확인됐다.
backend-engineer가 보고한 사실 관계 중 실제와 다른 것은 하나도 없었다. 버전 조사, QueryDSL classifier
결론, `spring-boot-starter-aop` 부재, H2 배제 구조는 모두 독립적으로 재현했다.

다만 요약에서 다루지 않았거나 과소평가된 문제가 네 건 있다. 가장 무거운 것은 `SecurityConfig`가
HTTP Basic을 명시적으로 켜 두어 Spring Boot 자동 생성 계정이 실제로 인증에 성공한다는 점이고,
그 다음이 Dockerfile의 `chown` 한 줄이 145MB짜리 레이어를 통째로 복제하고 있다는 점이다.

| 구분 | 건수 |
|---|---|
| PASS | 5 |
| FIX | 3 |
| REDO | 0 |
| 미검증 | 5 |

---

## 1. 성공 기준별 판정

### 기준 1 — `./gradlew build` 통과, 테스트 2건 성공

**판정: PASS**

```
$ ./gradlew clean build --console=plain
BUILD SUCCESSFUL in 6s
8 actionable tasks: 8 executed

$ build/test-results/test/TEST-com.petgyebu.telo.TeloApplicationTests.xml
tests="1" skipped="0" failures="0" errors="0"
$ build/test-results/test/TEST-com.petgyebu.telo.codef.EasyCodefUtilJdk25Test.xml
tests="1" skipped="0" failures="0" errors="0"
```

주장 그대로다.

### 기준 2 — 외부 인프라 없이 로컬 기동, 헬스체크 200

**판정: PASS**

```
$ SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
Started TeloApplication in 3.113 seconds

$ curl -s -o /tmp/h.json -w 'HTTP %{http_code}\n' http://localhost:8080/actuator/health
HTTP 200
{"groups":["liveness","readiness"],"status":"UP"}
```

인증 헤더 없이 200과 `UP`을 받았다. `show-details` 기본값이 `never`라 DB·Redis 상세가 노출되지
않는 점도 확인했다.

알아 둘 것이 하나 있다. 빌드된 jar를 `local` 프로필로 직접 실행하면 기동하지 않는다.

```
$ SPRING_PROFILES_ACTIVE=local java -jar build/libs/telo-0.0.1-SNAPSHOT.jar
Caused by: java.lang.IllegalStateException: Cannot load driver class: org.h2.Driver
```

H2가 `developmentOnly`라 jar에 없기 때문이고, 이는 의도된 설계다. 결함이 아니라 "로컬 기동 검증은
`bootRun` 경로에서만 성립한다"는 사실을 기록해 둔다. 오히려 아래 B 항목의 안전성 근거가 된다.

### 기준 3 — QueryDSL Q클래스 생성

**판정: PASS**

classifier가 필요하다는 주장을 양방향으로 확인했다. 저장소를 건드리지 않으려고 `git archive`로
스크래치 디렉터리에 사본을 만들고 `@Entity` 프로브 하나를 넣은 뒤 두 번 돌렸다.

classifier 있는 상태(현재 `build.gradle`):

```
$ ./gradlew compileJava
$ find build -name 'Q*.java'
/build/generated/sources/annotationProcessor/java/main/com/petgyebu/telo/probe/QProbe.java
```

classifier를 뺀 상태(`docs/05-infra-stack.md` 7장 스니펫 그대로):

```
$ ./gradlew clean compileJava
build exit=0
$ find build -name 'Q*.java'
(아무것도 없음)
```

빌드는 성공하는데 Q클래스가 하나도 안 생긴다. 조용히 실패하는 형태라서 문서 수정이 꼭 필요하다.
샘플 엔티티 제거도 확인했다. `src/` 전체에 `@Entity`가 하나도 없고 `QuerydslProbeEntity` 잔여물도
없다.

### 기준 4 — easycodef JDK 25 호환

**판정: PASS**

`EasyCodefUtilJdk25Test`가 통과한다(위 테스트 결과 XML). 2048비트 키쌍 생성 → `encryptRSA()` →
개인키 복호화 → 원문 일치까지 실제로 확인하는 테스트다. 검증 범위가 적절하다.

네트워크 경로(토큰 발급, `requestProduct()`, 2-way 추가인증)는 자격증명이 없어 확인할 수 없다.
아래 "미검증"에 남긴다.

### 기준 5 — Dockerfile 빌드 + 컨테이너 헬스체크 200

**판정: PASS** (이미지 크기 문제는 아래 C에서 FIX)

```
$ docker build -t telo:qa .
exit=0
$ docker image inspect telo:qa --format '{{.Size}}'
672199479

$ docker run --rm --entrypoint sh telo:qa -c "id; ls -la /app; ..."
uid=999(app) gid=999(app) groups=999(app)
-rw-r--r-- 1 app app 144981191 app.jar
BOOT-INF/lib/h2-        : 0건
spring-boot-devtools    : 0건
spring-ai               : 0건
ls: cannot access '/workspace': No such file or directory
openjdk version "25.0.4" 2026-07-21 LTS / Temurin-25.0.4+7
```

멀티스테이지가 실제로 산출물만 옮긴다. 런타임 이미지에 `/workspace`도 Gradle도 없고, JRE는
Temurin 25.0.4다. 비루트(uid 999) 실행도 맞다.

postgres 사이드카를 붙여 컨테이너 헬스체크도 직접 확인했다.

```
$ docker run -d --network telo-qa-net -p 18080:8080 \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://telo-qa-pg:5432/telo ... telo:qa
$ curl -s -w 'HTTP %{http_code}\n' http://localhost:18080/actuator/health
HTTP 200
{"groups":["liveness","readiness"],"status":"UP"}
Started TeloApplication in 4.004 seconds
```

`PORT` 환경변수도 실제로 먹는다. `-e PORT=9090`으로 띄우니 로그에 `Tomcat started on port 9090`이
찍혔다.

이미지 크기는 요약의 736MB가 아니라 672MB였다. spring-ai 제거 전후 시점 차이로 보인다. 크기 자체의
원인은 C에서 다룬다.

### 기준 6 — 배포 스크립트·CI에 `--no-cpu-throttling` / `--min-instances=1`

**판정: PASS (명시 여부만) / 미검증 (실동작)**

`scripts/deploy-cloud-run.sh:20-21`에서 두 플래그가 `deploy_args` 배열에 하드코딩돼 있고 환경변수로
덮을 수 없다. `scripts/verify-cloud-run-flags.sh`가 애노테이션 두 개를 검사한다. CI에도 검증
스텝이 들어 있다.

다만 진짜 `gcloud run services describe` 출력과 대조한 적이 없어서 애노테이션 키 이름이 맞는지는
확인되지 않았다. 아래 D와 "미검증"을 보라.

---

## A. 보안 설정 검토 (`SecurityConfig.java`)

**판정: FIX**

### A-1. permitAll 범위 자체는 좁다 — 이 부분은 문제없음

실제로 돌려서 확인했다.

```
/actuator/health            HTTP 200   (인증 없음)
/actuator/health/liveness   HTTP 200   (인증 없음)
/actuator                   HTTP 401
/actuator/env               HTTP 401
/actuator/beans             HTTP 401
/actuator/metrics           HTTP 401
/actuator/info              HTTP 401
/v3/api-docs                HTTP 401
/swagger-ui/index.html      HTTP 401
/foo (임의 경로)             HTTP 401
```

헬스체크만 열려 있다. 과도하게 넓지 않다.

### A-2. HTTP Basic이 켜져 있어 자동 생성 계정이 실제로 인증에 성공한다 — FIX

`SecurityConfig.java:21-22`의 `.httpBasic(basic -> {})` 때문이다. `spring-boot-starter-security`가
`UserDetailsService` 빈이 없을 때 자동으로 `user` 계정과 랜덤 비밀번호를 만드는데, Basic을 켜 두면
그 계정이 살아 있는 자격증명이 된다.

```
기동 로그: Using generated security password: 353e2e45-1807-4c11-8609-3ed82d26848b

$ curl -u user:353e2e45-1807-4c11-8609-3ed82d26848b http://localhost:8080/actuator
HTTP 200
```

인증이 붙어 있어야 할 `/actuator` 전체가 이 한 줄로 열린다. 비밀번호가 매 기동마다 바뀌긴 하지만
**표준출력에 찍히므로 Cloud Run에서는 Cloud Logging에 그대로 남는다.** 이 프로젝트에 Basic 인증을
쓸 소비자가 없으므로 넣을 이유가 없다.

수정 지시: `src/main/java/com/petgyebu/telo/config/SecurityConfig.java:21-22`의 `.httpBasic(...)`
호출을 제거한다. 제거해도 `anyRequest().authenticated()`가 살아 있어 헬스체크 외 경로는 401이 유지된다.

### A-3. CSRF·세션 정책이 JWT 계획(Q4)과 충돌한다 — FIX

둘 다 설정돼 있지 않아 Spring Security 기본값이 적용된다. CSRF는 켜진 상태, 세션 생성 정책은
`IF_REQUIRED`다. 실제로 인증되지 않은 요청에도 세션 쿠키가 발급된다.

```
$ curl -sD - -o /dev/null http://localhost:8080/foo
Set-Cookie: JSESSIONID=A7F3FD1A43001BE1191D287284F856D2; Path=/; HttpOnly
WWW-Authenticate: Basic realm="Realm"
```

액세스 30분 / 리프레시 14일 회전(Q4)은 무상태 토큰 인증이다. 지금 상태로 Sprint 1의 로그인 작업을
올리면 POST 계열 API가 CSRF 토큰 없이 403이 나고, 무의미한 JSESSIONID가 계속 발급된다. 지금
고치는 비용이 두 줄이므로 Sprint 1 착수 전에 처리하는 편이 낫다.

수정 지시: `SecurityConfig.java:17-23` 체인에 다음을 추가한다.

```java
.csrf(csrf -> csrf.disable())
.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

### A-4. springdoc 경로 처리 — 나중

현재 `/v3/api-docs`와 `/swagger-ui/**`가 401이라 당장 노출 사고는 없다. 운영 프로필에서
`springdoc.api-docs.enabled: false`로 끄는 작업은 Sprint 1 이후로 미뤄도 된다. backend-engineer도
같은 지적을 남겼다.

---

## B. 프로필 구성의 안전성

**판정: PASS** (아래 두 건은 향후 결정 사항)

### B-1. H2가 운영으로 새는 경로는 실제로 막혀 있다

주장 두 개를 각각 확인했다.

첫째, jar에 H2가 없다.

```
$ unzip -l build/libs/telo-0.0.1-SNAPSHOT.jar | grep -iE 'h2-|devtools|spring-ai'
(BOOT-INF/lib/httpcore5-h2-5.3.6.jar 만 매치 — 이름이 겹칠 뿐 H2 DB가 아니다)
```

둘째, `spring.datasource.url`이 비어 있을 때 조용히 H2로 기동하지 않고 큰 소리로 죽는다.

```
$ java -jar build/libs/telo-0.0.1-SNAPSHOT.jar
APPLICATION FAILED TO START
Failed to configure a DataSource: 'url' attribute is not specified and no embedded
datasource could be configured.
Reason: Failed to determine a suitable driver class
```

`developmentOnly` 선언이 실제로 이 사고를 구조적으로 막고 있다. 주장 그대로다. 덧붙여 기준 2에서
확인했듯 `local` 프로필을 jar에 실어 보내도 H2 드라이버가 없어 기동 자체가 안 되므로, 프로필
실수로 운영에 H2가 뜨는 경로는 이중으로 막혀 있다.

### B-2. Redis 헬스 인디케이터 — Cloud Run 첫 배포 시 헬스가 빨간불이 된다

`management.health.redis.enabled: false`는 `application-local.yaml`에만 있다. Upstash가 없는 상태로
운영 프로필을 띄우면 헬스가 DOWN이 된다. 컨테이너에서 이 설정만 빼고 직접 확인했다.

```
$ docker run -d -e PORT=9090 (MANAGEMENT_HEALTH_REDIS_ENABLED 미지정) ... telo:qa
$ curl -s -w 'HTTP %{http_code}\n' http://localhost:18081/actuator/health
HTTP 503
{"groups":["liveness","readiness"],"status":"DOWN"}
```

Cloud Run 기본 컨테이너 체크는 TCP 포트라서 배포 자체는 되지만, HTTP 헬스체크를 붙이거나
로드밸런서를 앞에 두는 순간 문제가 된다. Upstash 프로비저닝 시점에 정리할 항목으로 남긴다.

### B-3. 스키마 관리 수단이 없다 — Sprint 1 착수 전에 결정 필요

Flyway도 Liquibase도 없고, 운영 프로필에 `ddl-auto` 설정이 없다(비임베디드 기본값 `none`). 그래서
컨테이너 검증 때 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`를 손으로 넣어야 했다. Sprint 1에서
Account 엔티티가 들어오는 순간 "테이블을 누가 만드는가"가 바로 막힌다. Sprint 0 범위 밖이므로
지적만 하되, Sprint 1 착수 전에 결정해야 한다.

Spring Batch 메타 테이블 초기화도 같은 성격의 미결 사항이다. backend-engineer가 이미 짚었다.

---

## C. Dockerfile

**판정: FIX**

구조 자체는 맞다. 멀티스테이지가 실제로 산출물만 옮기고, 베이스는 JDK 25 계열이며, 비루트로 돈다
(기준 5의 실행 근거 참고). 다만 672MB 중 145MB가 순전히 낭비다.

### C-1. `chown -R`이 jar를 통째로 한 번 더 복제한다

레이어 이력을 보면 원인이 정확히 드러난다.

```
$ docker history telo:sprint0 --format '{{.Size}}\t{{.CreatedBy}}'
145MB   RUN /bin/sh -c chown -R app:app /app
145MB   COPY /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
4.71kB  RUN groupadd --system app && useradd ...
199MB   (JRE 설치 레이어)
```

`RUN chown -R`은 파일 메타데이터만 바꾸는 것처럼 보이지만 OverlayFS에서는 변경된 파일 전체를 새
레이어에 복사한다. 145MB짜리 jar 하나가 두 번 저장되고 있다.

수정 지시: `Dockerfile:22-23`을 다음으로 바꾼다.

```dockerfile
COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
```

23행의 `RUN chown -R app:app /app`은 삭제한다. 이미지가 672MB에서 약 527MB로 줄어든다.

### C-2. `.dockerignore`가 없다

저장소 루트에 `.dockerignore`가 없어서 빌드 컨텍스트에 `.git`, `build/`, `.gradle/`, `.idea/`가
전부 들어간다. 빌드가 느려지고, 로컬 `build/` 산출물이 컨텍스트에 섞여 캐시 무효화를 유발한다.
CI에서도 같은 컨텍스트를 전송한다.

수정 지시: 루트에 `.dockerignore`를 만들고 최소한 `.git`, `build`, `.gradle`, `.idea`, `_workspace`를
넣는다.

### C-3. 남은 527MB의 근거는 있다

JRE 베이스 199MB + Ubuntu 기반 레이어 + jar 145MB다. jar가 큰 이유는 의존성이 260개이기 때문이고,
그중 `firebase-admin`이 Google 계열 전이 의존성을 대량으로 끌어온다. 선언된 스택상 정상 범위다.
더 줄이려면 `bootJar` 레이어드 jar나 jlink 커스텀 런타임이 필요한데 지금 할 일은 아니다.

### C-4. 사소한 것

`Dockerfile:8`이 `gradlew settings.gradle build.gradle`만 복사한다. 현재 `gradle.properties`가 없어
문제가 없지만, 나중에 추가되면 조용히 빠진다. 기억해 둘 것.

---

## D. CI 워크플로 (`.github/workflows/ci.yml`)

**판정: FIX**

### D-1. secrets 우회 구조는 정상이다

`secrets` 컨텍스트를 잡 레벨 `if`에서 못 쓰는 것은 사실이고, 스텝 `env`로 받아 `$GITHUB_OUTPUT`으로
내보내는 방식은 표준적인 우회다. 구조 자체는 동작하는 형태다.

### D-2. 배포 성공 시 검증 스텝은 반드시 실행된다

`ci.yml:89`의 `if: steps.deploy.outcome == 'success'`가 맞게 작성됐다. 스킵된 스텝의 `outcome`은
`'skipped'`, 실패한 스텝은 `'failure'`이므로 배포가 실제로 성공한 경우에만 검증이 돈다. 이 부분은
주장대로다.

### D-3. 자격증명이 없으면 전부 스킵되고 CI가 초록색으로 보인다 — 실재하는 함정

`deploy` 잡의 `if`는 `github.ref == 'refs/heads/main'`만 본다. 자격증명이 없으면 `creds` 스텝이
`available=false`를 내보내고 이후 다섯 스텝이 전부 스킵되며, 잡은 성공으로 끝난다. 지금은
프로비저닝 전이라 의도된 동작이지만, **GCP가 붙은 뒤에 시크릿이 지워지거나 만료되면 배포도
플래그 검증도 조용히 사라지고 CI는 계속 초록색이다.** Sprint 0의 목적이 "플래그가 리셋되면
빌드가 깨지게 만드는 것"인데 정반대로 동작할 수 있다.

수정 지시: GCP 프로비저닝 완료 시점에 `ci.yml:50-56`의 `else` 분기를 `exit 1`로 바꾸거나,
저장소 변수(`vars.DEPLOY_ENABLED` 등)로 명시적으로 켜고 끄는 구조로 바꾼다. 지금 당장은 아니고,
"GCP 붙일 때 같이 한다"를 인수인계 항목으로 남겨야 한다.

### D-4. 검증 스크립트가 서비스의 희망 상태만 본다

`scripts/verify-cloud-run-flags.sh:31-36`이 `spec.template.metadata.annotations`만 읽는다. 이것은
다음 리비전에 적용될 템플릿이고, 실제로 트래픽을 받는 리비전의 상태가 아니다. 배포가 부분적으로
실패해 새 리비전이 Ready가 되지 못한 경우, 템플릿은 올바른데 살아 있는 리비전은 플래그 없이 도는
상황을 통과시킬 수 있다.

수정 지시: `status.latestReadyRevisionName`을 함께 확인하거나, 해당 리비전을
`gcloud run revisions describe`로 다시 조회해 애노테이션을 대조한다. GCP 붙는 시점 과제다.

### D-5. 사소한 것

`build` 잡에 `permissions` 블록이 없어 기본 토큰 권한이 적용된다. 실패 시 테스트 리포트 업로드
스텝도 없다. 둘 다 나중에 해도 된다.

---

## E. 의존성이 문서와 일치하는지

**판정: PASS** (코드 쪽은 맞고, 문서 쪽이 틀렸다)

backend-engineer가 보고한 네 가지를 전부 독립적으로 재확인했다.

### E-1. `spring-boot-starter-aop`는 Spring Boot 4.0에 없다 — 사실

스크래치 사본에서 `spring-boot-starter-aspectj`를 `spring-boot-starter-aop`로 바꿔 돌렸다.

```
FAILURE: Build failed with an exception.
   > Could not find org.springframework.boot:spring-boot-starter-aop:.
BUILD FAILED
```

주장이 맞다. `docs/05-infra-stack.md` 7장의 표기가 틀렸다. 실제 jar에는
`aspectjweaver-1.9.25.1.jar`가 들어 있다.

### E-2. 버전 조사 결과 — 전부 사실

Maven Central `maven-metadata.xml`을 직접 조회했다.

| 아티팩트 | Maven Central 최신 | 적용 버전 | 판정 |
|---|---|---|---|
| `net.javacrumbs.shedlock:shedlock-spring` | 7.10.1 | 7.9.0 | 문서 확정값 유지 — 의도적 |
| `io.github.openfeign.querydsl:querydsl-apt` | 7.6 | 7.5 | 문서 확정값 유지 — 의도적 |
| `com.google.firebase:firebase-admin` | 9.10.0 | 9.10.0 | 최신 |
| `io.github.resilience4j:resilience4j-spring-boot4` | 2.4.0 | 2.4.0 | 유일 버전 |

보고된 숫자가 전부 일치한다. 문서가 고정해 둔 값을 임의로 올리지 않은 판단도 적절하다.

### E-3. jar 내용물 확인

```
$ unzip -l build/libs/telo-0.0.1-SNAPSHOT.jar | grep BOOT-INF/lib/ | wc -l
260

aspectjweaver-1.9.25.1.jar
querydsl-jpa-7.5.jar / querydsl-core-7.5.jar
shedlock-spring-7.9.0.jar / shedlock-provider-redis-spring-7.9.0.jar / shedlock-core-7.9.0.jar
resilience4j-spring-boot4-2.4.0.jar (+ core/retry/circuitbreaker/... 2.4.0)
firebase-admin-9.10.0.jar
easycodef-java-1.0.6.jar
springdoc-openapi-starter-webmvc-ui-3.1.0.jar
```

spring-ai·devtools·h2는 0건이다. 제거와 유지 결정이 모두 실제 산출물에 반영돼 있다.

### E-4. 문서를 고쳐야 할 곳 (코드가 아니라 문서 쪽)

`docs/05-infra-stack.md` 7장에서 네 군데가 실제와 다르다. backend-engineer가 손대지 않고 남겨 둔
것이 맞는 판단이고, 리더가 처리할 몫이다.

1. `spring-boot-starter-aop` → `spring-boot-starter-aspectj` (E-1)
2. `annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:7.5'` → `:jakarta` classifier 필수 (기준 3)
3. `springdoc-openapi-starter-webmvc-ui:3.1.0`이 의존성 목록에 없음 — 유지하기로 했으므로 추가
4. 6장 Action Item 중 QueryDSL·easycodef·버전 확인 세 건은 이번에 결론이 났으므로 체크 처리

### E-5. 프로젝트 이름이 문서와 코드에서 갈린다 — 첫 배포 전 결정 필요

문서 3.1절은 Group `com.budgetpet` / Artifact `budget-pet-api`인데 실제 저장소는 `com.petgyebu` /
`telo`다. 배포 스크립트(`deploy-cloud-run.sh:10`)와 CI(`ci.yml:38`)는 문서 쪽 `budget-pet-api`를
서비스명으로 쓴다. 지금은 아무 데도 배포되지 않아 문제가 드러나지 않지만, 첫 배포에서 서비스명이
확정되면 바꾸기가 번거로워진다. 배포 전에 한쪽으로 정해야 한다.

---

## 미검증 (PASS로 분류하지 않는다)

| 항목 | 사유 |
|---|---|
| Cloud Run 실배포와 `gcloud run services describe` 실출력 대조 | GCP 계정 없음. 애노테이션 키 `run.googleapis.com/cpu-throttling`·`autoscaling.knative.dev/minScale`은 Knative 규약에 근거한 추정이며 실물로 확인된 적이 없다. 첫 배포에서 반드시 확인할 것 |
| Upstash Redis 연결과 ShedLock 분산락 동작 | Upstash 계정 없음 |
| Cloud SQL 연결과 `postgres-socket-factory` 동작 | 프로비저닝 전. 이번 컨테이너 검증은 일반 postgres 컨테이너로 대체했다 |
| 코드에프 실 API 호출, 토큰 발급, 2-way 추가인증 | 자격증명·DEMO 승인 없음. `encryptRSA()`만 검증됐고 SDK 내부 HTTP 경로는 미확인 |
| QueryDSL 실쿼리 실행 | 엔티티·리포지터리가 Sprint 1 범위. Q클래스 생성까지만 확인 |

---

## Sprint 1 진입 전에 반드시 해결할 것

1. **`SecurityConfig`의 `.httpBasic()` 제거** (A-2). 자동 생성 계정이 실제로 인증에 성공하고 그
   비밀번호가 로그에 남는다. `SecurityConfig.java:21-22`
2. **CSRF 비활성화 + 세션 `STATELESS` 설정** (A-3). Sprint 1의 JWT 로그인이 이 설정 위에 올라가므로
   지금 고치지 않으면 잘못된 기반 위에 쌓인다. `SecurityConfig.java:17-23`
3. **스키마 관리 수단 결정** (B-3). Flyway 도입 여부를 Account 엔티티 착수 전에 정한다
4. **서비스명·Group ID 통일** (E-5). `budget-pet-api`와 `telo` 중 하나로
5. **`docs/05-infra-stack.md` 7장 수정** (E-4). 특히 QueryDSL classifier는 빠지면 조용히 실패하므로
   문서에 남아 있으면 안 된다

## 나중으로 미뤄도 되는 것

- Dockerfile `chown` 레이어 제거와 `.dockerignore` 추가 (C-1, C-2). 145MB를 줄이는 값싼 수정이지만
  기능에는 영향이 없다
- CI가 자격증명 없을 때 초록색으로 끝나는 구조의 강화 (D-3). GCP를 붙이는 작업과 묶어서 처리
- 검증 스크립트가 서빙 리비전을 확인하도록 보강 (D-4). 같은 시점
- 운영 프로필의 Redis 헬스 인디케이터 정책 (B-2). Upstash 프로비저닝과 함께
- Spring Batch 메타 테이블 초기화 방식 (B-3 후단). Cloud SQL 연결과 함께
- 운영 프로필에서 springdoc 비활성화 (A-4)
- CI `build` 잡의 `permissions` 블록과 테스트 리포트 업로드 (D-5)

## 총평

Sprint 0 부분 진행분으로서 품질이 높다. 특히 QueryDSL classifier 문제를 "빌드는 되는데 Q클래스가
안 생긴다"는 조용한 실패 형태까지 규명하고 원인(jar 안 서비스 등록 파일 부재)을 짚은 것, H2를
`developmentOnly`로 두어 운영 누수를 구조적으로 막은 것, 프로브 엔티티를 남기지 않고 지운 것은
제대로 된 판단이다. 요약의 사실 관계에 거짓이 없다는 점도 확인했다.

지적한 세 건의 FIX는 모두 국소적이고, REDO는 없다. 보안 두 줄만 정리하면 Sprint 1에 들어가도 된다.
