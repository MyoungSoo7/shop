// 커버리지 아티팩트 레이아웃 게이트 — 집계 잡의 "Restore report paths" 를 실제로 실행해 본다.
//
// 막는 것: "테스트는 다 통과했는데 필수 백엔드 게이트가 커버리지 복원 실패로 빨개지는" 상태.
//
// 실측 결함(2026-08-22, run 32590175490 / 커밋 26a0c8592): `actions/download-artifact` 는
// `pattern:` + `merge-multiple: false` 에서 **매치가 2개 이상일 때만** 아티팩트별 하위 디렉토리를
// 만든다. 정확히 1개면 내용물을 `path` 에 곧장 푼다. 집계 잡의 복원 루프는 하위 디렉토리를
// 전제한 글로브라 0회 돌았고, restored 가 비어 "복원되지 않은 모듈이 있다"로 실패했다 —
// **백엔드 모듈이 하나만 바뀐 푸시마다** 필수 게이트가 실패하는 잠복 결함이었다.
// 그 실행의 모듈 잡은 success, 아티팩트도 394,582 B 로 정상 업로드된 상태였다.
//
// 왜 문자열 검사가 아니라 실행인가 — 이 결함은 "스크립트에 무슨 문자열이 있는가"가 아니라
// "레이아웃이 둘인데 하나만 처리한다"는 **동작**의 문제다. 그래서 ci.yml 에서 스크립트를 그대로
// 꺼내(정본 이원화 없음) 두 레이아웃에 각각 태운다.
//
// 세 번째 케이스가 핵심이다: 기대 모듈이 여럿인데 아티팩트가 하나뿐인 상태는 **진짜 배선 결함**
// 이므로 정규화가 삼키면 안 된다. 정규화를 "무조건 되돌리기"로 넓히면 이 케이스가 조용히 통과한다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
// 기본은 저장소의 ci.yml. `CI_YML_PATH` 로 다른 파일을 물릴 수 있는 이유는 **이 게이트가 정말
// 빨개지는지 증명**하기 위해서다 — 수정 전 원문(`git show <sha>:.github/workflows/ci.yml`)을
// 물리면 "아티팩트 1개" 케이스가 실패해야 한다. 통과만 확인한 게이트는 통과를 증명하지 않는다.
const CI_YML = process.env.CI_YML_PATH || join(REPO_ROOT, '.github', 'workflows', 'ci.yml');

/**
 * ci.yml 에서 지정한 스텝의 `run:` 블록을 원문 그대로 꺼낸다.
 *
 * YAML 파서를 쓰지 않는 이유 — 이 저장소의 하네스 테스트는 워크플로를 정규식으로 읽는 관례이고
 * (node-version-gate·ci-verdict-gate 동일), 여기서 필요한 것은 블록 스칼라의 들여쓰기 제거뿐이다.
 */
export function extractRunScript(yamlText, stepName) {
  const lines = String(yamlText).split(/\r?\n/);
  const nameIdx = lines.findIndex((l) => l.trim() === `- name: ${stepName}`);
  if (nameIdx < 0) return null;

  const runIdx = lines.findIndex((l, i) => i > nameIdx && /^\s*run:\s*\|\s*$/.test(l));
  if (runIdx < 0) return null;
  // 다음 스텝(`- name:`)을 만나기 전에 run 이 나와야 같은 스텝의 것이다.
  const nextStep = lines.findIndex((l, i) => i > nameIdx && /^\s*- name:\s/.test(l));
  if (nextStep >= 0 && runIdx > nextStep) return null;

  const body = [];
  const indent = (lines[runIdx].match(/^\s*/) || [''])[0].length;
  for (let i = runIdx + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === '') { body.push(''); continue; }
    const own = (line.match(/^\s*/) || [''])[0].length;
    if (own <= indent) break;
    body.push(line.slice(indent + 2));
  }
  return body.join('\n').replace(/\s+$/, '') + '\n';
}

/** 로컬(Git Bash)에는 jq 가 없는 경우가 많다. 없을 때만 최소 셈을 하는 대역을 PATH 앞에 둔다. */
function ensureJq(binDir) {
  try {
    execFileSync('jq', ['--version'], { stdio: 'ignore' });
    return null;
  } catch {
    mkdirSync(binDir, { recursive: true });
    const shim = join(binDir, 'jq');
    // 이 스크립트가 쓰는 표현은 `-r '.include[].module'` 하나뿐이다.
    writeFileSync(shim, [
      '#!/usr/bin/env bash',
      'set -euo pipefail',
      'payload=$(cat)',
      'printf \'%s\' "$payload" | grep -o \'"module"[[:space:]]*:[[:space:]]*"[^"]*"\' \\',
      '  | sed \'s/.*"\\([^"]*\\)"$/\\1/\'',
      '',
    ].join('\n'));
    chmodSync(shim, 0o755);
    return binDir;
  }
}

