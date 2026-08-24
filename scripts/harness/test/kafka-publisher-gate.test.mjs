// Kafka 발행부 ↔ 토픽 카탈로그 대조 게이트 — 리포 전수 (ADR 0035).
//
// kafka-topic-gate 는 application.yml 의 app.kafka.topic.* 만 본다. 그래서 **발행 전용 토픽**
// (구독 설정이 없어 yml 에 안 적히는 토픽)은 카탈로그에서 빠져도 아무도 모른다. 실제로 두 번 났다:
//   · lemuel.insurance.general_payout_{requested,paid} — 발행 코드는 있는데 카탈로그에 없었다
//   · lemuel.card.statement.paid — 계약 스키마 파일명을 옮겨 적은 탓에 실재하지 않는 이름이 등재됐다
//     (실제 발행명은 statement_paid). 카탈로그가 브로커와 무관한 토픽을 만들 뻔했다.
//
// 이 게이트는 정본을 yml 이 아니라 **발행 코드**에서 가져온다. KafkaOutboxPublisher 가 토픽명과
// 메시지 키를 outbox 레코드에서 그대로 파생시키므로, OutboxEvent.pending(aggregateType, aggregateId,
// eventType, …) 호출부가 곧 "이 서비스가 무엇을 어떤 키로 발행하는가"의 1차 자료다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CATALOG_PATH = join(repoRoot, 'shared-common', 'src', 'main', 'resources', 'kafka', 'topic-catalog.json');

/**
 * 키 표현식이 이것들로 끝나면 도메인 이름을 알 수 없다 — 수신자 변수명(account/order/loan)은
 * 카탈로그 용어와 다를 수 있어 대조 근거가 못 된다. 판정을 보류한다(오탐보다 범위 축소).
 */
const GENERIC_KEY_HINTS = new Set(['id', 'aggregateId']);

/**
 * 힌트와 카탈로그 표기가 의도적으로 다른 곳. 추가할 때는 반드시 근거를 남긴다.
 * (현재 없음 — 12건의 불일치는 카탈로그를 고쳐서 해소했다)
 */
const ALLOWED_KEY_ALIASES = new Map();

/** KafkaOutboxPublisher#camelToSnake 와 같은 규칙. 다르면 게이트가 엉뚱한 토픽을 계산한다. */
export function camelToSnake(camel) {
  let out = '';
  for (let i = 0; i < camel.length; i++) {
    const c = camel[i];
    if (c >= 'A' && c <= 'Z') {
      if (i > 0) out += '_';
      out += c.toLowerCase();
    } else {
      out += c;
    }
  }
  return out;
}

/** KafkaOutboxPublisher#resolveTopic 과 같은 규칙. */
export function resolveTopic(aggregateType, eventType) {
  const suffix = eventType.startsWith(aggregateType) ? eventType.slice(aggregateType.length) : eventType;
  return `lemuel.${aggregateType.toLowerCase()}.${camelToSnake(suffix)}`;
}

/** 주석을 지운다 — 자바독의 예시 토픽명이 발행 사실로 잡히면 안 된다. */
export function stripComments(source) {
  return String(source).replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

/** `pending(` 뒤의 괄호 균형을 맞춰 최상위 인자만 잘라낸다. */
export function splitArgs(text, openParenIndex) {
  const args = [];
  let depth = 0;
  let cur = '';
  for (let i = openParenIndex; i < text.length; i++) {
    const c = text[i];
    if (c === '(') {
      depth++;
      if (depth === 1) continue;
    } else if (c === ')') {
      depth--;
      if (depth === 0) { args.push(cur.trim()); break; }
    }
    if (depth === 1 && c === ',') { args.push(cur.trim()); cur = ''; continue; }
    if (depth >= 1) cur += c;
  }
  return args;
}

/** `"Literal"` 이거나 파일 안에서 값이 확정되는 상수면 그 문자열, 아니면 null. */
function resolveString(expr, constants) {
  const literal = /^"([^"]*)"$/.exec(expr);
  if (literal) return literal[1];
  return constants.get(expr) ?? null;
}

