// 변경 감지 기준점 게이트 — ci.yml 의 "Resolve paths-filter base" 를 실제로 실행해 본다.
//
// 막는 것: 연달아 push 했을 때 **프론트 잡이 통째로 skip 되는** 상태.
//
// 결함의 모양: 기준점이 `github.event.before`(직전 push 의 head)였다. 그 값이 옳으려면 모든
// push 가 자기 런을 완주해야 하는데, main 은 concurrency 대기 슬롯이 그룹당 1개뿐이라 연달아
// push 하면 가운데 런이 pending 에서 취소된다. 취소된 런이 담당하던 구간은 어느 런도 보지 않는다:
//   push A(프론트) 실행 중 → push B 대기 → push C 도착 → B 취소, C 의 base = B 의 head.
// A..B 의 프론트 변경은 C 의 diff 에 없고, dorny 필터는 정상 동작한 채로 frontend=false 를 낸다.
// 경로 필터가 틀린 게 아니라 **입력이 "검증됐다고 가정한 커밋"** 이었던 것이 문제다.
//
// 그래서 기준은 "마지막으로 green 이었던 커밋"이어야 한다. 그러면 취소·실패한 구간이 다음 런의
// diff 에 계속 남는다(누적). 이 게이트는 그 성질을 문자열이 아니라 **동작**으로 확인한다 —
// gh 대역과 진짜 git 저장소를 만들어 스크립트를 그대로 돌린다.
//
// `CI_YML_PATH` 로 다른 ci.yml 을 물릴 수 있다. 수정 전 원문을 물리면 첫 케이스가 실패해야
// 한다 — 통과만 확인한 게이트는 통과를 증명하지 않는다(ci-artifact-layout-gate 와 같은 관례).
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { extractRunScript } from './ci-artifact-layout-gate.test.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CI_YML = process.env.CI_YML_PATH || join(REPO_ROOT, '.github', 'workflows', 'ci.yml');
const STEP = 'Resolve paths-filter base';

/** GitHub 표현식을 테스트 값으로 치환한다 — 러너가 하는 일을 그대로 흉내낸다. */
function materialize(script, { eventName = 'push', repository = 'owner/repo' } = {}) {
  return script
    .replaceAll('${{ github.event_name }}', eventName)
    .replaceAll('${{ github.repository }}', repository);
}

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

/**
 * 커밋 3개짜리 저장소를 하나 만들고, 그 안에서 스크립트를 여러 번 돌린다.
 *
 * 저장소를 케이스마다 새로 만들면 안 된다 — SHA 가 달라져 "직전 커밋" 폴백이 항상
 * "없는 커밋"으로 떨어지고, 그러면 폴백 케이스가 통과하는 것처럼 보이지 않는다.
 * run 의 ghSha 가 null 이면 gh 가 실패하는 상황(권한 없음·API 오류)을 흉내낸다.
 */
