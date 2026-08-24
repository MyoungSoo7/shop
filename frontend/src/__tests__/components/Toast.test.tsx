import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import Toast, { ToastType } from '@/components/Toast';

describe('Toast', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it.each<ToastType>(['success', 'error', 'warning', 'info'])('%s 타입 메시지를 렌더링한다', (type) => {
    render(<Toast message={`${type} message`} type={type} onClose={vi.fn()} />);

    expect(screen.getByText(`${type} message`)).toBeInTheDocument();
  });

  it('닫기 버튼 클릭 시 onClose를 호출한다', () => {
    const onClose = vi.fn();
    render(<Toast message="닫기 테스트" onClose={onClose} />);

    fireEvent.click(screen.getByRole('button'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('duration 이후 자동으로 닫힌다', () => {
    vi.useFakeTimers();
    const onClose = vi.fn();
    render(<Toast message="자동 닫기" duration={1000} onClose={onClose} />);

    act(() => {
      vi.advanceTimersByTime(999);
    });
    expect(onClose).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('언마운트 시 자동 닫기 타이머를 정리한다', () => {
    vi.useFakeTimers();
    const onClose = vi.fn();
    const { unmount } = render(<Toast message="정리" duration={1000} onClose={onClose} />);

    unmount();
    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(onClose).not.toHaveBeenCalled();
  });
});
