#!/usr/bin/env bash
# Lemuel 통합 검증 — "다 됐다"를 한 명령으로 증명한다.
#
#   ./scripts/verify.sh              # origin/develop 대비 변경분만 검증 (기본)
#   ./scripts/verify.sh --base main  # 베이스 지정
#   ./scripts/verify.sh --all        # 전체 모듈 빌드 (느림 — CI 의 shared 변경 경로와 동일)
#   ./scripts/verify.sh --fast       # Gradle 생략, 하네스 게이트만 (수초)
#
# 왜 있나: harness-guard.yml / ci.yml 이 PR 에서 하는 판정을 로컬에서 **같은 순서로** 재현한다.
# 에이전트가 완료를 자기 보고("테스트 통과했습니다")가 아니라 종료 코드로 증명하게 하는 것이 목적.
#
# 설계 원칙 — 느려지면 우회당한다:
#   · 기본 경로는 **변경 모듈만** 빌드한다 (ci.yml 의 test_tasks 산출 로직과 동일한 매핑).
#   · 하네스 게이트(수초)를 Gradle(수분) 앞에 두어, 싸게 실패할 수 있는 것부터 실패시킨다.
#   · shared-common / 모호한 backend 변경은 CI 와 같이 안전하게 전체 build 로 격상한다.
#
# 종료 코드: 0 = 전부 통과. 그 외 = 첫 실패 단계의 코드. 실패해도 어느 단계인지 항상 요약에 남긴다.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_REF="origin/develop"
MODE="changed"

while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE_REF="${2:?--base 는 ref 가 필요함}"; shift 2 ;;
    --all)  MODE="all"; shift ;;
    --fast) MODE="fast"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done

FAILED_STAGE=""
FAILED_CODE=0

# 첫 실패를 기억하되 나머지 단계도 계속 돌린다 — 한 번에 모든 문제를 보기 위해.
run_stage() {
  local label="$1"; shift
  echo ""
  echo "───────────────────────────────────────────────"
  echo "▶ $label"
  echo "───────────────────────────────────────────────"
  # 상태는 반드시 명령 직후에 캡처한다 — `if cmd; then …; fi` 뒤의 $? 는 if 문 자체의 0 이다.
  "$@"
  local code=$?
  if [ "$code" -eq 0 ]; then
    echo "✅ $label"
    return 0
  fi
  echo "❌ $label (exit $code)"
  if [ -z "$FAILED_STAGE" ]; then FAILED_STAGE="$label"; FAILED_CODE="$code"; fi
  return "$code"
}

# ── 변경 파일 산출 ────────────────────────────────────────────
# 워킹트리 변경 + 베이스 대비 커밋 변경을 합친다. 베이스를 못 찾으면 워킹트리만 본다(오프라인·새 클론).
changed_files() {
  {
    git diff --name-only --diff-filter=ACMR HEAD 2>/dev/null
    git diff --name-only --diff-filter=ACMR --cached 2>/dev/null
    if git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
      local base
      base="$(git merge-base HEAD "$BASE_REF" 2>/dev/null)"
      [ -n "$base" ] && git diff --name-only --diff-filter=ACMR "$base" HEAD 2>/dev/null
    fi
  } | sort -u | grep -v '^$' || true
}

# 목록 파일은 **반드시 저장소 안**에 둔다 — guard.mjs 의 normalizeRepoPath 는 저장소 밖 경로를
# 설계상 거부한다(경로 탈출 차단). mktemp(/var/folders/…) 를 넘기면 "path is outside repository".
CHANGED_LIST=".harness-changed.txt"
trap 'rm -f "$REPO_ROOT/$CHANGED_LIST" "$REPO_ROOT/.harness-deleted.txt"' EXIT
changed_files > "$CHANGED_LIST"
CHANGED_COUNT="$(wc -l < "$CHANGED_LIST" | tr -d ' ')"

echo "==============================================="
echo " Lemuel verify — base=$BASE_REF mode=$MODE"
echo " 변경 파일 $CHANGED_COUNT 건"
echo "==============================================="

