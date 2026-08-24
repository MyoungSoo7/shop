// PWA 자산 생성 — iOS 스플래시(portrait) + manifest 스크린샷.
// 별도 이미지 라이브러리를 들이지 않고, 이미 있는 Playwright 로 정확한 픽셀 크기를 렌더해 캡처한다.
//
// 실행: `npm run pwa:assets` (frontend 를 cwd 로 가정 — 출력 경로가 public/ 상대경로다)
// 스크린샷 단계는 `npm run build && npx vite preview --port 4173` 이 떠 있어야 한다.
// 산출물은 커밋 대상이다(런타임에 생성할 수 없는 정적 자산).
import { chromium, devices } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

const BRAND_BG = '#0f172a'; // manifest background_color 와 동일해야 실행 시 이음매가 없다.

/**
 * iOS 스플래시 대상 — 최근 아이폰 위주 8종(세로만).
 * iOS 는 media 쿼리의 device-width/height/DPR 이 **정확히** 맞을 때만 해당 이미지를 쓴다.
 * 하나라도 어긋나면 흰 화면으로 떨어지므로 논리 크기·배율을 함께 표에 둔다.
 */
const SPLASH = [
  { name: 'iphone-750x1334', w: 375, h: 667, dpr: 2 }, // SE2/3, 8
  { name: 'iphone-828x1792', w: 414, h: 896, dpr: 2 }, // XR, 11
  { name: 'iphone-1125x2436', w: 375, h: 812, dpr: 3 }, // X, XS, 11 Pro
  { name: 'iphone-1170x2532', w: 390, h: 844, dpr: 3 }, // 12, 13, 14
  { name: 'iphone-1179x2556', w: 393, h: 852, dpr: 3 }, // 14 Pro, 15
  { name: 'iphone-1242x2688', w: 414, h: 896, dpr: 3 }, // XS Max, 11 Pro Max
  { name: 'iphone-1284x2778', w: 428, h: 926, dpr: 3 }, // 12/13 Pro Max, 14 Plus
  { name: 'iphone-1290x2796', w: 430, h: 932, dpr: 3 }, // 14 Pro Max, 15 Pro Max
];

const splashHtml = (w, h) => `<!doctype html><meta charset="utf-8">
<style>
  html,body{margin:0;width:${w}px;height:${h}px;background:${BRAND_BG};
    display:flex;align-items:center;justify-content:center;
    font-family:Inter,system-ui,-apple-system,'Segoe UI',sans-serif}
  .wrap{text-align:center;color:#fff}
  .logo{font-size:${Math.round(w * 0.11)}px;font-weight:800;letter-spacing:-.02em}
  .sub{margin-top:${Math.round(h * 0.012)}px;font-size:${Math.round(w * 0.035)}px;
    color:#94a3b8;letter-spacing:.08em}
</style>
<div class="wrap"><div class="logo">Lemuel</div><div class="sub">SETTLEMENT</div></div>`;

const browser = await chromium.launch();

// ── iOS 스플래시 ───────────────────────────────────────────────────────────
mkdirSync('public/splash', { recursive: true });
const links = [];
for (const s of SPLASH) {
  const ctx = await browser.newContext({
    viewport: { width: s.w, height: s.h },
    deviceScaleFactor: s.dpr,
  });
  const page = await ctx.newPage();
  await page.setContent(splashHtml(s.w, s.h));
  const buf = await page.screenshot({ type: 'png' });
  writeFileSync(`public/splash/${s.name}.png`, buf);
  console.log(`splash ${s.name}.png  ${s.w}x${s.h}@${s.dpr}x = ${s.w * s.dpr}x${s.h * s.dpr}  ${buf.length}B`);
  links.push(
    `    <link rel="apple-touch-startup-image" media="(device-width: ${s.w}px) and (device-height: ${s.h}px) and (-webkit-device-pixel-ratio: ${s.dpr}) and (orientation: portrait)" href="/splash/${s.name}.png" />`,
  );
  await ctx.close();
}
writeFileSync('splash-links.local.html', links.join('\n') + '\n');

// ── manifest 스크린샷 ──────────────────────────────────────────────────────
// 로그인 화면만 찍는다 — 인증 후 화면은 정산 금액·거래처가 찍혀 저장소에 남는다.
mkdirSync('public/screenshots', { recursive: true });
const SHOTS = [
  { name: 'narrow', opts: { ...devices['iPhone 14'] }, form: 'narrow' },
  { name: 'wide', opts: { viewport: { width: 1280, height: 800 } }, form: 'wide' },
];
for (const s of SHOTS) {
  const ctx = await browser.newContext({ ...s.opts, baseURL: 'http://localhost:4173' });
  // 설치 배너를 억제한다 — manifest 스크린샷은 앱의 정상 화면을 보여야 하고, 일시적 배너가
  // 찍히면 "설치하라"는 안내가 설치 다이얼로그 안에 다시 나오는 우스운 그림이 된다.
  // (iOS UA 에서 배너가 실제로 뜨는 것은 이 스크립트 첫 실행에서 확인했다.)
  await ctx.addInitScript(() => {
    try {
      window.localStorage.setItem('pwa_install_dismissed_at', String(Date.now()));
    } catch {
      /* 저장 불가 환경은 무시 */
    }
  });
  const page = await ctx.newPage();
  await page.goto('/login');
  await page.waitForSelector('input', { timeout: 10_000 });
  const buf = await page.screenshot({ type: 'png' });
  writeFileSync(`public/screenshots/${s.name}.png`, buf);
  const size = page.viewportSize();
  const dpr = s.opts.deviceScaleFactor ?? 1;
  console.log(
    `shot ${s.name}.png  ${size.width * dpr}x${size.height * dpr} (${s.form})  ${buf.length}B`,
  );
  await ctx.close();
}

await browser.close();
