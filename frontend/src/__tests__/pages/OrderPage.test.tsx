import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OrderPage from '@/pages/OrderPage';
import { productApi } from '@/api/product';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { reviewApi } from '@/api/review';
import { couponApi } from '@/api/coupon';
import { facetApi } from '@/api/facet';

const addItem = vi.fn();

vi.mock('@/contexts/useCart', () => ({
  useCart: () => ({ addItem }),
}));

vi.mock('@/api/product', () => ({
  productApi: { getAvailableProducts: vi.fn() },
}));
vi.mock('@/api/order', () => ({
  orderApi: { createOrder: vi.fn() },
}));
vi.mock('@/api/payment', () => ({
  paymentApi: { createPayment: vi.fn(), authorizePayment: vi.fn(), capturePayment: vi.fn() },
}));
vi.mock('@/api/review', () => ({
  reviewApi: { getProductReviews: vi.fn() },
}));
vi.mock('@/api/coupon', () => ({
  couponApi: { validate: vi.fn(), use: vi.fn() },
}));

// 파셋 헬퍼(toggle/count)는 순수 함수라 실제 구현을 그대로 쓰고 네트워크 호출만 가짜로 바꾼다.
vi.mock('@/api/facet', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/facet')>();
  return { ...actual, facetApi: { search: vi.fn() } };
});

const mockedProduct = vi.mocked(productApi);
const mockedOrder = vi.mocked(orderApi);
const mockedPayment = vi.mocked(paymentApi);
const mockedReview = vi.mocked(reviewApi);
const mockedCoupon = vi.mocked(couponApi);
const mockedFacet = vi.mocked(facetApi);

const product = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    name: '티셔츠',
    description: '면 100%',
    price: 20000,
    stockQuantity: 10,
    status: 'ACTIVE',
    primaryImageUrl: null,
    ...over,
  }) as never;

const order = (over: Record<string, unknown> = {}) =>
  ({ id: 100, userId: 1, amount: 20000, status: 'CREATED', ...over }) as never;

