import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CartPage from '@/pages/CartPage';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { couponApi } from '@/api/coupon';
import type { CartItem } from '@/contexts/useCart';

const removeItem = vi.fn();
const updateQuantity = vi.fn();
const clearCart = vi.fn();

let cartItems: CartItem[] = [];

vi.mock('@/contexts/useCart', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/contexts/useCart')>();
  return {
    ...actual,
    useCart: () => ({
      items: cartItems,
      addItem: vi.fn(),
      removeItem,
      updateQuantity,
      clearCart,
      totalAmount: cartItems.reduce((s, i) => s + i.product.price * i.quantity, 0),
      totalCount: cartItems.reduce((s, i) => s + i.quantity, 0),
      loading: false,
      syncing: false,
      serverBacked: false,
    }),
  };
});

vi.mock('@/api/order', () => ({ orderApi: { createOrder: vi.fn() } }));
vi.mock('@/api/payment', () => ({
  paymentApi: { createPayment: vi.fn(), authorizePayment: vi.fn(), capturePayment: vi.fn() },
}));
vi.mock('@/api/coupon', () => ({ couponApi: { validate: vi.fn(), use: vi.fn() } }));

const mockedOrder = vi.mocked(orderApi);
const mockedPayment = vi.mocked(paymentApi);
const mockedCoupon = vi.mocked(couponApi);

const item = (over: Record<string, unknown> = {}, quantity = 1): CartItem =>
  ({
    product: {
      id: 1,
      name: '티셔츠',
      description: '면 100%',
      price: 20000,
      stockQuantity: 5,
      status: 'ACTIVE',
      availableForSale: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      primaryImageUrl: undefined,
      ...over,
    },
    quantity,
  }) as CartItem;

const renderPage = () => render(<MemoryRouter><CartPage /></MemoryRouter>);

beforeEach(() => {
  vi.clearAllMocks();
  cartItems = [item()];
  mockedOrder.createOrder.mockImplementation((req) =>
    Promise.resolve({ id: 100, userId: 1, amount: (req as { amount: number }).amount, status: 'CREATED' }) as never,
  );
  mockedPayment.createPayment.mockResolvedValue({ id: 500, amount: 20000, status: 'READY' } as never);
  mockedPayment.authorizePayment.mockResolvedValue({ id: 500, status: 'AUTHORIZED' } as never);
  mockedPayment.capturePayment.mockResolvedValue({ id: 500, status: 'CAPTURED' } as never);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('CartPage — 목록', () => {
  it('비어 있으면 상품 보러 가기를 안내한다', () => {
    cartItems = [];
    renderPage();

    expect(screen.getByText('장바구니가 비어있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '상품 보러 가기' })).toBeInTheDocument();
  });

  it('담긴 상품과 합계를 보여 준다', () => {
    cartItems = [item({}, 2)];
    renderPage();

    expect(screen.getByText('티셔츠')).toBeInTheDocument();
    expect(screen.getByText('총 2개 상품')).toBeInTheDocument();
    expect(screen.getByText('2개')).toBeInTheDocument();
  });

  it('수량 증감과 삭제가 컨텍스트로 전달된다', async () => {
    cartItems = [item({}, 2)];
    renderPage();
    const row = screen.getByText('티셔츠').closest('div')!.parentElement as HTMLElement;
    const buttons = within(row).getAllByRole('button');

    await userEvent.click(buttons[0]); // 감소
    expect(updateQuantity).toHaveBeenCalledWith(1, 1);

    await userEvent.click(buttons[1]); // 증가
    expect(updateQuantity).toHaveBeenCalledWith(1, 3);

    await userEvent.click(buttons[2]); // 삭제
    expect(removeItem).toHaveBeenCalledWith(1);
  });

  it('재고까지 담았으면 증가 버튼이 잠긴다', () => {
    cartItems = [item({ stockQuantity: 2 }, 2)];
    renderPage();
    const row = screen.getByText('티셔츠').closest('div')!.parentElement as HTMLElement;

    expect(within(row).getAllByRole('button')[1]).toBeDisabled();
  });

  it('전체 삭제를 누르면 카트를 비운다', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '전체 삭제' }));

    expect(clearCart).toHaveBeenCalled();
  });
});

