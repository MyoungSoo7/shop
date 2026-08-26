import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OrderRequestActions from '@/components/order/OrderRequestActions';
import { ToastProvider } from '@/contexts/ToastContext';
import { orderWorkflowApi } from '@/api/orderWorkflow';
import { returnRequestApi, type ReturnRequestResponse } from '@/api/returnRequest';
import { orderApi } from '@/api/order';
import { OrderResponse } from '@/types';

// 서버로 나가는 호출만 가짜로 바꾼다. 노출 조건(canRequest*·hasOpenRequest)과 사유·은행 표는
// 진짜를 쓴다 — 판정까지 가짜로 만들면 "화면이 실제로 그 조건을 붙였는가"를 검사하지 못한다.
vi.mock('@/api/orderWorkflow', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/orderWorkflow')>()),
  orderWorkflowApi: { withdrawRequest: vi.fn() },
}));

vi.mock('@/api/returnRequest', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/returnRequest')>()),
  returnRequestApi: {
    submit: vi.fn(),
    history: vi.fn(),
    registerWaybill: vi.fn(),
    changeRefundAccount: vi.fn(),
    withdraw: vi.fn(),
  },
}));

vi.mock('@/api/order', () => ({ orderApi: { getOrder: vi.fn() } }));

const order = (status: string): OrderResponse => ({
  id: 42,
  userId: 7,
  productId: 1,
  amount: 10000,
  status,
  createdAt: '2026-08-09T10:00:00',
  updatedAt: '2026-08-09T10:00:00',
});

