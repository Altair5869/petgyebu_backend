# 브랜치 전략

- 관련 프로젝트: 반려동물 감정 기반 소비 관리 가계부 앱 백엔드
- 최종 갱신: 2026-09-07
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

**GitHub 저장소 설정 (웹 UI에서 1회 수동 적용, `gh` CLI 미설치로 자동화 불가):**
- Settings → General → Pull Requests: "Allow squash merging"만 체크, merge commit/rebase는 해제
- Settings → General → Pull Requests: "Automatically delete head branches" 체크
- Settings → Branches → `main` 보호 규칙: "Require a pull request before merging" 체크 (직접 push 방지)

## 3. main 원칙

`main`은 항상 빌드 가능한 상태를 유지한다. PR 병합 전 최소 빌드 통과를 확인한다(CI 구성 전까지는
로컬 `./gradlew build`로 확인).

## 4. 태그 — 스프린트 완료 시점

스프린트 하나가 완전히 완료되면(`docs/06-sprint-plan.md`의 해당 스프린트 체크박스 전부 완료)
`main`에 `v0.{N}.0` 태그를 남긴다. 배포·롤백 기준점으로 사용한다.

## 5. 커밋 메시지

Conventional Commits 형식을 따른다 (`feat:`, `fix:`, `chore:`, `docs:` 등). PR 제목이 squash
커밋 메시지가 되므로, PR 제목을 Conventional Commits 형식으로 작성한다.
