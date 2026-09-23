# T-003a QA 리포트 — 액세스 토큰 발급·검증

검증: 2026-09-23. 브랜치 `feature/F-QJXRMD-token-access`, 커밋 `5743384` (`b5e6c9a..HEAD`).
대상 기능 **F-QJXRMD**. 입력 명세 `_workspace/t025_00_input.md` 1장.

## 요약

| 판정 | 건수 |
|---|---|
| PASS | 8 |
| FIX | 3 |
| REDO | 0 |
| 미검증(후속 과제로 이월) | 1 |

FIX 세 건 중 **머지 전 반드시 고쳐야 하는 것은 QA-3(문서 숫자 어긋남)** 하나다. QA-1·QA-2는
심층방어·테스트 보강이며 T-003b로 미뤄도 기능은 성립한다.

**구현자 요약(`_workspace/t003a_backend_summary.md`)의 사실 주장은 직접 재현해 전부 확인했다.**
음성 대조(필터 등록 한 줄 제거 → 5개 중 2개 실패), fail-fast(환경변수 제거 → 컨텍스트 기동
실패), 실패 모드 11종을 실제 요청으로 돌렸다. 검증용 임시 테스트는 전부 제거했고 작업 트리는
깨끗하다(`git status --porcelain` 무출력).

---

## 검증 방법 — 실제로 만들어 보고 확인한 것

"그런 상태는 만들 수 없다"를 넘겨짚지 않기 위해 임시 프로브 테스트 두 개를 넣어 돌린 뒤
제거했다. 실측 결과:

| 만들어 본 요청 | 결과 | 기대 |
|---|---|---|
| `alg: none` 무서명 JWT | **401** | 401 ✅ |
| `sub`가 숫자가 아닌 토큰(우리 키로 정상 서명) | **401** | 401 ✅ |
| `sub` 클레임이 아예 없는 토큰(우리 키로 정상 서명) | **401** | 401 ✅ |
| `Authorization: Bearer ` (빈 토큰) | **401** (500 아님) | 401 ✅ |
| `Authorization: Bearer` (스킴만) | **401** | 401 ✅ |
| `Authorization: bearer <유효토큰>` (소문자 스킴) | **401** | 참고 — 아래 관찰 1 |
| 스킴 없이 raw 토큰만 | **401** | 401 ✅ |
| `Bearer not.a.jwt` 쓰레기 | **401** | 401 ✅ |
| 쓰레기 토큰을 달고 `/actuator/health` | **200** | 200 ✅ |
| 존재하지 않는 `userId=424242`가 담긴 유효 서명 토큰 | **200**, 본문 `{"userId":424242}` | 아래 QA-4 |
| 같은 키·다른 HMAC 알고리즘(HS384) | 로컬 32바이트 키에서는 **서명 자체가 거부**(키 길이 부족) | 아래 QA-1 |

추가로 **64바이트 키를 쓰는 운영 상황**을 직접 만들어 봤다.

```
PROBE2[HS256] parsed=777
PROBE2[HS384] parsed=777
PROBE2[HS512] parsed=777
```

`TokenService`가 HS256으로만 발급하는데 **HS384·HS512로 서명된 토큰도 그대로 통과한다.**
지금 키(32바이트)에서는 길이 제약이 우연히 막고 있을 뿐이다. → QA-1.

---

## 축 1 — 입력 검증 양방향

### 만료 판정 — PASS

- 검증 대상: `AccessTokenAuthenticationTest.expiredTokenIsRejected` (:119-131) vs 입력 명세 1장 성공 기준
- 사유: 거부(+30분 1초 → 401)와 통과(+29분 59초 → 200)를 **같은 테스트 안에서 양방향으로**
  단언한다. 거부만 있으면 "항상 401"인 구현도 통과하는데, 그 구멍이 막혀 있다.

### 서명 검증 / 토큰 부재 — PASS

