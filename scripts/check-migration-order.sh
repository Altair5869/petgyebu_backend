#!/usr/bin/env bash
#
# 새로 추가된 Flyway 마이그레이션의 버전이 기준 브랜치의 최대 버전보다 큰지 검사한다.
#
# 왜 필요한가:
#   브랜치 A가 V202609201000을, 브랜치 B가 V202609201030을 만들고 B가 먼저 병합·배포되면
#   이력에는 1030이 적용된다. 그 뒤 A가 병합되면 1000은 이미 적용된 1030보다 낮은 버전이라
#   Flyway가 FlywayValidateException으로 기동을 거부한다.
#
#   이 실패는 빈 DB에서 절대 재현되지 않는다. 새 DB는 어떤 순서로 만들어졌든 오름차순으로
#   적용하기 때문이다. PostgresMigrationTest도 통과한다. 운영처럼 이미 마이그레이션이 적용된
#   DB에서만 드러난다. 그래서 파일명만으로 판정하는 정적 검사를 CI에 둔다.
#
# 사용법:
#   scripts/check-migration-order.sh [기준]
#     기준 기본값: origin/main
#
set -euo pipefail

BASE="${1:-origin/main}"
MIGRATION_DIR="src/main/resources/db/migration"

if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
	echo "기준 '$BASE'를 찾을 수 없다. git fetch가 필요한지 확인하라." >&2
	exit 1
fi

# 파일명에서 버전 숫자만 뽑는다. V202609201430__create_account.sql -> 202609201430
extract_version() {
	basename "$1" | sed -nE 's/^V([0-9]+)__.*\.sql$/\1/p'
}

# 기준 브랜치에 이미 있는 마이그레이션의 최대 버전
base_max=0
while read -r path; do
	[ -n "$path" ] || continue
	v="$(extract_version "$path")"
	[ -n "$v" ] || continue
	if [ "$v" -gt "$base_max" ]; then
		base_max="$v"
	fi
done < <(git ls-tree -r --name-only "$BASE" -- "$MIGRATION_DIR" 2>/dev/null || true)

# 이번 브랜치가 새로 추가한 마이그레이션
added=$(git diff --name-only --diff-filter=A "$BASE...HEAD" -- "$MIGRATION_DIR" || true)

# 기준 브랜치에 이미 있는 마이그레이션을 고치거나 지웠는지
#
# 왜 필요한가:
#   Flyway는 적용한 마이그레이션의 checksum을 flyway_schema_history에 남긴다. 파일이
#   나중에 바뀌면 기동 시 checksum 불일치로 FlywayValidateException이 나고 애플리케이션이
#   아예 뜨지 않는다. 주석 한 줄만 고쳐도 같다.
#
#   순서 검사와 마찬가지로 이 실패는 빈 DB에서 절대 재현되지 않는다. CI의
#   PostgresMigrationTest는 매번 새 컨테이너라 통과하고 운영에서만 터진다.
#
#   실측 (병합된 마이그레이션에 주석 한 줄 추가):
#     빈 DB (CI)  -> BUILD SUCCESSFUL
#     적용된 DB   -> ERROR: Migration checksum mismatch for migration version 202609181920
#
#   고쳐야 할 것이 있으면 파일을 수정하지 말고 새 마이그레이션을 추가한다.
touched=$(git diff --name-only --diff-filter=MRD "$BASE...HEAD" -- "$MIGRATION_DIR" || true)
touched=$(echo "$touched" | grep -E '\.sql$' || true)

if [ -n "$touched" ]; then
	echo "기준: $BASE"
	echo ""
	echo "이미 병합된 마이그레이션이 수정·삭제됐다:"
	while read -r path; do
		[ -n "$path" ] || continue
		echo "  [실패] $(basename "$path")"
	done <<< "$touched"
	echo ""
	echo "         Flyway가 checksum을 남기므로 적용된 DB에서 기동이 실패한다."
	echo "         빈 DB에서는 재현되지 않아 CI가 통과한다."
	echo "         파일을 되돌리고, 바꿀 내용은 새 마이그레이션으로 추가하라."
	exit 1
fi

if [ -z "$added" ]; then
	echo "추가·수정된 마이그레이션이 없다. 검사를 건너뛴다."
	exit 0
fi

echo "기준: $BASE (최대 버전 ${base_max})"

failed=0
while read -r path; do
	[ -n "$path" ] || continue
	v="$(extract_version "$path")"

	if [ -z "$v" ]; then
		echo "  [실패] $(basename "$path")"
		echo "         파일명이 V{YYYYMMDDHHmm}__{설명}.sql 형식이 아니다."
		failed=1
		continue
	fi

	if [ "$v" -le "$base_max" ]; then
		echo "  [실패] $(basename "$path")"
		echo "         버전 ${v}가 기준 브랜치의 최대 버전 ${base_max}보다 크지 않다."
		echo "         병합하면 운영 DB에서 FlywayValidateException으로 기동이 실패한다."
		echo "         파일명을 현재 시각 기준으로 재타임스탬프하라."
		failed=1
	else
		echo "  [통과] $(basename "$path") (버전 ${v})"
	fi
done <<< "$added"

if [ "$failed" -ne 0 ]; then
	echo ""
	echo "마이그레이션 순서 검사 실패."
	echo "파일명을 'V\$(TZ=Asia/Seoul date +%Y%m%d%H%M)__설명.sql' 형식으로 다시 지어라."
	exit 1
fi

echo "마이그레이션 순서 검사 통과."
