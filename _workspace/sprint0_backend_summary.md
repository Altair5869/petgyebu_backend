# Sprint 0 백엔드 구현 요약 (인프라 골격)

- 작성: 2026-09-15
- 브랜치: `chore/sprint0-infra-skeleton` (원격 push·PR 없음)
- 입력 문서: `_workspace/sprint0_00_input.md`, `docs/05-infra-stack.md`, `docs/06-sprint-plan.md`

## 항목별 결과

- [완료] build.gradle을 문서와 일치시키고 빌드 통과: `build.gradle` — `firebase-admin` 버전 명시로 해석 실패를 없앴고, 누락돼 있던 Resilience4j·ShedLock·QueryDSL·AOP 의존성을 넣었다. 단 AOP 스타터는 문서에 적힌 이름 그대로는 존재하지 않아 Boot 4.0의 새 이름을 썼다(아래 참고).
- [완료] 헬스체크 엔드포인트: `src/main/java/com/petgyebu/telo/config/SecurityConfig.java` — 컨트롤러를 새로 만들지 않고 Actuator 기본 `/actuator/health`를 인증 예외로 열었다.
- [완료] 외부 인프라 없이 로컬 기동: `src/main/resources/application-local.yaml`, `build.gradle`, `src/test/java/com/petgyebu/telo/TeloApplicationTests.java`
- [완료] Dockerfile: `Dockerfile`
- [완료] Cloud Run 배포 스크립트: `scripts/deploy-cloud-run.sh`
- [완료] CI 플래그 검증 스텝: `.github/workflows/ci.yml`, `scripts/verify-cloud-run-flags.sh`
- [완료] QueryDSL annotationProcessor 검증: Q클래스 생성 확인. classifier가 필요하다는 사실을 확인했고, 검증용 샘플 엔티티는 확인 후 제거했다.
- [완료] easycodef-java JDK 25 호환성 검증: `src/test/java/com/petgyebu/telo/codef/EasyCodefUtilJdk25Test.java`
- [완료] 문서 외 의존성 3건 처리(리더 확인 후): `build.gradle`, `Dockerfile` — spring-ai 제거, spring-boot-devtools 제거, springdoc 유지
- [완료] QA 지적 FIX 3건 반영: `src/main/java/com/petgyebu/telo/config/SecurityConfig.java`, `src/main/java/com/petgyebu/telo/TeloApplication.java`, `Dockerfile`, `.dockerignore` — 12장 참고

## 1. 의존성 정리와 빌드

`./gradlew build`가 실패하던 직접 원인은 `com.google.firebase:firebase-admin`에 버전이 없었던 것이다. 이 라이브러리는 Spring Boot BOM 관리 대상이 아니라 버전을 생략하면 해석 자체가 되지 않는다. Maven Central의 `maven-metadata.xml`을 직접 조회해 최신 릴리스 `9.10.0`을 확인하고 명시했다.

같은 방식으로 문서 6장에 "정확한 최신 버전 미확인"으로 남아 있던 나머지도 확인했다.

| 의존성 | 확인된 최신 버전 | 이번에 적용한 버전 | 비고 |
|---|---|---|---|
| `io.github.resilience4j:resilience4j-spring-boot4` | 2.4.0 | 2.4.0 | Maven Central에 올라온 유일한 버전이다. 아티팩트 자체는 실재한다 |
| `net.javacrumbs.shedlock:shedlock-spring` | 7.10.1 | 7.9.0 | 문서 7장이 7.9.0으로 고정해 둔 값을 그대로 썼다 |
| `net.javacrumbs.shedlock:shedlock-provider-redis-spring` | 7.10.1 | 7.9.0 | 위와 같다 |
| `com.google.firebase:firebase-admin` | 9.10.0 | 9.10.0 | 미명시로 빌드가 깨지던 항목 |
| `io.github.openfeign.querydsl:querydsl-jpa`/`querydsl-apt` | 7.6 | 7.5 | 문서 2장·7장이 7.5로 확정해 둔 값을 유지했다 |

