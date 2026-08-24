import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * 서비스워커 갱신 흐름의 분기 테스트.
 *
 * 여기서 지키려는 것은 두 가지 사고다.
 *  ① 최초 설치(=아직 controller 없음)를 "새 버전"으로 오인해 처음 방문자에게 갱신 배너를 띄우는 것.
 *  ② `postMessage` 직후 새로고침해서, 아직 구버전 워커가 컨트롤러인 채로 같은 자산을 다시 받아
 *     무한 새로고침에 빠지는 것 — reload 는 controllerchange 를 신호로 **한 번만** 돌아야 한다.
 *
 * 모듈이 최상위에 상태(waitingWorker/listeners)를 들고 있어 테스트마다 resetModules 로 새로 읽는다.
 */

type Handler = () => void;

/** statechange/updatefound 를 수동으로 발화할 수 있는 최소 EventTarget 스텁. */
const makeEventful = () => {
  const handlers: Record<string, Handler[]> = {};
  return {
    handlers,
    addEventListener: (type: string, fn: Handler) => {
      (handlers[type] ??= []).push(fn);
    },
    fire: (type: string) => (handlers[type] ?? []).forEach((fn) => fn()),
  };
};

const loadModule = () => import('@/lib/serviceWorkerUpdate');

describe('serviceWorkerUpdate', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it('최초 설치(controller 없음)는 갱신으로 보지 않는다 — 첫 방문자에게 배너를 띄우지 않는다', async () => {
    const installing = { ...makeEventful(), state: 'installed' };
    const registration = { ...makeEventful(), installing, waiting: null };

    vi.stubGlobal('navigator', {
      serviceWorker: {
        register: vi.fn().mockResolvedValue(registration),
        controller: null, // 최초 설치 — 아직 이 페이지를 제어하는 워커가 없다.
        addEventListener: vi.fn(),
      },
    });

    const { registerServiceWorker, onUpdateReady } = await loadModule();
    const seen = vi.fn();
    onUpdateReady(seen);

    registerServiceWorker();
    await Promise.resolve();
    registration.fire('updatefound');
    installing.fire('statechange');

    expect(seen).not.toHaveBeenCalled();
  });

  it('기존 controller 가 있을 때 설치 완료면 대기 워커를 통지한다', async () => {
    const installing = { ...makeEventful(), state: 'installed' };
    const registration = { ...makeEventful(), installing, waiting: null };

    vi.stubGlobal('navigator', {
      serviceWorker: {
        register: vi.fn().mockResolvedValue(registration),
        controller: {}, // 이미 구버전 워커가 제어 중 — 이제 설치된 것은 새 버전이다.
        addEventListener: vi.fn(),
      },
    });

    const { registerServiceWorker, onUpdateReady } = await loadModule();
    const seen = vi.fn();
    onUpdateReady(seen);

    registerServiceWorker();
    await Promise.resolve();
    registration.fire('updatefound');
    installing.fire('statechange');

    expect(seen).toHaveBeenCalledTimes(1);
    expect(seen).toHaveBeenCalledWith(installing);
  });

  it('구독 시점에 이미 대기 중이면 즉시 통지한다 — 등록이 마운트보다 빨라도 놓치지 않는다', async () => {
    const waiting = { postMessage: vi.fn() };
    const registration = { ...makeEventful(), installing: null, waiting };

    vi.stubGlobal('navigator', {
      serviceWorker: {
        register: vi.fn().mockResolvedValue(registration),
        controller: {},
        addEventListener: vi.fn(),
      },
    });

    const { registerServiceWorker, onUpdateReady } = await loadModule();
    registerServiceWorker();
    await Promise.resolve();

    const late = vi.fn(); // 등록이 끝난 **뒤에** 구독하는 경우
    onUpdateReady(late);
    expect(late).toHaveBeenCalledWith(waiting);
  });

  it('applyUpdate 는 SKIP_WAITING 을 보내고 controllerchange 에서 딱 한 번 새로고침한다', async () => {
    const controllerChange: Handler[] = [];
    const reload = vi.fn();

    vi.stubGlobal('navigator', {
      serviceWorker: {
        register: vi.fn(),
        controller: {},
        addEventListener: (type: string, fn: Handler) => {
          if (type === 'controllerchange') controllerChange.push(fn);
        },
      },
    });
    vi.stubGlobal('window', { location: { reload } });

    const { applyUpdate } = await loadModule();
    const worker = { postMessage: vi.fn() };

    applyUpdate(worker as unknown as ServiceWorker);
    expect(worker.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' });
    expect(reload, 'postMessage 만으로는 새로고침하지 않는다').not.toHaveBeenCalled();

    controllerChange.forEach((fn) => fn());
    controllerChange.forEach((fn) => fn()); // 두 번 발화해도 — 무한 새로고침 방지
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('서비스워커 미지원 브라우저에서는 조용히 아무것도 하지 않는다', async () => {
    vi.stubGlobal('navigator', {});
    const { registerServiceWorker } = await loadModule();
    expect(() => registerServiceWorker()).not.toThrow();
  });
});
