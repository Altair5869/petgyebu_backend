---
name: petgyebu-sprint
description: >
  petgyebu 백엔드(Spring Boot 4.0/Java 25) 스프린트 기능을 backend-engineer + qa-reviewer
  2인 에이전트 팀으로 구현·검증한다. "스프린트 N 구현해줘", "F-XXXXX 구현", "이번 스프린트
  진행", "다음 스프린트 시작" 요청 시 사용. 후속 작업(재실행/업데이트 필요): "스프린트 N
  다시", "QA 지적 반영", "이전 스프린트 보완", "F-XXXXX만 다시 구현", "스프린트 결과 개선"
  요청 시에도 반드시 이 스킬을 사용한다.
---

# petgyebu Sprint Orchestrator

`docs/06-sprint-plan.md`에 정의된 스프린트 순서를 backend-engineer(구현)와 qa-reviewer(검증)
2인 팀으로 실행하는 오케스트레이터. 브랜치/PR 규칙은 `docs/07-branch-strategy.md` 참고.

## 실행 모드: 에이전트 팀

생성-검증 패턴. backend-engineer가 모듈을 완성할 때마다 qa-reviewer가 즉시 검증하고,
SendMessage로 직접 수정 요청을 주고받아 왕복 비용을 줄인다.

## 에이전트 구성

| 팀원 | 에이전트 타입 | 역할 | 사용 스킬 | 출력 |
|------|-------------|------|----------|------|
| backend-engineer | 커스텀 (`.claude/agents/backend-engineer.md`) | 엔티티/API/배치/코드에프 연동 구현 | lean-build, surgical-patch, migration, investigate-first | `_workspace/{sprint}_backend_summary.md` |
| qa-reviewer | 커스텀 (`.claude/agents/qa-reviewer.md`), `general-purpose` 타입으로 스폰 | 통합 정합성·스펙 준수 검증 | investigate-first, verify-and-stop, caveman-review | `_workspace/{sprint}_qa_report.md` |

## 워크플로우

### Phase 0: 컨텍스트 확인 (후속 작업 지원)

1. `_workspace/` 존재 여부 확인
2. 사용자 요청에서 대상 스프린트 번호 또는 기능 ID(F-XXXXX) 파악. 불명확하면 `docs/06-sprint-plan.md`의
   체크박스(`- [ ]`/`- [x]`)와 `src/` 트리를 대조해 다음으로 진행할 스프린트를 추정하고 사용자에게 확인한다
3. 실행 모드 결정:
   - `_workspace/{sprint}_*` 미존재 → 초기 실행, Phase 1로
   - 존재 + 사용자가 특정 항목/QA 지적 재작업 요청 → 부분 재실행. 해당 항목만 backend-engineer에게
     재할당하고, 기존 `_workspace/{sprint}_backend_summary.md`·`_qa_report.md`는 덮어쓰지 않고 갱신 항목만 추가
   - 존재 + 새 스프린트로 진행 → 새 실행, 이전 스프린트의 `_workspace/{sprint}_*`는 그대로 보존(스프린트별로
     파일명이 분리되어 있으므로 이동 불필요)

### Phase 1: 준비

1. `docs/06-sprint-plan.md`에서 대상 스프린트의 체크박스 항목 전부 추출
2. 관련 기능 ID(F-XXXXX)가 있으면 `docs/02-requirements-features.md`에서 해당 상세 명세 로드
3. Sprint 0(인프라)이면 `docs/05-infra-stack.md`의 확정 스택/버전 표도 함께 로드
4. `_workspace/{sprint}_00_input.md`에 추출한 항목·명세·완료 기준을 정리해 저장
5. `docs/07-branch-strategy.md` 기준으로 이번 스프린트에서 만들 기능 브랜치 목록을 정한다
   (기능 ID 하나당 `feature/{F-ID}-{슬러그}` 하나, 병렬 기능은 각자 별도 브랜치)

### Phase 2: 팀 구성

```
TeamCreate(
  team_name: "petgyebu-sprint-team",
  members: [
    { name: "backend-engineer", agent_type: "backend-engineer", model: "opus",
      prompt: "{sprint}_00_input.md 참고. 스프린트 {N} 항목을 구현하라. 모듈 하나
      완성할 때마다 qa-reviewer에게 SendMessage로 검증 요청." },
    { name: "qa-reviewer", agent_type: "qa-reviewer", model: "opus",
      prompt: "backend-engineer가 완성 알림을 보내는 모듈을 즉시 검증하라.
      _workspace/{sprint}_qa_report.md에 판정 기록, FIX/REDO는 즉시 SendMessage." }
  ]
)
```

작업 등록:

```
TaskCreate(tasks: [
  { title: "{스프린트 항목 1}", description: "{docs 발췌}", assignee: "backend-engineer" },
  { title: "{스프린트 항목 2}", description: "{docs 발췌}", assignee: "backend-engineer" },
  ...
  { title: "스프린트 {N} 최종 QA", assignee: "qa-reviewer", depends_on: ["{스프린트 항목 전체}"] }
])
```

