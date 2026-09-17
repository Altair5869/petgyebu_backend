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

---

# 2차 검증 (Flyway·네이밍·문서)

- 작성: 2026-09-17
- 검증 대상: 커밋 `7e01405`(Flyway 도입), `1b868c6`(네이밍 통일), `a9795e7`(문서 반영). 1차 검증 대상인
  `0725721`·`c7a9216`는 재검증하지 않고 회귀 여부만 확인했다.
- 검증 방식: 리더가 요약 15장에 적어 둔 검증 결과를 근거로 삼지 않고, 스크래치 디렉터리에
  `git archive`로 사본을 만들어 PostgreSQL 16 컨테이너를 붙여 전부 다시 돌렸다. 이번 변경은 작성자와
  검증자와 커밋 주체가 전부 같은 사람이라 독립 재현이 특히 필요했다.
- 컨테이너는 이전 실행과 겹치지 않는 이름(`qa-pg-flyway`, `qa-pg-zero`)과 포트(15439, 15441,
  앱은 18085~18091)를 썼고, 검증이 끝난 뒤 전부 제거했다. 포트가 실제로 죽었는지도 확인했다.

## 종합

| 구분 | 건수 |
|---|---|
| PASS | 7 |
| FIX | 4 |
| REDO | 0 |
| 미검증 | 1 |

리더가 주장한 다섯 가지는 전부 사실이었다. 과장도 누락도 없었고, 특히 Flyway 자동설정 함정은
양방향으로 재현해 확인했다. 코드와 설정 쪽에는 고칠 것이 없다.

문제는 전부 문서 쪽이다. 가장 무거운 것은 **`docs/05-infra-stack.md`에 Flyway가 단 한 글자도 없다**는
점이다. Sprint 0에서 내린 가장 중요한 기술 결정(스키마 관리 주체와 그 함정)이 작업 메모인
`_workspace/sprint0_backend_summary.md`에만 있고 결정 문서에는 반영되지 않았다. QueryDSL classifier
때와 정확히 같은 종류의 사고가 다시 준비돼 있다.

그 다음이 마이그레이션 이름 규칙 README다. 타임스탬프 규칙이 해결한다고 주장한 문제와 실제로
해결하는 문제가 다르고, 병렬 브랜치에서 진짜로 터지는 실패 모드가 문서에 없다.

## 리더 주장 5건의 재현 결과

### 주장 1 — `./gradlew clean build` 통과

**판정: PASS**

```
$ ./gradlew clean build --console=plain
BUILD SUCCESSFUL in 13s
8 actionable tasks: 8 executed
```

### 주장 2 — PostgreSQL에서 Flyway가 실제로 돌고 `flyway_schema_history`가 생성된다

**판정: PASS**

`postgres:16-alpine`를 15439 포트로 새로 띄우고, 저장소 사본에 프로브 마이그레이션
(`V202601010000__qa_probe.sql`, `CREATE TABLE qa_probe`)을 하나 넣어 돌렸다.

```
org.flywaydb.core.FlywayExecutor : Database: jdbc:postgresql://localhost:15439/qadb (PostgreSQL 16.15)
o.f.c.i.s.JdbcTableSchemaHistory : Creating Schema History table "public"."flyway_schema_history" ...
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "202601010000 - qa probe"
o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema "public"

$ psql \dt
 public | flyway_schema_history | table
 public | qa_probe              | table
$ select version, description, success from flyway_schema_history;
 202601010000 | qa probe | t
```

기록만 남는 것이 아니라 SQL이 실제로 실행돼 테이블이 만들어지는 것까지 확인했다.

### 주장 3 — 마이그레이션 0건에서도 기동 실패 없음, 헬스체크 200

**판정: PASS**

주장 2에서 쓴 사본에는 프로브 파일이 있으므로, 저장소 HEAD를 그대로 뽑은 별도 사본
(`db/migration`에 `README.md`만 있는 상태)과 새 DB(15441 포트)로 다시 확인했다.

