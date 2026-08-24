#!/usr/bin/env node
// Lemuel harness guard — PLUGIN-INDEPENDENT, repo-tracked core invariant enforcement.
//
// Why this exists: the settlement-copilot / invest-copilot plugin guards live outside the build
// graph (service `src/main/resources/` — jar-excluded) and are not
// wired into CI on a fresh clone. This
// script re-implements the *non-negotiable* money/architecture invariants with zero external
// dependency so the guard survives plugin relocation and works in CI. (See HARNESS.md
// "하드스톱" — this is the machine-enforced subset of that section.)
//
// Modes:
//   node scripts/harness/guard.mjs --staged        # scan git staged files (pre-commit / CI)
//   node scripts/harness/guard.mjs --files a.java b.sql
//   node scripts/harness/guard.mjs --hook          # Claude Code PreToolUse (reads JSON on stdin)
//
// Exit 0 = clean, Exit 1 = blocking violation(s). Exceptions require structured metadata.

import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { readFile, realpath, stat } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { appendJsonl, logGuardHits, logGuardRun } from './telemetry.mjs';

const ALLOW = /harness-guard:\s*allow/i;
const ALLOWANCE = /harness-guard:\s*allow\s+reason="([^"]*)"\s+issue="([^"]*)"\s+owner="([^"]*)"\s+expires="([^"]*)"\s*$/i;
const ISSUE = /^(ISSUE-\d+|https:\/\/github\.com\/[^/\s]+\/[^/\s]+\/issues\/\d+)$/;
const OWNER = /^team-[a-z0-9]+(?:-[a-z0-9]+)*$/;

const POLICY_ROOT = /(?:^|\/)((?:hackathon|pwc|settlement-service|finance-service|external-data-service)\/.*)$/;

function policyPath(filePath) {
  const normalized = String(filePath).replaceAll('\\', '/');
  return normalized.match(POLICY_ROOT)?.[1] ?? normalized;
}

// Money-scope = files where BigDecimal / immutable-history rules are non-negotiable.
// `point` 는 단어 경계로 묶는다 — 그러지 않으면 endpoint/checkpoint 같은 경로가 전부 금액 스코프로
// 잡혀 오탐이 된다. 포인트 원장은 고객 부채라 BigDecimal 강제가 다른 돈 경로와 동일하게 적용된다.
const MONEY_SCOPE = /(settlement|ledger|payout|chargeback|loan|payment|investment|account|insurance|pgreconciliation|recon|\bpoint\b)/i;
// ai 슬라이스는 금액 스코프가 아니다 (ADR 0040). MONEY_SCOPE 는 **경로 문자열**로 판정하는데,
// 흡수로 ai 파일이 settlement-service/ 아래에 들어오면서 경로에 'settlement' 가 섞여 전부 금액
// 스코프가 됐다 — 임베딩 벡터의 float[]·코사인 유사도 12건이 "금액에 double 금지"로 차단됐다.
// 이것은 card 처럼 유예할 부채가 아니라 **오탐**이다: ai 슬라이스에는 금액 개념이 아예 없고,
// 768차원 벡터를 BigDecimal 로 다루라는 요구는 규칙의 의도가 아니다. 그래서 부채로 남기지 않고
// 스코프를 정정한다(ADR 0039 3단계-a 의 "제외가 아니라 범위 정정" 계열).
// ※ ai 슬라이스가 언젠가 금액을 다루게 되면(예: LLM 과금 원장) 이 제외를 지워야 한다.
const AI_SLICE = /github\/lms\/lemuel\/ai\//;
const inMoneyScope = (f) => MONEY_SCOPE.test(f) && !AI_SLICE.test(f);
const JAVA_KT = /\.(java|kt)$/i;
const SQL = /\.sql$/i;
const WORKFLOW_YAML = /(^|\/)\.github\/workflows\/[^/]+\.ya?ml$/i;
// Money math lives in domain/application; adapters legitimately use double for
// Micrometer gauges, pagination, mock probabilities, JDBC — scope the primitive
// rule to core layers and exclude test sources to keep the gate false-positive free.
const isCore = (f) => /\/(domain|application)\//.test(f);
const isProd = (f) => !/\/src\/test\//.test(f);

// OO invariants — 2026-07-14 OO 캠페인(패널 중앙값 9.5+)을 만든 구조의 회귀 방지.
// 도메인 프로덕션 소스만 대상. common/audit(감사 인프라 DTO)은 3회 패널 모두 대상 밖 판정.
const isDomainMain = (f) => JAVA_KT.test(f) && /\/src\/main\/java\/.+\/domain\//.test(f) && !/common\/audit\//.test(f);
// generic 예외 금지는 캠페인이 청정화를 완료한 5개 금융 서비스에 한정(위성 서비스는 oo-score 스킬로 채점).
// deposit 추가(2026-08-13): 셀러 예치금 원장은 돈 경로인데 목록에서 빠져 있어, 도메인이 generic
// IAE 를 던져도 차단되지 않았다(역산에서 발견). 타입 예외 전환과 함께 대상에 편입했다.
// 2026-08-24(ADR 0039 1단계): deposit-service 가 settlement-service 로 흡수되면서 별도 항목이
// 불필요해졌다 — deposit 파일은 이제 settlement-service/ 아래라 첫 항목이 그대로 커버한다.
// 2026-08-24(ADR 0039 3단계): loan·investment 는 finance-service 로 흡수됐다. 경로가 바뀌면
// 이 정규식이 두 도메인을 못 보게 되므로 finance 를 추가한다 — 흡수가 가드 사정권을 조용히
// 좁히는 경로다. loan|investment 항목은 남겨 둘 이유가 없어 finance 로 대체한다.
//
// ★ card 슬라이스는 제외한다(3단계-b, 2026-08-24). 이 규칙의 대상은 "캠페인이 청정화를 완료한"
//   서비스이고 card-service 는 그 대상이 아니었다 — 흡수로 finance-service/ 아래에 들어오면서
//   **우연히** 사정권에 들어와 기존 generic IAE 36건이 한꺼번에 차단됐다. 흡수와 무관한 부채를
//   흡수 커밋이 떠안으면 두 변경이 뒤섞이므로, 제외를 명시하고 부채로 드러낸다.
//   ※ 이것은 면제가 아니라 **기한 없는 유예의 기록**이다. card 도메인을 타입 예외로 청정화하면
//     이 예외를 지워야 하고, 그때 36건이 0 이어야 한다(ADR 0039 후속 과제).
//
// ★ ai 슬라이스도 같은 이유로 제외한다(ADR 0040, 2026-08-25). ai-service 는 캠페인 대상이 아니었고,
//   settlement-service/ 아래로 흡수되면서 우연히 사정권에 들어와 기존 generic IAE 33건이 한꺼번에
//   차단됐다(chat 13 + rag 20). card 와 같은 판단 — 흡수와 무관한 부채를 흡수 커밋이 떠안지 않는다.
//   ※ 면제가 아니라 **기한 없는 유예의 기록**이다. ai 도메인을 타입 예외로 청정화하면 이 예외를
//     지워야 하고, 그때 33건이 0 이어야 한다(ADR 0040 후속 과제).
const CAMPAIGN_SERVICES = /order-service\//;

// settlement-service 가 import 해도 되는 자기 바운디드 컨텍스트(+shared-common `common`).
// 이 집합의 여집합(order 컨텍스트: order/user/cart/product/coupon/shipping/payment/review/game/category/menu/rbac…)은
// MSA 경계 위반. enum denylist 는 신규 order 도메인 누락에 취약하므로 allowlist 여집합으로 강제한다(감사 MED-2).
const SETTLEMENT_OWN_PACKAGES = new Set([
  'settlement', 'payout', 'ledger', 'chargeback', 'pgreconciliation',
  'recon', 'recovery', 'report', 'tax', 'idempotency', 'integrity', 'closing',
  // crypto: 바운디드 컨텍스트가 아니라 슬라이스 공용 인프라(필드 암호화 컨버터).
  // payout 어댑터 안에 있던 것을 tax 엔티티도 쓰면서 슬라이스 경계를 뚫어, 소유 슬라이스가 없는 곳으로 뺐다.
  'crypto',
  // deposit: ADR 0039 1단계로 deposit-service 에서 흡수된 셀러 예치금 원장 슬라이스.
  // 흡수 전에는 별도 모듈이라 이 규칙의 사정권 밖이었고, 옮기자마자 자기 패키지 import 141건이
  // MSA 경계 위반으로 차단됐다(실측). 예치금은 settlement 가 소유하는 컨텍스트이므로 허용한다.
  // ※ 이것이 경계를 푸는 것은 아니다 — deposit → settlement 방향 의존은 DepositArchitectureTest 가
  //   여전히 금지한다. 이 허용목록은 "settlement-service 모듈이 자기 것을 import 해도 되는가"만 답한다.
  'deposit',
  // ai: ADR 0040 으로 ai-service 에서 흡수된 AI 챗봇·RAG 슬라이스. deposit 과 같은 이유로 허용한다 —
  // 옮기자마자 자기 패키지 import 가 전부 MSA 경계 위반으로 차단되기 때문이다.
  // ※ 경계를 푸는 것이 아니다. ai → settlement 방향 의존은 AiArchitectureTest 가 계속 금지한다.
  'ai',
  'common',
]);

export const RULES = [
  {
    id: 'MONEY-PRIMITIVE',
    // double/float primitive or parse in money-scope core java/kt → must use BigDecimal.
    // 선언·파라미터·반환타입·배열·캐스트·var 더블 리터럴 추론까지 커버(우회면 봉쇄).
    when: (f) => JAVA_KT.test(f) && inMoneyScope(f) && isCore(f) && isProd(f),
    test: (line) =>
      /\b(double|float)\s+\w+\s*[=;,)(]/.test(line) ||
      /\b(double|float)\s*\[\]/.test(line) ||
      /\b(Double|Float)\.parse(Double|Float)\s*\(/.test(line) ||
      /\(\s*(double|float)\s*\)/.test(line) || // cast to double/float
      /\bvar\s+\w+\s*=\s*-?\d[\d_]*\.\d/.test(line), // var fee = 0.035;
    // 개행 분할 우회(Double\n.parseDouble) 차단 — 파일 단위 멀티라인 스캔.
    fileTest: /\b(?:Double|Float)\s*\.\s*parse(?:Double|Float)\s*\(/g,
    msg: '금액 스코프에서 double/float 사용 금지 → BigDecimal 사용 (money-safety)',
  },
  {
    id: 'MONEY-BIGDECIMAL-DOUBLE',
    // new BigDecimal(0.1) 은 이진 부동소수 정밀도 손실을 그대로 흡수한다 → 문자열 생성자만.
    when: (f) => JAVA_KT.test(f) && inMoneyScope(f) && isCore(f) && isProd(f),
    fileTest: /new\s+BigDecimal\s*\(\s*-?\d[\d_]*\.\d/g,
    msg: 'new BigDecimal(더블 리터럴) 금지 → new BigDecimal("0.1") 문자열 생성자 (money-safety, 정밀도 손실)',
  },
  {
    id: 'IMMUTABLE-HISTORY',
    // UPDATE/DELETE on append-only ledgers (SQL always; java prod only — test fixtures may seed).
    //
    // 대상은 이 저장소가 실제로 소유한 append-only 원장이다 — 포인트·상품권.
    // 잔액은 계산 결과지 저장값이 아니고, 로트는 되살리지 않는다(EXPIRED·REVOKED 를 ACTIVE 로
    // 되돌리는 대신 신규 로트를 발급한다 — 역분개 원칙). 소비 이력도 같다.
    //
    // 모놀리스 시절의 settlements·ledger_entries·payouts 는 여기서 제외한다: 그 테이블들은
    // V20260820110000 이 폐기했고 남은 것은 **동결된 마이그레이션 이력**뿐이라 고칠 수 없다
    // (고치면 Flyway 체크섬이 깨진다). 없는 테이블을 지키느라 커밋이 막히는 것이 규칙의 뜻은 아니다.
    when: (f) => SQL.test(f) || (JAVA_KT.test(f) && inMoneyScope(f) && isProd(f)),
    test: (line) =>
      /\b(UPDATE|DELETE\s+FROM)\s+(point_lots|point_entries|point_lot_consumptions|gift_card_entries)\b/i.test(line),
    msg: '포인트·상품권 원장 레코드 UPDATE·DELETE 금지 → 신규 로트·엔트리 추가로 표현 (immutable history)',
  },
  {
    id: 'MSA-BOUNDARY',
    // settlement-service 는 자기 컨텍스트 + shared-common 외의 github.lms.lemuel.* 를 import 하지 않는다.
    // order 도메인 전부(payment·review·game·category·menu·rbac 등 신규 포함) 차단 — allowlist 여집합이라
    // denylist 나열 누락 함정이 없다(감사 MED-2). settlement 자체 `recon`(OrderReconClient)은 HTTP 대사라 허용.
    when: (f) => JAVA_KT.test(f) && /settlement-service\//.test(f),
    test: (line) => {
      // `import static github.lms.lemuel.order...` 도 잡는다 — static 키워드가 사이에 껴도 우회 못 하게(#7).
      const m = /^\s*import\s+(?:static\s+)?github\.lms\.lemuel\.([a-z0-9_]+)\b/.exec(line);
      return m != null && !SETTLEMENT_OWN_PACKAGES.has(m[1]);
    },
    msg: 'settlement-service 가 타 컨텍스트(order 등) import 금지 → Kafka 프로젝션/내부 대사 API 만 (ADR 0020)',
  },
  {
    id: 'ACCOUNT-CONSUME-ONLY',
    // 계정계(GL)는 소비 전용이다 — 발행하는 순간 "기록되는 쪽"과 "기록하는 쪽"이 한 몸이 된다.
    //
    // ★ 이 규칙의 무게가 ADR 0039 3단계-b 로 커졌다. 흡수 전 account-service 는 @ComponentScan 으로
    //   common.outbox.{adapter.out,adapter.in.web,application} 을 **아예 배제**해, 발행 포트가 컨텍스트에
    //   존재하지 않는 것으로 통제를 강제했다(주입하려 해도 빈이 없음). finance-service 는 loan·investment 가
    //   Outbox 로 발행하므로 전역 스캔이고, 그 빈 수준 방어가 사라졌다. 이제 통제는 이 정적 가드 +
    //   FinanceArchitectureTest 의 account 슬라이스 규칙 둘이 전부다 — 어느 하나도 끄면 안 된다.
    when: (f) => JAVA_KT.test(f)
      && (/account-service\//.test(f) || /finance-service\/.*\/account\//.test(f))
      && isProd(f),
    test: (line) => /\bkafkaTemplate\.\s*send\s*\(/.test(line) || /SaveOutboxEventPort/.test(line),
    msg: '계정계(account 슬라이스)는 소비 전용 → 이벤트 발행 금지 (기록되는 쪽과 기록하는 쪽의 분리)',
  },
  {
    id: 'MARKET-NO-VALUATION',
    // market 도메인은 PER/PBR 을 계산하지 않는다 (시세·시총만 서빙).
    // ADR 0038 통합으로 경로가 market-service/ → external-data-service/**/market/ 로 바뀌었다.
    when: (f) => JAVA_KT.test(f) && /external-data-service\/.*\/market\//.test(f) && isProd(f),
    test: (line) => /\b(PER|PBR|priceEarnings|priceToBook)\b/.test(line) && /=/.test(line),
    msg: 'market 도메인은 PER/PBR 계산 금지 → 시세·시총만 서빙 (밸류에이션 조인은 소비측)',
  },
  {
    id: 'OO-DOMAIN-SETTER',
    // 도메인 public setter → 상태머신·불변식 우회 경로. 재구성은 rehydrate/팩토리로.
    when: isDomainMain,
    test: (line) => /public\s+void\s+set[A-Z]\w*\s*\(/.test(line),
    msg: '도메인 public setter 금지 → rehydrate/생성 팩토리 + 의미 있는 도메인 메서드 (OO 게이트, oo-score 스킬)',
  },
  {
    id: 'OO-DOMAIN-MUTABLE-LOMBOK',
    // @Setter/@Data 는 컴파일 타임에 setter 를 생성해 grep 기반 봉인을 우회한다. @Getter 는 허용.
    when: isDomainMain,
    test: (line) => /@(Setter|Data)\b/.test(line),
    msg: '도메인 @Setter/@Data 금지 — Lombok 생성 setter 는 캡슐화 우회 (@Getter 는 허용)',
  },
  {
    id: 'OO-DOMAIN-GENERIC-IAE',
    // 도메인 규칙 위반은 타입 도메인 예외(InvariantViolation/InvalidState 계열)로. write-once
    // 인프라 가드는 IllegalStateException 을 쓰므로 이 규칙과 충돌하지 않는다.
    when: (f) => isDomainMain(f) && CAMPAIGN_SERVICES.test(f),
    test: (line) => /throw\s+new\s+IllegalArgumentException\s*\(/.test(line),
    msg: '금융 5서비스 도메인에서 generic IllegalArgumentException throw 금지 → 타입 도메인 예외 (OO 게이트)',
  },
  {
    id: 'WORKFLOW-EMPTY-EXPR',
    // Actions 는 워크플로 전체를 표현식 렉서로 훑는다 — run/script 블록 안의 주석까지 포함해서.
    // 빈 표현식이 하나라도 있으면 "(Line: N, Col: M): An expression was expected" 로 **파일이
    // 통째로 무효**가 되고, 그 워크플로는 잡 0개·로그 없음·실행 이름이 파일 경로로 뜨는 형태로
    // 죽는다. 다른 체크는 초록이라 며칠간 아무도 모른다(2026-08 pr-review.yml 실측).
    // 이 계층에서만 잡힌다: YAML 파서·공식 워크플로 스키마·액션 SHA 검증은 모두 통과한다.
    // \s* 라서 개행으로 쪼갠 표기도 함께 막는다.
    when: (f) => WORKFLOW_YAML.test(f),
    fileTest: /\$\{\{\s*\}\}/g,
    msg: '워크플로에 빈 표현식 ${{ }} 금지 — Actions 가 파일을 통째로 무효화한다(잡 0개·로그 없이 죽음). 주석 안에도 쓰지 말 것',
  },
];

export function parseAllowance(line, { now = new Date() } = {}) {
  const match = String(line).match(ALLOWANCE);
  if (!match) return null;

  const [, reason, issue, owner, expires] = match;
  const dateMatch = expires.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!reason.trim() || !ISSUE.test(issue) || !OWNER.test(owner) || !dateMatch) return null;

  const [year, month, day] = dateMatch.slice(1).map(Number);
  const expiry = new Date(Date.UTC(year, month - 1, day));
  if (
    expiry.getUTCFullYear() !== year
    || expiry.getUTCMonth() !== month - 1
    || expiry.getUTCDate() !== day
  ) return null;

  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  if (expiry.getTime() <= today) return null;
  return { reason, issue, owner, expires };
}

export function scanText(f, content, { now = new Date() } = {}) {
  const violations = [];
  const allowances = [];
  const comparablePath = policyPath(f);
  const lines = String(content).split(/\r?\n/);
  const lineAllowances = lines.map((line, i) => {
    if (!ALLOW.test(line)) return null;
    const allowance = parseAllowance(line, { now });
    if (!allowance) {
      violations.push({ file: f, line: i + 1, id: 'INVALID-ALLOWANCE', msg: 'harness-guard 예외는 유효한 reason, issue, owner, 미래 expires가 필요함' });
      return null;
    }
    allowances.push({ file: f, line: i + 1, ...allowance });
    return allowance;
  });
  for (const rule of RULES) {
    if (!rule.when(comparablePath)) continue;
    lines.forEach((line, i) => {
      if (lineAllowances[i]) return;
      // ignore comment-only lines for code rules (keep SQL/DDL scanning)
      if (JAVA_KT.test(f) && /^\s*(\/\/|\*|\/\*)/.test(line)) return;
      if (rule.test && rule.test(line)) violations.push({ file: f, line: i + 1, id: rule.id, msg: rule.msg });
    });
    if (!rule.fileTest) continue;
    // 파일 단위 멀티라인 스캔 — 라인 정규식을 개행 분할로 우회하는 표기를 잡는다.
    // 매치 시작 라인 기준으로 allowance·주석 판정(라인 스캔과 동일 규약), (id, line) 중복은 병합.
    const pattern = new RegExp(rule.fileTest.source, rule.fileTest.flags.includes('g') ? rule.fileTest.flags : `${rule.fileTest.flags}g`);
    for (const match of String(content).matchAll(pattern)) {
      const lineNumber = String(content).slice(0, match.index).split(/\r?\n/).length;
      if (lineAllowances[lineNumber - 1]) continue;
      if (JAVA_KT.test(f) && /^\s*(\/\/|\*|\/\*)/.test(lines[lineNumber - 1] ?? '')) continue;
      if (violations.some((violation) => violation.id === rule.id && violation.line === lineNumber && violation.file === f)) continue;
      violations.push({ file: f, line: lineNumber, id: rule.id, msg: rule.msg });
    }
  }
  return { violations, allowances };
}

export async function readUtf8Strict(filePath) {
  const bytes = await readFile(filePath);
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

async function readStdinUtf8Strict() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(Buffer.from(chunk));
  return new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
}

async function nearestExistingAncestor(filePath) {
  let candidate = filePath;
  for (;;) {
    try { await stat(candidate); return candidate; } catch (error) {
      if (error?.code !== 'ENOENT' && error?.code !== 'ENOTDIR') throw error;
      const parent = dirname(candidate);
      if (parent === candidate) throw error;
      candidate = parent;
    }
  }
}

function isContained(root, candidate) {
  const remainder = relative(root, candidate);
  return remainder !== '' && !remainder.startsWith('..') && !isAbsolute(remainder);
}

export async function normalizeRepoPath(repoRoot, requestedPath) {
  if (typeof requestedPath !== 'string' || requestedPath.length === 0) throw new Error('missing file path');
  const root = await realpath(resolve(repoRoot));
  const requested = resolve(root, requestedPath);
  if (!isContained(root, requested)) throw new Error('path is outside repository or is repository root');
  const ancestor = await nearestExistingAncestor(requested);
  const actualAncestor = await realpath(ancestor);
  if (actualAncestor !== root && !isContained(root, actualAncestor)) throw new Error('path escapes repository through a link');
  return requested;
}

function replaceExactlyOnce(content, oldString, newString) {
  if (typeof oldString !== 'string' || oldString.length === 0 || typeof newString !== 'string') throw new Error('invalid edit');
  const first = content.indexOf(oldString);
  if (first < 0 || content.indexOf(oldString, first + oldString.length) >= 0) throw new Error('edit must match exactly once');
  return content.slice(0, first) + newString + content.slice(first + oldString.length);
}

export async function reconstructPendingContent(event, { repoRoot }) {
  if (!event || typeof event !== 'object' || !event.tool_input || typeof event.tool_input !== 'object') throw new Error('malformed hook event');
  const input = event.tool_input;
  if ('new_path' in input || 'destination' in input) throw new Error('rename is unsupported');
  const filePath = await normalizeRepoPath(repoRoot, input.file_path);
  const tool = String(event.tool_name ?? event.tool ?? '').split('.').at(-1);
  if (tool === 'Write') {
    if (typeof input.content !== 'string') throw new Error('Write content is required');
    return input.content;
  }
  if (tool === 'Edit') return replaceExactlyOnce(await readUtf8Strict(filePath), input.old_string, input.new_string);
  if (tool === 'MultiEdit') {
    if (!Array.isArray(input.edits) || input.edits.length === 0) throw new Error('MultiEdit edits are required');
    let content = await readUtf8Strict(filePath);
    for (const edit of input.edits) content = replaceExactlyOnce(content, edit?.old_string, edit?.new_string);
    return content;
  }
  throw new Error('unsupported hook tool');
}

// DoD 넛지(비차단) — 돈 경로(core/prod) 변경이 테스트 변경 없이 스테이지되면 stderr 리마인더.
// 차단하지 않는다: TDD 는 판단 영역(리팩터·문서화 커밋 등 정당한 예외 존재) — 게이트는 JaCoCo 가 정답.
export function dodNudgeMessage(files) {
  const normalized = files.map(policyPath);
  const money = normalized.filter((f) => JAVA_KT.test(f) && inMoneyScope(f) && isCore(f) && isProd(f));
  if (money.length === 0) return null;
  if (normalized.some((f) => JAVA_KT.test(f) && /\/src\/test\//.test(f))) return null;
  return `DoD 넛지(비차단): 돈 경로 프로덕션 변경 ${money.length}건이 테스트 변경 없이 스테이지됨 — tdd-discipline·verify-before-done 절차와 게이트(:module:test + jacoco LINE 90%) 통과를 확인하고 커밋하세요.`;
}

// 하네스 자체를 이루는 경로 — 여기가 지워지면 가드·스킬·규율이 통째로 사라진다.
// 실제로 PR #210 에 섞인 삭제 커밋 3개가 .claude(81)·.codex(41)·docs/harness(148) 를 날렸고,
// 기존 스테이징 스캔이 --diff-filter=ACMR 로 삭제를 아예 보지 않아 그대로 통과했다.
//
// docs/harness 는 목록에 넣지 않는다 — 그 사고에 함께 휩쓸렸을 뿐 하네스 기계장치가 아니라
// 해커톤 제출물 보관함이고, 공개 저장소 위생상 의도적으로 비우는 대상이다(CLAUDE.md 배치 기준:
// 소유 서비스가 없는 제출물은 저장소에 두지 않는다). 보호 대상은 실행되는 하네스로 한정한다.
const PROTECTED_DELETE_ROOTS = ['.claude/', '.codex/', 'scripts/harness/'];

// 위 루트 안이지만 재생성 가능한 세션 상태 — 삭제를 막을 이유가 없다(정리 작업을 방해하지 않는다).
const PROTECTED_DELETE_EXEMPT = [
  '.claude/scratch/', '.claude/agent-memory/', '.claude/worktrees/', '.claude/harness/',
];

/**
 * 스테이징된 삭제 중 하네스 보호 경로에 해당하는 것을 위반으로 보고한다.
 *
 * <p>임계치를 두지 않는다 — "대량이면 막는다"는 규칙은 한 파일씩 여러 커밋으로 나누면 그대로
 * 뚫린다. 보호 경로의 삭제는 항상 의도적이어야 하므로 1건도 막고, 진짜 지울 때는
 * {@code HARNESS_ALLOW_DELETE=1} 로 명시적으로 opt-in 한다(그 사실이 실행 기록에 남는다).
 */
export function checkProtectedDeletions(deletedFiles, options = {}) {
  const allowDelete = options.allowDelete ?? process.env.HARNESS_ALLOW_DELETE === '1';
  if (allowDelete) return [];
  const violations = [];
  for (const file of deletedFiles ?? []) {
    const path = policyPath(file);
    if (PROTECTED_DELETE_EXEMPT.some((prefix) => path.startsWith(prefix))) continue;
    if (!PROTECTED_DELETE_ROOTS.some((prefix) => path.startsWith(prefix))) continue;
    violations.push({
      file,
      id: 'HARNESS-DELETE',
      msg: '하네스 경로 삭제 금지 — 스킬·가드·규율이 사라진다. 의도한 삭제면 HARNESS_ALLOW_DELETE=1 로 실행',
    });
  }
  return violations;
}

// ── Bash 명령 가드 (PreToolUse matcher: Bash) ────────────────────────────────────────
//
// 실시간 파일 가드(--hook)는 매처가 Write|Edit|MultiEdit 뿐이라, Bash 로 우회한 파일 조작
// (sed -i·perl -i·리다이렉트·heredoc)은 커밋·CI 계층까지 내려가야 잡혔고, 운영 데이터 직접
// 조작 명령 차단(check-command)은 settlement-copilot **플러그인 소유**라 플러그인 미설치
// 환경(CI·새 클론·Codex)에는 아예 없었다 — "플러그인 독립" 전제의 구멍. 이 계층이 그 둘을
// 저장소 네이티브로 닫는다. 내용 검사가 아니라 **운반 수단 차단**이므로 대상을 좁게 유지한다
// (오탐보다 범위 축소). 의도적 실행은 HARNESS_ALLOW_CMD=1 로 opt-in(실행 기록에 남는다).
//
// heredoc/리다이렉트로 소스를 쓰면 백슬래시·NUL 손상이 조용히 발생한 전력이 2회 있고
// (레포 규율: 저장소 파일은 Write/Edit 도구가 정답), sed -i 는 CRLF 파일 전체 라인엔딩을
// 뒤집는다 — 차단 대상 확장자는 실시간 파일 가드가 스캔하는 소스 계열로 한정한다.
const GUARDED_WRITE_EXT = String.raw`\.(?:java|kts?|sql|mjs|ya?ml)\b`;
export const COMMAND_RULES = [
  {
    id: 'CMD-EDIT-BYPASS',
    severity: 'BLOCK',
    test: (cmd) =>
      (/\b(?:sed|perl)\b[^|;&]*\s-[a-zA-Z]*i\b/.test(cmd) && new RegExp(GUARDED_WRITE_EXT).test(cmd))
      || new RegExp(String.raw`>{1,2}\s*['"]?[\w.$/\\~-]+${GUARDED_WRITE_EXT}`).test(cmd)
      || new RegExp(String.raw`\btee\b[^|;&]*${GUARDED_WRITE_EXT}`).test(cmd),
    msg: 'Bash 로 소스 파일(.java/.kt/.sql/.mjs/.yml) 편집 금지 — 실시간 가드(내용 스캔)를 우회하고 heredoc 백슬래시 손실·CRLF churn 전력이 있다. Write/Edit 도구를 사용하세요. 의도한 실행이면 HARNESS_ALLOW_CMD=1',
  },
  {
    id: 'CMD-NO-VERIFY',
    severity: 'BLOCK',
    test: (cmd) => /\bgit\b[^|;&]*\b(?:commit|push)\b[^|;&]*--no-verify\b/.test(cmd),
    msg: 'git --no-verify 금지 — pre-commit 가드 우회는 CI 에서 재차단된다(HARNESS.md 강제 지점). 훅이 오탐이면 가드를 고치는 것이 정답',
  },
  {
    id: 'CMD-PROD-DB-WRITE',
    severity: 'BLOCK',
    test: (cmd) =>
      (/\b(?:psql|pgcli|pg_dump)\b/.test(cmd)
        && /\b(?:opslab|settlement_db|lemuel_[a-z_]+)\b/.test(cmd)
        && /\b(?:UPDATE|DELETE|INSERT|TRUNCATE|ALTER|DROP)\s/i.test(cmd))
      || (/kubectl\s+exec\b/.test(cmd) && /\b(?:psql|pg_dump)\b/.test(cmd)),
    msg: '서비스 DB 직접 쓰기 금지 — 데이터 정정은 adjustment/역분개 API 경로로만(원장 불변). 조회는 MCP 도구 또는 /admin/integrity API. 로컬 개발 DB 의 의도적 조작이면 HARNESS_ALLOW_CMD=1',
  },
  {
    id: 'CMD-EVENT-PRODUCE',
    severity: 'WARN',
    test: (cmd) => /(?:rpk\s+topic\s+produce|kafka-console-producer)/.test(cmd) && /lemuel\./.test(cmd),
    msg: 'lemuel.* 토픽에 직접 produce — Outbox 를 우회하면 event_id 멱등 체계가 깨질 수 있다. 테스트면 -z none(스나피 네이티브 부재) + 테스트 토픽 권장 (비차단 경고)',
  },
];

export function checkCommand(command, options = {}) {
  const allow = options.allowCommands ?? process.env.HARNESS_ALLOW_CMD === '1';
  if (allow) return [];
  const cmd = String(command ?? '');
  if (cmd.length === 0) return [];
  return COMMAND_RULES.filter((rule) => rule.test(cmd))
    .map((rule) => ({ id: rule.id, severity: rule.severity, file: '(bash)', msg: rule.msg }));
}

// ── KAFKA-DLQ: 컨슈머를 가진 서비스는 반드시 DLT 배선이 닿아야 한다 ──────────────────
//
// 왜 라인 규칙이 아니라 저장소 규칙인가: 이 위반은 "잘못 쓴 줄"이 아니라 "없는 파일"이다.
// 배선이 서비스마다 ~180줄씩 복붙되던 동안 card·insurance·operation 은 배선 자체가 누락됐고,
// Spring Kafka 기본 핸들러 FixedBackOff(0, 9) 로 떨어져 재시도 소진 메시지를 조용히 skip 했다
// (= 사실상 유실). 어떤 파일도 "틀리지" 않았기 때문에 라인 스캔으로는 영원히 안 잡힌다.
//
// 판정: 모듈 안에 @KafkaListener 가 있으면 공용 배선(KafkaConsumerErrorHandlingConfig)이
// 닿아야 한다. 닿는 경로는 둘 중 하나다.
//   (a) 루트 github.lms.lemuel 스캔 — @SpringBootApplication 이 루트 패키지에 있고 scanBasePackages 로
//       좁히지 않은 경우 (대부분의 서비스)
//   (b) 명시 @Import(KafkaConsumerErrorHandlingConfig.class) — 제한 스캔 서비스(company 등)
const SHARED_KAFKA_CONFIG = 'KafkaConsumerErrorHandlingConfig';

/** settings.gradle.kts 가 선언한 Gradle 모듈만 대상 — 폴리글랏 standalone 은 shared-common 자체가 없다. */
export function parseGradleModules(settingsText) {
  const includeBlock = /include\s*\(([\s\S]*?)\)/.exec(String(settingsText));
  if (!includeBlock) return [];
  return [...includeBlock[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

function gitGrepFiles(repoRoot, pattern, pathspecs) {
  // --untracked: 아직 커밋되지 않은 신규 배선 파일도 봐야 한다. 이게 없으면 "방금 추가한 @Import"를
  //   못 보고 오탐을 낸다(무시 대상 파일은 여전히 제외된다).
  // git grep -l 은 매치가 없으면 exit 1 — 정상 상태이므로 예외로 만들지 않는다.
  const result = spawnSync('git', ['grep', '-lE', '--untracked', '-e', pattern, '--', ...pathspecs], { cwd: repoRoot });
  if (result.status === 1) return [];
  if (result.status !== 0) throw new Error(`git grep failed: ${result.stderr?.toString('utf8') ?? ''}`);
  return result.stdout.toString('utf8').split(/\r?\n/).filter(Boolean).map((f) => f.replaceAll('\\', '/'));
}

const moduleOf = (file) => file.split('/')[0];

const ROOT_PACKAGE = 'github.lms.lemuel';
/** 컴포넌트 스캔 범위를 정하는 어노테이션만 — @EntityScan·@EnableJpaRepositories 의 동명 속성은 무관하다. */
const COMPONENT_SCAN_ANNOTATION = /@(?:ComponentScan|SpringBootApplication)\s*\(/g;
/** {@code scanBasePackages}/{@code basePackages} 의 값 리터럴만 뽑는다 — excludeFilters 의 정규식은 보지 않는다. */
const SCAN_ATTRIBUTE = /\b(?:scanBasePackages|basePackages)\s*=\s*(\{[^}]*\}|"[^"]*")/g;

/** 여는 괄호 위치에서 시작해 짝이 맞는 닫는 괄호까지의 인자 텍스트를 돌려준다. */
function balancedArgs(source, openParenIndex) {
  let depth = 0;
  for (let i = openParenIndex; i < source.length; i += 1) {
    if (source[i] === '(') depth += 1;
    else if (source[i] === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(openParenIndex + 1, i);
    }
  }
  return source.slice(openParenIndex + 1);
}

/**
 * 부트 진입점이 루트 패키지 전체를 컴포넌트 스캔하는지 판정한다.
 *
 * <p>스캔 범위 지정이 아예 없으면 진입점의 패키지(=루트)부터 스캔하므로 참이다. 지정이 있으면
 * 값이 전부 {@code github.lms.lemuel} 일 때만 참 — {@code github.lms.lemuel.company} 처럼
 * 하위로 좁힌 경우는 shared-common 의 공용 설정이 스캔에 안 잡히므로 거짓이다.
 *
 * <p>주의 2가지(둘 다 실제로 오탐을 냈다):
 * <ul>
 *   <li>account 는 {@code @ComponentScan(basePackages = "github.lms.lemuel", excludeFilters = ...)} 로
 *       일부만 배제한다 — 여전히 루트 스캔이다.</li>
 *   <li>같은 파일의 {@code @EntityScan}/{@code @EnableJpaRepositories} 도 {@code basePackages} 를 쓰지만
 *       JPA 스캔이라 컴포넌트 스캔 범위와 무관하다 — 그래서 어노테이션 블록 안으로 한정해 읽는다.</li>
 * </ul>
 */
export function isRootScanned(source) {
  const text = String(source);
  const packages = [];
  for (const annotation of text.matchAll(COMPONENT_SCAN_ANNOTATION)) {
    const args = balancedArgs(text, annotation.index + annotation[0].length - 1);
    for (const attribute of args.matchAll(SCAN_ATTRIBUTE)) {
      packages.push(...[...attribute[1].matchAll(/"([^"]*)"/g)].map((q) => q[1]));
    }
  }
  return packages.length === 0 || packages.every((pkg) => pkg === ROOT_PACKAGE);
}

/** 자체 DLT 격리 머시너리 — 폴리글랏 standalone 이 공용 설정 없이 같은 계약을 만족하는 경로. */
const OWN_DLT_WIRING = 'DeadLetterPublishingRecoverer';

/**
 * 주석(한 줄·블록)을 지운 소스를 돌려준다. 배선 판정은 **실행되는 코드**만 봐야 한다 —
 * 자바독의 {@code @Import(...)} 예시나 "언젠가 이렇게 배선하자"는 메모가 통과 근거가 되면
 * 애노테이션만 지우고 주석을 남긴 순간 가드가 조용히 GREEN 이 된다(= 가드의 존재 이유 상실).
 *
 * 문자열 리터럴은 보존한다 — `"https://..."` 의 `//` 를 주석 시작으로 오인하면 같은 줄 뒤의
 * 실제 배선 코드가 통째로 사라져 반대 방향 오탐이 난다.
 */
export function stripComments(source) {
  const text = String(source);
  let out = '';
  let state = 'code'; // code | line | block | string | char
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    const next = text[i + 1];
    if (state === 'code') {
      if (c === '/' && next === '/') { state = 'line'; i += 1; continue; }
      if (c === '/' && next === '*') { state = 'block'; i += 1; continue; }
      if (c === '"') state = 'string';
      else if (c === "'") state = 'char';
      out += c;
    } else if (state === 'line') {
      if (c === '\n') { state = 'code'; out += c; }
    } else if (state === 'block') {
      if (c === '*' && next === '/') { state = 'code'; i += 1; }
      else if (c === '\n') out += c; // 줄 수를 보존해 다른 규칙의 행 번호가 밀리지 않게 한다
    } else { // string | char
      if (c === '\\') { out += c + (next ?? ''); i += 1; continue; }
      if ((state === 'string' && c === '"') || (state === 'char' && c === "'")) state = 'code';
      out += c;
    }
  }
  return out;
}

/** 공용 배선을 실제로 끌어오는 형태 — 자바독 예시가 아니라 애노테이션이어야 한다. */
const IMPORT_SHARED_CONFIG = new RegExp(`@Import[\\s(]*\\{?[^)]*${SHARED_KAFKA_CONFIG}\\s*(?:\\.class|::class)`);
/** 공용 배선을 **제공**하는 형태 — 이름 언급이 아니라 클래스 정의여야 한다. */
const DEFINES_SHARED_CONFIG = new RegExp(`\\bclass\\s+${SHARED_KAFKA_CONFIG}\\b`);
/** 자체 배선을 실제로 생성하는 형태 — 타입 이름 언급이 아니라 생성자 호출이어야 한다. */
const CONSTRUCTS_OWN_WIRING = new RegExp(`${OWN_DLT_WIRING}\\s*\\(`);

/** 후보 파일 중 실제 배선 코드를 가진 파일이 하나라도 있는 모듈 집합. */
function modulesWiredBy(candidateFiles, pattern, readSource) {
  const wired = new Set();
  for (const file of candidateFiles) {
    let source;
    try {
      source = readSource(file);
    } catch {
      continue; // 읽을 수 없는 후보는 근거로 삼지 않는다 — 통과시키지도 않는다
    }
    if (pattern.test(stripComments(source))) wired.add(moduleOf(file));
  }
  return wired;
}

/**
 * @KafkaListener 를 가진 모듈에 DLT 배선이 닿는지 검사한다.
 *
 * <p>배선이 닿는 경로는 셋 중 하나다.
 * <ol>
 *   <li><b>(a) 루트 스캔 + shared-common 의존</b> — 공용 설정이 자동으로 잡힌다(대부분의 Java 서비스).
 *       스캔만으로는 부족하다: shared-common 미의존 위성(financial·economics·market·commondata)은
 *       루트 스캔이어도 공용 설정이 클래스패스에 아예 없다.</li>
 *   <li><b>(b) 명시 @Import</b> — 제한 스캔 서비스(company 등). 주석은 근거가 되지 않는다:
 *       {@code stripComments} 후 애노테이션 형태가 남아 있어야 한다.</li>
 *   <li><b>(c) 자체 DLT 배선</b> — shared-common 을 의존하지 않는 폴리글랏 standalone
 *       (같은 계약을 자기 언어로 구현한다). ADR 0041 으로 Kotlin notification-service 가
 *       operation-service 로 흡수돼 현재 이 경로를 타는 모듈은 없지만, 폴리글랏 컨슈머가 다시
 *       생기면 되살아나는 분기라 규칙을 유지한다.</li>
 * </ol>
 *
 * <p>shared-common 자신은 배선 제공자이므로 (c) 로 자연히 통과한다.
 */
export function checkKafkaDlqWiring(repoRoot, deps = {}) {
  const grep = deps.gitGrepFiles ?? gitGrepFiles;
  const readSource = deps.readSource ?? ((f) => readFileSync(resolve(repoRoot, f), 'utf8'));
  // 워킹트리를 읽는다 — 모듈을 추가하는 커밋에서도 그 커밋이 곧바로 검사 대상이 되어야 한다.
  const readSettings = deps.readSettings
    ?? (() => readFileSync(resolve(repoRoot, 'settings.gradle.kts'), 'utf8'));

  let gradleModules;
  try {
    gradleModules = new Set(parseGradleModules(readSettings()));
  } catch {
    return []; // settings 를 못 읽는 환경(얕은 클론 등)에서 가드를 깨뜨리지 않는다
  }

  const mainSources = ['*/src/main/**/*.java', '*/src/main/**/*.kt'];
  // 폴리글랏 standalone 도 대상이다 — settings.gradle.kts 밖이라고 유실이 허용되지는 않는다.
  const consumerModules = new Set(grep(repoRoot, '@KafkaListener', mainSources).map(moduleOf));
  if (consumerModules.size === 0) return [];

  // git grep 은 후보 파일을 싸게 좁히는 용도일 뿐이다 — 통과 판정은 주석을 걷어낸 뒤
  // 실제 배선 형태(@Import 애노테이션 / recoverer 생성자 호출)가 있을 때만 내린다.
  const ownWiringModules = modulesWiredBy(grep(repoRoot, OWN_DLT_WIRING, mainSources), CONSTRUCTS_OWN_WIRING, readSource);
  const sharedConfigCandidates = grep(repoRoot, SHARED_KAFKA_CONFIG, mainSources);
  const importingModules = modulesWiredBy(sharedConfigCandidates, IMPORT_SHARED_CONFIG, readSource);
  // (a) 경로는 "shared-common 이 배선을 제공한다"는 가정 위에 서 있다. 그 가정을 검사하지 않으면
  // 공용 배선이 통째로 사라져도 루트 스캔 서비스가 전부 조용히 통과한다 — 실제 사고가 그렇게 났다
  // (커밋 7cf573446 이 "액션 SHA 핀" 제목으로 서비스별 배선 5벌을 함께 삭제). 제공자 존재를 함께 본다.
  const sharedConfigProvided = modulesWiredBy(sharedConfigCandidates, DEFINES_SHARED_CONFIG, readSource).size > 0;
  // account 처럼 @SpringBootApplication 을 분해해 쓰는 형태(@SpringBootConfiguration +
  // @EnableAutoConfiguration + @ComponentScan(excludeFilters=...))도 진입점으로 인정한다.
  const rootScanned = new Set(
    grep(repoRoot, '@SpringBoot(Application|Configuration)', mainSources)
      .filter((f) => /\/src\/main\/java\/github\/lms\/lemuel\/[^/]+\.java$/.test(f))
      .filter((f) => isRootScanned(readSource(f)))
      .map(moduleOf),
  );

  const dependsOnSharedCommon = (module) => {
    if (!gradleModules.has(module)) return false;
    try {
      return readSource(`${module}/build.gradle.kts`).includes('shared-common');
    } catch {
      return false;
    }
  };

  const violations = [];
  for (const module of [...consumerModules].sort()) {
    if (ownWiringModules.has(module) || importingModules.has(module)) continue;
    const scannedWithSharedCommon = rootScanned.has(module) && dependsOnSharedCommon(module);
    if (scannedWithSharedCommon && sharedConfigProvided) continue;
    const cause = scannedWithSharedCommon
      ? `공용 배선 클래스 ${SHARED_KAFKA_CONFIG} 가 저장소에 없다 — 삭제됐거나 옮겨졌다. 루트 스캔·shared-common 의존만으로는 배선이 성립하지 않는다`
      : `shared-common 의존 서비스는 루트 스캔이거나 @Import(${SHARED_KAFKA_CONFIG}.class), standalone 은 자체 ${OWN_DLT_WIRING} 배선이 필요하다`;
    violations.push({
      file: `${module}/src/main`,
      id: 'KAFKA-DLQ',
      msg: `@KafkaListener 가 있는데 DLT 배선이 닿지 않음 — Spring Kafka 기본 핸들러는 재시도 소진 후 메시지를 조용히 skip 한다(사실상 유실). ${cause}`,
    });
  }
  return violations;
}

// ── KAFKA-GROUP-OWNER: 컨슈머 그룹 ID 는 모듈 소유여야 한다 ────────────────────────
//
// 같은 group-id 를 두 서비스가 쓰면 카프카는 그 둘을 한 컨슈머 그룹으로 보고 파티션을 나눠 준다.
// 한쪽이 가져간 메시지는 다른 쪽에 오지 않고, 오프셋까지 공유되므로 **조용히 유실**된다. 예외도
// 로그도 없다 — 정산 이벤트가 안 왔다는 사실로만 뒤늦게 드러난다.
//
// 실제 상태(2026-08-14): order-service 가 spring.kafka.consumer.group-id 로 `lemuel-settlement` 을
// 들고 있었다. 모놀리스 분리 시점의 잔재이고 order 에는 @KafkaListener 가 하나도 없어 지금은 무해하지만,
// 리스너가 하나 붙는 순간 settlement 가 받아야 할 파티션 일부를 order 가 점유하고 커밋한다.
// 컴파일도 테스트도 잡지 못한다.
//
// 판정: Gradle 모듈 `<name>-service` 의 group-id 는 `lemuel-<name>` 이어야 한다. 이름 규칙이 곧
// 유일성 보장이다. 폴리글랏 standalone(settings.gradle.kts 밖)은 대상이 아니다 — 팬아웃 목적의
// 별도 그룹을 쓰는 것이 정상이다.
const CONSUMER_GROUP_ID = /^\s*group-id:\s*(\S+)\s*$/m;

/** 의도적으로 규칙을 벗어나는 모듈(현재 없음). 추가 시 반드시 사유를 남긴다. */
const ALLOWED_GROUP_IDS = new Map();

/** `${KAFKA_GROUP_ID:lemuel-order}` 처럼 환경변수로 감싼 경우 기본값을 본다 — 실제로 뜨는 값이다. */
export function resolveGroupId(raw) {
  const wrapped = /^\$\{[^:}]*:?([^}]*)\}$/.exec(String(raw).trim());
  return (wrapped ? wrapped[1] : String(raw).trim()) || null;
}

/** `order-service` → `lemuel-order` */
export function expectedGroupId(module) {
  return `lemuel-${module.replace(/-service$/, '')}`;
}

export function checkConsumerGroupOwnership(repoRoot, deps = {}) {
  const readSettings = deps.readSettings
    ?? (() => readFileSync(resolve(repoRoot, 'settings.gradle.kts'), 'utf8'));
  const readYaml = deps.readYaml ?? ((module) => {
    try {
      return readFileSync(resolve(repoRoot, module, 'src/main/resources/application.yml'), 'utf8');
    } catch {
      return null; // 설정이 없는 모듈(gateway 등)은 대상이 아니다
    }
  });

  let modules;
  try {
    modules = parseGradleModules(readSettings());
  } catch {
    return [];
  }

  const violations = [];
  for (const module of modules.filter((m) => m.endsWith('-service')).sort()) {
    const yaml = readYaml(module);
    if (!yaml) continue;
    const match = CONSUMER_GROUP_ID.exec(yaml);
    if (!match) continue; // 컨슈머가 없는 모듈
    const actual = resolveGroupId(match[1]);
    const expected = ALLOWED_GROUP_IDS.get(module) ?? expectedGroupId(module);
    if (actual === expected) continue;
    violations.push({
      file: `${module}/src/main/resources/application.yml`,
      id: 'KAFKA-GROUP-OWNER',
      msg: `컨슈머 그룹 ID 가 '${actual}' 이다 — '${expected}' 여야 한다. 다른 모듈과 같은 그룹을 쓰면 `
        + '카프카가 둘을 한 그룹으로 보고 파티션을 나눠 줘, 한쪽이 가져간 메시지는 다른 쪽에 오지 않고 '
        + '오프셋까지 공유되어 조용히 유실된다',
    });
  }
  return violations;
}

export function discoverStagedDeletions(repoRoot) {
  const output = execFileSync('git', ['diff', '--cached', '--name-only', '-z', '--diff-filter=D'], { cwd: repoRoot });
  return output.toString('utf8').split('\0').filter(Boolean);
}

export function discoverStagedFiles(repoRoot) {
  const output = execFileSync('git', ['diff', '--cached', '--name-only', '-z', '-M', '--diff-filter=ACMR'], { cwd: repoRoot });
  return output.toString('utf8').split('\0').filter(Boolean);
}

function hasApplicableRule(f) {
  const comparablePath = policyPath(f);
  return RULES.some((rule) => rule.when(comparablePath));
}

async function scanRepoFile(repoRoot, requestedPath) {
  // Rules only target java/kt/sql sources — skip reading anything else so staged
  // binary artifacts (docx/png/log) can't abort the whole scan on a UTF-8 decode failure.
  if (!hasApplicableRule(requestedPath)) return [];
  const absolute = await normalizeRepoPath(repoRoot, requestedPath);
  return scanText(requestedPath, await readUtf8Strict(absolute)).violations;
}

function emitReport(violations, io = {}) {
  const stdout = io.stdout ?? ((message) => console.log(message));
  const stderr = io.stderr ?? ((message) => console.error(message));
  if (violations.length === 0) { stdout('harness guard: clean'); return 0; }
  stderr(`harness guard: ${violations.length} blocking violation(s)`);
  for (const violation of violations) stderr(`[${violation.id}] ${violation.file}${violation.line ? `:${violation.line}` : ''} ${violation.msg}`);
  return 1;
}

/**
 * 저장소 루트 — **cwd 가 아니라 이 스크립트의 위치에서** 도출한다.
 *
 * 훅은 셸의 cwd 를 물려받는다(`$CLAUDE_PROJECT_DIR` 은 스크립트 경로만 정한다). Bash 도구의
 * cwd 가 하위 디렉터리에 남아 있는 채로 가드가 돌면 repoRoot 가 그 하위 디렉터리가 되어 둘이
 * 깨진다: ① 정상 저장소 경로가 `normalizeRepoPath` 에서 "outside repository" 로 차단되고(정상
 * 편집이 exit 2), ② 텔레메트리가 `frontend/src/.claude/harness/logs/` 같은 평행 트리에 쌓여
 * 분모에서 빠진다(실측 29회 유실, 2026-08-19~21).
 *
 * guard.mjs 는 항상 `<repo>/scripts/harness/` 에 있으므로 스크립트 위치에서 두 단계 올라가면
 * cwd 와 무관하게 정확하다. 워크트리에서도 그 워크트리의 루트가 잡힌다 — 남의 체크아웃을
 * 검사하지 않는다는 뜻이라 이쪽이 오히려 옳다.
 */
export function defaultRepoRoot() {
  return resolve(fileURLToPath(new URL('../..', import.meta.url)));
}

export async function runGuardCli(args, io = {}) {
  const repoRoot = io.repoRoot ?? defaultRepoRoot();
  const stderr = io.stderr ?? ((message) => console.error(message));
  const modes = ['--staged', '--list', '--deleted-list', '--files', '--hook', '--hook-bash', '--self-test'].filter((mode) => args.includes(mode));
  if (modes.length !== 1) { stderr('exactly one guard mode is required'); return 2; }
  const mode = modes[0];
  // 인자 검증 실패를 종료 코드만으로 알리면 CI 로그에는 "exit 1" 만 남아 원인 추적이 불가능하다.
  // 어떤 모드가 무엇을 요구하는지 항상 stderr 로 말한다.
  const usage = (spec, code) => { stderr(`usage: guard ${spec}`); return code; };
  if (mode === '--self-test') {
    if (args.length !== 1) return usage('--self-test', 2);
    return spawnSync(process.execPath, ['--test', fileURLToPath(new URL('./test/guard.test.mjs', import.meta.url))], { cwd: repoRoot, stdio: 'inherit' }).status ?? 2;
  }
  if (mode === '--deleted-list') {
    // CI 는 삭제를 --diff-filter=ACMR 로 걸러 changed.txt 에 담지 않는다. 삭제 목록을 따로 받아
    // 하네스 보호 경로가 지워졌는지 검사한다 — PR 로 들어온 대량 삭제를 막는 유일한 지점이다.
    if (args.length !== 2) return usage('--deleted-list <file>', 2);
    try {
      const listPath = await normalizeRepoPath(repoRoot, args[1]);
      const deleted = (await readUtf8Strict(listPath)).split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
      return emitReport(checkProtectedDeletions(deleted), io);
    } catch (error) { stderr(`guard input failed: ${error.message}`); return 1; }
  }
  if (mode === '--hook-bash') {
    if (args.length !== 1) return usage('--hook-bash   (PreToolUse Bash 이벤트 JSON 을 stdin 으로)', 2);
    // 파일 훅(--hook)과 달리 fail-open 이다: 여기는 내용 불변식이 아니라 운반 수단 차단이라,
    // 입력 파싱 실패에 fail-closed 하면 하네스 결함 하나가 모든 Bash 실행을 멈춘다(블래스트 반경).
    // 우회 시도는 커밋(--staged)·CI(--list) 계층이 내용 기준으로 재차단한다.
    try {
      const event = JSON.parse(io.stdin ?? await readStdinUtf8Strict());
      const violations = checkCommand(event?.tool_input?.command);
      await logGuardHits(repoRoot, 'hook-bash', violations);
      await logGuardRun(repoRoot, 'hook-bash', { files: 1, violations: violations.length });
      for (const violation of violations) stderr(`[${violation.id}] ${violation.msg}`);
      if (violations.some((violation) => violation.severity === 'BLOCK')) return 2;
      if (violations.length > 0) {
        const stdout = io.stdout ?? ((message) => console.log(message));
        stdout(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse',
          additionalContext: violations.map((violation) => `[${violation.id}] ${violation.msg}`).join('\n') } }));
      }
      return 0;
    } catch (error) { stderr(`guard hook-bash skipped (unparseable input): ${error.message}`); return 0; }
  }
  if (mode === '--hook') {
    if (args.length !== 1) return usage('--hook   (PreToolUse 이벤트 JSON 을 stdin 으로)', 2);
    try {
      const event = JSON.parse(io.stdin ?? await readStdinUtf8Strict());
      const pending = await reconstructPendingContent(event, { repoRoot });
      const { violations } = scanText(event.tool_input.file_path, pending);
      await logGuardHits(repoRoot, 'hook', violations); // observability only — never affects the verdict
      await logGuardRun(repoRoot, 'hook', { files: 1, violations: violations.length });
      return emitReport(violations, io) ? 2 : 0;
    } catch (error) { stderr(`guard hook rejected input: ${error.message}`); return 2; }
  }
  try {
    let files;
    if (mode === '--staged') {
      if (args.length !== 1) return usage('--staged', 2);
      files = discoverStagedFiles(repoRoot);
    } else if (mode === '--list') {
      if (args.length !== 2) return usage('--list <file>   (검사할 파일 목록이 줄 단위로 담긴 파일)', 1);
      const listPath = await normalizeRepoPath(repoRoot, args[1]);
      files = (await readUtf8Strict(listPath)).split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
    } else {
      if (args[0] !== '--files' || args.length < 2) return usage('--files <file> [file...]', 1);
      files = args.slice(1);
    }
    const violations = [];
    for (const file of files) violations.push(...await scanRepoFile(repoRoot, file));
    // 삭제는 내용 스캔으로 잡히지 않는다(스테이징 목록이 ACMR 로 D 를 빼고 온다) — 별도로 확인한다.
    if (mode === '--staged') violations.push(...checkProtectedDeletions(discoverStagedDeletions(repoRoot)));
    // "없는 파일"은 라인 스캔으로 못 잡는다 — 저장소 단위 불변식은 커밋·CI 시점에 전수 검사한다.
    // (--files/--hook 은 편집 중 실시간 경로라 저장소 전수 스캔을 돌리지 않는다.)
    if (mode === '--staged' || mode === '--list') {
      violations.push(...checkKafkaDlqWiring(repoRoot));
      violations.push(...checkConsumerGroupOwnership(repoRoot));
    }
    await logGuardHits(repoRoot, mode.slice(2), violations); // observability only — never affects the verdict
    await logGuardRun(repoRoot, mode.slice(2), { files: files.length, violations: violations.length });
    if (mode === '--staged') {
      const nudge = dodNudgeMessage(files);
      if (nudge) {
        stderr(nudge); // advisory only — exit code stays with emitReport
        await appendJsonl(repoRoot, 'dod-nudges.jsonl', [{ ts: new Date().toISOString(), staged: files.length }]);
      }
    }
    return emitReport(violations, io);
  } catch (error) { stderr(`guard input failed: ${error.message}`); return 1; }
}


if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  process.exit(await runGuardCli(process.argv.slice(2)));
}
