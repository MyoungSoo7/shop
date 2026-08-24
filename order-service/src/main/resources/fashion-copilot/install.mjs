#!/usr/bin/env node
/**
 * fashion-copilot 단일 진입점 설치/진단 CLI (zero-dependency, Node 18+, 크로스 플랫폼).
 * Windows PowerShell / macOS / Linux 어디서든 동일하게 동작한다 — Git Bash 불필요.
 *
 *   node fashion-copilot/install.mjs <command> [options]
 *
 * commands:
 *   codex             Codex CLI 설치/동기화 (멱등 — 재실행 = 최신 상태로 수렴)
 *   claude            Claude Code 설치 (marketplace add + plugin install, 실패 시 수동 안내)
 *   doctor            설치 상태·연결 진단 (양 플랫폼, 읽기 전용)
 *   smoke             스모크 테스트 실행 (네트워크 불필요)
 *   package           제출물 생성 — logs 채집(smoke/doctor/MCP 세션) + submission.zip (src/+README.md+logs/)
 *   uninstall-codex   Codex 설치 흔적 제거 (AGENTS.md 블록·prompts·skills·config.toml·pre-commit)
 *
 * options:
 *   --codex-home=PATH     기본 $CODEX_HOME 또는 ~/.codex
 *   --repo-root=PATH      기본 플러그인 디렉터리의 부모 (테스트용 오버라이드)
 *   --order-url=URL       config.toml 에 기록할 ORDER_BASE_URL (기본 http://localhost:8088)
 *   --internal-key=KEY    config.toml 에 기록할 INTERNAL_API_KEY (order /internal/recon 용)
 *   --no-live             doctor 에서 order-service 연결 확인 생략
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const PLUGIN_DIR = path.dirname(fileURLToPath(import.meta.url));
const BEGIN_MARK = '<!-- fashion-copilot:begin (managed by install-codex.sh - 직접 수정 금지) -->';
const END_MARK = '<!-- fashion-copilot:end -->';
const CONFIG_HEADER = '[mcp_servers.fashion-copilot]';
const SKILL_NAMES = () => fs.readdirSync(path.join(PLUGIN_DIR, 'skills'));
const COMMAND_FILES = () => fs.readdirSync(path.join(PLUGIN_DIR, 'commands')).filter(f => f.endsWith('.md'));

// ── CLI 파싱 ─────────────────────────────────────────────────────────────────
const [, , cmd, ...rest] = process.argv;
const opts = {};
for (const a of rest) {
  const m = a.match(/^--([a-z-]+)(?:=(.*))?$/);
  if (m) opts[m[1]] = m[2] ?? true;
}
const CODEX_HOME = String(opts['codex-home'] ?? process.env.CODEX_HOME ?? path.join(os.homedir(), '.codex'));
const REPO_ROOT = String(opts['repo-root'] ?? path.dirname(PLUGIN_DIR));
const ORDER_URL = String(opts['order-url'] ?? process.env.ORDER_BASE_URL ?? 'http://localhost:8088');
const INTERNAL_KEY = opts['internal-key'] ? String(opts['internal-key']) : '';

const ok = (m) => console.log(`  ✅ ${m}`);
const warn = (m) => console.log(`  ⚠️  ${m}`);
const bad = (m) => console.log(`  ❌ ${m}`);

// ── 공통 조작 ────────────────────────────────────────────────────────────────
function stripMarkerBlock(text) {
  const lines = text.split('\n');
  const out = [];
  let skip = false;
  for (const l of lines) {
    if (l === BEGIN_MARK) { skip = true; continue; }
    if (l === END_MARK) { skip = false; continue; }
    if (!skip) out.push(l);
  }
  return out.join('\n').replace(/\n+$/, '');
}

function mergeAgentsMd() {
  const target = path.join(REPO_ROOT, 'AGENTS.md');
  const body = fs.existsSync(target) ? stripMarkerBlock(fs.readFileSync(target, 'utf8')) : '';
  const core = fs.readFileSync(path.join(PLUGIN_DIR, 'AGENTS.md'), 'utf8').replace(/\n+$/, '');
  const merged = (body ? body + '\n\n' : '') + BEGIN_MARK + '\n' + core + '\n' + END_MARK + '\n';
  fs.writeFileSync(target, merged);
  return target;
}

function removeAgentsBlock() {
  const target = path.join(REPO_ROOT, 'AGENTS.md');
  if (!fs.existsSync(target)) return false;
  const stripped = stripMarkerBlock(fs.readFileSync(target, 'utf8'));
  if (stripped.trim() === '') fs.rmSync(target);
  else fs.writeFileSync(target, stripped + '\n');
  return true;
}

function serverPathToml() {
  return path.join(PLUGIN_DIR, 'mcp', 'server', 'index.mjs').replace(/\\/g, '/');
}

function stripConfigBlock(text) {
  const lines = text.split('\n');
  const start = lines.findIndex(l => l.trim() === CONFIG_HEADER);
  if (start < 0) return text;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (lines[i].trim().startsWith('[')) { end = i; break; }
  }
  const out = [...lines.slice(0, start), ...lines.slice(end)];
  return out.join('\n').replace(/\n{3,}/g, '\n\n');
}

function upsertConfigToml() {
  const p = path.join(CODEX_HOME, 'config.toml');
  fs.mkdirSync(CODEX_HOME, { recursive: true });
  let text = fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '';
  const hadBlock = text.includes(CONFIG_HEADER);
  text = stripConfigBlock(text).replace(/\n+$/, '');
  const env = [`ORDER_BASE_URL = "${ORDER_URL}"`];
  if (INTERNAL_KEY) env.push(`INTERNAL_API_KEY = "${INTERNAL_KEY}"`);
  const block = [
    CONFIG_HEADER,
    'command = "node"',
    `args = ["${serverPathToml()}"]`,
    `env = { ${env.join(', ')} }`,
  ].join('\n');
  fs.writeFileSync(p, (text ? text + '\n\n' : '') + block + '\n');
  return { path: p, replaced: hadBlock };
}

function removeConfigBlock() {
  const p = path.join(CODEX_HOME, 'config.toml');
  if (!fs.existsSync(p)) return false;
  const text = fs.readFileSync(p, 'utf8');
  if (!text.includes(CONFIG_HEADER)) return false;
  fs.writeFileSync(p, stripConfigBlock(text).replace(/\n+$/, '') + '\n');
  return true;
}

function copyPrompts() {
  const dest = path.join(CODEX_HOME, 'prompts');
  fs.mkdirSync(dest, { recursive: true });
  for (const f of COMMAND_FILES()) {
    fs.copyFileSync(path.join(PLUGIN_DIR, 'commands', f), path.join(dest, f));
  }
  return dest;
}

function copySkills() {
  const dest = path.join(CODEX_HOME, 'skills');
  fs.mkdirSync(dest, { recursive: true });
  fs.cpSync(path.join(PLUGIN_DIR, 'skills'), dest, { recursive: true, force: true });
  return dest;
}

function gitHooksDir() {
  const r = spawnSync('git', ['-C', REPO_ROOT, 'rev-parse', '--git-path', 'hooks'], { encoding: 'utf8' });
  if (r.status !== 0) return null;
  const p = r.stdout.trim();
  return path.isAbsolute(p) ? p : path.join(REPO_ROOT, p);
}

function installPreCommit() {
  const hooks = gitHooksDir();
  if (!hooks) return { skipped: 'git 저장소가 아님' };
  fs.mkdirSync(hooks, { recursive: true });
  const hookFile = path.join(hooks, 'pre-commit');
  const relGuard = path.relative(REPO_ROOT, path.join(PLUGIN_DIR, 'hooks', 'guards', 'pre-commit.mjs')).replace(/\\/g, '/');
  const guardLine = `node "${relGuard}" || exit 1   # fashion-copilot guard`;
  let text = fs.existsSync(hookFile) ? fs.readFileSync(hookFile, 'utf8') : '#!/bin/sh\n';
  if (text.includes('fashion-copilot')) return { path: hookFile, already: true };
  fs.writeFileSync(hookFile, text.replace(/\n+$/, '\n') + guardLine + '\n');
  try { fs.chmodSync(hookFile, 0o755); } catch { /* Windows: 무시 */ }
  return { path: hookFile };
}

