// 트랜잭션 롤백 게이트 — "체크 예외는 커밋된다" 회귀 방지 (김영한 「스프링 DB 2편」 예외와 트랜잭션 커밋·롤백).
//
// 스프링 트랜잭션 AOP 의 기본 규칙:
//   - 언체크(RuntimeException) 예외 → **롤백**
//   - 체크(Exception) 예외        → **커밋**   ← 비즈니스 의미로 간주하기 때문
//
// 금융 도메인에서 이 기본값은 조용한 사고가 된다. @Transactional 안에서 체크 예외가 올라가면
// "실패했는데 커밋"이 되어 정산·원장·지급 데이터가 반쪽 상태로 남는다. 컴파일도 되고 테스트도
// 통과하므로 리뷰로 잡기 어렵다 — 그래서 소스 전수로 잠근다.
//
// 규칙 2개:
//   1. 도메인 예외는 전부 언체크여야 한다 (상속 체인을 끝까지 따라가 RuntimeException 도달 확인)
//   2. @Transactional 메서드가 체크 예외를 throws 하면 rollbackFor 를 반드시 명시해야 한다
//
// 이 파일은 탐지 로직 자체도 합성 픽스처로 검증한다. "실패할 수 없는 게이트"는 아무것도
// 증명하지 못한다는 것이 이 저장소가 Toss 재시도 결함에서 배운 교훈이다(docs/inflearn/spring.md §2).
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

/** JDK 표준 언체크 예외 루트 — 여기 닿으면 롤백된다. */
const UNCHECKED_ROOTS = new Set([
  'RuntimeException', 'IllegalStateException', 'IllegalArgumentException', 'NullPointerException',
  'UnsupportedOperationException', 'ArithmeticException', 'ClassCastException', 'Error',
  'IndexOutOfBoundsException', 'NoSuchElementException', 'ConcurrentModificationException',
  'DataAccessException', 'NestedRuntimeException', 'ResponseStatusException',
]);
/** JDK 표준 체크 예외 루트 — 여기 닿으면 커밋된다(롤백 안 됨). */
const CHECKED_ROOTS = new Set([
  'Exception', 'IOException', 'SQLException', 'InterruptedException', 'TimeoutException',
  'ClassNotFoundException', 'ExecutionException',
]);

/** `class X extends Y` 쌍을 뽑는다. */
export function parseExceptionTypes(sources) {
  const parents = new Map();
  for (const [file, content] of sources) {
    for (const line of content.split(/\r?\n/)) {
      const m = line.match(/\b(?:public\s+|abstract\s+|final\s+)*class\s+(\w+)\s+extends\s+([\w.]+)/);
      if (!m) continue;
      parents.set(m[1], { parent: m[2].split('.').pop(), file });
    }
  }
  return parents;
}

/**
 * 상속 체인을 따라가 롤백되는 예외인지 판정한다.
 * 반환: 'unchecked' | 'checked' | 'unknown'
 */
export function classify(name, parents, seen = new Set()) {
  if (UNCHECKED_ROOTS.has(name)) return 'unchecked';
  if (CHECKED_ROOTS.has(name)) return 'checked';
  if (seen.has(name)) return 'unknown'; // 순환 방어
  seen.add(name);
  const entry = parents.get(name);
  if (!entry) return 'unknown';
  return classify(entry.parent, parents, seen);
}

/** @Transactional 메서드 중 체크 예외를 throws 하면서 rollbackFor 가 없는 지점. */
export function findUnguardedCheckedThrows(sources, parents) {
  const violations = [];
  for (const [file, content] of sources) {
    const lines = content.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (!/^\s*@Transactional\b/.test(lines[i])) continue;
      const hasRollbackFor = /rollbackFor/.test(lines[i]);
      let sig = '';
      for (let j = i + 1; j < Math.min(i + 12, lines.length); j++) {
        const s = lines[j].trim();
        if (s === '' || s.startsWith('@') || /^(\/\/|\*|\/\*)/.test(s)) continue;
        sig += ' ' + s;
        if (s.includes('{') || s.includes(';')) break;
      }
      const m = sig.match(/\bthrows\s+([\w.,\s]+?)\s*[{;]/);
      if (!m) continue;
      const checked = m[1].split(',')
        .map((t) => t.trim().split('.').pop())
        .filter(Boolean)
        .filter((t) => classify(t, parents) === 'checked');
      if (checked.length > 0 && !hasRollbackFor) {
        violations.push(`${file}:${i + 1} @Transactional + throws ${checked.join(',')} — rollbackFor 없음 → 예외가 나도 커밋된다`);
      }
    }
  }
  return violations;
}

