/**
 * 배선 게이트 — "컨트롤러는 있는데 <b>아무도 도달할 수 없는</b>" 상태를 빌드 시점에 드러낸다.
 *
 * <p>왜 필요한가: 새 엔드포인트는 컴파일만으로 살아나지 않는다. 브라우저에서 서비스까지 가는 길은
 * <b>nginx → gateway → service</b> 세 구간이고, 앞의 두 구간은 각각 <b>경로 allowlist</b>다.
 * 컨트롤러를 추가하면서 게이트웨이 라우트를 빠뜨리면, 서비스는 401(존재)로 답하는데 게이트웨이는
 * 404(경로 없음)로 답한다 — 컴파일러도 다른 게이트도 잡지 못하고, 화면에서만 드러난다.
 * 실제로 {@code /admin/shipping-policies} 가 이렇게 새어 나갔다(2026-08-20, 화면 실기동에서 발견).
 *
 * <p>{@code api-screen-gate} 와 같은 질문의 다른 면이다: 저쪽은 "화면이 부르는가", 이쪽은 "부르면
 * 닿는가". 둘 다 통과해야 기능이 실제로 쓰인다. 컨트롤러 추출기는 {@code lib/java-controllers.mjs}
 * 하나를 공유한다 — 파서가 두 벌이면 하네스 안에서 드리프트가 난다.
 *
 * <p>검사 셋:
 *   ① 컨트롤러 엔드포인트는 게이트웨이가 라우팅하거나, 사유와 함께 등록돼 있다.
 *   ② 내부 전용 표면({@code /internal}·{@code /van}·{@code /actuator})은 게이트웨이로 <b>새지 않는다</b>.
 *      — 이건 누락이 아니라 <b>노출</b>이 사고인 방향이라 반대로 검사한다.
 *   ③ 게이트웨이가 라우팅하는 최상위 세그먼트는 nginx 두 벌(compose·프로덕션) 모두에서 프록시된다.
 *      — 한쪽에만 있으면 "로컬에선 되는데 배포하면 안 되는"(또는 그 반대) 경로가 생긴다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { controllers as extractControllers } from '../lib/java-controllers.mjs';
import { gatewayPatterns as readGatewayPatterns, matchesPattern } from '../lib/gateway-routes.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (path) => readFileSync(path, 'utf8');

const GATEWAY_YML = join(REPO_ROOT, 'gateway-service', 'src', 'main', 'resources', 'application.yml');
const NGINX_CONFS = ['frontend/nginx.compose.conf', 'frontend/nginx.conf'];

/**
 * 게이트웨이를 <b>거치지 않는 것이 설계</b>인 표면의 접두사.
 *
 * <p>누락이 아니라 결정이다. {@code /internal} 은 서비스 간 호출(공유 시크릿 헤더),
 * {@code /van} 은 카드 VAN 단말 규격, {@code /actuator} 는 관측 표면이다.
 * 이들이 게이트웨이에 실리면 외부에서 닿게 되므로 ②가 반대 방향으로 감시한다.
 */
const NEVER_ROUTED_PREFIXES = ['/internal', '/van', '/actuator'];

/**
 * 게이트웨이가 라우팅하지 <b>않는다</b>고 선언한 컨트롤러와 사유 — 영구 결정.
 *
 * <p>위성 서비스의 {@code /admin/**} 수집·관리 트리거들이다. 서비스 자체가
 * {@code AdminApiKeyFilter}(X-Internal-Api-Key)로 게이팅하며, 게이트웨이 {@code application.yml} 에도
 * "외부 미노출"이라고 적혀 있다. {@code api-screen-gate} 의 MACHINE_ONLY 와 같은 판단이다
 * (그쪽은 "화면이 없다", 여기는 "길이 없다" — 같은 결정의 두 표현).
 */
const NOT_ROUTED_BY_DESIGN = new Map([
  // 이 저장소에는 게이트웨이에 올리지 않기로 선언한 컨트롤러가 아직 없다.
  // 새로 생기면 여기에 사유와 함께 등록한다 — 등록 자체가 "밖에서 닿지 않는다"는 선언이다.
]);

/**
 * 라우팅돼야 하는데 아직 안 된 컨트롤러 = <b>인정된 부채</b>. 줄어들기만 해야 한다.
 *
 * <p>{@code api-screen-gate} 의 SCREEN_PENDING 과 짝이다 — 화면도 없고 길도 없다. 화면을 붙이려면
 * 여기부터 지워야 한다(라우트 없이 화면만 만들면 404 를 부르는 화면이 된다).
 */
