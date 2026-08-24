import assert from "node:assert/strict";
import { afterEach, describe, test } from "node:test";
import { mkdtemp, mkdir, readFile, rm, utimes, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  decideHookOutput,
  defaultRepoRoot,
  pruneStaleState,
  routeSkills,
  runRouterCli,
} from "../skill-router.mjs";
import {
  appendJsonl,
  guardHitRecords,
  LOG_DIR_SEGMENTS,
} from "../telemetry.mjs";
import { runGuardCli } from "../guard.mjs";
import { readJsonl, summarize } from "../telemetry-report.mjs";

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), "skill-router-test-"));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("routeSkills", () => {
  test("service sources route to their *-rules skill plus tdd-discipline last", () => {
    assert.deepEqual(
      routeSkills(
        "order-service/src/main/java/github/lms/lemuel/order/domain/Order.java",
      ),
      ["order-commerce-rules", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "operation-service/src/main/java/github/lms/lemuel/operation/signal/domain/MetricBucket.java",
      ),
      ["operation-signal-rules", "tdd-discipline"],
    );
  });

  test("board 슬라이스는 관제 규칙이 아니라 자기 규칙으로 간다", () => {
    // 슬라이스를 제외하지 않으면 게시판 파일마다 operation-signal-rules 가 따라붙어
    // 라우터의 3개 상한에서 더 구체적인 규칙을 밀어낸다 — 흡수가 규율을 헐겁게 만드는 경로다.
    assert.deepEqual(
      routeSkills(
        "operation-service/src/main/java/github/lms/lemuel/operation/board/domain/BoardDefinition.java",
      ),
      ["board-domain-rules", "tdd-discipline"],
    );
    // education 은 전용 규칙 스킬이 없다 — 제외만 하고 라우팅하지 않는다(절차 스킬만 남는다).
    assert.deepEqual(
      routeSkills(
        "operation-service/src/main/java/github/lms/lemuel/operation/education/domain/Course.java",
      ),
      ["tdd-discipline"],
    );
  });

  test("organization sources route to organization-domain-rules", () => {
    // organization 은 order-service 안의 슬라이스라 커머스 규칙도 함께 붙는다 —
    // 순서가 중요하다: 넓은 규칙(order-commerce)이 먼저, 좁은 규칙(organization)이 뒤다.
    assert.deepEqual(
      routeSkills(
        "order-service/src/main/java/github/lms/lemuel/organization/domain/Membership.java",
      ),
      ["order-commerce-rules", "organization-domain-rules", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "order-service/src/main/java/github/lms/lemuel/organization/adapter/out/event/OrganizationEventPublisherAdapter.java",
      ),
      ["order-commerce-rules", "organization-domain-rules", "idempotency-and-events"],
    );
  });

  test("outbox publisher keeps the domain rules ahead of the event procedure", () => {
    assert.deepEqual(
      routeSkills(
        "order-service/src/main/java/github/lms/lemuel/point/adapter/out/event/PointEventPublisher.java",
      ),
      ["order-commerce-rules", "idempotency-and-events", "tdd-discipline"],
    );
  });
  test("kafka consumer path adds idempotency-and-events on top of the service rules", () => {
    assert.deepEqual(
      routeSkills(
        "operation-service/src/main/java/github/lms/lemuel/operation/signal/adapter/in/kafka/DomainEventSignalConsumer.java",
      ),
      ["operation-signal-rules", "idempotency-and-events", "tdd-discipline"],
    );
  });
  test("test sources get the tdd-discipline procedure reminder", () => {
    assert.deepEqual(
      routeSkills(
        "order-service/src/test/java/github/lms/lemuel/payment/application/service/RefundServiceTest.java",
      ),
      ["order-commerce-rules", "tdd-discipline"],
    );
  });
  test("suggestions are capped at 3 even when more routes match", () => {
    const skills = routeSkills(
      "order-service/src/main/java/github/lms/lemuel/organization/adapter/in/kafka/SomeConsumer.java",
    );
    assert.equal(skills.length, 3);
    assert.deepEqual(skills, [
      "order-commerce-rules",
      "organization-domain-rules",
      "idempotency-and-events",
    ]);
  });
  test("event contract fixtures route regardless of extension", () => {
    assert.deepEqual(
      routeSkills(
        "shared-common/src/testFixtures/resources/contracts/events/order.created.schema.json",
      ),
      ["event-contract-change"],
    );
  });
  test("hookify capture rule files route to the guard porting workflow", () => {
    assert.deepEqual(routeSkills(".claude/hookify.block-raw-sql.local.md"), [
      "hookify-to-guard",
    ]);
    assert.deepEqual(
      routeSkills("C:\\repo\\.claude\\hookify.warn-rm.local.md"),
      ["hookify-to-guard"],
    );
  });
  test("non-source files stay silent; unmapped-service sources still get tdd-discipline", () => {
    assert.deepEqual(routeSkills("order-service/README.md"), []);
    assert.deepEqual(routeSkills("gateway-service/src/main/java/App.java"), [
      "tdd-discipline",
    ]);
    assert.deepEqual(routeSkills(undefined), []);
  });
});

