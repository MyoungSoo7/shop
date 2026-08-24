import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MyPage from '@/pages/MyPage';
import { orderApi } from '@/api/order';
import { productApi } from '@/api/product';
import { reviewApi } from '@/api/review';
import { authApi } from '@/api/auth';

vi.mock('@/api/order', () => ({ orderApi: { getUserOrders: vi.fn() } }));
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

const mockedOrder = vi.mocked(orderApi);
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
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
});

afterEach(() => {
  confirmSpy.mockRestore();
  alertSpy.mockRestore();
});

const renderAndWait = async () => {
  render(<MyPage />);
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
    render(<MyPage />);

    expect(await screen.findByText('주문 내역이 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사유를 보여 준다', async () => {
    mockedOrder.getUserOrders.mockRejectedValue(new Error('down'));
    render(<MyPage />);

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