```
Flyway : All configured schemas are empty; baseline operation skipped.
JdbcTableSchemaHistory : Creating Schema History table "public"."flyway_schema_history" ...
TeloApplication : Started TeloApplication in 3.688 seconds

$ curl http://localhost:18091/actuator/health
{"groups":["liveness","readiness"],"status":"UP"} HTTP 200
$ psql: flyway_schema_history 1개, 행 0개
```

단서 하나. 이 200은 `MANAGEMENT_HEALTH_REDIS_ENABLED=false`를 넣었을 때의 결과다. 1차 리포트 B-2에서
지적한 대로 운영 프로필에는 Redis 헬스 인디케이터를 끄는 설정이 없어서, 이 환경변수 없이 띄우면
Upstash가 붙기 전까지 헬스는 503이다. 이번 변경과는 무관하지만 "헬스체크 200"이라는 문장이 조건
없이 성립하지는 않는다는 점을 기록해 둔다.

### 주장 4 — `ddl-auto: validate` 상태에서 검증 통과

**판정: PASS** (단, 통과의 의미가 제한적이다)

엔티티가 하나도 없으므로 Hibernate가 검증할 대상이 없어서 통과한 것이다. 기동이 되는 것은 맞지만
"validate 설정이 제대로 동작함을 확인했다"는 뜻은 아니다. validate가 실제로 무엇을 하는지는 아래 B에서
엔티티를 하나 넣어 확인했고, 정상적으로 기동을 막았다.

### 주장 5 — 코드·스크립트·CI에 `budget-pet` 잔존 없음

**판정: PASS** (문서에 별개 잔존물이 하나 있다 — 아래 E-3)

```
$ grep -ri 'budget-pet\|budgetpet\|budgetdb' --exclude-dir=.git --exclude-dir=build .
docs/05-infra-stack.md : 정정 이력을 설명하는 문장 1건 (의도된 것)
docs/05-infra-stack.md : jdbc:postgresql:///budgetdb?... (E-3에서 다룬다)
_workspace/*.md        : 과거 기록 (수정 대상 아님)
```

`.github/workflows/ci.yml:38`, `scripts/deploy-cloud-run.sh:10`, `scripts/verify-cloud-run-flags.sh:10`
세 곳 모두 `telo`로 바뀌었다. 코드·스크립트·CI 잔존은 0건이 맞다.

## A. Flyway 자동설정 주장의 진위 — 사실이다

**판정: PASS (주장이 사실로 확인됨) + 문서 반영 필요(E-1)**

먼저 구조를 확인했다. `FlywayAutoConfiguration`은 `spring-boot-autoconfigure-4.0.8.jar`에 없고
`spring-boot-flyway-4.0.8.jar`에만 있다.

```
$ unzip -l spring-boot-flyway-4.0.8.jar | grep FlywayAutoConfiguration
org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration$FlywayConfiguration.class
... (외 10여 개)
$ unzip -l spring-boot-autoconfigure-4.0.8.jar | grep -i FlywayAutoConfiguration
(없음)
```

그 다음 실제로 돌렸다. 같은 사본에서 `implementation 'org.springframework.boot:spring-boot-flyway'`
한 줄만 지우고, `flyway-core`와 `flyway-database-postgresql`은 남긴 채, 프로브 마이그레이션이 들어
있고 `spring.flyway.enabled: true`가 켜진 상태로 새 DB에 붙였다.

```
$ grep -c -i flyway run_without_flyway.log
0                          ← Flyway 로그가 한 줄도 없다
TeloApplication : Started TeloApplication in 2.961 seconds
$ curl /actuator/health → HTTP 200
$ psql \dt → Did not find any relations.
```

**기동 성공, 헬스 200, 경고 0건, 그런데 DB에는 아무것도 없다.** `spring.flyway.enabled: true`라고
적어 두어도 소용이 없고, 알 수 없는 설정 키라는 경고조차 나오지 않는다. QueryDSL classifier와
성질이 완전히 같은 조용한 실패다. 그 뒤 같은 사본에 `spring-boot-flyway` 한 줄만 되돌리니 즉시
마이그레이션이 적용됐다. 양방향으로 확인된 셈이다.

