import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { classifyCommits, readMissions, readSessions, runSessionMetricsCli, summarize } from '../session-metrics.mjs';

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'session-metrics-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('classifyCommits', () => {
  test('counts fix/revert/hotfix conventional subjects as rework', () => {
    const subjects = [
      'feat(payout): 자동 생성 배선',
      'fix(recon): 이중 clawback 차단',
      'revert: feat(payout) 롤백',
      'hotfix!: 긴급 패치',
      'docs(fixture): fix 라는 단어가 본문에 있어도 재작업 아님',
    ];
    assert.deepEqual(classifyCommits(subjects), { total: 5, rework: 3, ratePct: 60 });
  });

  test('empty history yields null rate, never NaN', () => {
    assert.deepEqual(classifyCommits([]), { total: 0, rework: 0, ratePct: null });
  });
});

describe('readSessions / readMissions', () => {
  test('reads OMC session-started files, skips corrupt entries, sorts by start', async () => {
    const root = await temporaryRepo();
    const sessions = join(root, '.omc', 'state', 'sessions');
    await mkdir(join(sessions, 'b-later'), { recursive: true });
    await mkdir(join(sessions, 'a-earlier'), { recursive: true });
    await mkdir(join(sessions, 'corrupt'), { recursive: true });
    await writeFile(join(sessions, 'b-later', 'session-started.json'), JSON.stringify({ started_at: '2026-07-02T00:00:00Z' }));
    await writeFile(join(sessions, 'a-earlier', 'session-started.json'), JSON.stringify({ started_at: '2026-07-01T00:00:00Z' }));
    await writeFile(join(sessions, 'corrupt', 'session-started.json'), 'not json');
    assert.deepEqual(readSessions(root).map((session) => session.id), ['a-earlier', 'b-later']);
  });

  test('absent .omc (fresh clone / CI) degrades to empty, not a crash', async () => {
    const root = await temporaryRepo();
    assert.deepEqual(readSessions(root), []);
    assert.deepEqual(readMissions(root), []);
  });
});

describe('summarize', () => {
  const NOW = new Date('2026-07-22T00:00:00Z');

  test('computes KPI-3 from mission closures and KPI-4 from commit subjects', () => {
    const report = summarize({
      sessions: [{ id: 's1', startedAt: '2026-07-21T01:00:00Z' }],
      missions: [
        { status: 'done', workerCount: 3, taskCounts: { total: 3, completed: 3 } },
        { status: 'failed', workerCount: 1, taskCounts: { total: 2, completed: 1 } },
      ],
      subjects: ['feat: a', 'fix: b'],
      days: 30,
      now: NOW,
    });
    assert.match(report, /KPI-3 완주율\(done\/종결\) 50% \(1\/2\)/);
    assert.match(report, /표본 적음/);
    assert.match(report, /KPI-4 재작업률\(fix\/revert\/hotfix\) 1건 = 50%/);
    assert.match(report, /워커 누적 4명, 태스크 4\/5 완료/);
  });

  test('no OMC data reports N/A instead of failing', () => {
    const report = summarize({ sessions: [], missions: [], subjects: [], days: 30, now: NOW });
    assert.match(report, /KPI-3 완주율 N\/A/);
    assert.match(report, /KPI-4 재작업률 N\/A/);
  });
});

describe('CLI', () => {
  test('always exits 0 even on a bare directory (report, not gate)', async () => {
    const root = await temporaryRepo();
    const outputs = [];
    const code = await runSessionMetricsCli(['--root', root, '--days', '7'], { stdout: (text) => outputs.push(text) });
    assert.equal(code, 0);
    assert.match(outputs.join('\n'), /최근 7일/);
  });
});
