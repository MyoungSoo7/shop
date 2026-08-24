// Lemuel Settlement PWA service worker — app shell 캐시(오프라인 껍데기) + network-first
// SPA 라 라우트는 index.html 로 폴백. API(axios) 응답은 캐시하지 않음(정산 데이터 신선도).
const CACHE = 'settlement-shell-v2';

/**
 * 백엔드로 프록시되는 경로 — 캐시에 절대 담지 않는다.
 *
 * 정본은 frontend/nginx.conf 의 프록시 location 정규식이다. 예전에는 '/api' 하나만 걸렀는데,
 * 이 앱의 REST 는 /users/me, /settlements/{id} 처럼 **최상위 경로**도 쓴다. 그래서 그 응답들이
 * 아래 "정적 자원 cache-first" 가지로 새어 들어가 캐시에 눌러앉았다:
 *   · /users/me 의 401 이 캐시되면 → 이후 모든 하드 로드가 즉시 로그아웃된다(인쇄 창은 항상 새 창).
 *   · /settlements/{id} 가 캐시되면 → 정산서에 **낡은 금액**이 찍힌다.
 */
const BACKEND_PATH =
  /^\/(auth|api|admin|users|orders|payments|reviews|settlements|loans|categories|coupons|games|products|tags|refunds|chargebacks|ledger|actuator|swagger-ui|v3)(\/|$)/;
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png'];

/**
 * 설치 후 **스스로 교체하지 않는다**(skipWaiting 제거).
 *
 * 예전에는 install 끝에 `skipWaiting()` 을 불러 새 워커가 즉시 페이지를 넘겨받았다. 그러면 사용자가
 * 보던 화면(구버전 JS)과 새로 받는 자산(신버전 청크)이 한 세션 안에서 섞인다. 정산 금액이 떠 있는
 * 화면에서 이 혼합이 일어나면 무엇을 보고 있는지 보증할 수 없다.
 * 그래서 새 워커는 waiting 상태로 대기하고, 교체 시점은 사용자가 고른다(UpdatePrompt → SKIP_WAITING).
 */
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
});

// 페이지가 "지금 갱신" 을 눌렀을 때만 교체한다. 이 메시지 외에는 스스로 활성화되지 않는다.
self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // 백엔드 API·외부 호출은 캐시 개입 없이 그대로 (정산 데이터 신선도 보장)
  if (BACKEND_PATH.test(url.pathname) || url.origin !== self.location.origin) return;

  // SPA 네비게이션: network-first → 실패 시 캐시된 index.html
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).catch(() => caches.match('/index.html')));
    return;
  }
  // 정적 자원: cache-first, 없으면 네트워크 후 캐시
  e.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => hit))
  );
});
