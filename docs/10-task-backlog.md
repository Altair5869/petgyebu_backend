# Task 백로그

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱
- 최종 갱신: 2026-09-17
- 도출 근거: `08-feature-implementation-map.md`(API·테이블), `09-db-design.md`(스키마), `06-sprint-plan.md`(순서)

`08`과 `09`에서 확정한 내용을 **PR 하나 단위**로 쪼갠 목록이다. Task 하나가 끝나면 브랜치 하나가 병합 가능한 상태가 된다.

## 사용법

- 착수 전에 `차단` 열을 먼저 본다. 차단된 Task는 외부 조건이 풀리기 전까지 시작할 수 없다.
- `의존` 열의 Task가 먼저 끝나야 한다.
- 완료하면 체크박스를 `[x]`로 바꾸고, PR 번호를 `비고`에 적는다.

## 브랜치 이름 규칙

`07-branch-strategy.md`는 기능 ID 하나당 브랜치 하나를 원칙으로 한다. 다만 기능 하나가 스키마·API·배치로 나뉘어 PR이 여러 개가 되는 경우가 있어, 접미사로 구분한다.

```
feature/F-QJXRMD-schema     스키마·엔티티
feature/F-QJXRMD-login      API 구현
chore/{슬러그}               F-ID 없는 인프라·기술 부채
```

---

## 현재 차단 요소

| 차단 ID | 내용 | 푸는 방법 | 막고 있는 Task |
|---|---|---|---|
| **B-KAKAO** | 카카오 개발자 애플리케이션 미등록 | 카카오 디벨로퍼스에서 앱 생성, 동의 항목 설정, REST API 키 발급 | T-002 |
| **B-TERMS** | 이용약관·개인정보처리방침 문안 없음 | 문안 작성 (코드 작업 아님) | T-004 |
| **B-CODEF** | 코드에프 데모 서비스 미승인 | 데모 신청 접수 후 승인 대기. **대기가 길 수 있어 지금 넣는 것이 이득이다** | T-009, T-015 |
| **B-REDIS** | Upstash Redis 미프로비저닝 | Upstash 계정 생성 후 인스턴스 생성 | T-003(부분), T-009, T-020, T-021, T-050 |
| **B-GCP** | GCP 프로젝트·Cloud SQL 미프로비저닝 | GCP 계정·결제 설정 후 프로비저닝 | T-048, T-049 |

차단되지 않은 Task부터 진행할 수 있다. 아래 "지금 착수 가능한 Task" 참고.

---

## Sprint 0 — 계정

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [x] | T-001 | `users`·`user_consents` 스키마와 엔티티 | `feature/F-QJXRMD-schema` | ✅ 완료. QA PASS 10 / FIX 2 / REDO 0 | — | — |
| [ ] | T-002 | 카카오 소셜 로그인 API | `feature/F-QJXRMD-login` | 카카오 인가 코드로 계정 생성·조회가 되고, 신규 가입 시 `requiresConsent`가 내려온다 | T-001 | **B-KAKAO** |
| [ ] | T-003 | JWT 발급·검증·회전 | `feature/F-QJXRMD-token` | 액세스 30분·리프레시 14일로 발급된다. 리프레시 사용 시 이전 토큰이 폐기되고, 폐기 토큰 재사용이 감지되면 전 세션이 무효화된다 | T-001 | 부분 **B-REDIS** |
| [ ] | T-004 | 약관·개인정보 동의 API | `feature/F-QJXRMD-consent` | 필수 동의 없이는 가입이 완료되지 않는다. 동의 기록에 종류·버전·시각이 남는다 | T-001 | **B-TERMS** |
| [ ] | T-005 | 회원 탈퇴 API | `feature/F-ZPNVKT-withdrawal` | 탈퇴 후 해당 사용자의 모든 행이 사라진다(`ON DELETE CASCADE` 확인). 전 세션이 무효화된다 | T-001, T-003 | — |

**T-003 부분 차단**: 폐기 상태 저장을 Upstash에 두기로 했으므로(`09-db-design.md` 2.2절) 실제 Redis 없이는 종단 검증이 안 된다. 인메모리 구현으로 로직을 먼저 만들고 Upstash 연결 시 교체하는 방식은 가능하다.

**T-005는 코드에프 연동 전이라 connectedId 해지 호출이 비어 있다.** 해지 연동은 T-006 이후에 채운다.

---

