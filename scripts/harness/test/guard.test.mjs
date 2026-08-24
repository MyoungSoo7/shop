import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

import {
  checkCommand,
  checkKafkaDlqWiring,
  checkProtectedDeletions,
  discoverStagedDeletions,
  discoverStagedFiles,
  dodNudgeMessage,
  isRootScanned,
  normalizeRepoPath,
  parseAllowance,
  parseGradleModules,
  readUtf8Strict,
  reconstructPendingContent,
  runGuardCli,
  stripComments,
  scanText,
  checkConsumerGroupOwnership,
  defaultRepoRoot,
  expectedGroupId,
  resolveGroupId,
} from '../guard.mjs';

const temporaryDirectories = [];
async function temporaryRepo() {
  // normalizeRepoPath 는 repoRoot 를 realpath 로 정규화한 뒤 격리 여부를 판정한다. macOS 의
  // tmpdir() 은 /var → /private/var 심링크라, 픽스처 경로를 그대로 쓰면 멀쩡한 경로가
  // "repository 밖"으로 판정된다 — 리눅스 CI 는 통과하고 개발자 맥에서만 빨간불이 뜬다.
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'guard-test-')));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

const NOW = new Date('2026-07-13T12:00:00Z');
const marker = (fields = '') => ['harness-guard:', 'allow', fields && ` ${fields}`].join('');
const VALID_ALLOWANCE = marker('reason="bounded migration" issue="ISSUE-123" owner="team-settlement" expires="2026-08-01"');

const cases = [
  {
    id: 'MONEY-PRIMITIVE',
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    violation: 'double amount = 1.0;',
    normal: 'BigDecimal amount = BigDecimal.ONE;',
  },
  {
    id: 'IMMUTABLE-HISTORY',
    file: 'db/migration/V1__point_ledger.sql',
    violation: "UPDATE point_lots SET status = 'ACTIVE' WHERE status = 'EXPIRED';",
    normal: 'INSERT INTO point_lots (account_id, remaining) VALUES (1, 100);',
  },
  {
    id: 'MSA-BOUNDARY',
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/App.java',
    violation: 'import github.lms.lemuel.order.domain.Order;',
    normal: 'import github.lms.lemuel.settlement.domain.Settlement;',
  },
  {
    id: 'ACCOUNT-CONSUME-ONLY',
    file: 'finance-service/src/main/java/github/lms/lemuel/account/App.java',
    violation: 'kafkaTemplate.send("ledger", event);',
    normal: 'ledgerConsumer.consume(event);',
  },
  {
    id: 'MARKET-NO-VALUATION',
    file: 'external-data-service/src/main/java/github/lms/lemuel/market/Quote.java',
    violation: 'BigDecimal PBR = price.divide(bookValue);',
    normal: 'BigDecimal marketPrice = quote.price();',
  },
  {
    id: 'OO-DOMAIN-SETTER',
    file: 'order-service/src/main/java/github/lms/lemuel/menu/domain/Menu.java',
    violation: 'public void setName(String name) { this.name = name; }',
    normal: 'public static Menu rehydrate(Long id, String name) { return new Menu(id, name); }',
  },
  {
    id: 'OO-DOMAIN-MUTABLE-LOMBOK',
    file: 'order-service/src/main/java/github/lms/lemuel/menu/domain/Menu.java',
    violation: '@Setter',
    normal: '@Getter',
  },
  {
    id: 'OO-DOMAIN-GENERIC-IAE',
    file: 'order-service/src/main/java/github/lms/lemuel/coupon/domain/CouponPolicy.java',
    violation: 'throw new IllegalArgumentException("쿠폰 한도 초과");',
    normal: 'throw new CouponInvariantViolationException("쿠폰 한도 초과: requested=" + requested, requested, limit);',
  },
  {
    // point 는 포인트 원장(돈 경로)이다 — 돈이 오가는 도메인에서 generic IAE 가 새지 않는지가
    // 이 픽스처의 요지다.
    id: 'OO-DOMAIN-GENERIC-IAE',
    file: 'order-service/src/main/java/github/lms/lemuel/point/domain/PointLot.java',
    violation: 'throw new IllegalArgumentException("적립 금액은 양수여야 합니다: " + amount);',
    normal: 'throw new InvalidPointAmountException("적립 금액은 양수여야 합니다: " + amount, "grant", amount);',
  },
  {
    // Actions 는 워크플로 전체를 표현식 렉서로 훑는다. 빈 표현식이 하나라도 있으면
    // "An expression was expected" 로 **파일이 통째로 무효**가 되어 잡 0개·로그 없이 죽는다.
    // YAML 파서·공식 스키마·액션 SHA 검증은 전부 통과하므로 이 계층에서만 잡힌다.
    id: 'WORKFLOW-EMPTY-EXPR',
    file: '.github/workflows/pr-review.yml',
    violation: '            // 모델 출력은 신뢰할 수 없는 텍스트다 — ${{ }} 로 스크립트에 보간하지 않고',
    normal: '          github-token: ${{ secrets.GITHUB_TOKEN }}',
  },
];