function removePreCommit() {
  const hooks = gitHooksDir();
  if (!hooks) return false;
  const hookFile = path.join(hooks, 'pre-commit');
  if (!fs.existsSync(hookFile)) return false;
  const lines = fs.readFileSync(hookFile, 'utf8').split('\n').filter(l => !l.includes('fashion-copilot'));
  fs.writeFileSync(hookFile, lines.join('\n'));
  return true;
}

// ── MCP 왕복 (doctor 용) ─────────────────────────────────────────────────────
function mcpRoundTrip() {
  return new Promise((resolve) => {
    const server = spawn(process.execPath, [path.join(PLUGIN_DIR, 'mcp', 'server', 'index.mjs')], {
      stdio: ['pipe', 'pipe', 'ignore'],
    });
    const responses = [];
    let buf = '';
    server.stdout.on('data', d => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (line) { try { responses.push(JSON.parse(line)); } catch { /* skip */ } }
      }
    });
    const send = m => server.stdin.write(JSON.stringify(m) + '\n');
    send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'doctor', version: '0' } } });
    send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
    setTimeout(() => {
      server.kill();
      const init = responses.find(r => r.id === 1);
      const list = responses.find(r => r.id === 2);
      resolve({
        name: init?.result?.serverInfo?.name,
        tools: (list?.result?.tools ?? []).map(t => t.name),
      });
    }, 1200);
  });
}

