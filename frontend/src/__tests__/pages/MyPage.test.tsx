import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MyPage from '@/pages/MyPage';
import { ToastProvider } from '@/contexts/ToastContext';
import { orderApi } from '@/api/order';
import { productApi } from '@/api/product';
import { reviewApi } from '@/api/review';
import { authApi } from '@/api/auth';
import { orderWorkflowApi } from '@/api/orderWorkflow';
import { returnRequestApi } from '@/api/returnRequest';
import { shippingApi } from '@/api/shipping';

vi.mock('@/api/order', () => ({ orderApi: { getUserOrders: vi.fn(), getOrder: vi.fn() } }));
vi.mock('@/api/product', () => ({ productApi: { getAllProducts: vi.fn() } }));
vi.mock('@/api/review', () => ({
  reviewApi: {
    getUserReviews: vi.fn(),
    deleteReview: vi.fn(),
    createReview: vi.fn(),
    updateReview: vi.fn(),
  },
}));
vi.mock('@/api/auth', () => ({ authApi: { getCurrentUser: vi.fn() } }));
// 라벨 표(ORDER_STATUS_LABEL)와 노출 조건은 진짜를 쓰고, 서버로 나가는 호출만 가짜로 바꾼다.
// 이 화면이 실제로 붙였는지 보려는 것이므로 판정 로직까지 가짜로 만들면 검사가 헛돈다.
vi.mock('@/api/orderWorkflow', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/orderWorkflow')>()),
  orderWorkflowApi: {
    requestCancellation: vi.fn(),
    requestRefund: vi.fn(),
    withdrawRequest: vi.fn(),
  },
}));
// 신청은 이제 레코드로 간다. 여기서 보는 것은 여전히 "이 화면에 붙어 있는가" 뿐이고,
// 폼의 동작은 OrderRequestActions.test.tsx 가 따로 본다.
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
vi.mock('@/api/shipping', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/shipping')>()),
  shippingApi: { get: vi.fn(), changeAddress: vi.fn() },
}));

const mockedOrder = vi.mocked(orderApi);
const mockedWorkflow = vi.mocked(orderWorkflowApi);
const mockedReturnRequest = vi.mocked(returnRequestApi);
const mockedProduct = vi.mocked(productApi);
const mockedReview = vi.mocked(reviewApi);
const mockedAuth = vi.mocked(authApi);

const order = (over: Record<string, unknown> = {}) =>
  ({
    id: 100,
    userId: 1,
    productId: 1,
    amount: 20000,
    status: 'PAID',
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }) as never;

const product = (over: Record<string, unknown> = {}) =>
  ({ id: 1, name: '티셔츠', price: 20000, stockQuantity: 5, status: 'ACTIVE', ...over }) as never;

const review = (over: Record<string, unknown> = {}) =>
  ({
    id: 5,
    productId: 1,
    userId: 1,
    rating: 4,
    content: '좋아요',
    createdAt: '2026-08-02T00:00:00Z',
    ...over,
  }) as never;

let confirmSpy: ReturnType<typeof vi.spyOn>;
let alertSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedAuth.getCurrentUser.mockReturnValue({ id: 1, email: 'u@example.com', role: 'USER' } as never);
  mockedOrder.getUserOrders.mockResolvedValue([order()] as never);
  mockedProduct.getAllProducts.mockResolvedValue([product()] as never);
  mockedReview.getUserReviews.mockResolvedValue([] as never);
  mockedReturnRequest.history.mockResolvedValue([] as never);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
});

afterEach(() => {
  confirmSpy.mockRestore();
  alertSpy.mockRestore();
});

/**
 * 주문 카드가 취소·환불 신청 버튼을 품게 되면서 이 화면도 토스트를 쓴다.
 * {@code useToast} 는 provider 밖에서 호출되면 그냥 던지므로, 앱과 같은 배선으로 감싼다.
 */
const renderPage = () => render(<ToastProvider><MyPage /></ToastProvider>);

const renderAndWait = async () => {
  renderPage();
  await screen.findByText('주문 #100');
};

