# T-023 QA 리포트 — `budget_periods`·`status_thresholds` 스키마와 엔티티

- 기능 ID: F-FZUVLV
- 대상: `feature/F-FZUVLV-schema` 로컬 커밋 `99851fa`, `fd06e09` (푸시 전)
- 판정 요약: **PASS 9 / FIX 1 / REDO 0**
- 검증자가 직접 실행한 명령: `./gradlew build --rerun-tasks`, `./gradlew test --tests BudgetSchemaTest`(격리 워크트리 변이 2회), `bash scripts/check-migration-order.sh`

---

## 1. 마이그레이션 SQL ↔ `docs/09-db-design.md` 4.1 (F-FZUVLV)
- 판정: PASS
- 검증 대상: `src/main/resources/db/migration/V202609202314__create_budget_periods.sql:4-28` vs `docs/09-db-design.md:4.1`
- 사유: 9개 컬럼의 이름·타입·NULL 여부가 한 줄씩 일치한다. `target_amount > 0` CHECK, `status IN ('ACTIVE','CLOSED')` CHECK, `UNIQUE (user_id, period_start)`, `INDEX (user_id, status)`, `INDEX (status, period_end)`, FK `ON DELETE CASCADE`까지 명세와 같다. 누락·추가 컬럼 없음.

## 2. 마이그레이션 SQL ↔ `docs/09-db-design.md` 4.2 (F-FZUVLV)
- 판정: PASS
- 검증 대상: `V202609202314__create_budget_periods.sql:30-52` vs `docs/09-db-design.md:4.2`
- 사유: 7개 컬럼 일치. `end_rate`만 NULL 허용, `start_rate >= 0` CHECK, `status_code` 6종 CHECK(REST·WAKE·INTEREST·ANXIOUS·STRONG_WARNING·OVER_BUDGET), `UNIQUE (budget_period_id, status_code)` 모두 존재. `sort_order`에 `1~6` CHECK는 없으나 명세가 CHECK가 아닌 설명란에만 `1~6`을 적었고 6단계 구성 검증이 T-025 몫이므로 불일치 아님.

## 3. 엔티티 매핑 ↔ 마이그레이션
- 판정: PASS
- 검증 대상: `budget/domain/BudgetPeriod.java:41-71`, `budget/domain/StatusThreshold.java:36-59` vs 마이그레이션
- 사유: `TIMESTAMPTZ ↔ OffsetDateTime`, `DATE ↔ LocalDate`, `NUMERIC(5,2) ↔ BigDecimal(precision=5, scale=2)`, `SMALLINT ↔ short`, `VARCHAR(10)/(20) ↔ @Enumerated(STRING) + length`가 전부 대응한다. `ddl-auto: validate`(`application.yaml:9`)가 켜진 상태로 `@SpringBootTest` 7건이 기동했으므로 Hibernate가 실제 테이블과 대조해 통과한 것이다.

## 4. 완료 기준 1 — 마이그레이션 적용 (`PostgresMigrationTest`)
- 판정: PASS
- 사유: `운영 프로필(PostgreSQL + Flyway + validate) 기동 검증: tests=2 failures=0 errors=0 skipped=0`. 마이그레이션 순서 검사도 직접 실행해 `[통과] V202609202314__create_budget_periods.sql (버전 202609202314)`, exit 0을 확인했다.

## 5. 완료 기준 2 — `(user_id, period_start)` 유니크가 실제로 동작함
- 판정: **FIX**
- 검증 대상: `src/test/java/com/petgyebu/telo/budget/BudgetSchemaTest.java:62-82` vs 완료 기준 "같은 달 중복 삽입이 막히는 것을 증명"
- 사유(절반은 통과): 변이 검증을 직접 돌렸다. 격리 워크트리에서 두 UNIQUE를 `CHECK (true)`로 바꾸자 정확히 해당 2건만 깨졌다 — `7 tests completed, 2 failed`. 예외를 삼키지 않고 `DataIntegrityViolationException` 타입을 확인하므로 "잡기만 하는" 테스트는 아니다.
  그러나 **잘못된 제약도 통과한다.** 같은 워크트리에서 제약을 `UNIQUE (user_id)`(달과 무관하게 사용자당 예산 기간 1개만 허용 — s9 배치의 다음 달 기간 생성을 통째로 막는 치명적 스키마)로 바꿔 돌렸더니 `BUILD SUCCESSFUL`, 7건 전부 통과했다. 각 테스트가 서로 다른 사용자를 쓰고 한 사용자가 서로 다른 두 달을 갖는 경우를 아무도 저장하지 않기 때문이다. 즉 현재 테스트는 "중복이 막힌다"는 증명하지만 "막히는 범위가 (user_id, period_start)다"는 증명하지 못한다.