## Sprint 1 — 계좌 연결

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [ ] | T-006 | `accounts` 스키마와 엔티티 | `feature/F-TEDWWF-schema` | 마이그레이션 적용, `PostgresMigrationTest` 통과. `bank_code`가 VARCHAR라 앞자리 0이 보존된다 | T-001 | — |
| [ ] | T-007 | 금융 데이터 조회 동의 API | `feature/F-TEDWWF-consent` | 동의 없이 계좌 연결을 시작할 수 없다. 가입 시 동의와 분리 기록된다 | T-004, T-006 | — |
| [ ] | T-008 | 코드에프 SDK 연동 골격 (SANDBOX) | `feature/F-TEDWWF-codef-client` | SANDBOX 자격증명으로 API 호출이 왕복한다. `EasyCodefUtil.encryptRSA()` 사용 경로 확인 | T-006 | — |
| [ ] | T-009 | 계좌 연결 시작·2-way 추가인증 콜백 | `feature/F-TEDWWF-connect` | 인증 성공 시 코드에프가 반환한 계좌 목록 **전체가 배열로** 저장된다. 2-way 상태가 Upstash에 TTL로 저장된다 | T-008 | **B-CODEF**, **B-REDIS** |
| [ ] | T-010 | 계좌 목록·해제 API | `feature/F-TEDWWF-manage` | 해제 후 동기화 대상에서 빠진다. 해제 시 connectedId가 해지된다 | T-009 | — |
| [ ] | T-011 | 재인증 필요 상태와 사용자 유도 | `feature/F-TEDWWF-reauth` | 동의 만료·인증 실패 시 `reauth_required`가 켜지고 목록 응답에 노출된다 | T-010 | — |
| [ ] | T-012 | 탈퇴 시 connectedId 해지 연동 | `chore/withdrawal-codef-revoke` | 탈퇴 시 연결된 모든 계좌가 해지된다. 해지 실패해도 삭제는 진행되고 실패 건이 기록된다 | T-005, T-010 | — |

**T-009의 2-way 콜백은 SANDBOX에서 검증이 불가능하다**(`06-sprint-plan.md` Sprint 1 리스크). 고정 응답이라 실제 챌린지-리스폰스 왕복이 발생하지 않는다. DEMO 승인 후 첫 실전 통합테스트가 일어난다는 전제로 일정에 시간을 따로 잡아야 한다.

---

## Sprint 2 — 거래 수집과 조회

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [ ] | T-013 | `categories`·`merchant_keyword_rules` 스키마와 시드 | `feature/F-OAVYWT-category-schema` | 카테고리 10개가 시드로 들어간다. `keywords`에 GIN 인덱스가 생성된다 | T-001 | — |
| [ ] | T-014 | `transactions`·`transfer_links`·`sync_attempts` 스키마와 엔티티 | `feature/F-OAVYWT-schema` | 마이그레이션 적용, 유니크·부분 인덱스 전부 생성 확인 | T-006, T-013 | — |
| [ ] | T-015 | 코드에프 거래 조회 연동과 90일 페이지네이션 | `feature/F-OAVYWT-fetch` | 90일치가 페이지 단위 순차 호출로 전부 수집된다. 단일 호출로 안 채워지는 경우를 재현해 확인 | T-008, T-014 | **B-CODEF** |
| [ ] | T-016 | 중복 거래 판정과 저장 | `feature/F-OAVYWT-dedup` | 같은 거래를 두 번 수집해도 행이 늘지 않는다. 유니크 제약 위반이 정상 처리된다 | T-015 | — |
| [ ] | T-017 | 이체 후보 매칭 함수 | `feature/F-OAVYWT-transfer-match` | 조건 3개(금액 완전 일치 + 10분 이내 + 연결된 계좌 쌍)를 AND로 검사한다. 하나의 출금에 여러 입금이 대응하면 자동 연결하지 않는다. **F-KBBFRU에서 재사용 가능한 형태로 분리** | T-016 | — |
| [ ] | T-018 | 환불 순액 처리 | `feature/F-OAVYWT-refund` | 원거래와 환불이 각각 행으로 저장되고 연결된다. 집계 시 순액이 반영된다 | T-016 | — |
| [ ] | T-019 | 자동 카테고리 분류 | `feature/F-OAVYWT-classify` | 키워드 매칭 시 카테고리가 지정되고 `initial_classification_source`가 `AUTO_MATCHED`로 남는다. 미매칭은 `UNCLASSIFIED` | T-013, T-016 | — |
| [ ] | T-020 | 동기화 스케줄러와 분산락 | `feature/F-OAVYWT-scheduler` | 하루 2회(09:00·21:00 KST) 실행된다. ShedLock으로 중복 실행이 막힌다. 재시도 3회 지수 백오프 동작 | T-016 | **B-REDIS** |
| [ ] | T-021 | 수동 새로고침 API | `feature/F-OAVYWT-manual-sync` | 1시간 이내 재호출은 캐시를 반환한다. 명시적 새로고침은 캐시를 무시하되 분당 1회로 제한된다 | T-020 | **B-REDIS** |
| [ ] | T-022 | 거래 목록·상세 API | `feature/F-RNECMQ-list` | 기간 필터·최신순 동작. 목록은 순액만, 상세는 원거래·환불 금액을 각각 보여준다. 숨김 계좌 거래가 목록에서 빠진다 | T-018 | — |

