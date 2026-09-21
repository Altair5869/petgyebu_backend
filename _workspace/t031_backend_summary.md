# T-031 구현 요약 — `push_device_tokens`·`push_logs` 스키마

- 기능 ID: F-VZFPVW (예산 사용률 연동 반려동물 소비 피드백) 중 푸시 층
- 브랜치: `feature/F-VZFPVW-schema`
- 출처: `docs/09-db-design.md` 4.3·4.4, `_workspace/t031_00_input.md`

## 항목별 결과

- [완료] 마이그레이션: `src/main/resources/db/migration/V202609212337__create_push.sql` — 테이블 2개, CHECK 2, UNIQUE 2, FK 3, 인덱스 1
- [완료] 엔티티·열거형: `PushDeviceToken`, `PushLog`, `DevicePlatform`, `PushThresholdType`
- [완료] 리포지토리: `PushDeviceTokenRepository`, `PushLogRepository`
- [완료] 스키마 테스트: `PushSchemaTest` 18건 — 검증 축 7가지를 두 테이블 전부에 적용
- [완료] 트러블슈팅 21번 추가: `docs/11-troubleshooting-log.md`

## 만든 파일

```
src/main/resources/db/migration/V202609212337__create_push.sql
src/main/java/com/petgyebu/telo/push/domain/PushDeviceToken.java
src/main/java/com/petgyebu/telo/push/domain/PushLog.java
src/main/java/com/petgyebu/telo/push/domain/DevicePlatform.java
src/main/java/com/petgyebu/telo/push/domain/PushThresholdType.java
src/main/java/com/petgyebu/telo/push/repository/PushDeviceTokenRepository.java
src/main/java/com/petgyebu/telo/push/repository/PushLogRepository.java
src/test/java/com/petgyebu/telo/push/PushSchemaTest.java
```

고친 파일: `docs/11-troubleshooting-log.md` (21번 항목, 요약 표 행, 건수 재검산, 양방향 검증 목록).
**기존 여덟 스키마 테스트 파일은 건드리지 않았다.**

## 문서와 다르게 결정한 것

- **`PushThresholdType` 열거형을 새로 만들었다.** `budget.domain.StatusCode`에 같은 이름의 상수
  둘(`STRONG_WARNING`·`OVER_BUDGET`)이 이미 있지만 그쪽은 캐릭터 상태 6종 전부다. 재사용하면
  `ANXIOUS` 같은 값이 컴파일을 통과하고 런타임 CHECK 위반으로만 걸린다. DB CHECK가 둘만
  허용하므로 타입도 둘만 갖게 했다.
- **`fcm_token` UNIQUE를 인라인이 아닌 명명 제약으로 썼다.** `uq_push_device_tokens_fcm_token`.
  인라인 `UNIQUE`는 PostgreSQL이 이름을 자동 생성해 테스트가 제약 이름으로 원인을 못 박을 수 없다.
  나머지 테이블의 기존 관례와 같다.
- 그 밖에는 설계 문서 4.3·4.4 그대로다. `platform`에 `WEB`을 넣지 않았고, `push_logs` 유니크는
  `reward_grants`와 달리 **2열**(`budget_period_id`, `threshold_type`)이다.

## 실행한 검증

### 전체 빌드

```
$ ./gradlew build --rerun-tasks
BUILD SUCCESSFUL in 27s

TOTAL 139 tests, 0 failures, 0 errors     (T-041 시점 121건 → +18)
PushSchemaTest: tests="18" skipped="0" failures="0" errors="0"
```

### 마이그레이션 순서 검사 (커밋 후)

```
$ ./scripts/check-migration-order.sh
기준: origin/main (최대 버전 202609212143)
  [통과] V202609212337__create_push.sql (버전 202609212337)
마이그레이션 순서 검사 통과.
exit=0
```

### 완료 기준 8개 ↔ 덮은 테스트