// ── commands ─────────────────────────────────────────────────────────────────
async function cmdCodex() {
  console.log('fashion-copilot → Codex CLI 설치 (멱등)');
  ok(`AGENTS.md 병합 → ${mergeAgentsMd()}`);
  ok(`commands → ${copyPrompts()} (${COMMAND_FILES().length}개 — /drop-check, /return-audit, /coupon-audit, /review-scan, /fashion-help)`);
  ok(`skills → ${copySkills()} (${SKILL_NAMES().length}개)`);
  const toml = upsertConfigToml();
  ok(`MCP 서버 ${toml.replaced ? '갱신' : '등록'} → ${toml.path}${INTERNAL_KEY ? ' (INTERNAL_API_KEY 포함)' : ''}`);
  const hook = installPreCommit();
  if (hook.skipped) warn(`pre-commit 가드 건너뜀 — ${hook.skipped}`);
  else ok(`pre-commit 가드 ${hook.already ? '이미 설치됨' : '설치'} → ${hook.path}`);
  console.log('\n완료. Codex CLI 를 재시작하면 /fashion-help 로 시작할 수 있습니다.');
  if (!INTERNAL_KEY) console.log('운영 대사 API(refund_recon)를 쓰려면: node install.mjs codex --internal-key=<KEY> 로 재실행 (멱등 갱신)');
}

function cmdClaude() {
  console.log('fashion-copilot → Claude Code 설치');
  const shell = process.platform === 'win32';
  const add = spawnSync('claude', ['plugin', 'marketplace', 'add', PLUGIN_DIR], { encoding: 'utf8', shell });
  if (add.status === 0) {
    ok('marketplace 등록');
    const ins = spawnSync('claude', ['plugin', 'install', 'fashion-copilot@fashion-copilot'], { encoding: 'utf8', shell });
    if (ins.status === 0) {
      ok('플러그인 설치 — Claude Code 재시작 후 /fashion-copilot:fashion-help 로 시작하세요.');
      return;
    }
    warn(`plugin install 실패: ${(ins.stderr || ins.stdout || '').trim().slice(0, 200)}`);
  } else {
    warn(`claude CLI 실행 실패${add.error ? ` (${add.error.message})` : ''} — 수동 설치 안내로 전환`);
  }
  console.log(`
수동 설치 (둘 중 하나):
  1) Claude Code 세션에서:
       /plugin marketplace add ${PLUGIN_DIR}
       /plugin install fashion-copilot@fashion-copilot
  2) 터미널에서:
       claude plugin marketplace add "${PLUGIN_DIR}"
       claude plugin install fashion-copilot@fashion-copilot
설치 후 재시작하면 commands/skills/hooks/MCP 가 자동 등록됩니다.`);
}

