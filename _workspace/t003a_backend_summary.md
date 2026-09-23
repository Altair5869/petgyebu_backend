# T-003a 구현 요약 — 액세스 토큰 발급·검증

작성: 2026-09-23. 브랜치 `feature/F-QJXRMD-token-access`. 대상 기능 **F-QJXRMD**.
입력 명세: `_workspace/t025_00_input.md` 1장. **2장 이하(T-025)는 손대지 않았다.**

## 1. 항목별 결과

- [완료] `TokenService.issueAccessToken(User)` — `src/main/java/com/petgyebu/telo/auth/TokenService.java` — HS256, 만료 30분, 클레임 `sub`=`users.id`만. HTTP 엔드포인트는 만들지 않았다(T-002 미착수라 진입점 없음)
- [완료] 액세스 토큰 검증 필터 — `src/main/java/com/petgyebu/telo/auth/AccessTokenAuthenticationFilter.java` — `OncePerRequestFilter`, `Authorization: Bearer` 파싱 → `SecurityContext`에 `Long userId`를 principal로 설정
- [완료] 필터 등록 — `src/main/java/com/petgyebu/telo/config/SecurityConfig.java` — `/actuator/health` permitAll과 401 엔트리포인트는 그대로
- [완료] `Clock` 빈 — `src/main/java/com/petgyebu/telo/config/TimeConfig.java` — `AppZone.clock()`(KST)
- [완료] 서명 키 설정 주입 — `src/main/resources/application.yaml`, `application-local.yaml`, `build.gradle`
- [완료] 종단 테스트 5개 — `src/test/java/com/petgyebu/telo/auth/AccessTokenAuthenticationTest.java`
- [완료] 트러블슈팅 기록 25번 — `docs/11-troubleshooting-log.md`
- [보류] 리프레시 토큰 발급·회전·재사용 감지·전 세션 무효화 — **T-003b**. 폐기 상태 저장소로 쓸 Redis가 미프로비저닝(백로그 B-REDIS)이라 입력 명세가 미룬 범위다. 이번 작업에 흔적을 남기지 않았다

## 2. 내린 결정과 근거

**서명 키에 기본값을 두지 않는다.** base `application.yaml`은 `${TELO_ACCESS_TOKEN_SECRET}`만 두어 주입이 없으면 기동이 실패한다. 기본값을 두면 운영에서 주입을 빠뜨렸을 때 저장소에 공개된 키로 토큰을 찍어내며 정상처럼 동작한다. 키 길이가 256비트 미만이면 `Keys.hmacShaKeyFor`가 기동 시점에 예외를 던지므로 약한 키도 부팅에서 걸린다.
키 값이 사는 곳은 세 군데이고 각각 역할이 다르다 — 운영/스테이징은 환경변수, 로컬 실행(`local` 프로필)은 `application-local.yaml`의 더미 값, 테스트는 `build.gradle` `test` 블록의 환경변수. 뒤 둘은 저장소에 그대로 들어가므로 공개된 값으로 취급한다.

**필터에서 직접 401을 쓰지 않는다.** 토큰이 없거나 틀리면 컨텍스트를 비운 채 체인을 계속 태우고, 인가 단계가 보호된 경로에 대해서만 401을 낸다. 이렇게 해야 `/actuator/health` 같은 permitAll 경로가 이상한 헤더 하나로 막히지 않는다. 사유(만료/서명 불일치)를 응답으로 구분하지 않는 것은 공격자에게 힌트를 주지 않기 위해서다.

**필터를 빈으로 만들지 않았다.** `Filter` 타입 빈은 Boot가 서블릿 컨테이너 필터로도 자동 등록해 시큐리티 체인 밖 요청에까지 걸린다. `SecurityConfig` 안에서 직접 생성해 `UsernamePasswordAuthenticationFilter` 앞에 넣었다.

**클레임을 `sub` 하나로 제한했다.** 토큰은 30분간 무효화할 수 없어서 안에 든 값은 그동안 낡은 채로 돌아다닌다. 인증 주체는 `Long userId`이고 컨트롤러는 `@AuthenticationPrincipal Long userId`로 받는다 — 경로에 userId를 두지 않는다는 입력 명세 3장의 관례와 같은 선택이다.

