/**
 * 백엔드 표면 ↔ 화면 커버리지 게이트 — "기능은 짰는데 아무도 못 쓰는" 상태를 빌드 시점에 드러낸다.
 *
 * 왜 필요한가: `menu-route-gate` 는 메뉴↔라우트만 본다. 그래서 죽은 링크와 유령 화면은 막지만,
 * <b>백엔드에 컨트롤러가 새로 생겼는데 부르는 화면이 없는</b> 경우는 아무도 보지 않는다.
 * 실제로 card(Phase 2 완료)·insurance·deposit·organization 은 REST 와 게이트웨이 라우팅이
 * 다 있는데 화면이 0 인 채로 오래 있었다. 컴파일러도 기존 게이트도 잡지 못하는 종류의 누락이다.
 *
 * 판정 방법:
 *   백엔드 — `@RestController` 의 <b>엔드포인트 전체</b>(클래스 @RequestMapping + 메서드 매핑 조합).
 *            클래스 매핑 없이 메서드에 전체 경로를 다는 컨트롤러가 실제로 있어서
 *            (ApplicationDocumentController) "첫 경로 = base" 모델로는 나머지 경로를 놓친다.
 *   프론트 — frontend/src 전체(테스트 제외)의 URL 문자열 리터럴.
 *   귀속   — URL 을 <b>최장일치</b> 엔드포인트에만 크레딧한다. 접두사만 보면 형제 컨트롤러가
 *            남의 호출을 가로챈다 — `/admin/deposits/proofs`(DepositProofAdminController) 호출이
 *            `/admin/deposits`(DepositAdminController)까지 "덮였다"로 만들어 가짜 GREEN 이 된다.
 *
 * 그래서 목록의 키는 경로가 아니라 <b>`서비스/클래스명`</b>이다 — 두 컨트롤러가 같은 base 를
 * 공유할 수 있어 경로 키는 애초에 유일하지 않다.
 *
 * 새 컨트롤러를 추가하면 셋 중 하나를 해야 통과한다:
 *   ① 부르는 화면을 만든다  ② MACHINE_ONLY 에 등록한다  ③ SCREEN_PENDING 에 등록하고 예산을 올린다
 * ③ 은 부채를 지는 선택이라 PENDING_BUDGET 을 함께 고쳐야 해서 눈에 띈다.
 *
 * 한계: 폴리글랏 7종(Kotlin/Go/Python)은 스캔하지 않는다 — 자바 애노테이션 기준 추출이라서다.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { controllers as extractControllers, norm, walk } from '../lib/java-controllers.mjs';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * 이 게이트가 훑는 서비스. `java-controllers.mjs` 의 정본 로스터(18개)보다 <b>좁다</b> —
 * board·education 은 아직 화면 대조 대상으로 편입되지 않았다. 넓히면 미분류 컨트롤러가
 * 새로 드러나므로 그때 사유와 함께 분류해야 한다(별도 작업). 라우팅 게이트는 정본 18개를 쓴다.
 *
 * marketing-service 는 신설과 <b>동시에</b> 편입했다(ADR 0045). 대상 밖으로 두면 화면 없는
 * 컨트롤러가 부채로 잡히지도 않는다 — 그 상태가 이 게이트가 막으려던 바로 그 상태다.
 */
const SERVICES = [
  'order-service', 'operation-service', 'marketing-service',
];

/**
 * 화면을 만들지 <b>않는다</b>고 선언한 컨트롤러와 사유.
 * 브라우저가 부를 수 없거나(내부 키 게이트·외부 단말 규격), 사람이 상시로 볼 대상이 아닌 것들이다.
 * 일회성 집행 경로에 화면을 두면 운영자가 상시 기능으로 오해한다.
 */
const MACHINE_ONLY = new Map([
  ['operation-service/AlertmanagerWebhookController', 'Alertmanager 가 POST 하는 수신구 — 사람이 여는 화면이 아니다'],
  ['order-service/AdminStockReclaimController', '배치 트리거 — 미결제 재고 회수'],
  ['order-service/AdminPaymentExpiryController', '배치 트리거 — 결제 만료 처리'],
]);

