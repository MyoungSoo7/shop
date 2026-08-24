// CI 판정 조회 게이트 — ci-verdict.mjs 의 판정 규칙과 필수 체크 표를 검증한다.
//
// 막는 것 둘:
//   ① 판정 규칙이 무너지는 것 — `cancelled`/`skipped` 를 통과로 세기 시작하면 이 도구는
//      "취소를 통과로 읽는" 바로 그 착시를 그대로 재현하는 물건이 된다.
//   ② 표가 워크플로에서 떨어져 나가는 것 — REQUIRED_CHECKS 의 체크 이름·경로 조건은
//      ci.yml / harness-guard.yml / semgrep.yml 에서 온 값이다. 잡 이름이나 `if:` 가 바뀌면
//      이 도구는 조용히 **없는 체크를 조회해 영원히 UNJUDGED** 를 뱉는다(오탐) 또는
//      경로 조건이 어긋나 조상 판정을 잘못 유효화한다(미탐). 컴파일도 CI 도 잡지 못한다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  REQUIRED_CHECKS,
  assembleVerdict,
  formatLine,
  hasPending,
  parseArgs,
  touchesScope,
  verdictFor,
  workflowPending,
} from '../ci-verdict.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const workflow = (file) => readFileSync(join(repoRoot, '.github', 'workflows', file), 'utf8');

/**
 * 워크플로 원문을 잡 블록으로 쪼갠다 — 2칸 들여쓰기 키가 잡 id.
 * YAML 파서를 들이지 않는 이유: 게이트가 검사해야 할 것은 값 두 개(name, if)뿐이고,
 * 파서 의존을 하나 늘리면 이 게이트 자체가 설치 조건을 갖게 된다.
 */
export function jobBlocks(yaml) {
  const lines = String(yaml).split('\n');
  const start = lines.findIndex((l) => /^jobs:\s*$/.test(l));
  const blocks = new Map();
  let current = null;
  for (const line of lines.slice(start + 1)) {
    const header = line.match(/^ {2}([A-Za-z0-9_-]+):\s*$/);
    if (header) {
      current = header[1];
      blocks.set(current, []);
      continue;
    }
    if (current && /^ {0,1}\S/.test(line) && line.trim() !== '') current = null; // 들여쓰기 이탈 = 잡 목록 끝
    if (current) blocks.get(current).push(line);
  }
  return blocks;
}

/** 잡 블록에서 표시 이름(없으면 잡 id)과 경로 조건을 뽑는다. */
export function jobFacts(jobId, blockLines) {
  const body = blockLines.join('\n');
  const name = body.match(/^ {4}name:\s*(.+?)\s*$/m)?.[1] ?? jobId;
  const cond = body.match(/^ {4}if:\s*(.+?)\s*$/m)?.[1] ?? '';
  let scope = 'always';
  if (/needs\.changes\.outputs\.frontend\s*==\s*'true'/.test(cond)) scope = 'frontend';
  else if (/needs\.changes\.outputs\.backend\s*==\s*'true'/.test(cond)) scope = 'backend';
  return { name, scope };
}

describe('ci-verdict 판정 규칙', () => {
  const runs = (entries) => entries.map(([name, status, conclusion]) => ({ name, status, conclusion }));

  test('cancelled·skipped 는 판정이 아니다', () => {
    assert.equal(verdictFor(runs([['guard', 'completed', 'cancelled']]), 'guard'), null);
    assert.equal(verdictFor(runs([['guard', 'completed', 'skipped']]), 'guard'), null);
    assert.equal(verdictFor(runs([['guard', 'completed', 'success']]), 'guard'), 'success');
  });

  test('같은 커밋에 push 실행과 PR 실행이 겹치면 실패가 이긴다', () => {
    // PR 실행은 base=main 대비 전량 검증이라 push 실행보다 더 많이 본다 — 더 본 쪽이 실패했으면 실패다.
    const both = runs([['Frontend - Tests', 'completed', 'success'], ['Frontend - Tests', 'completed', 'failure']]);
    assert.equal(verdictFor(both, 'Frontend - Tests'), 'failure');
  });

  test('skipped 와 success 가 겹치면 success 가 판정이다', () => {
    const both = runs([['Frontend - Tests', 'completed', 'skipped'], ['Frontend - Tests', 'completed', 'success']]);
    assert.equal(verdictFor(both, 'Frontend - Tests'), 'success');
  });

  test('실행 중은 체크런으로도 워크플로 실행으로도 감지한다', () => {
    assert.equal(hasPending(runs([['guard', 'in_progress', null]]), 'guard'), true);
    assert.equal(hasPending(runs([['guard', 'completed', 'cancelled']]), 'guard'), false);
    // 실행이 queued 인 동안에는 잡의 체크런이 아직 만들어지지 않는다 — 워크플로 상태로 본다.
    const wf = [{ path: '.github/workflows/ci.yml', status: 'queued', conclusion: null }];
    assert.equal(workflowPending(wf, 'ci.yml'), true);
    assert.equal(workflowPending(wf, 'semgrep.yml'), false);
  });

  test('경로 범위 판정', () => {
    assert.equal(touchesScope(['frontend/src/App.tsx'], 'frontend'), true);
    assert.equal(touchesScope(['frontend/src/App.tsx'], 'backend'), false);
    assert.equal(touchesScope(['order-service/build.gradle.kts'], 'backend'), true);
    assert.equal(touchesScope([], 'always'), true);
  });
});

