#!/usr/bin/env node
// CI 판정 조회 — "이 커밋은 정말 판정을 받았는가" 를 체크 단위로 되묻는다.
//
// 막는 것: **취소된 실행이 통과로 읽히는 상태**.
//
//   develop 의 ci·harness-guard 는 concurrency 로 "최신 커밋이 이긴다"(ci.yml 상단 주석).
//   그래서 연속 push 중간 커밋의 실행은 `cancelled` 로 끝난다. 여기까지는 의도된 설계다.
//   문제는 그 다음이다 — `cancelled` 는 `failure` 가 아니어서 브랜치를 훑어보면 빨간 X 가 없고,
//   `gh run list` 도 "실패 없음" 으로 보인다. 판정이 **없는** 것과 판정이 **통과** 인 것이
//   같은 색으로 보인다.
//
//   여기에 경로 필터가 겹치면 구멍이 영구화된다. `Frontend - Tests` 는
//   `if: needs.changes.outputs.frontend == 'true'` 이고, push 의 변경 감지 기준은 **직전 커밋**이다.
//   프론트를 바꾼 커밋 X 의 실행이 취소되면, 뒤따르는 커밋들은 프론트를 안 건드리는 한
//   이 잡을 `skipped` 로 넘긴다 — X 의 프론트 변경은 **영영 한 번도 테스트되지 않는다**.
//   (2026-08-19 실측: 커밋 1d17aaa7d 의 ci 실행 32292943467 이 취소된 뒤, 잡 재실행마저
//    다시 취소됐고 판정은 상시 열려 있던 릴리스 PR 실행에서 우연히 메워졌다. PR 이 닫혀 있었다면
//    그대로 미판정이었다.)
//
// 그래서 이 도구는 체크별로 **결론이 난 판정**만 판정으로 센다:
//   success / failure       → 결론
//   cancelled / skipped     → 결론 아님 (미판정)
//   queued / in_progress    → 결론 아님 (아직)
//
// 그리고 판정을 세 곳에서 찾는다(가까운 순):
//   ① 대상 커밋 자신           → PASS(target)
//   ② 브랜치 위 후손 커밋       → PASS(descendant) — 후손 트리가 대상의 변경을 품고 있으므로 유효
//   ③ 조상 커밋 + 무변경 증명   → COVERED — 그 체크의 경로가 이후 한 번도 안 바뀌었으면 판정은 유효
//   어디에도 없으면            → UNJUDGED
//
// 사용:
//   node scripts/harness/ci-verdict.mjs                 # origin/develop 끝 커밋
//   node scripts/harness/ci-verdict.mjs <sha|ref>
//   node scripts/harness/ci-verdict.mjs --branch origin/main HEAD
//   node scripts/harness/ci-verdict.mjs --json
//
// 종료코드: 0 = 필수 체크 전부 판정 통과 · 1 = 미판정/실패 있음 · 2 = 도구 오류(gh·git·네트워크).
//
// 읽기 전용이다 — 재실행은 하지 않고 필요한 `gh` 명령만 출력한다. 재실행 여부는 사람이 정한다.

import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

/**
 * main 의 ruleset 에 **필수 상태 체크**로 등록된 6종.
 *
 * `scope` 는 "이 체크가 어떤 변경에 반응하는가" 다 — ci.yml 의 `if:` 조건에서 온 값이고,
 * ci-verdict-gate.test.mjs 가 워크플로 원문과 대조해 드리프트를 막는다.
 *   always   : 경로 조건 없음 (모든 커밋에서 돈다)
 *   frontend : frontend/** 변경에만 (needs.changes.outputs.frontend == 'true')
 *   backend  : frontend/** 밖 변경에만 (needs.changes.outputs.backend == 'true')
 */
export const REQUIRED_CHECKS = [
  { name: 'Detect changed paths', scope: 'always', workflow: 'ci.yml' },
  { name: 'Backend - Build/Test/JaCoCo/SonarCloud', scope: 'backend', workflow: 'ci.yml' },
  { name: 'Frontend - Production Build & Quality', scope: 'frontend', workflow: 'ci.yml' },
  { name: 'Frontend - Tests', scope: 'frontend', workflow: 'ci.yml' },
  { name: 'guard', scope: 'always', workflow: 'harness-guard.yml' },
  { name: 'SAST (Semgrep OSS)', scope: 'always', workflow: 'semgrep.yml' },
];

/** 결론으로 인정하지 않는 conclusion — 이 목록이 이 도구의 존재 이유다. */
const INCONCLUSIVE = new Set([null, undefined, '', 'cancelled', 'skipped', 'neutral', 'stale']);