async function cmdDoctor() {
  console.log(`fashion-copilot doctor — 설치 상태 진단 (읽기 전용)\n`);
  let coreFail = 0;

  // [1] 공통 — 플러그인 자체 무결성
  console.log('[1] 플러그인 무결성');
  const nodeMajor = Number(process.versions.node.split('.')[0]);
  nodeMajor >= 18 ? ok(`Node ${process.versions.node} (>=18)`) : (bad(`Node ${process.versions.node} — 18+ 필요`), coreFail++);
  for (const f of ['.claude-plugin/plugin.json', '.claude-plugin/marketplace.json', '.codex-plugin/plugin.json', '.mcp.json', 'hooks/hooks.json']) {
    try { JSON.parse(fs.readFileSync(path.join(PLUGIN_DIR, f), 'utf8')); ok(`${f} 파싱`); }
    catch (e) { bad(`${f} — ${e.message}`); coreFail++; }
  }
  const skills = SKILL_NAMES();
  skills.length >= 4 ? ok(`skills ${skills.length}종`) : (bad(`skills ${skills.length}종 — 4종 이상 기대`), coreFail++);
  const rt = await mcpRoundTrip();
  rt.name === 'fashion-copilot' && rt.tools.length === 5
    ? ok(`MCP 서버 왕복 — 도구 5종 (${rt.tools.join(', ')})`)
    : (bad(`MCP 서버 왕복 실패 — name=${rt.name}, tools=${rt.tools.length}`), coreFail++);
  try {
    const { checkFileContent } = await import('./hooks/guards/rules.mjs');
    const hit = checkFileContent('order-service/src/main/java/x/C.java', 'coupon.setUsedCount(1);\n');
    hit.some(v => v.severity === 'BLOCK') ? ok('가드 규칙 동작 (coupon-usage BLOCK)') : (bad('가드 규칙 미동작'), coreFail++);
  } catch (e) { bad(`가드 로드 실패 — ${e.message}`); coreFail++; }

  // [2] Codex CLI 설치 상태
  console.log(`\n[2] Codex CLI (CODEX_HOME=${CODEX_HOME})`);
  if (!fs.existsSync(CODEX_HOME)) {
    warn('CODEX_HOME 없음 — Codex 미사용이면 무시, 사용하려면: node install.mjs codex');
  } else {
    const toml = path.join(CODEX_HOME, 'config.toml');
    fs.existsSync(toml) && fs.readFileSync(toml, 'utf8').includes(CONFIG_HEADER)
      ? ok('config.toml MCP 등록') : warn('config.toml 에 MCP 미등록 → node install.mjs codex');
    let stale = 0, missing = 0;
    for (const f of COMMAND_FILES()) {
      const dst = path.join(CODEX_HOME, 'prompts', f);
      if (!fs.existsSync(dst)) missing++;
      else if (fs.readFileSync(dst, 'utf8') !== fs.readFileSync(path.join(PLUGIN_DIR, 'commands', f), 'utf8')) stale++;
    }
    for (const s of skills) {
      const dst = path.join(CODEX_HOME, 'skills', s, 'SKILL.md');
      if (!fs.existsSync(dst)) missing++;
      else if (fs.readFileSync(dst, 'utf8') !== fs.readFileSync(path.join(PLUGIN_DIR, 'skills', s, 'SKILL.md'), 'utf8')) stale++;
    }
    if (missing === 0 && stale === 0) ok('prompts/skills 최신 동기화');
    else warn(`prompts/skills 누락 ${missing}·구버전 ${stale} → node install.mjs codex 재실행`);
    const agents = path.join(REPO_ROOT, 'AGENTS.md');
    fs.existsSync(agents) && fs.readFileSync(agents, 'utf8').includes(BEGIN_MARK)
      ? ok('AGENTS.md 코어 규칙 병합됨') : warn('AGENTS.md 마커 없음 → node install.mjs codex');
    const hooks = gitHooksDir();
    hooks && fs.existsSync(path.join(hooks, 'pre-commit'))
      && fs.readFileSync(path.join(hooks, 'pre-commit'), 'utf8').includes('fashion-copilot')
      ? ok('git pre-commit 가드 설치됨') : warn('pre-commit 가드 없음 (Codex 는 훅이 없어 이것이 유일한 가드)');
  }

  // [3] Claude Code
  console.log('\n[3] Claude Code');
  const shell = process.platform === 'win32';
  const cv = spawnSync('claude', ['--version'], { encoding: 'utf8', shell });
  cv.status === 0 ? ok(`claude CLI ${String(cv.stdout).trim()}`) : warn('claude CLI 미발견 — 설치는 node install.mjs claude 안내 참조');

  // [4] 런타임 연결 (선택)
  console.log('\n[4] 런타임 연결');
  if (opts['no-live']) {
    warn('--no-live — 연결 확인 생략');
  } else {
    try {
      const res = await fetch(`${ORDER_URL}/actuator/health`, { signal: AbortSignal.timeout(3000) });
      res.ok ? ok(`order-service 응답 (${ORDER_URL})`) : warn(`order-service HTTP ${res.status} (${ORDER_URL})`);
    } catch {
      warn(`order-service 미응답 (${ORDER_URL}) — coupon/refund_simulate·가드는 오프라인에서도 동작`);
    }
    const tomlText = fs.existsSync(path.join(CODEX_HOME, 'config.toml'))
      ? fs.readFileSync(path.join(CODEX_HOME, 'config.toml'), 'utf8') : '';
    process.env.INTERNAL_API_KEY || /INTERNAL_API_KEY\s*=\s*"[^"]+"/.test(tomlText)
      ? ok('INTERNAL_API_KEY 설정됨')
      : warn('INTERNAL_API_KEY 미설정 — refund_recon 사용 시 필요: node install.mjs codex --internal-key=<KEY> 또는 환경변수');
  }

  console.log(coreFail === 0 ? '\n진단 완료 — 핵심 항목 정상 (⚠️ 는 선택 사항)' : `\n진단 완료 — 핵심 실패 ${coreFail}건`);
  process.exit(coreFail === 0 ? 0 : 1);
}

