#!/usr/bin/env node
// Lemuel 세션 메트릭 — OMC 런타임 상태(.omc/state)와 git 이력을 조인해
// KPI-3(워크플로 완주율)·KPI-4(재작업률)를 산출하는 리포트 CLI.
//
//   node scripts/harness/session-metrics.mjs [--root <repo>] [--days N]
//
// 경계: .omc 는 OMC 플러그인 소유 — 이 스크립트는 **읽기 전용 관측**만 하며 절대 쓰지 않고,
// 디렉토리 부재(플러그인 미설치·새 클론·CI)에서도 우아하게 빈 값으로 동작한다.
// 세션이 병렬로 겹치므로 세션↔커밋 1:1 귀속은 하지 않는다 — 일 단위 병렬 추이만 제시.
// 항상 exit 0 — 리포트는 게이트가 아니다.

import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function readSessions(root) {
  const directory = resolve(root, '.omc', 'state', 'sessions');
  if (!existsSync(directory)) return [];
  const sessions = [];
  for (const id of readdirSync(directory)) {
    try {
      const parsed = JSON.parse(readFileSync(resolve(directory, id, 'session-started.json'), 'utf8'));
      if (typeof parsed?.started_at === 'string') sessions.push({ id, startedAt: parsed.started_at });
    } catch { /* 손상·비표준 세션 디렉토리는 관측 누락일 뿐 — 리포트는 죽지 않는다 */ }
  }
  return sessions.sort((a, b) => a.startedAt.localeCompare(b.startedAt));
}

export function readMissions(root) {
  try {
    const parsed = JSON.parse(readFileSync(resolve(root, '.omc', 'state', 'mission-state.json'), 'utf8'));
    return Array.isArray(parsed?.missions) ? parsed.missions : [];
  } catch {
    return [];
  }
}

// conventional commit 타입이 fix/revert/hotfix 인 커밋 = 재작업 프록시(KPI-4).
const REWORK_SUBJECT = /^(fix|revert|hotfix)(\(|!|:)/i;

export function classifyCommits(subjects) {
  const total = subjects.length;
  const rework = subjects.filter((subject) => REWORK_SUBJECT.test(subject)).length;
  return { total, rework, ratePct: total ? Math.round((rework / total) * 1000) / 10 : null };
}

export function gitSubjects(root, days) {
  try {
    const raw = execFileSync('git', ['-C', root, 'log', `--since=${days} days ago`, '--format=%s'], { encoding: 'utf8' });
    return raw.split('\n').filter(Boolean);
  } catch {
    return [];
  }
}

export function summarize({ sessions, missions, subjects, days, now = new Date() }) {
  const since = new Date(now.getTime() - days * 86_400_000).toISOString();
  const recent = sessions.filter((session) => session.startedAt >= since);
  const lines = [];

  lines.push(`== 세션 (전체 ${sessions.length}건, 최근 ${days}일 ${recent.length}건) — .omc/state/sessions 읽기 전용 관측 ==`);
  const byDay = new Map();
  for (const session of recent) {
    const day = session.startedAt.slice(0, 10);
    byDay.set(day, (byDay.get(day) ?? 0) + 1);
  }
  for (const [day, count] of [...byDay.entries()].sort()) lines.push(`  ${day}: 세션 ${count}건`);

  const done = missions.filter((mission) => mission?.status === 'done').length;
  const failed = missions.filter((mission) => mission?.status === 'failed').length;
  const workers = missions.reduce((sum, mission) => sum + (Number(mission?.workerCount) || 0), 0);
  const tasksTotal = missions.reduce((sum, mission) => sum + (Number(mission?.taskCounts?.total) || 0), 0);
  const tasksCompleted = missions.reduce((sum, mission) => sum + (Number(mission?.taskCounts?.completed) || 0), 0);
  lines.push(`== 미션(OMC 위임 오케스트레이션) ${missions.length}건 — 워커 누적 ${workers}명, 태스크 ${tasksCompleted}/${tasksTotal} 완료 ==`);
  if (missions.length > 0) {
    const closed = done + failed;
    const pct = closed ? Math.round((done / closed) * 100) : null;
    lines.push(`  KPI-3 완주율(done/종결) ${pct === null ? 'N/A' : `${pct}% (${done}/${closed})`}${missions.length < 10 ? ' — 표본 적음(n<10): 추이 지표로만 사용' : ''}`);
  } else {
    lines.push('  KPI-3 완주율 N/A — 미션 데이터 없음(위임 오케스트레이션 미사용 또는 OMC 미설치)');
  }

  const { total, rework, ratePct } = classifyCommits(subjects);
  lines.push(`== 커밋 (최근 ${days}일 ${total}건) ==`);
  lines.push(
    total
      ? `  KPI-4 재작업률(fix/revert/hotfix) ${rework}건 = ${ratePct}% — 베이스라인 19.3%(2026-07-22), 하향 추이가 목표`
      : '  KPI-4 재작업률 N/A — 기간 내 커밋 없음',
  );
  return lines.join('\n');
}

export async function runSessionMetricsCli(args, io = {}) {
  const stdout = io.stdout ?? ((text) => console.log(text));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  const root = resolve(value('--root', io.repoRoot ?? process.cwd()));
  const parsedDays = Number(value('--days', '30'));
  const days = Number.isFinite(parsedDays) && parsedDays > 0 ? parsedDays : 30;
  stdout(summarize({
    sessions: readSessions(root),
    missions: readMissions(root),
    subjects: gitSubjects(root, days),
    days,
    now: io.now ?? new Date(),
  }));
  return 0; // 리포트는 게이트가 아니다
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runSessionMetricsCli(process.argv.slice(2));
}
