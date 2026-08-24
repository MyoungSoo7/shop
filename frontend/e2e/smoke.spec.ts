import { test, expect } from './fixtures';

test.describe('운영 smoke — 라우팅/백엔드 프록시 통합', () => {
  test('루트 / 200 + 로그인 페이지 진입 가능', async ({ page }) => {
    const response = await page.goto('/');
    expect(response?.status(), '/ 응답 코드').toBeLessThan(400);
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '👤 일반 사용자' })).toBeVisible();
  });

  test('/actuator/health → 200 (nginx → 백엔드 프록시 정상)', async ({ request }) => {
    const res = await request.get('/actuator/health');
    expect(res.status(), '/actuator/health 200 — nginx upstream + 백엔드 헬스').toBe(200);
    const body = await res.json();
    expect(body.status, '백엔드 health.status=UP').toBe('UP');
  });

  test('/auth/dev/auto-login?role=USER POST → 200 (백엔드 도달)', async ({ request }) => {
    const res = await request.post('/auth/dev/auto-login?role=USER');
    expect(
      res.status(),
      'POST /auth/dev/auto-login — 인증 끊김(401/403)/프록시 끊김(502/404) 사전 차단',
    ).toBe(200);
  });
});
