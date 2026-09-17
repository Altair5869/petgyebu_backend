# Sprint 0 입력 정리 (인프라 골격)

- 작성: 2026-09-15
- 근거 문서: `docs/06-sprint-plan.md` Sprint 0, `docs/05-infra-stack.md` (확정 스택 2장·의존성 7장)
- 범위 한정: 외부 계정·승인이 필요한 항목은 이번 실행에서 제외한다.

## 이번 실행에 포함하는 항목

| # | 항목 | 근거 |
|---|---|---|
| 1 | Spring Boot 프로젝트 골격을 `05-infra-stack.md` 7장 의존성 목록과 일치시키기 | Sprint 0 "Spring Boot 프로젝트 초기화" |
| 2 | 헬스체크 엔드포인트 | Sprint 0 "헬스체크 엔드포인트" |
| 3 | Dockerfile | Sprint 0 "Dockerfile 작성" |
| 4 | Cloud Run 배포 스크립트 (`--no-cpu-throttling --min-instances=1` 고정) | Sprint 0 "Cloud Run 배포 스크립트" |
| 5 | CI에 `gcloud run services describe`로 위 플래그 검증 스텝 추가 | Sprint 0 "CI에 플래그 검증 스텝 추가" |
| 6 | QueryDSL(OpenFeign 포크) Gradle `annotationProcessor` 설정 검증 | `05-infra-stack.md` 6장 Action Item |
| 7 | `easycodef-java` SDK의 JDK 25 호환성 검증 | `05-infra-stack.md` 6장 Action Item |
| 8 | `resilience4j-spring-boot4`, `shedlock-provider-redis-spring`, `firebase-admin` 최신 버전 확인 | `05-infra-stack.md` 6장 Action Item |

## 이번 실행에서 제외하는 항목 (선행 조건 미충족)

| 항목 | 사유 |
|---|---|
| 코드에프 데모 서비스 신청 | 외부 프로세스. 사용자가 직접 접수 |
| GCP 프로젝트 생성, IAM 롤 부여 | GCP 계정·결제 설정 필요 |
| Cloud SQL 프로비저닝 | 위와 동일 |
| Upstash Redis 프로비저닝, Lettuce 커넥션 테스트 | Upstash 계정 필요 |
| User 엔티티, 카카오 소셜 로그인 API, 토큰 정책, 동의 기록, 회원 탈퇴 | 카카오 개발자 애플리케이션 등록과 약관 문안이 선행 조건 (Q3·Q5·Q6) |

## 현재 저장소 상태 (착수 전 확인 결과)

- 프로젝트명 `telo`, 루트 패키지 `com.petgyebu.telo`, Spring Boot 4.0.8, Java 25 toolchain, Gradle 9.7.1
- 소스는 Initializr 기본 골격만 존재: `TeloApplication.java`, `TeloApplicationTests.java`
- 설정 파일은 `application.yaml` / `application-sandbox.yaml` / `application-demo.yaml` 3개. 코드에프 프로필 분리는 이미 반영돼 있다
- Dockerfile, 배포 스크립트, CI 설정 파일 없음

### `build.gradle`과 `05-infra-stack.md` 7장의 차이

**누락된 의존성 (문서에는 있으나 build.gradle에 없음)**

- `io.github.resilience4j:resilience4j-spring-boot4`
- `net.javacrumbs.shedlock:shedlock-spring`, `shedlock-provider-redis-spring`
- `io.github.openfeign.querydsl:querydsl-jpa`, `querydsl-apt` (+ `jakarta.persistence-api`, `jakarta.annotation-api` annotationProcessor)
- `spring-boot-starter-aop`

**문서에 없으나 build.gradle에 있는 의존성 — 판단 필요**

- `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0` — API 문서화용. 유지해도 무방하나 인프라 문서에 기록이 없다
- `org.springframework.ai:spring-ai-starter-model-anthropic` + `spring-ai-bom` — **이 프로젝트 기획에 LLM 사용 계획이 없다.** 자동 분류는 가맹점명 키워드 룰 테이블 방식으로 확정됐다(R-CCOEWW 결정 1). 사용자 확인 후 제거 여부 결정
- `spring-boot-devtools` — 로컬 개발 편의용. Cloud Run 배포 이미지에는 포함되지 않도록 확인 필요

## 완료 기준

`docs/06-sprint-plan.md` Sprint 0의 완료 기준은 "카카오 또는 애플로 로그인해서 Cloud Run 배포된 API를 호출하면 응답이 온다"이다. 이번 실행은 인증과 클라우드 프로비저닝을 제외하므로 **스프린트 완료 판정이 아니라 부분 진행**이다.

이번 실행의 검증 가능한 성공 기준:

1. `./gradlew build`가 통과한다
2. 애플리케이션이 외부 인프라(Cloud SQL·Upstash) 없이 로컬에서 기동되고 헬스체크 엔드포인트가 200을 반환한다
3. QueryDSL Q클래스가 실제로 생성된다 (샘플 엔티티로 확인)
4. `easycodef-java`가 JDK 25에서 로드·호출된다 (최소 클래스 로딩과 `EasyCodefUtil.encryptRSA()` 동작 확인)
5. Dockerfile로 이미지가 빌드되고 컨테이너에서 헬스체크가 200을 반환한다
6. 배포 스크립트와 CI 검증 스텝에 `--no-cpu-throttling`·`--min-instances=1`이 명시돼 있다

## 브랜치

`docs/07-branch-strategy.md` 기준으로 F-ID가 없는 인프라 작업이므로 `chore/` 접두사를 쓴다.

- `chore/sprint0-infra-skeleton`
