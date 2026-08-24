/**
 * 서비스워커 등록 + "새 버전 대기 중" 감지.
 *
 * 예전에는 index.html 인라인 스크립트가 등록만 했고, `sw.js` 가 install 에서 스스로 `skipWaiting()`
 * 을 불러 즉시 교체했다. 사용자가 보던 화면(구버전 JS)과 새로 받는 청크(신버전)가 섞일 수 있는데,
 * 정산 금액 화면에서 그 혼합은 "지금 보는 숫자가 어느 버전인지" 보증을 깬다.
 *
 * 지금은 새 워커가 waiting 에 머무르고, 교체 시점을 사용자가 고른다. 이 모듈은 그 상태를 구독
 * 가능한 형태로 노출하기만 하고, 화면 표현은 `UpdatePrompt` 가 담당한다(로직/표현 분리 — 테스트 용이).
 */

type Listener = (waiting: ServiceWorker) => void;

let waitingWorker: ServiceWorker | null = null;
const listeners = new Set<Listener>();

const notify = (worker: ServiceWorker) => {
  waitingWorker = worker;
  listeners.forEach((listener) => listener(worker));
};

/**
 * 대기 중인 워커를 구독한다. 구독 시점에 이미 대기 중이면 즉시 한 번 통지한다 —
 * 등록이 컴포넌트 마운트보다 먼저 끝나는 경쟁을 없애기 위해서다.
 * 반환값은 구독 해제 함수.
 */
export const onUpdateReady = (listener: Listener): (() => void) => {
  listeners.add(listener);
  if (waitingWorker) listener(waitingWorker);
  return () => listeners.delete(listener);
};

/**
 * 교체를 지시하고, 실제 교체가 끝나면(controllerchange) 새로고침한다.
 *
 * reload 를 controllerchange 에서 하는 이유: 메시지 직후 새로고침하면 아직 구버전 워커가 컨트롤러라
 * 같은 자산을 다시 받아 무한 새로고침이 될 수 있다. 교체 완료를 신호로 받고 한 번만 돈다.
 */
export const applyUpdate = (worker: ServiceWorker) => {
  let reloaded = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (reloaded) return;
    reloaded = true;
    window.location.reload();
  });
  worker.postMessage({ type: 'SKIP_WAITING' });
};

/** 서비스워커 등록. 미지원 브라우저·등록 실패는 조용히 무시한다(앱은 그대로 동작해야 한다). */
export const registerServiceWorker = () => {
  if (!('serviceWorker' in navigator)) return;

  navigator.serviceWorker
    .register('/sw.js')
    .then((registration) => {
      // 이미 대기 중인 워커가 있는 경우(이전 방문에서 받아둔 새 버전).
      if (registration.waiting && navigator.serviceWorker.controller) notify(registration.waiting);

      registration.addEventListener('updatefound', () => {
        const installing = registration.installing;
        if (!installing) return;
        installing.addEventListener('statechange', () => {
          // controller 가 없으면 최초 설치다 — 갱신이 아니므로 알리지 않는다.
          if (installing.state === 'installed' && navigator.serviceWorker.controller) {
            notify(installing);
          }
        });
      });
    })
    .catch(() => {
      /* 등록 실패는 오프라인 캐시만 없는 것이라 앱 동작에 영향이 없다. */
    });
};