---

## Sprint 3 — 예산·캐릭터·거래 수정

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [ ] | T-023 | `budget_periods`·`status_thresholds` 스키마와 엔티티 | `feature/F-FZUVLV-schema` | 마이그레이션 적용. `(user_id, period_start)` 유니크 동작 | T-001 | — |
| [ ] | T-024 | **소비 지출 집계 함수 (공통)** | `feature/F-FZUVLV-expense-aggregation` | 이체 제외·환불 순액·가계부 제외 계좌 제외가 모두 반영된다. **예산·캐릭터 상태·소비 요약·카테고리 분석이 전부 이 함수를 쓴다** | T-018, T-022 | — |
| [ ] | T-025 | 예산 설정 API와 구간 검증 | `feature/F-FZUVLV-api` | 0% 고정·오름차순·중복·공백·6단계 누락 검증이 각각 해당 행을 지목하며 저장을 막는다. 기본값 6단계가 프리필된다 | T-023 | — |
| [ ] | T-026 | 사용률 계산과 경계값 판정 | `feature/F-FZUVLV-usage-rate` | 오른쪽 닫힘 `(시작, 끝]` 판정. **40%는 "기상"이 아니라 "휴식"**, 0%는 첫 구간에 포함. 목표 수정 시 스냅샷은 불변 | T-024, T-025 | — |
| [ ] | T-027 | 캐릭터 선택 API | `feature/F-GGIDHG-api` | 선택·변경이 저장되고 변경 시각이 남는다 | T-001 | — |
| [ ] | T-028 | 거래 분류·유형 수정 API와 이력 | `feature/F-KBBFRU-edit` | 수정이 집계에 즉시 반영된다. **`initial_classification_source`는 갱신되지 않는다**(`updatable = false` 확인) | T-022, T-024 | — |
| [ ] | T-029 | 이체 확인·연결 해제 API | `feature/F-KBBFRU-transfer` | T-017의 매칭 함수를 재사용한다. 해제 시 원래 수입·지출 유형으로 재계산된다 | T-017, T-028 | — |
| [ ] | T-030 | 계좌 가계부 포함·제외 토글 API | `feature/F-KBBFRU-account-toggle` | 제외 시 집계에서 빠지고, "완전히 숨김"이면 목록에서도 사라진다 | T-022, T-024 | — |

**T-024가 이 스프린트의 핵심이다.** 화면마다 집계를 따로 구현하면 숫자가 어긋난다(`09-db-design.md` 9장 1번). 먼저 만들고 나머지가 그것을 쓰게 한다.

---

## Sprint 4 — 반려동물 피드백과 푸시

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [ ] | T-031 | `push_device_tokens`·`push_logs` 스키마 | `feature/F-VZFPVW-schema` | 마이그레이션 적용. `(budget_period_id, threshold_type)` 유니크 동작 | T-023 | — |
| [ ] | T-032 | 상태 계산과 메인 홈 API | `feature/F-VZFPVW-home` | 사용률→6단계 매핑. 상태 문구·사용률·남은 예산 또는 초과 금액이 함께 내려온다. T-024 집계 함수 사용 | T-026, T-027 | — |
| [ ] | T-033 | 상태 설명·범례 API | `feature/F-VZFPVW-legend` | 6단계 구간과 의미가 내려온다 | T-032 | — |
| [ ] | T-034 | 디바이스 토큰 등록·해제 API | `feature/F-VZFPVW-device-token` | 토큰 upsert가 동작하고 같은 토큰의 소유자 이전이 처리된다 | T-031 | — |
| [ ] | T-035 | 임계값 진입 감지와 FCM 발송 | `feature/F-VZFPVW-push` | 90%·100% 진입 시 각 1회 발송. **재발송되지 않는다** — 환불·목표 상향으로 내려갔다 재진입해도 유니크 제약이 막는다 | T-032, T-034 | — |
| [ ] | T-036 | 알림 열람 보고 API | `feature/F-VZFPVW-push-read` | 앱이 알림 탭을 보고하면 `read_at`이 기록된다. KPI 확인율 집계 가능 | T-035 | — |

