# 인프라 스택 결정 문서

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱
- 최종 갱신: 2026-09-15 (Sprint 0 인프라 착수 결과 반영 — 프로젝트 메타데이터 정정, 7장 의존성 오류 2건 수정, Action Item 3건 해소)
- 결정 상태: **전체 확정** (컴퓨트/DB 벤더, 언어/프레임워크 버전 포함 미확정 항목 없음)
- 관련 문서: `01-prd.md`, `02-requirements-features.md`, `03-user-flow.md`, `04-review-log.md`

## 0. 결정 과정 요약

AWS → NHN Cloud/Vultr(국내 클라우드) → Cloudflare/Supabase 부분 검토 → **Google Cloud**로 최종 확정. 각 단계에서 배제한 이유는 4장에 정리했다. 언어/프레임워크는 Java 21+Spring Boot 3.3.x로 시작했다가, Spring Boot 3.x 라인 전체가 2026년 6월 30일 OSS 지원 종료된 것을 확인하고 **Java 25+Spring Boot 4.0.x+Spring Framework 7.0.x**로 갱신했다(3.1절 참고).

## 1. 전체 아키텍처 개요

```
[Cloudflare CDN/WAF] ── 무료 티어, DDoS 방어 + API 도메인 앞단
        │
        ├─→ [Vercel] ── Next.js 웹 클라이언트 (React + lottie-react)
        │
        └─→ [로드밸런서] → [컴퓨트: Google Cloud Run, asia-northeast3 서울]
                                  │  Spring Boot 4.0.x (Java 25, Spring Framework 7.0.x)
                                  │  Spring Batch (n70→n71→n72 원자적 배치)
                                  │  Spring Security + JWT
                                  │  Resilience4j (재시도/서킷브레이커)
                                  │  easycodef-java SDK (코드에프 연동)
                                  │
                                  ├─→ [Cloud SQL for PostgreSQL 16, asia-northeast3]
                                  │      표준 프로토콜, VPC 커넥터 없이 네이티브 연동
                                  │      JSONB+GIN, SELECT FOR UPDATE, 윈도우함수
                                  │
                                  ├─→ [Upstash Redis, ap-northeast-1 도쿄]
                                  │      ShedLock 분산락 / 분당 rate-limit / 1시간 캐시
                                  │      코드에프 2-way 인증 세션 임시 저장(TTL)
                                  │      (서울 리전 미지원 확인됨 → 도쿄로 대체)
                                  │
                                  ├─→ [Cloud Storage + Cloudflare CDN]
                                  │      Lottie 에셋(12개: 캐릭터 2종×상태 6단계)
                                  │
                                  └─→ [FCM] 강한경고/예산초과 푸시 (기간당 1회 한정)
                                       동일 GCP 프로젝트 산하, 별도 서비스 계정 키 불필요

[모바일: Flutter] ── iOS/Android 공용, lottie 패키지로 동일 에셋 렌더링
```

## 2. 레이어별 확정 스택