요약 15장의 주장은 사실이고, 이것은 문서에 반드시 남아야 할 함정이다. 그런데 남아 있지 않다(E-1).

## B. local(H2, create-drop) + 운영(Flyway, validate) 분리 구성의 위험

**판정: FIX**

구성 자체의 방향은 맞다. JSONB·GIN 인덱스를 쓸 마이그레이션을 H2에서 돌릴 수 없는 것도 사실이고,
금융 거래 테이블에 `update`를 쓰지 않기로 한 판단도 옳다. 문제는 **이 분리를 검증하는 경로가
아무 데도 없다**는 점이다.

`src/test/java/com/petgyebu/telo/TeloApplicationTests.java:8`이 `@ActiveProfiles("local")`이다.
즉 유일한 컨텍스트 로딩 테스트가 H2 + `create-drop` + Flyway 비활성 조합으로 돈다. CI의
`./gradlew build`도 이 경로만 탄다. 결과적으로 **Flyway 마이그레이션과 `validate`는 CI에서 단 한 번도
실행되지 않는다.**

로컬에서 잘 돌던 엔티티가 운영에서 기동 불가가 되는 경로가 실제로 존재하는지 직접 확인했다.
사본에 필드 두 개짜리 엔티티 하나를 넣고 마이그레이션은 쓰지 않았다.

```
$ ./gradlew test          (local 프로필, H2, create-drop)
BUILD SUCCESSFUL in 6s    ← CI는 초록불이다

$ (같은 코드, PostgreSQL + Flyway + validate)
Caused by: org.hibernate.tool.schema.spi.SchemaManagementException:
    Schema validation: missing table [probe]
APPLICATION FAILED TO START
```

Sprint 1에서 이것이 어떻게 드러나는지는 분명하다. Account 엔티티를 추가하고 마이그레이션 작성을
잊거나 컬럼 하나를 빠뜨리면, 테스트는 통과하고 PR도 초록불로 병합되며, 문제는 **Cloud Run 배포 후
컨테이너가 기동에 실패하는 시점에** 처음 드러난다. `validate`가 사고를 막아주는 것은 맞지만 막아주는
위치가 운영이다. 엔티티 필드명·타입·nullable·인덱스명이 마이그레이션과 한 글자라도 어긋날 때마다
같은 일이 반복된다.

덧붙여 H2와 PostgreSQL의 타입 매핑 차이(예: `@Lob`, `UUID`, `TIMESTAMP WITH TIME ZONE`, 식별자
생성 전략)는 `create-drop` 경로에서는 아예 드러나지 않는다.

수정 지시: Sprint 1의 첫 엔티티를 넣기 **전에**, PostgreSQL을 대상으로 Flyway + `validate`를 돌리는
검증 경로를 하나 만든다. 가장 값싼 방법은 Testcontainers(`spring-boot-testcontainers` +
`postgresql`)로 `@SpringBootTest` 하나를 운영 프로필 설정으로 띄워 컨텍스트 로딩만 확인하는
것이다. Testcontainers를 쓰지 않겠다면 최소한 CI에 `services: postgres`를 붙이고 부팅 확인 스텝을
넣어야 한다. 어느 쪽이든 "마이그레이션과 엔티티가 어긋나면 CI가 빨간불이 된다"가 성립해야 한다.
지금은 성립하지 않는다.

이 지적은 이번 변경의 결함이라기보다 이번 변경이 새로 만들어 낸 빈틈이다. Flyway를 도입하는 순간
"스키마의 진짜 모습"이 테스트 경로 밖으로 나갔다.

## C. Spring Batch 메타 테이블 — 지금은 안전, Sprint 6에 확실히 터진다

**판정: PASS (현재 상태) + 인수인계 항목**

`--debug`로 띄워 조건 평가 리포트를 직접 읽었다.