- 검증 대상: `wrongSignatureIsRejected`(:103), `missingTokenIsRejected`(:96) vs
  `validTokenAuthenticates`(:82)
- 사유: 거부 단언 둘 다 **통과 케이스 짝(유효 토큰 200)** 을 가진다. 한쪽만 있는 단언은 없다.
  `validTokenAuthenticates`는 응답 `userId` 단언 전에 `tokenService.parseUserId(token)`을 따로
  확인해, 컨트롤러가 토큰이 아닌 다른 경로로 id를 얻었을 가능성까지 배제한다.

### 필터 등록 의존성 (음성 대조) — PASS

- 검증 대상: 구현자 요약 3장의 음성 대조 주장 vs 실측
- 사유: `SecurityConfig.java:41-42`의 `addFilterBefore`를 제거하고 재실행해 **정확히 2개**가
  깨지는 것을 직접 확인했다.

```
액세스 토큰 발급·검증 종단 > 유효한 토큰이면 200이고 ... FAILED
액세스 토큰 발급·검증 종단 > 만료 30분이 지나면 401 ... FAILED
```

되돌리면 다시 통과한다. **테스트가 필터 등록에 실제로 의존한다.** 요약의 주장이 사실이다.

---

## 축 2 — 토큰 검증의 빠진 실패 모드

### QA-1. 서명 알고리즘이 고정돼 있지 않다 — **FIX**

- 판정: **FIX**
- 검증 대상: `TokenService.java:80-86` (파서) vs `TokenService.java:66` (발급은 HS256 고정)
- 사유: `verifyWith(signingKey)`는 **JWT 헤더의 `alg`를 믿고** 그에 맞는 HMAC으로 검증한다.
  발급은 HS256만 하는데 검증은 HS384·HS512도 받는다. 위 PROBE2에서 64바이트 키로 실측해
  세 알고리즘 전부 `parsed=777`로 통과하는 것을 확인했다.
  지금 로컬·테스트 키가 32바이트라 HS384 서명이 길이 제약에 걸려 막히는 것은 **우연이고,
  운영 키를 권장대로 64바이트로 늘리는 순간 그 우연이 사라진다.**
  공격자가 키를 모르면 여전히 위조는 못 하므로 즉시 뚫리는 구멍은 아니다. 다만 "발급한
  형식이 아닌 토큰은 받지 않는다"는 것이 여기서 지켜지지 않는다.
- 수정 지시: `src/main/java/com/petgyebu/telo/auth/TokenService.java:80-86`.
  파서가 HS256만 받도록 고정한다. jjwt 0.13에서는 `parseSignedClaims`가 돌려주는
  `Jws<Claims>`의 헤더 알고리즘을 검사하는 방식이 가장 확실하다 —
  `Jws<Claims> jws = ...parseSignedClaims(token);` 뒤에
  `if (!Jwts.SIG.HS256.getId().equals(jws.getHeader().getAlgorithm())) { throw new MalformedJwtException(...); }`.
  그리고 이 검증을 **양방향으로** 증명하는 테스트를 넣는다 — 64바이트 키로 HS512 토큰을
  만들어 거부되는지, 같은 키의 HS256 토큰은 여전히 통과하는지.

### QA-2. 실측한 실패 모드 9종이 회귀 테스트로 남지 않았다 — **FIX**

- 판정: **FIX**
- 검증 대상: 위 실측 표 vs `AccessTokenAuthenticationTest`의 시나리오 5개
- 사유: `alg: none`, `sub` 비숫자, `sub` 부재, 빈 `Bearer`, 스킴 없는 raw 토큰 — 전부 **지금은
  올바르게 401이다.** 문제는 그 동작을 지키는 단언이 하나도 없다는 것이다.
  특히 두 갈래는 잃기 쉽다.
  (a) `TokenService.java:88-94`의 `NumberFormatException` → `MalformedJwtException` 변환.
      이걸 지우면 `NumberFormatException`이 필터의 `catch (JwtException | IllegalArgumentException)`에
      우연히 걸려 여전히 401이 되지만, `catch` 절이 바뀌는 순간 500이 된다.
  (b) 빈 토큰(`""`)은 `JwtException`이 아니라 `IllegalArgumentException`으로 나간다.
      `AccessTokenAuthenticationFilter.java:47`의 `catch`에서 `IllegalArgumentException`을
      빼면 즉시 500이 된다. 지금 그 한 단어를 지켜 주는 테스트가 없다.