| 레이어 | 선택 | 상태 |
|---|---|---|
| 백엔드 언어/프레임워크 | **Java 25 (LTS) + Spring Boot 4.0.x + Spring Framework 7.0.x** | ✅ 확정 (2026-09-05, Java 21+Boot 3.3.x에서 변경) |
| 빌드 도구 | **Gradle - Groovy** (wrapper `gradlew` 사용) | ✅ 확정 (2026-09-05, Maven에서 변경 — 팀 숙련도 우선) |
| 패키징 | **Jar** | ✅ 확정 (Cloud Run 컨테이너 실행 구조상 War 불필요) |
| 설정 파일 형식 | **YAML** (`application.yml`, profile별 분리) | ✅ 확정 |
| 배치 처리 | Spring Batch (Spring Boot 4.0 BOM 관리 버전) | ✅ 확정 |
| 스케줄러 + 분산락 | Spring `@Scheduled` + ShedLock 7.x (Upstash Redis 백엔드) | ✅ 확정 |
| 재시도/서킷브레이커 | Resilience4j (`resilience4j-spring-boot4`) | ✅ 확정 |
| 코드에프 연동 | `io.codef.api:easycodef-java:1.0.6` (Maven Central) | ✅ 확정 |
| ORM/쿼리 | Spring Data JPA + QueryDSL(`io.github.openfeign.querydsl` 포크, 7.5) | ✅ 확정 (2026-09-05, 원본 `com.querydsl`에서 변경) |
| 인증 | Spring Security + JWT(`io.jsonwebtoken:jjwt-*`) | ✅ 확정 |
| 비동기 이벤트 | `@TransactionalEventListener(AFTER_COMMIT)` | ✅ 확정 (Kafka 배제) |
| **컴퓨트 호스팅** | **Google Cloud Run** (asia-northeast3, `--no-cpu-throttling --min-instances=1`) | ✅ 확정 |
| **데이터베이스** | **Cloud SQL for PostgreSQL 16** (asia-northeast3) | ✅ 확정 |
| **캐시·분산락 저장소** | **Upstash Redis** (ap-northeast-1 도쿄, 표준 TCP+REST) | ✅ 확정 |
| **오브젝트 스토리지** | **Cloud Storage** | ✅ 확정 |
| CDN/WAF (API·에셋 앞단) | Cloudflare (무료 티어) | ✅ 확정 |
| **웹 프론트엔드** | **Next.js on Vercel** | ✅ 확정 (2026-09-03) |
| **모바일** | **Flutter** | ✅ 확정 (2026-09-03) |
| 푸시 알림 | Firebase Cloud Messaging (`firebase-admin` Java SDK) | ✅ 확정 |

## 3. 레이어별 결정 근거

### 3.1 언어/프레임워크 — Java 25 + Spring Boot 4.0.x + Spring Framework 7.0.x

**변경 배경**: 초기엔 Java 21 + Spring Boot 3.3.x로 시작했으나, 검토 결과 **Spring Boot 3.x 라인 전체(3.5.x 포함)가 2026년 6월 30일부로 오픈소스 지원이 완전히 종료**됐음을 확인했다. 지금(2026-09-05) 새 프로젝트를 3.x로 시작하면 시작하는 날부터 무상 보안 패치가 없는 프레임워크 위에서 개발하는 셈이라, Spring Boot 4.0.x(Spring Framework 7.0.x 기반)로 확정했다. Java는 25(2025년 9월 출시 LTS, 2032년까지 지원)로 맞췄다 — Spring Boot 4.0의 공식 요구사항은 최소 Java 17·권장 21·전체 기능은 Java 25에서 지원된다.

**Java 25가 이 아키텍처에 실질적으로 주는 이점**: (1) 가상 스레드 피닝 버그 수정 — 코드에프 API 호출·Cloud SQL 쿼리·Upstash 호출처럼 I/O 대기가 많은 워크로드에 직접 도움. (2) Compact Object Headers로 메모리 사용량 감소 — Cloud Run `--memory=2Gi` 예산에 여유. (3) 개선된 AOT 캐싱 — 재배포·재시작 시 워밍업 속도 향상.

**빌드 도구는 Gradle - Groovy.** 초기엔 Maven으로 확정했었으나(QueryDSL 애노테이션 프로세서 레퍼런스가 Maven 쪽에 많다는 이유), 팀이 Gradle에 더 익숙하다는 실제 생산성 요인을 우선해 2026-09-05 Gradle-Groovy로 변경했다. Gradle-Kotlin은 여전히 배제 — 팀 기존 언어(Java/Python)에 추가 언어를 얹을 실익이 없다는 판단은 유효. **다만 QueryDSL의 애노테이션 프로세서 설정은 Gradle 쪽이 Maven보다 트러블슈팅 사례가 많고 까다롭다는 점이 확인됐다** — `annotationProcessor` 구성을 명시적으로 잡아야 하며, 아래 3.1절 QueryDSL 항목 참고.

**패키징은 Jar.** War는 외부 서블릿 컨테이너 배포용인데, Cloud Run은 도커 컨테이너로 `java -jar`를 직접 실행하는 구조라 Jar(내장 Tomcat)가 맞다.

**설정 파일은 YAML.** 코드에프 지원은행 목록(20개 배열), SANDBOX/DEMO 프로필 분리, 예산 임계값 6단계 등 계층·리스트 구조가 많아 YAML의 들여쓰기 표현이 유리하다. ⚠️ 은행 조직코드("0004" 등)는 반드시 따옴표로 감싸 문자열로 명시할 것 — YAML이 앞자리 0을 숫자로 해석해 날릴 수 있음.

