#!/usr/bin/env bash
# k3s BuildKit 이미지 빌드 팬아웃 — GitHub Actions 의 backend-ghcr 잡 대체물.
#
#   ./k8s/buildkit/build.sh order-service operation-service
#   ./k8s/buildkit/build.sh frontend                 # 프론트엔드도 대상이다
#   ./k8s/buildkit/build.sh --all --wait             # 백엔드 전부 + 프론트엔드
#   ./k8s/buildkit/build.sh --ref main --all --wait --scan
#
# 컨텍스트는 git 원격이다. 로컬 워킹트리는 빌드되지 않으므로, 원격에 push 된 커밋만 이미지가 된다.
set -euo pipefail

NS=build
REGISTRY=ghcr.io
IMAGE_BASE=myoungsoo7/shop
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# module → image suffix.
# ⚠ ci.yml 의 `mapping` (Compute image build matrix 스텝)과 반드시 일치해야 한다.
#   불일치하면 같은 서비스가 GitHub Actions 와 k3s 에서 서로 다른 이미지로 나간다.
MAPPING="
order-service=
gateway-service=-gateway
operation-service=-operation
marketing-service=-marketing
partner-service=-partner
seller-service=-seller
"

all_modules() { echo "$MAPPING" | sed '/^$/d' | cut -d= -f1; }

# 프론트엔드는 Gradle 모듈이 아니라 별도 Dockerfile(frontend/Dockerfile)이라 매핑 밖에 둔다.
# 이미지는 ghcr.io/myoungsoo7/shop-frontend (ci.yml 의 FRONTEND_IMAGE 와 동일).
all_targets() { all_modules; echo "frontend"; }

suffix_for() {
  local m="$1" line
  line=$(echo "$MAPPING" | sed '/^$/d' | grep -E "^${m}=" || true)
  if [ -z "$line" ]; then
    echo "알 수 없는 대상: $m" >&2
    echo "가능한 값: $(all_targets | tr '\n' ' ')" >&2
    exit 2
  fi
  echo "${line#*=}"
}

GIT_REF=""
WAIT=0
SCAN=0
DRY_RUN=0
TARGETS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --all)     TARGETS=($(all_targets)); shift ;;
    --ref)     GIT_REF="$2"; shift 2 ;;
    --wait)    WAIT=1; shift ;;
    --scan)    SCAN=1; WAIT=1; shift ;;   # 스캔은 이미지가 올라간 뒤에만 의미가 있다
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    -*)        echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
    *)         TARGETS+=("$1"); shift ;;
  esac
done

[ "${#TARGETS[@]}" -eq 0 ] && { echo "빌드할 모듈이 없다. --all 또는 모듈명을 지정할 것." >&2; exit 2; }

[ -z "$GIT_REF" ] && GIT_REF="$(git rev-parse --abbrev-ref HEAD)"

# 원격에 실제로 있는 커밋을 기준으로 태그를 만든다.
# 로컬 HEAD 로 태그를 달면 buildkit 이 굽는 커밋(원격)과 태그가 어긋난다 — 추적 불가능한 이미지가 된다.
REMOTE_SHA="$(git ls-remote origin "refs/heads/${GIT_REF}" | cut -f1)"
if [ -z "$REMOTE_SHA" ]; then
  echo "origin 에 브랜치 '${GIT_REF}' 가 없다. push 먼저 할 것." >&2
  exit 1
fi
SHORT_SHA="${REMOTE_SHA:0:7}"

LOCAL_SHA="$(git rev-parse HEAD)"
if [ "$LOCAL_SHA" != "$REMOTE_SHA" ] && [ "$(git rev-parse --abbrev-ref HEAD)" = "$GIT_REF" ]; then
  echo "⚠ 로컬 HEAD(${LOCAL_SHA:0:7}) != origin/${GIT_REF}(${SHORT_SHA}) — 빌드되는 것은 원격 커밋이다." >&2
fi