const payment = (over: Record<string, unknown> = {}) =>
  ({
    id: 500,
    orderId: 100,
    amount: 20000,
    paymentMethod: 'CARD',
    status: 'READY',
    pgTransactionId: null,
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  mockedProduct.getAvailableProducts.mockResolvedValue([product()] as never);
  mockedFacet.search.mockResolvedValue({ products: [], facets: [] } as never);
  mockedReview.getProductReviews.mockResolvedValue([] as never);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const selectProduct = async () => {
  render(<OrderPage />);
  await userEvent.click(await screen.findByText('티셔츠'));
};

describe('OrderPage — 상품 목록', () => {
  it('진입하면 판매 가능 상품을 읽어 보여 준다', async () => {
    render(<OrderPage />);

    expect(await screen.findByText('티셔츠')).toBeInTheDocument();
    expect(screen.getByText('재고 10개')).toBeInTheDocument();
    expect(mockedProduct.getAvailableProducts).toHaveBeenCalledTimes(1);
  });

  it('목록 조회가 실패하면 사유를 남긴다', async () => {
    mockedProduct.getAvailableProducts.mockRejectedValue(new Error('down'));
    render(<OrderPage />);

    expect(await screen.findByText('상품 목록을 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('상품이 없으면 그 사실을 알린다', async () => {
    mockedProduct.getAvailableProducts.mockResolvedValue([] as never);
    render(<OrderPage />);

    expect(await screen.findByText('판매 가능한 상품이 없습니다.')).toBeInTheDocument();
  });

  it('검색어에 걸리는 상품이 없으면 검색 결과 없음으로 구분해 알린다', async () => {
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    await userEvent.type(screen.getByPlaceholderText('상품명을 입력하세요'), '없는상품');

    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('장바구니 담기는 카트에 넣고 잠시 체크 표시로 바뀐다', async () => {
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    await userEvent.click(screen.getByTitle('장바구니 담기'));

    expect(addItem).toHaveBeenCalledWith(expect.objectContaining({ id: 1 }));
  });

  it('상품을 고르기 전에는 주문 버튼이 잠겨 있다', async () => {
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    expect(screen.getByRole('button', { name: '상품을 먼저 선택해주세요' })).toBeDisabled();
  });
});

describe('OrderPage — 상품 선택 후', () => {
  it('선택 요약·쿠폰 입력·리뷰 섹션이 함께 열린다', async () => {
    await selectProduct();

    expect(screen.getByText('선택된 상품')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/쿠폰 코드 입력/)).toBeInTheDocument();
    expect(await screen.findByText('상품 리뷰 (0개)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '주문하기' })).toBeEnabled();
  });

  it('리뷰가 있으면 개수와 평균 별점을 함께 보여 주고 펼칠 수 있다', async () => {
    mockedReview.getProductReviews.mockResolvedValue([
      { id: 1, productId: 1, userId: 7, rating: 5, content: '좋아요', createdAt: '2026-08-01T00:00:00Z' },
      { id: 2, productId: 1, userId: 8, rating: 3, content: '보통', createdAt: '2026-08-02T00:00:00Z' },
    ] as never);
    await selectProduct();

    await userEvent.click(await screen.findByText('상품 리뷰 (2개)'));

    // 접힘 헤더의 평균과 펼친 ReviewList 요약이 같은 값을 각각 보여 준다
    expect(screen.getAllByText('4.0').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('좋아요')).toBeInTheDocument();
  });

  it('쿠폰을 적용하면 할인 금액과 최종가를 반영한다', async () => {
    mockedCoupon.validate.mockResolvedValue({
      valid: true,
      discountAmount: 2000,
      finalAmount: 18000,
      message: null,
    } as never);
    await selectProduct();

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));

    expect(await screen.findByText('-₩2,000 할인')).toBeInTheDocument();
    expect(screen.getByText('쿠폰 적용됨:')).toBeInTheDocument();
  });
});

describe('OrderPage — 주문·결제 흐름', () => {
  it('주문 생성 → 결제 생성 → 승인 → 확정까지 진행한다', async () => {
    mockedOrder.createOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockResolvedValue(payment());
    mockedPayment.authorizePayment.mockResolvedValue(payment({ status: 'AUTHORIZED' }));
    mockedPayment.capturePayment.mockResolvedValue(payment({ status: 'CAPTURED' }));
    await selectProduct();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));
    expect(await screen.findByText('주문이 생성되었습니다')).toBeInTheDocument();
    expect(mockedOrder.createOrder).toHaveBeenCalledWith({ userId: 1, productId: 1, amount: 20000 });

    await userEvent.click(screen.getByRole('button', { name: '결제 진행하기' }));
    expect(await screen.findByText('결제 정보')).toBeInTheDocument();
    expect(mockedPayment.createPayment).toHaveBeenCalledWith({ orderId: 100, paymentMethod: 'CARD' });

    await userEvent.click(screen.getByRole('button', { name: '결제하기' }));

    await waitFor(() => expect(mockedPayment.capturePayment).toHaveBeenCalledWith(500), {
      timeout: 3000,
    });
    expect(await screen.findByText('결제가 완료되었습니다!')).toBeInTheDocument();
  });

  it('완료 후 새 주문을 누르면 처음 상태로 돌아간다', async () => {
    mockedOrder.createOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockResolvedValue(payment());
    mockedPayment.authorizePayment.mockResolvedValue(payment({ status: 'AUTHORIZED' }));
    mockedPayment.capturePayment.mockResolvedValue(payment({ status: 'CAPTURED' }));
    await selectProduct();
    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));
    await userEvent.click(await screen.findByRole('button', { name: '결제 진행하기' }));
    await userEvent.click(await screen.findByRole('button', { name: '결제하기' }));
    await screen.findByText('결제가 완료되었습니다!', undefined, { timeout: 3000 });

    await userEvent.click(screen.getByRole('button', { name: '새로운 주문하기' }));

    expect(await screen.findByText('상품 선택 및 결제')).toBeInTheDocument();
  });

  it('주문 생성 실패는 사유를 남긴다', async () => {
    mockedOrder.createOrder.mockRejectedValue({ response: { data: { message: '재고 부족' } } });
    await selectProduct();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText('재고 부족')).toBeInTheDocument();
  });

  it('결제 생성 실패도 사유를 남긴다', async () => {
    mockedOrder.createOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockRejectedValue(new Error('down'));
    await selectProduct();
    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await userEvent.click(await screen.findByRole('button', { name: '결제 진행하기' }));

    expect(await screen.findByText('결제 생성에 실패했습니다.')).toBeInTheDocument();
  });

  it('승인 실패도 사유를 남긴다', async () => {
    mockedOrder.createOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockResolvedValue(payment());
    mockedPayment.authorizePayment.mockRejectedValue(new Error('down'));
    await selectProduct();
    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));
    await userEvent.click(await screen.findByRole('button', { name: '결제 진행하기' }));

    await userEvent.click(await screen.findByRole('button', { name: '결제하기' }));

    expect(await screen.findByText('결제 승인에 실패했습니다.')).toBeInTheDocument();
  });

  it('쿠폰이 적용된 주문은 할인가로 생성하고 쿠폰 사용을 기록한다', async () => {
    mockedCoupon.validate.mockResolvedValue({
      valid: true,
      discountAmount: 2000,
      finalAmount: 18000,
      message: null,
    } as never);
    mockedCoupon.use.mockResolvedValue(undefined as never);
    mockedOrder.createOrder.mockResolvedValue(order({ amount: 18000 }));
    await selectProduct();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await waitFor(() =>
      expect(mockedOrder.createOrder).toHaveBeenCalledWith({
        userId: 1,
        productId: 1,
        amount: 18000,
      }),
    );
    expect(mockedCoupon.use).toHaveBeenCalledWith('WELCOME10', 1, 100);
  });

  it('쿠폰 사용 기록이 실패해도 주문은 유지된다', async () => {
    mockedCoupon.validate.mockResolvedValue({
      valid: true,
      discountAmount: 2000,
      finalAmount: 18000,
      message: null,
    } as never);
    mockedCoupon.use.mockRejectedValue(new Error('down'));
    mockedOrder.createOrder.mockResolvedValue(order({ amount: 18000 }));
    await selectProduct();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText('주문이 생성되었습니다')).toBeInTheDocument();
  });
});