describe("decideHookOutput", () => {
  const writeEvent = (sessionId) => ({
    session_id: sessionId,
    tool_name: "Edit",
    tool_input: {
      file_path:
        "order-service/src/main/java/github/lms/lemuel/point/domain/PointLot.java",
    },
  });

  test("suggests once per session, then dedupes; new session suggests again", async () => {
    const repoRoot = await temporaryRepo();
    const first = await decideHookOutput(writeEvent("session-a"), { repoRoot });
    assert.ok(first, "first edit must produce a suggestion");
    const parsed = JSON.parse(first);
    assert.equal(parsed.hookSpecificOutput.hookEventName, "PreToolUse");
    assert.match(
      parsed.hookSpecificOutput.additionalContext,
      /order-commerce-rules/,
    );
    assert.equal(
      await decideHookOutput(writeEvent("session-a"), { repoRoot }),
      null,
    );
    assert.ok(await decideHookOutput(writeEvent("session-b"), { repoRoot }));
    const suggestions = readJsonl(
      join(repoRoot, ...LOG_DIR_SEGMENTS, "skill-suggestions.jsonl"),
    );
    // 세션당 (order-commerce-rules + tdd-discipline) 2건 × 2세션
    assert.equal(suggestions.length, 4);
  });

  test("Skill invocations are logged as usage, not suggestions", async () => {
    const repoRoot = await temporaryRepo();
    const output = await decideHookOutput(
      {
        session_id: "s",
        tool_name: "Skill",
        tool_input: { skill: "order-commerce-rules" },
      },
      { repoRoot },
    );
    assert.equal(output, null);
    const usage = readJsonl(
      join(repoRoot, ...LOG_DIR_SEGMENTS, "skill-usage.jsonl"),
    );
    assert.equal(usage.length, 1);
    assert.equal(usage[0].skill, "order-commerce-rules");
  });

  test("malformed events and CLI misuse never block (exit 0)", async () => {
    const repoRoot = await temporaryRepo();
    assert.equal(await decideHookOutput(null, { repoRoot }), null);
    assert.equal(
      await runRouterCli(["--hook"], {
        repoRoot,
        stdin: "not json",
        stderr: () => {},
      }),
      0,
    );
    assert.equal(
      await runRouterCli(["--bogus"], { repoRoot, stderr: () => {} }),
      0,
    );
  });
});

describe("state GC (세션 상태 파일 무한 누적 방지)", () => {
  const stateDir = (repoRoot) => join(repoRoot, ".claude", "harness", "state");
  const ageFile = async (path, days, now) => {
    const stale = new Date(now.getTime() - days * 86_400_000);
    await utimes(path, stale, stale);
  };

  test("보존기간을 넘긴 라우터 상태만 지우고, 신선한 상태·다른 파일은 남긴다", async () => {
    const repoRoot = await temporaryRepo();
    const now = new Date();
    await mkdir(stateDir(repoRoot), { recursive: true });
    const old = join(stateDir(repoRoot), "skill-router-old-session.json");
    const fresh = join(stateDir(repoRoot), "skill-router-fresh.json");
    const other = join(stateDir(repoRoot), "other-tool-state.json");
    for (const f of [old, fresh, other]) await writeFile(f, "{}", "utf8");
    await ageFile(old, 20, now);
    await ageFile(other, 20, now); // 라우터 소유가 아닌 파일은 오래돼도 건드리지 않는다
    assert.equal(await pruneStaleState(repoRoot, { now }), 1);
    assert.equal(existsSync(old), false);
    assert.equal(existsSync(fresh), true);
    assert.equal(existsSync(other), true);
  });

  test("상태 디렉토리가 없으면 조용히 0 을 반환한다", async () => {
    assert.equal(await pruneStaleState(await temporaryRepo()), 0);
  });

  test("훅 경로가 기회적으로 GC 를 수행한다", async () => {
    const repoRoot = await temporaryRepo();
    const now = new Date();
    await mkdir(stateDir(repoRoot), { recursive: true });
    const old = join(stateDir(repoRoot), "skill-router-dead-session.json");
    await writeFile(old, "{}", "utf8");
    await ageFile(old, 20, now);
    await decideHookOutput(
      {
        session_id: "gc-live",
        tool_name: "Edit",
        tool_input: {
          file_path:
            "finance-service/src/main/java/github/lms/lemuel/loan/domain/LoanAdvance.java",
        },
      },
      { repoRoot, now },
    );
    assert.equal(existsSync(old), false);
    assert.equal(
      existsSync(join(stateDir(repoRoot), "skill-router-gc-live.json")),
      true,
    );
  });
});

