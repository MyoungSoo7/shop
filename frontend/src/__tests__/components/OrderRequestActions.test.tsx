import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OrderRequestActions from '@/components/order/OrderRequestActions';
import { ToastProvider } from '@/contexts/ToastContext';
import { orderWorkflowApi } from '@/api/orderWorkflow';
import { OrderResponse } from '@/types';

vi.mock('@/api/orderWorkflow', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/orderWorkflow')>();
  return {
    ...actual,
    orderWorkflowApi: { requestCancellation: vi.fn(), requestRefund: vi.fn() },
  };
});

const order = (status: string): OrderResponse => ({
  id: 42,
  userId: 7,
  productId: 1,
  amount: 10000,
  status,
  createdAt: '2026-08-09T10:00:00',
  updatedAt: '2026-08-09T10:00:00',
});

const renderWith = (status: string, onUpdated = vi.fn()) => {
  render(
    <ToastProvider>
      <OrderRequestActions order={order(status)} onUpdated={onUpdated} />
    </ToastProvider>
  );
  return onUpdated;
};

describe('OrderRequestActions', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('결제 완료 주문에는 취소·환불 신청이 모두 열린다', () => {
    renderWith('PAID');

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '환불 신청' })).toBeInTheDocument();
  });

  it('결제 전 주문에는 환불 신청이 열리지 않는다', () => {
    renderWith('CREATED');

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '환불 신청' })).not.toBeInTheDocument();
  });

  it('종단 상태에서는 아무 버튼도 렌더링하지 않는다', () => {
    const { container } = render(
      <ToastProvider>
        <OrderRequestActions order={order('REFUNDED')} onUpdated={vi.fn()} />
      </ToastProvider>
    );
    expect(container.querySelectorAll('button')).toHaveLength(0);
  });

  /** 사유가 없으면 승인 화면에서 운영자가 판단할 근거가 사라진다. */
  it('사유가 비어 있으면 신청 버튼이 비활성이다', () => {
    renderWith('PAID');
    fireEvent.click(screen.getByRole('button', { name: '취소 신청' }));

    const submit = screen.getByRole('button', { name: '취소 신청' });
    expect(submit).toBeDisabled();
  });

  it('사유를 적으면 신청이 서버로 전달되고 갱신된 주문이 부모로 올라간다', async () => {
    const updated = { ...order('CANCELLATION_REQUESTED') };
    vi.mocked(orderWorkflowApi.requestCancellation).mockResolvedValue(updated);
    const onUpdated = renderWith('PAID');

    fireEvent.click(screen.getByRole('button', { name: '취소 신청' }));
    fireEvent.change(screen.getByLabelText('취소 사유'), { target: { value: '단순 변심' } });
    fireEvent.click(screen.getByRole('button', { name: '취소 신청' }));

    await waitFor(() =>
      expect(orderWorkflowApi.requestCancellation).toHaveBeenCalledWith(42, '단순 변심')
    );
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(updated));
  });

  it('환불 신청도 같은 방식으로 사유와 함께 간다', async () => {
    vi.mocked(orderWorkflowApi.requestRefund).mockResolvedValue(order('REFUND_REQUESTED'));
    renderWith('DELIVERED');

    fireEvent.click(screen.getByRole('button', { name: '환불 신청' }));
    fireEvent.change(screen.getByLabelText('환불 사유'), { target: { value: '상품 파손' } });
    fireEvent.click(screen.getByRole('button', { name: '환불 신청' }));

    await waitFor(() =>
      expect(orderWorkflowApi.requestRefund).toHaveBeenCalledWith(42, '상품 파손')
    );
  });

  it('앞뒤 공백만 있는 사유는 신청되지 않는다', () => {
    renderWith('PAID');
    fireEvent.click(screen.getByRole('button', { name: '취소 신청' }));
    fireEvent.change(screen.getByLabelText('취소 사유'), { target: { value: '   ' } });

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeDisabled();
    expect(orderWorkflowApi.requestCancellation).not.toHaveBeenCalled();
  });
});
