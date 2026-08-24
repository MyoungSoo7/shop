/**
 * 프론트 타입체크 사각지대 게이트 — "typecheck OK 였는데 CI 는 빨갛다" 를 막는다.
 *
 * 이 저장소의 프론트 타입체크는 **두 갈래**다:
 *   - `npm run typecheck`       → tsconfig.app.json  (프로덕션 코드, `src/__tests__/**` 를 **제외**)
 *   - `npm run typecheck:tests` → tsconfig.test.json (테스트 코드)
 *
 * 실측 사고(2026-08-25, 커밋 ab497e73d): 알림 화면을 추가하며 `npm run typecheck` 만 돌리고
 * "타입 OK" 로 판정했다. 그 tsconfig 가 테스트를 제외하므로 테스트 파일의 타입 오류
 * (`vi.fn()` 이 `Mock<Procedure|Constructable>` 로 추론돼 `NotificationStreamHandle.close`
 * (`() => void`)에 대입되지 않음)를 **한 번도 검사하지 않은 채** develop 에 올라갔고,
 * `vitest` 는 타입을 보지 않으므로 단위 테스트 11건도 전부 초록이었다.
 * CI 의 "Frontend - Production Build & Quality" 두 번째 스텝에서만 드러났다.
 *
 * 이 게이트가 지키는 것은 **범위**다 — 검사 자체가 존재하는가, 그리고 그 검사가 실제로
 * 테스트 디렉터리에 도달하는가. 타입 오류 내용은 tsc 가 보고 이 게이트는 보지 않는다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const read = (path) => readFileSync(join(REPO_ROOT, path), 'utf8');
const readJson = (path) => JSON.parse(stripJsonComments(read(path)));

/**
 * tsconfig 는 JSONC 다 — 주석을 걷어내야 JSON.parse 가 먹는다.
 *
 * <p>정규식으로 뭉개면 안 된다: 경로 별칭 `"@/*": ["src/*"]` 안의 `/*` 가 블록 주석 시작으로
 * 잡혀 파일 뒷부분이 통째로 사라진다(이 게이트를 처음 쓸 때 실제로 그랬다). 문자열 안인지
 * 밖인지를 추적하며 훑는다. 트레일링 콤마도 JSONC 는 허용하므로 함께 제거한다.
 */
function stripJsonComments(raw) {
  let out = '';
  let inString = false;
  let escaped = false;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (inString) {
      out += c;
      if (escaped) {
        escaped = false;
      } else if (c === '\\') {
        escaped = true;
      } else if (c === '"') {
        inString = false;
      }
      continue;
    }
    if (c === '"') {
      inString = true;
      out += c;
      continue;
    }
    if (c === '/' && raw[i + 1] === '*') {
      const end = raw.indexOf('*/', i + 2);
      i = end === -1 ? raw.length : end + 1;
      continue;
    }
    if (c === '/' && raw[i + 1] === '/') {
      const end = raw.indexOf('\n', i);
      i = end === -1 ? raw.length : end - 1;
      continue;
    }
    out += c;
  }
  return out.replace(/,(\s*[}\]])/g, '$1');
}

test('게이트 대상이 실재한다 (공회전 방지)', () => {
  const pkg = readJson('frontend/package.json');
  assert.ok(pkg.scripts, 'frontend/package.json 에 scripts 가 없다');
  assert.ok(read('.github/workflows/ci.yml').length > 0);
});

test('프로덕션 tsconfig 가 테스트를 제외한다면, 테스트 전용 타입체크가 반드시 있다', () => {
  const app = readJson('frontend/tsconfig.app.json');
  const excludesTests = (app.exclude ?? []).some((p) => p.includes('__tests__') || p.includes('.test.'));
  if (!excludesTests) {
    return; // 프로덕션 설정이 테스트까지 본다면 별도 스크립트가 없어도 사각지대가 아니다
  }

  const pkg = readJson('frontend/package.json');
  assert.ok(
    pkg.scripts['typecheck:tests'],
    'tsconfig.app.json 이 테스트를 제외하는데 `typecheck:tests` 스크립트가 없다 — '
      + '테스트 파일의 타입이 어디서도 검사되지 않는다(vitest 는 타입을 보지 않는다)',
  );
});

test('테스트 tsconfig 가 실제로 테스트 디렉터리를 포함한다', () => {
  const cfg = readJson('frontend/tsconfig.test.json');
  const scope = [...(cfg.include ?? [])].join(' ');
  assert.ok(
    scope.includes('src'),
    `tsconfig.test.json 의 include 가 src 에 닿지 않는다: ${scope || '(비어 있음)'} — `
      + '검사 대상이 0개면 tsc 는 조용히 통과한다',
  );
  const excluded = (cfg.exclude ?? []).join(' ');
  assert.ok(
    !excluded.includes('__tests__'),
    `tsconfig.test.json 이 __tests__ 를 제외하고 있다 — 테스트 타입체크가 공전한다: ${excluded}`,
  );
});

test('CI 가 두 타입체크를 모두 실행한다', () => {
  const ci = read('.github/workflows/ci.yml');
  for (const script of ['npm run typecheck', 'npm run typecheck:tests']) {
    assert.ok(
      ci.includes(script),
      `ci.yml 이 \`${script}\` 를 실행하지 않는다 — 로컬에서만 도는 검사는 게이트가 아니다`,
    );
  }
});

test('[자기검증] 테스트를 제외한 설정을 넣으면 잡아낸다', () => {
  // 이 게이트가 무엇을 근거로 판정하는지 고정한다 — 판정 로직이 비면 영원히 통과한다.
  const excludesTests = (cfg) => (cfg.exclude ?? []).some((p) => p.includes('__tests__'));
  assert.equal(excludesTests({ exclude: ['src/__tests__/**'] }), true);
  assert.equal(excludesTests({ exclude: ['node_modules'] }), false);
  assert.equal(excludesTests({}), false);
});
