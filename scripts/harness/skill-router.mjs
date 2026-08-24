#!/usr/bin/env node
// Lemuel skill router — Claude Code PreToolUse hook (matcher: Write|Edit|MultiEdit|Skill).
//
// CLAUDE.md / HARNESS.md 라우팅 표("X-service 를 만지면 해당 *-rules 스킬 로드")는 지금까지
// LLM 이 기억해서 지키는 문서 규율이었다. 이 훅은 그 권장을 기계화한다: 편집 대상 경로를 보고
// 해당 규칙 스킬 로드를 additionalContext 리마인더로 주입한다(세션당 스킬별 1회 — 스팸 방지).
// guard.mjs 가 "금지의 기계화"라면 이 훅은 "권장의 기계화"다.
//
// 부수 기능: Skill 도구 호출을 텔레메트리로 적재해(skill-usage.jsonl) 스킬 사용률을 측정한다 —
// 제안됐지만 로드되지 않는 스킬 / 한 번도 안 불리는 스킬을 telemetry-report.mjs 가 드러낸다.
//
// Invariant: 이 훅은 절대 도구 호출을 차단하지 않는다 — 어떤 실패도 exit 0.

import { mkdir, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { appendJsonl } from "./telemetry.mjs";

const SOURCE = /\.(java|kt|sql)$/i;

// [pattern, skills, anyFile?] — anyFile 은 java/kt/sql 외 파일(계약 JSON 등)에도 적용.
export const ROUTES = [
  [/order-service\//, ["order-commerce-rules"]],
  // board·education 은 operation 의 슬라이스다. 관제 규칙(operation-signal-rules)은 게시판·교육
  // 파일과 무관하므로 슬라이스 경로를 제외한다. education 은 전용 규칙 스킬이 없어 제외만 하고
  // 라우팅하지 않는다(HARNESS.md 커버리지 현황).
  [/operation-service\/(?!.*\/(board|education)\/)/, ["operation-signal-rules"]],
  [/operation-service\/.*\/board\//, ["board-domain-rules"]],
  [/\/organization\//, ["organization-domain-rules"]], // order-service 의 organization 슬라이스 (ADR 0042)
  [
    /(\/outbox\/|adapter\/in\/kafka\/|adapter\/out\/event\/)/i,
    ["idempotency-and-events"],
  ],
  [/settlement-service\/.*(readmodel|projection)/i, ["projection-view-ops"]],
  [/contracts\/events\//, ["event-contract-change"], true],
  // hookify 캡처 규칙 파일 → 정본(guard.mjs) 이식 절차 리마인더. 규칙 정본 이원화 방지.
  [/\.claude\/hookify\..+\.local\.md$/i, ["hookify-to-guard"], true],
  // 절차 규율(플러그인 독립): 세션 첫 소스 편집에 1회 — 테스트 우선 절차 리마인더. 마지막 순위(cap 3에서 도메인 규칙 우선).
  [/\/src\/(main|test)\//, ["tdd-discipline"]],
];

export function routeSkills(filePath) {
  const normalized = String(filePath ?? "").replaceAll("\\", "/");
  const isSource = SOURCE.test(normalized);
  const skills = [];
  for (const [pattern, names, anyFile] of ROUTES) {
    if (!anyFile && !isSource) continue;
    if (!pattern.test(normalized)) continue;
    for (const name of names) if (!skills.includes(name)) skills.push(name);
  }
  return skills.slice(0, 3);
}

function stateFilePath(repoRoot, sessionId) {
  const id =
    String(sessionId ?? "")
      .replace(/[^a-zA-Z0-9-]/g, "")
      .slice(0, 64) || "global";
  return resolve(
    repoRoot,
    ".claude",
    "harness",
    "state",
    `skill-router-${id}.json`,
  );
}

async function readSuggested(statePath) {
  try {
    const parsed = JSON.parse(await readFile(statePath, "utf8"));
    return Array.isArray(parsed?.suggested)
      ? parsed.suggested.filter((s) => typeof s === "string")
      : [];
  } catch {
    return [];
  }
}

async function writeSuggested(statePath, suggested) {
  try {
    await mkdir(dirname(statePath), { recursive: true });
    await writeFile(statePath, JSON.stringify({ suggested }), "utf8");
  } catch {
    /* state loss only degrades dedupe, never the hook */
  }
}

// 세션 상태 GC — 세션당 상태 파일 1개가 영구 누적되던 문제(실측 ~70개, 정리 정책 부재)의 대응.
// 세션은 며칠이면 끝나므로 mtime 기준 보존기간을 넘긴 파일은 재제안 dedupe 가치가 없다.
// 정리 실패는 훅 결과에 영향을 주지 않는다(라우터 불변식: 어떤 실패도 exit 0).
export const STATE_RETENTION_DAYS = 14;

export async function pruneStaleState(
  repoRoot,
  { now = new Date(), maxAgeDays = STATE_RETENTION_DAYS } = {},
) {
  const stateDir = resolve(repoRoot, ".claude", "harness", "state");
  const cutoff = now.getTime() - maxAgeDays * 86_400_000;
  let pruned = 0;
  try {
    for (const name of await readdir(stateDir)) {
      if (!/^skill-router-[\w-]+\.json$/.test(name)) continue;
      const filePath = join(stateDir, name);
      try {
        if ((await stat(filePath)).mtimeMs < cutoff) {
          await unlink(filePath);
          pruned += 1;
        }
      } catch {
        /* per-file race (parallel session touching its state) — skip, never fail */
      }
    }
  } catch {
    /* state dir missing or unreadable — nothing to prune */
  }
  return pruned;
}

async function readStdinUtf8() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks).toString("utf8");
}

// Returns the hook JSON output string to print, or null when silent. Never throws.
export async function decideHookOutput(
  event,
  { repoRoot, now = new Date() } = {},
) {
  try {
    const tool = String(event?.tool_name ?? event?.tool ?? "")
      .split(".")
      .at(-1);
    const ts = now.toISOString();
    if (tool === "Skill") {
      await appendJsonl(repoRoot, "skill-usage.jsonl", {
        ts,
        skill:
          typeof event.tool_input?.skill === "string"
            ? event.tool_input.skill
            : null,
        session:
          typeof event.session_id === "string"
            ? event.session_id.slice(0, 64)
            : null,
      });
      return null;
    }
    const skills = routeSkills(event?.tool_input?.file_path);
    if (skills.length === 0) return null;
    const statePath = stateFilePath(repoRoot, event?.session_id);
    const seen = await readSuggested(statePath);
    const fresh = skills.filter((skill) => !seen.includes(skill));
    if (fresh.length === 0) return null;
    await writeSuggested(statePath, [...seen, ...fresh]);
    await pruneStaleState(repoRoot, { now }); // 기회적 GC — 새 제안이 생기는 드문 시점에만 돈다

    await appendJsonl(
      repoRoot,
      "skill-suggestions.jsonl",
      fresh.map((skill) => ({ ts, skill, file: event.tool_input.file_path })),
    );
    const context = `스킬 라우터: 이 파일은 ${fresh.map((skill) => `'${skill}'`).join(", ")} 스킬의 강제 규칙 대상입니다. 이번 세션에서 아직 로드하지 않았다면 Skill 도구로 로드한 뒤 작업하세요 (HARNESS.md 라우팅 맵).`;
    return JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        additionalContext: context,
      },
    });
  } catch {
    return null;
  }
}

/**
 * 저장소 루트 — cwd 가 아니라 이 스크립트의 위치에서 도출한다(guard.mjs 와 같은 규약).
 *
 * 훅은 셸의 cwd 를 물려받으므로 process.cwd() 를 믿으면 라우터 상태·제안 로그가
 * `<하위 디렉터리>/.claude/harness/` 라는 평행 트리에 쌓인다. 실제로 9개 위치에 33개 파일이
 * 그렇게 생겼고, 그중 account/loan 의 src/main/resources 아래 것들은 **jar 에까지 실려 나갔다**
 * (빌드 산출물에서 실측). 라우터는 advisory 라 조용히 실패해서 아무도 눈치채지 못했다.
 */
export function defaultRepoRoot() {
  return resolve(fileURLToPath(new URL("../..", import.meta.url)));
}

export async function runRouterCli(args, io = {}) {
  if (args[0] !== "--hook" || args.length !== 1) {
    (io.stderr ?? ((m) => console.error(m)))("usage: skill-router.mjs --hook");
    return 0; // advisory hook: never block, even on misuse
  }
  try {
    const event = JSON.parse(io.stdin ?? (await readStdinUtf8()));
    const output = await decideHookOutput(event, {
      repoRoot: io.repoRoot ?? defaultRepoRoot(),
      now: io.now,
    });
    if (output) (io.stdout ?? ((m) => console.log(m)))(output);
  } catch {
    /* malformed input → stay silent, stay green */
  }
  return 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.exit(await runRouterCli(process.argv.slice(2)));
}
