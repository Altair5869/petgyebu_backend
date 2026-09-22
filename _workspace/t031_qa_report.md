# T-031 QA 리포트: `push_device_tokens`·`push_logs` 스키마와 엔티티

- 기능 ID: F-VZFPVW (푸시 층)
- 대상: 브랜치 `feature/F-VZFPVW-schema`, 커밋 `7475f26` (**푸시 전**)
- 검증자 실행 환경: 원본 저장소(빌드) + 격리 워크트리 `/tmp/t031qa`(변이 전용, 검증 후 제거 완료)

## 판정 요약

**PASS 10 / FIX 3 / REDO 0 / 미검증 0**

스키마·엔티티·범위·관례에는 결함이 없다. 마이그레이션은 `docs/09-db-design.md` 4.3·4.4와 컬럼·타입·NULL·CHECK·UNIQUE·인덱스가 한 줄도 어긋나지 않는다. **검증 축 7가지가 두 테이블 전부에 처음부터 들어와 있다는 자기 보고는 사실이다** — 검증자가 표를 다시 그려 대조했고 빈 칸이 없다. DB 제약 변이는 검증자가 설계한 것까지 **전부 잡힌다(14종 중 14종).**

FIX 3건 중 무게가 실린 것은 **6번**이다. M4에 대한 backend-engineer의 추론이 **사실과 다르다.** "3열 유니크와 2열 유니크를 동작으로 구별할 수 없다"는 주장을 검증자가 실험으로 반증했다. 트러블슈팅 21번의 진단 본문이 같은 오류를 담고 있다.

---

## 1. 마이그레이션 SQL ↔ `docs/09-db-design.md` 4.3·4.4
- 판정: PASS
- 검증 대상: `src/main/resources/db/migration/V202609212337__create_push.sql` vs 설계 4.3·4.4 표
- 사유: 두 테이블 12개 컬럼을 한 줄씩 대조했다. 어긋난 항목이 없다.
  - `push_device_tokens`(SQL:4-19) — `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `user_id` FK→users CASCADE, `fcm_token VARCHAR(512) NOT NULL` + 명명 UNIQUE, `platform VARCHAR(10) NOT NULL` + CHECK 2값, `created_at`·`updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`, 인덱스 `(user_id)`.
  - `push_logs`(SQL:21-43) — 6열 전부 일치. `read_at`만 NULL 허용(SQL:31), `sent_at`에는 DEFAULT가 없다(설계도 DEFAULT를 적지 않았다), 두 FK 모두 `ON DELETE CASCADE`, `UNIQUE (budget_period_id, threshold_type)`(SQL:42).
  - **`platform`에 `WEB`이 없다**(SQL:14) — `docs/02-requirements-features.md` R-ENPLNB 결정 5(모바일 전용)와 일치.
  - **유니크가 2열이다**(SQL:42) — 결정 6("발송 로그의 (예산 기간, 임계값) 유니크 키만으로 보장")의 문구 그대로다. `reward_grants`의 3열을 따라가지 않았다.
  - `read_at`(SQL:31)이 결정 7(발송·열람 계측)의 열람 쪽이다. dataSpec(`docs/02-requirements-features.md:316`)의 "사용자 식별자, 예산 기간 식별자, 임계값 종류, 발송 시각, 열람 시각" 5항목과 컬럼이 1:1로 대응하고, 디바이스 토큰의 "사용자 식별자, FCM 토큰, 플랫폼, 갱신 시각"도 대응한다.
- 관례: `uq_`/`ck_` 접두 명명, 인라인 UNIQUE 대신 명명 제약 — 기존 여덟 테이블과 같다. 자기 보고의 "문서와 다르게 결정한 것" 2번(명명 제약)은 관례 일치 쪽이 맞다.

## 2. 엔티티 매핑 ↔ 스키마
- 판정: PASS
- 검증 대상: `PushDeviceToken`·`PushLog`·`DevicePlatform`·`PushThresholdType` vs 마이그레이션
- 사유: 타입·길이·nullable·`updatable`이 전부 맞는다. `fcmToken`은 `length = 512`(`PushDeviceToken:45`), `platform`은 `length = 10`(`:49`), `thresholdType`은 `length = 20`(`PushLog:56`)으로 DB와 이중 고정된다. `readAt`만 `@Column(nullable=false)`가 없다(`PushLog:63`) — 설계와 일치. `createdAt`·`sentAt`의 `updatable = false`도 의미에 맞다.
- `PostgresMigrationTest`(운영 프로필 + `ddl-auto: validate`)가 통과하므로 매핑 누락은 구조적으로도 막혀 있다. 검증자의 변이 X10(`@Enumerated(STRING)` 제거)이 **18건 전부 실패**로 이것을 확인했다.

## 3. 빌드 — 검증자가 직접 실행
- 판정: PASS
- 실행: `./gradlew build --rerun-tasks` (원본 저장소, 커밋 `7475f26` 상태)

```
BUILD SUCCESSFUL in 28s
7 actionable tasks: 7 executed
```

테스트 결과 XML을 직접 집계했다. 자기 보고의 139건과 일치한다.

```
  1 tests  com.petgyebu.telo.TeloApplicationTests
 12 tests  accounts 스키마 제약 검증
 13 tests  budget_periods·status_thresholds 스키마 제약 검증
 11 tests  categories·merchant_keyword_rules 스키마 제약 검증
  1 tests  com.petgyebu.telo.codef.EasyCodefUtilJdk25Test
  5 tests  기준 타임존 KST 규칙
  2 tests  운영 프로필(PostgreSQL + Flyway + validate) 기동 검증
 18 tests  push_device_tokens·push_logs 스키마 제약 검증
 16 tests  credit_balances·reward_grants 스키마 제약 검증
 19 tests  shop_items·user_items 스키마 제약 검증
  9 tests  sync_attempts 스키마 제약 검증
 23 tests  transactions·transfer_links 스키마 제약 검증
  9 tests  users·user_consents 스키마 제약 검증
