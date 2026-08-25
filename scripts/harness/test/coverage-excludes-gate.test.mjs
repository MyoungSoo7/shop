// 커버리지 제외 목록 게이트 — 두 빌드가 같은 목록을 쓰는가, 그 목록이 실제 코드와 맞는가.
//
// 막는 것: **문턱을 못 넘어서 아무도 안 켜는 게이트**.
//
// 실측 결함: 제외 목록이 build.gradle.kts 와 shared-common/build.gradle.kts 에 따로 적혀 있었다.
// shared-common 은 독립 빌드(includeBuild)라 루트의 subprojects {} 를 상속받지 않기 때문에,
// 한쪽을 고쳐도 다른 쪽은 그대로 남는다. 루트가 어댑터·config·util 22개 패턴을 제외하는 동안
// shared-common 은 `**/common/pdf/**` 하나만 제외했고, 그 상태의 LINE 은 81% — 자기가 선언한
// 90% 문턱 아래였다. 그런데도 CI 는 초록이었다: `test` 는 `jacocoTestCoverageVerification` 에
// 의존하지 않고 `check` 만 의존한다.
//
// 그래서 목록을 gradle/coverage-excludes.txt 하나로 모으고, 이 게이트가 두 가지를 본다.
//   (1) 빌드 스크립트가 그 파일만 읽는가 — 패턴을 다시 인라인으로 적으면 드리프트가 재개된다.
//   (2) 부트 진입점 목록이 실제 소스와 일치하는가 — "새 서비스를 추가하면 여기에도 한 줄
//       추가할 것"이라는 주석은 지켜지지 않는다. java-controllers 로스터가 settlement 시절
//       이름 8개를 들고 있던 것과 같은 종류의 결함이다.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const EXCLUDES_PATH = join(REPO_ROOT, 'gradle', 'coverage-excludes.txt');
const BUILD_SCRIPTS = ['build.gradle.kts', join('shared-common', 'build.gradle.kts')];

