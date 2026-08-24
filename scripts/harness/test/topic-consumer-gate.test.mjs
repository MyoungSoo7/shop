// 미소비 토픽 게이트 — 리포 전수 (ADR 0024 / SPEC.md §5).
//
// 막는 것: "발행은 하는데 아무도 듣지 않는 토픽이 조용히 늘어나는 상태".
//
// 계약 테스트(ADR 0024)는 프로듀서와 스키마가 어긋나는 것을 막는다. 그런데 **아무도 안 듣는
// 토픽**은 스키마가 완벽해도 계약 테스트를 통과한다 — 검증 대상이 프로듀서와 스키마 둘뿐이기
// 때문이다. 그 사이 매 이벤트마다 Outbox 행이 쌓이고 브로커에 메시지가 남는데, 읽는 쪽이 없다.
//
// 이 저장소는 "발행 전용"을 **인정된 상태**로 둔다(SPEC.md §5 — 소비자가 생기면 ADR 0024 절차로
// 계약 편입, 트리거는 소비처 등장 시점). 소비자 없이 계약을 먼저 고정하면 실제 필요 형태를 모른 채
// 박는 셈이기 때문이다. 그래서 이 게이트는 미소비를 금지하지 않는다 — **선언되지 않은 미소비**를
// 금지한다.
//
// 실측 사례(2026-08-22): 미소비 23종 중 4종이 SPEC.md §5 발행 전용 목록 밖에 있었다
// (`card.authorized` · `card.statement_paid` · `loan.lease_activated` · `education.course_published`).
// 목록은 card 5종으로 적혀 있는데 그 뒤 토픽이 늘었고, 아무도 대조하지 않았다.
//
// 정본: SPEC.md §5 · docs/plan/prd-seed-drift-audit.md §6.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CATALOG_PATH = join(REPO_ROOT, 'shared-common', 'src', 'main', 'resources', 'kafka', 'topic-catalog.json');
const SPEC_PATH = join(REPO_ROOT, 'SPEC.md');

/**
 * 소비처가 없다고 **선언된** 토픽과 그 사유 — SPEC.md §5 "발행 전용" 절의 기계 대응물.
 *
 * 등록은 "소비자가 아직 없다"는 사실의 선언이지 면제가 아니다. 소비자가 생기면 이 항목을 지워야
 * 하고(아래 죽은 항목 검사가 강제), 그때가 ADR 0024 계약 편입 시점이다.
 */
const PUBLISH_ONLY = new Map([
  // 이 저장소는 커머스 코어(order)와 운영(operation)만 담는다. 아래 토픽들은 order 가 발행하고
  // 정산·여신·계정계 같은 하류 소비자가 이 저장소 밖에 있다 — "소비자를 안 만든 것"이 아니라
  // "경계 밖에 있는 것"이다. 소비자가 이 저장소 안에 생기면 계약에 편입한다(ADR 0024).
  ['lemuel.user.registered', '발행 전용 — 회원 마스터 변경 통지, 소비자는 저장소 밖'],
  ['lemuel.product.changed', '발행 전용 — 상품 마스터 변경 통지, 소비자는 저장소 밖'],
  ['lemuel.seller.tier_changed', '발행 전용 — 셀러 등급 변경 통지, 소비자는 저장소 밖'],
  // organization 4종 — 조직 마스터. 발행 전용이 이 슬라이스의 설계다(컨슈머 0).
  ['lemuel.organization.created', '발행 전용 — 조직 마스터 통지, 소비자는 저장소 밖'],
  ['lemuel.organization.member_joined', '발행 전용 — 조직 마스터 통지, 소비자는 저장소 밖'],
  ['lemuel.organization.member_removed', '발행 전용 — 조직 마스터 통지, 소비자는 저장소 밖'],
  ['lemuel.organization.member_role_changed', '발행 전용 — 조직 마스터 통지, 소비자는 저장소 밖'],
  // point 6종 — 포인트 원장. 원장은 order 안에서 닫히고 발행은 회계·관측용이다.
  ['lemuel.point.charged', '발행 전용 — 포인트 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.point.granted', '발행 전용 — 포인트 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.point.used', '발행 전용 — 포인트 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.point.restored', '발행 전용 — 포인트 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.point.expired', '발행 전용 — 포인트 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.point.revoked', '발행 전용 — 포인트 원장 통지, 소비자는 저장소 밖'],
  // giftcard 4종 — 상품권 원장. point 와 같은 이유.
  ['lemuel.giftcard.registered', '발행 전용 — 상품권 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.giftcard.used', '발행 전용 — 상품권 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.giftcard.restored', '발행 전용 — 상품권 원장 통지, 소비자는 저장소 밖'],
  ['lemuel.giftcard.expired', '발행 전용 — 상품권 원장 통지, 소비자는 저장소 밖'],
  // education — 과정 게시 통지. operation 의 유일한 발행 경로이고 소비자는 저장소 밖이다.
  ['lemuel.education.course_published', '발행 전용 — 과정 게시 통지, 소비자는 저장소 밖'],
]);