const request = (over: Partial<ReturnRequestResponse> = {}): ReturnRequestResponse => ({
  id: 9,
  orderId: 42,
  userId: 7,
  type: 'RETURN',
  status: 'REQUESTED',
  reasonCode: 'DEFECT',
  reasonDetail: null,
  refundBankCode: null,
  refundAccountNumberMasked: null,
  refundAccountHolder: null,
  awaitsRefundAccount: false,
  returnCarrier: null,
  returnTrackingNumber: null,
  exchangeCarrier: null,
  exchangeTrackingNumber: null,
  requestedBy: 'buyer',
  processedBy: null,
  rejectReason: null,
  requestedAt: '2026-08-09T10:00:00',
  approvedAt: null,
  collectedAt: null,
  exchangeShippedAt: null,
  completedAt: null,
  updatedAt: '2026-08-09T10:00:00',
  ...over,
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
    vi.mocked(returnRequestApi.history).mockResolvedValue([]);
    vi.mocked(orderApi.getOrder).mockImplementation(async () => order('PAID'));
  });

  it('결제 완료 주문에는 취소·반품·교환이 모두 열린다', () => {
    renderWith('PAID');

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '반품 신청' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '교환 신청' })).toBeInTheDocument();
  });

  it('결제 전 주문에는 취소만 열린다', () => {
    renderWith('CREATED');

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반품 신청' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '교환 신청' })).not.toBeInTheDocument();
  });

  /** 취소가 승인된 주문은 돈이 돌아가는 중이라 보낼 물건이 없다. */
  it('취소 승인된 주문에는 반품만 열리고 교환은 닫힌다', () => {
    renderWith('CANCELLATION_APPROVED');

    expect(screen.getByRole('button', { name: '반품 신청' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '교환 신청' })).not.toBeInTheDocument();
  });

  it('종단 상태에서는 아무 버튼도 렌더링하지 않는다', () => {
    const { container } = render(
      <ToastProvider>
        <OrderRequestActions order={order('REFUNDED')} onUpdated={vi.fn()} />
      </ToastProvider>
    );
    expect(container.querySelectorAll('button')).toHaveLength(0);
  });

  it('사유 코드와 상세가 신청 레코드로 간다', async () => {
    vi.mocked(returnRequestApi.submit).mockResolvedValue(request());
    const updated = order('REFUND_REQUESTED');
    vi.mocked(orderApi.getOrder).mockResolvedValue(updated);
    const onUpdated = renderWith('DELIVERED');

    fireEvent.click(screen.getByRole('button', { name: '반품 신청' }));
    fireEvent.change(screen.getByLabelText('반품 사유'), { target: { value: 'DEFECT' } });
    fireEvent.change(screen.getByLabelText('반품 사유 상세'), { target: { value: '모서리가 깨졌어요' } });
    fireEvent.click(screen.getByRole('button', { name: '반품 신청' }));

    await waitFor(() =>
      expect(returnRequestApi.submit).toHaveBeenCalledWith(42, expect.objectContaining({
        type: 'RETURN', reasonCode: 'DEFECT', reasonDetail: '모서리가 깨졌어요',
      }))
    );
    // 신청 응답에는 주문 상태가 없다 — 갱신된 주문은 다시 읽어서 올린다.
    await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(updated));
  });

  it('교환도 같은 레코드로 가고 유형만 다르다', async () => {
    vi.mocked(returnRequestApi.submit).mockResolvedValue(request({ type: 'EXCHANGE' }));
    renderWith('IN_TRANSIT');

    fireEvent.click(screen.getByRole('button', { name: '교환 신청' }));
    fireEvent.click(screen.getByRole('button', { name: '교환 신청' }));

    await waitFor(() =>
      expect(returnRequestApi.submit).toHaveBeenCalledWith(42, expect.objectContaining({ type: 'EXCHANGE' }))
    );
  });

  /** 교환은 돈이 돌아가지 않는다 — 계좌 칸을 보여주면 서버가 거절할 값을 받게 된다. */
  it('교환 신청에는 환불 계좌 칸이 없다', () => {
    renderWith('DELIVERED');
    fireEvent.click(screen.getByRole('button', { name: '교환 신청' }));

    expect(screen.queryByLabelText('환불 계좌번호')).not.toBeInTheDocument();
  });

  /** 코드가 사유를 담고 있으면 자유 입력은 선택이지만, '기타' 는 아무것도 말해 주지 않는다. */
  it("'기타' 를 고르면 상세가 없는 한 신청되지 않는다", () => {
    renderWith('PAID');
    fireEvent.click(screen.getByRole('button', { name: '취소 신청' }));
    fireEvent.change(screen.getByLabelText('취소 사유'), { target: { value: 'OTHER' } });

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeDisabled();

    fireEvent.change(screen.getByLabelText('취소 사유 상세'), { target: { value: '   ' } });
    expect(screen.getByRole('button', { name: '취소 신청' })).toBeDisabled();
  });

  it('사유 코드만으로도 신청할 수 있다', () => {
    renderWith('PAID');
    fireEvent.click(screen.getByRole('button', { name: '취소 신청' }));

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeEnabled();
  });

  describe('신청이 열려 있는 동안', () => {
    it('신청 버튼 대신 철회만 보인다', async () => {
      renderWith('REFUND_REQUESTED');

      await waitFor(() => expect(screen.getByRole('button', { name: '신청 철회' })).toBeInTheDocument());
      expect(screen.queryByRole('button', { name: '반품 신청' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '취소 신청' })).not.toBeInTheDocument();
    });

    it('교환 신청 중에도 철회가 열린다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([request({ type: 'EXCHANGE' })]);
      renderWith('EXCHANGE_REQUESTED');

      await waitFor(() => expect(screen.getByRole('button', { name: '신청 철회' })).toBeInTheDocument());
    });

    it('레코드가 있으면 레코드를 닫으며 철회한다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([request()]);
      const restored = order('IN_TRANSIT');
      vi.mocked(orderApi.getOrder).mockResolvedValue(restored);
      const onUpdated = renderWith('REFUND_REQUESTED');

      await waitFor(() => expect(returnRequestApi.history).toHaveBeenCalledWith(42));
      fireEvent.click(screen.getByRole('button', { name: '신청 철회' }));

      await waitFor(() => expect(returnRequestApi.withdraw).toHaveBeenCalledWith(42, 9));
      expect(orderWorkflowApi.withdrawRequest).not.toHaveBeenCalled();
      // 돌아갈 상태를 화면이 고르지 않는다 — 배송 중이던 주문은 배송 중으로 돌아간다.
      await waitFor(() => expect(onUpdated).toHaveBeenCalledWith(restored));
    });

    /** 이 변경 이전에 신청되어 레코드가 없는 주문이 철회 불가로 묶이면 안 된다. */
    it('레코드가 없는 옛 주문은 옛 경로로 철회한다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([]);
      renderWith('CANCELLATION_REQUESTED');

      await waitFor(() => expect(returnRequestApi.history).toHaveBeenCalledWith(42));
      fireEvent.click(screen.getByRole('button', { name: '신청 철회' }));

      await waitFor(() => expect(orderWorkflowApi.withdrawRequest).toHaveBeenCalledWith(42));
      expect(returnRequestApi.withdraw).not.toHaveBeenCalled();
    });

    /** 계좌를 기다리는 동안 환불은 실행되지 않는다. 말해 주지 않으면 서로를 기다린다. */
    it('계좌가 필요한 신청에는 계좌 등록 칸이 뜬다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([request({ awaitsRefundAccount: true })]);
      vi.mocked(returnRequestApi.changeRefundAccount).mockResolvedValue(
        request({ awaitsRefundAccount: false, refundAccountNumberMasked: '110****6789' })
      );
      renderWith('REFUND_REQUESTED');

      await waitFor(() => expect(screen.getByLabelText('환불 계좌번호')).toBeInTheDocument());
      fireEvent.change(screen.getByLabelText('환불 은행'), { target: { value: '088' } });
      fireEvent.change(screen.getByLabelText('환불 계좌번호'), { target: { value: '110123456789' } });
      fireEvent.change(screen.getByLabelText('예금주'), { target: { value: '홍길동' } });
      fireEvent.click(screen.getByRole('button', { name: '계좌 등록' }));

      await waitFor(() =>
        expect(returnRequestApi.changeRefundAccount).toHaveBeenCalledWith(42, 9, {
          bankCode: '088', accountNumber: '110123456789', holderName: '홍길동',
        })
      );
      // 마스킹된 값만 돌려받아 그대로 보여준다.
      await waitFor(() => expect(screen.getByText(/110\*\*\*\*6789/)).toBeInTheDocument());
    });

    it('계좌가 필요 없으면 계좌 칸을 띄우지 않는다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([request({ awaitsRefundAccount: false })]);
      renderWith('REFUND_REQUESTED');

      await waitFor(() => expect(screen.getByRole('button', { name: '신청 철회' })).toBeInTheDocument());
      expect(screen.queryByLabelText('환불 계좌번호')).not.toBeInTheDocument();
    });

    /** 승인되지 않을 반품이 배송비를 쓰고 되돌아오지 않도록, 권하는 것은 승인 뒤다. */
    it('회수 송장은 승인된 뒤에만 권한다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([request({ status: 'REQUESTED' })]);
      const { unmount } = render(
        <ToastProvider>
          <OrderRequestActions order={order('REFUND_REQUESTED')} onUpdated={vi.fn()} />
        </ToastProvider>
      );
      await waitFor(() => expect(screen.getByRole('button', { name: '신청 철회' })).toBeInTheDocument());
      expect(screen.queryByLabelText('회수 송장번호')).not.toBeInTheDocument();
      unmount();

      vi.mocked(returnRequestApi.history).mockResolvedValue([request({ status: 'APPROVED' })]);
      vi.mocked(returnRequestApi.registerWaybill).mockResolvedValue(
        request({ status: 'APPROVED', returnCarrier: 'CJ', returnTrackingNumber: '123456789012' })
      );
      renderWith('REFUND_REQUESTED');

      await waitFor(() => expect(screen.getByLabelText('회수 송장번호')).toBeInTheDocument());
      fireEvent.change(screen.getByLabelText('회수 택배사'), { target: { value: 'CJ' } });
      fireEvent.change(screen.getByLabelText('회수 송장번호'), { target: { value: '123456789012' } });
      fireEvent.click(screen.getByRole('button', { name: '송장 등록' }));

      await waitFor(() =>
        expect(returnRequestApi.registerWaybill).toHaveBeenCalledWith(42, 9, {
          carrier: 'CJ', trackingNumber: '123456789012',
        })
      );
    });

    /** 취소는 물건이 나가지 않았다 — 돌려보낼 것이 없는데 송장을 물으면 신청이 멈춘다. */
    it('취소 신청에는 회수 송장을 묻지 않는다', async () => {
      vi.mocked(returnRequestApi.history).mockResolvedValue([
        request({ type: 'CANCEL', status: 'APPROVED' }),
      ]);
      renderWith('CANCELLATION_REQUESTED');

      await waitFor(() => expect(screen.getByRole('button', { name: '신청 철회' })).toBeInTheDocument());
      expect(screen.queryByLabelText('회수 송장번호')).not.toBeInTheDocument();
    });
  });
});