TOTAL 139 failures 0 errors 0
```

마이그레이션 순서도 직접 확인했다.

```
$ ./scripts/check-migration-order.sh
기준: origin/main (최대 버전 202609212143)
  [통과] V202609212337__create_push.sql (버전 202609212337)
마이그레이션 순서 검사 통과.
exit=0
```

## 4. 검증 축 7가지 — 두 테이블 전수 확인
- 판정: PASS
- 검증 대상: `PushSchemaTest` vs `docs/10-task-backlog.md`의 "스키마 단언 전수 점검" + "축 7: 엔티티 경로"
- 사유: **빠진 칸이 없다.** T-041 QA가 새로 세운 축 7이 이번에 처음부터 들어왔다.

| 축 | `push_device_tokens` | `push_logs` |
|---|---|---|
| 유니크 **거부** | O `duplicateFcmTokenIsRejected`(:82) | O `duplicatePushForSameThresholdIsRejected`(:193) |
| 유니크 **허용** | O `differentTokensForSameUserAreAccepted`(:98) | O `bothThresholdsCoexistInSamePeriod`(:213) + `sameThresholdIsAllowedInAnotherPeriod`(:231) |
| 유니크 **제약 이름** | O `uq_push_device_tokens_fcm_token`(:93) | O `uq_push_logs_period_threshold`(:204) |
| CHECK **거부** | O `webPlatformIsRejected`(:116) | O `undefinedThresholdTypeIsRejected`(:252) |
| CHECK **허용** | O `definedPlatformsAreAccepted`(:130, enum 순회) | O `definedThresholdTypesAreAccepted`(:266, enum 순회) |
| CHECK **제약 이름** | O `ck_push_device_tokens_platform`(:125) | O `ck_push_logs_threshold_type`(:261) |
| 인덱스 `indexdef` | O 2건 — `ix_..._user_id`, `uq_..._fcm_token`(:366-369) | O 1건 — 2열·열 순서까지(:371) |
| FK 동작 (**각각**) | O `deletingUserCascadesToDeviceTokens`(:145) — FK 1개 | O **두 FK 각각** — `deletingUserCascadesToPushLogs`(:280), `deletingBudgetPeriodCascadesToPushLogs`(:294) |
| 컬럼 타입 `udt_name` + 길이 | O 6열(:378-384) | O 6열(:386-392) |
| `NOT NULL` 맵 통째 | O(:398-404) | O(:406-413) |
| **엔티티 경로** | O `pushDeviceTokenEntityPersistsFields`(:158) | O `pushLogEntityPersistsUnread`(:312) |

- 입력 명세가 강조한 "두 FK를 각각"이 지켜졌다. 검증자가 M8(예산 기간 쪽 CASCADE만 제거)을 재현해 `deletingBudgetPeriodCascadesToPushLogs` **하나만** 실패하는 것을 확인했다. 이 테스트가 없었다면 그 변이는 통째로 새어 나간다(트러블슈팅 19번 계열).
- 엔티티 경로가 **두 엔티티 모두** 리포지토리 `saveAndFlush` → JDBC 되읽기로 구현돼 있다. 축 7의 요구를 형식이 아니라 내용으로 충족한다.

## 5. 완료 기준 2 — 세 방향이 정말 서로 다른 테스트에 걸리는가 (격리 워크트리 재현)
- 판정: PASS
- 검증 대상: `t031_backend_summary.md:100-102`의 주장 vs 검증자 재현
- 사유: 세 변이를 검증자가 직접 넣고 `PushSchemaTest`만 돌렸다. **주장대로다.** 각 방향마다 "그 변이만" 깨는 고유 테스트가 있다.

```
=== M1-drop-pushlog-unique => exit 1; tests=18 failures=2
      같은 기간에 같은 임계값을 두 번 보낼 수 없다 — UNIQUE (budget_period_id, threshold_type)
      설계한 인덱스가 실제로 만들어져 있다 [push_logs 테이블에 인덱스 uq_push_logs_period_threshold가 없다]

