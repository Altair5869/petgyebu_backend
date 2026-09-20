# T-023 구현 요약 — `budget_periods`·`status_thresholds` 스키마와 엔티티

- 기능 ID: F-FZUVLV
- 브랜치: `feature/F-FZUVLV-schema` (로컬 커밋 `99851fa`, **푸시하지 않음 / PR 없음**)

## 항목별 결과

- [완료] 마이그레이션: `src/main/resources/db/migration/V202609202314__create_budget_periods.sql` — 두 테이블, CHECK 4종, UNIQUE 2종, 인덱스 2종
- [완료] 엔티티: `src/main/java/com/petgyebu/telo/budget/domain/{BudgetPeriod,StatusThreshold,BudgetPeriodStatus,StatusCode}.java`
- [완료] 리포지토리: `src/main/java/com/petgyebu/telo/budget/repository/{BudgetPeriodRepository,StatusThresholdRepository}.java` — T-001과 같이 `JpaRepository`만, 조회 메서드는 필요해지는 T-025에서 추가
- [완료] 스키마 제약 테스트: `src/test/java/com/petgyebu/telo/budget/BudgetSchemaTest.java` (7건)
- [완료] 트러블슈팅 기록 16번 추가: `docs/11-troubleshooting-log.md`
- [범위 밖 — 의도적 미구현] 예산 설정 API, 구간 검증(0% 고정·오름차순·중복·공백·6단계 누락), 오른쪽 닫힘 경계값 판정, 기본 구간 6단계 삽입 로직 — 전부 T-025

## 문서와 다르게 결정한 것

없다. 스키마는 `docs/09-db-design.md` 4.1·4.2절 그대로다. 다만 문서가 명시하지 않아 판단한 것이 셋 있다.

1. **`BudgetPeriod` 생성자가 `targetAmountSnapshot`을 받지 않고 `targetAmount`와 같은 값으로 채운다.** 문서가 "기간 시작 시점 목표"라고 정의했으므로 생성 시점에는 둘이 같을 수밖에 없다. 필드에 `updatable = false`를 걸어 이후 갱신 경로를 매핑 단계에서 막았다(`User.joinedAt`과 같은 방식).
2. **`sort_order`를 `short`로 매핑했다.** 컬럼이 `SMALLINT`라 `ddl-auto: validate`가 통과해야 한다. 1~6 범위 검사는 넣지 않았다 — 6단계 구성 검증이 T-025 몫이기 때문이다.
3. **`created_at`·`updated_at`은 DB `DEFAULT now()`가 있어도 애플리케이션이 채운다.** T-001이 `joined_at`에서 정한 방식과 같다(`insertable = false`로 두면 저장 직후 객체 필드가 null이다). `updatedAt` 자동 갱신 훅은 넣지 않았다 — 수정 경로가 생기는 T-025에서 정할 일이다.

## 검증

### 1. `./gradlew build`

```
BUILD SUCCESSFUL in 42s
```

테스트 20건 전부 통과, 스킵 0건.

```
com.petgyebu.telo.TeloApplicationTests: tests=1 failures=0 errors=0 skipped=0
budget_periods·status_thresholds 스키마 제약 검증: tests=7 failures=0 errors=0 skipped=0
com.petgyebu.telo.codef.EasyCodefUtilJdk25Test: tests=1 failures=0 errors=0 skipped=0
기준 타임존 KST 규칙: tests=5 failures=0 errors=0 skipped=0
운영 프로필(PostgreSQL + Flyway + validate) 기동 검증: tests=2 failures=0 errors=0 skipped=0
users·user_consents 스키마 제약 검증: tests=4 failures=0 errors=0 skipped=0
```

### 2. 유니크 제약 양방향 검증

완료 기준이 "제약을 걸었다"가 아니라 "실제로 막힌다"이므로, 마이그레이션에서 UNIQUE 두 개를
`CHECK (true)`로 바꿔 두 테스트가 정말 깨지는지 확인했다.

```
budget_periods·status_thresholds 스키마 제약 검증 > 같은 기간에 같은 상태 구간을 두 번 저장할 수 없다 FAILED
budget_periods·status_thresholds 스키마 제약 검증 > 같은 사용자·같은 달 예산 기간은 두 번 저장할 수 없다 — s9 배치 중복 실행 방어 FAILED
> Task :test FAILED
7 tests completed, 2 failed
```

