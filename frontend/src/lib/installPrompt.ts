/**
 * 홈 화면 설치 유도 — 브라우저마다 방식이 달라 두 갈래로 갈린다.
 *
 * · Chromium 계열: 설치 조건을 만족하면 `beforeinstallprompt` 가 발화한다. 기본 동작을 막아
 *   이벤트를 쥐고 있다가, 사용자가 우리 버튼을 눌렀을 때 `prompt()` 를 부른다.
 *   (이벤트는 **한 번만** 쓸 수 있어 사용 후 버린다.)
 * · iOS Safari: 이 이벤트를 아예 발화하지 않는다. `prompt()` 로 설치시킬 방법이 없으므로
 *   "공유 → 홈 화면에 추가" 를 안내하는 수밖에 없다. 그래서 iOS 는 별도 모드로 다룬다.
 *
 * 표시 여부 판단은 전부 여기서 하고, 화면 표현은 `InstallPrompt` 가 맡는다(테스트 가능하게 분리).
 */

/** 표준 타입이 아직 lib.dom 에 없어 필요한 부분만 선언한다. */
export interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export type InstallMode =
  /** Chromium — 버튼 한 번으로 설치 가능 */
  | { kind: 'prompt'; event: BeforeInstallPromptEvent }
  /** iOS Safari — 수동 안내만 가능 */
  | { kind: 'ios-manual' };

type Listener = (mode: InstallMode | null) => void;

/** 사용자가 "나중에" 를 누른 시점(ms). 이 값이 있으면 유예 기간 동안 다시 띄우지 않는다. */
const DISMISSED_KEY = 'pwa_install_dismissed_at';
/** 한 번 거절하면 2주간 조용히 있는다 — 설치 배너는 반복 노출이 가장 큰 민폐다. */
const SNOOZE_MS = 14 * 24 * 60 * 60 * 1000;

let current: InstallMode | null = null;
const listeners = new Set<Listener>();

const notify = (mode: InstallMode | null) => {
  current = mode;
  listeners.forEach((listener) => listener(mode));
};

/** 이미 설치돼 실행 중인가. 설치형에서 설치 배너를 띄우는 것은 명백한 버그다. */
export const isStandalone = (): boolean =>
  window.matchMedia?.('(display-mode: standalone)')?.matches === true ||
  // iOS Safari 전용 비표준 플래그 — display-mode 를 지원하지 않던 시절의 잔재라 함께 본다.
  (navigator as { standalone?: boolean }).standalone === true;

const isIos = (): boolean => /iphone|ipad|ipod/i.test(navigator.userAgent);

/**
 * 이 사용자가 앱을 실제로 써 본 적이 있는가(로그인 흔적).
 *
 * 로그인 화면에서는 배너를 띄우지 않는다. 두 가지 이유가 있고 둘 다 실측에서 나왔다.
 *  ① **하단 고정 배너가 로그인 화면의 데모 버튼을 덮어 클릭을 막았다.** e2e(mobile-safari)
 *     6건이 이것으로 실패했고, 실패 로그가 가로막은 요소로 이 배너를 지목했다
 *     (`locator.click: Timeout` + intercepting `<div class="pointer-events-auto mb-20 …">`).
 *     테스트만의 문제가 아니라 실제 아이폰 사용자가 로그인 버튼을 못 누르는 상태였다.
 *  ② 아직 써 보지도 않은 사람에게 "설치하세요"를 먼저 들이미는 것은 순서가 뒤집힌 권유다.
 *
 * 판정은 토큰 유무로 한다 — 이 모듈은 라우터·컨텍스트 바깥(main.tsx)에서 돌아 경로를 모른다.
 * 대신 배너는 "로그인해 본 적 있는 사용자의 다음 방문"부터 뜬다. 설치 유도의 성격상 그 시점이
 * 오히려 적절하고, 로그인 동선을 절대 가리지 않는다는 보장이 생긴다.
 */
const hasEngaged = (): boolean => {
  try {
    return !!window.localStorage?.getItem('access_token');
  } catch {
    return false; // 저장소 접근 불가(프라이빗 모드 등)는 "모름" → 띄우지 않는 쪽으로
  }
};

/** 거절 유예가 아직 유효한가. 값이 깨져 있으면 유예 없음으로 본다(사용자에게 유리한 쪽이 아니라 안전한 쪽). */
const isSnoozed = (now: number): boolean => {
  const raw = window.localStorage?.getItem(DISMISSED_KEY);
  if (!raw) return false;
  const at = Number(raw);
  return Number.isFinite(at) && now - at < SNOOZE_MS;
};

/**
 * 설치 가능 상태를 구독한다. 구독 시점에 이미 가능하면 즉시 통지한다
 * (`beforeinstallprompt` 가 컴포넌트 마운트보다 먼저 오는 경쟁을 없앤다).
 */
export const onInstallAvailable = (listener: Listener): (() => void) => {
  listeners.add(listener);
  if (current) listener(current);
  return () => listeners.delete(listener);
};

/** "나중에" — 유예를 기록하고 배너를 내린다. */
export const snoozeInstall = (now: number = Date.now()) => {
  try {
    window.localStorage?.setItem(DISMISSED_KEY, String(now));
  } catch {
    /* 사파리 프라이빗 모드 등 저장 불가 — 배너를 내리는 것까지는 그대로 동작해야 한다. */
  }
  notify(null);
};

/**
 * 설치를 실행한다. 성공/거절 여부와 무관하게 배너는 내린다 —
 * `beforeinstallprompt` 이벤트는 재사용할 수 없어 다시 띄워도 동작하지 않기 때문이다.
 */
export const acceptInstall = async (mode: InstallMode): Promise<'accepted' | 'dismissed'> => {
  if (mode.kind !== 'prompt') return 'dismissed';
  await mode.event.prompt();
  const { outcome } = await mode.event.userChoice;
  if (outcome === 'dismissed') snoozeInstall();
  else notify(null);
  return outcome;
};

/**
 * 감지 시작. 앱 부팅 시 한 번 호출한다.
 *
 * iOS 는 이벤트가 없으므로 "iOS + 설치 안 됨 + 유예 아님" 이면 곧바로 안내 모드로 켠다.
 * 그 외 브라우저는 `beforeinstallprompt` 가 올 때까지 아무것도 하지 않는다 — 설치 조건을
 * 만족하는지(manifest·서비스워커·HTTPS 등) 판단하는 주체는 브라우저이지 우리가 아니다.
 */
export const watchInstallAvailability = (now: number = Date.now()) => {
  if (isStandalone() || isSnoozed(now) || !hasEngaged()) return;

  if (isIos()) {
    notify({ kind: 'ios-manual' });
    return;
  }

  window.addEventListener('beforeinstallprompt', (event) => {
    // 막지 않으면 브라우저 기본 배너가 뜨고 이벤트를 쥘 수 없다.
    event.preventDefault();
    notify({ kind: 'prompt', event: event as BeforeInstallPromptEvent });
  });

  // 설치가 끝나면(우리 버튼이든 브라우저 메뉴든) 배너는 의미가 없다.
  window.addEventListener('appinstalled', () => notify(null));
};