---

## Sprint 5 — 소비 분석

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [ ] | T-037 | 소비 요약 API | `feature/F-TKSQSR-summary` | 총지출·사용률·남은 예산·상위 3개 카테고리·직전 기간 증감률. 신규 사용자는 증감률 줄이 빠진다 | T-024, T-026 | — |
| [ ] | T-038 | 카테고리별 집계 API | `feature/F-IWBASY-category` | **카테고리별 합계가 총지출과 일치한다.** 동률이면 최근 거래가 있는 쪽 우선 | T-024 | — |
| [ ] | T-039 | 기간별 집계 API | `feature/F-IWBASY-period` | 8주 이하는 주간, 초과는 월간으로 자동 전환. **기간별 합계가 총지출과 일치한다.** 윈도우 함수 사용 | T-024 | — |

**합계 정합성이 이 스프린트의 완료 기준이다.** 거래 목록 합계, 예산 계산 합계, 카테고리 합계, 기간 합계가 전부 같은 숫자여야 한다.

---

## Sprint 6 — 배치·보상·상점

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [ ] | T-040 | Spring Batch 메타 테이블 마이그레이션 | `chore/batch-schema` | `schema-postgresql.sql`을 마이그레이션으로 옮긴다. **직접 작성하지 않는다** | T-023 | — |
| [ ] | T-041 | 보상·상점 스키마와 시드 | `feature/F-HPWCNJ-schema` | `credit_balances`·`reward_grants`·`shop_items`·`user_items` 생성. 슬롯 4종 시드. `(user_id, item_type) WHERE is_placed` 부분 유니크 동작 | T-023 | — |
| [ ] | T-042 | s9 예산 기간 전환 배치 | `feature/F-FZUVLV-period-batch` | 매일 KST 00:05 실행. 기간 종료→새 기간 생성→상태 재계산이 **단일 트랜잭션**. 중간 실패 시 전부 롤백되는 것을 확인 | T-026, T-040 | — |
| [ ] | T-043 | 절약 조건 판정과 크레딧 지급 | `feature/F-EZZFNU-reward` | 스냅샷 기준 판정. 10% 이상 감소 조건. 첫 기간은 비교 조건 생략. 중복 지급이 유니크 제약으로 막힌다. `SELECT FOR UPDATE` 확인 | T-041, T-042 | — |
| [ ] | T-044 | 상점 목록·구매 API | `feature/F-HPWCNJ-shop` | 잔액 부족 시 구매가 막히고 부족분이 안내된다. 구매 시 `price_paid`에 당시 가격이 남는다 | T-041, T-043 | — |
| [ ] | T-045 | 보관함·방 배치 API | `feature/F-HPWCNJ-placement` | 슬롯당 1개 규칙이 DB 제약으로 보장된다. 같은 슬롯에 다른 아이템을 배치하면 기존 것이 해제된다. 미보유 아이템은 배치 불가 | T-044 | — |
| [ ] | T-046 | 보상 알림 순서 보장 | `feature/F-EZZFNU-notification` | 기간 종료→생성→재계산→판정→알림 순서가 지켜진다 | T-043 | — |

---

## 기술 부채와 인프라