describe('guard policy fixtures', () => {
  for (const fixture of cases) {
    test(`${fixture.id} blocks a violation and accepts normal content`, () => {
      const blocked = scanText(fixture.file, fixture.violation, { now: NOW });
      const clean = scanText(fixture.normalFile ?? fixture.file, fixture.normal, { now: NOW });

      assert.deepEqual(blocked.violations.map(({ id }) => id), [fixture.id]);
      assert.deepEqual(clean.violations, []);
    });
  }

  // 이 규칙은 오탐이 나면 워크플로 편집 전체를 막는다 — 저장소의 진짜 워크플로(정상 표현식
  // 190건 이상)가 깨끗한지 매번 확인한다. 동시에 "실제 파일에 규칙이 닿는가"의 증거이기도 하다.
  test('WORKFLOW-EMPTY-EXPR 은 저장소의 실제 워크플로에 오탐하지 않는다', async () => {
    const workflowDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '.github', 'workflows');
    const names = (await readdir(workflowDir)).filter((name) => /\.ya?ml$/i.test(name));
    assert.ok(names.length > 0, '워크플로를 하나도 못 찾았다 — 경로가 바뀌었는지 확인할 것');

    for (const name of names) {
      const path = join(workflowDir, name);
      const result = scanText(path, await readFile(path, 'utf8'), { now: NOW });
      assert.deepEqual(result.violations, [], `${name} 오탐`);
    }
  });

  test('WORKFLOW-EMPTY-EXPR 은 개행으로 쪼갠 빈 표현식도 막는다', () => {
    const result = scanText('.github/workflows/x.yml', 'script: |\n  // ${{\n  }}\n', { now: NOW });

    assert.deepEqual(result.violations.map(({ id }) => id), ['WORKFLOW-EMPTY-EXPR']);
  });

  test('ignores Java and Kotlin comment-only lines', () => {
    const java = scanText(cases[0].file, '// double amount = 1.0;', { now: NOW });
    const kotlin = scanText(
      'settlement-service/src/main/kotlin/github/lms/lemuel/settlement/domain/Money.kt',
      '/* float amount = 1.0; */',
      { now: NOW },
    );

    assert.deepEqual(java.violations, []);
    assert.deepEqual(kotlin.violations, []);
  });

  test('MSA-BOUNDARY blocks every order-service context, not just the legacy denylist (MED-2)', () => {
    const file = 'settlement-service/src/main/java/github/lms/lemuel/settlement/App.java';
    // 구 denylist(order|user|cart|product|coupon|shipping)가 놓치던 order 도메인 — 이제 allowlist 여집합으로 전부 차단
    for (const pkg of ['payment', 'review', 'game', 'category', 'menu', 'rbac', 'commoncode', 'order', 'user']) {
      const result = scanText(file, `import github.lms.lemuel.${pkg}.Foo;`, { now: NOW });
      assert.deepEqual(result.violations.map(({ id }) => id), ['MSA-BOUNDARY'], `${pkg} 는 차단되어야 한다`);
    }
    // settlement 자체 바운디드 컨텍스트 + shared-common(common) 은 허용 (false positive 금지)
    for (const pkg of ['settlement', 'payout', 'ledger', 'chargeback', 'pgreconciliation',
      'recon', 'recovery', 'report', 'tax', 'idempotency', 'integrity', 'closing', 'crypto', 'common']) {
      const result = scanText(file, `import github.lms.lemuel.${pkg}.Foo;`, { now: NOW });
      assert.deepEqual(result.violations, [], `${pkg} 는 허용되어야 한다`);
    }
  });

  test('MSA-BOUNDARY blocks `import static` of an order-context symbol too (#7)', () => {
    const file = 'settlement-service/src/main/java/github/lms/lemuel/settlement/App.java';
    // static 키워드가 import 와 패키지 사이에 껴도 우회하지 못한다.
    const blocked = scanText(file, 'import static github.lms.lemuel.order.OrderStatus.PAID;', { now: NOW });
    assert.deepEqual(blocked.violations.map(({ id }) => id), ['MSA-BOUNDARY']);
    // 자기 컨텍스트의 static import 는 여전히 허용(false positive 금지).
    const allowed = scanText(file, 'import static github.lms.lemuel.settlement.domain.Money.ZERO;', { now: NOW });
    assert.deepEqual(allowed.violations, []);
  });

  for (const fixture of [
    {
      id: 'MONEY-PRIMITIVE',
      file: String.raw`C:\workspace\repo\settlement-service\src\main\java\github\lms\lemuel\settlement\domain\Money.java`,
      content: 'double amount = 1.0;',
    },
    {
      id: 'MONEY-PRIMITIVE',
      file: '/workspace/repo/settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
      content: 'double amount = 1.0;',
    },
    {
      id: 'ACCOUNT-CONSUME-ONLY',
      file: String.raw`C:\workspace\repo\finance-service\src\main\java\github\lms\lemuel\account\Publisher.java`,
      content: 'kafkaTemplate.send("ledger", event);',
    },
    {
      id: 'ACCOUNT-CONSUME-ONLY',
      file: '/workspace/repo/finance-service/src/main/java/github/lms/lemuel/account/Publisher.java',
      content: 'kafkaTemplate.send("ledger", event);',
    },
  ]) {
    test(`${fixture.id} cannot be bypassed with absolute path ${fixture.file}`, () => {
      const result = scanText(fixture.file, fixture.content, { now: NOW });
      assert.ok(result.violations.some(({ id }) => id === fixture.id));
    });
  }
});

describe('money-path hardening (bypass surface)', () => {
  const file = 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Fee.java';

  for (const [name, line] of [
    ['method return type', 'double calc(BigDecimal base) {'],
    ['parameter position', 'void apply(double rate, long id) {'],
    ['array declaration', 'double[] rates = buildRates();'],
    ['var double literal inference', 'var fee = 0.035;'],
  ]) {
    test(`MONEY-PRIMITIVE blocks ${name}`, () => {
      assert.ok(
        scanText(file, line, { now: NOW }).violations.some(({ id }) => id === 'MONEY-PRIMITIVE'),
        `expected MONEY-PRIMITIVE for: ${line}`,
      );
    });
  }

  test('MONEY-PRIMITIVE catches parse split across lines', () => {
    const result = scanText(file, 'total = Double\n    .parseDouble(raw);', { now: NOW });
    assert.ok(result.violations.some(({ id }) => id === 'MONEY-PRIMITIVE'));
  });

  test('MONEY-BIGDECIMAL-DOUBLE blocks double-literal constructor including line splits', () => {
    for (const content of [
      'BigDecimal fee = new BigDecimal(0.1);',
      'BigDecimal fee = new BigDecimal(\n        0.1);',
    ]) {
      assert.ok(
        scanText(file, content, { now: NOW }).violations.some(({ id }) => id === 'MONEY-BIGDECIMAL-DOUBLE'),
        `expected MONEY-BIGDECIMAL-DOUBLE for: ${JSON.stringify(content)}`,
      );
    }
  });

  test('string constructor, int literals, and BigDecimal vars stay clean', () => {
    const clean = [
      'BigDecimal fee = new BigDecimal("0.1");',
      'BigDecimal count = new BigDecimal(3);',
      'var count = 3;',
      'var fee = new BigDecimal("0.1");',
    ].join('\n');
    assert.deepEqual(scanText(file, clean, { now: NOW }).violations, []);
  });

  test('a valid allowance suppresses the double-literal constructor on its own line', () => {
    const result = scanText(file, `BigDecimal fee = new BigDecimal(0.1); // ${VALID_ALLOWANCE}`, { now: NOW });
    assert.deepEqual(result.violations, []);
  });
});

