import { isNativePlatform } from './platform';

export const normalizeAppUrl = (url: string): string => {
  try {
    const parsed = new URL(url);
    const path = parsed.pathname || `/${parsed.hostname}`;
    return `${path}${parsed.search}${parsed.hash}`;
  } catch {
    return url.startsWith('/') ? url : `/${url}`;
  }
};

export const initializeNativeLifecycle = async (): Promise<void> => {
  if (!isNativePlatform()) return;

  const [{ App }, { Keyboard, KeyboardResize }, { StatusBar, Style }] = await Promise.all([
    import('@capacitor/app'),
    import('@capacitor/keyboard'),
    import('@capacitor/status-bar'),
  ]);

  await Promise.all([
    Keyboard.setResizeMode({ mode: KeyboardResize.Native }),
    StatusBar.setStyle({ style: Style.Default }),
  ]);

  const handleUrl = (url: string) => {
    const path = normalizeAppUrl(url);
    if (path.startsWith('/payment/') || path.startsWith('/reset')) {
      window.history.pushState({}, '', path);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }
  };

  App.addListener('appUrlOpen', ({ url }) => handleUrl(url));
  const launchUrl = await App.getLaunchUrl();
  if (launchUrl?.url) handleUrl(launchUrl.url);
};