**프로젝트 메타데이터** (2026-09-15 정정): Group `com.petgyebu`, Artifact `telo`, 루트 패키지 `com.petgyebu.telo`. Cloud Run 서비스명도 `telo`로 통일한다. 이전 문서에는 `com.budgetpet`/`budget-pet-api`로 적혀 있었으나 실제 저장소가 `com.petgyebu`/`telo`로 생성돼 있어 코드 기준으로 맞췄다.

**Spring Boot 4.0 전환에 따른 구체적 변경점**:
- Jakarta EE 11 베이스라인 (Jakarta Persistence 3.2, Servlet 6.1 등)
- 스타터 이름 변경: `spring-boot-starter-web` → `spring-boot-starter-webmvc`
- 테스트 애노테이션(`@WebMvcTest`, `@DataJpaTest`) import 경로 변경
- Resilience4j는 `resilience4j-spring-boot3`가 아니라 **`resilience4j-spring-boot4`**(Spring Boot 4 전용 애드온이 별도로 출시됨)

**⚠️ Sprint 0에서 반드시 검증할 것**:
- **QueryDSL — 원본이 아니라 포크를 쓴다.** 원 저장소(`com.querydsl:querydsl-jpa`)는 5.1.0에서 사실상 멈췄고, 커뮤니티 포크 **`io.github.openfeign.querydsl:querydsl-jpa:7.5`**가 릴리스를 이어가고 있음을 확인(추측이 아니라 Maven Central에서 직접 확인됨). Jakarta EE 11(Spring Boot 4.0)에는 이 포크를 쓴다. **Gradle에서 `annotationProcessor` 구성이 정상적으로 Q클래스를 생성하는지, classifier가 여전히 필요한지(포크가 네이티브 jakarta 패키지를 쓴다면 불필요할 수 있음)는 이번 조사로 확정 못 했다** — Sprint 0에서 실제 빌드로 확인. 문제가 있으면 Spring Data JPA `Specification` API나 jOOQ로 대체 검토.
- **`easycodef-java` SDK의 JDK 25 호환성** — 벤더가 JDK 25 대상으로 명시적으로 테스트했는지 확인된 바 없음(자바 하위호환성상 문제 가능성은 낮으나 미검증).

### 3.2 컴퓨트 — Google Cloud Run

```bash
gcloud run deploy telo \
  --image=asia-northeast3-docker.pkg.dev/PROJECT/repo/backend:latest \
  --region=asia-northeast3 \
  --no-cpu-throttling \
  --min-instances=1 \
  --max-instances=3 \
  --cpu=2 --memory=2Gi \
  --add-cloudsql-instances=PROJECT:asia-northeast3:telo-db
```

`--no-cpu-throttling`(요청 없어도 CPU 계속 할당)과 `--min-instances=1`(콜드스타트 없이 상시구동)을 함께 쓰면 ShedLock 백그라운드 스레드·Spring Batch 스케줄러가 죽지 않는 상시구동 프로세스처럼 동작한다. Google Cloud 공식 블로그가 이 기능의 활용 사례로 "Spring Boot 앱의 내장 스케줄링/백그라운드 기능을 옮기는 것"을 직접 예시로 든다.

**운영 리스크**: 이 플래그를 배포 스크립트나 `service.yaml`에 커밋해두고, CI 파이프라인에서 `gcloud run services describe`로 설정이 살아있는지 검증하는 스텝을 추가할 것. 수동 콘솔 배포로 이 설정이 조용히 리셋되면 스케줄러가 에러 로그 없이 멈출 수 있다.

**Cloud Run을 선택하고 Cloudflare Containers를 배제한 이유**: Cloudflare Containers는 유휴 시 슬립되는 구조(콜드스타트 2~3초)라 상시구동 요구와 안 맞고, 관리형 Postgres/Redis 자체가 없어 결국 제3자를 더 붙여야 한다. Cloud Run은 위 플래그로 상시구동을 흉내낼 수 있고, Cloud SQL과 VPC 없이 네이티브 연동된다.

### 3.3 데이터베이스 — Cloud SQL for PostgreSQL