/**
 * 화면이 필요하지만 아직 없는 컨트롤러 = <b>인정된 부채</b>. 줄어들기만 해야 한다.
 * 정산 4종의 추적 위치는 docs/PLAN.md §8-8 로스터다.
 */
const SCREEN_PENDING = new Map([
  // --- order-service ---
  // 카테고리 탐색 화면이 생겼다(2026-08-27, /browse). 사유가 "관리 화면만 있다"였던 것이 그대로
  // 설계가 됐다 — 관리 API(/admin/categories)는 비활성 분류까지 내려주므로, 구매자 화면이
  // 그것을 부르면 아직 열지 않은 분류가 노출된다. 그래서 화면을 옮겨 붙인 게 아니라 공개
  // API 를 부르는 화면을 따로 만들었다.
  // 환불 운영 콘솔이 생겼다(2026-08-22, /admin/settlement/refunds). 자동 재시도 5회가 끝나면
  // 스케줄러가 손대지 않는데, 그 대상을 볼 화면이 없었다 — 사람이 안 하면 영영 처리되지 않는 건이다.
  // RefundHistoryController 도 함께 내려간다: 화면이 행마다 결제별 환불 이력을 실제로 부른다
  // (여러 번 시도한 건의 이중 환불 여부를 실제 완료액으로 판단하는 자리).
  // PG 라우터 상태 카드가 생겼다(2026-08-22, 운영 관제 상단). 사유를 먼저 "설정 콘솔"에서
  // "읽기 전용 상태 점검"으로 정정한 것이 그대로 설계가 됐다 — 조작 버튼 없는 스냅샷 한 장이고,
  // order-service 표면이지만 읽는 맥락이 관제라 operation 화면에 얹었다.
  // 상품 옵션(SKU) 관리 화면이 생겼다(2026-08-27, /admin/system/product-variants).
  // 이 부채는 성격이 달랐다: 화면이 없어 기능을 못 쓰는 정도가 아니라, 서버가 이 경로를
  // 인가 목록에 올려 두지 않아 로그인한 누구나 남의 상품에 SKU 를 만들고 재고를 깎을 수
  // 있었다. 경로가 /admin 아래가 아니라(/products/{id}/variants) 관리자 경로를 훑는 눈에
  // 걸리지 않던 자리다 — 화면을 붙이려고 인가를 확인하다 드러났다.
  // 조직·멤버십 화면이 생겼다(2026-08-22, /admin/system/organizations). organization-service
  // 최초의 화면이다 — 그전까지 조직을 만들고 사람을 붙이는 경로가 API 뿐이었다.
]);

/**
 * 미노출 부채의 상한. <b>내려가기만 한다</b> — 화면을 붙였으면 이 수를 함께 내린다.
 * 올리려면 그 자체가 리뷰 대상이라는 뜻이다.
 */