| # | 완료 기준 | 덮은 테스트 | 변이 |
|---|---|---|---|
| 1 | 마이그레이션 적용, `PostgresMigrationTest` 통과 | `flywayRunsAndHibernateValidates`, `allAppliedMigrationsSucceeded` | — |
| 2a | 같은 기간·같은 임계값 재발송 거부 | `duplicatePushForSameThresholdIsRejected` | M1 |
| 2b | 같은 기간에 두 임계값이 각각 한 번씩 | `bothThresholdsCoexistInSamePeriod` | M2 |
| 2c | 다른 기간이면 같은 임계값 재발송 가능 | `sameThresholdIsAllowedInAnotherPeriod` | M3 |
| 2d | `user_id` 추가 변이 결과 보고 | (아래 "M4에 대한 보고") — 구조 단언 `designedIndexesExist`만 잡음 | M4 |
| 3 | `fcm_token` UNIQUE 거부+허용+제약 이름 | `duplicateFcmTokenIsRejected`, `differentTokensForSameUserAreAccepted` | M5 |
| 4 | `platform`에 `WEB` 없음 / `ANDROID`·`IOS` 허용 | `webPlatformIsRejected`, `definedPlatformsAreAccepted` | M6 |
| 5 | 두 FK를 각각 | `deletingUserCascadesToPushLogs`, `deletingBudgetPeriodCascadesToPushLogs` | M7, M8 |
| 6 | `read_at` NULL 허용 | `pushLogEntityPersistsUnread`, `readAtIsNullableAndFillable`, `nullabilityMatchesDesign` | M9, M10 |
| 7 | 검증 축 7가지를 두 테이블 전부에 | 아래 표 | 전부 |
| 8 | `./gradlew build --rerun-tasks` 통과 | `BUILD SUCCESSFUL`, 139 tests / 0 failures | — |

축 7가지 적용 현황.

| 축 | `push_device_tokens` | `push_logs` |
|---|---|---|
| 유니크(거부+허용+이름) | `duplicateFcmTokenIsRejected` / `differentTokensForSameUserAreAccepted` | `duplicatePushForSameThresholdIsRejected` / `bothThresholdsCoexistInSamePeriod` + `sameThresholdIsAllowedInAnotherPeriod` |
| CHECK(거부+허용+이름) | `webPlatformIsRejected` / `definedPlatformsAreAccepted` | `undefinedThresholdTypeIsRejected` / `definedThresholdTypesAreAccepted` |
| 인덱스(`indexdef`) | `designedIndexesExist` (2건) | `designedIndexesExist` (1건) |
| FK 동작 | `deletingUserCascadesToDeviceTokens` | 두 FK 각각 — 사용자만 / 예산 기간만 |
| 컬럼 타입 | `columnTypesMatchDesign` (6열) | `columnTypesMatchDesign` (6열) |
| `NOT NULL` 맵 | `nullabilityMatchesDesign` | `nullabilityMatchesDesign` |
| **엔티티 경로** | `pushDeviceTokenEntityPersistsFields` | `pushLogEntityPersistsUnread` |

### 양방향 검증 — 변이 14종

각 변이를 하나씩 넣고 `PushSchemaTest`만 돌린 뒤 매번 원복했다. **어느 테스트가 깨지는지까지**
확인했다.

| 변이 | 결과 | 깨진 테스트 |
|---|---|---|
| M1 `push_logs` 유니크 통째 제거 | 18 tests, 2 failed | 같은 기간 같은 임계값 재발송 거부 / 인덱스 |
| M2 유니크를 `(budget_period_id)` 1열로 | 18 tests, 3 failed | **두 임계값 공존** / CHECK 허용 / 인덱스 |
| M3 유니크를 `(threshold_type)` 1열로 | 18 tests, 8 failed | **다음 달 재발송** 외 7 |
| M4 유니크에 `user_id` 추가 (3열) | 18 tests, 1 failed | **인덱스 단언만** — 아래 별도 기술 |
| M5 `fcm_token` 유니크 제거 | 18 tests, 2 failed | 토큰 중복 거부 / 인덱스 |
| M6 `platform` CHECK에 `'WEB'` 추가 | 18 tests, 1 failed | 웹 푸시는 없다 |
| M7 `push_logs.user_id` CASCADE 제거 | 18 tests, 1 failed | 사용자 삭제 CASCADE |
| M8 `push_logs.budget_period_id` CASCADE 제거 | 18 tests, 1 failed | **예산 기간만 삭제 CASCADE** |
| M9 `read_at`을 NOT NULL로 | 18 tests, 10 failed | `read_at` 나중 기록 / nullable 맵 외 8 |
| M12 `push_device_tokens.user_id` CASCADE 제거 | 18 tests, 1 failed | 기기 토큰 CASCADE |
| M13 `ix_push_device_tokens_user_id` 제거 | 18 tests, 1 failed | 인덱스 |
| M10 `PushLog` 생성자 `readAt = sentAt` | 18 tests, 1 failed | **엔티티 경로 — `read_at`이 NULL이다** |
| M11 `PushDeviceToken` 생성자 `updatedAt = null` | 18 tests, 1 failed | **엔티티 경로 — 값이 그대로 내려간다** |
| M14 `PushLog`의 `@Enumerated(STRING)` 제거 | 18 tests, 18 failed | `ddl-auto: validate`가 컨텍스트 기동에서 막는다 |

