# 스프린트 플랜

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱
- 최종 갱신: 2026-09-03
- 관련 문서: `01-prd.md`, `../../02-requirements-features.md`, `03-user-flow.md`, `../../04-review-log.md`, `05-infra-stack.md`

## 순서 결정 근거

기능 하나가 다른 기능의 데이터를 전제로 하는 의존관계를 기준으로 순서를 짰다. 이 순서를 벗어나면 앞뒤가 막히거나 목업 데이터로 임시 처리하다 나중에 갈아엎는 상황이 생긴다.

```
Sprint 0 (인프라)
  → Sprint 1 (F-TEDWWF: 계좌연결)
    → Sprint 2 (F-OAVYWT+F-RNECMQ: 거래수집+조회)
      → Sprint 3 (F-FZUVLV+F-GGIDHG: 예산+캐릭터, 병렬 / F-KBBFRU 시작)
        → Sprint 4 (F-VZFPVW: 반려동물 피드백 + 푸시)
          → Sprint 5 (F-TKSQSR+F-IWBASY: 소비분석)
            → Sprint 6 (s9배치+F-EZZFNU+F-HPWCNJ: 예산전환+보상+상점)
```

각 화살표는 "왼쪽이 끝나야 오른쪽 데이터가 존재한다"는 뜻이다.

---

## Sprint 0 — 인프라 골격

- [ ] 코드에프 데모 서비스 신청 (외부 프로세스, 지금 바로 접수)
- [ ] GCP 프로젝트 생성, IAM 롤 부여(`roles/cloudsql.client`, `roles/storage.objectAdmin`)
- [ ] Cloud SQL 프로비저닝 (`db-custom-1-3840`, PostgreSQL 16, `asia-northeast3`)
- [ ] Upstash Redis 프로비저닝 (도쿄), Lettuce 커넥션 테스트
- [ ] Spring Boot 프로젝트 초기화 (Web, JPA, Security, Batch, Validation 의존성)
- [ ] Dockerfile 작성, Cloud Run 배포 스크립트에 `--no-cpu-throttling --min-instances=1` 고정
- [ ] CI에 `gcloud run services describe`로 위 플래그 검증 스텝 추가
- [ ] User 엔티티, 회원가입/로그인 API (Spring Security+JWT)
- [ ] 헬스체크 엔드포인트

**완료 기준**: 로그인해서 Cloud Run 배포된 API를 호출하면 응답이 온다.

**리스크**: 코드에프 데모 신청 승인 대기가 Sprint 1의 병목이 될 수 있음. 이 스프린트 시작 시점에 바로 접수해서 승인 시간을 벌어야 함.

---

## Sprint 1 — F-TEDWWF (은행 계좌 연결)

- [ ] **SANDBOX/DEMO 자격증명 분리 설정** — `application-sandbox.yml`/`application-demo.yml` 프로필 분리, `@ConfigurationProperties`로 `service-type`/`client-id`/`client-secret` 외부화. SDK는 `set_client_info()`(SANDBOX용)와 `set_demo_client_info()`(DEMO용)가 별도 메서드라, 이 구조를 처음부터 잡아두면 데모 승인 이후 전환이 profile 교체만으로 끝남
- [ ] Account 엔티티 (은행식별자, 코드에프 연결식별자, 마스킹된 계좌번호, 거래조회 동의상태, 동의만료시각, 마지막동기화시각)
- [ ] `easycodef-java` SDK 의존성 추가, `EasyCodefUtil.encryptRSA()` 동작 확인
- [ ] 지원은행 20개 목록 상수 테이블 (카카오뱅크·토스뱅크 제외 확정 반영)
- [ ] 2-way 인증 상태 Upstash 저장 (`jobIndex`/`threadIndex`/`jti`/`twoWayTimestamp`, TTL)
- [ ] 계좌 연결 시작 API (은행 선택 → 인증 시작)
- [ ] 추가인증 콜백 처리 API
- [ ] 복수 계좌 배열 저장 로직 (단일 계좌 가정 금지)
- [ ] 계좌 해제 API
- [ ] 재인증 요청 API (횟수 제한 없음, 코드에프/은행 보안정책 에러 그대로 노출)
- [ ] 예외처리: 인증실패·대행사 장애 시 연결정보 미저장
- [ ] 프론트: 은행 목록 화면, 인증 화면, 연결완료 화면

