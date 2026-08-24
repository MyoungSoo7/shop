import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import InstallPrompt from '@/components/InstallPrompt';
import { onInstallAvailable, acceptInstall, snoozeInstall, type InstallMode } from '@/lib/installPrompt';

vi.mock('@/lib/installPrompt', () => ({
  onInstallAvailable: vi.fn(),
  acceptInstall: vi.fn(),
  snoozeInstall: vi.fn(),
}));

let notify: ((m: InstallMode) => void) | null = null;

const promptMode = { kind: 'prompt', event: {} } as unknown as InstallMode;
const iosMode = { kind: 'ios' } as unknown as InstallMode;

beforeEach(() => {
  vi.clearAllMocks();
  notify = null;
  document.body.classList.remove('pwa-banner-open');
  vi.mocked(onInstallAvailable).mockImplementation((cb: (m: InstallMode) => void) => {
    notify = cb;
    return () => undefined;
  });
});

describe('InstallPrompt', () => {
  it('설치 가능 신호가 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(<InstallPrompt />);

    expect(container).toBeEmptyDOMElement();
    expect(document.body.classList.contains('pwa-banner-open')).toBe(false);
  });

  it('prompt 모드면 설치 버튼을 보여 준다', () => {
    render(<InstallPrompt />);

    act(() => notify?.(promptMode));

    expect(screen.getByText('앱으로 설치하기')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '설치' })).toBeInTheDocument();
  });

  it('배너가 뜨면 body 에 여백 클래스를 붙인다 (하단 버튼을 가리지 않게)', () => {
    render(<InstallPrompt />);

    act(() => notify?.(promptMode));

    expect(document.body.classList.contains('pwa-banner-open')).toBe(true);
  });

  it('언마운트하면 여백 클래스를 걷어낸다', () => {
    const { unmount } = render(<InstallPrompt />);
    act(() => notify?.(promptMode));

    unmount();

    expect(document.body.classList.contains('pwa-banner-open')).toBe(false);
  });

  it('설치를 누르면 acceptInstall 을 호출한다', async () => {
    vi.mocked(acceptInstall).mockResolvedValueOnce(undefined as never);
    render(<InstallPrompt />);
    act(() => notify?.(promptMode));

    await userEvent.click(screen.getByRole('button', { name: '설치' }));

    expect(acceptInstall).toHaveBeenCalledWith(promptMode);
  });

  it('iOS 모드는 실행 버튼 없이 경로만 안내한다', () => {
    render(<InstallPrompt />);

    act(() => notify?.(iosMode));

    expect(screen.getByText('홈 화면에 추가하기')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '설치' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '나중에' })).toBeInTheDocument();
  });

  it('나중에를 누르면 스누즈한다', async () => {
    render(<InstallPrompt />);
    act(() => notify?.(promptMode));

    await userEvent.click(screen.getByRole('button', { name: '나중에' }));

    expect(snoozeInstall).toHaveBeenCalledTimes(1);
  });
});
