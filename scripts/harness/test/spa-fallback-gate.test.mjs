/**
 * SPA 폴백 게이트 — "클릭하면 되는데 <b>F5 를 누르면 404</b>" 를 빌드 시점에 드러낸다.
 *
 * <p>{@code /admin} 은 프론트 SPA 라우트와 백엔드 admin API 가 <b>접두사를 공유</b>하는 유일한
 * 구간이다. nginx 는 이 충돌을 allowlist 로 가른다: 폴백 정규식에 걸리는 것만 {@code index.html}
 * 로 내려보내고(딥링크·새로고침), 나머지 {@code /admin/*} 는 전부 게이트웨이로 프록시한다.
 *
 * <p>그래서 <b>화면 URL 을 그 allowlist 밖에 두면 조용히 깨진다</b>. 깨지는 방식이 고약하다:
 *   - 클릭 이동은 멀쩡하다. React Router 가 처리하므로 서버 요청 자체가 없다.
 *   - <b>새로고침·북마크·주소 직접 입력·새 탭</b>에서만 서버로 간다. 그때 백엔드 라우트가
 *     없으면 404, 있으면 화면 대신 <b>API JSON(또는 401)이 브라우저에 그대로 렌더</b>된다.
 *   - vite dev 에는 nginx 가 없어 <b>개발에서는 재현되지 않는다</b>.
 * 컴파일러도, 프론트 테스트도, {@code menu-route-gate} 도 못 보는 사각지대다.
 *
 * <p>실제로 라우트 5개가 이렇게 새어 나갔다(2026-08-21 실측). 이 불변식이 여태
 * {@code App.tsx} <b>주석에만</b> 있었기 때문이다 — 주석을 읽은 사람은 지켰고, 못 본 사람은 안 지켰다.
 *
 * <p>검사 넷:
 *   ① nginx 두 벌(k8s 이미지용·compose 용)의 폴백 정규식이 같고, 일반 프록시보다 <b>먼저</b> 온다.
 *      nginx 정규식 location 은 <b>등장 순서</b>로 매칭되므로 순서가 규칙의 일부다.
 *   ② 폴백 접두사가 게이트웨이의 {@code /admin} 라우트를 <b>가리지 않는다</b>.
 *      — 이 방향이 없으면 "폴백 목록에 한 줄 추가"라는 손쉬운 오답이 통과한다. 그 한 줄은
 *        이번엔 프론트가 그 API 를 못 부르게 만든다(화면 대신 index.html 이 응답된다).
 *   ③ 모든 {@code /admin} 라우트는 폴백에 걸리거나, 사유와 함께 부채로 등록돼 있다(예산은 내려가기만).
 *   ④ 부채 목록에 이미 사라진 라우트가 남아 있지 않다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { gatewayPatterns, literalPrefix } from '../lib/gateway-routes.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (path) => readFileSync(join(REPO_ROOT, path), 'utf8');

const APP_TSX = 'frontend/src/App.tsx';

/** SPA 를 서빙하는 nginx 설정 두 벌 — 한쪽만 고치면 "로컬은 되는데 배포는 안 되는" 경로가 생긴다. */
const NGINX_CONFS = ['frontend/nginx.conf', 'frontend/nginx.compose.conf'];

/**
 * 폴백에서 빠져 있는 {@code /admin} 라우트 = <b>인정된 부채</b>. 새로고침하면 지금 깨진다.
 *
 * <p>등록하기 전에 먼저 고칠 수 있는지 보라. 고치는 법은 둘이다:
 *   - 화면이 <b>네비 그룹</b>이면 nginx 폴백 목록에 그룹으로 등록한다(백엔드 API 와 겹치지 않을 때만).
 *   - 그 외에는 <b>화면 URL 을 그룹 아래로 옮긴다</b>(`/admin/&lt;그룹&gt;/&lt;화면&gt;`).
 * 백엔드 API 와 URL 이 겹치는 화면은 반드시 후자다 — 폴백에 넣으면 프론트가 그 API 를 못 부른다.
 */
const FALLBACK_PENDING = new Map([
  // 2026-08-21: 신설 시점의 5건을 모두 갚았다.
  //   그룹 등록 2건 — /admin/shipping(배송) · /admin/approvals(승인)
  //   URL 이동 3건 — payouts → settlement/payouts · shipping-policies → shipping/policies
  //                  · education/courses → system/education (셋 다 백엔드 API 와 URL 이 겹쳤다)
]);

/** 폴백 미등록 부채의 상한. <b>내려가기만 한다</b> — 고쳤으면 이 수를 함께 내린다. */
// 2026-08-21: 5 (게이트 신설 시점의 실측값) → 0 (전건 수리)
const PENDING_BUDGET = 0;

const sorted = (values) => [...new Set(values)].sort();

