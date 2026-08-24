import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import TossPaymentSuccess from '@/pages/TossPaymentSuccess';
import OrderApprovalPage from '@/pages/OrderApprovalPage';
import { paymentApi } from '@/api/payment';
import { adminApi } from '@/api/admin';
import { orderWorkflowApi } from '@/api/orderWorkflow';

const navigate = vi.fn();
const showToast = vi.fn();

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigate };
});

vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/payment', () => ({
  paymentApi: { confirmTossPayment: vi.fn(), confirmTossCartPayment: vi.fn() },
}));
vi.mock('@/api/admin', () => ({ adminApi: { getAllOrders: vi.fn() } }));
vi.mock('@/api/orderWorkflow', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/orderWorkflow')>();
  return {
    ...actual,
    orderWorkflowApi: { approveCancellation: vi.fn(), approveRefund: vi.fn() },
  };
});

const mockedPayment = vi.mocked(paymentApi);
const mockedAdmin = vi.mocked(adminApi);
const mockedWorkflow = vi.mocked(orderWorkflowApi);

const payment = (over: Record<string, unknown> = {}) =>
  ({ id: 500, orderId: 100, amount: 20000, paymentMethod: 'TOSS_PAYMENTS', status: 'CAPTURED', ...over }) as never;

const order = (over: Record<string, unknown> = {}) =>
  ({
    id: 100,
    userId: 7,
    amount: 20000,
    status: 'CANCELLATION_REQUESTED',
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

const renderSuccess = (query: string) =>
  render(
    <MemoryRouter initialEntries={[`/order/toss/success${query}`]}>
      <TossPaymentSuccess />
    </MemoryRouter>,
  );

describe('TossPaymentSuccess', () => {
  it('필수 쿼리가 없으면 확인을 시도하지 않고 오류를 보여 준다', async () => {
    renderSuccess('');

    expect(await screen.findByText('결제 확인 실패')).toBeInTheDocument();
    expect(mockedPayment.confirmTossPayment).not.toHaveBeenCalled();
  });

  it('주문 식별자가 없으면 결제 정보 오류로 처리한다', async () => {
    renderSuccess('?paymentKey=pk&orderId=T-1&amount=20000');

    expect(await screen.findByText('결제 확인 실패')).toBeInTheDocument();
    expect(screen.getByText('결제 정보가 올바르지 않습니다.')).toBeInTheDocument();
  });

  it('단건 결제는 dbOrderId 로 확인한다', async () => {
    mockedPayment.confirmTossPayment.mockResolvedValue(payment());
    renderSuccess('?paymentKey=pk&orderId=T-1&amount=20000&dbOrderId=100');

    expect(await screen.findByText('결제 완료!')).toBeInTheDocument();
    expect(mockedPayment.confirmTossPayment).toHaveBeenCalledWith({
      dbOrderId: 100,
      paymentKey: 'pk',
      tossOrderId: 'T-1',
      amount: 20000,
    });
  });

  it('장바구니 결제는 주문 ID 목록을 숫자로 바꿔 일괄 확인한다', async () => {
    mockedPayment.confirmTossCartPayment.mockResolvedValue([payment(), payment({ id: 501, orderId: 101 })] as never);
    renderSuccess('?paymentKey=pk&orderId=CART-1&amount=40000&type=cart&dbOrderIds=100,101');

    expect(await screen.findByText('결제 완료!')).toBeInTheDocument();
    expect(mockedPayment.confirmTossCartPayment).toHaveBeenCalledWith({
      orderIds: [100, 101],
      paymentKey: 'pk',
      tossOrderId: 'CART-1',
      totalAmount: 40000,
    });
  });

  it('확인이 실패하면 사유와 함께 돌아가기 버튼을 준다', async () => {
    mockedPayment.confirmTossPayment.mockRejectedValue({
      response: { data: { message: '금액이 일치하지 않습니다' } },
    });
    renderSuccess('?paymentKey=pk&orderId=T-1&amount=20000&dbOrderId=100');

    expect(await screen.findByText('금액이 일치하지 않습니다')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '주문 페이지로 돌아가기' }));
    expect(navigate).toHaveBeenCalledWith('/order');
  });

  it('성공 후 카운트다운이 끝나면 마이페이지로 자동 이동한다', async () => {
    mockedPayment.confirmTossPayment.mockResolvedValue(payment());
    renderSuccess('?paymentKey=pk&orderId=T-1&amount=20000&dbOrderId=100');
    await screen.findByText('결제 완료!');

    await vi.advanceTimersByTimeAsync(3000);

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/mypage'));
  });
});