describe('DoD nudge (money-path commit without tests)', () => {
  const moneyProd = 'settlement-service/src/main/java/github/lms/lemuel/payout/application/service/PayoutService.java';

  test('nudges when money-scope core prod changes have no staged test change', () => {
    const message = dodNudgeMessage([moneyProd, 'docs/DEVELOPMENT.md']);
    assert.match(message, /테스트/);
  });

  test('stays silent when a test change accompanies the money change', () => {
    assert.equal(dodNudgeMessage([moneyProd, 'settlement-service/src/test/java/github/lms/lemuel/payout/PayoutServiceTest.java']), null);
  });

  test('stays silent for non-money or adapter-only changes', () => {
    assert.equal(dodNudgeMessage(['docs/DEVELOPMENT.md']), null);
    assert.equal(dodNudgeMessage(['settlement-service/src/main/java/github/lms/lemuel/payout/adapter/out/persistence/PayoutJpaAdapter.java']), null);
  });
});

describe('pending content reconstruction', () => {
  test('Write returns the complete pending content', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    assert.equal(await reconstructPendingContent({ tool_name: 'Write', tool_input: { file_path: file, content: 'complete' } }, { repoRoot }), 'complete');
  });

  test('Edit replaces exactly one occurrence in the existing file', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    await writeFile(file, 'before\nold\nafter');
    const event = { tool_name: 'Edit', tool_input: { file_path: file, old_string: 'old', new_string: 'new' } };
    assert.equal(await reconstructPendingContent(event, { repoRoot }), 'before\nnew\nafter');
  });

  test('Edit rejects zero or multiple matches', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    await writeFile(file, 'same same');
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Edit', tool_input: { file_path: file, old_string: 'missing', new_string: 'new' } }, { repoRoot }));
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Edit', tool_input: { file_path: file, old_string: 'same', new_string: 'new' } }, { repoRoot }));
  });

  test('MultiEdit applies edits sequentially and rejects empty edits', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'Money.java');
    await writeFile(file, 'one two');
    const event = { tool_name: 'MultiEdit', tool_input: { file_path: file, edits: [
      { old_string: 'one', new_string: 'two' },
      { old_string: 'two two', new_string: 'done' },
    ] } };
    assert.equal(await reconstructPendingContent(event, { repoRoot }), 'done');
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'MultiEdit', tool_input: { file_path: file, edits: [] } }, { repoRoot }));
  });

  test('rejects delete, rename, root paths, and paths outside the repo', async () => {
    const repoRoot = await temporaryRepo();
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Delete', tool_input: { file_path: join(repoRoot, 'x') } }, { repoRoot }));
    await assert.rejects(() => reconstructPendingContent({ tool_name: 'Write', tool_input: { file_path: join(repoRoot, 'x'), content: '', new_path: 'y' } }, { repoRoot }));
    await assert.rejects(() => normalizeRepoPath(repoRoot, repoRoot));
    await assert.rejects(() => normalizeRepoPath(repoRoot, join(repoRoot, '..', 'escape')));
  });

  test('rejects a symlink escape through the nearest existing ancestor', async () => {
    const repoRoot = await temporaryRepo();
    const outside = await temporaryRepo();
    const link = join(repoRoot, 'link');
    await symlink(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
    await assert.rejects(() => normalizeRepoPath(repoRoot, join(link, 'new', 'file.txt')));
  });

  test('UTF-8 strict reader rejects invalid bytes', async () => {
    const repoRoot = await temporaryRepo();
    const file = join(repoRoot, 'invalid.txt');
    await writeFile(file, Buffer.from([0xc3, 0x28]));
    await assert.rejects(() => readUtf8Strict(file));
  });
});