**리스크 — SANDBOX와 DEMO는 검증 범위가 다르다:** SANDBOX는 "요청 파라미터 체크 후 상품별 고정 응답값 반환" 구조라, 다음 세 가지는 SANDBOX 단계에서 **검증이 원천적으로 불가능**하다.
1. **2-way 추가인증 흐름** — 고정 응답이라 실제 챌린지-리스폰스 왕복 자체가 발생하지 않음. 추가인증 콜백 처리 API는 SANDBOX 단계에서 스키마·컴파일만 확인되고 한 번도 실행돼본 적 없는 코드로 데모 전환을 맞이하게 됨
2. **은행별 실제 응답 차이** — 20개 지원은행이 SANDBOX에서 전부 동일한 고정값으로 응답할 가능성이 높아, 은행별 목록 매핑 검증은 DEMO 전환 이후로 미뤄짐
3. **실제 에러 케이스** (인증 실패, 반복시도 차단 등) — SANDBOX가 이런 예외 상황을 재현하는지 불확실

**대응**: SANDBOX로는 UI·저장 로직·SDK 연동 배선까지만 먼저 진행하고, 2-way 인증 콜백 코드는 데모 승인 후 첫 실전 통합테스트가 발생한다는 전제로 일정에 별도 시간을 확보한다.

---

## Sprint 2 — F-OAVYWT + F-RNECMQ (거래 수집 + 조회)

### F-OAVYWT
- [ ] Transaction 엔티티 (거래식별자, 거래일시, 금액, 거래처, 수입지출유형, 카테고리, 취소환불상태, 연결된환불거래식별자, 계좌간이체유형, 연결상태, 상대거래식별자, 사용자확인상태)
- [ ] 코드에프 거래내역 조회 연동, 페이지네이션 순차호출(90일치)
- [ ] 중복거래 식별 (거래식별정보+일시+금액+거래처)
- [ ] 이체후보 자동매칭 함수 — **별도 함수로 분리**(금액완전일치+10분이내+연결된계좌쌍), F-KBBFRU에서 재사용 예정
- [ ] 환불거래 순액 계산 (원거래참조식별자 연결)
- [ ] 가맹점명 키워드 룰 테이블 (JSONB+GIN), 자동 카테고리 분류
- [ ] Spring `@Scheduled`+ShedLock, 하루 2회(09:00/21:00)
- [ ] 수동 새로고침 API (1시간 캐시, 분당1회 rate limit, 둘 다 Upstash)
- [ ] 동기화 시도 기록 테이블
- [ ] Resilience4j 재시도(3회, 1분→5분→15분 지수백오프)

### F-RNECMQ
- [ ] 거래목록 조회 API (기간필터, 최신순)
- [ ] 가계부 제외 계좌 뱃지/완전숨김 표시 로직
- [ ] 거래 상세 API (원거래-환불 연결관계)
- [ ] 프론트: 거래목록·상세 화면, 빈상태 처리

---

## Sprint 3 — F-FZUVLV + F-GGIDHG (병렬) / F-KBBFRU 시작

### F-FZUVLV
- [ ] BudgetPeriod/StatusThreshold 엔티티
- [ ] 기본 임계값 6단계 프리필(휴식0~40%~예산초과100%~)
- [ ] 구간 검증 (0% 고정, 오름차순, 중복·공백 검증)
- [ ] 예산 기간/목표 설정 API
- [ ] 사용률 실시간 계산 함수 (누적지출÷목표금액)
- [ ] 오른쪽닫힘 경계값 판정 함수 (`(시작,끝]`, 0%는 예외)
- [ ] 프론트: 예산설정 화면, 임계값 표 편집 UI