문서가 버전을 확정해 둔 항목(ShedLock 7.9.0, QueryDSL 7.5)은 더 최신이 있더라도 임의로 올리지 않았다. 올릴지 여부는 리더가 판단할 몫으로 남긴다.

**문서와 실제가 달랐던 지점 하나**: `docs/05-infra-stack.md` 7장은 `spring-boot-starter-aop`를 추가하라고 적고 있지만, 이 아티팩트는 Spring Boot 4.0에서 사라졌다. Maven Central 기준 마지막 버전이 `4.0.0-M2`이고, `spring-boot-dependencies:4.0.8` BOM에도 들어 있지 않다. BOM에는 대신 `spring-boot-starter-aspectj`가 있어 그쪽을 썼다. 실제로 `spring-boot-starter-aop`를 넣은 상태로는 `Could not find org.springframework.boot:spring-boot-starter-aop:.` 로 빌드가 깨지는 것을 확인했다. 문서 7장 수정이 필요하다.

검증:

```
$ ./gradlew build --console=plain
...
BUILD SUCCESSFUL in 10s
7 actionable tasks: 7 executed
```

테스트 결과 XML 두 건 모두 `tests="1" skipped="0" failures="0" errors="0"`였다.

## 2. 헬스체크

Actuator가 이미 의존성에 있으므로 컨트롤러를 새로 만들지 않았다. 남은 문제는 `spring-boot-starter-security`가 기본적으로 모든 요청에 인증을 걸어 `/actuator/health`가 401이 된다는 점뿐이었다. 그래서 `SecurityConfig`에 필터 체인 하나만 두고 `/actuator/health`와 그 하위 경로만 `permitAll`, 나머지는 `authenticated`로 뒀다. 실제 인증 규칙은 소셜 로그인 작업에서 채운다. (이 체인의 초기 버전에 있던 HTTP Basic·CSRF·세션 문제는 QA 지적으로 12장에서 정리했다.)

## 3. 외부 인프라 없이 로컬 기동 — 택한 방식과 근거

`local` 프로필에서 인메모리 H2를 쓰는 방식을 택했다. 자동설정 제외(`exclude = DataSourceAutoConfiguration.class`) 방식은 배제했는데, 그렇게 하면 JPA·Spring Batch가 함께 죽어서 "컨텍스트가 뜬다"는 사실 말고는 아무것도 검증하지 못하기 때문이다. H2를 쓰면 Hibernate가 실제로 엔티티 매핑을 검증하고 Spring Batch도 자기 스키마를 만들면서 뜨므로, 로컬·CI에서 의미 있는 기동 검증이 된다.

H2는 `developmentOnly` + `testRuntimeOnly`로 선언했다. `runtimeOnly`로 두면 H2가 `bootJar`에 들어가고, 운영에서 `spring.datasource.url` 설정이 빠졌을 때 Spring Boot의 임베디드 DB 자동 감지가 조용히 H2로 기동해 버린다. `developmentOnly`는 `bootJar`에서 제외되므로 이 사고가 구조적으로 막힌다. 실제로 빌드된 jar 안에 `h2-*.jar`가 없는 것을 확인했다(devtools도 마찬가지였고, 이후 10장에서 아예 제거했다).

`application-local.yaml`에서는 Redis 헬스 인디케이터를 껐다(`management.health.redis.enabled: false`). Upstash가 아직 없는 상태에서 Actuator가 Redis를 핑하면 헬스가 DOWN이 되어 503이 나오기 때문이다. Upstash 연결이 붙는 시점에 이 설정을 지워야 한다는 주석을 파일에 남겼다.

`TeloApplicationTests`에는 `@ActiveProfiles("local")`을 붙였다. CI에서 `./gradlew build`만 돌려도 외부 인프라 없이 컨텍스트 로딩이 검증된다.