describe('ci-verdict 판정 조립', () => {
  const check = { name: 'Frontend - Tests', scope: 'frontend', workflow: 'ci.yml' };
  const build = ({ runsBySha = {}, filesBySha = {}, descendants = [], ancestors = [] }) => assembleVerdict(check, {
    target: 'T',
    descendants,
    ancestors,
    checkRuns: (sha) => (runsBySha[sha] ?? []).map(([name, status, conclusion]) => ({ name, status, conclusion })),
    changedFiles: (sha) => filesBySha[sha] ?? [],
    workflowRuns: () => [],
  });

  test('대상 커밋에서 통과하면 PASS(target)', () => {
    const r = build({ runsBySha: { T: [['Frontend - Tests', 'completed', 'success']] } });
    assert.deepEqual(r, { state: 'PASS', at: 'T', where: 'target' });
  });

  test('취소된 대상 + 통과한 후손이면 PASS(descendant)', () => {
    // 2026-08-19 실측 형태: 1d17aaa7d 의 실행이 취소되고 후손 커밋의 실행이 판정을 냈다.
    const r = build({
      descendants: ['D1', 'D2'],
      runsBySha: { T: [['Frontend - Tests', 'completed', 'cancelled']], D2: [['Frontend - Tests', 'completed', 'success']] },
    });
    assert.deepEqual(r, { state: 'PASS', at: 'D2', where: 'descendant' });
  });

  test('앞뒤로 판정이 없고 경로도 안 바뀌었으면 조상 판정이 유효(COVERED)', () => {
    const r = build({
      ancestors: ['A1'],
      filesBySha: { T: ['order-service/x.java'] },
      runsBySha: { T: [['Frontend - Tests', 'completed', 'skipped']], A1: [['Frontend - Tests', 'completed', 'success']] },
    });
    assert.deepEqual(r, { state: 'COVERED', at: 'A1', where: 'ancestor' });
  });

  test('대상이 그 경로를 바꿨으면 조상 판정으로 덮지 않는다 — UNJUDGED', () => {
    // 이 한 줄이 이 도구의 존재 이유다: 프론트를 바꾼 커밋의 실행이 취소되고,
    // 뒤 커밋들이 프론트를 안 건드려 skipped 로 넘어가면 그 변경은 영영 테스트되지 않는다.
    const r = build({
      ancestors: ['A1'],
      filesBySha: { T: ['frontend/src/App.tsx'] },
      runsBySha: { T: [['Frontend - Tests', 'completed', 'cancelled']], A1: [['Frontend - Tests', 'completed', 'success']] },
    });
    assert.equal(r.state, 'UNJUDGED');
  });

  test('중간 후손에서 깨졌다 뒤 후손에서 복구됐으면 PASS + 깨진 지점을 함께 남긴다', () => {
    // 가까운 결론 하나로 끊으면 남의 커밋이 낸 실패가 대상에 영구히 눌어붙는다
    // (2026-08-20 자기 실증: guard 가 70e4c9be3 에서 FAIL → 44a8a5b8d 에서 복구됐는데도
    //  4aeb4bf4b 가 계속 FAIL 로 보고됐다). 가장 나중 판정이 그 내용의 현재 진실이다.
    const r = build({
      descendants: ['D1', 'D2'],
      runsBySha: {
        D1: [['Frontend - Tests', 'completed', 'failure']],
        D2: [['Frontend - Tests', 'completed', 'success']],
      },
    });
    assert.equal(r.state, 'PASS');
    assert.equal(r.at, 'D2');
    assert.equal(r.brokeAt, 'D1');
  });

  test('대상 자신이 실패했으면 뒤에서 고쳐져도 그 커밋의 사실은 FAIL — 복구 지점을 함께 남긴다', () => {
    // bisect·롤백은 "그 커밋이 성했는가" 를 묻는다. 브랜치가 초록이라는 사실로 덮으면 안 된다.
    const r = build({
      descendants: ['D1'],
      runsBySha: {
        T: [['Frontend - Tests', 'completed', 'failure']],
        D1: [['Frontend - Tests', 'completed', 'success']],
      },
    });
    assert.equal(r.state, 'FAIL');
    assert.equal(r.where, 'target');
    assert.equal(r.flippedTo, 'PASS');
    assert.equal(r.flippedAt, 'D1');
  });

  test('뒤 후손에서 깨진 채 끝났으면 FAIL — 통과한 중간 후손으로 덮지 않는다', () => {
    const r = build({
      descendants: ['D1', 'D2'],
      runsBySha: {
        D1: [['Frontend - Tests', 'completed', 'success']],
        D2: [['Frontend - Tests', 'completed', 'failure']],
      },
    });
    assert.equal(r.state, 'FAIL');
    assert.equal(r.at, 'D2');
  });

  test('실행 중이면 UNJUDGED 가 아니라 PENDING', () => {
    const r = build({ runsBySha: { T: [['Frontend - Tests', 'in_progress', null]] } });
    assert.deepEqual(r, { state: 'PENDING', at: 'T', where: 'target' });
  });

  test('한 줄 출력에 상태와 근거 커밋이 함께 담긴다', () => {
    const line = formatLine(check, { state: 'PASS', at: 'abcdef1234567', where: 'descendant' });
    assert.match(line, /PASS/);
    assert.match(line, /abcdef123/);
  });

  test('뒤집힌 이력은 한 줄 출력에도 남는다', () => {
    const broke = formatLine(check, { state: 'PASS', at: 'aaaaaaaaa11', where: 'descendant', brokeAt: 'bbbbbbbbb22' });
    assert.match(broke, /bbbbbbbbb/);
    const flipped = formatLine(check, { state: 'FAIL', at: 'aaaaaaaaa11', where: 'target', flippedTo: 'PASS', flippedAt: 'ccccccccc33' });
    assert.match(flipped, /ccccccccc/);
  });
});

