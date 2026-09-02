/**
 * 프론트 호출 → 백엔드 도달 게이트 — <b>화면이 부르는데 아무 데도 닿지 않는</b> 경로를 빌드 시점에 드러낸다.
 *
 * <p>왜 필요한가: 기존 게이트 둘은 <b>둘 다 백엔드에서 출발한다</b>. {@code gateway-route-gate} 는
 * "컨트롤러가 라우팅되는가", {@code api-screen-gate} 는 "컨트롤러를 부르는 화면이 있는가" 를 묻는다.
 * 두 게이트 모두 정의역이 {@code lib/java-controllers.mjs} 의 컨트롤러 집합이라,
 * <b>컨트롤러가 어디에도 없는 프론트 호출</b>은 놓치는 것이 아니라 <b>표현할 수가 없다</b>.
 * 반대 방향에서 출발하는 게이트가 하나도 없었다.
 *
 * <p>실제로 그 구멍으로 새어 있었다(2026-09-03 발견): 감사로그 콘솔의 정산 탭이 부르던
 * {@code /admin/audit-trail} 이 게이트웨이 술어 어디에도 없었다. 컨트롤러가 이 저장소에 없으니
 * 위 두 게이트의 정의역 밖이고, 메뉴에는 안 걸려 있어 {@code menu-route-gate} 도 못 본다.
 * 세 게이트를 다 통과하면서 탭 하나가 통째로 죽어 있었다.
 *
 * <p><b>주석은 증거가 아니다.</b> 그 경로에는 "settlement-service 가 답한다"는 주석이 두 군데
 * 붙어 있었다({@code api/auditLog.ts}, {@code AdminAuditLogController}). 주석을 근거로 예외를
 * 허용하는 규칙이었다면 이 결함을 그대로 통과시켰을 것이다. 그래서 여기서 "닿는다"의 정의는
 * <b>실제 배선 파일에서 유도</b>한다 — 게이트웨이 {@code Path=} 술어와 nginx 프록시 location 뿐이다.
 *
 * 판정 방법:
 *   ① 프론트 {@code frontend/src} 전체(테스트 제외)에서 경로 리터럴을 모은다. 주석은 먼저 걷어낸다.
 *   ② 게이트웨이가 <b>점유를 선언한 최상위 세그먼트</b>(술어들의 첫 세그먼트)로 좁힌다.
 *      `/items` `/values` 처럼 헬퍼에 붙는 조각이나 `/sw.js` 같은 정적 자산을 구조적으로 걷어내는
 *      자리다 — 손으로 고른 목록이 아니라 게이트웨이 표에서 유도한다.
 *   ③ SPA 라우트(정확일치)를 뺀다. 화면 경로는 요청이 아니다. 라우트와 API 가 <b>겹칠</b> 때
 *      폴백이 API 를 삼키는 문제는 {@code spa-fallback-gate} 소관이라 여기서 다시 보지 않는다.
 *   ④ 남은 것은 도달 가능해야 한다. 아니면 아래 두 목록 중 하나에 사유와 함께 있어야 한다.
 *
 * <p>③ 의 정확일치는 규칙의 일부다. 접두사로 보면 SPA 라우트 `/admin` 이 `/admin/audit-trail` 을
 * 통째로 덮어 <b>바로 그 결함이 통과</b>한다 — 이 게이트를 짜면서 실제로 처음엔 그렇게 틀렸다.
 *
 * <p>한계: 형제 게이트와 달리 자바 컨트롤러를 읽지 않고 <b>배선 표</b>만 읽으므로,
 * {@code api-screen-gate} 가 명시한 "폴리글랏 7종(Kotlin/Go/Python)은 스캔하지 않는다" 제약이
 * 여기에는 없다 — 게이트웨이는 구현 언어와 무관하게 라우팅한다. 대신 이 게이트는
 * <b>라우팅이 있으면 통과</b>시킨다. 라우팅은 있는데 그 뒤에 컨트롤러가 없는 경우는 못 본다.
 * 그쪽은 {@code gateway-route-gate} 가 반대 방향에서 본다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { norm, walk } from '../lib/java-controllers.mjs';
import { gatewayPatterns, matchesPattern } from '../lib/gateway-routes.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * <b>요청이 아닌</b> 경로 리터럴과 사유. 문자열 모양만으로는 요청과 구분할 수 없는 것들이다 —
 * `AUDIT_SCOPE_PATHS`(요청 경로 맵)와 `ADMIN_ONLY_PATHS`(권한 술어 집합)는 둘 다 그냥
 * "경로 리터럴을 담은 const" 라 구문으로는 같다. 그래서 사유를 사람이 적는다.
 */