실제 출력 발췌.

```
### M4 유니크에 user_id 추가 (3열)
18 tests completed, 1 failed
BUILD FAILED in 8s
  FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지

### M8 push_logs.budget_period_id CASCADE 제거
18 tests completed, 1 failed
  FAILED: 예산 기간만 지워도 발송 기록이 함께 지워진다 — budget_period_id FK의 ON DELETE CASCADE

### M10 PushLog 생성자: readAt = sentAt (뒤집기)
18 tests completed, 1 failed
  FAILED: PushLog 엔티티로 저장하면 read_at이 NULL이다 — 발송 직후는 읽지 않은 상태다

### M11 PushDeviceToken 생성자: updatedAt을 안 채움
18 tests completed, 1 failed
  FAILED: PushDeviceToken 엔티티로 저장한 값이 그대로 내려간다 — 엔티티 경로
```

원복 후 `git status`에 남은 변이 없음을 확인하고 전체 빌드를 다시 돌렸다(`BUILD SUCCESSFUL`).

## M4에 대한 보고 — 입력 명세가 요구한 확인

**입력 명세가 지시한 "유니크에 `user_id`를 더하는 변이"를 돌렸고, 완료 기준 2의 동작 세 가지는
그대로 통과했다.** 명세와 다른데 동작은 같다.

이유는 이 스키마에서 **3열 유니크가 2열보다 느슨하고, 그 차이가 도달 불가능한 상태에만
있기 때문이다.** 두 유니크의 동작이 갈리려면 `budget_period_id`가 같은데 `user_id`가 다른 두
행이 필요한데, `budget_periods` 행에 `user_id`가 이미 박혀 있어 그런 행을 만들 수 없다. 명세가
"`budget_period_id`가 이미 사용자를 함의한다"고 적은 그 사실이, 동시에 두 제약을 동작으로
구별할 수 없게 만든다.

**따라서 이 축은 동작 단언으로 고정되지 않는다.** 실제로 잡은 것은 구조 단언 하나다.

```java
assertIndex("push_logs", "uq_push_logs_period_threshold", "btree",
        "budget_period_id, threshold_type", null);
```

열 구성과 순서를 `pg_indexes`의 `indexdef`로 못 박아 두었으므로 열이 더해지거나 순서가 바뀌면
걸린다. 이 발견을 트러블슈팅 21번에 남겼다.

반대로 **열을 빼는 변이(M2·M3)는 동작으로 크게 드러난다.** 열을 더하는 변이와 빼는 변이는
대칭이 아니다.

## 남긴 미해결 사항

- **`assertIndex` 헬퍼가 여섯 벌로 갈렸다.** 백로그 "이월 사항"의 통일 권고(T-013 QA 6번,
  T-014 QA 13번)가 그대로 남아 있다. `PushSchemaTest`도 관례대로 자체 사본을 들었다.
  이 헬퍼는 `endsWith`라 **`CREATE UNIQUE INDEX`→`CREATE INDEX` 변이를 구조적으로 못 잡는다**
  (`UNIQUE`가 문자열 앞쪽에 있다). M1·M5가 인덱스 테스트를 깬 것은 인덱스가 통째로 사라졌기
  때문이고, 유니크성만 떼는 변이는 여전히 새어 나간다. 통일 Task에서 `indexdef` 전체 비교로
  바꾸면 함께 해소된다.
- **범위 밖(의도적 보류)**: FCM 발송 로직, 디바이스 토큰 등록 API와 upsert, 알림 권한 요청 흐름,
  열람 이벤트 수신 API, Upstash(Redis) 중복 판정. 전부 T-032 이후다.
- `PushDeviceTokenRepository`·`PushLogRepository`는 현재 사용처가 테스트뿐이다. 트러블슈팅
  20번이 "사용처 0인 리포지토리는 신호"라고 했는데, 엔티티 경로 테스트가 둘 다 지나가므로
  그 신호는 해소돼 있다.