검증:

```
$ SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
...
Started TeloApplication in 3.297 seconds

$ curl -s -o /tmp/health.json -w '%{http_code}' http://localhost:8080/actuator/health
200
{"groups":["liveness","readiness"],"status":"UP"}
```

인증 헤더 없이 200과 `UP`을 받았다.

## 4. Dockerfile

`Dockerfile`은 2단계다. 빌드 단계는 `eclipse-temurin:25-jdk`에서 빌드 스크립트를 먼저 복사해 의존성 해석 결과를 레이어 캐시에 남기고, 그 다음 `src`를 복사해 `bootJar -x test`를 돌린다. 런타임 단계는 `eclipse-temurin:25-jre`에 jar 하나만 올리고 비루트 사용자로 실행한다.

H2는 `developmentOnly`라 `bootJar`에 들어가지 않는다. 이는 런타임 이미지의 파일 목록이 아니라 jar 내용으로 확인했다(위 3번). `spring-boot-devtools`도 같은 구조라 원래부터 제외됐고, 10장에서 의존성 자체를 제거했다.

Cloud Run이 주입하는 `PORT`를 `-Dserver.port=${PORT}`로 받도록 엔트리포인트를 잡았다.

파일 소유권은 `COPY --chown`으로 넘긴다. 초기 버전은 `RUN chown -R`을 썼는데 이미지가 jar 크기만큼 부풀었다. 자세한 것은 12장이다. 빌드 컨텍스트 축소를 위한 `.dockerignore`도 12장에서 추가했다.

## 5. Cloud Run 배포 스크립트

`scripts/deploy-cloud-run.sh`. 리전은 `asia-northeast3` 기본값이고, `--no-cpu-throttling`과 `--min-instances=1`은 환경변수로 덮을 수 없게 배열에 고정했다. 배포 직후 같은 스크립트 안에서 `scripts/verify-cloud-run-flags.sh`를 한 번 더 호출해 자기 검증을 한다. 왜 빼면 안 되는지(ShedLock 스레드·Batch 스케줄러가 조용히 멈춘다)를 파일 상단 주석에 적어 뒀다.

## 6. CI 플래그 검증

`.github/workflows/ci.yml`에 `build` 잡과 `deploy` 잡을 뒀다. `deploy`는 `main` push에서만 돈다.

GCP 자격증명이 없을 때 스킵되게 하는 부분은 조금 우회가 필요했다. GitHub Actions에서 `secrets` 컨텍스트는 잡 레벨 `if`에서 쓸 수 없어서, 첫 스텝에서 자격증명 유무를 판별해 출력 변수로 내보내고 이후 스텝들이 그 값을 보게 했다. 검증 스텝만은 `if: steps.deploy.outcome == 'success'`로 걸었다. 배포 스텝이 성공한 경우에는 무조건 실행된다는 뜻이다.

검증 로직은 `scripts/verify-cloud-run-flags.sh`에 따로 뺐다. `gcloud run services describe --format=json` 결과에서 두 애노테이션을 본다.

- `run.googleapis.com/cpu-throttling` 이 `"false"` 인가 (`--no-cpu-throttling`)
- `autoscaling.knative.dev/minScale` 이 `"1"` 인가 (`--min-instances=1`)

gcloud를 호출할 수 없으므로 가짜 `gcloud`를 PATH에 넣고 파싱 로직만 양쪽 경로로 확인했다.

```
=== OK case
[OK] budget-pet-api: cpu-throttling=false, minScale=1
exit=0
=== BAD case
[FAIL] budget-pet-api: Cloud Run 상시구동 플래그가 적용돼 있지 않다.
  - --no-cpu-throttling 미적용: run.googleapis.com/cpu-throttling=None (기대값 'false')
  - --min-instances 미적용: autoscaling.knative.dev/minScale='0' (기대값 '1')
exit=1
```

