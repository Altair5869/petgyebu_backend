# 기능 구현 정리

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱
- 최종 갱신: 2026-09-17
- 관련 문서: `02-requirements-features.md`(기능 명세), `06-sprint-plan.md`(구현 순서), `09-db-design.md`(스키마)

각 기능(F-XXXXX)을 구현할 때 실제로 만들어야 하는 API와 건드리는 테이블을 정리했다. 기능 명세의 슬롯을 읽어 도출한 것이며, 명세에 없는 기능을 새로 만들지 않았다.

API 경로는 제안이다. 실제 구현 시 바꿔도 되지만, 바꾸면 이 문서도 함께 고친다.

---

## 요약 — 스프린트별 API 개수와 신규 테이블

| 스프린트 | 기능 | API 수 | 신규 테이블 |
|---|---|---|---|
| Sprint 0 | F-QJXRMD 로그인, F-ZPNVKT 탈퇴 | 5 | `users`, `user_consents` (리프레시 토큰 폐기 상태는 Redis) |
| Sprint 1 | F-TEDWWF 계좌 연결 | 6 | `accounts` |
| Sprint 2 | F-OAVYWT 동기화, F-RNECMQ 조회 | 4 | `transactions`, `categories`, `merchant_keyword_rules`, `transfer_links`, `sync_attempts` |
| Sprint 3 | F-FZUVLV 예산, F-GGIDHG 캐릭터, F-KBBFRU 수정 | 10 | `budget_periods`, `status_thresholds`, `transaction_edit_histories` |
| Sprint 4 | F-VZFPVW 피드백·푸시 | 5 | `push_device_tokens`, `push_logs` |
| Sprint 5 | F-TKSQSR 요약, F-IWBASY 분석 | 3 | 없음 (집계 쿼리만) |
| Sprint 6 | s9 배치, F-EZZFNU 보상, F-HPWCNJ 상점 | 6 | `credit_balances`, `reward_grants`, `shop_items`, `user_items` |

총 API 39개, 테이블 17개.

---

## Sprint 0 — 계정

### F-QJXRMD 계정 생성 및 로그인

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/auth/login/{provider}` | `provider`는 `kakao` 또는 `apple`. 소셜 인가 코드를 받아 검증하고 계정을 조회하거나 생성한다. 신규면 `requiresConsent: true`와 함께 임시 토큰을 반환한다 |
| POST | `/api/v1/auth/consents` | 이용약관·개인정보처리방침 동의를 저장하고 가입을 완료한다. 액세스·리프레시 토큰을 발급한다 |
| POST | `/api/v1/auth/refresh` | 리프레시 토큰 회전. 이전 토큰을 폐기하고 새로 발급한다. 폐기된 토큰이 다시 오면 해당 사용자의 모든 세션을 무효화한다 |
| POST | `/api/v1/auth/logout` | 현재 리프레시 토큰 폐기 |

**테이블**: `users`(생성), `user_consents`(생성)

**구현 시 주의**

- 이메일은 `null`일 수 있다(Q3). 이메일을 계정 식별자나 유니크 키로 쓰지 않는다.
- 계정 식별은 `(provider, provider_user_id)` 조합이다. 같은 사람이 카카오와 애플로 각각 가입하면 별도 계정이며 통합하지 않는다.
- 리프레시 토큰의 폐기 상태는 Upstash Redis에 TTL 14일로 저장한다(`05-infra-stack.md`). 테이블로 만들지 않아도 된다. Redis를 쓰지 않는 로컬 개발에서는 테이블 대체가 필요할 수 있어 `09-db-design.md`에 선택지로만 적어 뒀다.

### F-ZPNVKT 회원 탈퇴

| 메서드 | 경로 | 설명 |
|---|---|---|
| DELETE | `/api/v1/users/me` | 확인 절차를 거친 뒤 호출. 전체 데이터 즉시 삭제 + 코드에프 connectedId 해지 + 전 세션 무효화 |

**테이블**: 해당 사용자의 모든 행 삭제. 삭제 순서는 `09-db-design.md`의 FK 의존 그래프를 따른다.

**구현 시 주의**

- 코드에프 연결 해지에 실패해도 서비스 측 삭제는 진행한다. 실패한 해지 건은 재시도 대상으로 남긴다.
- 탈퇴 이벤트 기록에는 사용자를 식별할 수 있는 정보를 남기지 않는다.

---

## Sprint 1 — 계좌 연결

### F-TEDWWF 은행 계좌 연결

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/banks` | 지원 은행 20개 목록. 상수 테이블이며 DB를 쓰지 않는다 |
| POST | `/api/v1/accounts/connect/consent` | 코드에프 제3자 제공·금융거래정보 조회 동의 저장. 계좌 연결 시작 직전 단계 |
| POST | `/api/v1/accounts/connect` | 은행 선택 후 인증 시작. 2-way 추가인증이 필요하면 `jobIndex`/`threadIndex`/`jti`/`twoWayTimestamp`를 Upstash에 저장하고 추가인증 요청을 반환한다 |
| POST | `/api/v1/accounts/connect/two-way` | 추가인증 콜백 처리. 성공 시 코드에프가 반환한 계좌 목록 전체를 배열로 저장한다 |
| GET | `/api/v1/accounts` | 연결 계좌 목록. 은행명, 마스킹된 계좌번호, 마지막 동기화 시각, 재인증 필요 여부 |
| DELETE | `/api/v1/accounts/{accountId}` | 계좌 해제. 이후 동기화를 중단한다 |