/** 빌드 스크립트의 파서와 같은 규칙 — `#` 뒤 주석 제거, 트림, 빈 줄 제거. */
export function parseExcludes(text) {
  return String(text)
    .split('\n')
    .map((line) => line.split('#')[0].trim())
    .filter(Boolean);
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

/**
 * 실제 부트 진입점. 애노테이션 문자열만 보면 안 된다 — 주석·javadoc 에서 언급하는 파일이
 * 있다(order-service 의 PersistenceConfig). main 메서드까지 있어야 진입점이다.
 */
function bootEntrypoints() {
  const found = [];
  for (const module of readdirSync(REPO_ROOT, { withFileTypes: true })) {
    if (!module.isDirectory()) continue;
    const src = join(REPO_ROOT, module.name, 'src', 'main', 'java');
    if (!existsSync(src)) continue;
    for (const file of walk(src)) {
      if (!file.endsWith('.java')) continue;
      const text = readFileSync(file, 'utf8');
      if (/^\s*@SpringBootApplication/m.test(text) && /static\s+void\s+main\s*\(/.test(text)) {
        found.push(file.slice(file.lastIndexOf('/') + 1).replace(/\.java$/, ''));
      }
    }
  }
  return found.sort();
}

/** jacocoTestCoverageVerification 블록만 잘라낸다 — 중괄호 균형으로 끝을 찾는다. */
function verificationBlock(text) {
  const start = text.indexOf('jacocoTestCoverageVerification');
  if (start < 0) return '';
  let depth = 0;
  let i = text.indexOf('{', start);
  if (i < 0) return '';
  const from = i;
  for (; i < text.length; i += 1) {
    if (text[i] === '{') depth += 1;
    else if (text[i] === '}') {
      depth -= 1;
      if (depth === 0) return text.slice(from, i + 1);
    }
  }
  return text.slice(from);
}

describe('커버리지 제외 목록 게이트 (갈라지면 한쪽 게이트가 꺼진다)', () => {
  const excludesText = readFileSync(EXCLUDES_PATH, 'utf8');
  const patterns = parseExcludes(excludesText);

  test('정본 파일이 있고 패턴이 비어 있지 않다', () => {
    // 빈 목록은 게이트를 더 엄격하게 만드는 게 아니라, 무엇을 재는지 아무도 모르게 만든다.
    assert.ok(patterns.length > 0, 'gradle/coverage-excludes.txt 가 비었다');
    for (const pattern of patterns) {
      assert.ok(!pattern.includes('"'), `Kotlin 문법이 섞였다: ${pattern}`);
      assert.ok(!pattern.endsWith(','), `줄 끝 쉼표가 남았다: ${pattern}`);
    }
  });

  test('두 빌드가 모두 정본 파일을 읽는다', () => {
    for (const script of BUILD_SCRIPTS) {
      const text = readFileSync(join(REPO_ROOT, script), 'utf8');
      assert.match(
        text,
        /coverage-excludes\.txt/,
        `${script} 가 정본 목록을 읽지 않는다 — 이 스크립트의 게이트는 다른 목록으로 돈다`,
      );
    }
  });

  test('빌드 스크립트에 제외 패턴을 인라인으로 다시 적지 않았다', () => {
    // 이게 드리프트의 유일한 재발 경로다. 한쪽에만 한 줄 추가하는 순간 두 목록이 갈라진다.
    for (const script of BUILD_SCRIPTS) {
      const block = verificationBlock(readFileSync(join(REPO_ROOT, script), 'utf8'));
      assert.ok(block, `${script} 에서 jacocoTestCoverageVerification 블록을 찾지 못했다`);
      const inline = [...block.matchAll(/"(\*\*\/[^"]*)"/g)].map((m) => m[1]);
      assert.deepEqual(
        inline,
        [],
        `${script} 안에 제외 패턴이 직접 적혀 있다 — 정본은 gradle/coverage-excludes.txt 하나다: ${inline}`,
      );
    }
  });

  test('부트 진입점 제외 목록이 실제 소스와 일치한다', () => {
    // 새 서비스를 추가하고 이 줄을 잊으면 그 서비스의 main 이 커버리지 분모에 남아
    // 문턱을 조용히 끌어내린다. 반대로 없어진 서비스의 패턴은 아무것도 제외하지 않으면서
    // "이 서비스도 여기 있다"는 인상만 남긴다 — 로스터가 8개 중 6개가 유령이던 것과 같다.
    const declared = patterns
      .filter((p) => /Application\*$/.test(p))
      .map((p) => p.replace(/^\*\*\//, '').replace(/\*$/, ''))
      .sort();
    assert.deepEqual(
      declared,
      bootEntrypoints(),
      '제외 목록의 부트 진입점과 실제 @SpringBootApplication main 이 다르다',
    );
  });

  test('[자기검증] 목록이 shared-common 의 실측 사각지대를 실제로 덮는다', () => {
    // 이 게이트가 통과만 확인하는 물건이 되지 않도록, 수렴시킨 이유였던 경로들을 박제한다.
    // 이 패턴들이 빠지면 shared-common 의 LINE 은 다시 81% 로 내려가 90% 문턱을 못 넘는다.
    for (const required of ['**/adapter/out/persistence/**', '**/config/**', '**/common/pdf/**']) {
      assert.ok(patterns.includes(required), `${required} 가 목록에서 빠졌다`);
    }
  });

  test('제외 목록이 모듈 전체를 삼키지 않는다', () => {
    // 전부 제외하면 측정 대상이 0개가 되고 게이트는 "위반 없음"으로 통과한다.
    // 빌드 쪽 requireNonEmptyCoverageScope 가 실행 시점에 잡지만, 여기서 먼저 막는다.
    assert.ok(!patterns.includes('**'), '모든 것을 제외하면 게이트는 아무것도 재지 않는다');
    assert.ok(!patterns.some((p) => /^\*\*\/\*+$/.test(p)), `사실상 전량 제외 패턴이 있다: ${patterns}`);
  });

  test('정본 파일이 빌드 루트 밖이 아니다 (shared-common 이 한 단계 위를 본다)', () => {
    // shared-common 은 독립 빌드라 rootDir 이 shared-common/ 이다. 정본을 그 안으로 옮기면
    // 루트가 못 읽고, 루트 안 깊숙이 옮기면 shared-common 이 못 읽는다. 저장소 루트의
    // gradle/ 만이 두 빌드가 함께 볼 수 있는 자리다.
    assert.ok(statSync(EXCLUDES_PATH).isFile());
    assert.match(
      readFileSync(join(REPO_ROOT, 'shared-common', 'build.gradle.kts'), 'utf8'),
      /rootDir\.resolveSibling\("gradle"\)/,
      'shared-common 이 저장소 루트의 gradle/ 을 가리키지 않는다',
    );
  });
});
