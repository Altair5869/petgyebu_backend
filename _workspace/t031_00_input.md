# T-031 입력: `push_device_tokens`·`push_logs` 스키마

- 기능 ID: F-VZFPVW (예산 사용률 연동 반려동물 소비 피드백) 중 푸시 층
- 브랜치: `feature/F-VZFPVW-schema`
- 의존: T-023(완료, PR #16)
- 출처: `docs/09-db-design.md` 4.3·4.4

## 범위

테이블 2개, 제약, 인덱스까지다. 만들지 않는 것: FCM 발송 로직, 디바이스 토큰 등록 API, 알림 권한 요청 흐름, 열람 이벤트 수신 API, Upstash(Redis) 중복 판정.

## 테이블 1: `push_device_tokens`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE |
| `fcm_token` | VARCHAR(512) | NOT NULL, **UNIQUE** |
| `platform` | VARCHAR(10) | NOT NULL, CHECK IN ('ANDROID','IOS') |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() |

**인덱스**: `(user_id)`

`fcm_token`의 UNIQUE는 **기기를 넘겨받은 경우**(같은 토큰이 다른 사용자에게)를 충돌로 감지하기 위한 것이다. 토큰 등록 시 upsert로 소유자를 갱신한다. **upsert 로직은 T-032 이후 몫이니 만들지 마라.**

`platform`에 `WEB`이 **없다.** 웹 푸시를 구현하지 않기로 했다(Q9, `docs/02-requirements-features.md` R-ENPLNB 결정 5). 임의로 추가하지 마라.

## 테이블 2: `push_logs`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | |
| `user_id` | BIGINT | NOT NULL, FK→users ON DELETE CASCADE | |
| `budget_period_id` | BIGINT | NOT NULL, FK→budget_periods ON DELETE CASCADE | |
| `threshold_type` | VARCHAR(20) | NOT NULL, CHECK IN ('STRONG_WARNING','OVER_BUDGET') | |
| `sent_at` | TIMESTAMPTZ | NOT NULL | |
| `read_at` | TIMESTAMPTZ | NULL | 앱에서 알림을 탭하면 기록 |

**제약**: `UNIQUE (budget_period_id, threshold_type)`

### 이 유니크가 규칙을 보장한다

**"기간당 1회, 재발송 없음"을 DB가 보장한다**(Q13). Upstash 키(`budget_period_id:threshold`)가 유실되거나 배치가 중복 실행돼도 두 번째 발송은 제약 위반으로 막힌다. **Redis는 빠른 판정용이고 DB가 최종 방어선이다.**

환불 반영이나 목표 금액 상향으로 사용률이 임계값 아래로 내려갔다가 다시 진입해도 같은 예산 기간 안에서는 다시 보내지 않는다. 경계값 부근에서 사용률이 진동할 때 알림이 반복되는 위험을 없앤다.

### 유니크 열 구성에 주의하라

**`user_id`가 유니크에 들어가지 않는다.** `reward_grants`는 `UNIQUE (user_id, budget_period_id, condition_type)` 3열인데 여기는 **2열**이다. `budget_period_id`가 이미 사용자를 함의하므로 명세가 그렇게 정했다. 앞 Task와 맞춘다고 `user_id`를 넣지 마라.

`read_at`이 KPI "예산 초과 경고 확인율"의 분자다(Q11).

## 두 FK의 대칭 확인 — T-041에서 배운 것

`push_logs`에 FK가 둘(`user_id`, `budget_period_id`)인데, **사용자를 지우면 `budget_periods`도 함께 지워져** `user_id` 쪽 CASCADE만으로 결과가 같아진다. **예산 기간만 지우는 테스트를 따로 둬라.** 없으면 `budget_period_id` 쪽 CASCADE가 통째로 비어도 드러나지 않는다.

T-041이 같은 구조(`reward_grants`)에서 이것을 선제적으로 막았고, T-014에서는 `transfer_links`의 입금 쪽 CASCADE가 비어 있던 것이 QA에서 드러났다(트러블슈팅 19번).

## 검증 축 7가지 — 처음부터 전부 넣는다

`docs/10-task-backlog.md` 말미의 "스키마 단언 전수 점검" 절과 "축 7: 엔티티 경로"를 읽어라.

| 축 | 방법 |
|---|---|
| 유니크 | 거부 + **허용** + 제약 이름 |
| CHECK | 거부 + **허용** + 제약 이름 |
| 인덱스 | `pg_indexes`의 `indexdef` — 종류·열 구성·정렬 방향·부분 조건 |
| FK | CASCADE 동작. **두 FK를 각각** |
| 컬럼 타입 | `udt_name` + `character_maximum_length` |
| `NOT NULL` | 테이블별 nullable 맵 통째 비교 |
| **엔티티 경로** | **만든 엔티티를 리포지토리로 저장하고 되읽어 확인** |

축 7이 T-041에서 새로 생겼다. `UserItem`의 초기 배치 상태와 `RewardGrant`의 판정 근거 값이 엔티티 경로가 비어 조용히 통과했다(트러블슈팅 20번). **`PushDeviceToken`·`PushLog` 둘 다 리포지토리로 저장하고 되읽어라.**

`RewardSchemaTest`·`ShopSchemaTest`가 가장 최신 모델이다. 헬퍼를 그대로 가져와라. **기존 여덟 테스트 파일은 건드리지 마라.**

## 완료 기준

1. 마이그레이션 적용, `PostgresMigrationTest` 통과
2. **`(budget_period_id, threshold_type)` 유니크가 실제로 동작** (백로그 명시 기준)
   - 같은 기간·같은 임계값 두 번째 발송은 거부된다
   - **같은 기간에 `STRONG_WARNING`과 `OVER_BUDGET`은 각각 한 번씩 저장된다** — 90%와 100%가 둘 다 발생할 수 있다. 유니크에 `threshold_type`이 빠지면 이것이 막힌다
   - **다른 기간이면 같은 임계값을 다시 보낼 수 있다** — 매달 경고가 가능해야 한다. `budget_period_id`가 빠지면 이것이 막힌다
   - 유니크에 `user_id`를 더하는 변이를 돌려도 위 셋이 통과하는지 보라. 통과한다면 그 사실을 요약에 적어라(명세와 다르지만 동작은 같다)
3. `fcm_token` UNIQUE 동작 — 거부 + 허용(다른 토큰) + 제약 이름
4. `platform` CHECK에 `WEB`이 없음을 증명하라 — `'WEB'` 삽입이 거부되어야 한다. `'ANDROID'`·`'IOS'`는 허용
5. `push_logs`의 **두 FK를 각각** 확인 — 사용자만 지우는 경로와 예산 기간만 지우는 경로
6. `read_at`이 NULL 허용임을 증명하라 — 발송 직후에는 읽지 않은 상태다
7. 위 검증 축 7가지를 두 테이블 전부에 적용
8. `./gradlew build --rerun-tasks` 통과
