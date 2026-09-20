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