const UNROUTED_DEBT = new Map([
  // 2026-08-22: 4건을 모두 배선했다(/admin/refunds/** · /api/banking/**).
  //   노출 전에 인가부터 확인했다 — 게이트웨이 라우팅은 "밖에서 닿게 만드는" 결정이라,
  //   서버가 막지 않는 경로를 열면 그게 곧 무인증 공개다.
  //   · /admin/refunds/**  → SecurityConfig 가 ADMIN·MANAGER 로 게이트
  //   · /api/banking/**    → authenticated. 단 운용수익 인식·수급 지급 두 POST 만 ADMIN·MANAGER
  //                          (기관이 돈을 인식·지급하는 행위라 가입자에게 열면 임의 증액이 된다)
  //   account 슬라이스는 finance-service 안에 있고 SecurityConfig 는 그대로 로드된다
  //   그대로 로드된다 — 이걸 확인하지 않고 열었다면 뱅킹 3종이 무인증으로 공개될 뻔했다.
]);

/** 미배선 부채의 상한. <b>내려가기만 한다</b> — 라우트를 붙였으면 이 수를 함께 내린다. */
// 2026-08-21: 4 (게이트 신설 시점의 실측값)
// 2026-08-22: 0 (환불 콘솔·계정계 뱅킹 3종 배선 — 인가 확인 후 노출)
const UNROUTED_BUDGET = 0;

const sorted = (values) => [...new Set(values)].sort();

/**
 * 게이트웨이 경로표 파서와 {@code PathPattern} 매처는 {@code lib/gateway-routes.mjs} 에 산다 —
 * {@code spa-fallback-gate} 가 같은 표를 반대 방향(폴백이 이 라우트를 가리는가)에서 읽기 때문이다.
 */
const gatewayPatterns = () => readGatewayPatterns(REPO_ROOT);

/** 이 게이트가 판정 대상으로 삼는 컨트롤러(내부 전용 접두사만 가진 것은 ②의 몫이라 제외). */
function routableControllers() {
  return extractControllers(REPO_ROOT)
    .map((c) => ({
      key: c.key,
      endpoints: c.endpoints.filter(
        (p) => !NEVER_ROUTED_PREFIXES.some((prefix) => p === prefix || p.startsWith(prefix + '/'))),
    }))
    .filter((c) => c.endpoints.length > 0);
}

function unroutedControllers() {
  const patterns = gatewayPatterns();
  return routableControllers()
    .map((c) => ({
      key: c.key,
      missing: c.endpoints.filter((e) => !patterns.some((p) => matchesPattern(p, e))),
    }))
    .filter((c) => c.missing.length > 0);
}

/** nginx 가 게이트웨이로 프록시하는 최상위 세그먼트 allowlist. */
function nginxProxiedSegments(conf) {
  const text = read(join(REPO_ROOT, conf));
  const match = text.match(/location\s+~\s+\^\/\(([^)]+)\)\(\/\|\$\)/);
  assert.ok(match, `${conf} 에서 프록시 allowlist location 을 찾지 못했습니다 — 패턴이 바뀌었다면 이 게이트도 함께 고쳐야 합니다.`);
  return new Set(match[1].split('|'));
}

test('컨트롤러 엔드포인트는 게이트웨이가 라우팅하거나 사유와 함께 등록돼 있다', () => {
  const unclassified = sorted(unroutedControllers()
    .map((c) => c.key)
    .filter((key) => !NOT_ROUTED_BY_DESIGN.has(key) && !UNROUTED_DEBT.has(key)));

  assert.deepEqual(unclassified, [],
    `게이트웨이가 라우팅하지 않는 컨트롤러가 분류되지 않았습니다:\n  ${unclassified.join('\n  ')}\n`
    + 'gateway-service/src/main/resources/application.yml 의 해당 서비스 라우트 Path 에 경로를 추가하거나,\n'
    + '외부에 열지 않을 표면이라면 scripts/harness/test/gateway-route-gate.test.mjs 의 '
    + 'NOT_ROUTED_BY_DESIGN(영구 결정) 또는 UNROUTED_DEBT(부채, UNROUTED_BUDGET 도 함께 상향)에 '
    + '사유와 함께 등록하세요.');
});