/** 블록·라인 주석 제거 — 주석 속 토픽명을 구독으로 세지 않는다. */
export function stripComments(src) {
  return String(src).replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

/**
 * application.yml 의 `key: lemuel.x.y` / `key: ${ENV:lemuel.x.y}` 를 key→topic 으로 모은다.
 * 리스너가 `${app.kafka.topic.settlement-created}` 처럼 프로퍼티로 토픽을 받기 때문에 필요하다.
 */
export function topicProperties(yaml) {
  const map = new Map();
  for (const m of String(yaml).matchAll(
    /^\s*([a-z0-9-]+):\s*(?:\$\{[A-Z0-9_]+:)?(lemuel\.[a-z0-9_.]+)\}?\s*(?:#.*)?$/gim)) {
    map.set(m[1], m[2]);
  }
  return map;
}

/**
 * `@KafkaListener(` 뒤의 인자 영역을 **괄호 균형으로** 잘라낸다.
 *
 * 처음에는 `@KafkaListener\(([\s\S]*?)\)\s*(?:@|public|void...)` 로 잡았는데, 애노테이션 뒤에 오는
 * 토큰이 예상 밖(`final`·Kotlin `suspend fun`·줄바꿈 조합)이면 통째로 놓쳐서 63개 중 50개만
 * 잡혔다. 안 잡힌 리스너의 토픽은 그대로 "미소비"로 뜬다 — 과소 검출이 곧 오탐이 된다.
 */
export function listenerArgRegions(src) {
  const regions = [];
  const marker = '@KafkaListener';
  for (let i = src.indexOf(marker); i !== -1; i = src.indexOf(marker, i + 1)) {
    let j = i + marker.length;
    while (j < src.length && /\s/.test(src[j])) j += 1;
    if (src[j] !== '(') continue;
    let depth = 0;
    let inString = false;
    for (let k = j; k < src.length; k += 1) {
      const ch = src[k];
      if (inString) {
        if (ch === '\\') k += 1;
        else if (ch === '"') inString = false;
        continue;
      }
      if (ch === '"') inString = true;
      else if (ch === '(') depth += 1;
      else if (ch === ')') {
        depth -= 1;
        if (depth === 0) { regions.push(src.slice(j + 1, k)); break; }
      }
    }
  }
  return regions;
}

/**
 * 인자 영역에서 `topics = ...` 의 값 구간을 잘라낸다.
 *
 * ⚠️ 여기서 정규식 `\{[\s\S]*?\}` 를 쓰면 안 된다. 실제 리스너는
 * `topics = { "${app.kafka.topic.point-charged}", ... }` 처럼 **문자열 안에 `}` 를 품는다** —
 * 비탐욕 매칭이 그 첫 `}` 에서 끊겨 배열의 첫 항목조차 온전히 못 읽는다. 합성 테스트가
 * `{"lemuel.a.b"}` 처럼 중괄호 없는 형태만 덮고 있어서 이 결함이 통과했고, 실제 리포에 돌렸을 때
 * account 의 point·giftcard 컨슈머 10종이 통째로 "미소비"로 뜨면서 드러났다.
 */
export function topicsAttributeValue(args) {
  const at = args.search(/\btopics\s*=/);
  if (at === -1) return null;
  let i = args.indexOf('=', at) + 1;
  while (i < args.length && /\s/.test(args[i])) i += 1;
  if (args[i] === '"') return args.slice(i, scanString(args, i) + 1);
  if (args[i] !== '{') return null;

  let depth = 0;
  for (let k = i; k < args.length; k += 1) {
    if (args[k] === '"') { k = scanString(args, k); continue; }
    if (args[k] === '{') depth += 1;
    else if (args[k] === '}') {
      depth -= 1;
      if (depth === 0) return args.slice(i, k + 1);
    }
  }
  return null;
}

/** 여는 따옴표 위치에서 닫는 따옴표 위치를 돌려준다(이스케이프 인식). */
function scanString(text, openQuote) {
  for (let k = openQuote + 1; k < text.length; k += 1) {
    if (text[k] === '\\') { k += 1; continue; }
    if (text[k] === '"') return k;
  }
  return text.length - 1;
}

/** @KafkaListener 의 topics 속성 값만 뽑는다 — groupId·containerFactory 를 토픽으로 오인하지 않는다. */
export function listenerTopicExpressions(rawSrc) {
  const out = [];
  for (const args of listenerArgRegions(stripComments(rawSrc))) {
    const value = topicsAttributeValue(args);
    if (!value) continue;
    for (const s of value.matchAll(/"((?:[^"\\]|\\.)*)"/g)) out.push(s[1]);
  }
  return out;
}

/** 리터럴이면 그대로, `${prop}` 이면 yml 기본값 또는 프로퍼티 표에서 푼다. */
export function resolveTopic(expr, props) {
  const literal = String(expr).match(/^(lemuel\.[a-z0-9_.]+)$/);
  if (literal) return literal[1];
  const placeholder = String(expr).match(/^\$\{([a-zA-Z0-9._-]+)(?::([^}]*))?\}$/);
  if (!placeholder) return null;
  if (placeholder[2] && placeholder[2].startsWith('lemuel.')) return placeholder[2];
  return props.get(placeholder[1].split('.').pop()) ?? null;
}

function walkSources(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkSources(p, out);
    else if (e.name.endsWith('.java') || e.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

/** 전 서비스에서 실제 구독되는 토픽 — 자기 발행 토픽의 자기 소비도 구독으로 센다(card.captured). */
function consumedTopics() {
  const consumed = new Map();
  let expressions = 0;
  for (const entry of readdirSync(REPO_ROOT, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.endsWith('-service')) continue;
    const ymlPath = join(REPO_ROOT, entry.name, 'src', 'main', 'resources', 'application.yml');
    const props = existsSync(ymlPath) ? topicProperties(readFileSync(ymlPath, 'utf8')) : new Map();
    for (const file of walkSources(join(REPO_ROOT, entry.name, 'src', 'main'))) {
      const raw = readFileSync(file, 'utf8');
      if (!raw.includes('@KafkaListener')) continue;
      for (const expr of listenerTopicExpressions(raw)) {
        expressions += 1;
        const topic = resolveTopic(expr, props);
        if (!topic) continue;
        if (!consumed.has(topic)) consumed.set(topic, new Set());
        consumed.get(topic).add(entry.name);
      }
    }
  }
  return { consumed, expressions };
}

describe('미소비 토픽 (SPEC.md §5 발행 전용 정책)', () => {
  const catalog = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'));
  const { consumed, expressions } = consumedTopics();

  // ── 검출기 자체 검증 — 구독을 못 읽으면 전 토픽이 "미소비"로 뜬다 ──
  test('[자기검증] topics 속성만 읽는다 — groupId·containerFactory 는 토픽이 아니다', () => {
    const src = '@KafkaListener(topics = "${app.kafka.topic.settlement-created}", '
      + 'groupId = "lemuel-account", containerFactory = "kafkaListenerContainerFactory")\n'
      + 'public void on(Record r) {}';

    assert.deepEqual(listenerTopicExpressions(src), ['${app.kafka.topic.settlement-created}']);
  });

  test('[자기검증] 리터럴·기본값·프로퍼티 3형태를 모두 푼다', () => {
    const props = new Map([['settlement-created', 'lemuel.settlement.created']]);
    assert.equal(resolveTopic('lemuel.ops.order.failed', props), 'lemuel.ops.order.failed');
    assert.equal(resolveTopic('${app.ops.signal.topics.order-created:lemuel.order.created}', props),
      'lemuel.order.created');
    assert.equal(resolveTopic('${app.kafka.topic.settlement-created}', props), 'lemuel.settlement.created');
    assert.equal(resolveTopic('kafkaListenerContainerFactory', props), null);
  });

  test('[자기검증] 주석 속 @KafkaListener 를 구독으로 세지 않는다', () => {
    assert.deepEqual(listenerTopicExpressions('// @KafkaListener(topics = "lemuel.x.y")\nvoid a(){}'), []);
    assert.deepEqual(listenerTopicExpressions('/* @KafkaListener(topics = "lemuel.x.y") */\nvoid a(){}'), []);
  });

  test('[자기검증] 다중 토픽 리스너를 모두 잡는다', () => {
    const src = '@KafkaListener(topics = {"lemuel.a.b", "lemuel.c.d"}, groupId = "g")\npublic void on() {}';
    assert.deepEqual(listenerTopicExpressions(src), ['lemuel.a.b', 'lemuel.c.d']);
  });

  test('[자기검증] 배열 항목이 ${...} 라 문자열 안에 } 가 있어도 끝까지 읽는다', () => {
    // account PointLedgerConsumer 의 실제 형태 — 이 케이스가 없어서 결함이 통과했었다.
    const src = [
      '@KafkaListener(topics = {',
      '        "${app.kafka.topic.point-charged}",',
      '        "${app.kafka.topic.point-granted}",',
      '        "${app.kafka.topic.point-used}",',
      '}, groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")',
      '@Transactional',
      'public void onPointEvent(ConsumerRecord<String, String> record) {}',
    ].join('\n');

    assert.deepEqual(listenerTopicExpressions(src), [
      '${app.kafka.topic.point-charged}',
      '${app.kafka.topic.point-granted}',
      '${app.kafka.topic.point-used}',
    ]);
  });

  test('[자기검증] 애노테이션 뒤 토큰이 무엇이든 인자 영역을 잘라낸다', () => {
    // 처음엔 뒤따르는 토큰을 (@|public|void…) 로 열거했다가 63개 중 13개를 놓쳤다.
    for (const tail of ['@Transactional', 'public', 'final', 'suspend fun', '\n  void']) {
      assert.deepEqual(listenerTopicExpressions(`@KafkaListener(topics = "lemuel.a.b")\n${tail} x(){}`),
        ['lemuel.a.b'], `뒤따르는 토큰 "${tail}" 에서 놓쳤다`);
    }
  });

  // ── 스캔 자체 검증 ──
  test('스캔이 실제로 리스너에 도달했다', () => {
    assert.ok(expressions >= 8, `리스너 표현식 수집이 적다(${expressions}) — 추출기가 깨졌을 수 있다`);
    assert.ok(consumed.size >= 5, `소비 토픽 수집이 적다(${consumed.size})`);
    // 확실히 소비되는 토픽이 안 잡히면 나머지 판정을 신뢰할 수 없다.
    for (const known of ['lemuel.order.created', 'lemuel.payment.captured']) {
      assert.ok(consumed.has(known), `${known} 가 소비 토픽으로 안 잡혔다 — 추출기 결함`);
    }
  });

  // ── 본 검사 ──
  test('소비처 없는 토픽은 발행 전용으로 선언돼 있다', () => {
    const undeclared = catalog.topics
      .map((t) => t.name)
      .filter((name) => !consumed.has(name) && !PUBLISH_ONLY.has(name))
      .sort();

    assert.deepEqual(undeclared, [],
      '발행만 하고 아무도 듣지 않는 토픽입니다. 소비자를 배선하거나, SPEC.md §5 "발행 전용" 절과 '
      + '이 게이트의 PUBLISH_ONLY 에 사유와 함께 등록하세요(ADR 0024 — 소비자가 생기면 계약 편입).');
  });

  // ── allowlist 위생 ──
  test('PUBLISH_ONLY 에 죽은 항목이 없다 — 소비자가 생겼으면 지워야 한다', () => {
    const nowConsumed = [...PUBLISH_ONLY.keys()]
      .filter((name) => consumed.has(name))
      .map((name) => `${name} ← ${[...consumed.get(name)].join(', ')}`);

    assert.deepEqual(nowConsumed, [],
      '소비자가 생겼는데 발행 전용으로 남아 있습니다 — 항목을 지우고 ADR 0024 절차로 계약을 편입하세요');
  });

  test('PUBLISH_ONLY 에 카탈로그 밖 토픽이 없다', () => {
    const declared = new Set(catalog.topics.map((t) => t.name));
    const unknown = [...PUBLISH_ONLY.keys()].filter((name) => !declared.has(name));

    assert.deepEqual(unknown, [], '카탈로그에 없는 토픽이 발행 전용으로 등록돼 있습니다');
  });

  test('PUBLISH_ONLY 의 모든 항목에 사유가 있다', () => {
    const bare = [...PUBLISH_ONLY.entries()].filter(([, why]) => !why || why.trim().length < 15);
    assert.deepEqual(bare.map(([n]) => n), [], '사유 없는 등록은 게이트를 끄는 것과 같습니다');
  });

  test('SPEC.md 에 발행 전용 정책 절이 살아 있다', () => {
    // 이 게이트의 PUBLISH_ONLY 는 SPEC 정책의 기계 대응물이다 — 정책 문단이 사라지면 근거가 사라진다.
    assert.match(readFileSync(SPEC_PATH, 'utf8'), /발행 전용\(소비처 미배선/,
      'SPEC.md §5 의 발행 전용 정책 문단을 찾지 못했습니다');
  });
});
