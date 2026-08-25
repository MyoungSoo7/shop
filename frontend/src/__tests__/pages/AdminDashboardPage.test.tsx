import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AdminDashboardPage from '@/pages/AdminDashboardPage';
import { adminApi } from '@/api/admin';
import { productApi } from '@/api/product';
import { orderApi } from '@/api/order';
import { couponApi } from '@/api/coupon';
import { authApi } from '@/api/auth';

vi.mock('@/api/admin', () => ({
  adminApi: { getOrders: vi.fn(), getOrderSummary: vi.fn(), getAllUsers: vi.fn() },
}));
vi.mock('@/api/product', () => ({
  productApi: { getAllProducts: vi.fn() },
}));
vi.mock('@/api/order', () => ({
  orderApi: { cancelOrder: vi.fn() },
}));
vi.mock('@/api/coupon', () => ({
  couponApi: { getAll: vi.fn(), create: vi.fn() },
}));
vi.mock('@/api/auth', () => ({
  authApi: { getCurrentUser: vi.fn() },
}));

const mockedAdmin = vi.mocked(adminApi);
const mockedProduct = vi.mocked(productApi);
const mockedOrder = vi.mocked(orderApi);
const mockedCoupon = vi.mocked(couponApi);
const mockedAuth = vi.mocked(authApi);

const order = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    userId: 7,
    productId: 3,
    amount: 20000,
    status: 'CREATED',
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }) as never;

/** 한 페이지 응답. totalElements 를 안 주면 배열 길이로 둔다(= 1쪽짜리 목록). */
const orderPage = (content: unknown[], over: Record<string, unknown> = {}) =>
  ({
    content,
    page: 0,
    size: 50,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    ...over,
  }) as never;

/**
 * 상태별 집계 응답. 화면의 건수·매출은 전부 여기서 온다 — 주문 배열을 세지 않는다.
 * 배열을 세던 시절에는 페이징이 붙는 순간 모든 숫자가 "첫 페이지만 센 값"으로
 * 조용히 바뀌었고, 화면에는 여전히 숫자가 찍혔다.
 */
const orderSummary = (
  statuses: Array<{ status: string; count: number; amountSum: string | null }> = [
    { status: 'CREATED', count: 1, amountSum: '20000' },
  ],
) =>
  ({
    totalCount: statuses.reduce((sum, s) => sum + s.count, 0),
    totalAmount: String(statuses.reduce((sum, s) => sum + Number(s.amountSum ?? 0), 0)),
    statuses,
  }) as never;

const product = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    name: '티셔츠',
    description: '면 100%',
    price: 20000,
    stockQuantity: 15,
    status: 'ACTIVE',
    primaryImageUrl: null,
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }) as never;

const user = (over: Record<string, unknown> = {}) =>
  ({ id: 1, email: 'a@example.com', role: 'ADMIN', createdAt: '2026-01-01T00:00:00Z', ...over }) as never;

const coupon = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    code: 'SUMMER20',
    type: 'PERCENTAGE',
    discountValue: 20,
    minOrderAmount: 10000,
    maxUses: 100,
    usedCount: 3,
    expiresAt: null,
    isActive: true,
    ...over,
  }) as never;

const renderPage = () => render(<MemoryRouter><AdminDashboardPage /></MemoryRouter>);

let confirmSpy: ReturnType<typeof vi.spyOn>;
let alertSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedAuth.getCurrentUser.mockReturnValue({ id: 1, email: 'a@e.com', role: 'ADMIN' } as never);
  mockedAdmin.getOrders.mockResolvedValue(orderPage([order()]));
  mockedAdmin.getOrderSummary.mockResolvedValue(orderSummary());
  mockedAdmin.getAllUsers.mockResolvedValue([user()] as never);
  mockedProduct.getAllProducts.mockResolvedValue([product()] as never);
  mockedCoupon.getAll.mockResolvedValue([coupon()] as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
});

afterEach(() => {
  confirmSpy.mockRestore();
  alertSpy.mockRestore();
});

