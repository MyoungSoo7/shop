#!/usr/bin/env node
// 하네스 자산 무결성 감사 — 등록부(manifest.json)에 적힌 것이 실제로 저장소에 있는지 본다.
//
// 왜 있나: PR #210 에서 하네스 270 파일이 조용히 지워진 적이 있다. 가드는 "변경된 파일"을
// 검사하므로, 가드 자신이 삭제되면 검사할 것이 없어져 **초록불**이 된다. 등록부는 그 구멍을
// 메우려고 만들어졌지만, 그 등록부를 읽는 쪽(이 파일)이 없어서 지금까지 데이터로만 있었다.
// verify.sh 2단계가 이 파일을 부르고 있었고, 파일이 없으니 verify.sh 는 늘 실패했다 —
// "다 됐다를 종료 코드로 증명한다"는 스크립트가 정작 0 을 낸 적이 없었다.
//
// 사용:
//   node scripts/harness/harness-audit.mjs
//   exit 0 = 등록부와 저장소가 일치 · 1 = 불일치(누락·untracked·빈 파일)
//
// 검사 범위(정직 명세) — 이 감사는 아래 세 가지만 본다:
//   1. requiredTrackedFiles 의 각 경로가 **git 에 추적되고 있는가**
//      (working tree 에만 있는 파일은 통과시키지 않는다 — 커밋 안 된 가드는 CI 에 없다)
//   2. 그 파일이 실제로 존재하며 비어 있지 않은가 (내용을 지우고 파일만 남기는 우회 차단)
//   3. 등록부 자신이 requiredTrackedFiles 에 들어 있는가 (등록부만 지우면 감사가 무력해진다)
//   4. requiredExecutableFiles 가 **git 인덱스에서** 100755 인가. git 은 실행 비트가 없는 훅을
//      말없이 건너뛰고, `./scripts/verify.sh` 는 permission denied 로 끝난다. 워킹트리 권한이
//      아니라 인덱스 모드를 보는 이유는 그것이 다른 사람이 체크아웃할 때 받는 값이기 때문이다.
//
// 하지 않는 것: 라우팅 dangling 검사, CI 매트릭스 대조, 문서 수치 드리프트 검사. 문서 몇
// 군데가 harness-audit 이 그것들도 한다고 적고 있으나 사실이 아니다. 그 규칙들은 각각
// scripts/harness/test/ 아래 개별 게이트(gateway-route-gate, coverage-scope-gate 등)에 있다.

