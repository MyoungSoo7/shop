import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ToastProvider } from '@/contexts/ToastContext';
import { useToast } from '@/contexts/useToast';

const Consumer = () => {
  const { showToast } = useToast();
  return (
    <button onClick={() => showToast('컨텍스트 토스트', 'success')}>
      show
    </button>
  );
};

describe('ToastContext', () => {
  it('Provider 내부에서 토스트를 표시하고 닫을 수 있다', () => {
    render(
      <ToastProvider>
        <Consumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByRole('button', { name: 'show' }));
    expect(screen.getByText('컨텍스트 토스트')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button')[1]);
    expect(screen.queryByText('컨텍스트 토스트')).not.toBeInTheDocument();
  });

  it('Provider 밖에서 useToast를 사용하면 예외가 발생한다', () => {
    const BrokenConsumer = () => {
      useToast();
      return null;
    };

    expect(() => render(<BrokenConsumer />)).toThrow('useToast must be used within ToastProvider');
  });

  it('기본 타입 토스트도 자동으로 닫힌다', () => {
    vi.useFakeTimers();
    render(
      <ToastProvider>
        <Consumer />
      </ToastProvider>
    );

    fireEvent.click(screen.getByRole('button', { name: 'show' }));
    expect(screen.getByText('컨텍스트 토스트')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.queryByText('컨텍스트 토스트')).not.toBeInTheDocument();
    vi.useRealTimers();
  });
});