describe('AdminDashboardPage — 로드·권한', () => {
  it('ADMIN 은 주문·상품·회원·쿠폰을 모두 읽고 5개 탭을 본다', async () => {
    renderPage();

    expect(await screen.findByText('관리자 대시보드')).toBeInTheDocument();
    expect(mockedAdmin.getAllUsers).toHaveBeenCalled();
    expect(mockedCoupon.getAll).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /회원 관리/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /쿠폰 관리/ })).toBeInTheDocument();
  });

  it('MANAGER 는 회원·쿠폰을 요청하지 않고 탭도 감춘다 (권한 없음)', async () => {
    mockedAuth.getCurrentUser.mockReturnValue({ id: 2, email: 'm@e.com', role: 'MANAGER' } as never);
    renderPage();

    expect(await screen.findByText('매니저 대시보드')).toBeInTheDocument();
    expect(mockedAdmin.getAllUsers).not.toHaveBeenCalled();
    expect(mockedCoupon.getAll).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /회원 관리/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /쿠폰 관리/ })).not.toBeInTheDocument();
  });

  it('로드 중에는 스피너를 보여 준다', () => {
    renderPage();

    expect(screen.getByText('관리자 데이터 로드 중...')).toBeInTheDocument();
  });

  it('조회가 실패하면 화면 전체를 오류 문구로 대체한다', async () => {
    mockedAdmin.getOrders.mockRejectedValue(new Error('down'));
    renderPage();

    expect(await screen.findByText('데이터를 불러오지 못했습니다.')).toBeInTheDocument();
  });
});

describe('AdminDashboardPage — 개요 탭', () => {
  it('결제완료 주문만 매출로 집계한다', async () => {
    mockedAdmin.getOrderSummary.mockResolvedValue(orderSummary([
      { status: 'PAID', count: 1, amountSum: '30000' },
      { status: 'CREATED', count: 1, amountSum: '50000' },
      { status: 'CANCELED', count: 1, amountSum: '10000' },
    ]));
    renderPage();

    // 같은 금액이 최근 주문 카드에도 찍히므로 매출 카드 안으로 범위를 좁힌다
    const revenueCard = (await screen.findByText('총 매출')).closest('div')!.parentElement!;
    expect(within(revenueCard).getByText('₩30,000')).toBeInTheDocument();
    expect(screen.getByText('결제완료 1건')).toBeInTheDocument();
  });

  it('건수는 목록 배열이 아니라 서버 집계에서 온다 — 한 페이지만 받아도 전체가 찍힌다', async () => {
    // 목록은 3건짜리 한 페이지, 집계는 137건. 배열을 세던 코드로 되돌아가면 여기서 3이 나온다.
    mockedAdmin.getOrders.mockResolvedValue(
      orderPage([order({ id: 1 }), order({ id: 2 }), order({ id: 3 })],
        { totalElements: 137, totalPages: 3 }),
    );
    mockedAdmin.getOrderSummary.mockResolvedValue(orderSummary([
      { status: 'PAID', count: 100, amountSum: '1000000' },
      { status: 'CREATED', count: 37, amountSum: '370000' },
    ]));
    renderPage();

    const totalCard = (await screen.findByText('총 주문')).closest('div')!.parentElement!;
    expect(within(totalCard).getByText('137')).toBeInTheDocument();
  });

  it('재고 부족·품절 개수를 상품 카드에 적는다', async () => {
    mockedProduct.getAllProducts.mockResolvedValue([
      product({ id: 1, stockQuantity: 0 }),
      product({ id: 2, stockQuantity: 5 }),
      product({ id: 3, stockQuantity: 50 }),
    ] as never);
    renderPage();

    expect(await screen.findByText('재고부족 1개 · 품절 1개')).toBeInTheDocument();
  });

  it('최근 주문 카드에서 전체 주문 탭으로 넘어갈 수 있다', async () => {
    renderPage();
    await screen.findByText('최근 주문');

    await userEvent.click(screen.getByRole('button', { name: '전체 주문 보기 →' }));

    expect(screen.getByPlaceholderText('주문ID / 회원ID 검색')).toBeInTheDocument();
  });
});