/**
 * nginx 설정에서 SPA 폴백 정규식을 뽑는다.
 *
 * <p>{@code location ~ ^/admin(...)} 한 줄이 규칙 전체다. 이 게이트는 그 문자열을 <b>실행</b>해서
 * 판정한다 — 눈으로 읽고 "맞겠지" 하는 것과 달리, 정규식이 바뀌면 판정도 따라 바뀐다.
 */
function fallbackRegexSource(config) {
  return /location\s+~\s+(\^\/admin\S*)\s*\{/.exec(config)?.[1] ?? null;
}

/**
 * 일반 {@code /admin} 프록시 location — 폴백은 반드시 이보다 먼저 와야 한다.
 *
 * <p>세그먼트 목록 안 어디에 {@code admin} 이 있든(맨 앞이어도) 찾도록 단어 경계로 본다.
 * 못 찾으면 {@code -1} 을 돌려주고 호출부가 <b>큰 소리로 실패</b>한다 — 프록시 location 을
 * 못 읽은 채 "순서 정상"으로 통과하는 것이 이 검사에서 제일 나쁜 결과다.
 */
function proxyLocationIndex(config) {
  return config.search(/location\s+~\s+\^\/\((?=[^)]*\badmin\b)/);
}

/** {@code App.tsx} 의 {@code /admin} 라우트. 경로변수는 표본 세그먼트로 접는다. */
function adminRoutes() {
  return sorted([...read(APP_TSX).matchAll(/<Route\s+path="(\/admin[^"]*)"/g)]
    .map((m) => m[1].replace(/:[^/]+/g, 'x')));
}

test('게이트 대상이 비어 있지 않다 (공회전 방지)', () => {
  // 추출기가 조용히 0건을 반환하면 아래 판정이 전부 "위반 없음"으로 거짓 통과한다.
  assert.ok(adminRoutes().length > 20, `/admin 라우트를 거의 못 읽었습니다: ${adminRoutes().length}건`);
  assert.ok(gatewayPatterns(REPO_ROOT).length > 20, '게이트웨이 Path 술어를 거의 못 읽었습니다.');
});

test('① nginx 두 벌의 SPA 폴백 정규식이 같다', () => {
  const sources = NGINX_CONFS.map((file) => [file, fallbackRegexSource(read(file))]);
  for (const [file, source] of sources) {
    assert.ok(source, `${file}: SPA 폴백 location(~ ^/admin...)을 찾지 못했습니다.`);
  }
  const [[, first], [fileB, second]] = sources;
  assert.equal(second, first,
    `${fileB} 의 폴백 정규식이 ${NGINX_CONFS[0]} 와 다릅니다 — 두 배포 경로의 동작이 갈립니다.`);
});

test('① SPA 폴백 location 이 일반 /admin 프록시보다 먼저 온다', () => {
  // nginx 정규식 location 은 등장 순서로 매칭된다. 뒤로 밀리면 폴백은 영영 실행되지 않는데,
  // 설정만 봐서는 규칙이 그대로 있어 보인다.
  for (const file of NGINX_CONFS) {
    const config = read(file);
    const fallbackAt = config.search(/location\s+~\s+\^\/admin/);
    const proxyAt = proxyLocationIndex(config);
    assert.ok(proxyAt >= 0, `${file}: 일반 /admin 프록시 location 을 찾지 못했습니다.`);
    assert.ok(fallbackAt >= 0 && fallbackAt < proxyAt,
      `${file}: SPA 폴백 location 이 일반 프록시보다 뒤에 있습니다 — 폴백이 실행되지 않습니다.`);
  }
});

test('② SPA 폴백이 게이트웨이의 /admin API 라우트를 가리지 않는다', () => {
  const fallback = new RegExp(fallbackRegexSource(read(NGINX_CONFS[0])));
  const shadowed = sorted(gatewayPatterns(REPO_ROOT)
    .filter((pattern) => pattern.startsWith('/admin'))
    .map(literalPrefix)
    // 패턴이 점유하는 공간의 두 표본: 컬렉션 루트와 그 하위. 하나라도 폴백에 걸리면
    // 그 API 는 index.html 을 응답으로 받는다.
    .filter((prefix) => fallback.test(prefix) || fallback.test(`${prefix}/x`)));

  assert.deepEqual(shadowed, [],
    `SPA 폴백이 백엔드 API 경로를 삼킵니다: ${shadowed.join(', ')}\n`
    + '폴백 allowlist 에 API 와 겹치는 접두사를 넣으면 프론트가 그 API 를 못 부릅니다.\n'
    + '화면 URL 을 겹치지 않는 접두사로 옮기세요(예: /admin/settlement/** 아래).');
});

test('③ 모든 /admin 라우트가 SPA 폴백에 걸린다 (새로고침이 살아 있다)', () => {
  const fallback = new RegExp(fallbackRegexSource(read(NGINX_CONFS[0])));
  const broken = adminRoutes()
    .filter((route) => !fallback.test(route) && !FALLBACK_PENDING.has(route));

  assert.deepEqual(broken, [],
    `nginx SPA 폴백에서 빠진 /admin 라우트입니다 — 새로고침·북마크·새 탭에서 깨집니다: ${broken.join(', ')}\n`
    + `현재 폴백: ${fallback.source}\n`
    + '화면 URL 을 폴백 접두사 아래로 옮기거나(권장), 아직 못 고친다면 '
    + 'scripts/harness/test/spa-fallback-gate.test.mjs 의 FALLBACK_PENDING 에 사유와 함께 등록하세요.');
});

test('③ 폴백 미등록 부채가 예산을 넘지 않는다 (래칫)', () => {
  assert.ok(FALLBACK_PENDING.size <= PENDING_BUDGET,
    `폴백 미등록 라우트가 늘었습니다: ${FALLBACK_PENDING.size} > ${PENDING_BUDGET}`);
  assert.equal(FALLBACK_PENDING.size, PENDING_BUDGET,
    `부채를 갚았다면 PENDING_BUDGET 을 ${FALLBACK_PENDING.size} 로 내리세요 (래칫은 되돌아가지 않습니다).`);
});

test('④ 부채 목록에 이미 사라진 라우트가 없다', () => {
  const routes = new Set(adminRoutes());
  const stale = [...FALLBACK_PENDING.keys()].filter((route) => !routes.has(route));

  assert.deepEqual(stale, [],
    `App.tsx 에 없는 라우트가 FALLBACK_PENDING 에 남아 있습니다: ${stale.join(', ')}`);
});

test('④ 부채 목록의 항목은 실제로 폴백에서 빠져 있다', () => {
  // 고쳐 놓고 목록에서 안 지우면, 그 항목이 예산을 차지한 채 다음 위반을 숨긴다.
  const fallback = new RegExp(fallbackRegexSource(read(NGINX_CONFS[0])));
  const fixed = [...FALLBACK_PENDING.keys()].filter((route) => fallback.test(route));

  assert.deepEqual(fixed, [],
    `이미 폴백에 걸리는데 부채로 남아 있습니다: ${fixed.join(', ')} — 목록에서 지우고 예산을 내리세요.`);
});

test('[자기검증] 폴백 추출기와 판정이 실제로 위반을 잡아낸다', () => {
  const sample = `
    location ~ ^/admin(/(system|ceo)(/|$)|/?$) {
        try_files $uri /index.html;
    }
    location ~ ^/(auth|api|admin|users)(/|$) {
        proxy_pass http://gateway-service:8080;
    }
  `;
  const source = fallbackRegexSource(sample);
  assert.equal(source, '^/admin(/(system|ceo)(/|$)|/?$)', '폴백 정규식을 원문 그대로 뽑아야 한다');

  const fallback = new RegExp(source);
  assert.ok(fallback.test('/admin/system/menus'), '허용 접두사는 통과해야 한다');
  assert.ok(fallback.test('/admin'), '/admin 단독도 SPA 다');
  assert.ok(!fallback.test('/admin/shipping'), '목록 밖 경로를 통과시키면 게이트가 무의미하다');

  // 경계 `(/|$)` 가 load-bearing 이다: 이것이 빠지면 /admin/settlements 같은 API 까지 삼킨다.
  assert.ok(!fallback.test('/admin/systems'), '접두사가 아니라 세그먼트 단위로 잘라야 한다');
  assert.ok(new RegExp('^/admin(/(system|ceo)|/?$)').test('/admin/systems'),
    '경계를 뺀 정규식은 실제로 오탐한다 — ②가 감시하는 실패 양상이다');

  // 순서 검사가 두 location 을 구별하는지.
  assert.ok(sample.search(/location\s+~\s+\^\/admin/) < proxyLocationIndex(sample),
    '정상 순서를 위반으로 읽으면 안 된다');
  const reversed = `
    location ~ ^/(auth|api|admin|users)(/|$) { proxy_pass http://g:8080; }
    location ~ ^/admin(/(system)(/|$)|/?$) { try_files $uri /index.html; }
  `;
  assert.ok(reversed.search(/location\s+~\s+\^\/admin/) > proxyLocationIndex(reversed),
    '뒤바뀐 순서를 잡아내지 못하면 순서 검사가 무의미하다');

  // admin 이 세그먼트 목록 맨 앞에 와도 찾아야 한다(`|admin|` 로만 보면 여기서 -1 이 된다).
  assert.ok(proxyLocationIndex('location ~ ^/(admin|api)(/|$) { proxy_pass http://g:8080; }') >= 0,
    'admin 이 첫 세그먼트인 설정에서 프록시 location 을 놓치면 순서 검사가 조용히 눈을 감는다');
});