# ── 1. 하네스 자체 테스트 (harness-guard.yml 과 동일, 가장 먼저) ──
# 가드를 고치면서 가드를 깨뜨리는 것을 막는다. 카나리아(규칙 생존 검사)도 여기 포함된다.
run_stage "하네스 테스트 (규칙 카나리아 포함)" \
  node --test scripts/harness/test/*.test.mjs

# ── 2. 하네스 자산 무결성 (manifest 등록부) ──
run_stage "하네스 자산 감사" \
  node scripts/harness/harness-audit.mjs

# ── 3. 머니/아키텍처 가드 (변경 파일만) ──
if [ "$CHANGED_COUNT" -gt 0 ]; then
  run_stage "머니·아키텍처 가드 (변경 $CHANGED_COUNT 건)" \
    node scripts/harness/guard.mjs --list "$CHANGED_LIST"
else
  echo ""
  echo "⏭  머니·아키텍처 가드 — 변경 파일 없음, 건너뜀"
fi

# ── 3b. 삭제 가드 ──
# 삭제는 --diff-filter=ACMR 목록에 안 담긴다. PR #210 에서 하네스 270파일이 조용히 지워진
# 사고를 막는 지점이라, 로컬에서도 CI 와 같은 검사를 돈다.
DELETED_LIST=".harness-deleted.txt"
{
  git diff --name-only --diff-filter=D HEAD 2>/dev/null
  git diff --name-only --diff-filter=D --cached 2>/dev/null
  if git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
    base="$(git merge-base HEAD "$BASE_REF" 2>/dev/null)"
    [ -n "$base" ] && git diff --name-only --diff-filter=D "$base" HEAD 2>/dev/null
  fi
} | sort -u | grep -v '^$' > "$REPO_ROOT/$DELETED_LIST" || true
DELETED_COUNT="$(wc -l < "$REPO_ROOT/$DELETED_LIST" | tr -d ' ')"

run_stage "하네스 삭제 가드 (삭제 $DELETED_COUNT 건)" \
  node scripts/harness/guard.mjs --deleted-list "$DELETED_LIST"

# ── 4. Gradle 빌드/테스트 ──
# ci.yml 의 매핑과 동일하게 유지할 것. 여기가 어긋나면 로컬 통과 → CI 실패가 난다.
MODULES=(
  order-service settlement-service gateway-service account-service operation-service
  loan-service financial-statements-service economics-service company-service
  market-service common-data-service investment-service
  organization-service card-service
)

gradle_tasks() {
  # shared-common 변경 → 전체 (CI 와 동일하게 게이트 약화 방지)
  if grep -qE '^shared-common/' "$CHANGED_LIST"; then
    echo "build"; return
  fi
  local tasks="" m
  for m in "${MODULES[@]}"; do
    if grep -qE "^${m}/" "$CHANGED_LIST"; then tasks="$tasks :${m}:build"; fi
  done
  if [ -z "$tasks" ]; then
    # 모듈에도 shared 에도 안 걸리는 backend 변경이 있으면 CI 는 전체로 격상한다.
    if grep -qE '\.(java|kt|gradle|kts)$|^gradle/|^settings\.gradle|^build\.gradle' "$CHANGED_LIST"; then
      echo "build"; return
    fi
    echo ""; return
  fi
  echo "$tasks"
}

case "$MODE" in
  fast)
    echo ""
    echo "⏭  Gradle — --fast 지정, 건너뜀"
    ;;
  all)
    run_stage "Gradle 전체 build" ./gradlew clean build --no-daemon
    ;;
  changed)
    TASKS="$(gradle_tasks)"
    if [ -z "$TASKS" ]; then
      echo ""
      echo "⏭  Gradle — 백엔드 변경 없음, 건너뜀"
    elif [ "$TASKS" = "build" ]; then
      echo ""
      echo "ℹ  shared-common 또는 모호한 백엔드 변경 → 전체 build 로 격상 (CI 와 동일)"
      run_stage "Gradle 전체 build" ./gradlew clean build --no-daemon
    else
      # shellcheck disable=SC2086
      run_stage "Gradle 변경 모듈 build ($TASKS)" ./gradlew clean $TASKS --no-daemon
    fi
    ;;
esac

# ── 요약 ──
echo ""
echo "==============================================="
if [ -z "$FAILED_STAGE" ]; then
  echo " ✅ verify 통과 — 변경 $CHANGED_COUNT 건, base=$BASE_REF"
  echo "==============================================="
  exit 0
fi
echo " ❌ verify 실패 — 첫 실패 단계: $FAILED_STAGE (exit $FAILED_CODE)"
echo "==============================================="
exit "$FAILED_CODE"