실제 Cloud Run 응답 JSON으로는 검증하지 못했다. GCP 계정이 없어 `gcloud run services describe`의 진짜 출력 형태를 확인할 수 없었고, 애노테이션 키 이름은 Knative 규약에 근거한 것이다. GCP 프로비저닝 후 첫 배포에서 이 스크립트가 통과하는지 반드시 확인해야 한다.

## 7. QueryDSL annotationProcessor 검증 (Action Item 결론)

**classifier가 필요하다.** 문서 7장 스니펫대로 `annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:7.5'`만 넣으면 빌드는 성공하지만 Q클래스가 하나도 생기지 않는다. 실제로 확인했다.

원인은 jar 안의 프로세서 등록 파일이다.

| jar | `META-INF/services/javax.annotation.processing.Processor` |
|---|---|
| `querydsl-apt-7.5.jar` (classifier 없음) | 없음 |
| `querydsl-apt-7.5-jpa.jar` | `com.querydsl.apt.jpa.JPAAnnotationProcessor` |
| `querydsl-apt-7.5-jakarta.jar` | `com.querydsl.apt.jpa.JPAAnnotationProcessor` |
| `querydsl-apt-7.5-general.jar` | `com.querydsl.apt.QuerydslAnnotationProcessor` |

classifier 없는 jar에는 서비스 등록 파일이 아예 없어 애노테이션 프로세서로 동작하지 않는다. `jpa`와 `jakarta` classifier jar는 md5가 동일한 같은 파일이고, 둘 다 내부적으로 `jakarta/persistence/*`를 참조한다. 즉 포크가 이미 네이티브 jakarta라서 `javax` 대응 jar가 따로 없고, `jakarta`는 `jpa`의 별칭이다. 의도를 분명히 하려고 `jakarta`를 골랐다.

```groovy
annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:7.5:jakarta'
```

검증은 `@Entity` 하나짜리 샘플(`QuerydslProbeEntity`)로 했다.

```
$ ./gradlew compileJava --console=plain
BUILD SUCCESSFUL
$ find build -name 'Q*.java'
build/generated/sources/annotationProcessor/java/main/com/petgyebu/telo/QQuerydslProbeEntity.java
```

생성된 파일은 `@Generated("com.querydsl.codegen.DefaultEntitySerializer")`가 붙은 `EntityPathBase<QuerydslProbeEntity>` 하위 클래스로, 정상적인 Q타입이다.

**샘플 엔티티는 제거했다.** 근거는 두 가지다. 첫째, 이 엔티티가 남아 있으면 Hibernate가 실제 스키마에 `querydsl_probe_entity` 테이블을 만들려 들어 운영 DB에 의미 없는 테이블이 생긴다. 둘째, Sprint 1의 Account 엔티티가 들어오는 즉시 같은 경로가 다시 검증되므로 프로브를 상주시킬 필요가 없다. 대신 Q클래스가 안 생기는 회귀가 발생했을 때 원인을 바로 찾을 수 있게, classifier가 필요한 이유를 `build.gradle` 주석과 이 문서에 남겼다.

주의할 점 하나. QueryDSL은 `@Entity`가 붙은 클래스가 하나도 없으면 Q클래스를 만들지 않으므로, Sprint 1에서 첫 엔티티를 추가하기 전까지는 "Q클래스가 없다"가 정상 상태다. 설정이 깨진 것으로 오인하지 말 것.

쿼리 실행까지는 확인하지 못했다. 리포지터리와 실제 데이터가 있어야 하는데 엔티티를 만들지 않는 것이 이번 범위이기 때문이다. Sprint 1에서 Account 조회로 확인해야 한다.

## 8. easycodef-java JDK 25 호환성 (Action Item 결론)

