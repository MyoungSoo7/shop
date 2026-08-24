#!/usr/bin/env node
/**
 * fashion-copilot 스모크 테스트 (네트워크 불필요).
 *  1) MCP 서버: initialize → tools/list → coupon_simulate/refund_simulate 왕복 검증
 *  2) 가드 규칙: money/stock/coupon/pii/prod-db 케이스 검증
 *  3) 설치기: install.mjs codex 멱등성(2회 실행 수렴) + 타 설정 보존 + uninstall 청소
 * 실행: node fashion-copilot/test/smoke.mjs
 */
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { checkFileContent, checkCommand } from '../hooks/guards/rules.mjs';

const here = dirname(fileURLToPath(import.meta.url));
let failures = 0;

function assert(name, cond, detail = '') {
  if (cond) console.log(`  ok  ${name}`);
  else { failures++; console.error(`FAIL  ${name} ${detail}`); }
}

// ── 1. MCP server round-trip ─────────────────────────────────────────────────
async function testMcp() {
  console.log('[1] MCP server round-trip');
  const server = spawn(process.execPath, [join(here, '..', 'mcp', 'server', 'index.mjs')], {
    stdio: ['pipe', 'pipe', 'inherit'],
  });

  const responses = [];
  let buf = '';
  server.stdout.on('data', d => {
    buf += d;
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (line) responses.push(JSON.parse(line));
    }
  });

  const send = m => server.stdin.write(JSON.stringify(m) + '\n');
  send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'smoke', version: '0' } } });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'coupon_simulate', arguments: { type: 'PERCENTAGE', discountValue: '10', orderAmount: '33333', refundAmount: '10000' } } });
  send({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'coupon_simulate', arguments: { type: 'PERCENTAGE', discountValue: '20', orderAmount: '100000', maxDiscountAmount: '10000' } } });
  send({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'coupon_simulate', arguments: { type: 'FIXED', discountValue: '5000', orderAmount: '3000' } } });
  send({ jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'refund_simulate', arguments: { paymentAmount: '50000', refundedAmount: '20000', paymentId: 'PAY1' } } });
  send({ jsonrpc: '2.0', id: 7, method: 'tools/call', params: { name: 'refund_simulate', arguments: { paymentAmount: '50000', refundedAmount: '20000', requestAmount: '40000' } } });

  await new Promise(r => setTimeout(r, 1500));
  server.kill();

  const byId = id => responses.find(r => r.id === id);
  const parse = id => byId(id) && JSON.parse(byId(id).result.content[0].text);

  assert('initialize 응답', byId(1)?.result?.serverInfo?.name === 'fashion-copilot');
  assert('tools/list 5개 도구', byId(2)?.result?.tools?.length === 5,
    `got ${byId(2)?.result?.tools?.length}`);
  const toolNames = (byId(2)?.result?.tools ?? []).map(t => t.name);
  for (const t of ['refund_recon', 'refund_health', 'stock_pulse', 'coupon_simulate', 'refund_simulate']) {
    assert(`도구 등록: ${t}`, toolNames.includes(t), `missing ${t}`);
  }

  const pct = parse(3);
  assert('coupon: 33,333 × 10% → 3,333 (FLOOR)', pct?.discount === '3333', `got ${pct?.discount}`);
  assert('coupon: paid 30,000', pct?.paidAmount === '30000', `got ${pct?.paidAmount}`);
  assert('coupon: 환불 10,000 안분 999 (FLOOR)', pct?.refundProration?.proratedDiscount === '999',
    `got ${pct?.refundProration?.proratedDiscount}`);
  assert('coupon: 현금 환불 9,001', pct?.refundProration?.cashRefund === '9001',
    `got ${pct?.refundProration?.cashRefund}`);

  const clamp = parse(4);
  assert('coupon: 20,000 → max 10,000 클램프', clamp?.discount === '10000' && clamp?.clampedByMax === true,
    `got ${clamp?.discount}/${clamp?.clampedByMax}`);

  const fixed = parse(5);
  assert('coupon: FIXED 5,000 on 3,000 → 3,000 (주문액 초과 금지)', fixed?.discount === '3000',
    `got ${fixed?.discount}`);

  const full = parse(6);
  assert('refund: FULL 판정 + 가능액 30,000', full?.mode === 'FULL' && full?.refundAmount === '30000',
    `got ${full?.mode}/${full?.refundAmount}`);
  assert('refund: 자동 멱등키 payment-PAY1-full', full?.idempotencyKey?.key === 'payment-PAY1-full',
    `got ${full?.idempotencyKey?.key}`);
  assert('refund: 전액 도달 → REFUNDED', String(full?.orderStatusAfter ?? '').startsWith('REFUNDED'),
    `got ${full?.orderStatusAfter}`);

  const exceed = parse(7);
  assert('refund: 초과 요청 거부 (RefundExceeds)', exceed?.allowed === false
    && String(exceed?.rejection ?? '').includes('RefundExceeds'), `got ${exceed?.rejection}`);
  assert('refund: 부분은 호출자 멱등키 필수', exceed?.idempotencyKey?.policy === 'CALLER_REQUIRED',
    `got ${exceed?.idempotencyKey?.policy}`);
}