- 수정 지시:
  1. `BudgetSchemaTest.java:82` 뒤에 테스트 추가 — 같은 사용자가 9월 기간과 10월 기간(`LocalDate.of(2026,10,1)`~`10,31`)을 둘 다 저장할 수 있음을 단언한다. s9 배치의 다음 기간 자동 생성(F-FZUVLV action)이 이 동작에 의존하므로 회귀 방어 가치가 크다.
  2. (권장) `BudgetSchemaTest.java:68-71`의 단언에 제약 이름을 덧붙인다 — `.hasRootCauseMessage(...)` 또는 `.rootCause().hasMessageContaining("uq_budget_periods_user_id_period_start")`. 현재는 `DataIntegrityViolationException`만 보므로 NOT NULL·CHECK 위반으로 실패해도 초록이 된다.

## 6. 완료 기준 3 — `(budget_period_id, status_code)` 유니크
- 판정: PASS
- 검증 대상: `BudgetSchemaTest.java:84-95`
- 사유: 변이 검증에서 해당 제약을 `CHECK (true)`로 바꾸자 이 테스트가 깨졌다. 같은 기간·같은 `REST`를 `end_rate`만 달리해 두 번 저장하므로 다른 제약으로 실패할 경로가 없다.

## 7. 완료 기준 4·5 — 엔티티 매핑 검증 방식과 `./gradlew build`
- 판정: PASS
- 사유: `./gradlew build --rerun-tasks` 직접 실행 → `BUILD SUCCESSFUL in 44s`, `7 actionable tasks: 7 executed`. 테스트 20건 전부 통과, 스킵 0건(`TeloApplicationTests 1`, `BudgetSchemaTest 7`, `EasyCodefUtilJdk25Test 1`, `AppZoneTest 5`, `PostgresMigrationTest 2`, `UserSchemaTest 4`). backend-engineer가 보고한 수치와 일치한다. 처음 `./gradlew build`는 `UP-TO-DATE`로 끝나 테스트를 돌리지 않았으므로 `--rerun-tasks`로 다시 받은 결과다.

## 8. 범위 준수 (T-025 선점 여부)
- 판정: PASS
- 사유: 변경 파일 8개 전부가 스키마·엔티티·리포지토리·테스트다. 구간 검증(0% 고정·오름차순·중복·공백·6단계 누락), 오른쪽 닫힘 경계값 판정, 예산 설정 API, 기본 6단계 삽입이 코드에도 마이그레이션에도 없다(마이그레이션에 `INSERT` 한 줄 없음). 반대로 이번 Task에 필요한 것 중 빠진 것도 없다 — 두 테이블, 제약 2종 UNIQUE·CHECK 4종, 인덱스 2종, 엔티티 4개, 리포지토리 2개가 모두 있다.

## 9. 기존 관례 일치 (T-001 `com.petgyebu.telo.user`)
- 판정: PASS
- 검증 대상: `budget/domain/*` vs `user/domain/User.java`, `user/domain/UserConsent.java`, `user/UserSchemaTest.java`
- 사유: 패키지 레이아웃(`.domain`/`.repository`), `@Getter` + `@NoArgsConstructor(PROTECTED)` + `Objects.requireNonNull` 생성자, `@ManyToOne(LAZY, optional=false)` + `@JoinColumn(nullable=false)`, `@Enumerated(STRING)` + `length`, Testcontainers `@SpringBootTest` + `DataIntegrityViolationException` 단언 방식, 마이그레이션 파일명 규칙이 모두 같다. 차이는 `BudgetSchemaTest`가 `OffsetDateTime.now()` 대신 `AppZone.clock()`을 쓴 것뿐인데, 이는 T-052가 정한 KST 단일 출처 규칙에 더 맞는 방향이라 지적 대상이 아니다.

