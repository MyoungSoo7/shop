// Node 버전 단일 출처 게이트 — 리포 전수.
//
// 막는 것: "CI 가 검증한 dist 와 배포되는 dist 가 서로 다른 Node 엔진 산출물인 상태".
//
// frontend 이미지의 Node 는 런타임이 아니라 빌드 툴체인이다(런타임은 nginx). 그래서 이 값이
// 갈려도 이미지는 정상 기동하고, 테스트도 통과하고, 어떤 코드도 "틀리지" 않는다 — 컴파일도
// 테스트도 잡지 못한다. 드러나는 건 엔진별 산출물 차이가 프로덕션에서만 재현될 때다.
//
// 실측 사례: Dependabot #233(docker-all 그룹)이 frontend/Dockerfile 의 FROM node 를 20→26 으로
// 올렸는데, 워크플로의 node-version 리터럴은 어느 Dependabot 생태계도 추적하지 않아 "20" 에
// 그대로 남았다. 6 메이저가 벌어진 채로 머지됐고, 아무 게이트도 빨개지지 않았다. 사람이 눈으로
// 두 파일을 대조해야만 보이는 종류의 드리프트 — 그래서 기계로 옮긴다.
//
// 버전 선택 근거(24 = Active LTS): frontend 툴체인의 최강 제약은 jsdom 28 의
// `^20.19.0 || ^22.12.0 || >=24.0.0` 이고 vitest 4 가 `^20 || ^22 || >=24` 다. Node 20 은
// 2026-04 EOL 이라 보안 패치가 끊겼고, 26 은 아직 Current 라인이다. 24 가 둘 다 피한다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const NVMRC_PATH = join(repoRoot, '.nvmrc');
const DOCKERFILE_PATH = join(repoRoot, 'frontend', 'Dockerfile');
const WORKFLOW_DIR = join(repoRoot, '.github', 'workflows');

/** .nvmrc 의 major 를 뽑는다. `24`·`24.5.0`·`v24` 모두 24 로 읽는다. */
export function parseNvmrcMajor(text) {
  const m = String(text).trim().match(/^v?(\d+)(?:\.\d+)*$/);
  return m ? m[1] : null;
}

/** Dockerfile 의 `FROM node:<major>...` 에서 major 를 뽑는다. 없으면 null. */
export function parseDockerNodeMajor(dockerfile) {
  const m = String(dockerfile).match(/^FROM\s+node:v?(\d+)[.\-\s]/im);
  return m ? m[1] : null;
}

/**
 * 워크플로 YAML 에서 하드코딩된 `node-version:` 리터럴을 줄번호와 함께 찾는다.
 * `node-version-file:` 은 단일 출처를 가리키므로 대상이 아니다 — 접미사 `-file` 을
 * 명시적으로 배제해 둘을 가른다.
 */
export function findHardcodedNodeVersions(yaml) {
  const hits = [];
  String(yaml)
    .split('\n')
    .forEach((line, i) => {
      if (/^\s*node-version\s*:/.test(line) && !/^\s*node-version-file\s*:/.test(line)) {
        hits.push({ line: i + 1, text: line.trim() });
      }
    });
  return hits;
}

/** .github/workflows 아래 YAML 전부 — 작업트리 기준 스캔(미추적 파일도 본다). */
function workflowFiles() {
  if (!existsSync(WORKFLOW_DIR)) return [];
  return readdirSync(WORKFLOW_DIR, { withFileTypes: true })
    .filter((e) => e.isFile() && /\.ya?ml$/.test(e.name))
    .map((e) => join(WORKFLOW_DIR, e.name));
}

describe('Node 버전 단일 출처', () => {
  // ── 검출기 자체 검증 — 파서가 아무것도 못 잡으면 게이트는 영원히 통과한다 ──
  test('파서가 실제로 동작한다', () => {
    assert.equal(parseNvmrcMajor('24\n'), '24');
    assert.equal(parseNvmrcMajor('v24.5.0'), '24');
    assert.equal(parseNvmrcMajor('lts/iron'), null, '별칭은 major 를 특정할 수 없다');

    assert.equal(parseDockerNodeMajor('FROM node:24-alpine AS builder'), '24');
    assert.equal(parseDockerNodeMajor('FROM node:26.1.0-bookworm'), '26');
    assert.equal(parseDockerNodeMajor('FROM nginx:1.31-alpine'), null);

    const yaml = [
      '        with:',
      '          node-version: "20"',
      '          node-version-file: .nvmrc',
    ].join('\n');
    const hits = findHardcodedNodeVersions(yaml);
    assert.equal(hits.length, 1, 'node-version-file 은 하드코딩이 아니다');
    assert.equal(hits[0].line, 2);
  });

  test('.nvmrc 가 존재하고 major 를 특정할 수 있다', () => {
    assert.ok(existsSync(NVMRC_PATH), '.nvmrc 가 없다 — Node 버전 단일 출처가 사라졌다');
    assert.ok(
      parseNvmrcMajor(readFileSync(NVMRC_PATH, 'utf8')),
      '.nvmrc 값에서 major 를 읽을 수 없다 (lts/* 별칭 대신 숫자 버전을 쓴다)',
    );
  });

  test('frontend/Dockerfile 의 FROM node major 가 .nvmrc 와 일치한다', () => {
    const expected = parseNvmrcMajor(readFileSync(NVMRC_PATH, 'utf8'));
    const actual = parseDockerNodeMajor(readFileSync(DOCKERFILE_PATH, 'utf8'));

    assert.ok(actual, 'frontend/Dockerfile 에서 FROM node 를 찾지 못했다');
    assert.equal(
      actual,
      expected,
      `frontend/Dockerfile 은 node:${actual}, .nvmrc 는 ${expected} — 배포 산출물과 CI 검증 대상이 갈린다. ` +
        '베이스 이미지를 올릴 때는 .nvmrc 도 같이 올린다(Dependabot PR 이면 그 PR 안에서).',
    );
  });

  test('워크플로에 하드코딩된 node-version 이 없다', () => {
    const offenders = workflowFiles().flatMap((path) =>
      findHardcodedNodeVersions(readFileSync(path, 'utf8')).map(
        (h) => `${path.slice(repoRoot.length + 1).replace(/\\/g, '/')}:${h.line}  ${h.text}`,
      ),
    );

    assert.deepEqual(
      offenders,
      [],
      `node-version 리터럴은 .nvmrc 와 조용히 갈린다 — node-version-file: .nvmrc 로 바꾼다:\n${offenders.join('\n')}`,
    );
  });

  test('setup-node 를 쓰는 워크플로는 .nvmrc 를 가리킨다', () => {
    const missing = workflowFiles()
      .map((path) => ({ path, yaml: readFileSync(path, 'utf8') }))
      .filter(({ yaml }) => yaml.includes('actions/setup-node'))
      .filter(({ yaml }) => !yaml.includes('node-version-file: .nvmrc'))
      .map(({ path }) => path.slice(repoRoot.length + 1).replace(/\\/g, '/'));

    assert.deepEqual(
      missing,
      [],
      `setup-node 를 쓰면서 .nvmrc 를 안 읽는 워크플로 — 러너가 기본 Node 로 돌아 로스터 밖에 놓인다:\n${missing.join('\n')}`,
    );
  });
});
