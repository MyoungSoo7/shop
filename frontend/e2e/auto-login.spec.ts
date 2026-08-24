import { type Page } from '@playwright/test';
import { test, expect } from './fixtures';

const ROLES = [
  { name: 'USER', button: '👤 일반 사용자', expectedPath: '/order', expectedRole: 'USER' },
  { name: 'MANAGER', button: '🧑‍💼 매니저', expectedPath: '/admin', expectedRole: 'MANAGER' },
  { name: 'ADMIN', button: '👑 관리자', expectedPath: '/admin', expectedRole: 'ADMIN' },
] as const;

async function clearStorage(page: Page) {
  await page.addInitScript(() => {
    try {
      window.localStorage.clear();
    } catch {
      // SecurityError if no origin yet; ignore — first navigation establishes origin.
    }
  });
}

test.describe('데모 자동로그인 (운영 통합 검증)', () => {
  test.beforeEach(async ({ page }) => {
    await clearStorage(page);
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '👤 일반 사용자' })).toBeVisible();
  });

  for (const role of ROLES) {
    test(`${role.name} 자동로그인 → ${role.expectedPath} 이동 + JWT 저장`, async ({ page }) => {
      const loginResponse = page.waitForResponse(
        (res) =>
          res.url().includes(`/auth/dev/auto-login?role=${role.name}`) && res.request().method() === 'POST',
      );

      await page.getByRole('button', { name: role.button }).click();

      const response = await loginResponse;
      expect(response.status(), `POST /auth/dev/auto-login?role=${role.name} 응답 200 기대`).toBe(200);

      const body = await response.json();
      expect(body.token, 'JWT 토큰 발급').toBeTruthy();
      expect(body.role, '응답 역할 일치').toBe(role.expectedRole);

      await expect(page).toHaveURL(new RegExp(`${role.expectedPath}(/|$|\\?)`));

      const stored = await page.evaluate(() => ({
        token: window.localStorage.getItem('access_token'),
        role: window.localStorage.getItem('user_role'),
        email: window.localStorage.getItem('user_email'),
      }));
      expect(stored.token, 'localStorage access_token 저장').toBeTruthy();
      expect(stored.role, 'localStorage user_role 저장').toBe(role.expectedRole);
      expect(stored.email, 'localStorage user_email 저장').toBeTruthy();
    });
  }

  test('GUEST 둘러보기 → 토큰 발급 + role=GUEST 저장', async ({ page }) => {
    // NOTE: 현재 App.tsx 가 "/" → Navigate to="/login" 으로 무조건 라우팅하기 때문에
    // 게스트 로그인이 토큰은 정상 발급해도 화면상 /login 으로 튕겨돌아오는 UX 가 있음.
    // 본 테스트는 통합 라우팅 결함을 사전 차단하는 것이 목적이므로 API 응답 + localStorage 만 검증.
    // 게스트 진입 후 보일 페이지를 별도로 라우팅 보강하는 작업은 follow-up.
    const guestResponse = page.waitForResponse(
      (res) => res.url().includes('/auth/dev/guest') && res.request().method() === 'POST',
    );

    await page.getByRole('button', { name: '🔍 게스트 둘러보기' }).click();

    const response = await guestResponse;
    expect(response.status(), 'POST /auth/dev/guest 응답 200 기대').toBe(200);

    const body = await response.json();
    expect(body.token, '게스트 JWT 토큰 발급').toBeTruthy();
    expect(body.role, '응답 role=GUEST').toBe('GUEST');

    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem('access_token')), {
        timeout: 5_000,
        message: '게스트 토큰 localStorage 저장',
      })
      .toBeTruthy();

    const stored = await page.evaluate(() => ({
      role: window.localStorage.getItem('user_role'),
      email: window.localStorage.getItem('user_email'),
    }));
    expect(stored.role, 'localStorage user_role=GUEST').toBe('GUEST');
    expect(stored.email, 'localStorage user_email 저장').toBeTruthy();
  });
});