describe('AdminDashboardPage — 주문 관리', () => {
  const gotoOrders = async () => {
    renderPage();
    await screen.findByText('관리자 대시보드');
    await userEvent.click(screen.getByRole('button', { name: /주문 관리/ }));
  };

  it('상태 필터는 서버 조건으로 나간다 — 받아 온 배열을 다시 거르지 않는다', async () => {
    mockedAdmin.getOrders.mockResolvedValue(
      orderPage([order({ id: 1, status: 'PAID' }), order({ id: 2, status: 'CREATED' })]),
    );
    await gotoOrders();

    mockedAdmin.getOrders.mockResolvedValue(orderPage([order({ id: 1, status: 'PAID' })]));
    await userEvent.click(screen.getByRole('button', { name: '결제완료' }));

    await waitFor(() =>
      expect(mockedAdmin.getOrders).toHaveBeenCalledWith(
        expect.objectContaining({ status: ['PAID'], page: 0 }),
      ));
    expect(await screen.findByText('#1')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('#2')).not.toBeInTheDocument());
  });

  it('다음 쪽으로 넘기면 그 페이지를 서버에서 새로 받아 온다', async () => {
    mockedAdmin.getOrders.mockResolvedValue(
      orderPage([order({ id: 1 })], { totalElements: 60, totalPages: 2 }),
    );
    await gotoOrders();

    await userEvent.click(await screen.findByRole('button', { name: '다음' }));

    await waitFor(() =>
      expect(mockedAdmin.getOrders).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));
  });

  it('주문ID·회원ID 로 검색한다', async () => {
    mockedAdmin.getOrders.mockResolvedValue(
      orderPage([order({ id: 11, userId: 7 }), order({ id: 22, userId: 9 })]),
    );
    await gotoOrders();

    await userEvent.type(screen.getByPlaceholderText('주문ID / 회원ID 검색'), '22');

    expect(screen.getByText('#22')).toBeInTheDocument();
    expect(screen.queryByText('#11')).not.toBeInTheDocument();
  });

  it('조건에 맞는 주문이 없으면 그 사실을 알린다', async () => {
    await gotoOrders();

    await userEvent.type(screen.getByPlaceholderText('주문ID / 회원ID 검색'), '9999');

    // 검색은 받아 온 페이지 안에서만 도므로 "없다"가 아니라 "이 페이지에 없다"고 적는다
    expect(screen.getByText(/이 페이지에는 "9999" 와 맞는 주문이 없습니다/)).toBeInTheDocument();
  });

  it('CREATED 주문만 취소할 수 있고, 확인 후 목록의 그 행만 갱신한다', async () => {
    mockedOrder.cancelOrder.mockResolvedValue(order({ id: 1, status: 'CANCELED' }));
    await gotoOrders();

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(mockedOrder.cancelOrder).toHaveBeenCalledWith(1));
    // '취소됨'은 상태 필터 버튼에도 있으므로 그 주문 행 안에서 확인한다 (findBy — 갱신 렌더 경합 방지)
    const row = (await screen.findByText('#1')).closest('tr') as HTMLElement;
    expect(await within(row).findByText('취소됨')).toBeInTheDocument();
  });

  it('확인창에서 취소하면 API 를 부르지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await gotoOrders();

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(mockedOrder.cancelOrder).not.toHaveBeenCalled();
  });

  it('취소 실패는 알림으로 알린다', async () => {
    mockedOrder.cancelOrder.mockRejectedValue(new Error('down'));
    await gotoOrders();

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('주문 취소에 실패했습니다.'));
  });
});