=== M2-unique-period-only => exit 1; tests=18 failures=3
      같은 기간에 STRONG_WARNING과 OVER_BUDGET은 각각 한 번씩 저장된다  ← M2 고유
      명세에 있는 threshold_type 두 값은 전부 저장된다
      설계한 인덱스가 실제로 만들어져 있다

=== M3-unique-threshold-only => exit 1; tests=18 failures=8
      기간이 다르면 같은 임계값을 다시 보낼 수 있다 — 매달 경고가 가능해야 한다  ← M3 고유
      (외 7 — 같은 기간의 두 번째 INSERT가 전부 막혀 연쇄로 깨진다)
```

- **고유 판별자**: M1 → 재발송 거부(:193), M2 → 두 임계값 공존(:213), M3 → 다음 기간 재발송(:231). 셋 중 어느 하나를 지워도 대응 변이가 새어 나간다. 세 테스트가 전부 필요하다는 뜻이며, 완료 기준 2가 세 방향을 요구한 이유가 실측으로 확인된다.
- 자기 보고의 실패 건수(2 / 3 / 8)도 정확히 일치한다.

## 6. M4 판단 — backend-engineer의 추론이 사실과 다르다
- 판정: **FIX**
- 검증 대상: `t031_backend_summary.md:138-160`, `docs/11-troubleshooting-log.md` 21번 "진단"·"원인"·"배운 것" vs 검증자 실험
- 자기 보고의 주장:
  > 두 유니크의 동작이 갈리려면 `budget_period_id`가 같은데 `user_id`가 다른 두 행이 필요한데, `budget_periods` 행에 `user_id`가 이미 박혀 있어 **그런 행을 만들 수 없다.** … **따라서 이 축은 동작 단언으로 고정되지 않는다.**

- **이 추론은 DB 수준에서 틀렸다.** `push_logs`에는 `user_id → users(id)` FK와 `budget_period_id → budget_periods(id)` FK가 **각각 따로** 있을 뿐, `(user_id, budget_period_id)`가 `budget_periods`의 소유자와 일치하도록 강제하는 복합 FK나 CHECK가 **없다**(SQL:23-26). 따라서 `budget_period_id`는 A의 기간인데 `user_id`는 B인 행을 **DB는 그대로 받는다.** "그런 행을 만들 수 없다"를 보장하는 것은 스키마가 아니라 애플리케이션 의미뿐이다.

- **검증자가 실험으로 반증했다.** 격리 워크트리에서 `PushSchemaTest`에 프로브 한 건을 추가했다 — 같은 기간·같은 임계값을 **다른 `user_id`로** 두 번 raw INSERT 한다.

```
(무변이 + 프로브)        => exit 0; tests=19 failures=0
     → 2열 유니크가 두 번째 INSERT를 거부한다