const NOT_A_REQUEST = new Map([
  ['/admin/settlement/payouts', 'MenuManagementPage 의 관리자 전용 메뉴 판별 술어 — 이 저장소에 화면이 없다(V20260825200000 에서 메뉴도 삭제됨)'],
  ['/admin/settlement/chargebacks', '같은 술어'],
  ['/admin/settlement/monthly-closing', '같은 술어'],
  ['/admin/settlement/commission-rates', '같은 술어'],
  ['/admin/settlement/dlq', '같은 술어'],
  ['/admin/system/...', 'MenuManagementPage 경로 입력칸의 placeholder 문자열'],
]);

/**
 * 부르는데 닿지 않는 경로 = <b>인정된 부채</b>. 줄어들기만 해야 한다.
 *
 * <p>여기에 올린다는 것은 "화면의 그 기능은 지금 죽어 있다"는 선언이다. 고치는 방법은 셋이다:
 * 백엔드를 만들거나, 게이트웨이/nginx 에 실제 라우트를 뚫거나, 부르는 쪽을 내린다.
 * <b>"다른 서비스가 답한다"는 주석은 셋 중 어느 것도 아니다</b> — 배선이 없으면 안 닿는다.
 */
// 2026-09-03: 1 (/admin/audit-trail — 감사로그 정산 탭. 게이트웨이 58패턴 어디에도 없었다.)
// 2026-09-03: 0 (f04affa 가 그 탭과 스코프를 내렸다. 이 게이트는 그 뒤에 들어가므로 0 에서 시작한다 —
//              부채를 물려받는 게 아니라, 같은 종류가 다시 생기면 그때 이 숫자를 올려야 한다.)
const DANGLING_DEBT = new Map([]);
const DANGLING_BUDGET = 0;

const read = (path) => readFileSync(join(REPO_ROOT, path), 'utf8');

/**
 * 주석을 걷어낸다. 걷어내지 않으면 <b>주석에 적힌 경로가 호출로 집계된다</b> —
 * 실제로 {@code /internal/notifications/send} 가 javadoc 한 줄에서 나와 후보에 올라왔었다.
 * 반대 방향의 사고도 있다: 형제 게이트는 주석 속 경로로 컨트롤러에 <b>크레딧을 줄</b> 수 있다.
 *
 * <p>줄 끝에 붙는 `// 주석` 은 걷어내지 않는다. `'https://…'` 같은 리터럴 안의 `//` 와
 * 구분하려면 파서가 필요한데, 여기서 얻을 것보다 잃을 것이 크다(코드 줄이 통째로 잘린다).
 * 줄 끝 주석에 적힌 경로는 이 게이트에 보인다 — 그때는 사유와 함께 분류하면 된다.
 */
const stripComments = (source) => source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