```
BatchAutoConfiguration matched:
BatchJobLauncherAutoConfiguration matched:
BatchJobLauncherAutoConfiguration#jobLauncherApplicationRunner matched:
   - @ConditionalOnBooleanProperty (spring.batch.job.enabled=true) matched

TeloApplication : Started TeloApplication in ...   ← BATCH_* 테이블이 하나도 없는 DB
```

자동설정은 켜져 있는데도 기동에는 실패하지 않는다. `initialize-schema: never`라 스키마를 만들려
하지도 않고, `JobRepository`가 만들어지는 것만으로는 DB에 접근하지 않기 때문이다. 즉 **지금 괜찮은
이유는 Job 빈이 하나도 없어서**가 맞다. 잠재적 기동 실패는 아니다.

다만 `jobLauncherApplicationRunner`가 이미 활성이라는 점이 중요하다. Sprint 6에서 `Job` 빈이
하나 등록되는 순간 이 러너가 기동 시점에 잡을 실행하고, 그때 `BATCH_JOB_INSTANCE`가 없으면
기동 자체가 실패한다. 배치 잡을 만드는 커밋과 `BATCH_*` 마이그레이션을 쓰는 커밋이 갈리면
바로 그 배포가 죽는다.

Flyway로 관리하기로 한 결정 자체는 타당하다. 스키마 생성 경로가 하나인 편이 낫다. 다만
`BATCH_*` DDL은 직접 쓰지 말고 Spring Batch가 배포하는
`org/springframework/batch/core/schema-postgresql.sql`을 그대로 마이그레이션 파일로 옮겨야 한다.
버전이 올라갈 때 스키마가 바뀌면 손으로 쓴 DDL은 조용히 어긋난다. 이 내용이 지금 어디에도 적혀
있지 않으므로 README의 "현재 상태" 절이나 Sprint 6 계획에 남겨야 한다.

## D. 마이그레이션 이름 규칙 — 해결한다고 적힌 문제와 실제로 해결하는 문제가 다르다

**판정: FIX** — 대상 파일 `src/main/resources/db/migration/README.md:24-27`

먼저 규칙 자체가 Flyway에서 동작하는지는 확인했다. 12자리 타임스탬프
(`V202601010000__qa_probe.sql`)는 정상적으로 인식돼 적용됐고, 이력 테이블에도 `202601010000`으로
기록됐다. 버전 정렬은 숫자 비교라 자리수가 같은 타임스탬프끼리는 시간순과 일치한다. 여기까지는
문제없다.

문제는 README가 근거로 든 문장이다. "순번을 쓰면 두 브랜치가 같은 번호를 잡아 병합할 때 충돌한다"
— 맞다. 그리고 타임스탬프는 그 충돌을 없앤다. 그런데 **파일 이름 충돌이 없어진 대가로 더 조용한
실패가 생긴다.** 실제로 재현했다.

브랜치 B(늦은 타임스탬프)가 먼저 병합돼 운영에 적용된 뒤, 브랜치 A(이른 타임스탬프)가 나중에
병합되는 상황을 만들었다.

```
적용 완료 상태: flyway_schema_history = [202601010000]
추가된 파일:    V202512310000__earlier_branch.sql   (이미 적용된 것보다 낮은 버전)

$ bootRun
Detected resolved migration not applied to database: 202512310000.
Caused by: org.flywaydb.core.api.exception.FlywayValidateException:
    Validate failed: Migrations have failed validation
APPLICATION FAILED TO START
```

무시되는 것이 아니라 **기동이 죽는다.** 그리고 이 실패는 병합한 사람의 로컬에서는 절대 재현되지
않는다. 로컬은 H2라 Flyway가 꺼져 있고, 새로 만든 DB에는 이력이 없어 두 파일이 순서대로 다
적용되기 때문이다. 오직 이미 마이그레이션이 적용돼 있는 운영 DB에서만 터진다.