const repoSources = mainJava.map((f) => [f, read(f)]);
const repoParents = parseExceptionTypes(repoSources);
const domainExceptionFiles = mainJava.filter((f) => /\/domain\/exception\//.test(f));

describe('트랜잭션 롤백 게이트 (체크 예외는 커밋된다 — 스프링 DB 2편)', () => {
  test('도메인 예외는 전부 언체크다 (체크 예외면 @Transactional 이 롤백하지 않는다)', () => {
    const violations = [];
    for (const f of domainExceptionFiles) {
      const declared = [...read(f).matchAll(/\bclass\s+(\w+)\s+extends\s+([\w.]+)/g)].map((m) => m[1]);
      for (const name of declared) {
        const verdict = classify(name, repoParents);
        if (verdict !== 'unchecked') {
          violations.push(`${f} — ${name} 은 ${verdict} (RuntimeException 계열이어야 롤백된다)`);
        }
      }
    }
    assert.deepEqual(violations, []);
  });

  test('@Transactional + 체크 예외 throws 는 rollbackFor 를 명시한다', () => {
    assert.deepEqual(findUnguardedCheckedThrows(repoSources, repoParents), []);
  });

  test('도메인 예외 스캔 대상이 비어 있지 않다 (게이트 공회전 방지)', () => {
    assert.ok(domainExceptionFiles.length >= 50,
      `도메인 예외 파일이 ${domainExceptionFiles.length}개 — 경로 규칙이 바뀌어 게이트가 헛돌고 있을 수 있다`);
  });

  // ── 탐지력 자체 검증: 게이트가 실제로 위반을 잡는지 합성 픽스처로 증명한다 ──

  test('[자기검증] 체크 예외를 상속한 도메인 예외를 잡아낸다', () => {
    const fixture = [
      ['fake/domain/exception/BadOne.java', 'public class BadOne extends Exception {}'],
      ['fake/domain/exception/GoodOne.java', 'public class GoodOne extends RuntimeException {}'],
    ];
    const parents = parseExceptionTypes(fixture);
    assert.equal(classify('BadOne', parents), 'checked');
    assert.equal(classify('GoodOne', parents), 'unchecked');
  });

  test('[자기검증] 간접 상속 체인 끝까지 따라간다', () => {
    const fixture = [
      ['a.java', 'public class MidChecked extends Exception {}'],
      ['b.java', 'public class LeafChecked extends MidChecked {}'],
      ['c.java', 'public class MidUnchecked extends RuntimeException {}'],
      ['d.java', 'public class LeafUnchecked extends MidUnchecked {}'],
    ];
    const parents = parseExceptionTypes(fixture);
    assert.equal(classify('LeafChecked', parents), 'checked');
    assert.equal(classify('LeafUnchecked', parents), 'unchecked');
  });

  test('[자기검증] rollbackFor 없는 @Transactional + 체크 예외를 잡고, 있으면 통과시킨다', () => {
    const parents = parseExceptionTypes([['x.java', 'public class MyChecked extends Exception {}']]);

    const bad = [['Bad.java', [
      '    @Transactional',
      '    public void settle() throws MyChecked {',
    ].join('\n')]];
    assert.equal(findUnguardedCheckedThrows(bad, parents).length, 1);

    const guarded = [['Guarded.java', [
      '    @Transactional(rollbackFor = MyChecked.class)',
      '    public void settle() throws MyChecked {',
    ].join('\n')]];
    assert.deepEqual(findUnguardedCheckedThrows(guarded, parents), []);

    const unchecked = [['Fine.java', [
      '    @Transactional',
      '    public void settle() throws IllegalStateException {',
    ].join('\n')]];
    assert.deepEqual(findUnguardedCheckedThrows(unchecked, parents), []);
  });
});