describe('ci-verdict 인자 파싱', () => {
  test('값 플래그의 값이 위치 인자로 새지 않는다', () => {
    assert.deepEqual(parseArgs(['--branch', 'origin/main']),
      { branch: 'origin/main', maxWalk: 25, asJson: false, ref: 'origin/main' });
    assert.equal(parseArgs(['--branch', 'origin/main', 'HEAD']).ref, 'HEAD');
    assert.equal(parseArgs(['--max-walk', '5', 'HEAD']).maxWalk, 5);
    assert.equal(parseArgs(['--max-walk', 'zero']).maxWalk, 25); // 잘못된 값은 기본값으로
    assert.equal(parseArgs(['--json']).asJson, true);
  });
});

describe('필수 체크 표가 워크플로와 일치한다', () => {
  const facts = new Map();
  for (const file of ['ci.yml', 'harness-guard.yml', 'semgrep.yml']) {
    for (const [jobId, lines] of jobBlocks(workflow(file))) {
      const { name, scope } = jobFacts(jobId, lines);
      facts.set(`${file}::${name}`, scope);
    }
  }

  for (const check of REQUIRED_CHECKS) {
    test(`${check.name} — ${check.workflow} 에 그 이름의 잡이 있고 경로 조건이 '${check.scope}'`, () => {
      const key = `${check.workflow}::${check.name}`;
      assert.ok(facts.has(key),
        `${check.workflow} 에 '${check.name}' 잡이 없다. 잡 이름을 바꿨다면 ruleset 의 필수 체크도 함께 바뀌었는지 확인하고 REQUIRED_CHECKS 를 맞출 것`);
      assert.equal(facts.get(key), check.scope,
        `'${check.name}' 의 경로 조건이 워크플로와 다르다 — 조상 판정 유효화가 틀어진다`);
    });
  }

  test('CLAUDE.md 의 필수 CI 목록과 건수가 표와 일치한다', () => {
    const claudeMd = readFileSync(join(repoRoot, 'CLAUDE.md'), 'utf8');
    const line = claudeMd.split('\n').find((l) => l.includes('필수 CI'));
    assert.ok(line, 'CLAUDE.md 에 필수 CI 목록 줄이 없다');
    assert.match(line, new RegExp(`필수 CI ${REQUIRED_CHECKS.length}종`),
      `CLAUDE.md 가 적어 둔 건수가 REQUIRED_CHECKS(${REQUIRED_CHECKS.length}) 와 다르다`);
    for (const check of REQUIRED_CHECKS) {
      assert.ok(line.includes(check.name), `CLAUDE.md 필수 CI 목록에 '${check.name}' 이 없다`);
    }
  });
});