**테이블**: `accounts`(생성), `user_consents`(금융 동의 추가)

**구현 시 주의**

- **단일 계좌를 가정하지 않는다.** 코드에프는 은행 인증 1회당 해당 명의의 전체 계좌를 배열로 반환한다.
- 재시도 횟수를 제한하지 않는다. 코드에프·은행 측 보안 정책 에러 메시지는 그대로 노출한다(F-OAVYWT의 시스템 재시도 3회와 정책이 다르다. 공통 retry 유틸로 묶지 말 것).
- 은행 조직코드처럼 앞자리 0이 있는 값은 YAML에서 따옴표로 감싼다.

---

## Sprint 2 — 거래 수집과 조회

### F-OAVYWT 거래 내역 자동 동기화

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/transactions/sync` | 수동 새로고침. 마지막 동기화 후 1시간 이내면 캐시를 반환한다. 명시적 새로고침은 캐시를 무시하되 분당 1회로 제한한다 |

스케줄러(API 아님): `@Scheduled` + ShedLock으로 하루 2회(09:00, 21:00 KST) 전체 계좌 동기화.

**테이블**: `transactions`(생성), `categories`(생성·시드), `merchant_keyword_rules`(생성·시드), `transfer_links`(생성), `sync_attempts`(생성), `accounts`(마지막 동기화 시각 갱신)

**구현 시 주의**

- 최초 연결 시 90일치는 **페이지 단위 순차 호출**로 채운다. 단일 호출로 전체 기간이 오지 않는다.
- 중복 판정 기준은 거래 식별 정보 + 일시 + 금액 + 거래처다. DB 유니크 제약으로 이중 방어한다.
- **이체 후보 자동 매칭은 별도 함수로 분리한다.** 조건 3개(금액 완전 일치 + 10분 이내 + 연결된 계좌 쌍)를 AND로 검사하며, F-KBBFRU의 수동 확인에서 재사용한다.
- 하나의 출금에 여러 입금이 대응하면 자동 연결하지 않고 사용자 확인 대상으로 남긴다.
- **최초 분류 출처 필드는 사용자가 분류를 수정해도 갱신하지 않는다**(KPI 계측용).
- Resilience4j 재시도 3회, 지수 백오프 1분→5분→15분.

### F-RNECMQ 거래 목록 조회

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/transactions` | 기간 필터, 최신순. 기본값은 현재 활성 예산 기간 |
| GET | `/api/v1/transactions/{transactionId}` | 상세. 원거래-환불 연결 관계와 이체 상대 거래 링크 포함 |

**테이블**: `transactions`, `transfer_links`, `accounts`, `categories` 조회

**구현 시 주의**

- 목록에는 **원거래-환불 순액만** 표시하고, 상세에서 원거래 금액·환불 금액·연결 링크를 보여준다.
- 계좌의 노출 방식이 "완전히 숨김"이면 목록·분석·예산 계산 **모두에서** 제외한다. "뱃지 표시"면 목록에는 남고 집계에서만 빠진다.
- 빈 상태 안내에 "연결한 계좌의 거래만 집계된다"를 넣는다(수동 입력은 MVP 제외).

---

## Sprint 3 — 예산·캐릭터·거래 수정

