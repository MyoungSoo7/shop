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

async function loginAs(page: Page, button: string, expectedPath: string) {
  await page.addInitScript(() => {
    try {
      window.localStorage.clear();
    } catch {
      // origin 이 아직 없으면 SecurityError — 첫 내비게이션이 origin 을 만든다.
    }
  });
  await page.goto('/login');
  await page.getByRole('button', { name: button }).click();
  await expect(page).toHaveURL(new RegExp(`${expectedPath}(/|$|\\?)`));
}

/** 로그인 없이 볼 수 있는 화면. */
const PUBLIC_ROUTES = ['/login'];

/** USER 동선 — 주문·장바구니·마이페이지가 모바일 사용 비중이 가장 높다. */
const USER_ROUTES = ['/order', '/cart', '/mypage', '/recommend', '/loans', '/ai/chat'];

/**
 * ADMIN 동선 — 표가 많아 넘침이 가장 잘 나는 화면들.
 * 정산·CEO·시스템 그룹은 좌측 사이드바 셸(SideNavLayout)을 쓰므로 그룹별로 최소 1개씩 포함해
 * **사이드바가 붙는 3개 그룹이 모두 측정되게** 한다(항목은 menus 테이블이 정한다).
 */
const ADMIN_ROUTES = [
  '/admin',
  '/admin/settlement',
  '/settlement/search',
  '/product',
  '/admin/payouts',
  '/admin/shipping',
  '/admin/approvals',
  '/workforce',
  // CEO 그룹
  '/admin/ceo/insight',
  '/admin/ceo/invest',
  '/admin/ceo/companies',
  // 시스템 그룹
  '/admin/system/menus',
  '/admin/system/codes',
  '/admin/system/operation',
];

test.describe('모바일 레이아웃 — 가로 넘침 회귀 가드', () => {
  test.skip(({ isMobile }) => !isMobile, '모바일 뷰포트 프로젝트 전용');

  // 종료 시 페이지 정리(WebKit 워커 행 회피)는 fixtures.ts 의 `releaseAppPages` 가 전 스펙 공통으로 한다.

  test('비로그인 화면이 뷰포트를 넘지 않는다', async ({ page }) => {
    for (const route of PUBLIC_ROUTES) {
      await page.goto(route);
      await expectNoHorizontalOverflow(page, route);
    }
  });

  test('USER 동선 화면이 뷰포트를 넘지 않는다', async ({ page }) => {
    await loginAs(page, '👤 일반 사용자', '/order');
    for (const route of USER_ROUTES) {
      await page.goto(route);
      await expectRouteReached(page, route);
      await expectNoHorizontalOverflow(page, route);
    }
  });

  test('ADMIN 동선 화면이 뷰포트를 넘지 않는다', async ({ page }) => {
    await loginAs(page, '👑 관리자', '/admin');
    for (const route of ADMIN_ROUTES) {
      await page.goto(route);
      await expectRouteReached(page, route);
      await expectNoHorizontalOverflow(page, route);
    }
  });
});