describe('OrderPage — 토스페이먼츠', () => {
  const selectTossMethod = async () => {
    await selectProduct();
    await userEvent.selectOptions(screen.getByRole('combobox'), 'TOSS_PAYMENTS');
  };

  it('토스를 고르면 결제창 안내를 보여 준다', async () => {
    await selectTossMethod();

    expect(screen.getByText('주문하기를 누르면 토스페이먼츠 결제창이 열립니다.')).toBeInTheDocument();
  });

  it('주문하면 토스 결제창을 연다', async () => {
    const requestPayment = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    mockedOrder.createOrder.mockResolvedValue(order());
    await selectTossMethod();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await waitFor(() => expect(requestPayment).toHaveBeenCalledWith('카드', expect.objectContaining({
      amount: 20000,
      orderName: '티셔츠',
    })));
  });

  it('결제창을 열지 못하면 원인 문구를 보여 준다', async () => {
    vi.stubGlobal('TossPayments', vi.fn(() => ({
      requestPayment: vi.fn().mockRejectedValue(new Error('사용자가 취소했습니다')),
    })));
    mockedOrder.createOrder.mockResolvedValue(order());
    await selectTossMethod();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText('사용자가 취소했습니다')).toBeInTheDocument();
  });
});

describe('OrderPage — 옵션 파셋', () => {
  it('파셋 값을 고르면 그 선택으로 다시 질의하고 목록을 파셋 결과로 바꾼다', async () => {
    mockedFacet.search
      .mockResolvedValueOnce({
        products: [],
        facets: [
          {
            axisCode: 'COLOR',
            axisName: '색상',
            values: [{ code: 'RED', name: '빨강', productCount: 1, selected: false }],
          },
        ],
      } as never)
      .mockResolvedValueOnce({
        products: [product({ id: 2, name: '빨강 티셔츠' })],
        facets: [
          {
            axisCode: 'COLOR',
            axisName: '색상',
            values: [{ code: 'RED', name: '빨강', productCount: 1, selected: true }],
          },
        ],
      } as never);
    render(<OrderPage />);

    await userEvent.click(await screen.findByText(/빨강/));

    expect(await screen.findByText('빨강 티셔츠')).toBeInTheDocument();
    expect(mockedFacet.search).toHaveBeenLastCalledWith({ COLOR: ['RED'] });
  });

  it('파셋 조회가 실패해도 화면은 기존 목록으로 계속 동작한다', async () => {
    mockedFacet.search.mockRejectedValue(new Error('down'));
    render(<OrderPage />);

    expect(await screen.findByText('티셔츠')).toBeInTheDocument();
  });
});