function cmdSmoke() {
  const r = spawnSync(process.execPath, [path.join(PLUGIN_DIR, 'test', 'smoke.mjs')], { stdio: 'inherit' });
  process.exit(r.status ?? 1);
}

// ── package — 제출물(submission.zip) 생성 ────────────────────────────────────
// 레이아웃(제출 스펙): src/(플러그인 전체) + README.md + logs/(실행 증적)
function mcpTranscript() {
  return new Promise((resolve) => {
    const server = spawn(process.execPath, [path.join(PLUGIN_DIR, 'mcp', 'server', 'index.mjs')], {
      stdio: ['pipe', 'pipe', 'ignore'],
    });
    const log = [];
    let buf = '';
    server.stdout.on('data', d => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (line) log.push(`← ${line}`);
      }
    });
    const send = (m) => { log.push(`→ ${JSON.stringify(m)}`); server.stdin.write(JSON.stringify(m) + '\n'); };
    send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'package-log', version: '0' } } });
    send({ jsonrpc: '2.0', method: 'notifications/initialized' });
    send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
    send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'coupon_simulate', arguments: { type: 'PERCENTAGE', discountValue: '10', orderAmount: '33333', refundAmount: '10000' } } });
    send({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'refund_simulate', arguments: { paymentAmount: '50000', refundedAmount: '20000', paymentId: 'PAY1' } } });
    setTimeout(() => { server.kill(); resolve(log.join('\n') + '\n'); }, 1500);
  });
}

