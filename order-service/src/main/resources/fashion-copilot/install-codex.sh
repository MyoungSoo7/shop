#!/usr/bin/env bash
# fashion-copilot — Codex CLI 설치 (하위 호환 래퍼).
# 실제 로직은 install.mjs (크로스 플랫폼 — PowerShell 에서는 node install.mjs codex 를 직접 실행).
#
#   bash fashion-copilot/install-codex.sh [--internal-key=KEY ...]
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec node "$DIR/install.mjs" codex "$@"