**동작한다.** `src/test/java/com/petgyebu/telo/codef/EasyCodefUtilJdk25Test`에서 2048비트 RSA 키쌍을 만들어 공개키를 base64로 넘기고 `EasyCodefUtil.encryptRSA()`로 암호화한 뒤, 개인키로 복호화해 원문이 그대로 나오는지까지 확인했다. 통과했다.

부수적으로 확인한 사실: `easycodef-java:1.0.6`의 클래스 파일 메이저 버전은 52(Java 8)다. JDK 25 런타임이 읽는 데 문제가 없는 범위다. 시그니처는 `encryptRSA(String, String)`이고 `io.codef.api.EasyCodefUtil`에 있다(문서에 적힌 대로).

네트워크가 필요한 부분(`EasyCodef.requestProduct()`, 토큰 발급, 2-way 추가인증)은 검증하지 못했다. 코드에프 자격증명과 데모 서비스 승인이 없어서다. JDK 25에서 문제가 생긴다면 암호화보다는 SDK 내부의 HTTP 클라이언트 쪽일 가능성이 높으므로, 자격증명이 생기면 SANDBOX 호출 한 번으로 다시 확인해야 한다.

## 9. Docker 이미지 빌드와 컨테이너 헬스체크

`docker build -t telo:sprint0 .` 이 성공했다(최종 이미지 736MB, 재빌드 후 image id `126d9ce08c2b`). 베이스 이미지를 처음 받는 데 20분 가까이 걸렸는데 이는 네트워크 문제일 뿐 빌드 정의와는 무관하다.

런타임 이미지에 개발용 의존성이 섞이지 않았는지는 이미지 안에서 직접 확인했다.

```
$ docker run --rm --entrypoint sh telo:sprint0 -c \
    "grep -ac 'spring-boot-devtools' /app/app.jar; grep -ac 'BOOT-INF/lib/h2-' /app/app.jar"
0
0
```

둘 다 0건이다. `id` 출력도 `uid=999(app)`로 비루트 실행을 확인했다. 10장의 spring-ai·devtools 제거를 반영해 이미지를 다시 빌드한 뒤 같은 확인을 반복했고, `spring-boot-devtools`·`BOOT-INF/lib/h2-`·`spring-ai` 세 패턴 모두 0건이었다.

컨테이너 헬스체크는 postgres:16-alpine 사이드카를 같은 도커 네트워크에 띄우고 실제 DB에 붙여서 확인했다.

```
$ docker run -d --name telo-app --network telo-test-net -p 18080:8080 \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://telo-pg:5432/telo ... telo:sprint0
$ curl -s -o /tmp/chealth.json -w '%{http_code}' http://localhost:18080/actuator/health
200
{"groups":["liveness","readiness"],"status":"UP"}
```

컨테이너 로그에 `Started TeloApplication in 4.442 seconds`가 찍혔다. 이 컨테이너 헬스체크는 10장의 의존성 제거를 반영한 이미지로 한 번 더 돌려 동일하게 200/UP을 확인했다. 이 테스트에서는 Redis가 없으므로 `MANAGEMENT_HEALTH_REDIS_ENABLED=false`를, Batch 메타 테이블 생성을 위해 `SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=always`를 넣었다. **운영 프로필에서 Spring Batch 메타 테이블을 어떻게 만들 것인지는 아직 정해지지 않았다.** 기본값 `embedded`로는 PostgreSQL에 테이블이 생기지 않으므로, Cloud SQL 붙이는 시점에 결정해야 한다(Sprint 1 이후 과제).

## 10. 문서 외 의존성 3건 처리 (spring-ai 제거 / devtools 제거 / springdoc 유지)

리더 확인을 거쳐 세 건의 판단이 모두 끝났다.

### spring-ai 제거

리더 확인 결과 Initializr에서 잘못 체크된 것으로 판명돼 `build.gradle`에서 제거했다. 지운 것은 세 군데다.

