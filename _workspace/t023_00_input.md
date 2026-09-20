# T-023 입력: `budget_periods`·`status_thresholds` 스키마와 엔티티

- 기능 ID: F-FZUVLV (예산 기간 및 목표 설정)
- 브랜치: `feature/F-FZUVLV-schema`
- 의존: T-001 (완료, PR #11)
- 출처: `docs/09-db-design.md` 4.1·4.2, `docs/02-requirements-features.md` F-FZUVLV

## 범위

**이번 Task는 스키마와 엔티티까지다.** 예산 설정 API와 구간 검증 로직은 T-025의 몫이므로 만들지 않는다.

## 테이블 1: `budget_periods`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE |
| `period_start` | DATE | NOT NULL (매월 1일, KST) |
| `period_end` | DATE | NOT NULL (해당 월 말일, KST) |
| `target_amount` | BIGINT | NOT NULL, CHECK (target_amount > 0) |
| `target_amount_snapshot` | BIGINT | NOT NULL (기간 시작 시점 목표, 이후 불변) |
| `status` | VARCHAR(10) | NOT NULL, CHECK IN ('ACTIVE','CLOSED') |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |

제약·인덱스
- `UNIQUE (user_id, period_start)` — **완료 기준의 핵심.** s9 배치 중복 실행 방어
- `INDEX (user_id, status)` — 활성 예산 조회
- `INDEX (status, period_end)` — s9 배치가 종료된 기간을 찾을 때

## 테이블 2: `status_thresholds`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY |
| `budget_period_id` | BIGINT | NOT NULL, FK→budget_periods ON DELETE CASCADE |
| `status_code` | VARCHAR(20) | NOT NULL, CHECK (6종) |
| `start_rate` | NUMERIC(5,2) | NOT NULL, CHECK (start_rate >= 0) |
| `end_rate` | NUMERIC(5,2) | NULL — `OVER_BUDGET`만 NULL |
| `sort_order` | SMALLINT | NOT NULL (1~6) |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |

제약: `UNIQUE (budget_period_id, status_code)`

`status_code` 6종과 기본 구간:

| sort | status_code | 기본 구간 |
|---|---|---|
| 1 | `REST` | 0 ~ 40 |
| 2 | `WAKE` | 40 ~ 60 |
| 3 | `INTEREST` | 60 ~ 75 |
| 4 | `ANXIOUS` | 75 ~ 90 |
| 5 | `STRONG_WARNING` | 90 ~ 100 |
| 6 | `OVER_BUDGET` | 100 ~ (end_rate NULL) |

## 규칙 (문서가 명시한 것)

- **경계값 판정과 구간 검증은 DB가 아니라 애플리케이션에서 한다.** 오른쪽 닫힘 `(시작, 끝]`, 0%만 첫 구간 포함. 이 판정 로직은 T-025 범위이므로 이번에는 제약으로 넣지 않는다
- 상태 묘사 문구는 **테이블로 만들지 않는다.** 상수로 둔다
- 목표 금액이 둘인 이유: 사용률·캐릭터 상태는 `target_amount`, 절약 보상 판정은 `target_amount_snapshot`
- 모든 날짜 경계는 KST 기준. 단일 출처 `com.petgyebu.telo.common.time.AppZone` (T-052). `ZoneId.of("Asia/Seoul")` 재사용 금지

## 기존 코드 관례 (T-001에서 확립)

- 패키지: `com.petgyebu.telo.{도메인}.domain` / `.repository`
- 열거형: `VARCHAR` + `CHECK`, JPA `@Enumerated(STRING)`, 대문자 스네이크
- 시각 컬럼 `TIMESTAMPTZ` ↔ 엔티티 `OffsetDateTime`
- 마이그레이션 파일명: `V{yyyyMMddHHmm}__{설명}.sql` (예: `V202609181920__create_users.sql`)
- 마이그레이션 버전은 `origin/main`의 최대 버전보다 커야 한다 (`scripts/check-migration-order.sh`가 CI에서 강제)

## 완료 기준

1. 마이그레이션이 적용된다 (`PostgresMigrationTest` 통과)
2. `(user_id, period_start)` 유니크가 **실제로 동작한다** — 같은 달 중복 삽입이 막히는 것을 테스트로 증명
3. `(budget_period_id, status_code)` 유니크 동작
4. 엔티티 매핑이 스키마와 일치한다 (T-001의 `UserSchemaTest`와 같은 방식으로 검증)
5. `./gradlew build` 통과