// ── 2. Guard rules ───────────────────────────────────────────────────────────
function testGuards() {
  console.log('[2] Guard rules');

  const money = checkFileContent(
    'order-service/src/main/java/github/lms/lemuel/payment/application/RefundCalc.java',
    'double refundAmount = total * 0.1;\nBigDecimal d = new BigDecimal(0.1);\n');
  assert('money: double refundAmount 차단', money.some(v => v.rule === 'money-type-guard' && v.severity === 'BLOCK'));
  assert('money: BigDecimal(double) 차단', money.filter(v => v.rule === 'money-type-guard').length >= 2);

  const moneyOk = checkFileContent(
    'order-service/src/main/java/github/lms/lemuel/coupon/domain/Coupon.java',
    'BigDecimal discount = amount.multiply(rate);\n');
  assert('money: BigDecimal 정상 통과', moneyOk.length === 0, JSON.stringify(moneyOk));

  const stockBad = checkFileContent('order-service/src/main/java/x/Repo.java',
    '"UPDATE product_variants SET stock_quantity = stock_quantity - :qty WHERE id = :id"\n');
  assert('stock: 조건 없는 재고 UPDATE 차단', stockBad.some(v => v.rule === 'stock-atomicity-guard' && v.severity === 'BLOCK'));

  const stockOk = checkFileContent('order-service/src/main/java/x/Repo.java',
    '"UPDATE product_variants SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty"\n');
  assert('stock: stock >= 조건부 UPDATE 통과', !stockOk.some(v => v.rule === 'stock-atomicity-guard'), JSON.stringify(stockOk));

  const rmw = checkFileContent('order-service/src/main/java/x/StockService.java',
    'variant.setStockQuantity(newQty);\n');
  assert('stock: setStockQuantity 직접 호출 WARN', rmw.some(v => v.rule === 'stock-atomicity-guard' && v.severity === 'WARN'));

  const couponBad = checkFileContent('order-service/src/main/java/x/CouponService.java',
    'coupon.setUsedCount(coupon.getUsedCount() + 1);\n');
  assert('coupon: setUsedCount 직접 조작 차단', couponBad.some(v => v.rule === 'coupon-usage-guard' && v.severity === 'BLOCK'));

  const couponInc = checkFileContent('order-service/src/main/java/x/Coupon.java', 'this.usedCount++;\n');
  assert('coupon: usedCount++ 차단', couponInc.some(v => v.rule === 'coupon-usage-guard'));

  const pii = checkFileContent('order-service/src/main/java/x/ShippingService.java',
    'log.info("ship to phone={}", phone);\n');
  assert('pii: 연락처 로깅 차단', pii.some(v => v.rule === 'pii-logging-guard' && v.severity === 'BLOCK'));

  const piiOk = checkFileContent('order-service/src/main/java/x/ShippingService.java',
    'log.info("ship to phone={}", masker.maskPhone(phone));\n');
  assert('pii: 마스킹 경유 통과', !piiOk.some(v => v.rule === 'pii-logging-guard'));

  const cmd = checkCommand('psql -h db.prod -d opslab -c "UPDATE orders SET status = 1"');
  assert('prod-db: 직접 UPDATE 차단', cmd.some(v => v.rule === 'prod-db-guard' && v.severity === 'BLOCK'));

  const cmdOk = checkCommand('git status');
  assert('command: 일반 명령 통과', cmdOk.length === 0);

  // 'updated_at' 컬럼명이 UPDATE 로 오탐되면 안 됨 (settlement-copilot 실전 오탐 회귀 케이스 이식)
  const cmdReadOnly = checkCommand(
    'docker exec pg psql -U u -d opslab -c "SELECT id, updated_at FROM orders WHERE updated_at >= now()"');
  assert('prod-db: 읽기 전용 SELECT(updated_at 포함) 통과',
    !cmdReadOnly.some(v => v.rule === 'prod-db-guard'), JSON.stringify(cmdReadOnly));

  const mig = checkFileContent(
    'order-service/src/main/resources/db/migration/V20260707120000__drop_x.sql',
    'DROP TABLE reviews;\n');
  assert('migration: DROP WARN', mig.some(v => v.rule === 'migration-guard' && v.severity === 'WARN'));

  const migName = checkFileContent(
    'order-service/src/main/resources/db/migration/V2026_bad__x.sql', 'SELECT 1;\n');
  assert('migration: 파일명 규칙 WARN', migName.some(v => v.rule === 'migration-guard'));
}