타임스탬프 규칙이 순번보다 나쁜 선택이라는 뜻은 아니다. 순번을 써도 out-of-order 문제는 똑같이
생긴다. 문제는 README가 "타임스탬프를 쓰면 병렬 브랜치 문제가 해결된다"고만 적고 **남아 있는
쪽의 실패 모드를 전혀 언급하지 않는다**는 점이다. Sprint 3에 병렬 구간이 있다고 명시해 둔 문서가
정작 병렬 병합에서 실제로 터지는 실패를 안 적고 있다.

수정 지시: `src/main/resources/db/migration/README.md`에 다음 중 하나의 방침을 명시한다.

1. (권장) 병합 직전에 타임스탬프를 다시 찍는다 — 이미 적용된 최고 버전보다 항상 크게 유지한다.
   운영 이력이 선형으로 유지되고 `out-of-order` 설정을 건드릴 필요가 없다.
2. `spring.flyway.out-of-order: true`를 켠다. 대신 마이그레이션이 작성 순서와 다른 순서로 적용될 수
   있다는 점을 받아들여야 하고, 두 마이그레이션이 같은 테이블을 건드리면 결과가 순서에 의존한다.

덧붙여 두 가지를 더 적는 편이 좋다. 하나는 "여러 사람이 다른 타임존에서 작성하면 `UTC+9 기준`
규칙이 깨진다"는 점이고(현재는 1인 개발이라 실害는 없다), 다른 하나는 `U__` 되돌림 마이그레이션이
Flyway 유료 기능이라 쓸 수 없다는 점이다. 롤백은 새 마이그레이션으로만 한다는 원칙은 이미 적혀
있으니 근거만 한 줄 붙이면 된다.

## E. 문서 정정의 정확성 (`docs/05-infra-stack.md`)

### E-1. 7장 스니펫에 Flyway 3줄이 통째로 빠졌다 — FIX (이번 검증에서 가장 무거운 항목)

**판정: FIX** — 대상 `docs/05-infra-stack.md` 7장

확인 결과는 이렇다.

```
$ grep -rin 'flyway\|liquibase' docs/*.md
(0건)
```

**`docs/` 전체에 Flyway라는 단어가 한 번도 안 나온다.** 7장 스니펫에도, 아래 의존성 표에도,
3.3절 Cloud SQL 항목에도 없다. 스키마를 누가 만드는가는 1차 리포트가 "Sprint 1 착수 전에 반드시
결정할 것"으로 올린 항목이었고 이번에 결정이 났는데, 결정 문서에는 그 결정이 없다.

지금 7장 스니펫을 그대로 복사해 새 프로젝트를 만들면 빌드는 된다. 하지만 `application.yaml`의
`spring.flyway.*` 설정은 아무 효과가 없고, 마이그레이션은 조용히 실행되지 않으며, `ddl-auto: validate`
때문에 첫 엔티티에서 기동이 실패한다. 원인을 찾으려면 A에서 한 것과 똑같은 조사를 처음부터 다시
해야 한다. QueryDSL classifier를 문서에서 고친 이유가 정확히 이것이었는데, 같은 함정이 하나 더
생긴 채로 남아 있다.

수정 지시: 7장 `직접 추가` 스니펫에 다음 3줄을 넣는다. `build.gradle:57-62`와 동일해야 한다.

