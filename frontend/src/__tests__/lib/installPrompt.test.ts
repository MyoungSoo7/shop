import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * 설치 유도 로직의 분기 테스트.
 *
 * 막으려는 사고 세 가지:
 *  ① 이미 설치해 쓰고 있는 사용자에게 "설치하세요" 배너를 띄우는 것.
 *  ② 한 번 거절한 사용자에게 매 방문 다시 띄우는 것(설치 배너의 대표적 민폐).
 *  ③ iOS 를 Chromium 과 같게 다뤄, 발화하지 않는 `beforeinstallprompt` 를 기다리다
 *     아무 안내도 못 하는 것.
 *
 * 모듈이 최상위 상태를 들고 있어 테스트마다 resetModules 로 새로 읽는다.
 */

const IOS_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
const ANDROID_UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

const NOW = 1_760_000_000_000;

/** window/navigator 를 최소한으로 세운다. matchMedia 는 standalone 판정에 쓰인다. */
const setupEnv = ({
  ua,
  standalone = false,
  dismissedAt = null,
  loggedIn = true,
}: {
  ua: string;
  standalone?: boolean;
  dismissedAt?: number | null;
  /** 로그인 흔적(access_token). 기본 true — 로그인 화면 케이스만 false 로 준다. */
  loggedIn?: boolean;
}) => {
  const store = new Map<string, string>();
  if (dismissedAt !== null) store.set('pwa_install_dismissed_at', String(dismissedAt));
  if (loggedIn) store.set('access_token', 'test-token');

  const handlers: Record<string, ((e: unknown) => void)[]> = {};
  const win = {
    matchMedia: (q: string) => ({ matches: standalone && q.includes('standalone') }),
    localStorage: {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    },
    addEventListener: (type: string, fn: (e: unknown) => void) => {
      (handlers[type] ??= []).push(fn);
    },
    location: { reload: vi.fn() },
  };
  vi.stubGlobal('window', win);
  vi.stubGlobal('navigator', { userAgent: ua });
  vi.stubGlobal('localStorage', win.localStorage);
  return { handlers, store };
};

const load = () => import('@/lib/installPrompt');

