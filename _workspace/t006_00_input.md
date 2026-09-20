# T-006 입력: `accounts` 스키마와 엔티티

- 기능 ID: F-TEDWWF (은행 계좌 연결)
- 브랜치: `feature/F-TEDWWF-schema`
- 의존: T-001 (완료, PR #11)
- 출처: `docs/09-db-design.md` 3.1

## 범위

**스키마·엔티티·리포지토리까지다.** 코드에프 연동, 계좌 연결 API, 2-way 추가인증 흐름, 동기화 스케줄러는 전부 뒤 Task(T-009, T-015 등) 몫이며 코드에프 데모 승인을 기다리고 있다. 만들지 않는다.

## 테이블: `accounts`

| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE | |
| `bank_code` | VARCHAR(10) | NOT NULL | **VARCHAR다.** 은행 조직코드는 앞자리가 0일 수 있어 정수로 저장하면 0이 날아간다 |
| `codef_connected_id` | VARCHAR(255) | NOT NULL | 탈퇴·계좌 해제 시 해지에 필요 |
| `masked_account_no` | VARCHAR(50) | NOT NULL | 마스킹된 계좌번호. **원본은 저장하지 않는다** |
| `account_name` | VARCHAR(100) | NULL | 코드에프가 주면 저장 |
| `consent_status` | VARCHAR(20) | NOT NULL, CHECK IN ('ACTIVE','EXPIRED','REVOKED') | |
| `consent_expires_at` | TIMESTAMPTZ | NULL | |
| `last_synced_at` | TIMESTAMPTZ | NULL | 마지막 성공 동기화 |
| `reauth_required` | BOOLEAN | NOT NULL, DEFAULT FALSE | 2-way 추가인증 은행의 무인 동기화 실패 대응 |
| `included_in_budget` | BOOLEAN | NOT NULL, DEFAULT TRUE | 가계부 집계 포함 여부 |
| `display_mode` | VARCHAR(10) | NOT NULL, DEFAULT 'BADGE', CHECK IN ('BADGE','HIDDEN') | 제외 계좌의 목록 노출 방식 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**제약·인덱스**

- `UNIQUE (user_id, bank_code, masked_account_no)` — 같은 계좌 중복 연결 방지. 동일 은행 복수 계좌는 마스킹 번호가 달라 허용된다
- `INDEX (user_id)` — 계좌 목록 조회
- `INDEX (consent_status, last_synced_at)` — 스케줄러가 동기화 대상 계좌를 고를 때

`display_mode`는 `included_in_budget = FALSE`일 때만 의미가 있다. 포함 상태에서는 무시한다. 별도 테이블로 분리하지 않으며, 이 조건을 DB 제약으로 표현하지 않는다.

## 기존 코드 관례 (T-001·T-023에서 확립)

- 패키지: `com.petgyebu.telo.{도메인}.domain` / `.repository`
- 엔티티: `@Getter` + `@NoArgsConstructor(PROTECTED)` + `Objects.requireNonNull`, `@ManyToOne(LAZY, optional = false)`
- 열거형: `VARCHAR` + `CHECK`, JPA `@Enumerated(STRING)`, 대문자 스네이크
- `TIMESTAMPTZ` ↔ `OffsetDateTime`
- 리포지토리는 `JpaRepository`만. 조회 메서드는 실제 사용처 Task에서
- 마이그레이션 파일명 `V{yyyyMMddHHmm}__{설명}.sql`, 버전은 `origin/main` 최대값보다 커야 함
- 테스트는 Testcontainers 방식 (`UserSchemaTest`, `BudgetSchemaTest` 참고)
- KST 단일 출처 `com.petgyebu.telo.common.time.AppZone`. `ZoneId.of("Asia/Seoul")` 재사용 금지

## 완료 기준

1. 마이그레이션 적용, `PostgresMigrationTest` 통과
2. **`bank_code`가 VARCHAR라 앞자리 0이 보존된다** — 이 Task의 명시된 완료 기준. `'004'` 같은 값을 저장하고 읽어 그대로 나오는지 테스트로 증명
3. `UNIQUE (user_id, bank_code, masked_account_no)` 동작 — **양방향으로 증명하라.** 중복이 막히는 것뿐 아니라, 같은 사용자·같은 은행의 **다른 마스킹 번호** 계좌가 함께 저장되는 것도 단언한다(T-023 QA 교훈: 막히는 것만 보면 제약을 과도하게 좁혀도 통과한다)
4. 유니크 위반 단언에 제약 이름을 확인하는 조건을 넣는다 (NOT NULL·CHECK 위반으로 실패해도 초록이 되는 것을 막는다)
5. `./gradlew build --rerun-tasks` 통과 (`--rerun-tasks` 없이는 테스트가 실행되지 않는다)