describe('CLI dispatcher', () => {
  test('rejects malformed hook input with exit 2', async () => {
    assert.equal(await runGuardCli(['--hook'], { repoRoot: process.cwd(), stdin: '{', stdout() {}, stderr() {} }), 2);
  });

  test('hook CLI reads a valid event from stdin', async () => {
    const result = spawnSync(process.execPath, ['scripts/harness/guard.mjs', '--hook'], {
      cwd: process.cwd(),
      input: JSON.stringify({
        tool_name: 'Write',
        tool_input: { file_path: 'docs/guard-clean.txt', content: 'clean' },
      }),
    });
    assert.equal(result.status, 0, result.stderr.toString());
  });

  test('skips binary artifacts without applicable rules instead of failing UTF-8 decode', async () => {
    const repoRoot = await temporaryRepo();
    await mkdir(join(repoRoot, 'pwc', 'submission'), { recursive: true });
    await writeFile(join(repoRoot, 'pwc', 'submission', 'briefing.docx'), Buffer.from([0xff, 0xfe, 0x00, 0xd8]));
    assert.equal(
      await runGuardCli(['--files', 'pwc/submission/briefing.docx'], { repoRoot, stdout() {}, stderr() {} }),
      0,
    );
  });

  test('still fails closed when a rule-scoped source is not valid UTF-8', async () => {
    const repoRoot = await temporaryRepo();
    const sourceDir = join(repoRoot, 'settlement-service', 'src', 'main', 'java', 'domain');
    await mkdir(sourceDir, { recursive: true });
    await writeFile(join(sourceDir, 'Money.java'), Buffer.from([0xff, 0xfe, 0x00, 0xd8]));
    assert.equal(
      await runGuardCli(['--files', 'settlement-service/src/main/java/domain/Money.java'], { repoRoot, stdout() {}, stderr() {} }),
      1,
    );
  });

  test('rejects missing list with exit 1 and conflicting modes with exit 2', async () => {
    assert.equal(await runGuardCli(['--list'], { repoRoot: process.cwd(), stdout() {}, stderr() {} }), 1);
    assert.equal(await runGuardCli(['--staged', '--files', 'x'], { repoRoot: process.cwd(), stdout() {}, stderr() {} }), 2);
  });

  /** 인자를 잘못 준 실패는 "무엇을 고쳐야 하는지"까지 말해야 한다 — CI 에서 조용한 exit 1 은 원인 불명 실패다. */
  const usageOf = async (args) => {
    const errors = [];
    const code = await runGuardCli(args, { repoRoot: process.cwd(), stdout() {}, stderr: (m) => errors.push(m) });
    return { code, stderr: errors.join('\n') };
  };

  test('--list 는 인자가 빠지면 사용법을 알린다', async () => {
    const { code, stderr } = await usageOf(['--list']);
    assert.equal(code, 1);
    assert.match(stderr, /usage: guard --list <file>/);
  });

  test('--files 는 인자가 빠지면 사용법을 알린다', async () => {
    const { code, stderr } = await usageOf(['--files']);
    assert.equal(code, 1);
    assert.match(stderr, /usage: guard --files <file>/);
  });

  test('--deleted-list 는 인자가 빠지면 사용법을 알린다', async () => {
    const { code, stderr } = await usageOf(['--deleted-list']);
    assert.equal(code, 2);
    assert.match(stderr, /usage: guard --deleted-list <file>/);
  });

  test('discovers ACMR staged paths including spaces and renames', async () => {
    const repoRoot = await temporaryRepo();
    spawnSync('git', ['init'], { cwd: repoRoot });
    spawnSync('git', ['config', 'user.email', 'guard@example.com'], { cwd: repoRoot });
    spawnSync('git', ['config', 'user.name', 'Guard'], { cwd: repoRoot });
    await writeFile(join(repoRoot, 'old name.txt'), 'old');
    spawnSync('git', ['add', '.'], { cwd: repoRoot });
    spawnSync('git', ['commit', '-m', 'base'], { cwd: repoRoot });
    spawnSync('git', ['mv', 'old name.txt', 'new name.txt'], { cwd: repoRoot });
    assert.deepEqual(discoverStagedFiles(repoRoot), ['new name.txt']);
  });

  test('self-test child process exits 0', () => {
    const result = spawnSync(process.execPath, ['scripts/harness/guard.mjs', '--self-test'], { cwd: process.cwd() });
    assert.equal(result.status, 0, result.stderr?.toString());
  });
});

describe('bash command guard (--hook-bash · 실시간 우회 봉쇄)', () => {
  const ids = (cmd) => checkCommand(cmd, { allowCommands: false }).map((v) => v.id);

  test('sed/perl 인플레이스·리다이렉트·tee 로 소스를 쓰면 CMD-EDIT-BYPASS', () => {
    assert.deepEqual(ids("sed -i 's/a/b/' order-service/src/main/java/Order.java"), ['CMD-EDIT-BYPASS']);
    assert.deepEqual(ids("perl -pi -e 's/a/b/' scripts/harness/guard.mjs"), ['CMD-EDIT-BYPASS']);
    assert.deepEqual(ids("cat > V20260815__x.sql <<'EOF'"), ['CMD-EDIT-BYPASS']);
    assert.deepEqual(ids('echo x | tee .github/workflows/ci.yml'), ['CMD-EDIT-BYPASS']);
  });

  test('읽기 전용 sed·비소스 리다이렉트는 통과한다 (오탐 방지)', () => {
    assert.deepEqual(ids("sed -n '1,10p' scripts/harness/guard.mjs"), []);
    assert.deepEqual(ids('node build.mjs > build.log 2>&1'), []);
    assert.deepEqual(ids('grep -i pattern Money.java'), []);
    assert.deepEqual(ids('git commit -F commit-msg.txt'), []);
  });

  test('git --no-verify 는 commit·push 모두 차단', () => {
    assert.deepEqual(ids('git commit --no-verify -m x'), ['CMD-NO-VERIFY']);
    assert.deepEqual(ids('git push --no-verify origin develop'), ['CMD-NO-VERIFY']);
    assert.deepEqual(ids('git commit -m "no-verify 언급만"'), []);
  });

  test('서비스 DB 직접 쓰기·운영 파드 psql 은 차단, 조회는 통과', () => {
    assert.deepEqual(ids('psql -d settlement_db -c "UPDATE settlements SET s=1"'), ['CMD-PROD-DB-WRITE']);
    assert.deepEqual(ids('psql -d lemuel_deposit -c "TRUNCATE deposit_ledger"'), ['CMD-PROD-DB-WRITE']);
    assert.deepEqual(ids('kubectl exec -it pod -- psql -d lemuel_account'), ['CMD-PROD-DB-WRITE']);
    assert.deepEqual(ids('psql -d settlement_db -c "SELECT count(*) FROM settlements"'), []);
  });

  test('lemuel 토픽 직접 produce 는 WARN(비차단)', () => {
    const violations = checkCommand('rpk topic produce lemuel.order.created', { allowCommands: false });
    assert.deepEqual(violations.map((v) => [v.id, v.severity]), [['CMD-EVENT-PRODUCE', 'WARN']]);
  });

  test('HARNESS_ALLOW_CMD opt-in 이면 전부 통과한다', () => {
    assert.deepEqual(checkCommand('git commit --no-verify', { allowCommands: true }), []);
  });

  test('--hook-bash: BLOCK 은 exit 2, WARN 만이면 additionalContext + exit 0, 깨진 입력은 fail-open', async () => {
    const repoRoot = await temporaryRepo();
    const run = async (stdin) => {
      const out = [];
      const code = await runGuardCli(['--hook-bash'], { repoRoot, stdin, stdout: (m) => out.push(m), stderr() {} });
      return { code, out: out.join('\n') };
    };
    const blocked = await run(JSON.stringify({ tool_name: 'Bash', tool_input: { command: 'git commit --no-verify' } }));
    assert.equal(blocked.code, 2);
    const warned = await run(JSON.stringify({ tool_name: 'Bash', tool_input: { command: 'rpk topic produce lemuel.x -z none' } }));
    assert.equal(warned.code, 0);
    assert.match(warned.out, /CMD-EVENT-PRODUCE/);
    const broken = await run('{');
    assert.equal(broken.code, 0); // 운반 수단 계층은 fail-open — 커밋·CI 가 내용 기준으로 재차단
    const clean = await run(JSON.stringify({ tool_name: 'Bash', tool_input: { command: 'git status' } }));
    assert.equal(clean.code, 0);
    assert.equal(clean.out, '');
  });
});

