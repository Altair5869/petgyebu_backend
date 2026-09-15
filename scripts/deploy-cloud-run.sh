#!/usr/bin/env bash
# Cloud Run 배포 스크립트.
#
# --no-cpu-throttling 과 --min-instances=1 은 ShedLock 백그라운드 스레드와 Spring Batch
# 스케줄러가 죽지 않게 하는 전제 조건이다(docs/05-infra-stack.md 3.2절). 이 두 플래그는
# 절대 빼지 말 것. 콘솔에서 수동 배포로 리셋되면 스케줄러가 에러 로그 없이 멈춘다.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?PROJECT_ID 환경변수가 필요하다}"
SERVICE_NAME="${SERVICE_NAME:-telo}"
REGION="${REGION:-asia-northeast3}"
IMAGE="${IMAGE:?IMAGE 환경변수가 필요하다 (예: asia-northeast3-docker.pkg.dev/PROJECT/repo/backend:tag)}"
CLOUDSQL_INSTANCE="${CLOUDSQL_INSTANCE:-}"

deploy_args=(
  run deploy "${SERVICE_NAME}"
  --project="${PROJECT_ID}"
  --image="${IMAGE}"
  --region="${REGION}"
  --no-cpu-throttling
  --min-instances=1
  --max-instances=3
  --cpu=2
  --memory=2Gi
)

if [[ -n "${CLOUDSQL_INSTANCE}" ]]; then
  deploy_args+=(--add-cloudsql-instances="${CLOUDSQL_INSTANCE}")
fi

gcloud "${deploy_args[@]}"

# 배포 직후 같은 스크립트에서 한 번 더 확인한다. CI에서는 scripts/verify-cloud-run-flags.sh가
# 동일한 검증을 독립 스텝으로 수행한다.
REGION="${REGION}" SERVICE_NAME="${SERVICE_NAME}" PROJECT_ID="${PROJECT_ID}" \
  "$(dirname "$0")/verify-cloud-run-flags.sh"