test('내부 전용 표면은 게이트웨이로 새지 않는다', () => {
  const leaked = sorted(gatewayPatterns()
    .filter((p) => NEVER_ROUTED_PREFIXES.some((prefix) => p === prefix || p.startsWith(prefix + '/'))));

  assert.deepEqual(leaked, [],
    `내부 전용 경로가 게이트웨이 라우트에 실려 외부에서 닿게 됩니다: ${leaked.join(', ')}\n`
    + '/internal 은 공유 시크릿 헤더로만 부르는 서비스 간 호출이고, /van 은 카드 단말 규격, '
    + '/actuator 는 관측 표면입니다.');
});

test('게이트웨이가 라우팅하는 최상위 세그먼트는 nginx 두 벌 모두에서 프록시된다', () => {
  const segments = sorted(gatewayPatterns().map((p) => p.split('/')[1]).filter(Boolean));

  const missing = [];
  for (const conf of NGINX_CONFS) {
    const proxied = nginxProxiedSegments(conf);
    for (const segment of segments) {
      if (!proxied.has(segment)) missing.push(`${conf}: /${segment}`);
    }
  }

  assert.deepEqual(missing, [],
    `게이트웨이는 라우팅하는데 nginx 가 프록시하지 않는 경로가 있습니다 — 브라우저에서 닿지 않습니다:\n  `
    + `${missing.join('\n  ')}\n`
    + '해당 conf 의 프록시 location 정규식에 세그먼트를 추가하세요. '
    + '두 벌이 어긋나면 "로컬에선 되는데 배포하면 안 되는" 경로가 생깁니다.');
});

test('분류 목록에 이미 사라진 컨트롤러가 남아 있지 않다', () => {
  const keys = new Set(routableControllers().map((c) => c.key));
  const stale = sorted([...NOT_ROUTED_BY_DESIGN.keys(), ...UNROUTED_DEBT.keys()]
    .filter((key) => !keys.has(key)));

  assert.deepEqual(stale, [],
    `이미 없는(또는 개명된) 컨트롤러가 분류 목록에 남아 있습니다: ${stale.join(', ')}`);
});

test('라우트가 생긴 컨트롤러는 부채 목록에서 내려간다', () => {
  const unrouted = new Set(unroutedControllers().map((c) => c.key));
  const done = sorted([...UNROUTED_DEBT.keys()].filter((key) => !unrouted.has(key)));

  assert.deepEqual(done, [],
    `게이트웨이 라우트가 생겼는데 부채 목록에 남아 있습니다: ${done.join(', ')}\n`
    + 'UNROUTED_DEBT 에서 지우고 UNROUTED_BUDGET 을 함께 내리세요.');
});

test('미배선 부채가 늘지 않았다 (예산은 내려가기만 한다)', () => {
  assert.ok(UNROUTED_DEBT.size <= UNROUTED_BUDGET,
    `미배선 부채가 예산을 넘었습니다: ${UNROUTED_DEBT.size} > ${UNROUTED_BUDGET}`);
});

test('추출기가 살아 있다 (스캔이 비면 판정 전체가 거짓이 된다)', () => {
  const all = routableControllers();
  assert.ok(all.length > 50, `컨트롤러 스캔이 비었습니다(${all.length}개) — 추출기가 죽으면 "미배선 0" 은 거짓입니다.`);
  assert.ok(gatewayPatterns().length > 20, '게이트웨이 Path 술어를 거의 못 읽었습니다 — yml 형식이 바뀌었는지 확인하세요.');
});

test('[자기검증] 매처가 PathPattern 의 요점을 지킨다', () => {
  // `/**` 는 0개 이상 세그먼트 — 컬렉션 루트가 여기에 기대고 있다.
  assert.ok(matchesPattern('/admin/a/**', '/admin/a'));
  assert.ok(matchesPattern('/admin/a/**', '/admin/a/b/c'));
  // 접두사가 같아도 세그먼트 경계가 다르면 매칭되지 않는다 — 이게 깨지면
  // /admin/shipping/** 가 /admin/shipping-policies 를 덮어 가짜 GREEN 이 된다.
  assert.ok(!matchesPattern('/admin/a/**', '/admin/ab'));
  assert.ok(!matchesPattern('/admin/a/**', '/admin'));
  // 정확 경로 술어는 하위를 열지 않는다.
  assert.ok(matchesPattern('/api/seller/bank-account', '/api/seller/bank-account'));
  assert.ok(!matchesPattern('/api/seller/bank-account', '/api/seller/bank-account/x'));
  // 경로변수는 한 세그먼트.
  assert.ok(matchesPattern('/orders/{id}', '/orders/*'));
  assert.ok(!matchesPattern('/orders/{id}', '/orders/*/items'));
});
