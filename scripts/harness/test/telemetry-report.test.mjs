import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { canaryResults, collectMergedLogs, runReportCli, runsReport } from '../telemetry-report.mjs';
import { LOG_DIR_SEGMENTS, logGuardRun } from '../telemetry.mjs';
import { runIdsFromListJson, runPullCli } from '../telemetry-ci-pull.mjs';

const NOW = new Date('2026-07-22T09:00:00Z');

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'telemetry-report-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function seedLog(root, fileName, records) {
  const directory = join(root, ...LOG_DIR_SEGMENTS);
  await mkdir(directory, { recursive: true });
  await writeFile(join(directory, fileName), records.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8');
}

describe('guard canaries', () => {
  test('every rule has a canary fixture and every canary passes', () => {
    const results = canaryResults();
    assert.ok(results.length > 0);
    for (const { id, status } of results) {
      assert.equal(status, 'pass', `canary for ${id} must pass (got ${status})`);
    }
  });
});

describe('가드 실행 분모 (guard-runs.jsonl)', () => {
  test('실행 기록이 없으면 "0회 = 무위반"으로 읽지 말라고 경고한다', () => {
    const report = runsReport([]);
    assert.match(report, /기록 없음/);
    assert.match(report, /미측정/);
  });

  test('모드별로 실행·검사 파일 수·위반 발생 횟수를 분리해 센다', () => {
    const report = runsReport([
      { ts: '2026-07-21T10:00:00Z', mode: 'hook', files: 1, violations: 0 },
      { ts: '2026-07-21T10:01:00Z', mode: 'hook', files: 1, violations: 2 },
      { ts: '2026-07-21T10:02:00Z', mode: 'staged', files: 7, violations: 0 },
    ]);
    assert.match(report, /총 3회/);
    assert.match(report, /mode hook: 2회 실행 · 파일 2건 검사 · 위반 발생 1회/);
    assert.match(report, /mode staged: 1회 실행 · 파일 7건 검사 · 위반 발생 0회/);
  });

  test('위반 0건이어도 실행 자체는 기록된다 — 침묵과 무위반을 구분하는 지점', async () => {
    const root = await temporaryRepo();
    await logGuardRun(root, 'hook', { files: 1, violations: 0 });
    const output = [];
    assert.equal(await runReportCli(['--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    const text = output.join('\n');
    assert.match(text, /mode hook: 1회 실행 · 파일 1건 검사 · 위반 발생 0회/);
    assert.doesNotMatch(text, /no data yet/);
  });

  test('킬 스위치(HARNESS_TELEMETRY=off)는 분모 기록도 막는다', async () => {
    const root = await temporaryRepo();
    assert.equal(await logGuardRun(root, 'hook', { files: 1, violations: 0 }, { env: { HARNESS_TELEMETRY: 'off' } }), false);
    const output = [];
    assert.equal(await runReportCli(['--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    assert.match(output.join('\n'), /no data yet/);
  });
});

describe('CI 텔레메트리 병합 (--merge · 머신 경계 단절 해소)', () => {
  test('런별 서브디렉토리를 재귀로 걷어 4종 로그를 합산한다', async () => {
    const root = await temporaryRepo();
    const runDir = join(root, 'ci-logs', '12345', 'harness-telemetry-12345-1');
    await mkdir(runDir, { recursive: true });
    await writeFile(join(runDir, 'guard-hits.jsonl'), JSON.stringify({ ts: '2026-08-15T00:00:00Z', id: 'MSA-BOUNDARY', mode: 'list' }) + '\n', 'utf8');
    await writeFile(join(runDir, 'guard-runs.jsonl'), JSON.stringify({ ts: '2026-08-15T00:00:00Z', mode: 'list', files: 3, violations: 1 }) + '\n', 'utf8');
    await writeFile(join(runDir, 'unrelated.txt'), 'ignore me', 'utf8');
    const merged = collectMergedLogs([join(root, 'ci-logs')]);
    assert.equal(merged.files, 2);
    assert.equal(merged['guard-hits.jsonl'].length, 1);
    assert.equal(merged['guard-runs.jsonl'].length, 1);
    assert.equal(merged['skill-usage.jsonl'].length, 0);
  });

  test('--merge 는 로컬 로그와 합산된 리포트를 낸다 (없는 디렉토리는 무해)', async () => {
    const repoRoot = await temporaryRepo();
    await seedLog(repoRoot, 'guard-hits.jsonl', [{ ts: '2026-07-21T09:00:00Z', id: 'MONEY-PRIMITIVE', mode: 'hook', file: 'a.java', line: 1 }]);
    const ciDir = join(repoRoot, 'ci-logs', '777');
    await mkdir(ciDir, { recursive: true });
    await writeFile(join(ciDir, 'guard-hits.jsonl'), JSON.stringify({ ts: '2026-07-21T10:00:00Z', id: 'MSA-BOUNDARY', mode: 'list', file: 'b.java', line: 2 }) + '\n', 'utf8');
    const out = [];
    const code = await runReportCli(['--merge', 'ci-logs', '--merge', 'no-such-dir'], { repoRoot, now: NOW, stdout: (t) => out.push(t) });
    assert.equal(code, 0);
    const text = out.join('\n');
    assert.match(text, /CI 병합.*로그 파일 1건/);
    assert.match(text, /전체 2건/); // 로컬 1 + CI 1
    assert.match(text, /MSA-BOUNDARY/);
  });

  test('ci-pull: run 목록 파싱은 오염 입력에 안전하고, exec 주입으로 다운로드 흐름을 검증한다', async () => {
    assert.deepEqual(runIdsFromListJson('[{"databaseId":11},{"databaseId":"x"},{}]'), [11]);
    assert.deepEqual(runIdsFromListJson('not json'), []);
    const repoRoot = await temporaryRepo();
    const calls = [];
    const io = {
      repoRoot,
      stdout() {},
      stderr() {},
      exec: (cmd, args) => {
        calls.push([cmd, args[1]]);
        if (args[1] === 'list') return '[{"databaseId":1},{"databaseId":2}]';
        if (args[2] === '2') throw new Error('artifact expired');
        return '';
      },
    };
    assert.equal(await runPullCli([], io), 0);
    assert.equal(calls.filter(([, sub]) => sub === 'download').length, 2);
    // 멱등: 성공한 run 1 은 재실행 시 기수집으로 건너뛴다
    calls.length = 0;
    await runPullCli([], io);
    assert.deepEqual(calls.filter(([, sub]) => sub === 'download').map(([, s]) => s), ['download']);
  });

  test('ci-pull: gh 자체가 없으면 안내와 함께 exit 1', async () => {
    const errors = [];
    const code = await runPullCli([], {
      repoRoot: await temporaryRepo(),
      stdout() {},
      stderr: (m) => errors.push(m),
      exec: () => { throw new Error('gh: command not found'); },
    });
    assert.equal(code, 1);
    assert.match(errors.join('\n'), /gh CLI/);
  });
});

describe('SessionStart hook mode (--hook)', () => {
  test('emits SessionStart additionalContext when telemetry data exists', async () => {
    const root = await temporaryRepo();
    await seedLog(root, 'guard-hits.jsonl', [
      { ts: '2026-07-21T10:00:00Z', mode: 'hook', id: 'MONEY-PRIMITIVE', file: 'x.java', line: 1 },
      { ts: '2026-07-21T11:00:00Z', mode: 'hook', id: 'MONEY-PRIMITIVE', file: 'y.java', line: 2 },
    ]);
    await seedLog(root, 'skill-suggestions.jsonl', [
      { ts: '2026-07-21T10:00:00Z', skill: 'money-safety', file: 'x.java' },
    ]);
    const output = [];
    assert.equal(await runReportCli(['--hook', '--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    const parsed = JSON.parse(output.join(''));
    assert.equal(parsed.hookSpecificOutput.hookEventName, 'SessionStart');
    assert.match(parsed.hookSpecificOutput.additionalContext, /가드 차단/);
    assert.match(parsed.hookSpecificOutput.additionalContext, /MONEY-PRIMITIVE/);
    assert.match(parsed.hookSpecificOutput.additionalContext, /순응률/);
  });

  test('stays silent with no data and healthy canaries, always exit 0', async () => {
    const root = await temporaryRepo();
    const output = [];
    assert.equal(await runReportCli(['--hook', '--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    assert.deepEqual(output, []);
  });

  test('malformed jsonl lines never break the hook', async () => {
    const root = await temporaryRepo();
    await seedLog(root, 'guard-hits.jsonl', []);
    const directory = join(root, ...LOG_DIR_SEGMENTS);
    await writeFile(join(directory, 'guard-hits.jsonl'), '{not json}\n{"ts":"2026-07-21T10:00:00Z","mode":"hook","id":"MSA-BOUNDARY","file":"z.java","line":3}\n', 'utf8');
    const output = [];
    assert.equal(await runReportCli(['--hook', '--root', root], { stdout: (s) => output.push(s), now: NOW }), 0);
    assert.match(JSON.parse(output.join('')).hookSpecificOutput.additionalContext, /MSA-BOUNDARY/);
  });
});