// 2026-08-20: 42 (ReportController → 매출 통계 콘솔 /admin/settlement/sales-stats 이 cashflow 를 부른다)
// 2026-08-21: 35 (게이트웨이가 라우팅하지 않는 7종을 MACHINE_ONLY 로 정정 — 화면을 만든 게 아니라
//              애초에 브라우저가 부를 수 없던 것을 부채로 세고 있었다. 이 7종에 화면을 붙이려면
//              먼저 게이트웨이 노출 정책을 바꿔야 하고, 그건 화면 작업이 아니라 배선 결정이다.)
// 2026-08-21: 31 (화면 4종을 실제로 붙였다 — 예치금 잔고(내 잔액), 송장 일괄 업로드(배송 관리),
//              홀드백 해제 미리보기(지급 콘솔), 셀러 등급 콘솔(신규 화면). 앞의 셋은 기존 화면에
//              구획을 얹었고 넷째만 새 라우트라, 부채 상환 비용이 화면 수와 비례하지 않는다는
//              방증이기도 하다.)
// 2026-08-21: 28 (셀러 지급 계좌 2종 — 셀프 등록은 '내 잔액', 운영자 대행은 지급 콘솔의
//              구획으로 붙였다. 이 부채는 성격이 달랐다: 화면이 없어 기능을 못 쓰는 정도가
//              아니라, 계좌가 없으면 payout 이 생성조차 되지 않아 실패 목록에도 안 뜨는
//              무증상 정지였다. 반송 처리 안내는 "계좌 정정이 선행"이라고 적혀 있는데
//              정작 정정할 화면이 없었다.)
// 2026-08-21: 27 (예치금 수기 콘솔 — hold·offset 은 card 이벤트에 sellerId 가 없어 자동화가
//              막혀 있어 수기 경로가 유일하고, 상계 부족분은 해소 주체가 아예 없어 쌓이기만 했다.)
// 2026-08-21: 26 (보험 상품설명서 교부 — 완전판매 게이트의 입력 경로가 API 뿐이라
//              청약 승인을 UI 로 통과시킬 방법이 없었다.)
// 2026-08-21: 24 (담보 감시 화면 — 재평가·처분·대위변제. SecuredLoanController 는 상세 조회만
//              불리는데 컨트롤러 단위 판정이라 함께 내려간다. 신청·심사 화면 부재는 위 주석 참조.)
// 2026-08-22: 23 (조직·멤버십 — organization-service 최초의 화면.)
// 2026-08-22: 21 (환불 운영 콘솔 — 목록과 결제별 이력을 둘 다 부른다. 게이트웨이 배선이
//              선행이었고 같은 날 열렸다: 그전에는 화면을 만들어도 404 를 부르는 화면이었다.)
// 2026-08-22: 20 (CompanyWorkforceController — 화면을 붙인 게 아니라 거짓 부채를 걷어냈다.
//              추출기가 쿼리스트링 붙은 URL 을 못 읽어 9개를 못 보고 있었고, 그중 1건이
//              부채로 잡혀 있었다. 예산이 내려간다고 늘 상환은 아니다.)
// 2026-08-22: 17 (수신 3종 — 정기예금·적금·퇴직연금. 화면 하나에 탭 셋으로 3건을 갚았다.
//              세 상품이 같은 모양(가입 → 납입 → 만기/중도해지)이라 가능했다.)
// 2026-08-22: 14 (보험 3종 — 설계·청약·계약. 그중 PolicyController 1건은 화면이 부르는데도
//              추출기가 보간 괄호를 못 읽어 안 잡히던 것이라, 추출기 보정과 같이 내려간다.)
// 2026-08-22: 12 (상환표 시뮬레이터 — 기존 대출 화면에 탭 하나. 배선 0.)
// 2026-08-22: 11 (PG 라우터 상태 카드 — 운영 관제에 구획 하나. 배선 0.)
// 2026-08-22: 9 (정산 재실행 + 셀러 평판 — 둘 다 기존 화면에 구획 하나씩. 배선 0.
//              사유를 먼저 정정·보강한 것이 배치와 형태를 정한 세 번째·네 번째 사례다.)
// shop 분리(order + operation 범위) 시점 실측: 2
// 2026-08-27: 0 (남은 둘을 다 갚았다 — 카테고리 탐색(/browse)과 상품 옵션 관리
//              (/admin/system/product-variants). 예산이 0 이라는 것은 "이 범위에서는 새
//              컨트롤러에 화면이 반드시 따라온다"는 뜻이고, 미루려면 이 숫자를 올려야 해서
//              그 판단이 diff 에 남는다.)
const PENDING_BUDGET = 0;

const read = (path) => readFileSync(path, 'utf8');

