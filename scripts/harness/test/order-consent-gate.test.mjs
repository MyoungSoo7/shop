// 주문 동의 게이트 — "동의를 받지 않는 주문 경로"가 조용히 늘어나는 것을 막는다.
//
// 주문 생성 UseCase 는 동의(ConsentSubmission)를 마지막 인자로 받고, 그 자리를 비운 하위 호환
// 오버로드를 함께 둔다. 오버로드는 편의가 아니라 **선언**이다 — 그 경로는 개인정보 제3자 제공
// 동의를 받지 않는다는 뜻이고, 지금은 그런 경로가 넷뿐이다.
//
//   CreateMultiItemOrderUseCase#create      (…, ShippingAddressSnapshot, ConsentSubmission)  ← 5개가 정식
//   IdempotentMultiItemOrderUseCase#create  (…, ConsentSubmission, String idempotencyKey)    ← 6개가 정식
//
// 위험한 것은 새 호출자가 짧은 오버로드를 고르는 순간이다. 컴파일도 되고 테스트도 통과하고
// 주문도 정상으로 생성된다. 빠지는 것은 동의 이력 한 줄뿐이라 런타임에 아무 신호가 없다.
// 그래서 인자 개수로 소스를 전수 조사해, **미리 이름을 적어 둔 경로가 아니면** 떨어뜨린다.
//
// 리터럴 `null` 을 다섯 번째 자리에 넣는 우회도 같은 취급이다 — 정식 아리티를 부르면서
// 동의만 비우는 쪽이 오히려 눈에 안 띄기 때문이다.
//
// 반대 방향도 잠근다. 목록에 적혀 있는데 실제로는 그런 호출이 없으면 그것도 위반이다.
// 죽은 면제는 다음 사람에게 "여기는 원래 안 받는 곳" 이라는 잘못된 허가로 읽힌다.
//
// 탐지 로직 자체도 합성 픽스처로 검증한다. 실패할 수 없는 게이트는 아무것도 증명하지 못한다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const mainJava = execFileSync('git', ['ls-files', '*.java'], { cwd: repoRoot })
  .toString('utf8').split('\n').filter(Boolean)
  .filter((f) => f.includes('/src/main/java/'));

const read = (f) => readFileSync(join(repoRoot, f), 'utf8');

/** UseCase 타입별 "동의를 포함한" 정식 인자 개수. 이보다 짧으면 동의를 안 받는 경로다. */
const FULL_ARITY = {
  CreateMultiItemOrderUseCase: 5,
  IdempotentMultiItemOrderUseCase: 6,
};
/** 동의가 놓이는 자리(0-기준). 두 타입 모두 다섯 번째다. */
const CONSENT_INDEX = 4;

/**
 * 동의를 받지 않고 주문을 만드는 경로 — 여기 적힌 파일만 허용된다.
 *
 * <p>줄 번호가 아니라 파일로 붙든다. 줄은 리팩터링마다 밀리지만 "어느 경로인가" 는 안 밀린다.
 * 새 경로를 넣으려면 여기에 이유를 적어야 하고, 그 순간이 곧 리뷰 지점이다.
 */
const NO_CONSENT_PATHS = new Map([
  ['order-service/src/main/java/github/lms/lemuel/cart/application/service/CheckoutCartService.java',
    '장바구니 결제 — 동의 화면이 아직 붙지 않은 경로. 프런트가 /orders/multi 로 옮겨오면 지운다'],
  ['order-service/src/main/java/github/lms/lemuel/bulkorder/adapter/out/order/BulkOrderLineAdapter.java',
    '대량주문(B2B) — 계약서로 동의를 갈음하는 사업자 간 거래라 주문 시점 동의를 받지 않는다'],
  ['order-service/src/main/java/github/lms/lemuel/order/application/service/GiftClaimService.java',
    '선물 보내기 — 받는 사람의 개인정보는 이 시점에 아직 없다. 수령자가 배송지를 낼 때 받는다'],
]);

/** 포트 인터페이스의 default 오버로드 본문은 검사 대상이 아니다 — 그 자리가 곧 오버로드의 정의다. */
const PORT_DECLARATIONS = /\/application\/port\/in\//;