## 10. backend-engineer 자기 판단 검토
- 판정: PASS (5건 모두 타당)
- (a) `targetAmountSnapshot`을 생성자에서 받지 않고 `updatable = false` — **타당하며 "기간 중 목표 금액 수정" 요구사항과 충돌하지 않는다.** `docs/02-requirements-features.md:236` F-FZUVLV outcome이 수정하라고 하는 대상은 `target_amount`이고, `docs/09-db-design.md` 4.1이 스냅샷을 "기간 중 목표를 올려 보상을 쉽게 타는 것을 막는" 장치로 정의한다. 즉 스냅샷의 불변성이 곧 요구사항이다. 다음 기간은 s9 배치가 **새 행**으로 만들므로 그때 수정된 `targetAmount`가 새 스냅샷이 되며 `updatable=false`에 막히지 않는다. `User.joinedAt`과 같은 패턴이라 관례도 맞다.
- (b) `sort_order`를 `short`로 매핑 — 타당. `SMALLINT`에 대응하고 `validate`가 실제로 통과했다. 1~6 범위 검사는 T-025 몫이라는 판단도 명세와 맞는다.
- (c) `created_at`·`updated_at`을 애플리케이션이 채움 — 타당. T-001 `User.joinedAt:50-55`의 근거(저장 직후 객체 필드 null 방지)를 그대로 따른다.
- (d) 리포지토리에 조회 메서드 없음 — 타당. 쓰이지 않는 쿼리 메서드를 미리 만들지 않는 것이 최소 구현 지침에 맞다. 인덱스만 먼저 깐 것도 스키마 Task 범위에 정확하다.
- (e) `updated_at` 자동 갱신 수단 미정 — 타당하나 **T-025 진입 시 반드시 결정해야 하는 미결 사항**이다. 지금은 수정 경로가 없어 문제가 드러나지 않지만, 목표 금액 수정 API가 생기는 순간 `updated_at`이 생성 시각에 고정되는 조용한 실패가 된다. T-025 입력에 명시적으로 옮길 것.
- 참고(지적 아님): 요약 파일은 판단을 "셋"이라 적었고 나머지 둘은 "남긴 미해결 사항"에 있다. 검토는 5건 전부에 대해 했다.

## 11. 트러블슈팅 기록 16번 형식·숫자 정합성
- 판정: PASS
- 검증 대상: `docs/11-troubleshooting-log.md:515-565` vs `CLAUDE.md` "트러블슈팅 기록 (자동 갱신)"
- 사유: 6개 절(증상·진단·원인·해결·검증·배운 것)이 전부 있고 순서도 규정과 같다. 증상에 실제 출력(`추가된 마이그레이션이 없다. 검사를 건너뛴다.`)을 그대로 넣었고, 진단에 스크립트의 `git diff --name-only --diff-filter=A "$BASE...HEAD"` 한 줄을 단서로 제시했다. 검증은 커밋 전/후 양방향이다. 분류 "검증 방법"은 CLAUDE.md가 명시한 기록 대상("검증 방법 자체의 오류")에 해당한다.
  숫자 정합성: 요약 표가 16행이고 회고 절의 "16건 중 5건(3·4·9·10·11번)"이 표에서 **조용한 실패**로 표시된 행 수와 정확히 일치한다. 16번은 제목에 "조용히"가 들어가지만 조용한 실패로 집계하지 않았고 표 분류도 그렇게 돼 있어 모순 없다. 최종 갱신일도 2026-09-20으로 갱신됐다.
- 참고(지적 아님): PR 열이 `—`다. 푸시 전이라 맞지만, PR 생성 시 14·15번과 달리 번호를 채워야 한다(CLAUDE.md: "요약 표에 행을 추가하고 관련 PR 번호를 단다", "문서 변경은 그 문제를 고친 PR에 함께 넣는다").
