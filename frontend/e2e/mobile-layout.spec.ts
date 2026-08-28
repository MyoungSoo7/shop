import { type Page } from '@playwright/test';
import { test, expect } from './fixtures';

/**
 * 모바일 레이아웃 회귀 가드 — "화면이 가로로 넘치지 않는다"만 기계로 지킨다.
 *
 * 왜 이 검사인가: 반응형이 깨지는 방식은 대부분 하나다. 어떤 요소가 뷰포트보다 넓어져
 * **문서 전체가 가로 스크롤**되고, 그 순간 헤더·버튼이 화면 밖으로 밀린다. 실제로 이 앱에서
 * 한 번 발생했다(5a41caf3d — 390px 에서 문서 폭 634px). 사람 눈으로는 5개 엔진 × 40여 화면을
 * 매번 볼 수 없으므로, 판정 가능한 이 한 축만 전 화면에 건다.
 *
 * 판정식은 `documentElement.scrollWidth <= clientWidth` 다. `overflow-x-auto` 로 감싼 표처럼
 * **의도적으로 넓은 콘텐츠는 컨테이너 안에서 잘리므로 문서 폭을 늘리지 않는다** — 정상 패턴을
 * 오탐하지 않고 진짜 넘침만 잡는다는 뜻이다. 실패 시에는 원인 요소를 함께 출력한다.
 *
 * 데스크톱 프로젝트에서는 의미가 없어 건너뛴다(모바일 뷰포트 전용).
 */

/** 서브픽셀 반올림(스케일된 뷰포트에서 0.5px 오차)이 매 실행 실패로 번지지 않게 하는 여유값. */
const SUBPIXEL_SLACK = 1;

/**
 * 문서가 가로로 넘치는지 재고, 넘치면 원인 요소를 사람이 읽을 수 있게 뽑아 온다.
 *
 * 원인 탐색은 **진단용 best-effort** 다: 스크롤 컨테이너(overflow-x auto/scroll/hidden) 안의
 * 요소는 레이아웃 사각형이 컨테이너 밖으로 뻗어도 실제로는 잘려 있으므로 후보에서 뺀다.
 * 판정 자체는 어디까지나 문서 폭 비교이고, 이 목록은 실패 메시지를 쓸모 있게 만들 뿐이다.
 */
async function measureOverflow(page: Page) {
  return page.evaluate((slack) => {
    const doc = document.documentElement;
    const documentWidth = doc.scrollWidth;
    const viewportWidth = doc.clientWidth;

    /**
     * 스타일시트가 아직/영영 안 붙은 상태(FOUC)인지. 이때는 Tailwind 클래스가 전부 무력해져
     * 표·내비가 콘텐츠 폭 그대로 펼쳐지므로 **레이아웃 결함이 아닌데도** 문서가 크게 넘친다.
     * 둘을 구분하지 못하면 "반응형이 깨졌다"며 엉뚱한 곳을 고치게 된다. index.css 가 body 를
     * flex 로 만드는 것을 표식으로 삼는다(앱 CSS 가 실제로 적용됐는가).
     */
    const cssApplied = getComputedStyle(document.body).display === 'flex';

    if (documentWidth <= viewportWidth + slack) {
      return { documentWidth, viewportWidth, cssApplied, culprits: [] as string[] };
    }

    const limit = viewportWidth + slack;
    const clipped = (el: Element) => {
      for (let node = el.parentElement; node; node = node.parentElement) {
        const overflowX = getComputedStyle(node).overflowX;
        if (overflowX !== 'visible') return true;
      }
      return false;
    };

    const describe = (el: Element) => {
      const cls = typeof el.className === 'string' ? el.className.trim().slice(0, 80) : '';
      const right = Math.round(el.getBoundingClientRect().right);
      return `<${el.tagName.toLowerCase()}${cls ? ` class="${cls}"` : ''}> right=${right}px`;
    };

    const culprits = Array.from(document.querySelectorAll('body *'))
      .filter((el) => {
        const rect = el.getBoundingClientRect();
        return rect.width > 0 && rect.right > limit && !clipped(el);
      })
      .slice(0, 5)
      .map(describe);

    return { documentWidth, viewportWidth, cssApplied, culprits };
  }, SUBPIXEL_SLACK);
}

/** 폭 안정화 대기 상한 — 데이터 로드 후 표·카드가 뒤늦게 붙는 화면을 위한 여유. */
const SETTLE_TIMEOUT_MS = 8_000;

/**
 * 라우트에 **실제로 도달했는지** 먼저 확인한다.
 *
 * 이 가드가 조용히 무력해지는 방식은 하나다. 인증이 끊기거나 권한이 없어 `/login` 으로 튕기면
 * 로그인 화면은 당연히 안 넘치므로 **모든 라우트가 통과로 찍힌다**. 측정값이 아니라 측정 대상이
 * 사라진 것인데 결과는 초록이다. 그래서 넘침을 재기 전에 도달을 어서트한다.
 */