describe('MyPage — 사용자·주문', () => {
  it('로그인 사용자와 주문 내역을 보여 준다', async () => {
    await renderAndWait();

    expect(screen.getByText('u@example.com')).toBeInTheDocument();
    expect(screen.getByText('USER')).toBeInTheDocument();
    expect(screen.getByText('결제 완료')).toBeInTheDocument();
  });

  it('로그인 정보가 없으면 기본 표시로 대체한다', async () => {
    mockedAuth.getCurrentUser.mockReturnValue(null as never);
    await renderAndWait();

    expect(screen.getByText('사용자')).toBeInTheDocument();
  });

  it('주문이 없으면 그 사실을 알린다', async () => {
    mockedOrder.getUserOrders.mockResolvedValue([] as never);
    renderPage();

    expect(await screen.findByText('주문 내역이 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다', async () => {
    mockedOrder.getUserOrders.mockRejectedValue(new Error('down'));
    renderPage();

    expect(await screen.findByText('주문 목록을 불러오지 못했습니다.')).toBeInTheDocument();
  });
});

describe('MyPage — 리뷰', () => {
  it('결제 완료 주문에는 리뷰 작성 버튼이 붙는다', async () => {
    await renderAndWait();

    expect(screen.getByRole('button', { name: '리뷰 작성하기' })).toBeInTheDocument();
  });

  it('이미 리뷰가 있으면 수정하기로 바뀐다', async () => {
    mockedReview.getUserReviews.mockResolvedValue([review()] as never);
    await renderAndWait();

    expect(screen.getByRole('button', { name: '수정하기' })).toBeInTheDocument();
    expect(screen.getAllByText('내 리뷰').length).toBeGreaterThanOrEqual(2);
  });

  it('리뷰 작성 폼을 열고 저장하면 목록에 반영된다', async () => {
    mockedReview.createReview.mockResolvedValue(review({ id: 9, rating: 5, content: '최고' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '리뷰 작성하기' }));
    // 별점 5개 중 마지막을 고른 뒤 등록
    const stars = screen.getAllByRole('button').filter((b) => b.querySelector('svg'));
    await userEvent.click(stars[4]);
    await userEvent.click(screen.getByRole('button', { name: '리뷰 등록' }));

    await waitFor(() => expect(mockedReview.createReview).toHaveBeenCalled());
    expect(await screen.findByRole('button', { name: '수정하기' })).toBeInTheDocument();
  });

  it('내 리뷰 탭은 목록을 다시 읽는다', async () => {
    mockedReview.getUserReviews.mockResolvedValue([review()] as never);
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '내 리뷰' }));

    await waitFor(() => expect(mockedReview.getUserReviews).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('좋아요')).toBeInTheDocument();
  });

  it('리뷰가 없으면 안내 문구를 보여 준다', async () => {
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '내 리뷰' }));

    expect(await screen.findByText('작성한 리뷰가 없습니다.')).toBeInTheDocument();
  });

  it('리뷰 삭제는 확인을 거쳐 목록에서 제거한다', async () => {
    mockedReview.getUserReviews.mockResolvedValue([review()] as never);
    mockedReview.deleteReview.mockResolvedValue(undefined as never);
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '내 리뷰' }));
    await screen.findByText('좋아요');

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mockedReview.deleteReview).toHaveBeenCalledWith(5, 1));
  });

  it('삭제 확인을 취소하면 호출하지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    mockedReview.getUserReviews.mockResolvedValue([review()] as never);
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '내 리뷰' }));
    await screen.findByText('좋아요');

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    expect(mockedReview.deleteReview).not.toHaveBeenCalled();
  });

  it('삭제 실패는 알림으로 알린다', async () => {
    mockedReview.getUserReviews.mockResolvedValue([review()] as never);
    mockedReview.deleteReview.mockRejectedValue(new Error('down'));
    await renderAndWait();
    await userEvent.click(screen.getByRole('button', { name: '내 리뷰' }));
    await screen.findByText('좋아요');

    await userEvent.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('리뷰 삭제에 실패했습니다.'));
  });
});

/**
 * 반품·환불의 <b>진입점</b> 검사.
 *
 * 백엔드 엔드포인트도 전이표도 {@code OrderRequestActions} 도 이미 있었지만 어느 화면도 그
 * 컴포넌트를 렌더링하지 않아서, 고객은 취소·환불을 신청할 방법이 아예 없었다. 그래서 여기서
 * 보는 것은 버튼의 동작이 아니라 <b>이 화면에 붙어 있는가</b> 다 — 컴포넌트 자체의 동작은
 * {@code OrderRequestActions.test.tsx} 가 따로 본다.
 */