describe('structured allowances', () => {
  test('parses an auditable allowance', () => {
    assert.deepEqual(parseAllowance(`// ${VALID_ALLOWANCE}`, { now: NOW }), {
      reason: 'bounded migration',
      issue: 'ISSUE-123',
      owner: 'team-settlement',
      expires: '2026-08-01',
    });
  });

  test('accepts a GitHub issue URL', () => {
    const line = marker('reason="temporary bridge" issue="https://github.com/acme/settlement/issues/42" owner="team-platform" expires="2026-08-01"');
    assert.equal(parseAllowance(line, { now: NOW })?.issue, 'https://github.com/acme/settlement/issues/42');
  });

  for (const [name, allowanceText] of [
    ['bare', marker()],
    ['missing field', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement"')],
    ['extra field', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-08-01" ticket="shadow"')],
    ['blank reason', marker('reason="   " issue="ISSUE-1" owner="team-settlement" expires="2026-08-01"')],
    ['malformed issue', marker('reason="bounded" issue="ADR-1" owner="team-settlement" expires="2026-08-01"')],
    ['malformed owner', marker('reason="bounded" issue="ISSUE-1" owner="alice" expires="2026-08-01"')],
    ['invalid date', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-02-30"')],
    ['expired', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-07-12"')],
    ['expires today', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-07-13"')],
  ]) {
    test(`rejects ${name} allowance`, () => {
      assert.equal(parseAllowance(allowanceText, { now: NOW }), null);
      const result = scanText(cases[0].file, `double amount = 1.0; // ${allowanceText}`, { now: NOW });
      assert.ok(result.violations.some(({ id }) => id === 'INVALID-ALLOWANCE'));
      assert.ok(result.violations.some(({ id }) => id === 'MONEY-PRIMITIVE'));
    });
  }

  test('a valid allowance suppresses only its own violating line and is reported', () => {
    const result = scanText(
      cases[0].file,
      `double first = 1.0; // ${VALID_ALLOWANCE}\ndouble second = 2.0;`,
      { now: NOW },
    );

    assert.deepEqual(result.violations.map(({ id, line }) => ({ id, line })), [
      { id: 'MONEY-PRIMITIVE', line: 2 },
    ]);
    assert.deepEqual(result.allowances, [{
      file: cases[0].file,
      line: 1,
      reason: 'bounded migration',
      issue: 'ISSUE-123',
      owner: 'team-settlement',
      expires: '2026-08-01',
    }]);
  });
});

describe('protected harness path deletion', () => {
  test('flags deletion of agent config and harness paths', () => {
    const violations = checkProtectedDeletions([
      '.claude/skills/tdd-discipline/SKILL.md',
      '.codex/config.toml',
      'scripts/harness/guard.mjs',
    ]);

    assert.deepEqual(violations.map(({ id }) => id),
      ['HARNESS-DELETE', 'HARNESS-DELETE', 'HARNESS-DELETE']);
  });

  test('ignores deletions outside protected paths', () => {
    assert.deepEqual(checkProtectedDeletions([
      'settlement-service/src/main/java/Foo.java',
      'docs/adr/0001-x.md',
      'README.md',
    ]), []);
  });

  // docs/harness 는 하네스 기계장치가 아니라 해커톤 제출물 보관함이다 — PR #210 사고 때 세 경로가
  // 한 묶음으로 지워져 보호 목록에 함께 들어갔을 뿐이다. 공개 저장소 위생상 의도적으로 비우는
  // 대상이므로(CLAUDE.md 배치 기준) 보호하지 않는다. 진짜 하네스는 scripts/harness·.claude·.codex 다.
  test('docs/harness is submission storage, not harness machinery — deletion is allowed', () => {
    assert.deepEqual(checkProtectedDeletions([
      'docs/harness/hackathon/kakaopay/submission/README.md',
      'docs/harness/omc-harness.md',
    ]), []);
  });

  test('does not mistake lookalike prefixes for protected paths', () => {
    assert.deepEqual(checkProtectedDeletions([
      '.claudex/file.md',
      'docs/harness2/etc/note.md',
      'scripts/harness-old/tool.mjs',
    ]), []);
  });

  test('a single deletion is enough to block — mass deletion is not the threshold', () => {
    assert.equal(checkProtectedDeletions(['.claude/skills/money-safety/SKILL.md']).length, 1);
  });

  test('gitignored subtrees are not protected — they are regenerable session state', () => {
    assert.deepEqual(checkProtectedDeletions([
      '.claude/scratch/note.md',
      '.claude/agent-memory/x/MEMORY.md',
      '.claude/worktrees/w/file',
      '.claude/harness/state.json',
    ]), []);
  });

  test('the message names the recovery path so an operator is not stuck', () => {
    const [violation] = checkProtectedDeletions(['.claude/skills/oo-score/SKILL.md']);
    assert.match(violation.msg, /HARNESS_ALLOW_DELETE/);
  });

  test('escape hatch: explicit env opt-in clears the block', () => {
    assert.deepEqual(
      checkProtectedDeletions(['.claude/skills/oo-score/SKILL.md'], { allowDelete: true }),
      [],
    );
  });

  test('staged deletions are discoverable — ACMR filter alone hides them', () => {
    assert.equal(typeof discoverStagedDeletions, 'function');
  });
});

describe('--deleted-list mode (CI wiring)', () => {
  test('blocks when the deleted-file list touches a protected path', async () => {
    const repoRoot = await temporaryRepo();
    await writeFile(join(repoRoot, 'deleted.txt'), '.claude/skills/tdd-discipline/SKILL.md\n');
    const errors = [];

    const code = await runGuardCli(['--deleted-list', 'deleted.txt'],
      { repoRoot, stderr: (m) => errors.push(m), stdout: () => {} });

    assert.equal(code, 1);
    assert.ok(errors.some((m) => m.includes('HARNESS-DELETE')));
  });

  test('passes when no protected path is deleted', async () => {
    const repoRoot = await temporaryRepo();
    await writeFile(join(repoRoot, 'deleted.txt'), 'settlement-service/src/main/java/Foo.java\n');

    assert.equal(await runGuardCli(['--deleted-list', 'deleted.txt'],
      { repoRoot, stderr: () => {}, stdout: () => {} }), 0);
  });

  test('an empty deleted list is clean, not an error', async () => {
    const repoRoot = await temporaryRepo();
    await writeFile(join(repoRoot, 'deleted.txt'), '');

    assert.equal(await runGuardCli(['--deleted-list', 'deleted.txt'],
      { repoRoot, stderr: () => {}, stdout: () => {} }), 0);
  });
});

describe('KAFKA-DLQ wiring (컨슈머는 있는데 DLT 배선이 없는 서비스)', () => {
  const SETTINGS = 'include(\n  "card-service",\n  "company-service",\n)\nincludeBuild("shared-common")';
  const APP = (module, pkg) => `${module}/src/main/java/github/lms/lemuel/${pkg}App.java`;

  /** git grep 대역 — 패턴별로 매치 파일 목록을 돌려준다. */
  const fakeGrep = ({ listeners = [], imports = [], apps = [], ownWiring = [] }) => (_root, pattern, pathspecs) => {
    // 단일 파일 pathspec 조회는 쓰지 않는다(readSource 로 대체) — 패턴만 보고 분기한다.
    if (pattern.includes('KafkaListener')) return listeners;
    if (pattern.includes('KafkaConsumerErrorHandlingConfig')) return imports;
    if (pattern.includes('DeadLetterPublishingRecoverer')) return ownWiring;
    if (pattern.includes('SpringBoot')) return apps;
    throw new Error(`unexpected pattern ${pattern} ${pathspecs}`);
  };

  /**
   * build.gradle.kts 는 shared-common 을 의존하고, 앱 클래스는 주어진 소스를 쓴다.
   * `files` 로 특정 경로의 내용을 덮어써 배선 파일(진짜 @Import vs 자바독 언급)을 구분한다.
   */
  const fakeSource = (appSource, { sharedCommon = true, files = {} } = {}) => (file) => {
    if (file.endsWith('build.gradle.kts')) {
      return sharedCommon ? 'implementation("github.lms.lemuel:shared-common:1.0.0")' : 'implementation("x")';
    }
    return Object.hasOwn(files, file) ? files[file] : appSource;
  };

  const COMPANY_CFG = 'company-service/src/main/java/github/lms/lemuel/company/adapter/in/kafka/Cfg.java';
  const NOTIFICATION_CFG = 'notification-service/src/main/kotlin/github/lms/lemuel/notification/Cfg.kt';

  test('루트 스캔 + shared-common 의존 + 공용 배선 존재면 통과한다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['card-service/src/main/java/github/lms/lemuel/card/adapter/in/kafka/C.java'],
        imports: [SHARED_PROVIDER], // shared-common 이 실제로 배선을 제공하는 상태
        apps: [APP('card-service', '')],
      }),
      readSource: fakeSource('@SpringBootApplication\npublic class CardServiceApplication {}', {
        files: { [SHARED_PROVIDER]: 'public class KafkaConsumerErrorHandlingConfig {}' },
      }),
    });

    assert.deepEqual(violations, []);
  });

  const SHARED_PROVIDER = 'shared-common/src/main/java/github/lms/lemuel/common/config/kafka/KafkaConsumerErrorHandlingConfig.java';

  test('공용 배선 클래스가 저장소에서 사라지면 루트 스캔 서비스도 통과하지 못한다 — 7cf573446 식 사고를 잡는 조건', () => {
    // 실제 사고: "fix(ci): 액션 SHA 핀" 커밋이 서비스별 배선 5벌을 함께 지웠다. 당시 shared-common 에는
    // 공용 배선이 없었으므로, (a) 경로가 "루트 스캔 + shared-common 의존"만 보면 5개 서비스가 조용히 통과한다.
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['card-service/src/main/java/github/lms/lemuel/card/adapter/in/kafka/C.java'],
        imports: [], // 공용 설정을 언급하는 파일이 하나도 없다 = 클래스가 사라졌다
        apps: [APP('card-service', '')],
      }),
      readSource: fakeSource('@SpringBootApplication\npublic class CardServiceApplication {}'),
    });

    assert.equal(violations.length, 1);
    assert.equal(violations[0].id, 'KAFKA-DLQ');
    assert.match(violations[0].msg, /공용 배선/);
  });

  test('공용 배선을 이름만 언급하는 파일은 제공자로 치지 않는다 — 정의가 있어야 한다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['card-service/src/main/java/github/lms/lemuel/card/adapter/in/kafka/C.java'],
        imports: ['card-service/src/main/java/github/lms/lemuel/card/adapter/in/kafka/C.java'],
        apps: [APP('card-service', '')],
      }),
      readSource: fakeSource('@SpringBootApplication', {
        files: {
          'card-service/src/main/java/github/lms/lemuel/card/adapter/in/kafka/C.java':
            '// TODO: KafkaConsumerErrorHandlingConfig 로 옮기기\nclass C {}',
        },
      }),
    });

    assert.equal(violations.length, 1);
  });


  test('루트 스캔이어도 shared-common 미의존이면 차단한다 — 공용 설정이 클래스패스에 없다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['card-service/src/main/java/github/lms/lemuel/card/adapter/in/kafka/C.java'],
        apps: [APP('card-service', '')],
      }),
      readSource: fakeSource('@SpringBootApplication', { sharedCommon: false }),
    });

    assert.equal(violations.length, 1);
    assert.equal(violations[0].id, 'KAFKA-DLQ');
  });

  test('제한 스캔 서비스가 @Import 를 빠뜨리면 차단한다 — 이게 card·insurance·operation 이 유실되던 상태다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['company-service/src/main/java/github/lms/lemuel/company/adapter/in/kafka/C.java'],
        apps: [APP('company-service', 'company/')],
      }),
      readSource: fakeSource('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.company")'),
    });

    assert.equal(violations.length, 1);
    assert.equal(violations[0].id, 'KAFKA-DLQ');
    assert.match(violations[0].file, /^company-service/);
  });

  test('제한 스캔이어도 명시 @Import 가 있으면 통과한다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['company-service/src/main/java/github/lms/lemuel/company/adapter/in/kafka/C.java'],
        imports: [COMPANY_CFG],
        apps: [APP('company-service', 'company/')],
      }),
      readSource: fakeSource('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.company")', {
        files: {
          [COMPANY_CFG]: '@Configuration\n@Import(KafkaConsumerErrorHandlingConfig.class)\npublic class Cfg {}',
        },
      }),
    });

    assert.deepEqual(violations, []);
  });

  test('줄바꿈이 낀 @Import 도 인정한다 — 포매터가 애노테이션을 접어도 배선은 배선이다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['company-service/src/main/java/github/lms/lemuel/company/adapter/in/kafka/C.java'],
        imports: [COMPANY_CFG],
        apps: [APP('company-service', 'company/')],
      }),
      readSource: fakeSource('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.company")', {
        files: {
          [COMPANY_CFG]: '@Import({\n    OtherConfig.class,\n    KafkaConsumerErrorHandlingConfig.class,\n})\nclass Cfg {}',
        },
      }),
    });

    assert.deepEqual(violations, []);
  });

  test('자바독 언급만으로는 통과하지 않는다 — @Import 를 지우고 주석만 남기면 배선은 사라진다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['company-service/src/main/java/github/lms/lemuel/company/adapter/in/kafka/C.java'],
        imports: [COMPANY_CFG],
        apps: [APP('company-service', 'company/')],
      }),
      readSource: fakeSource('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.company")', {
        files: {
          // 실제 저장소에 존재하는 문장 형태 그대로 — 이게 통과되면 가드는 아무것도 지키지 못한다.
          [COMPANY_CFG]: '/**\n * shared-common 의 {@link KafkaConsumerErrorHandlingConfig} 가 자동으로 잡히지 않는다 —\n'
            + ' * {@code @Import(KafkaConsumerErrorHandlingConfig.class)} 가 필요하다.\n */\nclass Cfg {}',
        },
      }),
    });

    assert.equal(violations.length, 1);
    assert.equal(violations[0].id, 'KAFKA-DLQ');
    assert.match(violations[0].file, /^company-service/);
  });

  test('폴리글랏 standalone 도 대상이다 — 배선이 없으면 차단한다 (settings 밖이라고 유실이 허용되지 않는다)', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['notification-service/src/main/kotlin/github/lms/lemuel/notification/K.kt'],
        apps: [],
      }),
      readSource: fakeSource(''),
    });

    assert.equal(violations.length, 1);
    assert.match(violations[0].file, /^notification-service/);
  });

  test('폴리글랏 standalone 이 자체 DLT 배선을 가지면 통과한다 (notification-service 형태)', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['notification-service/src/main/kotlin/github/lms/lemuel/notification/K.kt'],
        ownWiring: [NOTIFICATION_CFG],
        apps: [],
      }),
      readSource: fakeSource('', {
        files: { [NOTIFICATION_CFG]: 'fun recoverer() = DeadLetterPublishingRecoverer(dltKafkaTemplate) { r, e -> tp }' },
      }),
    });

    assert.deepEqual(violations, []);
  });

  test('standalone 이 주석으로만 자체 배선을 언급하면 통과하지 않는다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({
        listeners: ['notification-service/src/main/kotlin/github/lms/lemuel/notification/K.kt'],
        ownWiring: [NOTIFICATION_CFG],
        apps: [],
      }),
      readSource: fakeSource('', {
        files: { [NOTIFICATION_CFG]: '/** 언젠가 DeadLetterPublishingRecoverer 로 격리할 예정. */\nclass Cfg' },
      }),
    });

    assert.equal(violations.length, 1);
    assert.match(violations[0].file, /^notification-service/);
  });

  test('컨슈머가 없는 모듈은 배선을 요구하지 않는다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => SETTINGS,
      gitGrepFiles: fakeGrep({ listeners: [], apps: [APP('card-service', '')] }),
      readSource: fakeSource('@SpringBootApplication'),
    });

    assert.deepEqual(violations, []);
  });

  test('settings 를 못 읽는 환경에서는 가드를 깨뜨리지 않는다', () => {
    const violations = checkKafkaDlqWiring('/repo', {
      readSettings: () => { throw new Error('no settings'); },
      gitGrepFiles: () => { throw new Error('should not be called'); },
    });

    assert.deepEqual(violations, []);
  });

  test('parseGradleModules 는 include 블록의 모듈만 뽑는다 (includeBuild 는 제외)', () => {
    assert.deepEqual(parseGradleModules(SETTINGS), ['card-service', 'company-service']);
  });

  describe('stripComments', () => {
    test('한 줄 주석과 블록 주석을 지운다 — 배선 판정은 실행되는 코드만 본다', () => {
      assert.equal(stripComments('a // @Import(X.class)\nb').includes('@Import'), false);
      assert.equal(stripComments('/* @Import(X.class) */ b').includes('@Import'), false);
      assert.equal(stripComments('/** {@link X} */\n@Import(X.class)').includes('@Import'), true);
    });

    test('문자열 안의 // 는 주석이 아니다 — URL 때문에 뒤 코드가 통째로 날아가면 오탐이 난다', () => {
      const code = 'val url = "https://example.com"; wire(DeadLetterPublishingRecoverer(t))';
      assert.equal(stripComments(code).includes('DeadLetterPublishingRecoverer('), true);
    });

    test('주석 안의 따옴표가 문자열 상태를 오염시키지 않는다', () => {
      const code = '// it\'s a comment with "quote\n@Import(X.class)';
      assert.equal(stripComments(code).includes('@Import(X.class)'), true);
    });
  });

  describe('isRootScanned', () => {
    test('스캔 속성이 없으면 진입점 패키지(=루트)부터 스캔이다', () => {
      assert.equal(isRootScanned('@SpringBootApplication\nclass A {}'), true);
    });

    test('하위 패키지로 좁히면 공용 설정이 안 잡힌다', () => {
      assert.equal(isRootScanned('@SpringBootApplication(scanBasePackages = "github.lms.lemuel.company")'), false);
    });

    test('루트 basePackages + excludeFilters 는 여전히 루트 스캔이다 (account 형태)', () => {
      const source = '@SpringBootConfiguration\n@ComponentScan(\n  basePackages = "github.lms.lemuel",\n'
        + '  excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,\n'
        + '    pattern = {"github\\\\.lms\\\\.lemuel\\\\.common\\\\.outbox\\\\.adapter\\\\.out\\\\..*"}))';
      assert.equal(isRootScanned(source), true);
    });

    test('@EntityScan/@EnableJpaRepositories 의 basePackages 는 컴포넌트 스캔과 무관하다 (오탐 회귀)', () => {
      const source = '@ComponentScan(basePackages = "github.lms.lemuel")\n'
        + '@EntityScan(basePackages = {"github.lms.lemuel.account.adapter.out.persistence"})\n'
        + '@EnableJpaRepositories(basePackages = {"github.lms.lemuel.account.adapter.out.persistence"})';
      assert.equal(isRootScanned(source), true);
    });
  });
});