- `implementation 'org.springframework.ai:spring-ai-starter-model-anthropic'`
- `ext { set('springAiVersion', "2.0.1") }` — 이 변수를 쓰는 곳이 spring-ai BOM 하나뿐이었다
- `dependencyManagement { imports { mavenBom "org.springframework.ai:spring-ai-bom:..." } }` — 다른 BOM import가 없어 블록째로 제거했다. Spring Boot 자체 BOM은 `io.spring.dependency-management` 플러그인이 자동 적용하므로 이 블록이 없어도 버전 관리가 유지된다

근거: 이 프로젝트 기획에 LLM 사용 계획이 없다. 자동 카테고리 분류는 가맹점명 키워드 룰 테이블 방식으로 확정돼 있다(R-CCOEWW 결정 1).

제거 후 재검증:

```
$ ./gradlew clean build --console=plain
BUILD SUCCESSFUL in 7s
8 actionable tasks: 8 executed
```

테스트 두 건 모두 `failures="0" errors="0"`. 빌드된 jar에서 `spring-ai` 문자열 0건. jar 크기는 177MB에서 138MB로 줄었다.

### spring-boot-devtools 제거

리더 확인에 따라 `developmentOnly 'org.springframework.boot:spring-boot-devtools'` 한 줄을 지웠다. devtools가 없어졌으므로 Dockerfile 런타임 단계에 달아 둔 "devtools는 bootJar에 포함되지 않는다"는 주석도 h2만 언급하도록 정리했다. Dockerfile 자체에 devtools를 걸러내기 위한 별도 처리는 애초에 넣지 않았다(`developmentOnly` 선언만으로 `bootJar`에서 빠지는 구조였다).

`developmentOnly` 구성 자체는 Spring Boot Gradle 플러그인이 제공하는 것이라 devtools가 없어도 그대로 살아 있다. H2를 `developmentOnly`로 둔 3장의 구조는 영향을 받지 않는다. 제거 후 재검증했다.

```
$ ./gradlew clean build --console=plain
BUILD SUCCESSFUL in 8s
$ SPRING_PROFILES_ACTIVE=local ./gradlew bootRun &
$ curl -s -o /tmp/h2.json -w '%{http_code}' http://localhost:8080/actuator/health
200
{"groups":["liveness","readiness"],"status":"UP"}
```

부수 효과가 하나 있다. 로컬 개발에서 자동 재시작과 라이브 리로드가 사라진다. 지금까지 `bootRun` 로그에 보이던 `restartedMain` 스레드도 없어진다.

### springdoc-openapi 유지

`org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0`은 API 문서화 용도로 계속 쓰기로 해서 그대로 뒀다.

## 11. 발견했지만 손대지 않은 것

- **`docs/05-infra-stack.md` 7장 의존성 목록에 springdoc이 없다.** 유지하기로 결정된 이상 문서와 실제 `build.gradle`이 어긋난 상태다. 인프라 문서 반영은 리더가 별도로 처리한다.
- springdoc이 켜져 있어 기동 로그에서 `/v3/api-docs`와 `/swagger-ui.html`이 기본 활성화됐다는 경고가 나온다. 운영 프로필에서는 꺼야 하지만 이번 범위가 아니라 두었다.
- `docs/05-infra-stack.md` 7장의 `spring-boot-starter-aop` 표기와 QueryDSL classifier 미기재 두 군데는 실제와 다르다. 문서 수정은 리더 판단에 맡긴다.
- 문서 3.1절의 프로젝트 메타데이터는 Group `com.budgetpet` / Artifact `budget-pet-api`인데, 실제 저장소는 `com.petgyebu` / `telo`다. 배포 스크립트의 서비스명 기본값은 문서 쪽(`budget-pet-api`)에 맞췄다. 어느 쪽으로 통일할지 결정이 필요하다.

## 12. QA 지적 반영 (FIX 3건)

`_workspace/sprint0_qa_report.md`에서 FIX 3건이 나왔다. REDO는 없었다. 세 건 모두 반영했다.