- 수정 지시: `src/test/java/com/petgyebu/telo/auth/AccessTokenAuthenticationTest.java`에
  최소 세 건을 추가한다 — ①`alg: none` 토큰 → 401, ②`sub`가 숫자가 아닌 정상 서명 토큰 → 401,
  ③`Authorization: Bearer ` (빈 토큰) → 401(그리고 500이 아님). ③은 필터의 `catch` 절 회귀를
  막는 것이 목적이므로 상태 코드를 명시적으로 단언한다.

### 관찰 1 — `Bearer` 스킴 대소문자 (수정 불요)

`TokenService.java:103`의 `startsWith("Bearer ")`는 대소문자를 구분한다. RFC 7235는 인증 스킴을
대소문자 무관으로 정의하므로 `bearer <유효토큰>`을 보내는 클라이언트는 401을 받는다.
**실패 방향이 닫힘(fail-closed)이라 보안 문제는 아니다.** 클라이언트가 이 서비스의 앱 하나뿐이고
T-002가 붙을 때 헤더 형식을 함께 정하므로 지금 고칠 이유는 없다. 다만 T-002에서 클라이언트
구현과 맞출 때 이 제약을 기억해야 한다.

### QA-4. 존재하지 않는/탈퇴한 사용자의 토큰이 30분간 살아 있다 — **미검증(후속 이월)**

- 판정: **미검증** — PASS로 처리하지 않는다
- 검증 대상: `AccessTokenAuthenticationFilter.java:42-46` vs
  `docs/02-requirements-features.md` **F-ZPNVKT** action ("해당 사용자의 모든 세션을 무효화한다")
- 사유: 필터는 `sub`를 `Long`으로 바꿔 그대로 principal로 올린다. DB 조회가 없다. 실측에서
  존재하지 않는 `userId=424242`가 담긴 유효 서명 토큰이 **200과 함께 그 id를 컨트롤러까지
  전달**했다.
  T-003a 입력 명세의 범위는 "`userId` 해석"까지이므로 **이번 Task의 위반은 아니다.** 그러나
  F-ZPNVKT가 요구하는 "모든 세션 무효화"는 리프레시 폐기(T-003b)만으로는 완성되지 않는다 —
  액세스 토큰은 무상태라 탈퇴 후에도 최대 30분 유효하다. 이 구멍이 어디서 닫히는지 지금
  아무 문서에도 없다. 그대로 두면 아무도 모르는 채 남는다.
- 이월 지시(코드 수정 아님, 문서 등록):
  1. `docs/10-task-backlog.md`에 **T-003b의 완료 기준으로 "탈퇴 후 기존 액세스 토큰이 거부된다"를
     명시**하거나, 닫지 않기로 한다면 "최대 30분 잔존을 수용한다"를 근거와 함께 남긴다.
  2. **T-025에 즉시 영향이 있다.** 컨트롤러가 `findById(userId).orElseThrow()`를 쓰면 이 상태에서
     500이 난다. T-025 입력 명세 3장의 관례 표에 "인증 주체가 DB에 없을 때의 응답(401 권장)"을
     한 줄 추가할 것을 제안한다. 첫 API Task에서 정해야 나머지가 따라간다.

---

## 축 3 — 기존 동작 보존

### `/actuator/health` permitAll — PASS