/** 프론트가 실제로 부르는 URL — api/ 모듈뿐 아니라 페이지가 api.get() 을 직접 부르는 곳도 있다. */
function frontendUrls() {
  const urls = new Set();
  for (const file of walk(join(REPO_ROOT, 'frontend', 'src'))) {
    if (!/\.(ts|tsx)$/.test(file) || file.includes('__tests__')) continue;
    // 템플릿 보간을 <b>먼저</b> 균일한 토큰으로 접는다. 경로 문자류에 괄호가 없어서
    // `${encodeURIComponent(id)}` 같은 보간이 들어가면 매치가 통째로 실패하기 때문이다
    // (encodeURIComponent 는 이 저장소의 URL 관례다 — coupon·display-section·option-catalog 등).
    const source = read(file).replace(/\$\{[^}]*\}/g, '${x}');

    // 닫는 따옴표 앞의 쿼리스트링을 허용하고 <b>경로만</b> 캡처한다.
    // 이 `(?:\?...)?` 가 없으면 `/api/company/workforce?${params}` 같은 호출이 통째로 안 잡힌다.
    //
    // 두 보정 모두 같은 실패를 막는다: <b>화면이 멀쩡히 부르는데 "화면 없음"으로 집계되는 것</b>.
    // 스캔이 0이 되는 실패와 달리 조용히 부채를 부풀리는 방향이라 오래 산다.
    // 2026-08-22 실측 — 쿼리스트링: URL 9개 가려짐(CompanyWorkforceController 1건 거짓 부채),
    //                   보간 괄호: URL 19개 가려짐(PolicyController 1건 거짓 부채).
    for (const m of source.matchAll(/['"`](\/[a-zA-Z0-9/{}$_.*-]+)(?:\?[^'"`]*)?['"`]/g)) {
      urls.add(norm(m[1]));
    }
  }
  return urls;
}

/**
 * 이 게이트가 보는 컨트롤러 표면 — 추출은 공유 모듈이 하고, 여기서는 <b>화면 대조에 무의미한
 * 경로만</b> 덜어낸다. {@code /internal} 은 서비스 간 호출이고 {@code /actuator} 는 관측 표면이라
 * 브라우저가 부를 일이 없다(그 둘이 게이트웨이로 새지 않았는지는 gateway-route-gate 가 본다).
 */
function controllers() {
  return extractControllers(REPO_ROOT, SERVICES)
    .map((c) => ({
      key: c.key,
      endpoints: c.endpoints.filter((p) => !p.startsWith('/internal') && !p.startsWith('/actuator')),
    }))
    .filter((c) => c.endpoints.length > 0);
}

/** 각 URL 을 최장일치 엔드포인트 하나에만 크레딧한다. */
function calledControllers() {
  const all = controllers();
  const flat = all.flatMap((c) => c.endpoints.map((p) => ({ p, key: c.key })));
  const credited = new Set();
  for (const url of frontendUrls()) {
    let best = null;
    for (const cand of flat) {
      if ((url === cand.p || url.startsWith(cand.p + '/')) && (!best || cand.p.length > best.p.length)) best = cand;
    }
    if (best) credited.add(best.key);
  }
  return credited;
}

const sorted = (values) => [...new Set(values)].sort();

test('화면이 부르지 않는 컨트롤러는 전부 사유와 함께 분류돼 있다', () => {
  const called = calledControllers();
  const unclassified = sorted(controllers()
    .map((c) => c.key)
    .filter((key) => !called.has(key) && !MACHINE_ONLY.has(key) && !SCREEN_PENDING.has(key)));

  assert.deepEqual(unclassified, [],
    `프론트가 부르지 않는 컨트롤러가 분류되지 않았습니다:\n  ${unclassified.join('\n  ')}\n`
    + '부르는 화면을 만들거나, scripts/harness/test/api-screen-gate.test.mjs 의 '
    + 'MACHINE_ONLY(기계 전용) 또는 SCREEN_PENDING(화면 부채, PENDING_BUDGET 도 함께 상향)에 '
    + '사유와 함께 등록하세요.');
});

test('분류 목록에 이미 사라진 컨트롤러가 남아 있지 않다', () => {
  const keys = new Set(controllers().map((c) => c.key));
  const stale = sorted([...MACHINE_ONLY.keys(), ...SCREEN_PENDING.keys()].filter((key) => !keys.has(key)));

  assert.deepEqual(stale, [],
    `이미 없는(또는 개명된) 컨트롤러가 분류 목록에 남아 있습니다: ${stale.join(', ')}`);
});

test('화면이 생긴 컨트롤러는 부채 목록에서 내려간다', () => {
  const called = calledControllers();
  const done = sorted([...SCREEN_PENDING.keys()].filter((key) => called.has(key)));

  assert.deepEqual(done, [],
    `화면이 생겼는데 SCREEN_PENDING 에 남아 있습니다: ${done.join(', ')}\n`
    + `목록에서 지우고 PENDING_BUDGET 을 ${PENDING_BUDGET - done.length} 로 내리세요.`);
});

test('기계 전용으로 선언한 컨트롤러를 화면이 부르지 않는다', () => {
  const called = calledControllers();
  const wrong = sorted([...MACHINE_ONLY.keys()].filter((key) => called.has(key)));

  assert.deepEqual(wrong, [],
    `기계 전용으로 분류된 컨트롤러를 프론트가 부르고 있습니다: ${wrong.join(', ')}\n`
    + '분류가 틀렸거나(→ 목록에서 제거), 브라우저가 부르면 안 되는 것을 부르고 있습니다(→ 화면 수정).');
});

test('미노출 부채가 늘지 않았다 (예산은 내려가기만 한다)', () => {
  assert.ok(SCREEN_PENDING.size <= PENDING_BUDGET,
    `화면 부채가 예산을 넘었습니다: ${SCREEN_PENDING.size} > ${PENDING_BUDGET}. `
    + '새 백엔드 기능에는 화면을 함께 붙이는 것이 기본이며, 미루려면 PENDING_BUDGET 상향이 리뷰 대상입니다.');
  assert.equal(SCREEN_PENDING.size, PENDING_BUDGET,
    `부채가 줄었는데 예산이 그대로입니다: ${SCREEN_PENDING.size} < ${PENDING_BUDGET}. `
    + `PENDING_BUDGET 을 ${SCREEN_PENDING.size} 로 내려 래칫을 조이세요.`);
});

test('추출기가 살아 있다 (스캔이 비면 판정 전체가 거짓이 된다)', () => {
  // 추출 정규식이 깨져 0개가 되면 위 테스트들은 조용히 전부 통과한다.
  assert.ok(controllers().length >= 50, '컨트롤러 스캔 결과가 비정상적으로 적습니다.');
  assert.ok(frontendUrls().size >= 80, '프론트 URL 스캔 결과가 비정상적으로 적습니다.');
});

test('[자기검증] 쿼리스트링이 붙은 호출도 경로로 읽는다', () => {
  // 이 형태를 못 읽으면 화면이 멀쩡히 부르는 컨트롤러가 "화면 없음"으로 집계된다 —
  // 스캔이 0이 되는 실패와 달리 <b>조용히 부채를 부풀리는</b> 실패라 더 오래 산다.
  // 실제로 CompanyWorkforceController 가 그렇게 잡혀 있었다(2026-08-22).
  const urls = new Set();
  const sample = [
    'api.get<T>(`/api/company/workforce?${params}`)',
    "api.get('/api/company/workforce/detail?name=x&page=1')",
    'api.get(`/admin/refunds`)',
    'api.delete(`/api/organizations/${id}/members/${userId}`)',
    // 보간 안에 함수 호출이 들어가는 형태 — 이 저장소의 URL 관례다.
    'api.post(`/api/insurance/policies/${encodeURIComponent(policyNumber)}/surrender`)',
  ].join('\n').replace(/\$\{[^}]*\}/g, '${x}');
  for (const m of sample.matchAll(/['"`](\/[a-zA-Z0-9/{}$_.*-]+)(?:\?[^'"`]*)?['"`]/g)) {
    urls.add(norm(m[1]));
  }

  assert.ok(urls.has('/api/company/workforce'), '템플릿 리터럴 + 쿼리스트링을 읽어야 한다');
  assert.ok(urls.has('/api/company/workforce/detail'), '작은따옴표 + 쿼리스트링도 읽어야 한다');
  assert.ok(urls.has('/admin/refunds'), '쿼리 없는 경로는 그대로 읽어야 한다');
  assert.ok(urls.has('/api/organizations/*/members/*'), '경로변수는 와일드카드로 접어야 한다');
  assert.ok(urls.has('/api/insurance/policies/*/surrender'),
    '보간 안에 함수 호출이 있어도 읽어야 한다 — 괄호 때문에 매치가 통째로 실패하던 자리다');
  // 쿼리스트링 자체가 경로로 새어 들어오면 안 된다 — 그러면 어떤 엔드포인트에도 안 붙는다.
  assert.ok([...urls].every((u) => !u.includes('?')), '캡처는 경로까지다');
});