// ── 3. Installer round-trip (임시 디렉터리 — 실제 환경 불변) ──────────────────
function testInstaller() {
  console.log('[3] Installer (install.mjs codex — 멱등성·보존·제거)');
  const BEGIN = '<!-- fashion-copilot:begin (managed by install-codex.sh - 직접 수정 금지) -->';
  const HEADER = '[mcp_servers.fashion-copilot]';
  const base = fs.mkdtempSync(join(os.tmpdir(), 'fashion-smoke-'));
  const codexHome = join(base, 'codex');
  const repoRoot = join(base, 'repo');
  fs.mkdirSync(codexHome, { recursive: true });
  fs.mkdirSync(repoRoot, { recursive: true });
  // 기존 사용자 설정 시드 — 설치가 이걸 보존해야 한다
  fs.writeFileSync(join(repoRoot, 'AGENTS.md'), '# 기존 규칙\n');
  fs.writeFileSync(join(codexHome, 'config.toml'), '[mcp_servers.other]\ncommand = "x"\n');

  const installer = join(here, '..', 'install.mjs');
  const run = (args) => spawnSync(process.execPath, [installer, ...args,
    `--codex-home=${codexHome}`, `--repo-root=${repoRoot}`], { encoding: 'utf8' });
  const count = (text, needle) => text.split(needle).length - 1;

  try {
    const r1 = run(['codex', '--internal-key=TESTKEY']);
    assert('install: 1회차 성공', r1.status === 0, (r1.stderr ?? '').slice(0, 200));
    const r2 = run(['codex', '--internal-key=TESTKEY']);
    assert('install: 2회차(멱등) 성공', r2.status === 0, (r2.stderr ?? '').slice(0, 200));

    const agents = fs.readFileSync(join(repoRoot, 'AGENTS.md'), 'utf8');
    assert('install: AGENTS.md 마커 1회만 (중복 누적 없음)', count(agents, BEGIN) === 1, `count=${count(agents, BEGIN)}`);
    assert('install: 기존 AGENTS.md 내용 보존', agents.includes('# 기존 규칙'));

    const toml = fs.readFileSync(join(codexHome, 'config.toml'), 'utf8');
    assert('install: config.toml 블록 1회만', count(toml, HEADER) === 1, `count=${count(toml, HEADER)}`);
    assert('install: 타 MCP 서버 블록 보존', toml.includes('[mcp_servers.other]'));
    assert('install: INTERNAL_API_KEY 기록', /INTERNAL_API_KEY = "TESTKEY"/.test(toml));

    assert('install: prompts 복사 (fashion-help)', fs.existsSync(join(codexHome, 'prompts', 'fashion-help.md')));
    assert('install: skills 복사 (size-return-policy)', fs.existsSync(join(codexHome, 'skills', 'size-return-policy', 'SKILL.md')));

    const r3 = run(['uninstall-codex']);
    assert('uninstall: 성공', r3.status === 0, (r3.stderr ?? '').slice(0, 200));
    const agentsAfter = fs.readFileSync(join(repoRoot, 'AGENTS.md'), 'utf8');
    assert('uninstall: 마커 제거 + 기존 내용 보존', !agentsAfter.includes(BEGIN) && agentsAfter.includes('# 기존 규칙'));
    const tomlAfter = fs.readFileSync(join(codexHome, 'config.toml'), 'utf8');
    assert('uninstall: config.toml 블록 제거 + 타 서버 보존',
      !tomlAfter.includes(HEADER) && tomlAfter.includes('[mcp_servers.other]'));
    assert('uninstall: prompts 제거', !fs.existsSync(join(codexHome, 'prompts', 'drop-check.md')));
    assert('uninstall: skills 제거', !fs.existsSync(join(codexHome, 'skills', 'size-return-policy')));
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
}

await testMcp();
testGuards();
testInstaller();

console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
