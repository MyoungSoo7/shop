import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'https://jen.lemuel.co.kr';
const isCI = !!process.env.CI;

/**
 * loan.spec.ts 는 **Chromium 전용 API** 에 의존하므로 크로스 브라우저 프로젝트에서 제외한다.
 *  - `page.context().newCDPSession(page)` — CDP 는 Chromium 만 지원(firefox/webkit 에서 throw).
 *  - `test.use({ serviceWorkers: 'block' })` — 이 컨텍스트 옵션은 Chromium 에서만 유효.
 *    Firefox 는 SW 가 살아 있어 `page.route` 가 XHR 을 못 잡고 mock 이 통째로 무력화된다.
 * 크로스 브라우저·모바일 검증은 smoke(라우팅/프록시) + auto-login(인증 플로우)이 담당한다.
 */
const CHROMIUM_ONLY = ['**/loan.spec.ts'];

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  // 로컬 기본값(=CPU 코어 수, 실측 10워커)에서 WebKit 워커가 종료되지 않고 300초 뒤 force-kill 되는
  // 현상이 간헐 발생한다(테스트는 전부 통과해도 종료 코드 1). 4워커 이하에서는 재현되지 않아 상한을 둔다.
  workers: isCI ? 2 : 4,
  reporter: isCI ? [['list'], ['html', { open: 'never' }], ['github']] : 'list',
  timeout: 30_000,
  expect: { timeout: 7_000 },

  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    // 원격 운영 URL 을 5개 엔진이 동시에 때리므로 15s 는 빠듯하다 — webkit 워커 4개 병렬에서
    // `page.goto('/login')` 이 15s 를 넘겨 실패한 실측(2026-08-11)에 맞춰 상향.
    navigationTimeout: 30_000,
  },

  projects: [
    // 기준 프로젝트 — 전체 스펙 실행(loan.spec.ts 포함).
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    // 크로스 브라우저 — 렌더링 엔진(Gecko / WebKit) 차이 검증.
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      testIgnore: CHROMIUM_ONLY,
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      testIgnore: CHROMIUM_ONLY,
      // 아래 mobile-safari 와 같은 이유 — WebKit 워커 행 억제.
      workers: 1,
    },
    // 모바일 — 뷰포트(393/390px)·터치·devicePixelRatio 까지 포함한 반응형 검증.
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 7'] },
      testIgnore: CHROMIUM_ONLY,
    },
    /**
     * WebKit 계열은 **프로젝트당 워커 1개**로 직렬화한다.
     *
     * 이 두 프로젝트에서만 "worker process did not exit within 300000ms" 가 간헐 발생한다. 워커가
     * 300초 force-kill 되면 **모든 테스트가 통과해도 종료 코드가 1** 이라 CI 가 빨갛게 뜨고, 이건
     * 테스트 실패가 아니라 프로세스 종료 문제라 `retries` 로 구제되지 않는다.
     * fixtures 의 정리(서비스워커 등록 해제 + about:blank)로 빈도를 크게 줄였으나 9회 중 1회가 남았고,
     * 남은 재현은 전부 WebKit 워커가 여러 개 동시에 뜬 실행이었다. 동시 WebKit 워커 수를 줄여 재현
     * 조건 자체를 좁힌다.
     *
     * `TestProject.workers`(Playwright 1.52+, 설치본 1.60.0 지원 확인)는 전역 `workers` 와 함께
     * 동작한다 — 다른 프로젝트의 병렬도는 그대로 두고 이 프로젝트만 직렬화한다. 대가는 WebKit
     * 소요 시간 증가이고, 얻는 것은 초록/빨강 판정의 신뢰성이다.
     */
    {
      name: 'mobile-safari',
      use: { ...devices['iPhone 14'] },
      testIgnore: CHROMIUM_ONLY,
      workers: 1,
    },
    {
      name: 'mobile-320',
      use: { ...devices['Pixel 7'], viewport: { width: 320, height: 800 } },
    },
    {
      name: 'mobile-375',
      use: { ...devices['Pixel 7'], viewport: { width: 375, height: 812 } },
    },
    {
      name: 'mobile-390',
      use: { ...devices['Pixel 7'], viewport: { width: 390, height: 844 } },
    },
    {
      name: 'mobile-landscape',
      use: { ...devices['Pixel 7'], viewport: { width: 844, height: 390 }, isMobile: true },
    },
  ],
});