**jjwt 파서에도 주입받은 `Clock`을 넘겼다.** 파서 기본 시계는 `System.currentTimeMillis()`라 그냥 두면 고정 시계를 앞당겨도 만료가 재현되지 않는다. 발급과 만료 판정이 같은 시계를 본다.

**만료 30분은 상수로 뒀다**(`TokenService.ACCESS_TOKEN_TTL`). 설정으로 빼지 않은 것은 F-QJXRMD rules가 정한 값이라 환경별로 달라질 이유가 없어서다.

**테스트 전용 컨트롤러는 테스트 소스에만 뒀다.** `@TestConfiguration` 안의 `/__test__/me`다. 검증을 위해 실제 API 표면을 늘리지 않는다.

## 3. 테스트 결과

`AccessTokenAuthenticationTest` 5개 — 모의 인증(`@WithMockUser`)을 쓰지 않고 `TokenService`로 진짜 토큰을 발급해 `Authorization` 헤더로 보낸다.

| 시나리오 | 기대 | 결과 |
|---|---|---|
| 유효 토큰 | 200, 응답 `userId` == 발급 시 `sub` | 통과 |
| 토큰 없음 | 401 | 통과 |
| 서명이 다른 토큰 | 401 | 통과 |
| 만료 후(고정 시계 +30분 1초) | 401 | 통과 |
| 만료 직전(+29분 59초) | 200 | 통과 |
| `/actuator/health` 토큰 없이 | 200 | 통과 |

만료는 **양방향으로 봤다.** 거부만 확인하면 "항상 401"인 구현도 통과하기 때문에 만료 1초 전 통과를 같이 단언한다.

**음성 대조(negative control).** `SecurityConfig`의 `addFilterBefore` 한 줄을 지우고 돌리면 5개 중 2개(유효 토큰, 만료)가 실패한다. 되돌리면 다시 통과한다. 테스트가 필터 등록에 실제로 의존한다는 뜻이다.

**전체 빌드.** `./gradlew build` → `BUILD SUCCESSFUL`, 152 tests / 0 failed / 0 skipped (Testcontainers 포함).
테스트는 UTC로 돌지만(`build.gradle`) 시각 계산은 전부 주입받은 KST `Clock`을 통하므로 기본 존에 의존하는 곳이 없다.

## 4. 작업 중 깨진 것 (기록 완료)

새 서명 키 설정을 기본값 없이 두자 프로필을 지정하지 않는 Testcontainers 테스트 25개가 컨텍스트 기동 실패로 한꺼번에 깨졌다. `@Service`인 `TokenService`는 토큰을 쓰지 않는 테스트의 기동에도 끼어든다. `build.gradle` `test` 블록에 환경변수를 넣어 해결했고, base 설정의 기동 실패 성질은 그대로 뒀다. 진단·해결·양방향 검증은 `docs/11-troubleshooting-log.md` 25번에 있다.

## 5. 남긴 질문 (리더 확인 필요)

1. **`docs/10-task-backlog.md`의 T-003 행을 T-003a/T-003b로 쪼갤지.** 입력 명세가 둘로 나눴지만 백로그 문서는 아직 T-003 한 행이다. 이번 범위에 문서 갱신이 포함되는지 지시가 없어 손대지 않았다.
2. **토큰 만료 사유를 클라이언트에 구분해 줄지.** 지금은 만료도 서명 오류도 똑같이 빈 본문 401이다. 클라이언트가 "재로그인"과 "리프레시로 재발급"을 가르려면 구분이 필요할 수 있는데, 그 분기는 리프레시가 생기는 T-003b에서 정하는 편이 맞다고 보고 미뤘다.
3. **에러 응답 형식.** 입력 명세 3장은 `ProblemDetail`을 관례로 정했지만 그건 T-025 범위이고, 인증 실패 401은 시큐리티 엔트리포인트가 본문 없이 낸다. 401도 `ProblemDetail`로 맞출지는 T-025에서 `@RestControllerAdvice`를 만들 때 함께 정하는 게 자연스럽다.
