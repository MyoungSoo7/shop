#!/usr/bin/env node
// 낡은 게이트 리포트 인용 차단 — "가짜 GREEN 4경로" 중 'UP-TO-DATE 낡은 XML' 축의 기계화.
//
// 실패 모드: 에이전트가 "테스트/커버리지 통과"의 증거로 build/ 아래 XML 을 인용하는데, 그
// XML 이 지금의 소스보다 오래됐다면(직전 빌드 산출물·중단된 배치의 혼합) 그 결과는 이번
// 변경에 대한 판정이 아니다. 지금까지는 "인용 전 mtime 확인" 이 운용 지식(메모리)이었다 —
// 이 스크립트가 그 확인을 종료 코드로 바꾼다.
//
// 사용:
//   node scripts/harness/report-freshness.mjs <module> [module...]
//   exit 0 = 전 모듈 fresh · 1 = stale/missing 존재 · 2 = usage
//
// 판정:
//   fresh   — 가장 새 리포트 mtime ≥ 가장 새 소스 mtime (이번 소스 상태를 본 결과)
//   stale   — 리포트가 소스보다 오래됨 → 재실행 없이 인용 금지
//   missing — 리포트 자체가 없음 → "통과" 주장 불가(미실행)
//
// 한계(정직 명세): mtime 은 "실행됐다"의 근사다. 소스 무변경 재실행(UP-TO-DATE)은 mtime 이
// 안 바뀌어도 유효하므로 fresh 판정은 소스 mtime 과의 대소로만 한다. 반대로 소스를 고친 뒤
// 빌드를 안 돌리면 반드시 stale 로 떨어진다 — 이 게이트가 막는 것은 정확히 그 인용이다.

import { readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const REPORT_ROOTS = [
  ['build', 'test-results', 'test'], // Gradle 테스트 결과 XML
  ['build', 'reports', 'jacoco'], // JaCoCo XML/HTML — 커버리지 게이트 인용 근거
];

function walkNewestMs(dir, filter = () => true) {
  let newest = null;
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return null; // 디렉토리 없음 = 산출물 없음
  }
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      const child = walkNewestMs(path, filter);
      if (child != null && (newest == null || child > newest)) newest = child;
    } else if (entry.isFile() && filter(entry.name)) {
      try {
        const ms = statSync(path).mtimeMs;
        if (newest == null || ms > newest) newest = ms;
      } catch {
        /* 파일 경합 — 그 파일만 건너뜀 */
      }
    }
  }
  return newest;
}

export function assessModule(repoRoot, module) {
  const sourceMs = walkNewestMs(resolve(repoRoot, module, 'src'));
  if (sourceMs == null) return { module, status: 'no-sources', sourceMs: null, reportMs: null };
  let reportMs = null;
  for (const segments of REPORT_ROOTS) {
    const ms = walkNewestMs(resolve(repoRoot, module, ...segments), (name) => /\.(xml|html)$/i.test(name));
    if (ms != null && (reportMs == null || ms > reportMs)) reportMs = ms;
  }
  if (reportMs == null) return { module, status: 'missing', sourceMs, reportMs: null };
  return { module, status: reportMs >= sourceMs ? 'fresh' : 'stale', sourceMs, reportMs };
}

const iso = (ms) => (ms == null ? '(없음)' : new Date(ms).toISOString());

export function formatAssessment({ module, status, sourceMs, reportMs }) {
  const verdicts = {
    fresh: 'FRESH — 인용 가능(리포트가 현재 소스 이후)',
    stale: 'STALE — 인용 금지: 소스가 리포트보다 새로움 → 게이트 재실행이 먼저다',
    missing: 'MISSING — 리포트 없음(미실행): "통과" 주장 불가',
    'no-sources': 'NO-SOURCES — <module>/src 가 없음: 모듈명을 확인하라',
  };
  return `${module}: ${verdicts[status]}\n  source ${iso(sourceMs)} · report ${iso(reportMs)}`;
}

export async function runFreshnessCli(args, io = {}) {
  const stdout = io.stdout ?? ((m) => console.log(m));
  const stderr = io.stderr ?? ((m) => console.error(m));
  const modules = args.filter((a) => !a.startsWith('--'));
  if (modules.length === 0) {
    stderr('usage: report-freshness <module> [module...]   (예: settlement-service)');
    return 2;
  }
  const repoRoot = io.repoRoot ?? process.cwd();
  let dirty = false;
  for (const module of modules) {
    const assessment = assessModule(repoRoot, module);
    stdout(formatAssessment(assessment));
    if (assessment.status !== 'fresh') dirty = true;
  }
  return dirty ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runFreshnessCli(process.argv.slice(2));
}