### F-GGIDHG
- [ ] Character 엔티티
- [ ] 캐릭터 선택/변경 API
- [ ] Lottie 에셋 12개 Cloud Storage 업로드
- [ ] 프론트: 캐릭터 선택 화면, lottie 렌더링(웹/모바일 각각)

### F-KBBFRU (병행 시작 가능)
- [ ] 거래 분류·유형 수정 API
- [ ] 이체 확인/연결해제 API (Sprint2의 매칭 함수 재사용)
- [ ] 계좌별 가계부 포함/제외 토글 API
- [ ] 노출방식(뱃지/숨김) 하위옵션 API
- [ ] 수정이력 기록

**비고**: 이 세 트랙은 서로 독립적이라, 개발자가 2명 이상이면 완전히 나눠서 동시 진행 가능한 유일한 지점.

---

## Sprint 4 — F-VZFPVW (반려동물 소비 피드백) + 푸시

- [ ] 상태 계산 함수 (사용률→6단계 매핑, 이체제외 지출집계 함수와 통합 — Sprint2의 집계단계 재사용)
- [ ] 상태별 문구 컨텐츠 테이블 (6단계, 캐릭터 무관 공통)
- [ ] 메인 홈 API (상태+사용률+남은예산/초과금액)
- [ ] 상태설명/범례 API
- [ ] 90%/100% 진입감지, 기간당1회 발송기록(Upstash 키: `budget_period_id:threshold`)
- [ ] FCM 발송 (`firebase-admin`, ADC 인증 확인)
- [ ] 프론트: 메인홈, 상태설명, 예산초과 경고 화면

---

## Sprint 5 — F-TKSQSR + F-IWBASY (소비 분석)

### F-TKSQSR
- [ ] 소비요약 API (총지출, 사용률, 남은예산/초과금액, 상위3카테고리)
- [ ] 직전기간 대비 증감률 계산 (신규사용자는 미표시)
- [ ] 프론트: 소비요약 화면

### F-IWBASY
- [ ] 카테고리별 집계 API
- [ ] 기간별 집계 API (8주 기준 주간/월간 자동전환, 윈도우함수)
- [ ] 집계→거래상세 이동 링크
- [ ] 프론트: 카테고리별·기간별 분석 화면

---

## Sprint 6 — s9 배치 + F-EZZFNU + F-HPWCNJ

- [ ] Spring Batch Job: n70(기간종료)→n71(새기간생성)→n72(상태재계산), **단일 JobRepository 트랜잭션으로 원자적 처리**
- [ ] 목표금액 스냅샷 저장 (기간 시작 시점)
- [ ] F-EZZFNU: 절약조건 판정(목표이내지출/10%이상절약), 첫기간 예외처리
- [ ] Credit 엔티티, 잔액 갱신에 `SELECT FOR UPDATE`
- [ ] 보상 알림 발송 — **배치 완료 이후 순서 보장**(기간종료→생성→재계산→판정→알림)
- [ ] F-HPWCNJ: 상점 아이템 목록 API, 구매 API(크레딧 차감), 보관함 조회 API
- [ ] 프론트: 보상획득 화면, 보관함/상점 화면

**비고**: 가장 마지막에 배치한 이유는 예산 기간 종료→새 기간 생성→상태 재계산→보상 판정→알림까지 하나의 원자적 트랜잭션으로 묶어야 하는 요구사항(`../../04-review-log.md`) 때문. 여기 관여하는 컴포넌트(예산 계산, 반려동물 상태, 크레딧 지급, 상점)가 앞 스프린트에서 개별적으로 안정화된 뒤에 묶는 게 디버깅이 훨씬 쉽다.