async function cmdPackage() {
  console.log('fashion-copilot → 제출물 패키징');
  const outPath = String(opts.out ?? path.join(PLUGIN_DIR, 'dist', 'submission.zip'));
  const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'fashion-pkg-'));
  try {
    // 1) src/ — 플러그인 전체 (산출물·로그 제외)
    const EXCLUDE = new Set(['dist', 'logs', 'node_modules']);
    fs.cpSync(PLUGIN_DIR, path.join(staging, 'src'), {
      recursive: true,
      filter: (src) => !EXCLUDE.has(path.basename(src)) || path.dirname(src) !== PLUGIN_DIR,
    });
    ok('src/ ← 플러그인 전체 (.codex-plugin/.claude-plugin/skills/.mcp.json/실행 코드)');

    // 2) README.md — zip 루트
    fs.copyFileSync(path.join(PLUGIN_DIR, 'README.md'), path.join(staging, 'README.md'));
    ok('README.md ← 루트 배치');

    // 3) logs/ — 실행 증적 (smoke 47어서션·doctor 진단·MCP 세션 왕복)
    const logsDir = path.join(staging, 'logs');
    fs.mkdirSync(logsDir, { recursive: true });
    const stamp = new Date().toISOString();
    const smoke = spawnSync(process.execPath, [path.join(PLUGIN_DIR, 'test', 'smoke.mjs')], { encoding: 'utf8' });
    fs.writeFileSync(path.join(logsDir, 'smoke.log'),
      `# ${stamp} — node test/smoke.mjs (exit ${smoke.status})\n${smoke.stdout ?? ''}${smoke.stderr ?? ''}`);
    if (smoke.status !== 0) { bad('smoke 실패 — 패키징 중단 (증적이 GREEN 이어야 제출 가능)'); process.exit(1); }
    ok('logs/smoke.log (ALL GREEN)');
    const doctor = spawnSync(process.execPath, [fileURLToPath(import.meta.url), 'doctor'], { encoding: 'utf8' });
    fs.writeFileSync(path.join(logsDir, 'doctor.log'),
      `# ${stamp} — node install.mjs doctor (exit ${doctor.status})\n${doctor.stdout ?? ''}${doctor.stderr ?? ''}`);
    ok('logs/doctor.log');
    fs.writeFileSync(path.join(logsDir, 'mcp-session.log'),
      `# ${stamp} — MCP stdio 세션 왕복 (initialize/tools/list/coupon_simulate/refund_simulate)\n${await mcpTranscript()}`);
    ok('logs/mcp-session.log');

    // 4) zip
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.rmSync(outPath, { force: true });
    const zip = process.platform === 'win32'
      ? spawnSync('powershell.exe', ['-NoProfile', '-Command',
          `Compress-Archive -Path "${staging}\\*" -DestinationPath "${outPath}" -Force`], { encoding: 'utf8' })
      : spawnSync('zip', ['-rq', outPath, '.'], { cwd: staging, encoding: 'utf8' });
    if (zip.status !== 0) {
      bad(`zip 생성 실패: ${(zip.stderr || zip.stdout || zip.error?.message || '').trim().slice(0, 300)}`);
      process.exit(1);
    }
    const size = fs.statSync(outPath).size;
    ok(`submission.zip 생성 → ${outPath} (${(size / 1024).toFixed(1)} KB)`);
    console.log('\n구조: src/(.codex-plugin·.claude-plugin·skills·.mcp.json·실행코드) + README.md + logs/(smoke·doctor·mcp-session)');
  } finally {
    fs.rmSync(staging, { recursive: true, force: true });
  }
}

function cmdUninstallCodex() {
  console.log('fashion-copilot → Codex 설치 제거');
  removeAgentsBlock() ? ok('AGENTS.md 마커 블록 제거') : warn('AGENTS.md 없음/블록 없음');
  let removed = 0;
  for (const f of COMMAND_FILES()) {
    const p = path.join(CODEX_HOME, 'prompts', f);
    if (fs.existsSync(p)) { fs.rmSync(p); removed++; }
  }
  ok(`prompts 제거 (${removed}개)`);
  removed = 0;
  for (const s of SKILL_NAMES()) {
    const p = path.join(CODEX_HOME, 'skills', s);
    if (fs.existsSync(p)) { fs.rmSync(p, { recursive: true }); removed++; }
  }
  ok(`skills 제거 (${removed}개)`);
  removeConfigBlock() ? ok('config.toml MCP 블록 제거') : warn('config.toml 블록 없음');
  removePreCommit() ? ok('pre-commit 가드 라인 제거') : warn('pre-commit 라인 없음');
  console.log('\n제거 완료 (다른 플러그인의 파일은 건드리지 않음).');
}

// ── dispatch ─────────────────────────────────────────────────────────────────
const COMMANDS = {
  codex: cmdCodex,
  claude: cmdClaude,
  doctor: cmdDoctor,
  smoke: cmdSmoke,
  package: cmdPackage,
  'uninstall-codex': cmdUninstallCodex,
};

if (!cmd || !COMMANDS[cmd]) {
  console.log(`fashion-copilot installer — 사용법:

  node fashion-copilot/install.mjs codex             Codex CLI 설치/동기화 (멱등)
  node fashion-copilot/install.mjs claude            Claude Code 설치 (자동 시도 + 수동 안내)
  node fashion-copilot/install.mjs doctor            설치·연결 진단 (읽기 전용)
  node fashion-copilot/install.mjs smoke             스모크 테스트 (네트워크 불필요)
  node fashion-copilot/install.mjs package           제출물 생성 (logs 채집 + submission.zip)
  node fashion-copilot/install.mjs uninstall-codex   Codex 설치 제거

  옵션: --codex-home= --repo-root= --order-url= --internal-key= --no-live --out=`);
  process.exit(cmd ? 1 : 0);
}
await COMMANDS[cmd]();
