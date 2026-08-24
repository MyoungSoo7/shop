import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { assessModule, formatAssessment, runFreshnessCli } from '../report-freshness.mjs';

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'freshness-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

const HOUR = 3_600_000;
async function fileAt(path, agoMs) {
  await mkdir(join(path, '..'), { recursive: true });
  await writeFile(path, 'x', 'utf8');
  const when = new Date(Date.now() - agoMs);
  await utimes(path, when, when);
}

async function moduleFixture(repoRoot, { sourceAgoMs, reportAgoMs }) {
  const mod = 'settlement-service';
  await fileAt(join(repoRoot, mod, 'src', 'main', 'java', 'X.java'), sourceAgoMs);
  if (reportAgoMs != null) {
    await fileAt(join(repoRoot, mod, 'build', 'test-results', 'test', 'TEST-X.xml'), reportAgoMs);
  }
  return mod;
}

describe('report freshness (낡은 XML 인용 차단)', () => {
  test('리포트가 소스 이후면 fresh', async () => {
    const repoRoot = await temporaryRepo();
    const mod = await moduleFixture(repoRoot, { sourceAgoMs: 2 * HOUR, reportAgoMs: 1 * HOUR });
    assert.equal(assessModule(repoRoot, mod).status, 'fresh');
  });

  test('소스가 리포트보다 새로우면 stale — 직전 빌드 산출물 인용 금지', async () => {
    const repoRoot = await temporaryRepo();
    const mod = await moduleFixture(repoRoot, { sourceAgoMs: 1 * HOUR, reportAgoMs: 2 * HOUR });
    const assessment = assessModule(repoRoot, mod);
    assert.equal(assessment.status, 'stale');
    assert.match(formatAssessment(assessment), /STALE — 인용 금지/);
  });

  test('리포트가 없으면 missing — "통과" 주장 불가', async () => {
    const repoRoot = await temporaryRepo();
    const mod = await moduleFixture(repoRoot, { sourceAgoMs: HOUR, reportAgoMs: null });
    assert.equal(assessModule(repoRoot, mod).status, 'missing');
  });

  test('jacoco 리포트만 있어도 리포트로 인정한다', async () => {
    const repoRoot = await temporaryRepo();
    const mod = await moduleFixture(repoRoot, { sourceAgoMs: 2 * HOUR, reportAgoMs: null });
    await fileAt(join(repoRoot, mod, 'build', 'reports', 'jacoco', 'test', 'jacocoTestReport.xml'), HOUR);
    assert.equal(assessModule(repoRoot, mod).status, 'fresh');
  });

  test('src 가 없는 모듈명은 no-sources 로 즉시 드러난다 (오타가 fresh 로 통과하지 않게)', async () => {
    const repoRoot = await temporaryRepo();
    assert.equal(assessModule(repoRoot, 'no-such-service').status, 'no-sources');
  });

  test('CLI: stale/missing 이 하나라도 있으면 exit 1, 전부 fresh 면 0, 인자 없으면 2', async () => {
    const repoRoot = await temporaryRepo();
    await moduleFixture(repoRoot, { sourceAgoMs: 2 * HOUR, reportAgoMs: 1 * HOUR });
    const io = { repoRoot, stdout() {}, stderr() {} };
    assert.equal(await runFreshnessCli(['settlement-service'], io), 0);
    await fileAt(join(repoRoot, 'settlement-service', 'src', 'main', 'java', 'Y.java'), 0);
    assert.equal(await runFreshnessCli(['settlement-service'], io), 1);
    assert.equal(await runFreshnessCli([], io), 2);
  });
});