- 검증 대상: `SecurityConfig.java:30` vs `AccessTokenAuthenticationTest.healthStaysOpen`(:134) +
  실측
- 사유: 토큰 없이 200, **그리고 쓰레기 토큰을 달고도 200**(실측)이다. 필터가 401을 직접 쓰지
  않고 컨텍스트를 비운 채 체인을 태우는 설계(`AccessTokenAuthenticationFilter.java:47-49`)가
  실제로 그 효과를 낸다. 설계 의도와 관측 동작이 일치한다.

### 401 엔트리포인트 / 세션 정책 — PASS

- 검증 대상: `SecurityConfig.java:33-37` vs `b5e6c9a`의 같은 파일
- 사유: `authorizeHttpRequests`, `csrf.disable()`, `STATELESS`, `HttpStatusEntryPoint(UNAUTHORIZED)`
  네 줄 전부 변경 없다. 이번 diff가 더한 것은 `addFilterBefore` 한 줄과 메서드 시그니처의
  `TokenService` 파라미터뿐이다. 실측에서 모든 거부 경로가 **403이 아닌 401**로 나왔다.
- 참고: 필터는 토큰이 없을 때 `clearContext()`를 부르지 않는다. `STATELESS`라
  `SecurityContextHolderFilter`가 요청 끝에 정리하므로 스레드 로컬 누수는 없다.

### 필터를 빈으로 만들지 않은 선택 — PASS

- 검증 대상: `SecurityConfig.java:41-42` 주석의 주장 vs Spring Boot 서블릿 필터 자동 등록 동작
- 사유: `Filter` 타입 빈이 컨테이너 필터로도 자동 등록돼 시큐리티 체인 밖까지 걸리는 것은
  맞다. 직접 생성해 체인에만 넣은 선택이 옳다. `UsernamePasswordAuthenticationFilter` 앞 배치도
  `SecurityContextHolderFilter` 뒤이므로 순서가 맞다.

---

## 축 4 — 시각 주입

### 주입 `Clock`이 발급·검증 양쪽을 덮는가 — PASS

- 판정: **PASS**
- 검증 대상: `TokenService.java:63`(발급), `:84`(파서) vs `AppZone.clock()` / `TimeConfig`
- 사유: 발급은 `clock.instant()`를, 파서는 `.clock(() -> Date.from(clock.instant()))`로 **같은
  주입 시계**를 본다. 파서에 시계를 안 넘기면 기본값이 `System.currentTimeMillis()`라 고정
  시계가 무시되는데 그 함정을 피했다.
  **양쪽을 다 덮는다는 것이 테스트로 증명된다**: 만료 테스트는 고정 시계만 옮기고 실제 시각은
  건드리지 않는다. 만약 발급이 시스템 시각을 썼다면 발급 시각이 고정 시계보다 앞서 있어
  `+30분 1초` 지점에서도 만료되지 않아 그 단언이 실패한다. 즉 통과 자체가 발급 경로의 시계
  주입에 대한 증거다.
- 참고(수정 불요): `ISSUED_AT`이 검증일과 같은 날짜로 하드코딩돼 있다. 시간이 갈수록 실제
  시각과의 간격이 벌어져 위 증거는 **약해지지 않고 강해진다.**

### 존 없는 시각 호출이 남아 있는가 — PASS

- 검증 대상: `src/main/java` 전체 grep vs `AppZone` 규칙
- 사유: `currentTimeMillis`, `Instant.now()`, `LocalDate(Time).now()`, `OffsetDateTime.now()`,
  `ZonedDateTime.now()`, `new Date()` — 실행 코드에 **한 건도 없다.** 검색에 걸린 세 줄은 전부
  "이걸 쓰지 마라"고 적은 Javadoc 주석이다(`TimeConfig.java:11`, `TokenService.java:82`,
  `AppZone.java:32`).
