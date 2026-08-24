import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import UpdatePrompt from '@/components/UpdatePrompt';
import { onUpdateReady, applyUpdate } from '@/lib/serviceWorkerUpdate';

vi.mock('@/lib/serviceWorkerUpdate', () => ({
  onUpdateReady: vi.fn(),
  applyUpdate: vi.fn(),
}));

/** 구독 콜백을 붙잡아 테스트가 "새 버전 준비됨" 시점을 직접 만든다. */
let notify: ((sw: ServiceWorker) => void) | null = null;

beforeEach(() => {
  vi.clearAllMocks();
  notify = null;
  vi.mocked(onUpdateReady).mockImplementation((cb: (sw: ServiceWorker) => void) => {
    notify = cb;
    return () => undefined;
  });
});

const fakeWorker = { postMessage: vi.fn() } as unknown as ServiceWorker;

describe('UpdatePrompt', () => {
  it('대기 중인 워커가 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(<UpdatePrompt />);

    expect(container).toBeEmptyDOMElement();
  });

  it('새 버전이 준비되면 배너를 띄운다', async () => {
    render(<UpdatePrompt />);

    await Promise.resolve(notify?.(fakeWorker));

    expect(await screen.findByText('새 버전이 준비됐습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '지금 갱신' })).toBeInTheDocument();
  });

  it('갱신을 누르면 교체를 요청하고 버튼이 잠긴다 — 자동 새로고침은 하지 않는다', async () => {
    render(<UpdatePrompt />);
    await Promise.resolve(notify?.(fakeWorker));

    await userEvent.click(await screen.findByRole('button', { name: '지금 갱신' }));

    expect(applyUpdate).toHaveBeenCalledWith(fakeWorker);
    expect(await screen.findByRole('button', { name: '갱신 중…' })).toBeDisabled();
  });

  it('나중에를 누르면 배너가 사라진다', async () => {
    render(<UpdatePrompt />);
    await Promise.resolve(notify?.(fakeWorker));
    expect(await screen.findByText('새 버전이 준비됐습니다.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '나중에' }));

    expect(screen.queryByText('새 버전이 준비됐습니다.')).not.toBeInTheDocument();
    expect(applyUpdate).not.toHaveBeenCalled();
  });
});
