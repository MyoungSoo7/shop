#!/usr/bin/env node
// Lemuel harness telemetry report — .claude/harness/logs/*.jsonl 집계 CLI.
//
//   node scripts/harness/telemetry-report.mjs [--root <repo>] [--days N]
//
// 보여주는 것:
//   1. 가드 차단 통계 — 규칙별 발화 횟수(0회 규칙 = 죽은 규칙 후보), 모드별, 최근 일별 추이
//   2. 스킬 사용률 — Skill 도구로 실제 로드된 스킬 횟수
//   3. 라우터 제안 대비 미로드 스킬 — 제안은 됐지만 한 번도 로드 안 된 스킬(권장 무시 신호)
//
// 로그가 없으면 "no data" 로 정상 종료한다(설치 직후 상태). 항상 exit 0 — 리포트는 게이트가 아니다.

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { COMMAND_RULES, RULES, checkCommand, scanText } from './guard.mjs';
import { LOG_DIR_SEGMENTS } from './telemetry.mjs';

// 가드 카나리아 — 규칙별 "반드시 차단돼야 하는" 최소 위반 픽스처. 차단 0회의 모호성
// (죽은 규칙 vs 완전 예방)을 분리한다: 카나리아 PASS = 규칙 살아있음 → 0회는 예방 성공.
export const GUARD_CANARIES = {
  'MONEY-PRIMITIVE': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    line: 'double amount = 1.0;',
  },
  'MONEY-BIGDECIMAL-DOUBLE': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    line: 'BigDecimal fee = new BigDecimal(0.1);',
  },
  'IMMUTABLE-HISTORY': {
    file: 'order-service/src/main/resources/db/migration/V0__canary.sql',
    line: "UPDATE point_lots SET status = 'ACTIVE';",
  },
  'MSA-BOUNDARY': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/X.java',
    line: 'import github.lms.lemuel.order.domain.Order;',
  },
  'ACCOUNT-CONSUME-ONLY': {
    file: 'finance-service/src/main/java/github/lms/lemuel/account/application/service/X.java',
    line: 'kafkaTemplate.send(topic, payload);',
  },
  'MARKET-NO-VALUATION': {
    file: 'external-data-service/src/main/java/github/lms/lemuel/market/application/service/X.java',
    line: 'BigDecimal PER = price.divide(eps);',
  },
  'OO-DOMAIN-SETTER': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/X.java',
    line: 'public void setAmount(BigDecimal amount) {',
  },
  'OO-DOMAIN-MUTABLE-LOMBOK': {
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/X.java',
    line: '@Setter',
  },
  'OO-DOMAIN-GENERIC-IAE': {
    file: 'order-service/src/main/java/github/lms/lemuel/order/domain/X.java',
    line: 'throw new IllegalArgumentException("bad");',
  },
  // 실제 사고 형태 그대로 — script 블록 안 주석에 들어간 빈 표현식.
  'WORKFLOW-EMPTY-EXPR': {
    file: '.github/workflows/canary.yml',
    line: '            // 주석 안이라도 ${{ }} 는 워크플로를 통째로 무효화한다',
  },
  // Bash 명령 계층(COMMAND_RULES) — command 픽스처는 scanText 가 아니라 checkCommand 로 검사한다.
  'CMD-EDIT-BYPASS': { command: "sed -i 's/a/b/' settlement-service/src/main/java/X.java" },
  'CMD-NO-VERIFY': { command: 'git commit --no-verify -m x' },
  'CMD-PROD-DB-WRITE': { command: 'psql -d settlement_db -c "UPDATE settlements SET x=1"' },
  'CMD-EVENT-PRODUCE': { command: 'rpk topic produce lemuel.order.created' },
};

export function canaryResults({ rules = [...RULES, ...COMMAND_RULES], canaries = GUARD_CANARIES } = {}) {
  return rules.map((rule) => {
    const fixture = canaries[rule.id];
    if (!fixture) return { id: rule.id, status: 'undefined' };
    const violations = fixture.command != null
      ? checkCommand(fixture.command, { allowCommands: false })
      : scanText(fixture.file, `${fixture.line}\n`).violations;
    return { id: rule.id, status: violations.some((violation) => violation.id === rule.id) ? 'pass' : 'fail' };
  });
}

export function canaryReport(results = canaryResults()) {
  const lines = [`== 가드 카나리아 (규칙 생존 검사 — 차단 0회 = 완전 예방인지 판별) ==`];
  for (const { id, status } of results) {
    const mark = status === 'pass' ? 'PASS' : status === 'fail' ? 'FAIL — 규칙이 죽었다(위반 픽스처 미차단)' : 'UNDEFINED — 카나리아 픽스처 미정의';
    lines.push(`  ${mark.padEnd(4)}  ${id}`);
  }
  return lines.join('\n');
}