### F-FZUVLV 예산 기간 및 목표 설정

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/budgets/current` | 활성 예산 기간과 상태 구간 조회. 신규 사용자에게는 기본 임계값 6단계를 프리필해 반환한다 |
| PUT | `/api/v1/budgets/current` | 목표 금액 수정. 즉시 새 목표 기준으로 사용률이 갱신된다 |
| PUT | `/api/v1/budgets/current/thresholds` | 상태 구간 표 저장. 검증 실패 시 문제가 있는 행을 지목한다 |
| DELETE | `/api/v1/budgets/current` | 예산 설정 종료 |

**테이블**: `budget_periods`(생성), `status_thresholds`(생성)

**구현 시 주의**

- 예산 기간은 **매월 1일 00:00 KST ~ 말일 24:00 KST 고정**이며 사용자가 시작일을 바꿀 수 없다.
- 구간 검증: 첫 시작값 0% 고정, 오름차순, 중복·공백 없음, 6단계 모두 존재, 예산 초과는 100% 이상.
- 경계값 판정은 **오른쪽 닫힘** `(시작, 끝]`이며 0%만 예외적으로 첫 구간에 포함한다. 40%는 "기상"이 아니라 "휴식"이다.
- 목표 금액을 수정해도 **기간 시작 시점 스냅샷은 바꾸지 않는다**(F-EZZFNU 보상 판정 기준).
- **사용률 계산 함수는 하나로 통일한다.** 이체 제외·환불 순액·가계부 제외 계좌를 모두 반영하는 집계 단계를 공유해 예산 계산과 캐릭터 상태 계산이 항상 같은 숫자를 보게 한다.

### F-GGIDHG 반려동물 캐릭터 선택

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/characters` | 선택 가능한 캐릭터 2종(강아지·고양이)과 현재 선택 |
| PUT | `/api/v1/users/me/character` | 캐릭터 선택·변경 |

**테이블**: `users`(캐릭터 컬럼 갱신)

**구현 시 주의**: Lottie 에셋 12개(2종 × 6단계)는 Cloud Storage에 올리고 CDN으로 서빙한다. DB에는 캐릭터 유형만 저장한다.

### F-KBBFRU 거래 분류 및 유형 수정

| 메서드 | 경로 | 설명 |
|---|---|---|
| PATCH | `/api/v1/transactions/{transactionId}` | 카테고리·수입지출 구분·메모 수정 |
| POST | `/api/v1/transactions/{transactionId}/transfer` | 이체로 확인 |
| DELETE | `/api/v1/transactions/{transactionId}/transfer` | 자동 연결 해제. 원래 수입·지출 유형 기준으로 다시 계산한다 |
| PATCH | `/api/v1/accounts/{accountId}/budget-inclusion` | 가계부 집계 포함·제외 토글과 노출 방식(뱃지/숨김) |

**테이블**: `transactions`(갱신), `transfer_links`(갱신), `accounts`(갱신), `transaction_edit_histories`(생성)

**구현 시 주의**

- 사용자 수정이 자동 분류보다 우선한다. 단 **최초 분류 출처 필드는 건드리지 않는다**.
- 수정 결과는 소비 분석과 예산 사용률에 즉시 반영된다.

---

## Sprint 4 — 반려동물 피드백과 푸시

### F-VZFPVW 예산 사용률 연동 소비 피드백

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/home` | 메인 홈. 캐릭터 상태, 상태 묘사 문구, 사용률, 남은 예산 또는 초과 금액, 방에 배치된 아이템 4슬롯 |
| GET | `/api/v1/home/status-legend` | 상태 설명·범례 6단계 |
| POST | `/api/v1/devices/push-token` | FCM 디바이스 토큰 등록·갱신 |
| DELETE | `/api/v1/devices/push-token` | 토큰 해제 |
| POST | `/api/v1/push-logs/{pushLogId}/read` | 알림 열람 보고. KPI "예산 초과 경고 확인율" 계측용 |

**테이블**: `push_device_tokens`(생성), `push_logs`(생성)

**구현 시 주의**

- 상태 묘사 문구는 캐릭터 유형과 무관한 6단계 공통이다. 콘텐츠 테이블로 관리하되 값이 고정이므로 상수로 두어도 된다.
- 푸시는 **90%·100% 진입 시 각 1회, 예산 기간당 1회 한정**. 발송 기록 키는 `(budget_period_id, threshold_type)`이다.
- **재발송하지 않는다.** 환불이나 목표 상향으로 사용률이 내려갔다가 다시 진입해도 같은 기간에는 보내지 않는다(Q13).
- 푸시는 **모바일 전용**이다(Q9).

---

## Sprint 5 — 소비 분석

### F-TKSQSR 핵심 소비 요약

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/analytics/summary` | 총지출, 사용률, 남은 예산 또는 초과 금액, 상위 3개 카테고리, 직전 기간 대비 증감률, 마지막 동기화 시각 |

