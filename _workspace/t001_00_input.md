# T-001 입력 정리 — users·user_consents 스키마와 엔티티

- 작성: 2026-09-18
- Task: `docs/10-task-backlog.md` T-001
- 브랜치: `feature/F-QJXRMD-schema`
- 근거 문서: `docs/09-db-design.md` 2.1·2.2절(스키마), `docs/02-requirements-features.md` F-QJXRMD(명세), `docs/04-review-log.md` Q3·Q4·Q5·Q6(결정)

## 범위

**이번 Task는 스키마와 엔티티까지다.** 아래는 별도 Task이며 손대지 않는다.

| 제외 항목 | 담당 Task |
|---|---|
| 카카오 소셜 로그인 API | T-002 (B-KAKAO 차단 중) |
| JWT 발급·검증·회전 | T-003 |
| 약관 동의 API | T-004 (B-TERMS 차단 중) |
| 회원 탈퇴 API | T-005 |

컨트롤러·서비스·DTO를 만들지 않는다. 리포지토리는 엔티티 검증에 필요한 최소한만 둔다.

## 완료 기준 (백로그 T-001)

1. 마이그레이션이 적용되고 `PostgresMigrationTest`가 통과한다
2. `(provider, provider_user_id)` 유니크가 실제로 동작한다

## 스키마 (`09-db-design.md` 2.1·2.2)

### users

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, `GENERATED ALWAYS AS IDENTITY` |
| `provider` | VARCHAR(10) | NOT NULL, CHECK IN ('KAKAO','APPLE') |
| `provider_user_id` | VARCHAR(255) | NOT NULL |
| `email` | VARCHAR(320) | **NULL 허용** |
| `character_type` | VARCHAR(10) | NULL, CHECK IN ('DOG','CAT') |
| `character_changed_at` | TIMESTAMPTZ | NULL |
| `joined_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |
| `last_login_at` | TIMESTAMPTZ | NULL |

- `UNIQUE (provider, provider_user_id)`
- `email`에 유니크를 걸지 않는다. 애플은 이메일 가리기를 지원하고 카카오도 동의 항목이라 null이거나 중복일 수 있다
- `joined_at` 인덱스 — 가입 후 24시간·7일 코호트 집계용

### user_consents

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, identity |
| `user_id` | BIGINT | NOT NULL, FK→users **ON DELETE CASCADE** |
| `consent_type` | VARCHAR(30) | NOT NULL, CHECK (아래 4종) |
| `consent_version` | VARCHAR(20) | NOT NULL |
| `consented_at` | TIMESTAMPTZ | NOT NULL |

- `consent_type` 값: `TERMS_OF_SERVICE`, `PRIVACY_POLICY`(가입 시) / `CODEF_THIRD_PARTY`, `FINANCIAL_DATA_INQUIRY`(계좌 연결 직전)
- 인덱스 `(user_id, consent_type, consented_at DESC)`
- 동의 갱신 시 기존 행을 수정하지 않고 새 행을 추가한다

## 공통 규약 (`09-db-design.md` 0장)

- 열거형은 VARCHAR + CHECK, 값은 대문자 스네이크
- 시각은 TIMESTAMPTZ, 기준 타임존 KST
- 소프트 삭제를 쓰지 않는다
- 사용자를 참조하는 FK는 전부 `ON DELETE CASCADE`

## 마이그레이션 규칙 (T-047에서 확정)

- 파일명: `V$(TZ=Asia/Seoul date +%Y%m%d%H%M)__{snake_case_설명}.sql`
- `spring.flyway.out-of-order: false`
- `scripts/check-migration-order.sh`로 로컬에서 미리 검사 가능
- 위치: `src/main/resources/db/migration/`

## 주의

- **`users`에는 비밀번호 컬럼이 없다.** 소셜 로그인 전용이다(Q3)
- 캐릭터는 별도 테이블이 아니라 `users`의 컬럼이다(`09-db-design.md` 2.1 근거 참고)
- 리프레시 토큰 테이블을 만들지 않는다. 폐기 상태는 Upstash Redis에 TTL로 저장한다