describe('OrderApprovalPage', () => {
  const renderPage = () => render(<OrderApprovalPage />);

  it('승인 대기 상태(취소·환불 신청)만 골라 보여 준다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([
      order({ id: 100, status: 'CANCELLATION_REQUESTED' }),
      order({ id: 101, status: 'PAID' }),
      order({ id: 102, status: 'REFUND_REQUESTED' }),
    ] as never);
    renderPage();

    expect(await screen.findByText('2건 대기 중')).toBeInTheDocument();
    expect(screen.getByText('주문 #100')).toBeInTheDocument();
    expect(screen.queryByText('주문 #101')).not.toBeInTheDocument();
  });

  it('대기 건이 없으면 그 사실을 알린다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([order({ status: 'PAID' })] as never);
    renderPage();

    expect(await screen.findByText('승인 대기 중인 취소·환불 신청이 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다', async () => {
    mockedAdmin.getAllOrders.mockRejectedValue({ response: { data: { message: '권한 없음' } } });
    renderPage();

    expect(await screen.findByText('권한 없음')).toBeInTheDocument();
  });

  it('새로고침은 목록을 다시 읽는다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([] as never);
    renderPage();
    await screen.findByText('승인 대기 중인 취소·환불 신청이 없습니다.');

    await userEvent.click(screen.getByRole('button', { name: '새로고침' }));

    await waitFor(() => expect(mockedAdmin.getAllOrders).toHaveBeenCalledTimes(2));
  });

  it('취소 신청은 취소 승인으로, 메모가 비면 기본 문구를 남긴다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([order({ status: 'CANCELLATION_REQUESTED' })] as never);
    mockedWorkflow.approveCancellation.mockResolvedValue(order({ status: 'CANCELED' }));
    renderPage();
    await screen.findByText('주문 #100');

    await userEvent.click(screen.getByRole('button', { name: '취소 승인' }));

    await waitFor(() =>
      expect(mockedWorkflow.approveCancellation).toHaveBeenCalledWith(100, '운영자 승인'),
    );
    expect(showToast).toHaveBeenCalledWith('취소를 승인했습니다.', 'success');
  });

  it('환불 신청은 환불 승인으로 가고 입력한 메모를 그대로 보낸다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([order({ status: 'REFUND_REQUESTED' })] as never);
    mockedWorkflow.approveRefund.mockResolvedValue(order({ status: 'REFUNDED' }));
    renderPage();
    await screen.findByText('주문 #100');

    await userEvent.type(screen.getByPlaceholderText('승인 메모 (선택)'), '고객 요청');
    await userEvent.click(screen.getByRole('button', { name: '환불 승인' }));

    await waitFor(() => expect(mockedWorkflow.approveRefund).toHaveBeenCalledWith(100, '고객 요청'));
    expect(showToast).toHaveBeenCalledWith('환불을 승인했습니다.', 'success');
  });

  it('승인 실패는 토스트로 사유를 알린다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([order({ status: 'CANCELLATION_REQUESTED' })] as never);
    mockedWorkflow.approveCancellation.mockRejectedValue({
      response: { data: { message: '이미 처리된 신청' } },
    });
    renderPage();
    await screen.findByText('주문 #100');

    await userEvent.click(screen.getByRole('button', { name: '취소 승인' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('이미 처리된 신청', 'error'));
  });

  it('승인되면 그 건이 대기 목록에서 빠진다', async () => {
    mockedAdmin.getAllOrders.mockResolvedValue([order({ status: 'CANCELLATION_REQUESTED' })] as never);
    mockedWorkflow.approveCancellation.mockResolvedValue(order({ status: 'CANCELED' }));
    renderPage();
    await screen.findByText('주문 #100');

    await userEvent.click(screen.getByRole('button', { name: '취소 승인' }));

    expect(await screen.findByText('승인 대기 중인 취소·환불 신청이 없습니다.')).toBeInTheDocument();
  });
});