- 참고: `TimeConfig`가 `AppZone.clock()`을 빈으로 노출한 것은 T-025 관례 표의
  "`Clock`을 빈으로 주입받는다"를 선반영한 것이며, T-003a에 실제로 필요한 구성이라 과잉이 아니다.

---

## 축 5 — 불변 / 보안

### 서명 키가 저장소에 실키로 남아 있는가 — PASS

- 검증 대상: `application.yaml`, `application-local.yaml`, `build.gradle` vs 운영 주입 경로
- 사유: 세 군데의 역할이 분리돼 있고 저장소에 들어가는 둘은 명백한 더미다.
  - base `application.yaml`: `${TELO_ACCESS_TOKEN_SECRET}` — **기본값 없음**
  - `application-local.yaml`: `local-only-dummy-hs256-signing-key-32b+` (로컬 프로필 전용)
  - `build.gradle` test 블록: `test-only-dummy-hs256-signing-key-32b+` (환경변수)
  두 더미 값이 서로 다르고 둘 다 "dummy"를 이름에 달고 있어 실수로 운영에 복사될 여지가 낮다.
  `local` 프로필은 어디서도 기본 활성화되지 않는다(`spring.profiles.active` 설정 없음) —
  즉 프로필을 명시하지 않으면 base로 떨어져 환경변수를 요구한다.

### fail-fast가 실제로 동작하는가 — PASS (실측)

- 판정: **PASS**
- 검증 대상: `application.yaml`의 기본값 없는 플레이스홀더 vs 실제 기동
- 사유: **주장을 그대로 받지 않고 되돌려 확인했다.** `build.gradle`의
  `environment 'TELO_ACCESS_TOKEN_SECRET', ...` 줄을 제거하고 프로필 없는 테스트
  (`UserSchemaTest`)를 돌렸더니 전 테스트가 컨텍스트 기동 실패로 깨졌다.

```
Caused by: org.springframework.util.PlaceholderResolutionException at PlaceholderResolutionException.java:81
```

  줄을 되돌리면 다시 통과한다. **프로퍼티를 빼면 기동이 실패한다는 성질이 살아 있다.**
  키 길이 방어(`Keys.hmacShaKeyFor`가 256비트 미만에서 기동 예외)도 주석대로이며,
  실측 PROBE에서 312비트 키에 HS384를 요구했을 때 jjwt가 길이를 근거로 거부하는 것을 봤다.

### 클레임 최소화 / 401 본문 — PASS

- 검증 대상: `TokenService.java:60-67` vs 입력 명세 1장 "클레임: `sub` = `users.id`. 그 이상 넣지 않는다"
- 사유: `sub`, `iat`, `exp` 셋뿐이다. 실패 사유(만료/서명 불일치)를 응답으로 구분하지 않아
  열거 힌트를 주지 않는다. 실측에서 모든 거부가 **빈 본문 401**로 동일했다.
  구현자가 남긴 질문 2(만료/서명 구분)는 리프레시가 생기는 T-003b의 결정 사항이 맞다.

---

## 축 6 — 범위 준수

### T-003b / T-025 선반영 흔적 — PASS

- 검증 대상: `git diff b5e6c9a..HEAD --stat` (11파일) vs 입력 명세 1장 범위표
- 사유: 리프레시·회전·재사용 감지·Redis·세션 무효화 관련 코드가 **한 줄도 없다**. 예산
  컨트롤러·서비스·DTO·`@RestControllerAdvice`도 없다. 발급 HTTP 엔드포인트를 만들지 않은 것도
  명세대로다(T-002 미착수).

### 불필요한 추상화 — PASS

- 사유: 인터페이스·전략·팩토리 같은 층이 없다. `TokenService` 한 클래스 + 필터 한 클래스다.
  만료 30분을 설정으로 빼지 않고 `ACCESS_TOKEN_TTL` 상수로 둔 것도 명세가 정한 값이라 옳다.