/** 문자열·문자 리터럴을 공백으로 지운다. 그 안의 괄호·콤마가 인자 파싱을 흔들지 않도록. */
export function blankLiterals(source) {
  let out = '';
  let i = 0;
  while (i < source.length) {
    const c = source[i];
    if (c === '"' || c === "'") {
      const quote = c;
      out += ' ';
      i++;
      while (i < source.length && source[i] !== quote) {
        if (source[i] === '\\') { out += '  '; i += 2; continue; }
        out += ' ';
        i++;
      }
      out += ' ';
      i++;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** `Type name` 형태로 선언된 수신자(필드·생성자 파라미터·지역변수) 이름을 모은다. */
export function receiverNames(source, type) {
  const names = new Set();
  const pattern = new RegExp(`\\b${type}\\s+(\\w+)\\b`, 'g');
  for (const m of source.matchAll(pattern)) names.add(m[1]);
  return names;
}

/** 여는 괄호 위치에서 시작해 균형 잡힌 인자 목록을 최상위 콤마로 쪼갠다. */
export function splitArguments(source, openParenIndex) {
  const args = [];
  let depth = 0;
  let current = '';
  for (let i = openParenIndex; i < source.length; i++) {
    const c = source[i];
    if (c === '(' || c === '[' || c === '{') {
      depth++;
      if (depth === 1) continue;
    } else if (c === ')' || c === ']' || c === '}') {
      depth--;
      if (depth === 0) {
        if (current.trim() !== '' || args.length > 0) args.push(current.trim());
        return args;
      }
    } else if (c === ',' && depth === 1) {
      args.push(current.trim());
      current = '';
      continue;
    }
    if (depth >= 1) current += c;
  }
  return null; // 괄호가 안 닫혔다 — 판정 불가
}

/**
 * 주문 생성 호출을 전부 찾아 동의를 실은 호출과 아닌 호출로 가른다.
 *
 * @param sources [경로, 내용] 쌍
 * @returns {{file: string, line: number, type: string, arity: number, withConsent: boolean, why: string}[]}
 */
export function findOrderCreationCalls(sources) {
  const calls = [];
  for (const [file, raw] of sources) {
    if (PORT_DECLARATIONS.test(file)) continue;
    const content = blankLiterals(raw);
    for (const [type, fullArity] of Object.entries(FULL_ARITY)) {
      if (!content.includes(type)) continue;
      for (const receiver of receiverNames(content, type)) {
        const callPattern = new RegExp(`\\b${receiver}\\s*\\.\\s*create\\s*\\(`, 'g');
        for (const m of content.matchAll(callPattern)) {
          const open = m.index + m[0].length - 1;
          const args = splitArguments(content, open);
          if (args === null) continue;
          const line = content.slice(0, m.index).split('\n').length;
          const consentArg = args.length === fullArity ? args[CONSENT_INDEX] : null;
          const withConsent = args.length === fullArity && consentArg !== 'null';
          const why = args.length < fullArity
            ? `인자 ${args.length}개 — 동의 자리를 뺀 오버로드`
            : (withConsent ? '동의 전달' : '동의 자리에 리터럴 null');
          calls.push({ file, line, type, arity: args.length, withConsent, why });
        }
      }
    }
  }
  return calls;
}

const repoSources = mainJava.map((f) => [f, read(f)]);
const repoCalls = findOrderCreationCalls(repoSources);
const repoNoConsent = repoCalls.filter((c) => !c.withConsent);

describe('주문 동의 게이트 (동의를 받지 않는 주문 경로는 이름으로 붙든다)', () => {
  test('동의를 받지 않는 주문 생성 경로는 전부 목록에 있다', () => {
    const undeclared = repoNoConsent
      .filter((c) => !NO_CONSENT_PATHS.has(c.file))
      .map((c) => `${c.file}:${c.line} ${c.type}#create — ${c.why}. `
        + '동의를 받든지, 왜 안 받는지를 order-consent-gate 의 NO_CONSENT_PATHS 에 적든지 하나를 해야 한다');
    assert.deepEqual(undeclared, []);
  });

  test('목록에 적힌 경로는 실제로 그런 호출을 갖고 있다 (죽은 면제 금지)', () => {
    const declaredFiles = new Set(repoNoConsent.map((c) => c.file));
    const stale = [...NO_CONSENT_PATHS.keys()]
      .filter((f) => !declaredFiles.has(f))
      .map((f) => `${f} — 동의 없는 주문 생성 호출이 더는 없다. 면제를 지워라 (다음 사람이 허가로 읽는다)`);
    assert.deepEqual(stale, []);
  });

  test('동의를 싣는 경로가 최소 하나는 살아 있다 (게이트 공회전 방지)', () => {
    const withConsent = repoCalls.filter((c) => c.withConsent);
    assert.ok(withConsent.length >= 1,
      `동의를 전달하는 주문 생성 호출이 0개 — 타입 이름이 바뀌어 게이트가 헛돌고 있을 수 있다. 찾은 호출 ${repoCalls.length}건`);
  });

  test('스캔 대상이 비어 있지 않다 (경로 규칙이 바뀌면 즉시 드러난다)', () => {
    assert.ok(repoCalls.length >= 4,
      `주문 생성 호출을 ${repoCalls.length}건밖에 못 찾았다 — 수신자 탐지가 깨졌을 수 있다`);
  });

  // ── 탐지력 자체 검증: 합성 픽스처로 게이트가 실제로 잡는지 증명한다 ──

  test('[자기검증] 짧은 오버로드를 부르면 동의 없는 호출로 잡는다', () => {
    const fixture = [['fake/Svc.java', [
      'class Svc {',
      '    private final CreateMultiItemOrderUseCase createOrderUseCase;',
      '    void go(Long userId, List<Line> lines) {',
      '        createOrderUseCase.create(userId, lines);',
      '    }',
      '}',
    ].join('\n')]];

    const calls = findOrderCreationCalls(fixture);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].arity, 2);
    assert.equal(calls[0].withConsent, false);
  });

  test('[자기검증] 정식 아리티로 동의를 넘기면 통과시킨다', () => {
    const fixture = [['fake/Svc.java', [
      'class Svc {',
      '    private final CreateMultiItemOrderUseCase delegate;',
      '    void go() {',
      '        delegate.create(userId, lines, couponCode, shippingAddress, consent);',
      '    }',
      '}',
    ].join('\n')]];

    const calls = findOrderCreationCalls(fixture);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].withConsent, true);
  });

  test('[자기검증] 정식 아리티라도 동의 자리가 리터럴 null 이면 잡는다', () => {
    const fixture = [['fake/Svc.java', [
      'class Svc {',
      '    private final CreateMultiItemOrderUseCase delegate;',
      '    void go() {',
      '        delegate.create(userId, lines, couponCode, shippingAddress, null);',
      '    }',
      '}',
    ].join('\n')]];

    const calls = findOrderCreationCalls(fixture);
    assert.equal(calls[0].arity, 5);
    assert.equal(calls[0].withConsent, false);
    assert.match(calls[0].why, /리터럴 null/);
  });

  test('[자기검증] 멱등 경로는 6개가 정식이라 5개짜리를 잡는다', () => {
    const fixture = [['fake/Gift.java', [
      'class Gift {',
      '    private final IdempotentMultiItemOrderUseCase createOrderUseCase;',
      '    void go() {',
      '        createOrderUseCase.create(senderUserId, lines, couponCode, null, idempotencyKey);',
      '    }',
      '}',
    ].join('\n')]];

    const calls = findOrderCreationCalls(fixture);
    assert.equal(calls[0].arity, 5);
    assert.equal(calls[0].withConsent, false);

    const full = findOrderCreationCalls([['fake/Ok.java', [
      'class Ok {',
      '    private final IdempotentMultiItemOrderUseCase useCase;',
      '    void go() {',
      '        useCase.create(userId, lines, couponCode, address, consent, idempotencyKey);',
      '    }',
      '}',
    ].join('\n')]]);
    assert.equal(full[0].withConsent, true);
  });

  test('[자기검증] 중첩 인자의 콤마를 인자 구분으로 세지 않는다', () => {
    const fixture = [['fake/Bulk.java', [
      'class Bulk {',
      '    private final CreateMultiItemOrderUseCase createMultiItemOrderUseCase;',
      '    void go() {',
      '        createMultiItemOrderUseCase.create(buyerUserId,',
      '                List.of(new CreateMultiItemOrderUseCase.Line(line.productId(), null, line.quantity())),',
      '                null,',
      '                new ShippingAddressSnapshot(a, b, c, d, e, f));',
      '    }',
      '}',
    ].join('\n')]];

    const calls = findOrderCreationCalls(fixture);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].arity, 4); // 4개로 세야 한다 — 중첩 콤마를 세면 10개가 된다
    assert.equal(calls[0].withConsent, false);
  });

  test('[자기검증] 문자열 안의 괄호·콤마에 흔들리지 않는다', () => {
    const fixture = [['fake/Str.java', [
      'class Str {',
      '    private final CreateMultiItemOrderUseCase useCase;',
      '    void go() {',
      '        useCase.create(userId, lines, "a,b(c", address, consent);',
      '    }',
      '}',
    ].join('\n')]];

    const calls = findOrderCreationCalls(fixture);
    assert.equal(calls[0].arity, 5);
    assert.equal(calls[0].withConsent, true);
  });

  test('[자기검증] 포트 인터페이스의 default 오버로드 본문은 세지 않는다', () => {
    const body = [
      'interface CreateMultiItemOrderUseCase {',
      '    default Order create(Long userId, List<Line> lines) {',
      '        return create(userId, lines, null, null, null);',
      '    }',
      '}',
    ].join('\n');

    assert.deepEqual(
      findOrderCreationCalls([['x/application/port/in/CreateMultiItemOrderUseCase.java', body]]), []);
  });

  test('[자기검증] 다른 이름의 create 호출에는 반응하지 않는다', () => {
    const fixture = [['fake/Other.java', [
      'class Other {',
      '    private final CreateMultiItemOrderUseCase useCase;',
      '    void go() {',
      '        somethingElse.create(a, b);',
      '        useCase.preview(a, b);',
      '    }',
      '}',
    ].join('\n')]];

    assert.deepEqual(findOrderCreationCalls(fixture), []);
  });
});
