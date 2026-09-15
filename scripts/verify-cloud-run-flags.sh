#!/usr/bin/env bash
# 배포된 Cloud Run 서비스에 --no-cpu-throttling / --min-instances=1 이 살아 있는지 확인한다.
#
# gcloud run services describe 결과에서 두 플래그는 리비전 템플릿 애노테이션으로 나타난다.
#   --no-cpu-throttling  -> run.googleapis.com/cpu-throttling = "false"
#   --min-instances=1    -> autoscaling.knative.dev/minScale  = "1"
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?PROJECT_ID 환경변수가 필요하다}"
SERVICE_NAME="${SERVICE_NAME:-budget-pet-api}"
REGION="${REGION:-asia-northeast3}"
EXPECTED_MIN_INSTANCES="${EXPECTED_MIN_INSTANCES:-1}"

describe_file="$(mktemp)"
trap 'rm -f "${describe_file}"' EXIT

gcloud run services describe "${SERVICE_NAME}" \
  --project="${PROJECT_ID}" \
  --region="${REGION}" \
  --format=json > "${describe_file}"

python3 - "${describe_file}" "${SERVICE_NAME}" "${EXPECTED_MIN_INSTANCES}" <<'PY'
import json
import sys

describe_file, service_name, expected_min = sys.argv[1], sys.argv[2], sys.argv[3]

with open(describe_file, encoding="utf-8") as handle:
    data = json.load(handle)

annotations = (
    data.get("spec", {})
    .get("template", {})
    .get("metadata", {})
    .get("annotations", {})
)

throttling = annotations.get("run.googleapis.com/cpu-throttling")
min_scale = annotations.get("autoscaling.knative.dev/minScale")

problems = []
if throttling != "false":
    problems.append(
        f"--no-cpu-throttling 미적용: run.googleapis.com/cpu-throttling={throttling!r} (기대값 'false')"
    )
if min_scale != expected_min:
    problems.append(
        f"--min-instances 미적용: autoscaling.knative.dev/minScale={min_scale!r} (기대값 {expected_min!r})"
    )

if problems:
    print(f"[FAIL] {service_name}: Cloud Run 상시구동 플래그가 적용돼 있지 않다.")
    for problem in problems:
        print(f"  - {problem}")
    sys.exit(1)

print(f"[OK] {service_name}: cpu-throttling=false, minScale={min_scale}")
PY