### FIX 1 — HTTP Basic 제거

`SecurityConfig`에 있던 `.httpBasic(basic -> {})`을 지웠다. 이게 켜져 있으면 Spring Boot가 자동 생성하는 `user` 계정이 살아 있는 자격증명이 되고, 그 비밀번호가 표준출력에 찍혀 Cloud Run에서는 Cloud Logging에 그대로 남는다. Basic 인증을 쓸 소비자가 이 프로젝트에 없다.

다만 `.httpBasic()`만 지워서는 부족했다. 실제로 돌려 보니 `Using generated security password:` 로그가 그대로 남았다. `UserDetailsServiceAutoConfiguration`은 HTTP Basic 설정과 무관하게 `UserDetailsService` 빈이 없으면 무조건 인메모리 계정을 만들기 때문이다. 자격증명으로 인증에 성공하지는 않게 됐지만(401 확인) 비밀번호가 로그에 남는 문제는 그대로였다.

그래서 `TeloApplication`에서 이 자동설정을 제외했다.

```java
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
```

Spring Boot 4.0에서 이 클래스의 위치는 `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`이다(`spring-boot-security-4.0.8.jar`에서 확인). 인증 방식이 무상태 JWT로 확정된 이상 인메모리 계정 자체가 불필요하므로 제외가 맞는 처리다.

### FIX 2 — CSRF 비활성화 + 세션 STATELESS

기본값(CSRF on, 세션 `IF_REQUIRED`)이라 인증되지 않은 요청에도 `JSESSIONID`가 발급되고 있었다. Q4에서 확정한 무상태 JWT(액세스 30분 / 리프레시 14일 회전)와 충돌한다. 체인에 두 줄을 넣었다.