```groovy
    implementation 'org.springframework.boot:spring-boot-flyway'   // 자동설정 모듈. 빼면 조용히 실패
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

그리고 아래 의존성 표에 Flyway 행을 추가하되, QueryDSL classifier 행과 같은 강도로 경고를 단다.
`spring-boot-flyway`가 없으면 빌드·기동·헬스체크가 전부 정상인 채 마이그레이션만 실행되지 않는다는
사실과, 2026-09-17에 실제로 재현했다는 근거를 남긴다. 스키마 관리 방침(운영은 Flyway + `validate`,
`local`은 Hibernate `create-drop`, 대상 DBMS는 PostgreSQL 16 단일)도 3.3절이나 별도 절에 한 문단으로
적어야 한다. 지금은 이 방침이 작업 메모에만 있다.

### E-2. 7장의 나머지 정정은 `build.gradle`과 일치한다 — PASS

한 줄씩 대조했다.

| 문서 7장 | `build.gradle` | 일치 |
|---|---|---|
| `spring-boot-starter-aspectj` | `build.gradle:22` 동일 | ✅ |
| `querydsl-apt:7.5:jakarta` (classifier 필수) | `build.gradle:53` 동일 | ✅ |
| `resilience4j-spring-boot4:2.4.0` | `build.gradle:45` 동일 | ✅ |
| `firebase-admin:9.10.0` | `build.gradle:39` 동일 | ✅ |
| `springdoc-openapi-starter-webmvc-ui:3.1.0` | `build.gradle:29` 동일 | ✅ |
| `postgres-socket-factory:1.28.1` | `build.gradle:38` 동일 | ✅ |
| `shedlock-spring:7.9.0` / `-provider-redis-spring:7.9.0` | `build.gradle:48-49` 동일 | ✅ |
| jjwt 0.13.0 3종 | `build.gradle:32-34` 동일 | ✅ |

1차 리포트 E-4에서 지적한 네 건(aop→aspectj, classifier, springdoc 누락, Action Item 체크) 중
세 건은 정확히 반영됐고, Action Item 3건도 `[x]`로 바뀌면서 결론이 요약돼 있다. 새로 추가된 Action
Item 2건(Cloud Run 플래그 실동작 확인, CI 조용한 성공 제거)도 1차 리포트 D-3·D-4와 내용이 일치한다.
문서 상단 갱신일과 갱신 사유도 갱신됐다.

### E-3. 3.3절 datasource 예시에 `budgetdb`가 남아 있다 — FIX(사소)

```
docs/05-infra-stack.md 3.3절:
  spring.datasource.url: jdbc:postgresql:///budgetdb?cloudSqlInstance=...:telo-db&socketFactory=...