/** `String.valueOf(x.getFooId())` → fooId, `score.stockCode()` → stockCode, `settlementId` → settlementId */
export function keyHint(expr) {
  let e = expr.replace(/\s+/g, '');
  const unwrapped = /^String\.valueOf\((.*)\)$/.exec(e);
  if (unwrapped) e = unwrapped[1];

  const getter = /\.get([A-Z]\w*)\(\)$/.exec(e);
  if (getter) return getter[1][0].toLowerCase() + getter[1].slice(1);

  const accessor = /\.(\w+)\(\)$/.exec(e);
  if (accessor) return accessor[1];

  if (/^[a-z]\w*$/.test(e)) return e;
  return null;
}

/** 한 파일에서 발행 사실을 뽑는다. eventType 이 변수면 토픽을 계산할 수 없어 미해석으로 센다. */
export function extractPublications(source, file) {
  const text = stripComments(source);
  const constants = new Map();
  for (const m of text.matchAll(/static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"/g)) {
    constants.set(m[1], m[2]);
  }

  const found = [];
  let unresolved = 0;
  for (const m of text.matchAll(/OutboxEvent\.pending\s*\(/g)) {
    const args = splitArgs(text, m.index + m[0].length - 1);
    if (args.length < 3) { unresolved++; continue; }
    const aggregate = resolveString(args[0], constants);
    const event = resolveString(args[2], constants);
    if (!aggregate || !event) { unresolved++; continue; }
    found.push({ file, topic: resolveTopic(aggregate, event), key: keyHint(args[1]) });
  }
  return { found, unresolved };
}

/** 작업트리 기준 스캔 — 병행 세션의 미추적 파일도 본다(git ls-files 는 못 본다). */
function javaSources(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'build' || entry.name === '.git' || entry.name === 'node_modules') continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) javaSources(full, acc);
    else if (entry.name.endsWith('.java')) acc.push(full);
  }
  return acc;
}

function moduleOf(file) {
  return file.slice(repoRoot.length + 1).split(/[\\/]/)[0];
}