# feature/xxx 같은 슬래시 포함 브랜치는 도커 태그로 못 쓴다.
TAG_REF="$(echo "$GIT_REF" | tr '/' '-')"
STAMP="$(date +%H%M%S)"

submitted=()

for m in "${TARGETS[@]}"; do
  if [ "$m" = "frontend" ]; then
    image="${REGISTRY}/${IMAGE_BASE}-frontend"
    template="${HERE}/21-frontend-job.template.yaml"
    short="frontend"
  else
    image="${REGISTRY}/${IMAGE_BASE}$(suffix_for "$m")"
    template="${HERE}/20-build-job.template.yaml"
    short="${m%-service}"
  fi

  tags="${image}:${TAG_REF},${image}:${TAG_REF}-${SHORT_SHA}"
  # latest 는 기본 브랜치에서만 — ci.yml 의 `enable={{is_default_branch}}` 와 동일 규칙
  [ "$GIT_REF" = "main" ] && tags="${tags},${image}:latest"

  job="build-${short}-${SHORT_SHA}-${STAMP}"

  rendered="$(sed \
    -e "s|__JOB_NAME__|${job}|g" \
    -e "s|__MODULE__|${m}|g" \
    -e "s|__GIT_REF__|${GIT_REF}|g" \
    -e "s|__SHORT_SHA__|${SHORT_SHA}|g" \
    -e "s|__TAGS__|${tags}|g" \
    "${template}")"

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "$rendered"
    echo "---"
    continue
  fi

  echo "$rendered" | kubectl apply -f -
  echo "  → ${m}  ⇒  ${tags}"
  submitted+=("${job}|${m}|${image}:${TAG_REF}-${SHORT_SHA}")
done

[ "$DRY_RUN" -eq 1 ] && exit 0
[ "$WAIT" -eq 0 ] && { echo; echo "진행 상황: kubectl -n ${NS} get jobs -w"; exit 0; }

rc=0
for entry in "${submitted[@]}"; do
  job="${entry%%|*}"; rest="${entry#*|}"; m="${rest%%|*}"; ref="${rest#*|}"
  echo
  echo "=== ${m} (${job}) ==="
  # 파드가 아직 안 뜬 상태에서 logs -f 를 걸면 즉시 실패하므로 파드 생성을 먼저 기다린다.
  kubectl -n "$NS" wait --for=condition=ready pod -l "job-name=${job}" --timeout=300s >/dev/null 2>&1 || true
  kubectl -n "$NS" logs -f "job/${job}" || true

  if kubectl -n "$NS" wait --for=condition=complete "job/${job}" --timeout=3600s >/dev/null 2>&1; then
    echo "✔ ${m} 빌드/푸시 성공 — ${ref}"
    if [ "$SCAN" -eq 1 ]; then
      sjob="scan-${m%-service}-${SHORT_SHA}-${STAMP}"
      sed \
        -e "s|__JOB_NAME__|${sjob}|g" \
        -e "s|__MODULE__|${m}|g" \
        -e "s|__IMAGE_REF__|${ref}|g" \
        "${HERE}/30-trivy-scan-job.template.yaml" | kubectl apply -f -
      kubectl -n "$NS" wait --for=condition=ready pod -l "job-name=${sjob}" --timeout=300s >/dev/null 2>&1 || true
      kubectl -n "$NS" logs -f "job/${sjob}" || true
      if kubectl -n "$NS" wait --for=condition=complete "job/${sjob}" --timeout=1800s >/dev/null 2>&1; then
        echo "✔ ${m} Trivy CRITICAL 게이트 통과"
      else
        echo "✘ ${m} Trivy CRITICAL 검출 — 이미지는 이미 푸시된 상태다. 롤백 판단은 사람이 한다." >&2
        rc=1
      fi
    fi
  else
    echo "✘ ${m} 빌드 실패 — kubectl -n ${NS} describe job/${job}" >&2
    rc=1
  fi
done

exit "$rc"