export function readJsonl(path) {
  if (!existsSync(path)) return [];
  return readFileSync(path, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .flatMap((line) => {
      try { return [JSON.parse(line)]; } catch { return []; }
    });
}

function countBy(records, key) {
  const counts = new Map();
  for (const record of records) {
    const value = record[key] ?? '(unknown)';
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

// 분모 리포트. 차단 건수만으로는 "안 걸렸다"와 "안 돌았다"가 같은 그림이라, 실행 횟수를
// 먼저 보여준다. 실행 0 이면 그 아래 규칙별 0회 는 아무 의미도 없다는 것을 명시한다.
export function runsReport(runs = []) {
  if (runs.length === 0) {
    return [
      '== 가드 실행 (분모) ==',
      '  기록 없음 — 가드가 한 번도 실행되지 않았거나 이 버전 이전의 로그다.',
      '  ⚠ 이 상태에서 아래 "0회"는 무위반이 아니라 미측정이다. 결론 내지 말 것.',
    ].join('\n');
  }
  const lines = [`== 가드 실행 (분모) — 총 ${runs.length}회 ==`];
  for (const [mode, count] of countBy(runs, 'mode')) {
    const scoped = runs.filter((run) => (run.mode ?? '(unknown)') === mode);
    const files = scoped.reduce((sum, run) => sum + (Number(run.files) || 0), 0);
    const blocked = scoped.filter((run) => (Number(run.violations) || 0) > 0).length;
    lines.push(`  mode ${mode}: ${count}회 실행 · 파일 ${files}건 검사 · 위반 발생 ${blocked}회`);
  }
  return lines.join('\n');
}

export function summarize({ hits, usage, suggestions, runs = [], days = 14, now = new Date() }) {
  const lines = [];
  const since = new Date(now.getTime() - days * 86_400_000).toISOString();
  const recent = hits.filter((hit) => typeof hit.ts === 'string' && hit.ts >= since);

  lines.push(runsReport(runs));
  lines.push(`== 가드 차단 (전체 ${hits.length}건, 최근 ${days}일 ${recent.length}건) ==`);
  const byRule = new Map(countBy(hits, 'id'));
  const roster = [...RULES, ...COMMAND_RULES];
  for (const rule of roster) lines.push(`  ${String(byRule.get(rule.id) ?? 0).padStart(4)}  ${rule.id}${byRule.get(rule.id) ? '' : '   (0회 — 죽은 규칙 후보 또는 완전 예방)'}`);
  for (const [id, count] of byRule) if (!roster.some((rule) => rule.id === id)) lines.push(`  ${String(count).padStart(4)}  ${id} (규칙 로스터 외 — 과거 규칙?)`);
  for (const [mode, count] of countBy(hits, 'mode')) lines.push(`  mode ${mode}: ${count}`);
  for (const [day, count] of countBy(recent.map((hit) => ({ day: hit.ts.slice(0, 10) })), 'day').sort()) lines.push(`  ${day}: ${count}`);

  lines.push(`== 스킬 사용 (${usage.length}회 로드) ==`);
  for (const [skill, count] of countBy(usage, 'skill')) lines.push(`  ${String(count).padStart(4)}  ${skill}`);

  const used = new Set(usage.map((record) => record.skill));
  const consumed = suggestions.filter((record) => used.has(record.skill)).length;
  const ignored = countBy(suggestions.filter((record) => !used.has(record.skill)), 'skill');
  lines.push(`== 라우터 제안 (${suggestions.length}건) — 제안됐지만 미로드 스킬 ==`);
  if (suggestions.length > 0) {
    lines.push(`  순응률(제안→로드) ${Math.round((consumed / suggestions.length) * 100)}% (${consumed}/${suggestions.length}) — 목표 ≥80%`);
  }
  for (const [skill, count] of ignored) lines.push(`  ${String(count).padStart(4)}  ${skill}`);
  if (ignored.length === 0) lines.push('  (없음 — 제안이 모두 소비됨)');
  return lines.join('\n');
}

// SessionStart 훅용 압축 요약 — 사람이 리포트를 돌리지 않아도 세션마다 관측이 도달하게 한다
// (닫힌 피드백 루프). 알릴 것이 없으면 null(침묵) — 스팸 방지.
export function hookSummary({ hits, usage, suggestions, canaries, days = 14, now = new Date() }) {
  const lines = [];
  const since = new Date(now.getTime() - days * 86_400_000).toISOString();
  const recent = hits.filter((hit) => typeof hit.ts === 'string' && hit.ts >= since);
  if (recent.length > 0) {
    const counts = new Map();
    for (const hit of recent) counts.set(hit.id, (counts.get(hit.id) ?? 0) + 1);
    const [topId, topCount] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0];
    lines.push(`가드 차단 최근 ${days}일 ${recent.length}건(최다 ${topId} ${topCount}회)`);
  }
  if (suggestions.length > 0) {
    const used = new Set(usage.map((record) => record.skill));
    const consumed = suggestions.filter((record) => used.has(record.skill)).length;
    lines.push(`라우터 순응률(제안→로드) ${Math.round((consumed / suggestions.length) * 100)}% (${consumed}/${suggestions.length}, 목표 ≥80%)`);
  }
  const failed = canaries.filter(({ status }) => status !== 'pass').map(({ id }) => id);
  if (failed.length > 0) lines.push(`⚠ 카나리아 FAIL(규칙 사망 의심): ${failed.join(', ')}`);
  if (lines.length === 0) return null;
  return `하네스 텔레메트리 요약: ${lines.join(' · ')} — 상세: node scripts/harness/telemetry-report.mjs`;
}

// CI 러너의 텔레메트리는 아티팩트(harness-telemetry-*)로만 남고 로컬 리포트는 로컬 로그만
// 봤다 — 관측이 머신 경계에서 단절되는 문제의 대응. --merge <dir> 로 내려받은 아티팩트
// 디렉토리(런별 서브디렉토리 포함, 재귀)를 로컬 집계에 합산한다. 수집은 telemetry-ci-pull.mjs.
const MERGE_LOG_NAMES = ['guard-hits.jsonl', 'skill-usage.jsonl', 'skill-suggestions.jsonl', 'guard-runs.jsonl'];

export function collectMergedLogs(dirs) {
  const merged = { 'guard-hits.jsonl': [], 'skill-usage.jsonl': [], 'skill-suggestions.jsonl': [], 'guard-runs.jsonl': [], files: 0 };
  const walk = (dir) => {
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      const path = resolve(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (MERGE_LOG_NAMES.includes(entry.name)) {
        merged[entry.name].push(...readJsonl(path));
        merged.files += 1;
      }
    }
  };
  for (const dir of dirs) walk(dir);
  return merged;
}

export async function runReportCli(args, io = {}) {
  const stdout = io.stdout ?? ((text) => console.log(text));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  const root = resolve(value('--root', io.repoRoot ?? process.cwd()));
  const days = Number(value('--days', '14'));
  const logDir = resolve(root, ...LOG_DIR_SEGMENTS);
  const hits = readJsonl(resolve(logDir, 'guard-hits.jsonl'));
  const usage = readJsonl(resolve(logDir, 'skill-usage.jsonl'));
  const suggestions = readJsonl(resolve(logDir, 'skill-suggestions.jsonl'));
  const runs = readJsonl(resolve(logDir, 'guard-runs.jsonl'));
  const mergeDirs = args.flatMap((arg, index) => (arg === '--merge' && args[index + 1] ? [resolve(root, args[index + 1])] : []));
  let mergedFiles = 0;
  if (mergeDirs.length > 0 && !args.includes('--hook')) {
    const merged = collectMergedLogs(mergeDirs);
    hits.push(...merged['guard-hits.jsonl']);
    usage.push(...merged['skill-usage.jsonl']);
    suggestions.push(...merged['skill-suggestions.jsonl']);
    runs.push(...merged['guard-runs.jsonl']);
    mergedFiles = merged.files;
  }
  if (args.includes('--hook')) {
    // 관측은 세션을 절대 깨뜨리지 않는다 — 어떤 실패도 침묵 + exit 0.
    try {
      const message = hookSummary({
        hits, usage, suggestions,
        canaries: canaryResults(),
        days: Number.isFinite(days) && days > 0 ? days : 14,
        now: io.now ?? new Date(),
      });
      if (message) stdout(JSON.stringify({ hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: message } }));
    } catch { /* silent */ }
    return 0;
  }
  if (hits.length + usage.length + suggestions.length + runs.length === 0) {
    stdout(`harness telemetry: no data yet (${logDir})`);
    stdout(runsReport(runs));
    stdout(canaryReport());
    return 0;
  }
  if (mergeDirs.length > 0) stdout(`== CI 병합 == ${mergeDirs.length}개 디렉토리에서 로그 파일 ${mergedFiles}건 합산 (수집: node scripts/harness/telemetry-ci-pull.mjs)`);
  stdout(summarize({ hits, usage, suggestions, runs, days: Number.isFinite(days) && days > 0 ? days : 14, now: io.now ?? new Date() }));
  stdout(canaryReport());
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runReportCli(process.argv.slice(2));
}