async function expectRouteReached(page: Page, route: string) {
  const landed = new URL(page.url()).pathname;
  expect
    .soft(landed, `${route} 도달 실패 — ${landed} 로 이동됨(인증 만료/권한 부족이면 가드가 통째로 무의미해진다)`)
    .not.toBe('/login');
}

async function expectNoHorizontalOverflow(page: Page, route: string) {
  // 지연 렌더까지 포함해 "최종 폭"을 재야 하므로 한 번 재고 끝내지 않는다. expect.poll 을 쓰지
  // 않는 이유는, poll 이 실패하면 마지막 측정값을 잃어 **원인 요소를 출력할 수 없기** 때문이다.
  const deadline = Date.now() + SETTLE_TIMEOUT_MS;
  let measured = await measureOverflow(page);
  while (measured.documentWidth > measured.viewportWidth + SUBPIXEL_SLACK && Date.now() < deadline) {
    await page.waitForTimeout(250);
    measured = await measureOverflow(page);
  }

  const { documentWidth, viewportWidth, cssApplied, culprits } = measured;
  // soft 인 이유: 한 라우트가 넘쳐도 나머지 라우트를 계속 측정해야 "어디가 몇 개 깨졌는지"가
  // 한 번에 나온다. 하나라도 soft 실패가 남으면 테스트는 최종적으로 실패한다.
  expect
    .soft(
      documentWidth,
      `${route} 가로 넘침 — 문서 ${documentWidth}px > 뷰포트 ${viewportWidth}px\n` +
        `앱 CSS 적용됨: ${cssApplied}${cssApplied ? '' : ' ← 스타일시트 미적용(FOUC). 레이아웃이 아니라 자산 로딩을 의심할 것'}\n` +
        `원인 후보:\n${
          culprits.map((c) => `  · ${c}`).join('\n') ||
          '  (스크롤 컨테이너 밖 요소 없음 — 루트 요소의 폭/패딩 확인)'
        }`,
    )
    .toBeLessThanOrEqual(viewportWidth + SUBPIXEL_SLACK);
}

/**
 * 라우트 한 개의 내비게이션 예산.
 *
 * 전역 설정은 `timeout: 30_000`(테스트 전체)과 `navigationTimeout: 30_000`(내비게이션 한 번)이
 * **같은 값**이다. 그래서 라우트 하나가 느리면 그 `goto` 한 번이 테스트 예산을 통째로 먹고,
 * 나머지 라우트는 재보지도 못한 채 `Test timeout of 30000ms exceeded` 로 끝난다 — 실패 메시지에
 * "어느 화면이 넘쳤는지"는커녕 "어느 화면이 느렸는지"도 안 남는다. 실제로 2026-08-28 main
 * (`bdfb3a4`)의 smoke 가 이 모양으로 빨간불이었다.
 *
 * 문서 자체는 느리지 않다(같은 날 `/admin/system/menus` TTFB 5회 실측 0.37~0.61초). 느려지는
 * 것은 `goto` 의 기본 대기 조건인 `load` 로, 라우트마다 번들·폰트를 다시 기다리는 데다 CI 가
 * 여러 모바일 프로젝트로 같은 운영 사이트를 동시에 두드린다. 즉 **가끔 느린 건 정상**이고,
 * 예산 배분이 틀렸던 것이다. 그래서 내비게이션 예산을 테스트 예산보다 작게 따로 잡는다.
 */
const ROUTE_NAV_TIMEOUT_MS = 12_000;

/**
 * 로그인 왕복 예산. 운영 백엔드로 auto-login POST 가 나가는데 전역 `expect.timeout` 7초로는
 * 모자랐다(같은 실행에서 클릭 후 7초 동안 `/login` 에 머물러 실패).
 */
const LOGIN_TIMEOUT_MS = 20_000;

/** 라우트당 총 예산 — 내비게이션 + 도달 확인 + 폭 안정화 대기에 여유를 얹은 값. */
const ROUTE_BUDGET_MS = 15_000;

/**
 * 라우트 수에 비례해 테스트 예산을 잡는다.
 *
 * 목록에 라우트를 더하면 예산도 같이 늘어난다는 뜻이다. 상수로 박아 두면 목록이 자라는 순간
 * 조용히 모자라지고, 그때 나오는 건 "이 화면이 깨졌다" 가 아니라 **타임아웃**이라 원인을
 * 엉뚱한 데서 찾게 된다. ADMIN 목록이 7개로 늘면서 실제로 그렇게 됐다.
 */
function budgetFor(routeCount: number) {
  return LOGIN_TIMEOUT_MS + routeCount * ROUTE_BUDGET_MS;
}