function withRepo(script, body) {
  const dir = mkdtempSync(join(tmpdir(), 'ci-changed-base-'));
  try {
    git(dir, 'init', '-q', '-b', 'main');
    git(dir, 'config', 'user.email', 'gate@example.com');
    git(dir, 'config', 'user.name', 'gate');
    const shas = [];
    for (const n of ['a', 'b', 'c']) {
      writeFileSync(join(dir, `${n}.txt`), n);
      git(dir, 'add', '-A');
      git(dir, 'commit', '-q', '-m', n);
      shas.push(git(dir, 'rev-parse', 'HEAD'));
    }

    const binDir = join(dir, '.bin');
    mkdirSync(binDir, { recursive: true });
    const outFile = join(dir, 'gh-output');

    // gh 대역: 인자를 보지 않고 정해진 답만 낸다. 스크립트가 gh 를 부르는지, 그 결과를
    // 기준점으로 삼는지만 보면 되기 때문이다.
    const run = ({ ghSha, before, eventName = 'push' }) => {
      writeFileSync(
        join(binDir, 'gh'),
        ghSha === null
          ? '#!/usr/bin/env bash\necho "gh: HTTP 403" >&2\nexit 1\n'
          : `#!/usr/bin/env bash\nprintf '%s\\n' '${ghSha ?? 'null'}'\n`,
      );
      chmodSync(join(binDir, 'gh'), 0o755);
      writeFileSync(outFile, '');
      const stdout = execFileSync('bash', ['-c', materialize(script, { eventName })], {
        cwd: dir,
        encoding: 'utf8',
        env: {
          ...process.env,
          PATH: `${binDir}:${process.env.PATH ?? ''}`,
          GITHUB_OUTPUT: outFile,
          BEFORE: before ?? '',
          BRANCH: 'main',
          GH_TOKEN: 'test-token',
        },
      });
      const base = (readFileSync(outFile, 'utf8').match(/^base=(.*)$/m) ?? [, null])[1];
      return { base, stdout };
    };

    return body({ run, shas });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

describe('변경 감지 기준점 게이트 (연달아 push 하면 프론트 잡이 사라진다)', () => {
  const script = extractRunScript(readFileSync(CI_YML, 'utf8'), STEP);

  test('스텝이 존재하고 run 블록을 꺼낼 수 있다', () => {
    assert.ok(script, `${STEP} 스텝의 run 블록을 찾지 못했다 — 스텝 이름이 바뀌었나?`);
  });

  test('마지막 green 커밋을 기준으로 쓴다 — before 가 있어도 green 이 이긴다', () => {
    // green=첫 커밋, before=둘째 커밋. before 를 쓰면 첫~둘째 구간(취소된 런의 몫)이 사라진다.
    withRepo(script, ({ run, shas }) => {
      const [first, second] = shas;
      const result = run({ ghSha: first, before: second });
      assert.equal(
        result.base,
        first,
        'base 가 마지막 green 이 아니다 — 취소된 런의 구간이 diff 에서 빠진다',
      );
      assert.match(result.stdout, /누적/);
    });
  });

  test('마지막 green 을 못 읽으면 직전 커밋으로 폴백한다', () => {
    withRepo(script, ({ run, shas }) => {
      assert.equal(run({ ghSha: null, before: shas[1] }).base, shas[1]);
    });
  });

  test('green 이 null 로 와도(런 이력 없음) 직전 커밋으로 폴백한다', () => {
    withRepo(script, ({ run, shas }) => {
      assert.equal(run({ ghSha: undefined, before: shas[1] }).base, shas[1]);
    });
  });

  test('green 도 before 도 없으면 base 를 비워 전량 검증으로 떨어진다', () => {
    withRepo(script, ({ run }) => {
      assert.equal(
        run({ ghSha: null, before: '0'.repeat(40) }).base,
        '',
        '판단이 갈릴 때는 더 도는 쪽이 옳다',
      );
    });
  });

  test('저장소에 없는 green 커밋(force-push 후)은 무시하고 폴백한다', () => {
    withRepo(script, ({ run, shas }) => {
      assert.equal(run({ ghSha: 'f'.repeat(40), before: shas[1] }).base, shas[1]);
    });
  });

  test('pull_request 는 base 를 비운다 — 머지 전 전량 검증은 그대로', () => {
    withRepo(script, ({ run, shas }) => {
      assert.equal(run({ ghSha: shas[0], before: shas[1], eventName: 'pull_request' }).base, '');
    });
  });

  test('changes 잡이 actions:read 를 갖는다 — 없으면 gh 가 조용히 실패해 폴백으로 내려앉는다', () => {
    const yaml = readFileSync(CI_YML, 'utf8');
    const changesJob = yaml.slice(yaml.indexOf('  changes:'), yaml.indexOf('    steps:'));
    assert.match(changesJob, /actions:\s*read/);
  });
});