/**
 * 한 커밋의 체크런 목록에서 특정 체크의 결론을 뽑는다.
 *
 * 커밋 하나에 같은 이름의 체크런이 여러 개 달린다 — push 실행 1벌 + 릴리스 PR 실행 1벌.
 * 그래서 다수결이 아니라 **실패 우선**이다: 한쪽이 통과했어도 다른 쪽이 실패했으면 실패다
 * (PR 실행은 base=main 대비 전량 검증이라 push 실행보다 더 많이 본다 — 더 본 쪽이 실패했으면 실패).
 *
 * @returns {'success'|'failure'|null} null 이면 미판정
 */
export function verdictFor(checkRuns, checkName) {
  const mine = (checkRuns ?? []).filter((run) => run?.name === checkName);
  if (mine.some((run) => ['failure', 'timed_out', 'action_required'].includes(run.conclusion))) return 'failure';
  if (mine.some((run) => run.conclusion === 'success')) return 'success';
  return null;
}

/**
 * 아직 돌고 있는 체크런이 있는가.
 *
 * "판정이 없다" 를 전부 UNJUDGED 로 부르면 push 직후 커밋이 매번 경보를 울린다 —
 * 아직 안 끝난 것과 영영 안 끝난 것은 다르다. 둘 다 통과는 아니지만 할 일이 다르다
 * (기다린다 vs 재실행한다).
 */
const PENDING_STATUS = ['queued', 'in_progress', 'waiting', 'pending', 'requested'];

export function hasPending(checkRuns, checkName) {
  return (checkRuns ?? []).some((run) => run?.name === checkName && PENDING_STATUS.includes(run.status));
}

/**
 * 워크플로 실행이 아직 큐에 있는가.
 *
 * 체크런만 보면 부족하다 — 실행이 queued 인 동안에는 잡의 체크런이 **아직 만들어지지도 않는다**.
 * 그래서 "이름이 없다" 가 "취소돼서 없다" 와 구분되지 않는다. 소유 워크플로의 실행 상태를 같이 본다.
 */
export function workflowPending(workflowRuns, workflowFile) {
  return (workflowRuns ?? []).some((run) => String(run?.path ?? '').endsWith(`/${workflowFile}`)
    && PENDING_STATUS.includes(run.status));
}

/** 변경 파일 목록이 그 체크의 경로에 걸리는가. */
export function touchesScope(changedFiles, scope) {
  if (scope === 'always') return true;
  const files = changedFiles ?? [];
  const isFrontend = (f) => f.startsWith('frontend/');
  return scope === 'frontend' ? files.some(isFrontend) : files.some((f) => !isFrontend(f));
}

/**
 * 체크 하나의 판정을 조립한다.
 *
 * @param {object} input
 * @param {string} input.target        대상 커밋 sha
 * @param {string[]} input.descendants 대상 이후 브랜치 커밋(가까운 순)
 * @param {string[]} input.ancestors   대상 이전 커밋(가까운 순)
 * @param {(sha:string)=>object[]} input.checkRuns 커밋별 체크런
 * @param {(sha:string)=>string[]} input.changedFiles 커밋별 변경 파일
 */
