import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TenderCheckoutPage from '@/pages/TenderCheckoutPage';

vi.mock('@/api/order', () => ({ orderApi: { getUserOrders: vi.fn() } }));
vi.mock('@/api/point', () => ({ pointApi: { myBalance: vi.fn() } }));
vi.mock('@/api/giftCard', () => ({ giftCardApi: { myBalance: vi.fn() } }));
vi.mock('@/api/tenderPayment', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/tenderPayment')>()),
  tenderPaymentApi: { create: vi.fn(), confirmDeposit: vi.fn() },
}));

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

const { orderApi } = await import('@/api/order');
const { pointApi } = await import('@/api/point');
const { giftCardApi } = await import('@/api/giftCard');
const { tenderPaymentApi } = await import('@/api/tenderPayment');

const order = (id: number, amount: number, status = 'CREATED') => ({
  id, userId: 7, productId: 1, amount, status,
  createdAt: '2026-08-22T10:00:00', updatedAt: '2026-08-22T10:00:00',
});

const paymentView = (status: string) => ({
  payment: { id: 55, orderId: 10, amount: 10000, refundedAmount: 0, status, paymentMethod: 'SPLIT:CARD', isSplit: true },
  tenders: [{ id: 1, type: 'POINT' as const, amount: 10000, refundedAmount: 0, refundableAmount: 10000, pgTransactionId: null, status: 'CAPTURED', sequence: 1 }],
});

/**
 * 나눠 결제 화면 — 서버 불변식을 화면이 먼저 지키는지 본다.
 *
 * 서버는 합계 불일치·잔액 초과를 거절하지만, 그걸 누른 뒤에 알게 되면 사용자는 무엇을 고쳐야
 * 할지 모른 채 실패 문구만 본다. 화면이 같은 규칙을 먼저 적용하는 것이 이 테스트의 대상이다.
 */
describe('TenderCheckoutPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuth.userId = 7;
    mockAuth.loading = false;
    vi.mocked(pointApi.myBalance).mockResolvedValue({ userId: 7, available: 6000 } as never);
    vi.mocked(giftCardApi.myBalance).mockResolvedValue({ userId: 7, available: 3000 } as never);
    vi.mocked(orderApi.getUserOrders).mockResolvedValue([order(10, 10000)] as never);
  });

  const pickOrder = async () => {
    const user = userEvent.setup();
    render(<TenderCheckoutPage />);
    await screen.findByText('주문 #10 · 10,000원');
    await user.click(screen.getByRole('button', { name: '선택' }));
    return user;
  };

  it('결제 대기 주문만 보여 준다 — 이미 결제된 주문은 후보가 아니다', async () => {
    vi.mocked(orderApi.getUserOrders).mockResolvedValue([order(10, 10000), order(11, 5000, 'PAID')] as never);

    render(<TenderCheckoutPage />);

    await screen.findByText('주문 #10 · 10,000원');
    expect(screen.queryByText('주문 #11 · 5,000원')).not.toBeInTheDocument();
  });

  it('잔액을 합쳐 보여 주지 않는다 — 포인트와 상품권은 다른 계정이다', async () => {
    render(<TenderCheckoutPage />);

    expect(await screen.findByTestId('point-available')).toHaveTextContent('6,000원');
    expect(screen.getByTestId('giftcard-available')).toHaveTextContent('3,000원');
  });

  it('합계가 주문 금액과 다르면 결제할 수 없다', async () => {
    const user = await pickOrder();

    await user.type(screen.getByLabelText('포인트 금액'), '6000');

    expect(screen.getByTestId('remaining')).toHaveTextContent('4,000원 부족');
    expect(screen.getByRole('button', { name: '결제하기' })).toBeDisabled();
  });

  it('잔액을 넘겨 배분하면 결제할 수 없다 — 서버가 거절하기 전에 막는다', async () => {
    const user = await pickOrder();

    await user.type(screen.getByLabelText('포인트 금액'), '10000');

    expect(screen.getByTestId('remaining')).toHaveTextContent('주문 금액과 일치합니다');
    expect(screen.getByText(/잔액을 넘었습니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '결제하기' })).toBeDisabled();
  });

  it('포인트만으로 전액 결제할 수 있다 — 이 경로가 이 화면의 이유다', async () => {
    vi.mocked(orderApi.getUserOrders).mockResolvedValue([order(10, 6000)] as never);
    vi.mocked(tenderPaymentApi.create).mockResolvedValue(paymentView('CAPTURED') as never);
    const user = userEvent.setup();
    render(<TenderCheckoutPage />);
    await screen.findByText('주문 #10 · 6,000원');
    await user.click(screen.getByRole('button', { name: '선택' }));

    await user.type(screen.getByLabelText('포인트 금액'), '6000');
    await user.click(screen.getByRole('button', { name: '결제하기' }));

    await waitFor(() => expect(tenderPaymentApi.create).toHaveBeenCalledWith(10, [{ type: 'POINT', amount: 6000 }]));
    expect(await screen.findByTestId('payment-captured')).toBeInTheDocument();
  });

  /** 누른 뒤에 알면 "왜 주문이 아직 미결제지"가 된다. */
  it('가상계좌가 섞이면 누르기 전에 입금 대기임을 알린다', async () => {
    const user = await pickOrder();

    await user.type(screen.getByLabelText('포인트 금액'), '6000');
    await user.type(screen.getByLabelText('가상계좌 금액'), '4000');

    expect(screen.getByTestId('awaits-deposit-notice')).toHaveTextContent('지금 결제가 확정되지 않습니다');
    expect(screen.getByRole('button', { name: '결제 신청' })).toBeEnabled();
  });

  it('입금 대기로 생성되면 확정 버튼을 주고, 확인 후 완료로 바뀐다', async () => {
    vi.mocked(tenderPaymentApi.create).mockResolvedValue(paymentView('READY') as never);
    vi.mocked(tenderPaymentApi.confirmDeposit).mockResolvedValue(paymentView('CAPTURED') as never);
    const user = await pickOrder();

    await user.type(screen.getByLabelText('포인트 금액'), '6000');
    await user.type(screen.getByLabelText('가상계좌 금액'), '4000');
    await user.click(screen.getByRole('button', { name: '결제 신청' }));

    expect(await screen.findByTestId('payment-pending')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '입금 확인 처리' }));

    await waitFor(() => expect(tenderPaymentApi.confirmDeposit).toHaveBeenCalledWith(55));
    expect(await screen.findByTestId('payment-captured')).toBeInTheDocument();
  });

  it('결제 대기 주문이 없으면 그 사실을 말한다', async () => {
    vi.mocked(orderApi.getUserOrders).mockResolvedValue([] as never);

    render(<TenderCheckoutPage />);

    expect(await screen.findByTestId('no-payable-order')).toBeInTheDocument();
  });

  it('로그인하지 않았으면 잔액을 조회하지 않는다 — 남의 잔액을 물을 수 없다', async () => {
    mockAuth.userId = null;

    render(<TenderCheckoutPage />);

    await screen.findByText('로그인해야 결제할 수 있습니다.');
    expect(pointApi.myBalance).not.toHaveBeenCalled();
    expect(orderApi.getUserOrders).not.toHaveBeenCalled();
  });
});