(M4 3열 유니크 + 프로브) => exit 1; tests=19 failures=2
     FAILED: QA PROBE: 같은 기간을 다른 user_id로 두 번 기록하면 거부된다 (2열 유니크의 관측 가능한 동작)
     FAILED: 설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지
```

  **2열과 3열은 동작으로 구별된다.** 프로브는 무변이에서 통과하고 M4에서 깨진다 — 양방향이 성립한다.

- 무엇이 걸려 있나: 3열 유니크에서는 `user_id`만 다르게 기록하면 **같은 예산 기간에 같은 임계값 푸시가 두 번 남는다.** 결정 6("기간당 1회, 재발송 없음")은 기간을 키로 삼는 규칙인데, 3열은 그 규칙을 "사용자별 기간당 1회"로 바꾼다. T-035가 발송 시 `user_id`를 `budget_periods`에서 읽지 않고 세션·요청에서 가져오는 순간 이 차이가 실사용 경로가 된다.
- **결론 판정**: "동작으로 고정할 수 없고 구조 단언이 유일한 방어선"은 **틀렸다.** 맞는 서술은 "완료 기준 2가 열거한 세 방향(재발송 거부·두 임계값 공존·다음 기간 재발송)만으로는 고정되지 않고, **네 번째 방향(다른 `user_id`·같은 기간)을 추가하면 동작으로도 고정된다**"이다. 구조 단언 `assertIndex`가 이 변이를 잡은 것은 사실이고 그 가치도 그대로다(`PushSchemaTest:371-372`).
- 수정 지시:
  1. `src/test/java/com/petgyebu/telo/push/PushSchemaTest.java` — `push_logs` 절(`:311` 앞)에 위 프로브에 해당하는 테스트를 한 건 더한다. 같은 `budget_period_id`·같은 `threshold_type`을 **다른 `user_id`로** raw INSERT 하고 `DataIntegrityViolationException` + 제약 이름 `uq_push_logs_period_threshold`를 단언한다. (검증자가 워크트리에서 돌린 구현을 그대로 옮기면 된다. 원본 저장소는 손대지 않았다.)
  2. `_workspace/t031_backend_summary.md:143-149` — "그런 행을 만들 수 없다" / "이 축은 동작 단언으로 고정되지 않는다"를 정정한다. 근거는 복합 FK가 없다는 사실이다.
  3. `docs/11-troubleshooting-log.md` 21번 — "진단"의 "그런 두 행을 만들 수 없다", "원인"의 "스키마에 구조적으로 도달할 수 없는 상태", "배운 것"의 결론이 같은 오류를 담고 있다. **포트폴리오 문서이므로 특히 중요하다.** 항목 자체는 살리되(동작 단언 셋이 부족했던 것은 사실이다) 진단을 "복합 FK가 없어 사실은 도달 가능한 상태였고, 검증 시점에 그것을 놓쳤다"로 고치는 편이 오히려 기록으로서 강해진다. `CLAUDE.md`가 "오판했다면 그것도 남긴다"고 적은 그대로다.

## 7. 축 7(엔티티 경로) — M10·M11 재현
- 판정: PASS
- 검증 대상: `t031_backend_summary.md:111-112` vs 검증자 재현
- 사유: 둘 다 **정확히 한 테스트씩** 잡힌다. 주장대로다.

```
=== M10-pushlog-readAt-eq-sentAt => exit 1; tests=18 failures=1
      PushLog 엔티티로 저장하면 read_at이 NULL이다 — 발송 직후는 읽지 않은 상태다
      [발송 직후인데 read_at이 채워져 있다. 확인율 KPI가 항상 100%가 된다]