- 표준 PostgreSQL 클라이언트-서버 프로토콜을 그대로 지원해 Supabase에서 걸렸던 "Transaction Pooler-Hibernate prepared statement 충돌" 문제가 없음.
- `--add-cloudsql-instances` 플래그로 **VPC 커넥터 없이 Cloud SQL Auth Proxy가 자동으로 암호화 터널을 생성** — 이게 AWS의 NAT Gateway 상시 비용 문제를 근본적으로 피해가는 지점. AWS ECS Fargate는 보통 프라이빗 서브넷+NAT Gateway 패턴(시간당+처리량 과금)을 쓰는데, Cloud Run은 애초에 VPC에 묶여있지 않은 서버리스 모델이라 이 비용이 발생하지 않는다.
- Spring Boot 연결 예시:
  ```yaml
  spring.datasource.url: jdbc:postgresql:///telodb?cloudSqlInstance=PROJECT:asia-northeast3:telo-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory
  ```
- 초기 티어: `db-custom-1-3840`(1 vCPU/3.75GB). 실제 부하 테스트는 배포 후 재검증 필요(미확정 항목, 6장 참고).
- HA(고가용성)는 초기엔 끄고 시작 — RDS Multi-AZ와 동일하게 비용이 약 2배가 되므로 MVP 단계엔 보류.

### 3.3.1 스키마 관리 — Flyway (2026-09-15 확정)

스키마 변경은 전부 Flyway 마이그레이션을 거친다. 운영 프로필의 `spring.jpa.hibernate.ddl-auto`는 `validate`로 고정해 Hibernate는 테이블을 만들지 않고 엔티티와 실제 스키마가 맞는지 검증만 한다. `update`를 쓰면 금융 거래 테이블이 배포 때마다 예고 없이 바뀔 수 있다.

| 프로필 | DB | 스키마 생성 주체 | Flyway |
|---|---|---|---|
| 운영·컨테이너 | PostgreSQL 16 | Flyway | 활성, `ddl-auto: validate` |
| `local` | H2 | Hibernate | 비활성, `ddl-auto: create-drop` |

**마이그레이션의 대상 DBMS는 PostgreSQL 16 하나다.** JSONB·GIN 인덱스·윈도우 함수 같은 PostgreSQL 전용 문법을 제약 없이 쓰기 때문에 H2에서는 돌릴 수 없고, 그래서 `local`에서는 Flyway를 끄고 Hibernate가 스키마를 만든다.

마이그레이션 파일 이름은 `V{YYYYMMDDHHmm}__{snake_case_설명}.sql` 형식을 쓴다. 순번 대신 타임스탬프를 쓰는 이유는 기능마다 브랜치를 따로 파고 병렬 진행하는 구간이 있어 순번이 충돌하기 때문이다(`docs/07-branch-strategy.md`). 상세 규칙은 `src/main/resources/db/migration/README.md` 참고.

Spring Batch 메타 테이블(`BATCH_*`)도 Flyway로 관리한다(`spring.batch.jdbc.initialize-schema: never`). DDL은 직접 작성하지 말고 Spring Batch 배포본에 포함된 `schema-postgresql.sql`을 마이그레이션 파일로 옮겨 쓴다.

**Sprint 1 착수 전에 처리할 것** (2026-09-15 QA 지적):

1. ~~CI에서 Flyway와 `validate`가 한 번도 실행되지 않는다.~~ **✅ 2026-09-17 해소.** `PostgresMigrationTest`(Testcontainers + PostgreSQL 16)를 추가해 운영 프로필을 실제로 검증한다. 마이그레이션 없이 엔티티만 추가하면 이 테스트가 `Schema validation: missing table`로 실패하는 것을 확인했다. 같은 상태에서 `local` 프로필 테스트는 통과하므로, 두 테스트를 모두 유지해야 의미가 있다.
2. ~~out-of-order 마이그레이션 방침이 없다.~~ **✅ 2026-09-17 해소.** 아래 "마이그레이션 순서 규칙" 참고.

### 마이그레이션 순서 규칙 (2026-09-17 확정)

`spring.flyway.out-of-order`는 `false`로 둔다(기본값이지만 의도를 분명히 하려고 명시했다). 이미 적용된 것보다 낮은 버전이 나중에 들어오면 기동을 거부한다.

**원칙: 병합 전에 재타임스탬프한다.** 브랜치를 오래 들고 있었다면 병합 직전에 마이그레이션 파일명을 현재 시각으로 다시 짓는다.