describe('MyPage — 취소·환불 신청 진입점', () => {
  it('결제 완료 주문 카드에서 취소·반품·교환을 신청할 수 있다', async () => {
    await renderAndWait();

    expect(screen.getByRole('button', { name: '취소 신청' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '반품 신청' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '교환 신청' })).toBeInTheDocument();
  });

  it('신청이 끝나면 카드의 상태 배지가 한글 라벨로 바뀐다', async () => {
    mockedReturnRequest.submit.mockResolvedValue({ id: 9, type: 'RETURN' } as never);
    mockedOrder.getOrder.mockResolvedValue(order({ status: 'REFUND_REQUESTED' }) as never);
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: '반품 신청' }));
    await userEvent.click(screen.getByRole('button', { name: '반품 신청' }));

    await waitFor(() =>
      expect(mockedReturnRequest.submit).toHaveBeenCalledWith(100, expect.objectContaining({ type: 'RETURN' }))
    );
    // 라벨 표가 4개뿐이던 시절엔 여기에 'REFUND_REQUESTED' 라는 enum 이 그대로 찍혔다.
    expect(await screen.findByText('환불 신청됨')).toBeInTheDocument();
    expect(screen.queryByText('REFUND_REQUESTED')).not.toBeInTheDocument();
  });

  /** 교환은 뒤늦게 들어온 상태다 — 라벨 표를 빠뜨리면 카드에 영문 enum 이 그대로 찍힌다. */
  it('교환 신청 상태도 한글 라벨로 나온다', async () => {
    mockedOrder.getUserOrders.mockResolvedValue([order({ status: 'EXCHANGE_REQUESTED' })] as never);
    await renderAndWait();

    expect(screen.getByText('교환 신청됨')).toBeInTheDocument();
    expect(screen.queryByText('EXCHANGE_REQUESTED')).not.toBeInTheDocument();
  });

  it('신청 상태인 주문은 철회만 남는다', async () => {
    mockedOrder.getUserOrders.mockResolvedValue([order({ status: 'REFUND_REQUESTED' })] as never);
    mockedOrder.getOrder.mockResolvedValue(order({ status: 'PAID' }) as never);
    await renderAndWait();

    expect(screen.queryByRole('button', { name: '반품 신청' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '신청 철회' }));

    // 레코드가 없는 주문(이 변경 이전 신청)은 옛 경로로 철회된다.
    await waitFor(() => expect(mockedWorkflow.withdrawRequest).toHaveBeenCalledWith(100));
    // 철회하면 신청 직전 상태로 돌아간다 — 서버가 정하고 화면은 다시 읽는다.
    expect(await screen.findByText('결제 완료')).toBeInTheDocument();
  });

  it('종단 상태(환불됨)에는 아무 신청 버튼도 붙지 않는다', async () => {
    mockedOrder.getUserOrders.mockResolvedValue([order({ status: 'REFUNDED' })] as never);
    await renderAndWait();

    expect(screen.getByText('환불됨')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '취소 신청' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반품 신청' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '교환 신청' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '신청 철회' })).not.toBeInTheDocument();
    // 배송지 변경도 붙지 않는다 — 환불된 주문에 보낼 곳을 묻는 칸이 남아 있으면 안 된다.
    expect(screen.queryByRole('button', { name: /배송지 확인/ })).not.toBeInTheDocument();
  });
});

/**
 * 배송지 변경의 <b>진입점</b> 검사.
 *
 * 서버의 {@code PATCH /orders/{id}/shipment/address} 는 처음부터 고객도 부를 수 있었는데
 * 부르는 화면이 운영자 콘솔뿐이었다. 주소를 잘못 적은 고객에게 남은 방법은 전화뿐이었다.
 */
describe('MyPage — 배송지 변경 진입점', () => {
  const shipment = (over: Record<string, unknown> = {}) =>
    ({
      id: 1, orderId: 100, status: 'PENDING',
      recipientName: '홍길동', phone: '010-1111-2222',
      postalCode: '06236', address1: '서울시 강남구 테헤란로 1', address2: '101호',
      deliveryMemo: null, carrier: null, trackingNumber: null,
      shippedAt: null, deliveredAt: null, ...over,
    }) as never;

  it('배송을 미리 읽지 않는다 — 펼칠 때 한 번만 부른다', async () => {
    vi.mocked(shippingApi.get).mockResolvedValue(shipment());
    await renderAndWait();

    // 카드마다 미리 읽으면 주문 수만큼 요청이 나가고 대부분은 404 다.
    expect(shippingApi.get).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /배송지 확인/ }));
    await waitFor(() => expect(shippingApi.get).toHaveBeenCalledWith(100));
  });

  it('출고 전이면 배송지를 고쳐 저장할 수 있다', async () => {
    vi.mocked(shippingApi.get).mockResolvedValue(shipment());
    vi.mocked(shippingApi.changeAddress).mockResolvedValue(
      shipment({ address1: '서울시 마포구 월드컵북로 2' })
    );
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: /배송지 확인/ }));
    await userEvent.click(await screen.findByRole('button', { name: '배송지 변경' }));
    await userEvent.clear(screen.getByLabelText('주소'));
    await userEvent.type(screen.getByLabelText('주소'), '서울시 마포구 월드컵북로 2');
    await userEvent.click(screen.getByRole('button', { name: '변경 저장' }));

    await waitFor(() =>
      expect(shippingApi.changeAddress).toHaveBeenCalledWith(
        100, expect.objectContaining({ address1: '서울시 마포구 월드컵북로 2' })
      )
    );
    expect(await screen.findByText(/월드컵북로 2/)).toBeInTheDocument();
  });

  /** 버튼만 사라지면 고객은 화면이 고장난 줄 안다. 왜 못 바꾸는지를 대신 적는다. */
  it('출고된 뒤에는 변경 버튼 대신 이유가 뜬다', async () => {
    vi.mocked(shippingApi.get).mockResolvedValue(shipment({ status: 'IN_TRANSIT' }));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: /배송지 확인/ }));

    expect(await screen.findByText(/이미 출고되어/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '배송지 변경' })).not.toBeInTheDocument();
  });

  /** 배송 생성 전 404 는 오류가 아니라 "아직 없음"이다 — 빨간 토스트를 띄우면 안 된다. */
  it('배송이 아직 없으면 조용히 알린다', async () => {
    vi.mocked(shippingApi.get).mockRejectedValue(new Error('404'));
    await renderAndWait();

    await userEvent.click(screen.getByRole('button', { name: /배송지 확인/ }));

    expect(await screen.findByText(/아직 배송 정보가 만들어지지 않았습니다/)).toBeInTheDocument();
  });
});