=== M11-token-updatedAt-unset => exit 1; tests=18 failures=1
      PushDeviceToken 엔티티로 저장한 값이 그대로 내려간다 — 엔티티 경로
      DataIntegrityViolationException: null value in column "updated_at" ...
```

- M10은 KPI 계측(결정 7)을 직접 지탱한다. `read_at`이 발송 시각으로 채워지면 "예산 초과 경고 확인율"이 언제나 100%로 보고된다. 엔티티 경로가 없었다면 raw INSERT 단언만으로는 전부 통과한다 — T-041 QA의 FIX-2와 같은 계열을 이번에는 선제적으로 막았다.
- 검증자가 추가로 돌린 엔티티 변이: `@Enumerated(EnumType.STRING)` 제거(X10) → `ddl-auto: validate`가 컨텍스트 기동에서 막아 **18건 전부 실패**. 자기 보고의 M14와 같은 양상이다.

## 8. 검증자가 직접 설계한 변이 — 20종
- 판정: **FIX** (미검출 2종)
- 검증 대상: 지시받은 후보 7종을 포함해 검증자가 설계한 변이 20종 vs `PushSchemaTest` 18건
- 사유: **DB 제약 변이는 14종 전부 잡힌다.** 새어 나간 것은 **엔티티가 넘긴 시각 값**뿐이다.

| 변이 | 결과 | 걸린 테스트 |
|---|---|---|
| M1 `push_logs` 유니크 제거 | 잡힘 (2) | 재발송 거부 + `indexdef` |
| M2 유니크 `(budget_period_id)` 1열 | 잡힘 (3) | 두 임계값 공존 외 2 |
| M3 유니크 `(threshold_type)` 1열 | 잡힘 (8) | 다음 기간 재발송 외 7 |
| M4 유니크 3열(`user_id` 추가) | 잡힘 (1) | `indexdef` — **6번 참고** |
| M10 `PushLog` 생성자 `readAt = sentAt` | 잡힘 (1) | 엔티티 경로 |
| M11 `PushDeviceToken` 생성자 `updatedAt` 미대입 | 잡힘 (1) | 엔티티 경로 |
| **X1 `PushLog` 생성자가 `sentAt`에 `+1일`을 저장** | **미검출** | — |
| **X2 `PushDeviceToken` 생성자가 `updatedAt`에 `+1년`을 저장** | **미검출** | — |
| X3 `fcm_token` `VARCHAR(512)`→`(255)` | 잡힘 (1) | `character_maximum_length` |
| X4 `sent_at`을 NULL 허용으로 | 잡힘 (1) | nullable 맵 |
| X5 `platform` CHECK에서 `'IOS'` 제거 | 잡힘 (3) | CHECK 허용 외 2 |
| X6 `ix_push_device_tokens_user_id`의 대상 열 변경 | 잡힘 (1) | `indexdef` |
| X7 `push_logs`에 `updated_at` 열 추가 | 잡힘 (1) | nullable 맵(통째 비교라 잉여 열도 잡는다) |
| X8 `ck_push_logs_threshold_type` 통째 삭제 | 잡힘 (1) | CHECK 거부 |
| X9 엔티티 `@Column(length = 512)` 제거 | 미검출 (지적 아님) | — |
| X10 `PushDeviceToken`의 `@Enumerated(STRING)` 제거 | 잡힘 (18) | 컨텍스트 기동 |
| X11 `PushLog.sentAt`의 `updatable = false` 제거 | 미검출 (지적 아님) | — |
| X12 유니크를 **평범한 인덱스**로(이름 유지) | 잡힘 (1) | 재발송 거부 |
| X13 `fcm_token` 유니크를 `(user_id, fcm_token)` 2열로 | 잡힘 (2) | 토큰 중복 거부 + `indexdef` |
| X14 `created_at`을 NULL 허용으로 | 잡힘 (1) | nullable 맵 |

- **지시받은 후보 7종은 X3·X4·X5·X6·X7·X8이 전부 잡았다.** 남은 하나, "`read_at`을 `sent_at`보다 이르게 하는 제약이 없는 것"은 **사실이나 지적이 아니다.** 설계 4.4에도 요구사항 결정 7에도 그런 제약이 없고, 열람은 항상 발송 뒤에 오므로 애플리케이션이 만들 수 없는 값이다. 범위 밖 처리로 본다(기록용).
- X9·X11은 DB가 진실의 원천이고 그쪽은 `columnTypesMatchDesign`이 못 박으므로 지적이 아니다. X11은 T-041의 `UserItem.itemType`처럼 **설계가 명시적으로 요구한** 경우가 아니다.
- X12는 T-041 QA 5번이 남긴 `assertIndex`의 `endsWith` 한계를 다시 확인한 것이다. 이번에도 **거부 테스트 하나에만** 매달려 있다. 이월 사항이지 이번 Task의 지적이 아니다.

### FIX-1 — 엔티티가 넘긴 시각을 바꿔도 18건이 전부 통과한다
- 파일: `src/main/java/com/petgyebu/telo/push/domain/PushLog.java:76`, `src/main/java/com/petgyebu/telo/push/domain/PushDeviceToken.java:68-69`
- 변이: `this.sentAt = Objects.requireNonNull(sentAt, "sentAt").plusDays(1);` → **`exit 0; tests=18 failures=0`**. `this.updatedAt = now.plusYears(1);` → **`exit 0; tests=18 failures=0`**.
- 왜 지금 테스트가 못 잡나: 엔티티 경로 단언이 `fcm_token`·`platform`·`user_id`·`budget_period_id`·`threshold_type`은 **값으로** 비교하는데, 시각 세 개는 `isNotNull()`만 본다(`PushSchemaTest:181-186`, `:334-336`). null만 걸러지고 값은 무엇이든 통과한다.
- 왜 문제인가: `sent_at`은 "기간당 1회" 판정과 KPI의 기준 시각이고 `updated_at`은 토큰 갱신 판정의 기준이다. 둘 다 호출자가 KST 시계로 넘기는 값인데(트러블슈팅 13번이 타임존을 런타임에 강제한 이유), 엔티티가 그 값을 그대로 쓰는지는 아무도 확인하지 않는다. T-035·T-034가 이 생성자를 쓰는 순간 드러나겠지만 그때는 원인이 배치나 API로 보인다.
- 수정 지시: 두 단언을 `isNotNull()`에서 **값 비교**로 바꾼다.
  - `PushSchemaTest:334-336` — `assertThat(((OffsetDateTime) row.get("sent_at")).toInstant()).isEqualTo(sentAt.toInstant())`
  - `PushSchemaTest:181-186` — `created_at`·`updated_at`을 같은 방식으로 `now`와 비교한다.
  새 테스트는 필요 없다. 한 줄씩 바꾸는 것으로 X1·X2가 함께 잡힌다.

## 9. 범위 준수
- 판정: PASS
- 검증 대상: `_workspace/t031_00_input.md:10` "만들지 않는 것" vs 실제 변경 파일 11개
- 사유: 앞서간 것도, 빠진 것도 없다.
  - **앞서가지 않았다**: `src/main/java/com/petgyebu/telo/push/` 아래에 서비스·컨트롤러·DTO가 0개다. 검증자가 푸시 패키지 전체에서 `firebase|fcm.*send|Upstash|redis|RestController|@Service|@Scheduled`를 검색했고, 나온 것은 **주석의 언급 두 곳뿐**이다(`PushLog:26-27`의 Upstash·Redis 설명). 토큰 등록 API·upsert(T-034), FCM 발송(T-035), 열람 보고 API(T-036), 알림 권한 흐름, Upstash 중복 판정 — 전부 없다. 리포지토리 둘 다 **빈 인터페이스**다.
  - **빠지지 않았다**: 테이블 2개, CHECK 2개, UNIQUE 2개, FK 3개, 인덱스 1개가 전부 들어왔다. 엔티티 2 + enum 2 + 리포지토리 2 + 테스트 1.
  - **기존 여덟 스키마 테스트 파일을 건드리지 않았다**(입력 명세 :76). `git show --stat`로 확인했다.
  - 리포지토리 사용처가 테스트뿐인 것은 이번에는 신호가 아니다 — 엔티티 경로 테스트가 둘 다 실제로 지나간다(트러블슈팅 20번의 기준을 충족).

## 10. 관례 일치 / `PushThresholdType`을 새로 만든 판단
- 판정: PASS
- 검증 대상: 아홉 도메인 패키지 vs 기존 여덟, `PushThresholdType` vs `budget.domain.StatusCode`
- 사유:
  - 패키지가 `{domain}/domain` + `{domain}/repository`로 아홉 개 모두 동일하다(`account`·`budget`·`category`·`push`·`reward`·`shop`·`sync`·`transaction`·`user`). Lombok `@Getter` + `@NoArgsConstructor(PROTECTED)`, `Objects.requireNonNull` 생성자, `OffsetDateTime now`를 호출자가 넘기는 규칙, `@ManyToOne(LAZY, optional=false)` — 전부 기존과 같다. 양방향 `@OneToMany`·`@ManyToMany`는 여전히 0개다.
  - **`PushThresholdType`을 새로 만든 판단이 타당하다.** `StatusCode`는 `REST`·`WAKE`·`INTEREST`·`ANXIOUS`·`STRONG_WARNING`·`OVER_BUDGET` 6종이고(`StatusCode.java:9-16`) DB CHECK는 둘만 허용한다(SQL:32-33). `StatusCode`를 재사용하면 `ANXIOUS`를 넘기는 코드가 **컴파일을 통과하고 런타임 CHECK 위반으로만** 걸린다. 자기 보고의 서술 그대로이며, 검증자가 두 열거형을 직접 대조해 확인했다. 비용은 T-035에서 `StatusCode → PushThresholdType` 매핑 한 곳이 필요하다는 것뿐인데, 그 매핑이 바로 "6종 중 둘에서만 푸시가 나간다"는 결정 4를 코드에 남기는 지점이다.
  - `definedThresholdTypesAreAccepted`(:271)·`definedPlatformsAreAccepted`(:135)가 enum `values()`를 순회하므로 **열거형과 CHECK가 어긋나면 잡힌다.** 열거형에 값을 더하고 CHECK를 안 고치는 실수까지 막는다.

## 11. 트러블슈팅 21번 — 형식·숫자·분류
- 판정: PASS (형식·숫자·분류). **본문의 사실 오류는 6번 FIX로 따로 잡았다.**
- 검증 대상: `docs/11-troubleshooting-log.md` 21번 vs `CLAUDE.md` "트러블슈팅 기록 (자동 갱신)"
- 사유:
  - **형식**: 증상(실제 출력 인용) → 진단 → 원인 → 해결 → 검증(양방향) → 배운 것. 기존 항목과 같은 구조다.
  - **숫자 정합성**: 요약 표에 21번 행이 추가됐고, "되짚어 보기"의 "**21건 중 9건**(3·4·9·10·11·17·18·19·20번)"이 실제 분류와 맞는다 — 21번은 "조용한 실패"가 아니므로 분모만 20→21로 늘고 분자는 9로 유지된다. 검증자가 요약 표의 분류 열을 세어 확인했다. 양방향 검증 목록에도 푸시 항목이 추가됐고 "변이 14종(유니크 5, CHECK 1, FK 3, `NOT NULL` 1, 인덱스 1, 엔티티 3)"의 합이 14로 맞는다.
  - **분류 판정**: "검증 방법 / 테스트 설계"가 **맞다.** "조용한 실패"는 `17`·`18`·`19`·`20`처럼 **변이가 아무 테스트도 깨지 않고 통과한** 경우다. M4는 `designedIndexesExist`가 **실제로 잡았다.** 잡혔는데 잡은 경로가 예상과 달랐던 사건이므로 검증 방법 쪽이다. 요약 표의 "조용한 실패" 9건에 넣지 않은 것도 그래서 옳다.
  - PR 번호가 `—`인 것은 푸시 전이라 그렇다. 14·15번과 같은 표기이며, PR 생성 시 채워야 한다(아래 리더 확인 요청).

## 12. Task 번호 참조
- 판정: **FIX** (우선순위 낮음. 코드 영향 없음)
- 검증 대상: `PushDeviceToken.java:28`, `PushLog.java:33-34`, `V202609212337__create_push.sql:8` vs `docs/10-task-backlog.md:110-116`
- 사유: 세 곳이 "T-032 이후 범위"라고 적는데, 백로그상 **T-032는 상태 계산·메인 홈 API**이고 여기 언급된 것들의 실제 주인은 다르다.
  - 토큰 등록 API와 upsert → **T-034**(`:114`)
  - FCM 발송 → **T-035**(`:115`)
  - 열람 이벤트 수신 API → **T-036**(`:116`)
  입력 명세(`t031_00_input.md:25`)가 "T-032 이후"라고 적었으므로 지시를 따른 것이지 창작은 아니다. 다만 읽는 사람은 T-032를 찾아보고 아무것도 못 찾는다. T-041 QA 14번이 지적한 것과 같은 계열이다.
- 수정 지시: `PushDeviceToken.java:28`을 "토큰 등록 API와 upsert는 T-034, 실제 FCM 발송은 T-035 범위라 여기에 아직 없다"로, `PushLog.java:33-34`를 "열람 이벤트 수신 API는 T-036, 실제 FCM 발송은 T-035"로, `V202609212337__create_push.sql:8`을 "그 upsert 로직은 T-034 몫이다"로 고친다. 푸시 전 같은 커밋에 넣는 편이 낫다.

---

## 리더 확인 요청

- **푸시를 막을 사유는 없다.** 스키마·엔티티·범위·관례에 결함이 없고, 검증 축 7가지가 두 테이블 전부에 처음부터 들어와 있으며, 검증자가 설계한 것을 포함해 DB 제약 변이 14종이 전부 잡힌다. 자기 보고의 변이 결과는 재현한 범위에서 모두 사실이었다.
- **FIX 3건 중 6번이 본질이다.** 코드는 옳은데 **기록이 틀렸다.** "2열과 3열을 동작으로 구별할 수 없다"는 서술이 `_workspace` 요약과 **포트폴리오용 트러블슈팅 21번** 양쪽에 들어가 있고, 검증자가 프로브로 반증했다. 항목을 지울 필요는 없다 — 동작 단언 셋이 부족했다는 골자는 맞다. 진단을 "복합 FK가 없어 사실은 도달 가능했고 그것을 놓쳤다"로 고치면 기록이 더 정확하고 더 강해진다.
- **FIX-1(시각 값 단언)은 축 7의 다음 단계다.** 축 7은 "엔티티를 리포지토리로 저장하고 되읽는다"까지 요구했고 그것은 지켜졌다. 그런데 되읽은 값 중 **시각만 `isNotNull()`로 남아** 변이가 새어 나갔다. 축의 문구를 "되읽어 **넘긴 값과 같은지** 확인한다"로 다듬을 것을 제안한다(`docs/10-task-backlog.md:231`).
- **이월 사항**: `assertIndex`가 이제 **일곱 벌**이다(T-013 QA 6번·T-014 QA 13번의 통일 권고가 그대로 남아 있다). `endsWith` 한계도 그대로여서 X12(`UNIQUE` 제거, 인덱스 유지)는 여전히 거부 테스트 하나에만 매달려 있다.
- **푸시·PR 시점에 할 것**: `docs/10-task-backlog.md:110`의 T-031이 아직 `[ ]`다. T-006·T-013·T-014·T-041과 같은 형식으로 갱신하고, 트러블슈팅 21번의 PR 번호 `—`를 실제 번호로 채워야 한다.