```
V$(TZ=Asia/Seoul date +%Y%m%d%H%M)__설명.sql
```

**CI가 이를 강제한다.** `scripts/check-migration-order.sh`가 PR에서 새로 추가된 마이그레이션의 버전이 기준 브랜치의 최대 버전보다 큰지 검사하고, 작으면 빌드를 실패시킨다. 파일명 형식 위반도 함께 잡는다.

**왜 정적 검사인가**: 이 실패는 빈 DB에서 절대 재현되지 않는다. 새 DB는 어떤 순서로 만들어졌든 오름차순으로 적용하므로 `PostgresMigrationTest`도 통과한다. 운영처럼 이미 마이그레이션이 적용된 DB에서만 드러난다. DB 없이 파일명만으로 판정할 수 있어 검사가 가볍다.

### 3.4 캐시/분산락 — Upstash Redis (도쿄 리전)

- 표준 Redis TCP 프로토콜을 지원해 ShedLock·Lettuce 클라이언트가 코드 변경 없이 그대로 연결됨.
- **서울(ap-northeast-2) 리전은 Upstash 공식 리전 목록에 없음을 확인**했다. 가장 가까운 대안인 도쿄(`ap-northeast-1`)로 프로비저닝한다. 서울-도쿄 간 지연시간은 배치락·rate-limit 체크 같은 백엔드 내부 통신에 한정돼 사용자 체감 영향은 낮은 것으로 판단.
- GCP Memorystore for Redis도 검토했으나 배제: (1) 무료 티어가 없고 프로비저닝 용량 기준 상시 과금이라, 우리처럼 사용량이 낮고 간헐적인 패턴(하루 2회 배치락, 분당 rate-limit, 1시간 캐시)에서 실사용 사례상 월 $35가 청구된 반면 Upstash는 동일 사용량에서 $0이었던 사례가 확인됨. (2) Cloud Run에서 접근하려면 Serverless VPC Access 커넥터가 추가로 필요해 그 자체가 상시 비용이 됨(Cloud SQL과 달리 네이티브 연동 없음).
- 코드에프 2-way 추가인증 세션값(`jobIndex`/`threadIndex`/`jti`/`twoWayTimestamp`)도 이 위에 TTL과 함께 저장.

### 3.5 스토리지 — Cloud Storage + Cloudflare CDN

Lottie 에셋(12개)은 Cloud Storage 버킷에 두고, GCP 자체 CDN(Cloud CDN)은 별도로 붙이지 않는다. API 앞단에 이미 확정한 Cloudflare CDN/WAF를 버킷 앞에도 그대로 프록시로 씌운다(버킷을 퍼블릭 origin으로 두고 Cloudflare에서 커스텀 도메인 연결) — 캐싱 계층 중복과 비용 중복을 피하기 위함.

### 3.6 IAM/FCM 통합

Cloud Run 서비스 계정에 `roles/cloudsql.client`, `roles/storage.objectAdmin`(범위 좁혀서) 부여. FCM 발송은 별도 서비스 계정 키 파일 없이 Application Default Credentials로 인증 — Firebase 프로젝트가 같은 GCP 프로젝트 산하라 `firebase-admin` SDK가 GCP 메타데이터 서버에서 자동으로 인증을 가져간다. AWS였다면 FCM용 서비스 계정 키 JSON을 Secrets Manager에 별도 관리해야 했는데, 이 절차가 사라진다.

### 3.7 프론트엔드 — Vercel(웹) / Flutter(모바일)

2026-09-03 팀 확정. Vercel Functions는 Java를 지원하지 않고 실행시간 제한이 있어 백엔드 API 서버로는 부적합하지만, Next.js 웹 클라이언트 호스팅에는 최적. Lottie 애니메이션을 iOS/Android/웹에서 동일 에셋으로 공용 렌더링한다는 F-GGIDHG 전제(캐릭터 2종×상태 6단계 = 12개 에셋)를 살리기 위해, 모바일은 Flutter의 `lottie` 패키지, 웹은 `lottie-react`를 사용한다.

## 4. 검토했으나 배제한 대안과 이유