describe("telemetry", () => {
  test("guardHitRecords carries rule id, file, line and mode", () => {
    const now = new Date("2026-07-18T00:00:00Z");
    const records = guardHitRecords(
      "hook",
      [{ id: "MONEY-PRIMITIVE", file: "a.java", line: 3, msg: "x" }],
      now,
    );
    assert.deepEqual(records, [
      {
        ts: "2026-07-18T00:00:00.000Z",
        mode: "hook",
        id: "MONEY-PRIMITIVE",
        file: "a.java",
        line: 3,
      },
    ]);
  });

  test("HARNESS_TELEMETRY=off disables writes", async () => {
    const repoRoot = await temporaryRepo();
    const written = await appendJsonl(
      repoRoot,
      "guard-hits.jsonl",
      { a: 1 },
      { env: { HARNESS_TELEMETRY: "off" } },
    );
    assert.equal(written, false);
    assert.equal(
      existsSync(join(repoRoot, ...LOG_DIR_SEGMENTS, "guard-hits.jsonl")),
      false,
    );
  });

  test("guard hook mode records blocked violations to guard-hits.jsonl", async () => {
    const repoRoot = await temporaryRepo();
    const file =
      "settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java";
    await mkdir(join(repoRoot, ...file.split("/").slice(0, -1)), {
      recursive: true,
    });
    await writeFile(
      join(repoRoot, ...file.split("/")),
      "placeholder\n",
      "utf8",
    );
    const event = {
      tool_name: "Write",
      tool_input: { file_path: file, content: "double amount = 1.0;\n" },
    };
    const exitCode = await runGuardCli(["--hook"], {
      repoRoot,
      stdin: JSON.stringify(event),
      stdout: () => {},
      stderr: () => {},
    });
    assert.equal(exitCode, 2);
    const hits = readJsonl(
      join(repoRoot, ...LOG_DIR_SEGMENTS, "guard-hits.jsonl"),
    );
    assert.equal(hits.length, 1);
    assert.equal(hits[0].id, "MONEY-PRIMITIVE");
    assert.equal(hits[0].mode, "hook");
  });

  test("summarize surfaces zero-fire rules and ignored suggestions", () => {
    const report = summarize({
      hits: [
        {
          ts: "2026-07-18T01:00:00Z",
          mode: "hook",
          id: "MONEY-PRIMITIVE",
          file: "a.java",
          line: 1,
        },
      ],
      usage: [{ ts: "2026-07-18T01:00:00Z", skill: "money-safety" }],
      suggestions: [
        {
          ts: "2026-07-18T01:00:00Z",
          skill: "ledger-invariants",
          file: "b.java",
        },
      ],
      now: new Date("2026-07-18T02:00:00Z"),
    });
    assert.match(report, /MONEY-PRIMITIVE/);
    assert.match(report, /IMMUTABLE-HISTORY.*0회/);
    assert.match(report, /ledger-invariants/);
  });
});

describe("repoRoot 기본값", () => {
  // 훅은 셸의 cwd 를 물려받는다. cwd 를 믿으면 라우터 상태·제안 로그가 하위 디렉터리의
  // 평행 트리에 쌓이고, src/main/resources 아래에 생긴 것은 jar 에까지 실려 나간다
  // (2026-08-21 실측: 9개 위치 33개 파일, account/loan 빌드 산출물에서 확인).
  test("cwd 를 옮겨도 변하지 않는다 — 스크립트 위치에서 도출한다", () => {
    const original = process.cwd();
    const before = defaultRepoRoot();
    try {
      process.chdir(tmpdir());
      assert.equal(defaultRepoRoot(), before);
    } finally {
      process.chdir(original);
    }
  });

  test("도출된 경로 아래에 라우터 자신이 있다", async () => {
    const source = await readFile(
      join(defaultRepoRoot(), "scripts", "harness", "skill-router.mjs"), "utf8");

    assert.ok(source.includes("export function defaultRepoRoot"));
  });
});