describe('CartPage — 일반 결제', () => {
  it('상품마다 주문→결제→승인→확정을 순서대로 처리하고 카트를 비운다', async () => {
    cartItems = [item({ id: 1, name: '티셔츠' }), item({ id: 2, name: '바지', price: 30000 })];
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '2개 상품 전체 주문하기' }));

    expect(await screen.findByText('전체 주문 완료!')).toBeInTheDocument();
    expect(mockedOrder.createOrder).toHaveBeenCalledTimes(2);
    expect(mockedPayment.capturePayment).toHaveBeenCalledTimes(2);
    expect(clearCart).toHaveBeenCalled();
  });

  it('중간에 실패하면 성공분만 남기고 실패 사유를 보여 준다', async () => {
    cartItems = [item({ id: 1, name: '티셔츠' }), item({ id: 2, name: '바지' })];
    mockedOrder.createOrder
      .mockResolvedValueOnce({ id: 100, amount: 20000, status: 'CREATED' } as never)
      .mockRejectedValueOnce({ response: { data: { message: '재고 부족' } } });
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '2개 상품 전체 주문하기' }));

    expect(await screen.findByText('1/2개 완료')).toBeInTheDocument();
    expect(screen.getByText('"바지" 주문 실패: 재고 부족')).toBeInTheDocument();
    expect(clearCart).not.toHaveBeenCalled();
  });

  it('첫 상품부터 실패하면 주문 실패로 표시한다', async () => {
    mockedOrder.createOrder.mockRejectedValue(new Error('네트워크'));
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    expect(await screen.findByText('주문 실패')).toBeInTheDocument();
  });

  it('쿠폰을 적용하면 할인가로 결제하고 사용 기록을 남긴다', async () => {
    mockedCoupon.validate.mockResolvedValue({
      valid: true,
      discountAmount: 2000,
      finalAmount: 18000,
      message: null,
    } as never);
    mockedCoupon.use.mockResolvedValue(undefined as never);
    renderPage();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    await waitFor(() =>
      expect(mockedOrder.createOrder).toHaveBeenCalledWith({
        userId: 1,
        productId: 1,
        amount: 18000,
      }),
    );
    expect(mockedCoupon.use).toHaveBeenCalledWith('WELCOME10', 1, 100);
  });

  it('쿠폰 사용 기록 실패는 주문 결과를 뒤집지 않는다', async () => {
    mockedCoupon.validate.mockResolvedValue({
      valid: true,
      discountAmount: 2000,
      finalAmount: 18000,
      message: null,
    } as never);
    mockedCoupon.use.mockRejectedValue(new Error('down'));
    renderPage();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    expect(await screen.findByText('전체 주문 완료!')).toBeInTheDocument();
  });
});

describe('CartPage — 토스페이먼츠', () => {
  const chooseToss = async () => {
    renderPage();
    await userEvent.selectOptions(screen.getByRole('combobox'), 'TOSS_PAYMENTS');
  };

  it('결제 수단을 토스로 바꾸면 버튼 문구가 금액을 포함해 바뀐다', async () => {
    await chooseToss();

    expect(screen.getByRole('button', { name: /토스페이먼츠로 ₩20,000 결제/ })).toBeInTheDocument();
  });

  it('주문을 먼저 만들고 결제창을 연다 (성공 URL 에 주문 ID 를 싣는다)', async () => {
    const requestPayment = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    await waitFor(() => expect(requestPayment).toHaveBeenCalled());
    const [, options] = requestPayment.mock.calls[0];
    expect(options.amount).toBe(20000);
    expect(options.successUrl).toContain('type=cart');
    expect(options.successUrl).toContain('dbOrderIds=100');
  });

  it('여러 상품이면 주문명을 "외 N개"로 요약한다', async () => {
    cartItems = [item({ id: 1, name: '티셔츠' }), item({ id: 2, name: '바지' })];
    const requestPayment = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    await waitFor(() => expect(requestPayment).toHaveBeenCalled());
    expect(requestPayment.mock.calls[0][1].orderName).toBe('티셔츠 외 1개');
  });

  it('주문 생성이 실패하면 결제창을 열지 않고 장바구니로 돌아온다', async () => {
    mockedOrder.createOrder.mockRejectedValue({ response: { data: { message: '품절' } } });
    const requestPayment = vi.fn();
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    expect(await screen.findByText('"티셔츠" 주문 생성 실패: 품절')).toBeInTheDocument();
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it('결제창을 열지 못하면 사유를 남기고 장바구니로 돌아온다', async () => {
    vi.stubGlobal('TossPayments', vi.fn(() => ({
      requestPayment: vi.fn().mockRejectedValue(new Error('사용자가 취소했습니다')),
    })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    expect(await screen.findByText('사용자가 취소했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /토스페이먼츠로/ })).toBeInTheDocument();
  });
});
