import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

import { auditManifest, formatViolations, loadManifest, runAuditCli } from '../harness-audit.mjs';

const MANIFEST_PATH = 'scripts/harness/manifest.json';

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), 'harness-audit-test-'));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

async function writeFileAt(repoRoot, path, content = 'x') {
  const absolute = join(repoRoot, path);
  await mkdir(join(absolute, '..'), { recursive: true });
  await writeFile(absolute, content, 'utf8');
  return absolute;
}

/** 등록부 + 등록된 파일들을 갖춘 최소 저장소. tracked 는 주입하므로 git 은 필요 없다. */
async function fixture(repoRoot, { required = [MANIFEST_PATH, 'scripts/harness/guard.mjs'], contents = {} } = {}) {
  await writeFileAt(repoRoot, MANIFEST_PATH, JSON.stringify({ schemaVersion: 1, requiredTrackedFiles: required }));
  for (const path of required) {
    if (path === MANIFEST_PATH) continue;
    await writeFileAt(repoRoot, path, contents[path] ?? 'x');
  }
  return { manifest: { schemaVersion: 1, requiredTrackedFiles: required }, tracked: new Set(required) };
}

describe('하네스 자산 감사 (등록된 가드가 조용히 사라지는 것을 막는다)', () => {
  test('등록부와 저장소가 일치하면 위반 없음', async () => {
    const repoRoot = await temporaryRepo();
    const { manifest, tracked } = await fixture(repoRoot);
    assert.deepEqual(auditManifest(repoRoot, manifest, tracked), []);
  });

  test('등록된 가드가 지워지면 잡는다 — PR #210 이 통과했던 형태', async () => {
    const repoRoot = await temporaryRepo();
    const { manifest, tracked } = await fixture(repoRoot);
    tracked.delete('scripts/harness/guard.mjs');
    await rm(join(repoRoot, 'scripts/harness/guard.mjs'));

    const violations = auditManifest(repoRoot, manifest, tracked);
    assert.equal(violations.length, 1);
    assert.equal(violations[0].path, 'scripts/harness/guard.mjs');
    assert.match(violations[0].reason, /없음/);
  });

  test('디스크에만 있고 커밋되지 않은 가드는 통과시키지 않는다', async () => {
    const repoRoot = await temporaryRepo();
    const { manifest, tracked } = await fixture(repoRoot);
    tracked.delete('scripts/harness/guard.mjs'); // 파일은 그대로 두고 추적만 뺀다

    const violations = auditManifest(repoRoot, manifest, tracked);
    assert.equal(violations.length, 1);
    assert.match(violations[0].reason, /추적되지 않음/);
  });

  test('내용을 비우고 파일만 남기는 우회를 잡는다', async () => {
    const repoRoot = await temporaryRepo();
    const { manifest, tracked } = await fixture(repoRoot, {
      contents: { 'scripts/harness/guard.mjs': '' },
    });

    const violations = auditManifest(repoRoot, manifest, tracked);
    assert.equal(violations.length, 1);
    assert.match(violations[0].reason, /비었다/);
  });

  test('등록부 자신이 목록에 없으면 잡는다 — 등록부만 지우면 감사가 사라진다', async () => {
    const repoRoot = await temporaryRepo();
    const { manifest, tracked } = await fixture(repoRoot, { required: ['scripts/harness/guard.mjs'] });

    const violations = auditManifest(repoRoot, manifest, tracked);
    assert.equal(violations.length, 1);
    assert.match(violations[0].reason, /등록부 자신이/);
  });

  test('requiredTrackedFiles 를 비워도 통과하지 않는다', async () => {
    const repoRoot = await temporaryRepo();
    const violations = auditManifest(repoRoot, { requiredTrackedFiles: [] }, new Set());
    assert.equal(violations.length, 1);
    assert.match(violations[0].reason, /비었다/);
  });

  test('등록부가 없거나 깨졌으면 CLI 가 1 을 낸다', async () => {
    const repoRoot = await temporaryRepo();
    const errors = [];
    assert.equal(runAuditCli({ repoRoot, stdout: () => {}, stderr: (m) => errors.push(m) }), 1);
    assert.match(errors.join('\n'), /등록부가 없다/);

    await writeFileAt(repoRoot, MANIFEST_PATH, '{ not json');
    assert.match(loadManifest(repoRoot).error, /읽을 수 없다/);
  });

  test('CLI 는 통과하면 0, 위반이 있으면 1 을 낸다', async () => {
    const repoRoot = await temporaryRepo();
    const { tracked } = await fixture(repoRoot);
    const out = [];
    assert.equal(runAuditCli({ repoRoot, tracked, stdout: (m) => out.push(m), stderr: (m) => out.push(m) }), 0);
    assert.match(out.join('\n'), /일치한다/);

    tracked.delete('scripts/harness/guard.mjs');
    assert.equal(runAuditCli({ repoRoot, tracked, stdout: () => {}, stderr: () => {} }), 1);
  });

  test('실행 비트가 빠진 훅·스크립트를 잡는다 — git 은 말없이 skip 한다', async () => {
    const repoRoot = await temporaryRepo();
    const required = [MANIFEST_PATH, 'scripts/verify.sh'];
    await fixture(repoRoot, { required });
    const manifest = { schemaVersion: 1, requiredTrackedFiles: required, requiredExecutableFiles: ['scripts/verify.sh'] };
    const tracked = new Set(required);

    const modes = new Map([[MANIFEST_PATH, '100644'], ['scripts/verify.sh', '100644']]);
    const violations = auditManifest(repoRoot, manifest, tracked, modes);
    assert.equal(violations.length, 1);
    assert.match(violations[0].reason, /실행 비트가 없다/);

    modes.set('scripts/verify.sh', '100755');
    assert.deepEqual(auditManifest(repoRoot, manifest, tracked, modes), []);
  });

  test('실행 대상이 추적 목록에 없으면 잡는다 — 존재 자체가 안 지켜진다', async () => {
    const repoRoot = await temporaryRepo();
    const required = [MANIFEST_PATH];
    await fixture(repoRoot, { required });
    const manifest = { schemaVersion: 1, requiredTrackedFiles: required, requiredExecutableFiles: ['scripts/verify.sh'] };

    const violations = auditManifest(repoRoot, manifest, new Set(required), new Map());
    assert.equal(violations.length, 1);
    assert.match(violations[0].reason, /requiredTrackedFiles 에는 없다/);
  });

  test('[자기검증] 실제 저장소의 verify.sh 와 pre-commit 훅이 실행 가능하다', () => {
    const repoRoot = process.cwd();
    const { manifest } = loadManifest(repoRoot);
    const modes = new Map(
      execFileSync('git', ['ls-files', '-s', '-z'], { cwd: repoRoot, encoding: 'utf8' })
        .split('\0')
        .filter(Boolean)
        .map((entry) => [entry.slice(entry.indexOf('\t') + 1), entry.slice(0, entry.indexOf(' '))]),
    );
    // 훅은 실행 비트가 없으면 git 이 조용히 건너뛴다 — 가드가 있는 척하는 가장 값싼 방법이다.
    assert.ok((manifest.requiredExecutableFiles ?? []).includes('scripts/harness/hooks/pre-commit'));
    for (const path of manifest.requiredExecutableFiles ?? []) {
      assert.equal(modes.get(path), '100755', `${path} 의 인덱스 모드가 100755 가 아니다`);
    }
  });

  test('요약문은 위반 경로와 이유를 모두 담는다', () => {
    const text = formatViolations([{ path: 'a/b.mjs', reason: '없음' }], 2);
    assert.match(text, /a\/b\.mjs/);
    assert.match(text, /없음/);
  });

  test('[자기검증] 실제 저장소의 등록부가 지금 통과한다', () => {
    const repoRoot = process.cwd();
    const { manifest, error } = loadManifest(repoRoot);
    assert.equal(error, undefined);
    const tracked = new Set(
      execFileSync('git', ['ls-files', '-z'], { cwd: repoRoot, encoding: 'utf8' }).split('\0').filter(Boolean),
    );
    assert.deepEqual(auditManifest(repoRoot, manifest, tracked), []);
  });
});