> 스프린트 항목이 6개를 넘으면(예: Sprint 1, Sprint 2) 우선순위 상위 5~6개만 먼저 등록하고
> 나머지는 완료 후 추가 등록한다.

### Phase 3: 구현 + 점진적 검증

**실행 방식:** 팀원이 자체 조율. 리더는 진행 상황을 모니터링한다.

1. backend-engineer가 기능(F-ID) 시작 시 `main`에서 `feature/{F-ID}-{슬러그}` 브랜치를 만들고
   그 위에서 작업 목록 항목을 순서대로 처리, 로컬 커밋(Conventional Commits 형식)
2. 모듈(엔티티+API 한 세트, 또는 배치 잡 하나) 완성 시마다 qa-reviewer에게 SendMessage
3. qa-reviewer는 "양쪽 동시 읽기"로 즉시 검증 후 판정:
   - PASS → 다음 모듈 진행
   - FIX/REDO → backend-engineer에게 파일:라인 포함 구체 지시, 최대 2회 재작업 루프
4. 외부 요인(코드에프 데모 승인 대기 등)으로 검증 불가한 항목은 qa-reviewer가 "미검증"으로 기록하고 진행

**산출물:**

| 팀원 | 경로 |
|------|------|
| backend-engineer | `_workspace/{sprint}_backend_summary.md` |
| qa-reviewer | `_workspace/{sprint}_qa_report.md` |

### Phase 4: 통합 및 보고

1. 모든 작업 완료 대기 (TaskGet)
2. `_workspace/{sprint}_qa_report.md`, `_workspace/{sprint}_backend_summary.md` Read
3. `docs/06-sprint-plan.md`의 해당 스프린트 체크박스를 완료 항목만 `[x]`로 갱신 (보류/미검증 항목은 그대로 둠)
4. PASS한 기능 브랜치마다 PR 제목(Conventional Commits 형식)·본문 초안을 준비한다.
   **원격 push, PR 생성, 병합은 사용자에게 결과를 보고하고 명시적으로 확인받은 뒤에만 실행한다**
   (`docs/07-branch-strategy.md`: squash merge만 사용, PR 제목이 곧 squash 커밋 메시지가 됨)
5. 스프린트의 모든 브랜치가 병합되어 스프린트가 완전히 끝났으면 `v0.{N}.0` 태그 생성을 사용자에게 제안한다
6. 사용자에게 요약 보고: 완료 항목 수, FIX 반영 수, 보류/미검증 항목과 사유, 생성된 브랜치/PR 목록, 다음 스프린트 진입 가능 여부

### Phase 5: 정리

1. 팀원에게 종료 요청 (SendMessage)
2. `TeamDelete`
3. `_workspace/`는 보존 (사후 검증·감사 추적용)

## 데이터 흐름

```
[리더] → docs/06-sprint-plan.md + 02-requirements-features.md 발췌
       → _workspace/{sprint}_00_input.md
       → TeamCreate + TaskCreate
       → backend-engineer ←SendMessage(모듈 완성/재작업)→ qa-reviewer
              ↓                                              ↓
   backend_summary.md                                  qa_report.md
              └──────────────── Read ───────────────────────┘
                              [리더: 통합·보고]
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| backend-engineer가 외부 승인 대기로 막힘 | 해당 항목 보류 처리, 다른 항목 계속 진행, 최종 보고에 명시 |
| qa-reviewer 2회 재작업 후에도 REDO | 강제 진행하지 않고 사용자에게 에스컬레이션, 이견 내용 함께 보고 |
| 팀원 1명 중단/무응답 | 리더가 유휴 알림 감지 → SendMessage로 상태 확인 → 재시작 |
| SANDBOX 단계라 검증 자체가 불가능한 항목 | qa-reviewer가 "미검증"으로 분류(PASS 아님), 최종 보고에 별도 명시 |

## 테스트 시나리오

### 정상 흐름
1. 사용자: "스프린트 1 구현해줘"
2. Phase 0에서 `_workspace/sprint1_*` 없음 확인 → 초기 실행
3. Phase 1에서 Sprint 1 항목(계좌 연결) + F-TEDWWF 명세 로드
4. Phase 2에서 팀 구성, 항목별 작업 등록
5. Phase 3에서 backend-engineer가 Account 엔티티+API 구현 → qa-reviewer 검증 PASS 반복
6. Phase 4에서 완료 항목 sprint-plan.md 체크, 요약 보고
7. Phase 5에서 팀 정리

### 에러 흐름
1. 사용자: "스프린트 1 구현해줘"
2. backend-engineer가 2-way 인증 콜백 구현 중 코드에프 데모 미승인으로 실행 검증 불가
3. 해당 항목을 "구현 완료(미검증)"으로 요약에 기록, 다른 항목 계속 진행
4. qa-reviewer도 해당 항목을 "미검증" 판정, 사유(데모 미승인) 기재
5. Phase 4 최종 보고에 "2-way 인증 콜백: 코드 완료, 데모 승인 후 실전 검증 필요" 명시
6. sprint-plan.md 체크박스는 미체크로 유지