describe('AdminDashboardPage — 상품·회원 관리', () => {
  const goto = async (name: RegExp) => {
    renderPage();
    await screen.findByText('관리자 대시보드');
    await userEvent.click(screen.getByRole('button', { name }));
  };

  it('상품명으로 검색한다', async () => {
    mockedProduct.getAllProducts.mockResolvedValue([
      product({ id: 1, name: '티셔츠' }),
      product({ id: 2, name: '바지' }),
    ] as never);
    await goto(/상품 관리/);

    await userEvent.type(screen.getByPlaceholderText('상품명 검색'), '바지');

    expect(screen.getByText('바지')).toBeInTheDocument();
    expect(screen.queryByText('티셔츠')).not.toBeInTheDocument();
  });

  it('상품 상태 필터가 걸리면 빈 결과를 문장으로 알린다', async () => {
    await goto(/상품 관리/);

    await userEvent.click(screen.getByRole('button', { name: '단종' }));

    expect(screen.getByText('조건에 맞는 상품이 없습니다.')).toBeInTheDocument();
  });

  it('회원을 이메일로 검색하고 역할로 거른다', async () => {
    mockedAdmin.getAllUsers.mockResolvedValue([
      user({ id: 1, email: 'admin@example.com', role: 'ADMIN' }),
      user({ id: 2, email: 'buyer@example.com', role: 'USER' }),
    ] as never);
    await goto(/회원 관리/);

    await userEvent.type(screen.getByPlaceholderText('이메일 검색'), 'buyer');
    expect(screen.getByText('buyer@example.com')).toBeInTheDocument();
    expect(screen.queryByText('admin@example.com')).not.toBeInTheDocument();

    await userEvent.clear(screen.getByPlaceholderText('이메일 검색'));
    await userEvent.click(screen.getByRole('button', { name: 'ADMIN' }));
    expect(screen.getByText('admin@example.com')).toBeInTheDocument();
    expect(screen.queryByText('buyer@example.com')).not.toBeInTheDocument();
  });

  it('조건에 맞는 회원이 없으면 그 사실을 알린다', async () => {
    await goto(/회원 관리/);

    await userEvent.type(screen.getByPlaceholderText('이메일 검색'), 'nobody');

    expect(screen.getByText('조건에 맞는 회원이 없습니다.')).toBeInTheDocument();
  });
});

describe('AdminDashboardPage — 쿠폰 관리', () => {
  const gotoCoupons = async () => {
    renderPage();
    await screen.findByText('관리자 대시보드');
    await userEvent.click(screen.getByRole('button', { name: /쿠폰 관리/ }));
  };

  it('기존 쿠폰을 표로 보여 준다', async () => {
    await gotoCoupons();

    expect(screen.getByText('SUMMER20')).toBeInTheDocument();
    expect(screen.getByText('20%')).toBeInTheDocument();
    expect(screen.getByText('무기한')).toBeInTheDocument();
    expect(screen.getByText('활성')).toBeInTheDocument();
  });

  it('쿠폰이 없으면 생성 안내를 보여 준다', async () => {
    mockedCoupon.getAll.mockResolvedValue([] as never);
    await gotoCoupons();

    expect(screen.getByText('쿠폰이 없습니다. 위 폼에서 생성하세요.')).toBeInTheDocument();
  });

  it('코드는 대문자로 정규화해 생성하고 목록 맨 앞에 붙인다', async () => {
    mockedCoupon.create.mockResolvedValue(coupon({ id: 2, code: 'WINTER10' }));
    await gotoCoupons();

    await userEvent.type(screen.getByPlaceholderText('예: SUMMER20'), 'winter10');
    await userEvent.click(screen.getByRole('button', { name: '쿠폰 생성' }));

    await waitFor(() =>
      expect(mockedCoupon.create).toHaveBeenCalledWith(
        expect.objectContaining({ code: 'WINTER10', type: 'PERCENTAGE', discountValue: 10 }),
      ),
    );
    expect(await screen.findByText('쿠폰 "WINTER10" 생성 완료!')).toBeInTheDocument();
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]).getByText('WINTER10')).toBeInTheDocument();
  });

  it('정액 타입을 고르면 할인 단위 라벨이 원으로 바뀐다', async () => {
    await gotoCoupons();

    await userEvent.selectOptions(screen.getAllByRole('combobox')[0], 'FIXED');

    expect(screen.getByText('할인 값 (원)')).toBeInTheDocument();
  });

  it('생성 실패는 사유를 폼 옆에 남긴다', async () => {
    mockedCoupon.create.mockRejectedValue({ response: { data: { message: '이미 있는 코드' } } });
    await gotoCoupons();

    await userEvent.type(screen.getByPlaceholderText('예: SUMMER20'), 'DUP');
    await userEvent.click(screen.getByRole('button', { name: '쿠폰 생성' }));

    expect(await screen.findByText('이미 있는 코드')).toBeInTheDocument();
  });
});