/** 픽스처 레이아웃을 만들고 복원 스크립트를 실제로 돌린다. */
function runRestore({ script, artifacts, matrixModules, testResult = 'success', allModules }) {
  const dir = mkdtempSync(join(tmpdir(), 'ci-artifact-layout-'));
  try {
    for (const rel of artifacts) {
      const full = join(dir, rel);
      mkdirSync(dirname(full), { recursive: true });
      writeFileSync(full, '<report/>');
    }
    // `all` 집합의 정본은 settings.gradle.kts 다 — 스크립트가 이 파일을 읽는다.
    writeFileSync(
      join(dir, 'settings.gradle.kts'),
      `include(\n${allModules.map((m) => `    "${m}",`).join('\n')}\n)\n`,
    );
    const outFile = join(dir, 'gh-output');
    writeFileSync(outFile, '');

    const shimDir = ensureJq(join(dir, '.bin'));
    const env = {
      ...process.env,
      TEST_MATRIX: JSON.stringify({ include: matrixModules.map((m) => ({ module: m })) }),
      TEST_RESULT: testResult,
      GITHUB_OUTPUT: outFile,
    };
    if (shimDir) env.PATH = `${shimDir}:${process.env.PATH ?? ''}`;

    const stdout = execFileSync('bash', ['-c', script], { cwd: dir, env, encoding: 'utf8' });
    return { ok: true, stdout, dir };
  } catch (error) {
    return { ok: false, stdout: String(error.stdout ?? ''), stderr: String(error.stderr ?? ''), dir };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const XML = 'build/reports/jacoco/test/jacocoTestReport.xml';
const ALL = ['order-service', 'settlement-service', 'education-service'];

describe('커버리지 아티팩트 레이아웃 게이트 (집계 잡 복원 스텝 실행)', () => {
  const yaml = existsSync(CI_YML) ? readFileSync(CI_YML, 'utf8') : '';
  const script = extractRunScript(yaml, 'Restore report paths');

  test('ci.yml 에서 복원 스크립트를 꺼낼 수 있다 (게이트 공회전 방지)', () => {
    assert.ok(script, 'ci.yml 의 "Restore report paths" 스텝 run 블록을 찾지 못했다');
    assert.match(script, /restored=/, '복원 스크립트가 아닌 다른 블록을 꺼냈다');
  });

  test('아티팩트 2개 — 하위 디렉토리 레이아웃을 복원한다', () => {
    const r = runRestore({
      script,
      artifacts: [
        `artifacts/backend-reports-order-service/jacoco/test/jacocoTestReport.xml`,
        `artifacts/backend-reports-education-service/jacoco/test/jacocoTestReport.xml`,
      ],
      matrixModules: ['order-service', 'education-service'],
      allModules: ALL,
    });
    assert.ok(r.ok, `복원이 실패했다:\n${r.stdout}\n${r.stderr ?? ''}`);
    assert.match(r.stdout, /복원된 커버리지 XML: 2개/);
  });

  test('아티팩트 1개 — download-artifact 가 하위 디렉토리 없이 푼 레이아웃도 복원한다', () => {
    // 이 케이스가 2026-08-22 에 필수 게이트를 깨뜨린 바로 그 모양이다.
    const r = runRestore({
      script,
      artifacts: ['artifacts/jacoco/test/jacocoTestReport.xml', 'artifacts/tests/test/index.html'],
      matrixModules: ['education-service'],
      allModules: ALL,
    });
    assert.ok(r.ok, `단일 아티팩트 레이아웃에서 실패했다 — 정규화가 없다:\n${r.stdout}\n${r.stderr ?? ''}`);
    assert.match(r.stdout, /복원된 커버리지 XML: 1개/);
    assert.match(r.stdout, /restored: education-service/);
  });

  test('기대 2개인데 아티팩트 1개 — 진짜 배선 결함이라 정규화가 삼키지 않는다', () => {
    const r = runRestore({
      script,
      artifacts: ['artifacts/jacoco/test/jacocoTestReport.xml'],
      matrixModules: ['order-service', 'education-service'],
      allModules: ALL,
    });
    assert.equal(r.ok, false, '기대 모듈이 여럿인데 아티팩트가 하나인 상태가 통과했다');
  });

  test('XML 이 하나도 없으면 실패한다 (nullglob 로 대조가 무력화되지 않는다)', () => {
    const r = runRestore({
      script,
      artifacts: ['artifacts/backend-reports-education-service/tests/test/index.html'],
      matrixModules: ['education-service'],
      allModules: ALL,
    });
    assert.equal(r.ok, false, '커버리지 XML 이 없는데 통과했다');
    assert.match(r.stdout, /복원된 커버리지 XML: 0개/);
  });
});

export { XML };