export function assembleVerdict(check, { target, descendants, ancestors, checkRuns, changedFiles, workflowRuns }) {
  const forward = [target, ...descendants];

  // 앞으로 가면서 **결론이 난 판정만** 모은다. 가장 가까운 하나로 끊으면 안 된다 —
  // 중간 후손에서 한 번 깨졌다가 그 뒤 후손에서 고쳐진 이력이 대상 커밋에 영구히 눌어붙는다
  // (2026-08-20 자기 실증: guard 가 70e4c9be3 에서 FAIL → 44a8a5b8d 에서 복구됐는데도
  //  4aeb4bf4b 가 계속 FAIL 로 보고됐다).
  const chain = forward
    .map((sha) => ({ sha, verdict: verdictFor(checkRuns(sha), check.name) }))
    .filter((entry) => entry.verdict !== null);

  if (chain.length > 0) {
    // 대상 커밋 자신의 판정이 있으면 그것이 그 커밋의 사실이다(bisect·롤백이 이 값을 본다).
    // 없으면 **가장 나중** 후손의 판정이 그 내용의 현재 진실이다.
    const own = chain[0].sha === target ? chain[0] : null;
    const latest = chain[chain.length - 1];
    const head = own ?? latest;
    const state = head.verdict === 'failure' ? 'FAIL' : 'PASS';
    const result = { state, at: head.sha, where: head.sha === target ? 'target' : 'descendant' };
    // 도중에 뒤집힌 적이 있으면 감추지 않는다 — 헤드라인만 보면 사라지는 사실이다.
    if (latest.verdict !== head.verdict) {
      result.flippedTo = latest.verdict === 'failure' ? 'FAIL' : 'PASS';
      result.flippedAt = latest.sha;
    } else if (chain.some((entry) => entry.verdict !== head.verdict)) {
      const broke = chain.find((entry) => entry.verdict !== head.verdict);
      result.brokeAt = broke.sha;
    }
    return result;
  }
  const runsAt = workflowRuns ?? (() => []);
  const pendingAt = forward.find((sha) => hasPending(checkRuns(sha), check.name)
    || workflowPending(runsAt(sha), check.workflow));
  if (pendingAt) return { state: 'PENDING', at: pendingAt, where: pendingAt === target ? 'target' : 'descendant' };

  // 조상으로 되짚는다 — 단, 그 조상 이후로 이 체크의 경로가 한 번도 안 바뀌었을 때만 유효하다.
  // 대상 자신의 변경부터 센다(대상이 프론트를 바꿨다면 조상의 프론트 판정은 대상을 덮지 못한다).
  let untouched = !touchesScope(changedFiles(target), check.scope);
  for (const sha of ancestors) {
    if (!untouched) break;
    const verdict = verdictFor(checkRuns(sha), check.name);
    if (verdict === 'failure') return { state: 'FAIL', at: sha, where: 'ancestor' };
    if (verdict === 'success') return { state: 'COVERED', at: sha, where: 'ancestor' };
    untouched = !touchesScope(changedFiles(sha), check.scope);
  }
  return { state: 'UNJUDGED', at: null, where: null };
}

const SYMBOL = { PASS: '✅', COVERED: '☑️', FAIL: '❌', PENDING: '⏳', UNJUDGED: '⚠️' };

/** 사람이 읽는 한 줄. */
export function formatLine(check, result) {
  const short = result.at ? result.at.slice(0, 9) : '-';
  const where = result.state === 'PENDING'
    ? `${short} 에서 실행 중 — 아직 결론 없음`
    : {
      target: '대상 커밋에서 판정',
      descendant: `후손 ${short} 에서 판정 — 대상 변경을 품은 트리`,
      ancestor: `조상 ${short} 판정 유효 — 이후 ${check.scope} 경로 무변경`,
    }[result.where] ?? '결론 난 판정 없음 — 취소/스킵뿐';
  const note = result.flippedTo
    ? ` · 이후 ${result.flippedAt.slice(0, 9)} 에서 ${result.flippedTo} 로 바뀜`
    : result.brokeAt ? ` · 중간 ${result.brokeAt.slice(0, 9)} 에서 한 번 깨졌다 복구` : '';
  return `${SYMBOL[result.state]} ${result.state.padEnd(8)} ${check.name.padEnd(38)} ${where}${note}`;
}

/**
 * 인자 파싱 — 값을 받는 플래그(--branch/--max-walk)의 값이 위치 인자로 새지 않게 한 번에 훑는다.
 * 위치 인자는 하나(대상 ref)뿐이고, 생략하면 브랜치 끝 커밋이 대상이다.
 */
export function parseArgs(args) {
  const VALUED = new Set(['--branch', '--max-walk']);
  const opts = {};
  let ref = null;
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (VALUED.has(arg)) { opts[arg] = args[i + 1]; i += 1; continue; }
    if (arg.startsWith('--')) { opts[arg] = true; continue; }
    ref ??= arg;
  }
  const branch = opts['--branch'] ?? 'origin/develop';
  const parsedWalk = Number(opts['--max-walk']);
  return {
    branch,
    maxWalk: Number.isInteger(parsedWalk) && parsedWalk > 0 ? parsedWalk : 25,
    asJson: opts['--json'] === true,
    ref: ref ?? branch,
  };
}

