import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures';

/**
 * 대출 화면 신규 UI 동작 검증 — 실제 로그인 + route mock. dev·운영 양쪽에서 동작한다.
 *
 * 백엔드 응답은 mock 해 화면 렌더·상호작용만 결정적으로 검증한다(실제 인가는 백엔드가 강제).
 * 다만 **인증은 mock 하지 않는다** — 가짜 토큰(`e2e-dev-token`)을 주입하면 운영에서 401 로 튕긴다.
 *
 * ⚠️ 운영에서 `page.goto('/loans')` 를 쓰면 안 된다. nginx(frontend/nginx.conf)가 `/loans` 를 **백엔드로
 * 프록시**하므로 SPA 에 닿지 않고, 문서 내비게이션은 Authorization 헤더를 실을 수 없어 백엔드가 401 →
 * Whitelabel Error Page 를 반환한다(2026-07-28 main CI smoke 실패 원인). 그래서
 * ① SPA 진입점 `/login` 에서 실제 JWT 를 발급받고 ② `/loans` 로는 **클라이언트 라우팅**으로만 이동한다.
 *
 * ⚠️ Playwright 의 glob/정규식/술어 라우트는 쿼리스트링 URL(예: /loans?sellerId=1)을 신뢰성 있게 매칭하지
 * 못한다(SPA 경로 /loans 와 API 경로 /loans 충돌). 그래서 '**\/*' 단일 catch-all 로 모든 요청을 가로채
 * URL 로 분기한다 — 매칭이 확정적이고 브라우저 캐시도 우회된다.
 *
 * 실행: 운영 대상은 PLAYWRIGHT_BASE_URL=https://jen.lemuel.co.kr (CI smoke 와 동일).
 * 로컬은 vite(`npm run dev`, 3000) + order-service(8088) 기동 후 PLAYWRIGHT_BASE_URL=http://localhost:3000
 * (vite 가 `/auth` 를 8088 로 프록시하므로 자동로그인이 그대로 동작한다).
 */

/** 데모 자동로그인으로 **실제** ADMIN JWT 를 발급받는다 (auto-login.spec.ts 와 동일 경로). */
async function loginAsAdmin(page: Page) {
  const loginResponse = page.waitForResponse(
    (res) =>
      res.url().includes('/auth/dev/auto-login?role=ADMIN') && res.request().method() === 'POST',
  );
  await page.goto('/login');
  await page.getByRole('button', { name: '👑 관리자' }).click();

  const response = await loginResponse;
  expect(response.status(), 'POST /auth/dev/auto-login?role=ADMIN 응답 200 기대').toBe(200);
  await expect(page).toHaveURL(/\/admin(\/|$|\?)/);
  await expect
    .poll(() => page.evaluate(() => window.localStorage.getItem('access_token')), {
      timeout: 5_000,
      message: 'localStorage access_token 저장',
    })
    .toBeTruthy();
}

/** SPA 내부 이동 — /loans 로의 **문서 요청을 만들지 않는다**(운영에선 백엔드로 프록시되어 401). */
async function gotoLoansClientSide(page: Page) {
  await page.evaluate(() => {
    window.history.pushState({}, '', '/loans');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page).toHaveURL(/\/loans(\/|$|\?)/);
}

const json = (route: Route, body: unknown) =>
  route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });

// 서비스워커가 XHR 을 가로채면 page.route 가 무력화된다(문서 요청만 잡히고 fetch 는 SW 로 흐름).
// SW 를 차단해 모든 요청이 네트워크(→ page.route)로 흐르게 한다.
test.use({ serviceWorkers: 'block' });