- 참고(사소, 수정 불요): `ACCESS_TOKEN_TTL`(`TokenService.java:33`)과
  `extractBearerToken`(`:103`)이 package-private인데 테스트에서 둘 다 쓰지 않는다. `private`로
  좁힐 수 있으나 `extractBearerToken`은 같은 패키지의 필터가 쓰므로 현 가시성이 맞다.
  `ACCESS_TOKEN_TTL`만 `private`가 더 정확하다 — QA-2 테스트를 추가할 때 이 상수를 쓸 것이라면
  지금 그대로 두는 편이 낫다.

### 인프라 좌표 — PASS

- 검증 대상: `build.gradle:33-35` vs `docs/05-infra-stack.md:228-230, 258`
- 사유: `jjwt-api/impl/jackson` 전부 `0.13.0`으로 확정 좌표와 일치한다. 이번 diff는
  `build.gradle`에 **의존성을 추가하지 않았다** — 테스트용 환경변수 한 줄만 더했다.

---

## 축 7 — 테스트 전용 코드 누수

### `/__test__/me`와 `MutableClock` — PASS

- 판정: **PASS**
- 검증 대상: `AccessTokenAuthenticationTest`의 `@TestConfiguration` vs `src/main/java` 전체
- 사유: `/__test__/me` 컨트롤러와 `MutableClock` 둘 다 **테스트 소스의 중첩 static 클래스**이며
  프로덕션 소스에 대응물이 없다. `@TestConfiguration`은 그것을 선언한 테스트 클래스의 컨텍스트
  안에서만 등록되므로 다른 테스트의 시큐리티 경로에도 새지 않는다.
  `SecurityConfig`에는 `/__test__/**`를 여는 규칙이 없다 — `anyRequest().authenticated()`에
  그대로 걸리므로 **테스트 엔드포인트가 보호 경로로 검증된다.** 오히려 이 점이 검증을 강하게
  만든다.
  `MutableClock` 빈 이름을 `testClock`으로 두고 `@Primary`로 이긴 처리도 맞다 — `clock`으로
  두면 `TimeConfig`의 빈과 이름이 겹쳐 기동이 실패한다.

---

## 문서 — `docs/11-troubleshooting-log.md` 25번

### 항목 형식 — PASS

- 검증 대상: 25번 항목 vs CLAUDE.md "항목 형식" 6단계 + 기존 24개 항목 구조
- 사유: 증상(실제 실패 목록) → 진단(`activeProfiles = []`라는 결정적 단서와 스택트레이스) →
  원인 → 해결(대안을 왜 버렸는지 포함) → 검증 → 배운 것. 여섯 단계가 전부 있고 순서와 굵은
  글씨 표기가 기존 항목과 같다.
  **양방향 검증이 실제로 있다** — "되돌리면: `environment` 줄을 지우면 같은 25개가 같은
  `PlaceholderResolutionException`으로 다시 깨진다". 이 주장은 위 축 5에서 내가 직접 재현해
  사실임을 확인했다.
  "조용한 실패"가 아니라 "부작용 / 설정"으로 분류한 것도 맞다 — 빌드가 요란하게 깨졌으므로
  조용한 실패의 정의에 들어가지 않는다.

### QA-3. 회고 절의 총계가 24에 멈춰 있다 — **FIX**

- 판정: **FIX**
- 검증 대상: `docs/11-troubleshooting-log.md:1565` vs 요약 표 행 수 / `## N` 제목 수
- 사유: 항목이 25개가 됐는데 회고 절은 아직 **"24건 중 12건"** 이다.

```
docs/11-troubleshooting-log.md:1565
24건 중 12건(3·4·9·10·11·17·18·19·20·22·23·24번)이 **빌드도 기동도 성공하는데 기능만 비어 있는** 유형이었다.
```

  실측: 요약 표 행 25개, `## N` 제목 25개, 표의 "조용한 실패" 표기 12개.
  즉 **분자 12는 맞고 분모 24만 낡았다.** 43행의 "12건이었다"는 그대로 옳다(25번은 조용한
  실패가 아니므로). CLAUDE.md 갱신 절차가 "문서 안의 숫자가 어긋나지 않는지 확인한다"를
  명시하고 있어 이건 절차 위반이다.
