#!/usr/bin/env node
// CI 텔레메트리 아티팩트 수집 — 러너에서만 남던 가드 실행 이력을 로컬 집계로 되가져온다.
//
// harness-guard.yml 이 실행마다 harness-telemetry-<run_id>-<attempt> 아티팩트(.claude/harness/logs)
// 를 30일 보존으로 남긴다. 이 스크립트는 gh CLI 로 최근 실행분을 내려받아
// .claude/harness/ci-logs/<run_id>/ 에 두고, 리포트는 --merge 로 합산한다:
//
//   node scripts/harness/telemetry-ci-pull.mjs            # 최근 10개 run
//   node scripts/harness/telemetry-ci-pull.mjs --limit 30
//   node scripts/harness/telemetry-report.mjs --merge .claude/harness/ci-logs
//
// best-effort 운영 도구다: gh 부재·네트워크 실패·아티팩트 만료는 해당 run 건너뜀으로 처리하고,
// 전체 실패(수집 0 + 오류)만 exit 1. 이미 내려받은 run 디렉토리는 재다운로드하지 않는다(멱등).

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function runIdsFromListJson(json) {
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed)
      ? parsed.map((run) => run?.databaseId).filter((id) => Number.isInteger(id))
      : [];
  } catch {
    return [];
  }
}

export async function runPullCli(args, io = {}) {
  const stdout = io.stdout ?? ((m) => console.log(m));
  const stderr = io.stderr ?? ((m) => console.error(m));
  const exec = io.exec ?? ((cmd, cmdArgs) => execFileSync(cmd, cmdArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  const limit = Number(value('--limit', '10'));
  const repoRoot = io.repoRoot ?? process.cwd();
  const dest = resolve(repoRoot, value('--dest', '.claude/harness/ci-logs'));

  let listJson;
  try {
    listJson = exec('gh', ['run', 'list', '--workflow', 'harness-guard.yml', '--limit', String(Number.isFinite(limit) && limit > 0 ? limit : 10), '--json', 'databaseId']);
  } catch (error) {
    stderr(`telemetry-ci-pull: gh run list 실패 — gh CLI/인증/네트워크를 확인하세요 (${error.message})`);
    return 1;
  }
  const ids = runIdsFromListJson(listJson);
  if (ids.length === 0) {
    stdout('telemetry-ci-pull: 대상 run 없음');
    return 0;
  }

  let downloaded = 0;
  let skipped = 0;
  let failed = 0;
  for (const id of ids) {
    const runDir = resolve(dest, String(id));
    if (existsSync(runDir)) { skipped += 1; continue; } // 멱등 — 재실행 시 신규분만
    try {
      mkdirSync(runDir, { recursive: true });
      exec('gh', ['run', 'download', String(id), '--pattern', 'harness-telemetry-*', '--dir', runDir]);
      downloaded += 1;
    } catch {
      failed += 1; // 아티팩트 없음(게이트 이전 run)·만료(30일)·취소 run — 건너뜀
      // 만들다 만 빈 디렉토리를 남기면 다음 실행이 "기수집"으로 오인해 영원히 재시도하지 않는다.
      rmSync(runDir, { recursive: true, force: true });
    }
  }
  stdout(`telemetry-ci-pull: run ${ids.length}개 중 신규 ${downloaded} · 기수집 ${skipped} · 아티팩트 없음/실패 ${failed}`);
  stdout(`합산 리포트: node scripts/harness/telemetry-report.mjs --merge ${value('--dest', '.claude/harness/ci-logs')}`);
  return downloaded + skipped > 0 || failed === 0 ? 0 : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runPullCli(process.argv.slice(2));
}
