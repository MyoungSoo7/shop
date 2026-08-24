// Lemuel harness telemetry — append-only JSONL under .claude/harness/logs (gitignored, never committed).
//
// Why this exists: guard.mjs blocks violations but kept no record of *what* it blocked, so
// "which guardrails actually fire" was unanswerable. This module gives every enforcement /
// routing layer a single non-fatal sink; telemetry-report.mjs aggregates it.
//
// Invariant: observability must never be able to break the guard or any hook — every write
// is best-effort and swallows all errors. Kill switch: HARNESS_TELEMETRY=off.

import { appendFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

// .claude/harness — 프로젝트 하네스 소유 런타임 디렉토리. .omc 는 OMC 플러그인이 소유·정리
// (state_clear, worktree 삭제 시 제거)하므로 하네스 상태·로그를 두면 플러그인 독립이 깨진다.
export const LOG_DIR_SEGMENTS = ['.claude', 'harness', 'logs'];

export function telemetryEnabled(env = process.env) {
  return env.HARNESS_TELEMETRY !== 'off';
}

export async function appendJsonl(repoRoot, fileName, records, { env = process.env } = {}) {
  if (!telemetryEnabled(env)) return false;
  const list = Array.isArray(records) ? records : [records];
  if (list.length === 0) return true;
  try {
    const directory = resolve(repoRoot, ...LOG_DIR_SEGMENTS);
    await mkdir(directory, { recursive: true });
    await appendFile(resolve(directory, fileName), list.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8');
    return true;
  } catch {
    return false;
  }
}

export function guardHitRecords(mode, violations, now = new Date()) {
  const ts = now.toISOString();
  return violations.map((violation) => ({
    ts,
    mode,
    id: violation.id,
    file: violation.file,
    line: violation.line ?? null,
  }));
}

export async function logGuardHits(repoRoot, mode, violations, options = {}) {
  return appendJsonl(repoRoot, 'guard-hits.jsonl', guardHitRecords(mode, violations, options.now ?? new Date()), options);
}

// 분모(heartbeat). guard-hits.jsonl 은 **위반만** 적기 때문에, 파일이 비어 있는 상태가
// "아무도 규칙을 어기지 않았다" 인지 "가드가 아예 안 돌았다" 인지 구분되지 않는다. 실제로
// 9개 체크아웃 어디에도 로그 디렉토리가 없었는데(2026-08-12 확인), 그게 무결점이라는 뜻인지
// 훅이 죽어 있었다는 뜻인지 사후에 답할 수 없었다. 실행 자체를 1줄 남겨 분모를 만든다.
export function guardRunRecord(mode, { files = 0, violations = 0 } = {}, now = new Date()) {
  return { ts: now.toISOString(), mode, files, violations };
}

export async function logGuardRun(repoRoot, mode, counts, options = {}) {
  return appendJsonl(repoRoot, 'guard-runs.jsonl', [guardRunRecord(mode, counts, options.now ?? new Date())], options);
}