import { existsSync, readFileSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

const MANIFEST_PATH = 'scripts/harness/manifest.json';

export function loadManifest(repoRoot) {
  const path = resolve(repoRoot, MANIFEST_PATH);
  if (!existsSync(path)) {
    return { error: `등록부가 없다: ${MANIFEST_PATH}` };
  }
  try {
    return { manifest: JSON.parse(readFileSync(path, 'utf8')) };
  } catch (e) {
    return { error: `등록부를 읽을 수 없다: ${MANIFEST_PATH} — ${e.message}` };
  }
}

export function listTrackedFiles(repoRoot) {
  const out = execFileSync('git', ['ls-files', '-z'], { cwd: repoRoot, encoding: 'utf8' });
  return new Set(out.split('\0').filter(Boolean));
}

/** 경로 → git 인덱스 모드('100644' | '100755' | …). 워킹트리 권한이 아니라 커밋될 값이다. */
export function listIndexModes(repoRoot) {
  const out = execFileSync('git', ['ls-files', '-s', '-z'], { cwd: repoRoot, encoding: 'utf8' });
  const modes = new Map();
  for (const entry of out.split('\0').filter(Boolean)) {
    // "<mode> <sha> <stage>\t<path>"
    const tab = entry.indexOf('\t');
    if (tab < 0) continue;
    modes.set(entry.slice(tab + 1), entry.slice(0, entry.indexOf(' ')));
  }
  return modes;
}

/**
 * 등록부 대비 위반 목록. 빈 배열이면 통과.
 * tracked 를 주입받는 이유는 테스트에서 git 없이도 판정을 검사하기 위해서다.
 */
export function auditManifest(repoRoot, manifest, tracked, indexModes = null) {
  const violations = [];
  const required = manifest?.requiredTrackedFiles;

  if (!Array.isArray(required) || required.length === 0) {
    violations.push({
      path: MANIFEST_PATH,
      reason: 'requiredTrackedFiles 가 비었다 — 등록부를 비우면 이 감사는 아무것도 지키지 않는다',
    });
    return violations;
  }

  if (!required.includes(MANIFEST_PATH)) {
    violations.push({
      path: MANIFEST_PATH,
      reason: '등록부 자신이 requiredTrackedFiles 에 없다 — 등록부만 지우면 감사가 사라진다',
    });
  }

  for (const path of required) {
    if (!tracked.has(path)) {
      // 파일이 디스크에 있어도 추적되지 않으면 CI 체크아웃에는 없다.
      violations.push({
        path,
        reason: existsSync(join(repoRoot, path))
          ? 'git 에 추적되지 않음 — 커밋되지 않은 가드는 CI 에 존재하지 않는다'
          : '없음 — 삭제됐거나 경로가 바뀌었다',
      });
      continue;
    }
    let size = -1;
    try {
      size = statSync(join(repoRoot, path)).size;
    } catch {
      violations.push({ path, reason: '추적되고 있으나 워킹트리에서 읽을 수 없다' });
      continue;
    }
    if (size === 0) {
      violations.push({ path, reason: '내용이 비었다 — 파일만 남기고 규칙을 지운 형태' });
    }
  }

  for (const path of manifest.requiredExecutableFiles ?? []) {
    if (!required.includes(path)) {
      violations.push({
        path,
        reason: 'requiredExecutableFiles 에만 있고 requiredTrackedFiles 에는 없다 — 존재 자체가 안 지켜진다',
      });
      continue;
    }
    if (!indexModes) continue; // 모드를 알 수 없는 호출(순수 판정 테스트) — 존재 검사만 한 것
    const mode = indexModes.get(path);
    if (mode && mode !== '100755') {
      violations.push({
        path,
        reason: `git 인덱스 모드가 ${mode} — 실행 비트가 없다. 훅은 말없이 skip 되고 ./로 부르면 permission denied 다 (git update-index --chmod=+x ${path})`,
      });
    }
  }
  return violations;
}

export function formatViolations(violations, checkedCount) {
  if (violations.length === 0) {
    return `하네스 자산 ${checkedCount}건 — 등록부와 저장소가 일치한다.`;
  }
  const lines = violations.map((v) => `  ✗ ${v.path}\n      ${v.reason}`);
  return [
    `하네스 자산 감사 실패 — ${violations.length}건 (등록부: ${MANIFEST_PATH})`,
    ...lines,
    '',
    '가드를 옮겼다면 등록부의 경로도 같이 고친다. 정말 없애는 것이라면 등록부에서 빼되,',
    '그 결정은 커밋 메시지에 남긴다 — 조용히 사라지는 것을 막는 게 이 감사의 목적이다.',
  ].join('\n');
}

export function runAuditCli(io = {}) {
  const stdout = io.stdout ?? ((m) => console.log(m));
  const stderr = io.stderr ?? ((m) => console.error(m));
  const repoRoot = io.repoRoot ?? process.cwd();

  const { manifest, error } = loadManifest(repoRoot);
  if (error) {
    stderr(error);
    return 1;
  }
  let tracked;
  let indexModes;
  try {
    tracked = io.tracked ?? listTrackedFiles(repoRoot);
    indexModes = io.indexModes ?? (io.tracked ? null : listIndexModes(repoRoot));
  } catch (e) {
    stderr(`git ls-files 실패 — 저장소 안에서 실행하라: ${e.message}`);
    return 1;
  }
  const violations = auditManifest(repoRoot, manifest, tracked, indexModes);
  const checked = manifest.requiredTrackedFiles?.length ?? 0;
  const report = formatViolations(violations, checked);
  if (violations.length === 0) {
    stdout(report);
    return 0;
  }
  stderr(report);
  return 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = runAuditCli();
}