/**
 * 라우트 목록을 훑으며 도달과 넘침을 확인한다.
 *
 * `goto` 에 `waitUntil` 을 바꾸지 않은 것은 의도다. `domcontentloaded` 로 낮추면 빨라지지만
 * 스타일시트가 붙기 전에 재게 되어 FOUC 상태의 폭을 "정상" 으로 읽고 지나갈 수 있다 —
 * 거짓 실패가 아니라 **거짓 통과**라 더 나쁘다.
 *
 * @param authenticated 로그인 뒤 도는 동선인지. 비로그인 목록에는 `/login` 자체가 들어 있어
 *   `expectRouteReached`(= `/login` 으로 튕기지 않았는가)를 걸면 **항상 실패**한다.
 */
async function sweepRoutes(page: Page, routes: readonly string[], authenticated: boolean) {
  for (const route of routes) {
    await page.goto(route, { timeout: ROUTE_NAV_TIMEOUT_MS });
    if (authenticated) await expectRouteReached(page, route);
    await expectNoHorizontalOverflow(page, route);
  }
}

async function loginAs(page: Page, button: string, expectedPath: string) {
  await page.addInitScript(() => {
    try {
      window.localStorage.clear();
    } catch {
      // origin 이 아직 없으면 SecurityError — 첫 내비게이션이 origin 을 만든다.
    }
  });
  await page.goto('/login', { timeout: ROUTE_NAV_TIMEOUT_MS });
  await page.getByRole('button', { name: button }).click();
  await expect(page).toHaveURL(new RegExp(`${expectedPath}(/|$|\\?)`), { timeout: LOGIN_TIMEOUT_MS });
}

/** 로그인 없이 볼 수 있는 화면. */
const PUBLIC_ROUTES = ['/login'];

/**
 * 아래 두 목록에는 **`App.tsx` 에 실제로 선언된 경로만** 넣는다.
 *
 * 없는 경로를 넣으면 조용히 무의미해진다. 라우터가 못 찾은 경로는 SPA 폴백 화면을 그리는데,
 * `expectRouteReached` 는 `/login` 으로 튕겼는지만 보고 폴백 화면은 가로로 넘치지 않는다 —
 * 그래서 **통과하지만 아무것도 재지 않는다**. 목록이 길수록 커버리지가 넓어 보이므로 더 나쁘다.
 *
 * 2026-08-25 에 이 상태의 경로 8개를 뺐다(`/loans` `/ai/chat` `/admin/settlement`
 * `/settlement/search` `/admin/payouts` `/workforce` `/admin/ceo/{insight,invest,companies}`).
 * 전부 여신·정산·인사·CEO 로, 이 저장소의 경계 밖이라 화면이 여기 없다. 남아 있던 이유는
 * 기본 `PLAYWRIGHT_BASE_URL` 이 외부 운영 호스트라 거기서는 그려졌기 때문이다.
 */

/** USER 동선 — 주문·장바구니·마이페이지가 모바일 사용 비중이 가장 높다. */
const USER_ROUTES = ['/order', '/cart', '/mypage', '/recommend'];

/**
 * ADMIN 동선 — 표가 많아 넘침이 가장 잘 나는 화면들.
 * 좌측 사이드바 셸(SideNavLayout)이 붙는 화면을 반드시 포함한다. 셸이 폭을 잡아먹어 넘침이
 * 가장 잘 나는 조합이기 때문이다. 지금은 `/product`·`/admin/shipping`·`/admin/system/*` 가
 * 그에 해당한다(사이드바 항목 자체는 menus 테이블이 정한다).
 */
const ADMIN_ROUTES = [
  '/admin',
  '/product',
  '/admin/shipping',
  '/admin/approvals',
  // 시스템 그룹 — SideNavLayout
  '/admin/system/menus',
  '/admin/system/codes',
  '/admin/system/operation',
];

test.describe('모바일 레이아웃 — 가로 넘침 회귀 가드', () => {
  test.skip(({ isMobile }) => !isMobile, '모바일 뷰포트 프로젝트 전용');

  // 종료 시 페이지 정리(WebKit 워커 행 회피)는 fixtures.ts 의 `releaseAppPages` 가 전 스펙 공통으로 한다.

  test('비로그인 화면이 뷰포트를 넘지 않는다', async ({ page }) => {
    test.setTimeout(budgetFor(PUBLIC_ROUTES.length));
    await sweepRoutes(page, PUBLIC_ROUTES, false);
  });

  test('USER 동선 화면이 뷰포트를 넘지 않는다', async ({ page }) => {
    test.setTimeout(budgetFor(USER_ROUTES.length));
    await loginAs(page, '👤 일반 사용자', '/order');
    await sweepRoutes(page, USER_ROUTES, true);
  });

  test('ADMIN 동선 화면이 뷰포트를 넘지 않는다', async ({ page }) => {
    test.setTimeout(budgetFor(ADMIN_ROUTES.length));
    await loginAs(page, '👑 관리자', '/admin');
    await sweepRoutes(page, ADMIN_ROUTES, true);
  });
});