정확히 그 두 건만 깨졌다. 제약을 되돌린 뒤 재실행:

```
BUILD SUCCESSFUL in 37s
```

### 3. 마이그레이션 순서 검사

```
기준: origin/main (최대 버전 202609181920)
  [통과] V202609202314__create_budget_periods.sql (버전 202609202314)
마이그레이션 순서 검사 통과.
```

커밋 전에 돌렸을 때는 `추가된 마이그레이션이 없다. 검사를 건너뛴다.`가 나왔다(종료 코드 0).
스크립트가 `origin/main...HEAD`를 보기 때문에 untracked 파일은 시야 밖이다.
트러블슈팅 기록 16번에 남겼다.

## 남긴 미해결 사항

- **로컬 커밋만 했다. 푸시·PR 없음** — 리더 지시 대기.
- `BudgetPeriodRepository`·`StatusThresholdRepository`에 조회 메서드가 없다. 활성 예산 조회
  (`user_id, status`)와 s9 배치의 마감 대상 조회(`status, period_end`)용 인덱스는 이미 깔았지만,
  그 인덱스를 쓰는 쿼리 메서드는 T-025/s9 배치 Task에서 추가한다.
- `updated_at` 자동 갱신 수단(`@PreUpdate` 또는 Auditing)을 정하지 않았다. 지금은 수정 경로가
  없어 문제되지 않지만, T-025에서 목표 금액 수정 API가 생기면 결정해야 한다.

---

## QA 지적 반영 (FIX 1건)

`(user_id, period_start)` 유니크 테스트가 제약의 **범위**를 증명하지 못한다는 지적. 제약을
`UNIQUE (user_id)`로 좁혀도 7건이 전부 통과했다 — 각 테스트가 서로 다른 사용자를 쓰고, 한 사용자가
두 달을 갖는 경우를 아무도 저장하지 않았기 때문이다.

- [완료] `BudgetSchemaTest.consecutiveMonthPeriodsForSameUserAreAllowed()` 추가 — 같은 사용자가
  9월(`2026-09-01~09-30`)과 10월(`2026-10-01~10-31`) 기간을 둘 다 저장할 수 있고 행이 2개임을 단언.
  F-FZUVLV action "기간 종료 시 다음 기간 자동 생성"(s9 배치)이 이 동작에 의존한다
- [완료] 유니크 위반 단언 두 곳에 `.rootCause().hasMessageContaining("<제약명>")` 추가 —
  `uq_budget_periods_user_id_period_start`, `uq_status_thresholds_period_status`.
  NOT NULL·CHECK 위반으로 실패해도 초록이 되던 것을 막는다
- 프로덕션 코드와 마이그레이션은 건드리지 않았다(`git diff --stat`이 테스트 파일 1개만 보고한다)

### 검증 — `--rerun-tasks`로만 판단했다

```
BUILD SUCCESSFUL in 42s
7 actionable tasks: 7 executed
```

`budget_periods·status_thresholds 스키마 제약 검증: tests=8 failures=0 skipped=0`.
(`--rerun-tasks` 없이 돌리면 `7 up-to-date`로 테스트가 아예 실행되지 않는다.)

**변이 1 — 제약을 `UNIQUE (user_id)`로 좁힘** (QA가 지적한, s9 배치를 막는 치명적 스키마):

```
> 같은 사용자가 서로 다른 달의 예산 기간을 함께 가질 수 있다 — s9 배치의 다음 기간 생성 FAILED
8 tests completed, 1 failed
```

새 테스트가 정확히 이 변이를 잡는다. 기존 7건은 여전히 통과 — 지적이 정확했다.

**변이 2 — 제약 이름만 `uq_bp_renamed`/`uq_st_renamed`로 변경**(열 구성은 그대로):

```
> 같은 기간에 같은 상태 구간을 두 번 저장할 수 없다 FAILED
> 같은 사용자·같은 달 예산 기간은 두 번 저장할 수 없다 — s9 배치 중복 실행 방어 FAILED
8 tests completed, 2 failed
```

제약명 단언이 실제로 동작한다(이름을 확인하지 않던 이전 테스트는 이 변이를 통과했을 것이다).

두 변이 모두 원복 후 `BUILD SUCCESSFUL in 42s / 7 actionable tasks: 7 executed`.