export async function runVerdictCli(args, io = {}) {
  const stdout = io.stdout ?? ((m) => console.log(m));
  const stderr = io.stderr ?? ((m) => console.error(m));
  const exec = io.exec ?? ((cmd, cmdArgs) =>
    execFileSync(cmd, cmdArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim());

  const { branch, maxWalk, asJson, ref } = parseArgs(args);

  let repo;
  let target;
  let descendants;
  let ancestors;
  try {
    repo = exec('gh', ['repo', 'view', '--json', 'nameWithOwner', '--jq', '.nameWithOwner']);
    target = exec('git', ['rev-parse', ref]);
    // --ancestry-path: 브랜치에 있으나 대상의 후손이 아닌 커밋(다른 갈래)은 판정 근거가 못 된다.
    descendants = exec('git', ['rev-list', '--reverse', '--ancestry-path', `${target}..${branch}`])
      .split('\n').filter(Boolean).slice(0, maxWalk);
    ancestors = exec('git', ['rev-list', `--max-count=${maxWalk}`, `${target}^`])
      .split('\n').filter(Boolean);
  } catch (error) {
    stderr(`ci-verdict: git/gh 준비 실패 — gh 인증과 \`git fetch origin\` 을 확인하세요 (${error.message})`);
    return 2;
  }

  const runCache = new Map();
  const checkRuns = (sha) => {
    if (!runCache.has(sha)) {
      try {
        const json = exec('gh', ['api', `repos/${repo}/commits/${sha}/check-runs?per_page=100`,
          '--jq', '[.check_runs[] | {name, status, conclusion}]']);
        runCache.set(sha, JSON.parse(json));
      } catch {
        runCache.set(sha, []); // 실행이 하나도 없는 커밋(=미판정)과 같은 취급 — 조회 실패를 통과로 바꾸지 않는다
      }
    }
    return runCache.get(sha);
  };
  const wfCache = new Map();
  const workflowRuns = (sha) => {
    if (!wfCache.has(sha)) {
      try {
        const json = exec('gh', ['api', `repos/${repo}/actions/runs?head_sha=${sha}&per_page=100`,
          '--jq', '[.workflow_runs[] | {path, status, conclusion}]']);
        wfCache.set(sha, JSON.parse(json));
      } catch {
        wfCache.set(sha, []);
      }
    }
    return wfCache.get(sha);
  };
  const fileCache = new Map();
  const changedFiles = (sha) => {
    if (!fileCache.has(sha)) {
      try {
        fileCache.set(sha, exec('git', ['diff-tree', '--no-commit-id', '--name-only', '-r', sha])
          .split('\n').filter(Boolean));
      } catch {
        fileCache.set(sha, ['<unknown>']); // 알 수 없으면 "바뀌었다" 로 — 조상 판정을 함부로 유효화하지 않는다
      }
    }
    return fileCache.get(sha);
  };

  const results = REQUIRED_CHECKS.map((check) => ({
    check: check.name,
    scope: check.scope,
    ...assembleVerdict(check, { target, descendants, ancestors, checkRuns, changedFiles, workflowRuns }),
  }));
  const bad = results.filter((r) => r.state !== 'PASS' && r.state !== 'COVERED');

  if (asJson) {
    stdout(JSON.stringify({ repo, target, branch, results, ok: bad.length === 0 }, null, 2));
    return bad.length === 0 ? 0 : 1;
  }

  stdout(`ci-verdict: ${repo} @ ${target.slice(0, 9)} (${ref}) · 후손 ${descendants.length} · 조상 ${ancestors.length} 커밋 조회`);
  stdout('');
  REQUIRED_CHECKS.forEach((check, i) => stdout(`  ${formatLine(check, results[i])}`));
  stdout('');
  if (bad.length === 0) {
    stdout('필수 체크 6종 모두 결론 난 통과 판정을 가지고 있다.');
    return 0;
  }
  for (const r of bad) {
    if (r.state === 'FAIL') {
      stdout(r.flippedTo === 'PASS'
        ? `❌ ${r.check}: ${r.at.slice(0, 9)} 에서 실패했으나 ${r.flippedAt.slice(0, 9)} 에서 복구됐다 — 브랜치는 초록, 이 커밋 자체는 깨져 있다(bisect·롤백 주의).`
        : `❌ ${r.check}: ${r.at.slice(0, 9)} 에서 실패 — 재실행이 아니라 고쳐야 한다.`);
    }
    else if (r.state === 'PENDING') stdout(`⏳ ${r.check}: 아직 돌고 있다 — 기다렸다 다시 조회할 것.`);
    else stdout(`⚠️  ${r.check}: 판정이 없다. 취소된 실행은 통과가 아니다.`);
  }
  if (bad.some((r) => r.state === 'UNJUDGED')) {
    stdout('');
    stdout('재판정 — 잡 단위 재실행은 경로 필터·concurrency 에 다시 걸린다. 실행 전체를 다시 돌릴 것:');
    stdout(`  gh run list --commit ${target} --json databaseId,name,conclusion`);
    stdout('  gh run rerun <databaseId>            # 같은 event 페이로드라 경로 필터 결과가 재현된다');
  }
  return 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runVerdictCli(process.argv.slice(2));
}
