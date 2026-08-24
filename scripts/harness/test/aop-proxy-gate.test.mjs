// AOP 프록시 게이트 — "프록시와 내부 호출" 회귀 방지 (김영한 스프링 고급편 §14 적용).
//
// 스프링 AOP 는 프록시 기반이다. 같은 빈 안에서 `this.method()` 로 부가기능이 걸린 메서드를
// 부르면 프록시를 거치지 않으므로 @Retry·@CircuitBreaker·@Cacheable·@Async·@PreAuthorize 가
// **조용히 무력화**된다. 컴파일도 되고 테스트도 통과하는데 운영에서만 안 걸리는 부류라
// 리포 전수 스캔으로 잠근다 (oo-gate.test.mjs 와 동일 철학 — ArchUnit 1.3 이 Java 25
// 바이트코드를 못 읽어 소스 스캔).
//
// 대응은 강의의 "대안 3 — 구조 변경"(호출 대상을 별도 빈으로 분리)을 정본으로 한다.
// 자기 자신 주입/지연 조회(ObjectProvider)는 순환 참조를 만들어 채택하지 않는다.
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

/**
 * 자기호출로 우회되면 **동작 자체가 사라지는** 애노테이션 — 무관용.
 * (@Transactional(REQUIRED) 은 호출자가 이미 트랜잭션 안이면 결과가 같아 별도 테스트에서 다룬다.)
 */
const HARD_PROXY_ANNOTATIONS = [
  'Cacheable', 'CachePut', 'CacheEvict',
  'Retry', 'CircuitBreaker', 'RateLimiter', 'Bulkhead', 'TimeLimiter',
  'Async', 'PreAuthorize', 'PostAuthorize', 'Auditable',
];

/**
 * @Transactional 자기호출 허용 목록 — `경로::메서드명` 과 근거.
 * 호출자가 이미 트랜잭션 경계 안이라 전파(REQUIRED)로 합류하므로 결과가 동일한 경우만 허용한다.
 * REQUIRES_NEW 는 결과가 달라지므로 절대 허용하지 않는다(아래 테스트가 강제).
 */
const TRANSACTIONAL_SELF_CALL_ALLOWLIST = new Map([
  ['order-service/src/main/java/github/lms/lemuel/coupon/application/service/CouponService.java::validateCoupon',
    '클래스 레벨 @Transactional — 호출자 getAvailableCoupons 가 이미 tx 안, REQUIRED 합류로 동작 동일'],
  ['order-service/src/main/java/github/lms/lemuel/product/application/service/ProductImageService.java::setPrimaryImage',
    '호출자 uploadImages 가 @Transactional — 같은 tx 에 합류, 커밋 경계 동일'],
]);

const read = (f) => readFileSync(join(repoRoot, f), 'utf8');
const isComment = (l) => /^\s*(\/\/|\*|\/\*)/.test(l);

/** 애노테이션 줄 뒤에 오는 메서드 선언에서 메서드명을 뽑는다. */
function methodNameAfter(lines, from) {
  for (let j = from + 1; j < Math.min(from + 10, lines.length); j++) {
    const s = lines[j].trim();
    if (s === '' || s.startsWith('@') || isComment(s)) continue;
    const m = s.match(/^(?:public|protected|private)\s[\w<>\[\],.?\s]*?(\w+)\s*\(/);
    if (m) return m[1];
    if (/\bclass\b/.test(s)) return null;
  }
  return null;
}

/**
 * 파일에서 "프록시 의존 애노테이션이 붙은 메서드"를 찾아 이름 -> 애노테이션 목록으로 반환.
 * requiresNew 여부도 함께 기록한다.
 */
function proxiedMethods(lines, annotationNames) {
  const found = new Map();
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    const m = t.match(/^@(\w+)\b/);
    if (!m || !annotationNames.includes(m[1])) continue;
    const name = methodNameAfter(lines, i);
    if (!name) continue;
    const entry = found.get(name) ?? { annotations: new Set(), requiresNew: false };
    entry.annotations.add(m[1]);
    if (/REQUIRES_NEW/.test(t)) entry.requiresNew = true;
    found.set(name, entry);
  }
  return found;
}

/** 같은 파일 안에서 해당 메서드를 수식자 없이(또는 this.) 호출하는 지점. */
function selfCallSites(lines, name) {
  const decl = new RegExp('^(?:public|protected|private)\\s[\\w<>\\[\\],.?\\s]*?' + name + '\\s*\\(');
  const call = new RegExp('(^|[^\\w.])(this\\.)?' + name + '\\s*\\(');
  const excluded = new RegExp('(new\\s+|::|\\.)' + name);
  const sites = [];
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (isComment(t) || t.startsWith('@')) continue;
    if (decl.test(t)) continue;
    if (!call.test(t)) continue;
    if (excluded.test(t)) continue;
    sites.push({ line: i + 1, code: t.slice(0, 120) });
  }
  return sites;
}

function scan(annotationNames) {
  const violations = [];
  for (const f of mainJava) {
    const lines = read(f).split(/\r?\n/);
    const proxied = proxiedMethods(lines, annotationNames);
    for (const [name, meta] of proxied) {
      for (const site of selfCallSites(lines, name)) {
        violations.push({
          file: f, line: site.line, method: name,
          annotations: [...meta.annotations], requiresNew: meta.requiresNew, code: site.code,
        });
      }
    }
  }
  return violations;
}

const fmt = (v) => `${v.file}:${v.line} @${v.annotations.join('/@')} ${v.method}() 자기호출 — ${v.code}`;

describe('AOP 프록시 게이트 (프록시와 내부 호출 — 고급편 §14)', () => {
  test('부가기능이 사라지는 애노테이션(@Cacheable/@Retry/@CircuitBreaker/@Async/@PreAuthorize 등)의 자기호출이 없다', () => {
    assert.deepEqual(scan(HARD_PROXY_ANNOTATIONS).map(fmt), []);
  });

  test('@Transactional 자기호출은 근거가 기록된 허용 목록 안에만 존재한다', () => {
    const unlisted = scan(['Transactional'])
      .filter((v) => !TRANSACTIONAL_SELF_CALL_ALLOWLIST.has(`${v.file}::${v.method}`))
      .map(fmt);
    assert.deepEqual(unlisted, []);
  });

  test('@Transactional(REQUIRES_NEW) 자기호출은 허용 목록과 무관하게 금지된다', () => {
    assert.deepEqual(scan(['Transactional']).filter((v) => v.requiresNew).map(fmt), []);
  });

  test('허용 목록은 실제로 존재하는 파일/메서드만 가리킨다(사문화 방지)', () => {
    const stale = [];
    for (const key of TRANSACTIONAL_SELF_CALL_ALLOWLIST.keys()) {
      const [file, method] = key.split('::');
      if (!mainJava.includes(file)) { stale.push(`${key} — 파일 없음`); continue; }
      const lines = read(file).split(/\r?\n/);
      if (!proxiedMethods(lines, ['Transactional']).has(method)) stale.push(`${key} — @Transactional 메서드 아님`);
      else if (selfCallSites(lines, method).length === 0) stale.push(`${key} — 자기호출 사라짐(항목 제거 필요)`);
    }
    assert.deepEqual(stale, []);
  });
});