### F-IWBASY 카테고리 및 기간별 분석

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/analytics/categories` | 카테고리별 지출 금액과 비중 |
| GET | `/api/v1/analytics/periods` | 기간별 지출 합계. 조회 기간 8주 이하는 주간, 초과는 월간으로 자동 전환 |

**테이블**: 신규 없음. `transactions` 집계 쿼리만.

**구현 시 주의**

- 상위 카테고리는 3개 고정이며 동률이면 최근 거래가 있는 쪽을 우선한다. 3개 미만이면 있는 만큼만.
- 직전 기간 비교 대상이 없는 신규 사용자에게는 증감률 줄을 표시하지 않는다.
- **합계 정합성**: 카테고리별 합계와 기간별 합계가 각각 총지출과 일치해야 한다. 거래 목록의 합계와 예산 계산의 합계도 일치해야 한다.
- 기간별 집계는 윈도우 함수를 쓴다.

---

## Sprint 6 — 배치·보상·상점

### s9 예산 기간 자동 전환 배치

API가 아니라 Spring Batch Job이다. 매일 KST 00:05 실행, 종료 시각이 지난 예산 기간을 전부 스캔한다.

```
n70 기간 종료 → n71 새 기간 생성 → n72 상태 재계산 → 보상 판정 → 알림
```

앞의 세 단계는 **단일 트랜잭션으로 원자적 처리**한다. 중간에 실패하면 "새 기간은 생겼는데 상태 재계산은 안 된" 상태가 남는다.

**테이블**: `budget_periods`(생성·갱신), `status_thresholds`(복사), `credit_balances`(갱신), `reward_grants`(생성)

### F-EZZFNU 절약 조건 판정 및 크레딧 지급

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/rewards` | 보상 지급 내역과 사유 |

지급 자체는 배치가 수행한다.

**구현 시 주의**

- "목표 예산 이내 지출"은 **기간 시작 시점 목표 금액 스냅샷** 기준이다. 기간 중 목표를 올려도 판정 기준은 원래 목표다.
- "이전 기간 대비 절약"은 직전 1개 기간과만 비교하며 **10% 이상 감소**해야 한다. 첫 기간은 이 조건을 평가하지 않는다.
- 두 조건 모두 충족하면 합산 지급(100 + 200 = 300 크레딧).
- 동일 조건으로 중복 지급하지 않는다. `(user_id, budget_period_id, condition)` 유니크로 막는다.
- 크레딧 잔액 갱신에 `SELECT FOR UPDATE`를 쓴다.

### F-HPWCNJ 보상 보관함 및 상점

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/shop/items` | 구매 가능 아이템 목록과 보유 크레딧 잔액 |
| POST | `/api/v1/shop/items/{itemId}/purchase` | 구매. 크레딧 즉시 차감 |
| GET | `/api/v1/users/me/items` | 보관함. 보유 아이템과 슬롯별 배치 상태 |
| PUT | `/api/v1/users/me/items/{userItemId}/placement` | 방에 배치. 같은 슬롯의 기존 아이템은 자동 해제된다 |
| DELETE | `/api/v1/users/me/items/{userItemId}/placement` | 배치 해제 |

**테이블**: `shop_items`(생성·시드), `user_items`(생성), `credit_balances`(갱신)

**구현 시 주의**

- 잔액이 부족하면 구매를 막고 부족한 수량을 안내한다.
- 보유하지 않은 아이템은 배치할 수 없다.
- 슬롯은 벽지·바닥·집·장난감 4개이며 각 1개씩이다. 같은 슬롯에 다른 아이템을 배치하면 기존 것이 해제된다.
- 렌더링 순서는 **벽지 → 바닥 → 집 → 캐릭터 → 장난감** 고정이다. 상태 6단계가 바뀌어도 배치는 유지된다.
- 크레딧은 현금으로 구매·환전할 수 없다. 획득 경로는 F-EZZFNU 보상뿐이다.

---

## 구현하지 않는 것

명세에 없거나 명시적으로 제외된 것들이다. 요청받지 않은 이상 만들지 않는다.

| 항목 | 근거 |
|---|---|
| 현금 거래 수동 입력·삭제 | Q10 |
| 관리자 화면·관리자 API | Q12. 키워드 룰과 상점 아이템은 시드 데이터로 관리 |
| 웹 클라이언트 | Q14. 모바일 단독 출시 |
| 웹 푸시 | Q9 |
| 이메일·비밀번호 로그인, 비밀번호 재설정 | Q3. 소셜 전용 |
| 사용자 정의 카테고리 | Q7. 10개 고정 |
| 사용자 수정 기반 자동 분류 학습 | R-CCOEWW 결정 1. 다음 버전 |
| 잔액 조회·송금 | R-BPFBTK. 거래 조회 권한만 사용 |
| 계정 통합(카카오·애플 동일인 병합) | Q3 |
| 아이템 재판매·사용자 간 교환 | F-HPWCNJ |
| 기기 등록·관리 화면 | Q4 |
