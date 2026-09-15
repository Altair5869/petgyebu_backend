# 브랜치 전략

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱 백엔드
- 최종 갱신: 2026-09-15 (GitHub 저장소 설정 적용 상태 반영)
- 관련 문서: `02-requirements-features.md`, `06-sprint-plan.md`

## 1. 브랜치 단위 — 기능(F-XXXXX) 단위

`docs/02-requirements-features.md`의 기능 ID 하나당 브랜치 하나가 원칙이다. 스프린트 하나가 기능
여러 개로 구성되는 경우(예: Sprint 3의 F-FZUVLV + F-GGIDHG 병렬 진행), 기능별로 별도 브랜치를
만들어 독립적으로 병합한다.

| 유형 | 네이밍 | 예시 |
|------|--------|------|
| 기능 구현 | `feature/{F-ID}-{영문-슬러그}` | `feature/F-TEDWWF-bank-account-linking` |
| 인프라/설정 (F-ID 없는 Sprint 0 항목) | `chore/{영문-슬러그}` | `chore/spring-boot-init` |
| 버그 수정 | `fix/{영문-슬러그}` | `fix/duplicate-transaction-detection` |

모든 브랜치는 최신 `main`에서 분기한다.

## 2. 병합 방식 — Squash merge

PR을 `main`에 병합할 때는 **Squash merge만 사용**한다. 기능/작업 단위로 커밋 1개가 남아
`main`의 이력이 스프린트 계획과 1:1로 대응한다. 병합 후 브랜치는 삭제한다.

**GitHub 저장소 설정** (2026-09-15에 `gh` CLI 설치 완료. 아래 상태 기준):

| 설정 | 상태 | 적용 방법 |
|------|------|-----------|
| "Allow squash merging"만 체크, merge commit/rebase 해제 | ✅ 적용 완료 (2026-09-15) | `gh api -X PATCH repos/{owner}/{repo} -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false` |
| "Automatically delete head branches" 체크 | ✅ 적용 완료 (2026-09-15) | 위 명령에 `-F delete_branch_on_merge=true` |
| `main` 보호 규칙: "Require a pull request before merging" (직접 push 방지) | ✅ 적용 완료 (2026-09-15) | Settings → Rules → Rulesets에서 수동 적용 |

`main` 보호는 구형 Branch protection 대신 **Rulesets**("main protection", 대상
`~DEFAULT_BRANCH`)으로 적용했다. 적용된 규칙은 세 가지다.

| 규칙 | 내용 |
|------|------|
| `pull_request` | PR 없이 `main`에 직접 push 불가. 1인 개발 단계라 Required approvals는 0으로 두어 본인이 올린 PR을 스스로 병합할 수 있다 |
| `deletion` | `main` 브랜치 삭제 금지 |
| `non_fast_forward` | `main`에 force push 금지 |

확인 명령: `gh api repos/{owner}/{repo}/rules/branches/main --jq '[.[].type]'`
(빈 배열이면 룰셋이 `disabled` 상태라는 뜻이니 Enforcement status를 Active로 바꿔야 한다.)

**알아둘 점 2가지**

- 룰셋에 `require_extra_approval_for_unattributed_changes`가 켜져 있다. GitHub 계정에 매핑되지
  않는 커밋(예: 봇 계정 이메일의 `Co-Authored-By`)이 섞이면 Required approvals가 0이어도 승인을
  요구한다. 혼자 병합이 막히면 이 옵션을 먼저 의심한다.
- 룰셋의 `allowed_merge_methods`는 merge/squash/rebase 셋 다 허용으로 남아 있다. 저장소 레벨에서
  squash만 켜둬 실질적으로는 squash만 가능하지만, 두 설정이 어긋나 있으므로 룰셋 쪽도 squash만
  남기면 일관된다.

## 3. main 원칙

`main`은 항상 빌드 가능한 상태를 유지한다. PR 병합 전 최소 빌드 통과를 확인한다(CI 구성 전까지는
로컬 `./gradlew build`로 확인).

## 4. 태그 — 스프린트 완료 시점

스프린트 하나가 완전히 완료되면(`docs/06-sprint-plan.md`의 해당 스프린트 체크박스 전부 완료)
`main`에 `v0.{N}.0` 태그를 남긴다. 배포·롤백 기준점으로 사용한다.

## 5. 커밋 메시지

Conventional Commits 형식을 따른다 (`feat:`, `fix:`, `chore:`, `docs:` 등). PR 제목이 squash
커밋 메시지가 되므로, PR 제목을 Conventional Commits 형식으로 작성한다.