| 대안 | 배제/제한 사유 |
|---|---|
| AWS 전체 | 비용 예측 어려움(NAT Gateway 시간당+처리량 과금, RDS Multi-AZ, 데이터 전송료) |
| NHN Cloud | 원가 자체는 AWS 대비 메리트 크지 않음(실사용 후기 확인). 스타트업 크레딧(즉시 1,000만원~최대 5,400만원) 의존 전제였는데, GCP의 시드~시리즈A 크레딧(최대 $200,000~$350,000)이 규모상 더 큼 |
| Vultr | 서울 DC·SLA는 확인했으나, GCP 대비 FCM 생태계 시너지·크레딧 규모에서 밀림 |
| Cloudflare Containers(컴퓨트) | sleep/cold-start 구조, 관리형 Postgres/Redis 부재 |
| Supabase(1차 DB 후보) | 컴퓨트 미제공(별도 호스팅 필요), Transaction Pooler-Hibernate 충돌, 단일 AZ |
| Fly.io | 서울 리전 미지원(커뮤니티 요청이 위시리스트로 남아있음 확인) |
| Vercel(백엔드) | Java 미지원, 실행시간 제한으로 상시 프로세스 불가 |
| Oracle Cloud Free Tier | 서울/춘천 ARM 용량 소진 이슈, 7일 CPU 20% 미만 시 리소스 회수 정책, SLA 부재 — 프로토타입 전용으로만 유지 |
| GCP Memorystore for Redis | 무료 티어 없음, 상시 과금, VPC 커넥터 추가 필요 — Upstash 유지 |
| Kafka | 소비자가 이벤트마다 1개뿐이라 과한 인프라 — `@TransactionalEventListener(AFTER_COMMIT)`로 대체 |
| MySQL / NoSQL | JSONB+GIN, 윈도우 함수, 락 예측성, 정합성 규칙(F-IWBASY·F-RNECMQ)에서 PostgreSQL이 우위 |

**AWS vs GCP 최종 비교의 핵심 근거**: Cloud Run은 VPC에 묶여있지 않은 서버리스 모델이라 AWS의 NAT Gateway 상시 비용 문제가 구조적으로 발생하지 않고, Cloud SQL도 VPC 커넥터 없이 네이티브 연동된다. FCM이 이미 GCP 생태계라 IAM·결제가 통합된다. 크레딧도 자체자금 기준 AWS $1,000 대비 GCP $2,000, 상한 기준 AWS $100,000 대비 GCP $200,000~$350,000로 더 크다.

## 5. 계좌 연동 전략과 인프라의 접점

v1은 코드에프 단독 연동으로 확정됐다(`04-review-log.md` 2026-09-03 "계좌 연동 전략 재검토" 참고). 카카오뱅크·토스뱅크는 미지원 상태로 출시하며, 법인 설립 완료 + 정성적 수요 신호 확인 시 금융결제원 오픈뱅킹 조회 전용 API 병행을 재검토한다. 오픈뱅킹을 나중에 추가하더라도 위 인프라 구성(Cloud Run, Cloud SQL)은 그대로 유지되며, 코드에프 연동 코드 옆에 오픈뱅킹 연동 모듈을 추가하는 형태가 될 것으로 예상된다 — 인프라 재설계는 불필요.

## 6. 남은 확인 항목 / Action Items