```java
.csrf(csrf -> csrf.disable())
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

여기서 한 가지 부작용이 있었다. HTTP Basic을 빼면 인증 수단이 하나도 없는 상태가 되고, 그러면 Spring Security의 기본 엔트리포인트가 `Http403ForbiddenEntryPoint`라 보호된 경로가 401이 아니라 403을 낸다. QA가 요구한 401을 유지하려고 엔트리포인트를 명시했다.

```java
.exceptionHandling(handling -> handling
        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
```

이건 JWT 필터가 들어올 Sprint 1에서도 그대로 쓰는 구성이다.

### FIX 3 — Dockerfile `chown` 레이어 제거 + `.dockerignore` 추가

`RUN chown -R app:app /app`을 지우고 `COPY --from=build --chown=app:app ...` 형태로 바꿨다. `RUN chown -R`은 메타데이터만 바꾸는 것처럼 보이지만 OverlayFS에서는 변경된 파일 전체가 새 레이어에 복사돼, 145MB짜리 jar가 이미지에 두 번 저장되고 있었다.

`.dockerignore`도 새로 만들어 `.git`, `build`, `.gradle`, `.idea`, `_workspace`, `docs`, `*.md`를 뺐다.

### 검증 출력

빌드:

```
$ ./gradlew build --console=plain
BUILD SUCCESSFUL in 5s
테스트 2건 모두 failures="0" errors="0"
```

기동 로그에서 자동 생성 비밀번호가 사라졌다.

```
$ grep -c "Using generated security password" /tmp/bootrun4.log
0
```

경로별 상태코드(인증 헤더 없음):

```
/actuator/health             200
/actuator/health/liveness    200
/actuator                    401
/actuator/env                401
/v3/api-docs                 401
/swagger-ui/index.html       401
/foo                         401
```

세션 쿠키와 Basic 광고 헤더가 모두 사라졌다.

```
$ curl -sD - -o /dev/null http://localhost:8080/foo | grep -i 'set-cookie\|www-authenticate'
(없음)
```

FIX 1 적용 직후(자동설정 제외 전) 자동 생성 계정으로 인증을 시도했을 때도 401이 나왔다. 즉 Basic 경로 자체가 닫혔다.

이미지 크기:

```
$ docker image inspect telo:sprint0 --format '{{.Size}}'     # 수정 전
672199479
$ docker image inspect telo:sprint0fix --format '{{.Size}}'  # 수정 후
527218787

$ docker history telo:sprint0fix --format '{{.Size}}\t{{.CreatedBy}}'
145MB   COPY --chown=app:app /workspace/build/libs/*…
4.71kB  RUN /bin/sh -c groupadd --system app && user…
```

672MB에서 527MB로 줄었다. QA가 예측한 수치와 일치한다. `chown` 레이어가 이력에서 사라졌다.

컨테이너 헬스체크(postgres 사이드카):

```
health HTTP 200
{"groups":["liveness","readiness"],"status":"UP"}
env HTTP 401
generated password 건수: 0
```

## 13. GCP 프로비저닝 시점에 함께 처리할 것 (지금 고치지 않음)

QA가 실재한다고 확인한 문제 두 건이다. 지금은 의도된 동작이라 손대지 않았지만, GCP를 붙이는 작업과 반드시 묶어서 처리해야 한다.

**CI가 자격증명이 없으면 조용히 초록색으로 끝난다.** `deploy` 잡의 조건은 `github.ref == 'refs/heads/main'`뿐이라, 자격증명이 없으면 `creds` 스텝이 `available=false`를 내보내고 이후 다섯 스텝이 전부 스킵되며 잡은 성공으로 끝난다. 프로비저닝 전인 지금은 이게 맞는 동작이다. 그러나 **GCP가 붙은 뒤에 시크릿이 지워지거나 만료되면 배포도 플래그 검증도 조용히 사라지는데 CI는 계속 초록색이다.** Sprint 0의 목적이 "플래그가 리셋되면 빌드가 깨지게 만드는 것"인데 정확히 반대로 동작하게 된다. 처리 방법은 `.github/workflows/ci.yml`의 `Check GCP credentials` 스텝에서 `else` 분기를 `exit 1`로 바꾸는 것이다. 또는 `vars.DEPLOY_ENABLED` 같은 저장소 변수로 명시적으로 켜고 끄는 구조로 바꾼다.

**검증 스크립트가 서비스의 희망 상태만 본다.** `scripts/verify-cloud-run-flags.sh`는 `spec.template.metadata.annotations`를 읽는데, 이건 다음 리비전에 적용될 템플릿이지 실제로 트래픽을 받는 리비전의 상태가 아니다. 배포가 부분적으로 실패해 새 리비전이 Ready가 되지 못하면, 템플릿은 올바른데 살아 있는 리비전은 플래그 없이 도는 상황을 통과시킬 수 있다. 처리 방법은 `status.latestReadyRevisionName`을 함께 확인하거나 그 리비전을 `gcloud run revisions describe`로 다시 조회해 애노테이션을 대조하는 것이다. 애노테이션 키 이름 자체가 아직 실물로 확인되지 않았으므로(아래 14장) 어차피 첫 배포에서 이 스크립트를 손봐야 한다.

## 14. 이번 실행에서 못 한 것

- Cloud Run 실제 배포와 `gcloud run services describe` 실출력 검증 (GCP 계정 없음)
- Upstash Redis 연결 검증 (계정 없음). `local` 프로필에서는 Redis 헬스 인디케이터를 꺼둔 상태다
- Cloud SQL 연결 검증 (프로비저닝 전)
- QueryDSL 쿼리 실행 검증 (엔티티가 Sprint 1 범위)
- 코드에프 실제 API 호출 (자격증명·데모 승인 없음)
- Sprint 0의 나머지 항목(User 엔티티, 소셜 로그인, 토큰 정책, 동의 기록, 탈퇴 API)은 입력 문서에서 이번 범위 밖으로 명시됐다. 따라서 이번 작업은 Sprint 0 완료 판정이 아니라 부분 진행이다.