describe('installPrompt', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.unstubAllGlobals();
  });

  it('iOS 는 beforeinstallprompt 가 없으므로 곧바로 수동 안내 모드가 된다', async () => {
    setupEnv({ ua: IOS_UA });
    const { watchInstallAvailability, onInstallAvailable } = await load();

    watchInstallAvailability(NOW);
    const seen = vi.fn();
    onInstallAvailable(seen);

    expect(seen).toHaveBeenCalledWith({ kind: 'ios-manual' });
  });

  it('Chromium 은 beforeinstallprompt 가 와야 배너가 뜨고, 기본 배너는 막는다', async () => {
    const { handlers } = setupEnv({ ua: ANDROID_UA });
    const { watchInstallAvailability, onInstallAvailable } = await load();

    watchInstallAvailability(NOW);
    const seen = vi.fn();
    onInstallAvailable(seen);
    expect(seen, '이벤트 전에는 아무것도 띄우지 않는다').not.toHaveBeenCalled();

    const preventDefault = vi.fn();
    const event = { preventDefault, prompt: vi.fn(), userChoice: Promise.resolve({ outcome: 'accepted' }) };
    handlers['beforeinstallprompt'][0](event);

    expect(preventDefault, '막지 않으면 이벤트를 쥘 수 없다').toHaveBeenCalled();
    expect(seen).toHaveBeenCalledWith({ kind: 'prompt', event });
  });

  /**
   * 회귀 가드 — 이 배너가 로그인 화면을 덮어 데모 버튼 클릭을 막은 적이 있다.
   * e2e(mobile-safari) 6건이 `locator.click: Timeout` 으로 죽었고, 가로막은 요소가 이 배너였다.
   * 로그인 흔적이 없으면 어떤 조건에서도 뜨지 않아야 한다.
   */
  it('로그인 흔적이 없으면(로그인 화면) 띄우지 않는다 — 하단 배너가 로그인 버튼을 덮는다', async () => {
    setupEnv({ ua: IOS_UA, loggedIn: false });
    const { watchInstallAvailability, onInstallAvailable } = await load();

    watchInstallAvailability(NOW);
    const seen = vi.fn();
    onInstallAvailable(seen);

    expect(seen).not.toHaveBeenCalled();
  });

  it('Chromium 도 로그인 흔적이 없으면 beforeinstallprompt 를 아예 구독하지 않는다', async () => {
    const { handlers } = setupEnv({ ua: ANDROID_UA, loggedIn: false });
    const { watchInstallAvailability } = await load();

    watchInstallAvailability(NOW);

    expect(handlers['beforeinstallprompt'], '리스너 자체를 걸지 않는다').toBeUndefined();
  });

  it('이미 설치해 실행 중이면(standalone) 아무것도 띄우지 않는다', async () => {
    setupEnv({ ua: IOS_UA, standalone: true });
    const { watchInstallAvailability, onInstallAvailable } = await load();

    watchInstallAvailability(NOW);
    const seen = vi.fn();
    onInstallAvailable(seen);

    expect(seen).not.toHaveBeenCalled();
  });

  it('거절 후 유예 기간에는 다시 띄우지 않고, 기간이 지나면 다시 띄운다', async () => {
    const DAY = 24 * 60 * 60 * 1000;

    setupEnv({ ua: IOS_UA, dismissedAt: NOW - 3 * DAY });
    {
      const { watchInstallAvailability, onInstallAvailable } = await load();
      watchInstallAvailability(NOW);
      const seen = vi.fn();
      onInstallAvailable(seen);
      expect(seen, '3일 전 거절 — 아직 유예 중').not.toHaveBeenCalled();
    }

    vi.resetModules();
    setupEnv({ ua: IOS_UA, dismissedAt: NOW - 20 * DAY });
    {
      const { watchInstallAvailability, onInstallAvailable } = await load();
      watchInstallAvailability(NOW);
      const seen = vi.fn();
      onInstallAvailable(seen);
      expect(seen, '20일 전 거절 — 유예 만료').toHaveBeenCalledWith({ kind: 'ios-manual' });
    }
  });

  it('저장된 거절 시각이 깨져 있으면 유예 없음으로 본다', async () => {
    const { store } = setupEnv({ ua: IOS_UA });
    store.set('pwa_install_dismissed_at', 'not-a-number');
    const { watchInstallAvailability, onInstallAvailable } = await load();

    watchInstallAvailability(NOW);
    const seen = vi.fn();
    onInstallAvailable(seen);

    expect(seen).toHaveBeenCalledWith({ kind: 'ios-manual' });
  });

  it('설치를 거절하면 유예를 기록해 다음 방문에 다시 뜨지 않게 한다', async () => {
    const { handlers, store } = setupEnv({ ua: ANDROID_UA });
    const { watchInstallAvailability, onInstallAvailable, acceptInstall } = await load();

    watchInstallAvailability(NOW);
    const seen = vi.fn();
    onInstallAvailable(seen);

    const event = {
      preventDefault: vi.fn(),
      prompt: vi.fn().mockResolvedValue(undefined),
      userChoice: Promise.resolve({ outcome: 'dismissed' as const }),
    };
    handlers['beforeinstallprompt'][0](event);

    const mode = seen.mock.calls[0][0];
    const outcome = await acceptInstall(mode);

    expect(outcome).toBe('dismissed');
    expect(event.prompt).toHaveBeenCalled();
    expect(store.get('pwa_install_dismissed_at'), '거절은 유예로 기록된다').toBeTruthy();
    expect(seen).toHaveBeenLastCalledWith(null);
  });
});