- [ ] 코드에프 데모 서비스 신청 (2-way 추가인증 흐름은 SANDBOX에서 테스트 불가, DEMO 서비스 필수)
- [x] **QueryDSL(OpenFeign 포크) Gradle `annotationProcessor` 설정 검증** — 2026-09-15 완료. **classifier가 필수다**(7장 참고). Q클래스 생성은 확인했고, 실쿼리 실행은 엔티티가 생기는 Sprint 1에서 확인한다
- [x] **`easycodef-java` SDK의 JDK 25 호환성 검증** — 2026-09-15 완료. `EasyCodefUtil.encryptRSA()` RSA 왕복 테스트 통과. 클래스 파일 메이저 버전 52(Java 8)라 런타임 문제 없음. 네트워크 경로(토큰 발급, 2-way 추가인증)는 자격증명·데모 승인 후 확인
- [x] **`resilience4j-spring-boot4`, `shedlock-provider-redis-spring`, `firebase-admin` 버전 확인** — 2026-09-15 완료. 7장 표에 확정 버전을 반영했다
- [ ] Google for Startups Cloud Program 신청 — 법인 설립 후 진행(현재는 개인/팀 단계라 신청 요건인 "비즈니스 이메일이 스타트업 공개 도메인과 일치" 충족 어려움). 그 전까지는 GCP 신규가입 무료체험($300, 90일)으로 개발 진행
- [ ] Cloud SQL 티어(`db-custom-1-3840`)가 실제 부하 테스트 후에도 충분한지 확인 (배포 후에만 확인 가능한 항목)
- [ ] 코드에프 실제 지원 은행 목록 최신본 재확인 (2026-09-03 목록 확보했으나 카카오뱅크·토스뱅크 최신 지원 여부는 지속 모니터링)
- [ ] **Cloud Run 첫 배포 시 `scripts/verify-cloud-run-flags.sh`의 실동작 확인** (2026-09-15 추가) — 애노테이션 키 `run.googleapis.com/cpu-throttling`·`autoscaling.knative.dev/minScale`은 Knative 규약 기준 추정이며 실물 출력과 대조하지 못했다. 또 현재 스크립트는 `spec.template.metadata.annotations`(희망 상태)만 보고 실제 트래픽을 받는 리비전을 보지 않아, 배포가 부분 실패해도 통과할 수 있다. `status.latestReadyRevisionName` 대조로 보강해야 한다
- [ ] **GCP 연결 후 CI의 "조용한 성공" 제거** (2026-09-15 추가) — 현재 CI는 GCP 자격증명이 없으면 배포·플래그 검증 스텝을 전부 스킵하고 잡을 성공으로 끝낸다. 지금은 의도된 동작이지만, 시크릿이 만료되면 배포와 검증이 사라진 채 CI는 계속 초록색으로 남는다. 자격증명 판별 스텝의 `else` 분기를 `exit 1`로 바꾸거나 `vars.DEPLOY_ENABLED` 구조로 전환해야 한다

## 7. 백엔드 의존성 목록 (Gradle - Groovy)

start.spring.io에서 Java 25 + Spring Boot 4.0.x + **Gradle - Groovy**로 프로젝트 생성 시 체크할 스타터와, 생성 후 `build.gradle`에 직접 추가할 항목을 구분했다.

**Initializr에서 체크**: Spring Web(내부적으로 `spring-boot-starter-webmvc`), Spring Data JPA, Spring Security, Spring Batch, Validation, Spring Data Redis, Spring Boot Actuator, AOP는 **`spring-boot-starter-aspectj`**를 쓴다(아래 ⚠️ 참고)

**직접 추가**:

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-aspectj'

    implementation 'io.github.resilience4j:resilience4j-spring-boot4:2.4.0'
    implementation 'net.javacrumbs.shedlock:shedlock-spring:7.9.0'
    implementation 'net.javacrumbs.shedlock:shedlock-provider-redis-spring:7.9.0'
    implementation 'io.codef.api:easycodef-java:1.0.6'

    implementation 'io.jsonwebtoken:jjwt-api:0.13.0'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.13.0'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.13.0'

    runtimeOnly 'org.postgresql:postgresql'
    implementation 'com.google.cloud.sql:postgres-socket-factory:1.28.1'

    implementation 'org.springframework.boot:spring-boot-flyway'  // 자동설정 모듈, 생략 금지
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'org.flywaydb:flyway-database-postgresql'
    implementation 'com.google.firebase:firebase-admin:9.10.0'

    implementation 'io.github.openfeign.querydsl:querydsl-jpa:7.5'
    annotationProcessor 'io.github.openfeign.querydsl:querydsl-apt:7.5:jakarta'  // classifier 필수
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'

    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