```

인스턴스명은 `telo-db`로 바꿨는데 데이터베이스명은 `budgetdb` 그대로다. 같은 한 줄 안에서 이름
체계가 갈린다. 첫 Cloud SQL 프로비저닝 때 DB 이름을 이 문서 보고 정하면 어긋난 채로 굳는다.
`telodb`(요약 15장의 컨테이너 검증에서 쓴 이름)로 맞추거나, 최소한 이름을 확정하지 않았다는
표시를 해야 한다.

### E-4. 7장 표의 코드에프 행이 Action Item과 어긋난다 — FIX(사소)

6장 Action Item은 `easycodef-java` JDK 25 호환성 검증을 `[x] 완료`로 바꿨는데, 7장 표의 코드에프 SDK
행은 여전히 `⚠️ JDK 25 호환성 Sprint 0 검증 필요`다. 같은 문서 안에서 두 곳이 다른 말을 한다.
7장 행을 다른 행들처럼 `✅ 2026-09-15 확인` 형태로 맞춘다.

### E-5. `application.yaml`의 `baseline-on-migrate` 주석이 실제 동작과 다르다 — FIX(사소)

`src/main/resources/application.yaml:14-17`의 주석은 "이미 테이블이 있는 DB에 Flyway를 붙일 때를
대비한 설정"이라고 적혀 있다. 그런데 `baseline-version: 0`이므로 기존 테이블이 있는 DB에 붙이면
Flyway는 이력에 0을 기록한 뒤 **모든 마이그레이션을 처음부터 실행한다.** 이미 있는 테이블을 다시
`CREATE TABLE` 하려다 실패할 뿐 보호 효과가 없다. 빈 DB에서 영향이 없다는 뒷문장은 맞다(이번
검증에서 `baseline operation skipped` 로그로 확인했다).

기존 DB를 실제로 인수할 계획이 없다면 이 설정을 빼는 편이 정직하다. 남긴다면 주석을 "운영 DB를
나중에 인수할 때 baseline 버전을 그 시점 스키마에 맞게 다시 정해야 한다"로 고쳐야 한다. 지금 문구는
없는 안전장치를 있다고 읽게 만든다.

## 회귀 확인 — 전부 유지

1차 리포트에서 PASS였던 항목과 c7a9216에서 고친 항목이 깨지지 않았는지 확인했다.

| 항목 | 결과 |
|---|---|
| `Using generated security password` | 기동 로그 0건 (`grep -c` → 0) |
| `/actuator/health` | HTTP 200 |
| `/actuator/env` | HTTP 401 |
| `/v3/api-docs` | HTTP 401 (1차 확인 유지, 설정 변경 없음) |
| `Set-Cookie: JSESSIONID` | 401 응답에 없음 |
| H2가 bootJar에 없음 | `BOOT-INF/lib/h2-`·devtools 매치 0건 |
| `SecurityConfig` | `httpBasic` 없음, `csrf().disable()`, `STATELESS` 유지 |
| Dockerfile `chown` 레이어 | `COPY --chown`으로 정리된 상태 유지 |
| `.dockerignore` | 존재 |
| 테스트 2건 | `./gradlew build` 통과 |

새로 추가된 `db/migration/README.md`가 jar에 실려 디렉터리가 유지되는 것도 확인했다
(`BOOT-INF/classes/db/migration/README.md`). Flyway는 `V`/`U`/`R` 접두사 파일만 읽으므로 무시된다는
설명도 맞다.

## 미검증

| 항목 | 사유 |
|---|---|
| Cloud SQL(실제 인스턴스)에서의 Flyway 동작과 마이그레이션 잠금 | 프로비저닝 전. 이번 검증은 일반 `postgres:16-alpine` 컨테이너로 대체했다. Cloud SQL 계정 권한(`CREATE TABLE` 권한, 스키마 소유자)에 따라 결과가 달라질 수 있다 |

1차 리포트의 미검증 5건(Cloud Run 실배포, Upstash, Cloud SQL 실연결, 코드에프 실 API, QueryDSL
실쿼리)은 그대로 미검증이다. 이번 변경으로 해소된 것이 없다.

## PR을 올려도 되는 상태인가

**올려도 된다. 다만 E-1은 PR 전에 고치는 편이 낫다.**

코드·설정·스크립트·CI에는 FIX가 하나도 없다. Flyway 도입은 실제로 동작하고, 네이밍 통일은 누락
없이 끝났고, 회귀도 없다. 리더가 요약에 적은 다섯 가지 주장은 재현 결과 전부 사실이었다.

남은 FIX 네 건 중 세 건(E-3, E-4, E-5)은 문구 수준이라 후속 커밋으로 미뤄도 된다. B는 Sprint 1
착수 전에 처리할 항목이지 이 PR을 막을 이유는 아니고, D는 첫 마이그레이션을 쓰기 전까지는 실害가
없다.

E-1만 성격이 다르다. 스니펫 3줄을 안 넣고 병합하면 결정 문서가 실제 구성과 어긋난 채로 확정되고,
그 상태가 다음 사람에게 조용한 실패로 돌아온다. 문서 한 곳을 고치는 비용이 몇 줄이므로 이 PR에
같이 넣는 편이 맞다.

## Sprint 1 착수 전 처리 목록 (2차 검증분)

1. **`docs/05-infra-stack.md` 7장에 Flyway 3줄 + 표 행 + 스키마 관리 방침 추가** (E-1). PR 전 권장
2. **PostgreSQL 대상 Flyway + `validate` 검증 경로 구축** (B). Account 엔티티 착수 전. Testcontainers 권장
3. **마이그레이션 README에 out-of-order 방침 명시** (D). 첫 마이그레이션 작성 전
4. `BATCH_*` 마이그레이션은 Spring Batch 배포본 `schema-postgresql.sql`을 옮겨 쓴다는 방침 기록 (C). Sprint 6 전
5. 문서 사소 정정 3건 — `budgetdb`(E-3), 코드에프 행(E-4), `baseline-on-migrate` 주석(E-5)