/** 프론트 소스의 경로 리터럴 → 어느 파일에서 나왔는지. */
function frontendPaths() {
  const found = new Map();
  for (const file of walk(join(REPO_ROOT, 'frontend', 'src'))) {
    if (!/\.(ts|tsx)$/.test(file) || file.includes('__tests__')) continue;
    // 형제 게이트와 같은 보정: 템플릿 보간을 먼저 균일 토큰으로 접고, 쿼리스트링을 허용하되
    // 경로만 캡처한다. 두 보정의 사유는 api-screen-gate 주석에 실측과 함께 적혀 있다.
    const source = stripComments(readFileSync(file, 'utf8')).replace(/\$\{[^}]*\}/g, '${x}');
    for (const m of source.matchAll(/['"`](\/[a-zA-Z0-9/{}$_.*-]+)(?:\?[^'"`]*)?['"`]/g)) {
      const path = norm(m[1]);
      if (!found.has(path)) found.set(path, new Set());
      found.get(path).add(file.slice(REPO_ROOT.length + 1));
    }
  }
  return found;
}

/**
 * nginx 가 게이트웨이를 <b>거치지 않고</b> 직접 프록시하는 접두사.
 *
 * <p>이것이 "다른 서비스가 답한다" 의 유일한 기계 증거다. 게이트웨이 표에 없어도 여기 있으면
 * 요청은 닿는다 — 주석이 아니라 배선이라서 믿을 수 있다. 오늘 이 저장소의 프론트는
 * 여기 해당하는 경로를 부르지 않지만, 규칙은 목록이 아니라 파일에서 유도한다.
 */
function nginxProxyPrefixes() {
  const prefixes = [];
  for (const conf of ['frontend/nginx.conf', 'frontend/nginx.compose.conf']) {
    const source = read(conf);
    for (const m of source.matchAll(/location\s+(?:\^~|=)\s+(\/\S*?)\s*\{/g)) {
      prefixes.push(m[1].replace(/\/+$/, '') || '/');
    }
  }
  return prefixes;
}

/** 게이트웨이가 점유를 선언한 최상위 세그먼트 — `/admin/**` → `admin`. */
function claimedRoots() {
  return new Set(gatewayPatterns(REPO_ROOT)
    .map((pattern) => pattern.split('/')[1])
    .filter((segment) => /^[a-zA-Z][a-zA-Z0-9-]*$/.test(segment)));
}

/** App.tsx 의 SPA 라우트. React Router 의 `:param` 은 형제 게이트의 `{var}` 와 같은 자리표로 접는다. */
function spaRoutes() {
  const source = read(join('frontend', 'src', 'App.tsx'));
  const routes = new Set();
  for (const m of source.matchAll(/path=["'`](\/[^"'`]*)["'`]/g)) {
    routes.add(norm(m[1].replace(/:[A-Za-z0-9_]+/g, '*')));
  }
  return routes;
}

/** 백엔드로 <b>닿는</b> 경로인가 — 게이트웨이 술어 또는 nginx 직프록시. */
function isReachable(path, patterns, proxies) {
  return patterns.some((pattern) => matchesPattern(pattern, path))
    || proxies.some((prefix) => path === prefix || path.startsWith(prefix + '/'));
}

/** 분류가 필요한 후보 — 백엔드 경로 공간에 있는데 닿지 않는 것들. */
function unreachablePaths() {
  const patterns = gatewayPatterns(REPO_ROOT);
  const proxies = nginxProxyPrefixes();
  const roots = claimedRoots();
  const routes = spaRoutes();

  const out = new Map();
  for (const [path, files] of frontendPaths()) {
    if (!roots.has(path.split('/')[1])) continue;      // ② 게이트웨이가 점유하지 않은 공간
    if (routes.has(path)) continue;                     // ③ 화면 경로(정확일치)
    if (isReachable(path, patterns, proxies)) continue; // ④ 닿는다
    out.set(path, [...files].sort());
  }
  return out;
}

const sorted = (values) => [...new Set(values)].sort();

test('프론트가 부르는 경로는 전부 백엔드에 닿거나, 사유와 함께 분류돼 있다', () => {
  const unclassified = [...unreachablePaths()]
    .filter(([path]) => !NOT_A_REQUEST.has(path) && !DANGLING_DEBT.has(path))
    .map(([path, files]) => `${path}  (${files.join(', ')})`)
    .sort();

  assert.deepEqual(unclassified, [],
    `게이트웨이·nginx 어디에도 없는 경로를 프론트가 들고 있습니다:\n  ${unclassified.join('\n  ')}\n`
    + '요청이라면 라우트를 뚫거나 부르는 쪽을 내리고(→ 해결), 요청이 아니라면 '
    + 'scripts/harness/test/frontend-call-gate.test.mjs 의 NOT_A_REQUEST 에 사유와 함께 '
    + '등록하세요. 지금은 못 고친다면 DANGLING_DEBT 에 올리고 DANGLING_BUDGET 을 함께 올리세요 — '
    + '그 상향 자체가 리뷰 대상입니다. "다른 서비스가 답한다"는 주석은 근거가 아닙니다.');
});

test('분류 목록에 이미 사라진 경로가 남아 있지 않다', () => {
  const live = unreachablePaths();
  const stale = sorted([...NOT_A_REQUEST.keys(), ...DANGLING_DEBT.keys()].filter((path) => !live.has(path)));

  assert.deepEqual(stale, [],
    `프론트에 더는 없는(또는 이제 닿는) 경로가 분류 목록에 남아 있습니다: ${stale.join(', ')}\n`
    + '목록에서 지우세요. 남겨 두면 다음 사람이 "검사되고 있다" 고 오해합니다.');
});

test('부채가 늘지 않았다 (예산은 내려가기만 한다)', () => {
  assert.ok(DANGLING_DEBT.size <= DANGLING_BUDGET,
    `닿지 않는 호출이 예산을 넘었습니다: ${DANGLING_DEBT.size} > ${DANGLING_BUDGET}.`);
  assert.equal(DANGLING_DEBT.size, DANGLING_BUDGET,
    `부채가 줄었는데 예산이 그대로입니다: ${DANGLING_DEBT.size} < ${DANGLING_BUDGET}. `
    + `DANGLING_BUDGET 을 ${DANGLING_DEBT.size} 로 내려 래칫을 조이세요.`);
});

test('추출기가 살아 있다 (스캔이 비면 판정 전체가 거짓이 된다)', () => {
  // 정규식이 깨져 0개가 되면 위 테스트들은 조용히 전부 통과한다. 형제 게이트와 같은 이유의 방어다.
  assert.ok(frontendPaths().size >= 80, '프론트 경로 스캔 결과가 비정상적으로 적습니다.');
  assert.ok(gatewayPatterns(REPO_ROOT).length >= 40, '게이트웨이 술어 스캔 결과가 비정상적으로 적습니다.');
  assert.ok(spaRoutes().size >= 40, 'SPA 라우트 스캔 결과가 비정상적으로 적습니다.');
  assert.ok(claimedRoots().size >= 5, '게이트웨이 점유 세그먼트가 비정상적으로 적습니다.');
});

test('[자기검증] 실제로 새어 나갔던 경로를 잡는다', () => {
  // 이 게이트가 존재하는 이유다. 2026-09-03 이전의 frontend/src/api/auditLog.ts 에는
  // `SETTLEMENT: '/admin/audit-trail'` 이 있었고, 게이트웨이 58패턴 어디에도 걸리지 않았다.
  const patterns = gatewayPatterns(REPO_ROOT);
  const proxies = nginxProxyPrefixes();

  assert.ok(claimedRoots().has('admin'),
    '게이트웨이가 /admin 공간을 점유하고 있어야 이 경로가 후보로 잡힌다');
  assert.ok(!isReachable('/admin/audit-trail', patterns, proxies),
    '/admin/audit-trail 은 어떤 배선에도 없다 — 여기가 통과하면 게이트가 무력하다');
  assert.ok(!spaRoutes().has('/admin/audit-trail'),
    'SPA 라우트가 아니어야 한다');

  // 접두사 매칭으로 SPA 라우트를 빼면 `/admin` 이 이것을 덮어 결함이 통과한다.
  // 처음 짤 때 실제로 그렇게 틀렸으므로 그 회귀를 여기서 고정한다.
  const routes = spaRoutes();
  assert.ok(routes.has('/admin'), '전제: /admin 은 SPA 라우트다');
  assert.ok(!routes.has('/admin/audit-trail'),
    'SPA 라우트 제외는 정확일치여야 한다 — 접두사로 보면 /admin 이 결함을 덮는다');
});

test('[자기검증] nginx 직프록시는 닿는 것으로 본다', () => {
  // 게이트웨이 표에 없어도 nginx 가 직접 프록시하면 요청은 닿는다.
  // 이 경로가 "안 닿는다" 로 잡히면 사람이 멀쩡한 호출을 지우게 된다.
  const proxies = nginxProxyPrefixes();
  assert.ok(proxies.length >= 2, 'nginx location 추출이 살아 있어야 한다');
  assert.ok(proxies.includes('/api/ai'), '^~ 접두 location 을 읽어야 한다');
  assert.ok(isReachable('/api/ai/chat', gatewayPatterns(REPO_ROOT), proxies),
    'nginx 가 프록시하는 하위 경로는 닿는 것으로 봐야 한다');
});