| 의존성 | 좌표/버전 | 비고 |
|---|---|---|
| QueryDSL | `io.github.openfeign.querydsl:querydsl-jpa:7.5`, `querydsl-apt:7.5:jakarta` | ⚠️ **원본 `com.querydsl`이 아니라 이 포크 사용** — 원본은 5.1.0에서 사실상 멈춤. ⚠️ **`:jakarta` classifier가 필수다** — 2026-09-15 검증 결과, classifier 없는 `querydsl-apt-7.5.jar`에는 `META-INF/services/javax.annotation.processing.Processor`가 없어 Q클래스가 하나도 생성되지 않는다. 그런데 빌드는 정상 종료(exit 0)하므로 **조용히 실패한다**. `:jpa`와 `:jakarta` classifier jar는 md5가 동일한 같은 파일이며, 포크가 네이티브 jakarta라 `:jakarta`를 쓰는 것이 의도가 분명하다 |
| Resilience4j | `io.github.resilience4j:resilience4j-spring-boot4:2.4.0` | ✅ 2026-09-15 확인. 2.4.0이 유일 버전 |
| ShedLock | `net.javacrumbs.shedlock:shedlock-spring:7.9.0`, `shedlock-provider-redis-spring:7.9.0` | Boot 버전별 아티팩트 분리 없음. 2026-09-15 기준 최신은 7.10.1이나 문서 확정값 7.9.0 유지 |
| 코드에프 SDK | `io.codef.api:easycodef-java:1.0.6` | ✅ 2026-09-15 JDK 25 호환성 검증 완료 — `EasyCodefUtil.encryptRSA()` RSA 왕복 통과, 클래스 파일 메이저 버전 52(Java 8). 네트워크 경로는 데모 승인 후 확인 |
| JWT | `io.jsonwebtoken:jjwt-api:0.13.0`, `jjwt-impl:0.13.0`(runtime), `jjwt-jackson:0.13.0`(runtime) | 2025-08 릴리스, 확인 완료 |
| PostgreSQL 드라이버 | `org.postgresql:postgresql`(runtime, 버전 생략) | Spring Boot BOM 관리 대상 |
| Cloud SQL 소켓 팩토리 | `com.google.cloud.sql:postgres-socket-factory:1.28.1` | ⚠️ Spring Boot BOM 비관리 대상 — 버전 필수 명시(이전에 이 누락으로 에러 발생 이력 있음) |
| FCM | `com.google.firebase:firebase-admin:9.10.0` | ✅ 2026-09-15 확인. **버전 생략이 Sprint 0 착수 시점 빌드 실패의 직접 원인이었다** — BOM 비관리 대상이다 |
| Lombok(선택) | `org.projectlombok:lombok`(compileOnly + annotationProcessor) | 엔티티 보일러플레이트 감소, 팀 취향 |
| AOP | `org.springframework.boot:spring-boot-starter-aspectj` | ⚠️ **`spring-boot-starter-aop`는 Spring Boot 4.0에 존재하지 않는다** — Maven Central 마지막 버전이 `4.0.0-M2`이고 `spring-boot-dependencies:4.0.8` BOM에도 없다. 2026-09-15에 실제로 넣어보니 `Could not find org.springframework.boot:spring-boot-starter-aop:.`로 빌드가 깨졌다 |
| Flyway | `org.springframework.boot:spring-boot-flyway`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`(runtime) | ⚠️ **`spring-boot-flyway`를 빠뜨리면 Flyway가 조용히 아무것도 하지 않는다** — Spring Boot 4.0이 자동설정을 모듈로 쪼개면서 `FlywayAutoConfiguration`이 `spring-boot-autoconfigure`에서 빠지고 `spring-boot-flyway` 아티팩트로 옮겨갔다. 2026-09-15 실측: 이 한 줄만 빼면 Flyway 로그 0줄, 경고 0건, 기동 성공, **DB에 테이블 0개**. `spring.flyway.enabled: true`도 무시된다. 버전은 Boot 4.0 BOM이 관리한다(현재 11.14.1) |
| API 문서 | `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0` | 2026-09-15 추가 기록. 저장소 생성 시점부터 포함돼 있었고 유지하기로 결정했다. 운영 프로필에서 비활성화할지는 미결 |

**BOM 관리 여부를 반드시 구분할 것**: Spring Boot 스타터 계열과 PostgreSQL 드라이버는 Spring Boot 4.0의 `dependency-management`(Gradle 플러그인이 자동 적용)가 버전을 관리해 생략 가능하다. 반면 QueryDSL·ShedLock·jjwt·easycodef-java·Cloud SQL 소켓 팩토리·Resilience4j·Firebase Admin은 **전부 Spring Boot BOM 밖의 서드파티 라이브러리라 버전 생략 시 해석 실패한다** — Gradle로 바꿔도 이 규칙 자체는 동일하게 적용된다. 2026-09-15 Sprint 0 착수 시점에 `firebase-admin`의 버전 생략으로 실제 빌드가 깨졌다.