describe('Kafka 발행부 ↔ 카탈로그 대조 (ADR 0035)', () => {
  // ── 계산 규칙이 KafkaOutboxPublisher 와 같은지 ──
  test('토픽명 계산이 KafkaOutboxPublisher 규칙과 같다', () => {
    assert.equal(resolveTopic('Card', 'CardStatementPaid'), 'lemuel.card.statement_paid');
    assert.equal(resolveTopic('Payment', 'PaymentCaptured'), 'lemuel.payment.captured');
    assert.equal(resolveTopic('seller_recovery', 'Opened'), 'lemuel.seller_recovery.opened');
    assert.equal(resolveTopic('Insurance', 'InsuranceGeneralPayoutRequested'),
      'lemuel.insurance.general_payout_requested');
  });

  test('키 힌트를 표현식에서 뽑는다', () => {
    assert.equal(keyHint('String.valueOf(hold.getCardAccountId())'), 'cardAccountId');
    assert.equal(keyHint('policy.getPolicyNumber()'), 'policyNumber');
    assert.equal(keyHint('violation.bankCode()'), 'bankCode');
    assert.equal(keyHint('String.valueOf(settlementId)'), 'settlementId');
    assert.equal(keyHint('String.valueOf(account.getId())'), 'id'); // 일반형 → 판정 보류 대상
  });

  test('주석 속 토픽명은 발행으로 세지 않는다', () => {
    const src = '// OutboxEvent.pending(AGG, id, "Ghost", p);\nclass X {}';
    assert.equal(extractPublications(src, 'X.java').found.length, 0);
  });

  test('상수로 선언된 aggregateType·eventType 을 해석한다', () => {
    const src = `
      private static final String AGGREGATE_TYPE = "Payout";
      private static final String EVENT_TYPE = "PayoutCompleted";
      save(OutboxEvent.pending(AGGREGATE_TYPE, String.valueOf(payoutId), EVENT_TYPE, json));`;
    const { found } = extractPublications(src, 'P.java');

    assert.deepEqual(found.map((f) => [f.topic, f.key]), [['lemuel.payout.completed', 'payoutId']]);
  });

  test('eventType 이 변수면 미해석으로 센다 — 조용히 통과시키지 않는다', () => {
    const src = `
      private static final String AGGREGATE_TYPE = "Deposit";
      OutboxEvent.pending(AGGREGATE_TYPE, aggregateId, eventType, json);`;
    const { found, unresolved } = extractPublications(src, 'D.java');

    assert.equal(found.length, 0);
    assert.equal(unresolved, 1);
  });

  // ── 리포 전수 ──
  const catalog = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'));
  const byName = new Map(catalog.topics.map((t) => [t.name, t]));

  const modules = readdirSync(repoRoot, { withFileTypes: true })
    .filter((e) => e.isDirectory() && e.name.endsWith('-service'))
    .map((e) => join(repoRoot, e.name, 'src', 'main', 'java'))
    .filter((p) => existsSync(p) && statSync(p).isDirectory());

  const publications = [];
  let unresolvedTotal = 0;
  for (const root of modules) {
    for (const file of javaSources(root)) {
      const source = readFileSync(file, 'utf8');
      if (!source.includes('OutboxEvent.pending')) continue;
      const { found, unresolved } = extractPublications(source, file);
      publications.push(...found);
      unresolvedTotal += unresolved;
    }
  }

  test('스캔이 실제로 발행부에 도달했다', () => {
    assert.ok(publications.length >= 5,
      `발행부 수집이 비정상적으로 적다(${publications.length}) — 파서가 깨졌을 수 있다`);
  });

  test('발행되는 모든 토픽이 카탈로그에 있다', () => {
    const missing = [...new Set(publications.map((p) => p.topic))]
      .filter((t) => !byName.has(t)).sort();

    assert.deepEqual(missing, [],
      '발행 코드는 있는데 카탈로그에 없는 토픽 — 브로커 기본값으로 자동생성되어 파티션이 코드 밖에서 정해진다');
  });

  test('토픽의 카탈로그 owner 가 실제 발행 모듈과 같다', () => {
    const wrong = [];
    for (const p of publications) {
      const entry = byName.get(p.topic);
      if (!entry) continue;
      const module = moduleOf(p.file);
      if (entry.owner !== module) wrong.push(`${p.topic}: catalog=${entry.owner} 실제발행=${module}`);
    }

    assert.deepEqual([...new Set(wrong)], [],
      'owner 가 틀리면 그 모듈은 자기 토픽을 만들지 않고, 아무도 안 만든 채 자동생성에 맡겨진다');
  });

  test('orderingKey 가 발행부의 aggregateId 와 일치한다', () => {
    const mismatched = [];
    for (const p of publications) {
      const entry = byName.get(p.topic);
      if (!entry || !p.key || GENERIC_KEY_HINTS.has(p.key)) continue; // 판정 보류
      if (ALLOWED_KEY_ALIASES.get(p.topic) === entry.orderingKey) continue;
      if (entry.orderingKey !== p.key) {
        mismatched.push(`${p.topic}: catalog=${entry.orderingKey} 발행부=${p.key}`);
      }
    }

    assert.deepEqual([...new Set(mismatched)], [],
      'orderingKey 는 메시지 키의 도메인 의미다 — 발행부와 다르면 "무엇의 순서를 지키는가"를 잘못 적은 것이다');
  });

  test('미해석 호출부 수를 드러낸다 — 커버리지를 조용히 줄이지 않는다', () => {
    // eventType 이 변수인 호출부(래퍼 메서드 경유)는 토픽을 계산할 수 없다. 0 이 목표는 아니지만,
    // 이 수가 늘어나면 게이트가 보는 범위가 줄어든 것이므로 상한을 둔다.
    assert.ok(unresolvedTotal <= 12,
      `미해석 호출부가 ${unresolvedTotal}개다 — 래퍼 경유 발행이 늘면 이 게이트의 사각지대가 커진다`);
  });
});