- 수정 지시: `docs/11-troubleshooting-log.md:1565`의 `24건 중 12건`을 `25건 중 12건`으로 고친다.
  괄호 안 번호 목록(3·4·9·…·24)은 그대로 둔다 — 25번은 조용한 실패가 아니다.

### QA-3b. PR 번호가 비어 있다 — **FIX (머지 시점)**

- 판정: **FIX** (머지 전까지는 현 상태가 정당하므로 머지와 동시에 처리)
- 검증 대상: `docs/11-troubleshooting-log.md:41` vs 문서 머리말
  ("각 항목은 관련 PR 번호를 달았다") + CLAUDE.md ("요약 표에 행을 추가하고 관련 PR 번호를 단다")
- 사유: 25번 행의 PR 열이 `— (T-003a, 미병합)`이다. 아직 PR이 없으니 지금 적을 수 없는 것이
  맞고 잠정 표기를 남긴 판단도 옳다. 다만 **이대로 머지되면 문서 머리말의 주장이 깨진다.**
  24번까지는 전부 번호가 있다.
- 수정 지시: 이 브랜치의 PR 번호가 정해지는 즉시 `docs/11-troubleshooting-log.md:41`의
  `— (T-003a, 미병합)`을 `#NN`으로 바꾸고 **같은 PR 안에서** 커밋한다(CLAUDE.md: "문서 변경은
  그 문제를 고친 PR에 함께 넣는다"). QA-3과 한 커밋으로 묶으면 된다.

### 회고 절 양방향 목록 — 관찰 2 (선택)

`## 되짚어 보기`의 "양방향 검증" 목록에 T-003a의 음성 대조(필터 등록 한 줄 제거 → 5개 중 2개
실패)가 빠져 있다. CLAUDE.md는 "새 교훈이 생겼으면" 갱신하라고 하므로 의무는 아니다. 다만 이
목록은 지금까지 전부 **스키마·빌드 설정** 사례뿐이고 T-003a가 **런타임 동작**에 대한 첫
음성 대조라 성격이 다르다. QA-3을 고치는 김에 한 줄 추가하는 것을 권한다.

---

## 구현자가 남긴 질문에 대한 QA 의견

1. **`docs/10-task-backlog.md`의 T-003을 쪼갤지** — 쪼개는 쪽을 권한다. QA-4가 T-003b에
   완료 기준 한 줄을 요구하는데, T-003이 한 행으로 남아 있으면 그 기준을 걸 자리가 없다.
   리더 결정 사항.
2. **만료/서명 오류 구분** — 미루는 것이 맞다. 지금 구분해 봐야 클라이언트가 할 수 있는 일이
   재로그인뿐이다.
3. **401도 `ProblemDetail`로 맞출지** — T-025에서 `@RestControllerAdvice`를 만들 때 함께 정하는
   것이 맞다. 다만 그때 QA-4의 "인증 주체가 DB에 없을 때"도 같은 자리에서 정해야 한다.

---

## 머지 전 체크리스트

- [ ] QA-3 — `docs/11-troubleshooting-log.md:1565` `24건` → `25건` **(필수)**
- [ ] QA-3b — PR 번호 확정 후 41행 갱신, QA-3과 같은 커밋 **(필수, 머지와 동시)**
- [ ] QA-1 — 파서 알고리즘 HS256 고정 + 양방향 테스트 (권장, T-003b 이월 가능)
- [ ] QA-2 — 실패 모드 3건 회귀 테스트 추가 (권장, T-003b 이월 가능)
- [ ] QA-4 — T-003b 완료 기준 / T-025 관례 표에 이월 등록 **(문서, 리더 결정)**