test.describe('대출 화면 — 신규 UI (만기 / 연체·상각 / 기업 상환)', () => {
  test.beforeEach(async ({ page }) => {
    // HTTP 캐시 비활성화 — dev 서버가 /loans?sellerId= 를 index.html(200)로 응답하면 브라우저가 캐시하고,
    // 캐시 히트는 page.route 가 가로채지 못한다(요청 이벤트만 발생). CDP 로 캐시를 꺼 모든 요청을 네트워크로 강제.
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
    // 인증은 mock 하지 않는다 — 실제 토큰을 발급받아야 운영 백엔드가 401 을 내지 않는다.
    // 로그인 흐름 자체는 실네트워크로 흘러야 하므로 route mock 은 로그인 이후에 설치한다.
    await loginAsAdmin(page);
  });

  test('선정산 목록: 만기 컬럼 + ADMIN 연체/상각 버튼 + 연체 실행', async ({ page }) => {
    let overdueCalled = false;
    await page.route('**/*', async (route) => {
      const url = route.request().url();
      if (url.includes('/loans/1/overdue')) {
        overdueCalled = true;
        return json(route, { id: 1, sellerId: 1, principal: 800000, fee: 800, outstanding: 800800, status: 'OVERDUE', dueAt: '2026-07-17T09:00:00' });
      }
      if (url.includes('/loans') && url.includes('sellerId')) {
        return json(route, [
          { id: 1, sellerId: 1, principal: 800000, fee: 800, outstanding: 800800, status: 'DISBURSED', disbursedAt: '2026-07-10T09:00:00', dueAt: '2026-07-17T09:00:00' },
          { id: 2, sellerId: 1, principal: 500000, fee: 500, outstanding: 500500, status: 'OVERDUE', disbursedAt: '2026-06-01T09:00:00', dueAt: '2026-06-08T09:00:00' },
        ]);
      }
      return route.continue();
    });

    await gotoLoansClientSide(page);
    await page.getByRole('button', { name: '조회' }).click();

    await expect(page.getByRole('columnheader', { name: '만기' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /2026/ }).first()).toBeVisible();
    await expect(page.getByText('OVERDUE')).toBeVisible();
    await expect(page.getByRole('button', { name: '연체' })).toBeVisible();
    await expect(page.getByRole('button', { name: '상각' })).toBeVisible();

    await page.getByRole('button', { name: '연체' }).click();
    await expect.poll(() => overdueCalled, { timeout: 5000 }).toBe(true);
    await expect(page.getByText(/연체 처리 완료/)).toBeVisible();

    await page.screenshot({ path: 'e2e/__screenshots__/loan-seller.png', fullPage: true });
  });

  test('기업대출: DISBURSED 에 상환 버튼 + 상환 실행(금액 전달)', async ({ page }) => {
    let repayAmount: number | null = null;
    await page.route('**/*', async (route) => {
      const req = route.request();
      const url = req.url();
      if (url.includes('/loans/corporate/5001/repay')) {
        repayAmount = req.postDataJSON()?.amount ?? null;
        return json(route, { id: 5001, stockCode: '005930', corpName: '삼성전자', principal: 1000000, fee: 6000, outstanding: 406000, termDays: 30, creditScore: 82, creditGrade: 'A', status: 'DISBURSED' });
      }
      if (url.includes('/loans/corporate/credit/005930')) {
        return json(route, { stockCode: '005930', corpName: '삼성전자', market: 'KOSPI', fiscalYear: 2025, creditScore: 82, creditGrade: 'A', limit: 5000000, debtRatio: 40.2, operatingMargin: 15.5, roa: 8.1, reputationGrade: 'B' });
      }
      if (url.includes('/loans/corporate') && url.includes('stockCode=005930')) {
        return json(route, [{ id: 5001, stockCode: '005930', corpName: '삼성전자', principal: 1000000, fee: 6000, outstanding: 1006000, termDays: 30, creditScore: 82, creditGrade: 'A', status: 'DISBURSED' }]);
      }
      if (url.includes('/api/financial/companies')) {
        return json(route, { content: [{ stockCode: '005930', name: '삼성전자', market: 'KOSPI' }], totalElements: 1, totalPages: 1, number: 0, size: 10 });
      }
      return route.continue();
    });

    await gotoLoansClientSide(page);
    await page.getByRole('button', { name: '기업대출' }).click();
    await page.getByPlaceholder('기업명 또는 종목코드').fill('삼성');
    await page.getByRole('button', { name: '검색' }).click();
    await page.getByRole('button', { name: /삼성전자/ }).click();

    const repayBtn = page.getByRole('button', { name: '상환' });
    await expect(repayBtn).toBeVisible();
    await repayBtn.click();
    await page.getByRole('button', { name: '확인' }).click();
    await expect.poll(() => repayAmount, { timeout: 5000 }).toBe(1006000);
    await expect(page.getByText(/기업대출 상환 완료/)).toBeVisible();

    await page.screenshot({ path: 'e2e/__screenshots__/loan-corporate.png', fullPage: true });
  });
});