| | ID | 제목 | 브랜치 | 완료 기준 | 의존 | 차단 |
|---|---|---|---|---|---|---|
| [x] | T-047 | out-of-order 마이그레이션 방침 결정 | `chore/flyway-out-of-order` | ✅ 완료. `out-of-order: false` 명시, 병합 전 재타임스탬프 원칙, `scripts/check-migration-order.sh`로 CI 강제 | — | — |
| [ ] | T-048 | CI의 "조용한 성공" 제거 | `chore/ci-fail-without-credentials` | GCP 자격증명이 없을 때 배포·검증 스텝이 스킵되고 잡이 성공으로 끝나는 구조를 고친다 | — | **B-GCP** |
| [ ] | T-049 | Cloud Run 플래그 검증 스크립트 보강 | `chore/verify-serving-revision` | `status.latestReadyRevisionName`을 대조해 실제 트래픽 받는 리비전을 검증한다. 애노테이션 키 실물 확인 | — | **B-GCP** |
| [ ] | T-050 | Redis 헬스 인디케이터 복구 | `chore/enable-redis-health` | `application-local.yaml`의 비활성 설정을 제거한다 | — | **B-REDIS** |
| [ ] | T-051 | 운영 프로필 springdoc 비활성화 검토 | `chore/springdoc-prod-policy` | 운영에서 API 문서를 노출할지 정하고 반영한다 | — | — |
| [ ] | T-052 | **기준 타임존 KST 런타임 강제** | `chore/enforce-kst-timezone` | `hibernate.jdbc.time_zone`, JVM `user.timezone`, Dockerfile `TZ` 중 어디서 강제할지 정하고 반영한다. 서버 타임존이 UTC일 때 월 경계·코호트 계산이 어긋나지 않는지 테스트로 확인한다 | — | — |

---

## 지금 착수 가능한 Task

외부 조건 없이 바로 시작할 수 있는 것들이다. 의존 관계 순서대로다.

| 순서 | ID | 제목 | 비고 |
|---|---|---|---|
| ~~1~~ | ~~T-047~~ | ~~out-of-order 마이그레이션 방침~~ | ✅ 2026-09-17 완료 |
| ~~2~~ | ~~T-001~~ | ~~`users`·`user_consents` 스키마와 엔티티~~ | ✅ 2026-09-18 완료 |
| 2 | T-052 | 기준 타임존 KST 런타임 강제 | **예산·배치 Task 전에 처리해야 한다.** 아래 참고 |
| 3 | T-023 | `budget_periods`·`status_thresholds` 스키마 | T-001만 있으면 된다 |
| 4 | T-013 | `categories`·`merchant_keyword_rules` 스키마와 시드 | 카테고리 10개는 이미 확정됐다 |
| 5 | T-006 | `accounts` 스키마와 엔티티 | 코드에프 연동 없이 스키마만 |
| 6 | T-041 | 보상·상점 스키마와 시드 | 슬롯 4종도 확정됐다 |
| 7 | T-051 | 운영 프로필 springdoc 정책 | 작은 결정 |

**T-052를 예산·배치 Task 전에 처리해야 하는 이유** (2026-09-18 T-001 QA에서 발견): `09-db-design.md` 0장이 "기준 타임존 KST 고정"을 정했는데 런타임에 강제하는 설정이 어디에도 없다. `hibernate.jdbc.time_zone`, JVM `user.timezone`, Dockerfile `TZ` 전부 없는 것을 확인했다. 시각을 절대값으로 저장·조회하는 한 문제가 없지만, **날짜로 자르는 계산에서 서버가 UTC면 하루가 어긋난다** — `joined_at` 기반 24시간·7일 코호트(T-001 완료), 월간 예산 기간 경계(T-023·T-026), s9 배치 실행 시각(T-042)이 전부 해당한다.

**스키마 Task를 먼저 몰아서 하는 것이 유리하다.** 외부 의존이 없고, 마이그레이션이 쌓이면 `PostgresMigrationTest`가 그때부터 실질적인 안전망으로 동작한다. API Task는 대부분 카카오·코드에프 승인을 기다려야 한다.

---

## 통계

| 구분 | 개수 |
|---|---|
| 전체 Task | 52 |
| 차단 없음 | 42 |
| 외부 조건에 막힌 것 | 10 |
| 완료 | 2 (T-047, T-001) |
| 지금 바로 착수 가능(차단·의존 모두 해소) | 6 |

차단된 10개의 내역은 이렇다.

| 차단 | Task |
|---|---|
| B-KAKAO | T-002 |
| B-TERMS | T-004 |
| B-CODEF | T-009, T-015 |
| B-REDIS | T-003(부분), T-009, T-020, T-021, T-050 |
| B-GCP | T-048, T-049 |

**차단 없는 41개 중 34개는 앞선 Task에 의존해 순서를 기다리는 것뿐이다.** 외부 조건과 무관하므로 앞 단계가 끝나는 대로 이어서 진행할 수 있다.