describe('KAFKA-GROUP-OWNER (컨슈머 그룹 ID 소유권)', () => {
  const SETTINGS = [
    'include(',
    '  "order-service",',
    '  "settlement-service",',
    '  "gateway-service",',
    ')',
  ].join('\n');
  const yaml = (groupId) => [
    'spring:',
    '  kafka:',
    '    consumer:',
    `      group-id: ${groupId}`,
  ].join('\n');

  test('모듈명과 짝이 맞으면 통과한다', () => {
    const violations = checkConsumerGroupOwnership('/repo', {
      readSettings: () => SETTINGS,
      readYaml: (m) => yaml(`lemuel-${m.replace('-service', '')}`),
    });

    assert.deepEqual(violations, []);
  });

  test('다른 모듈의 그룹 ID 를 쓰면 잡는다 — order 가 lemuel-settlement 을 들고 있던 실제 상태', () => {
    const violations = checkConsumerGroupOwnership('/repo', {
      readSettings: () => SETTINGS,
      readYaml: (m) => yaml(m === 'order-service' ? 'lemuel-settlement' : 'lemuel-settlement'),
    });

    const order = violations.find((v) => v.file.startsWith('order-service'));
    assert.equal(order.id, 'KAFKA-GROUP-OWNER');
    assert.match(order.msg, /lemuel-order/);
  });

  test('컨슈머가 없는 모듈(group-id 미선언)은 대상이 아니다', () => {
    const violations = checkConsumerGroupOwnership('/repo', {
      readSettings: () => SETTINGS,
      readYaml: () => ['spring:', '  application:', '    name: x'].join('\n'),
    });

    assert.deepEqual(violations, []);
  });

  test('설정 파일이 없는 모듈은 건너뛴다', () => {
    assert.deepEqual(checkConsumerGroupOwnership('/repo', {
      readSettings: () => SETTINGS, readYaml: () => null,
    }), []);
  });

  test('환경변수로 감싼 값은 기본값으로 판정한다 — 실제로 뜨는 값이다', () => {
    assert.equal(resolveGroupId('${KAFKA_GROUP_ID:lemuel-order}'), 'lemuel-order');
    assert.equal(expectedGroupId('common-data-service'), 'lemuel-common-data');
  });

  test('settings 를 못 읽으면 가드를 깨뜨리지 않는다', () => {
    assert.deepEqual(checkConsumerGroupOwnership('/repo', {
      readSettings: () => { throw new Error('shallow clone'); },
    }), []);
  });

  // 리포 전수 — 규칙이 현재 트리에서 실제로 성립하는지
  test('현재 저장소의 모든 모듈이 규칙을 지킨다', () => {
    const actualRepoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

    assert.deepEqual(checkConsumerGroupOwnership(actualRepoRoot), []);
  });
});

describe('repoRoot 기본값', () => {
  // 훅은 셸의 cwd 를 물려받는다. Bash 도구의 cwd 가 하위 디렉터리에 남아 있던 상태로 가드가
  // 돌면 repoRoot 가 그 하위 디렉터리가 되어, 정상 저장소 경로가 "outside repository" 로
  // 차단되고 텔레메트리도 평행 트리에 쌓인다. cwd 에 기대지 않는다는 것을 못 박는다.
  test('cwd 를 옮겨도 변하지 않는다 — 스크립트 위치에서 도출한다', async () => {
    const original = process.cwd();
    const before = defaultRepoRoot();
    try {
      process.chdir(tmpdir());
      assert.equal(defaultRepoRoot(), before);
    } finally {
      process.chdir(original);
    }
  });

  test('도출된 경로가 실제 저장소 루트다 — 그 아래 guard.mjs 가 있다', async () => {
    const source = await readFile(join(defaultRepoRoot(), 'scripts', 'harness', 'guard.mjs'), 'utf8');

    assert.ok(source.includes('export function defaultRepoRoot'));
  });
});
