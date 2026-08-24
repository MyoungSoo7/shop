import { describe, it, expect, vi, beforeEach } from 'vitest';
import { reviewApi } from '@/api/review';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const review = { id: 1, productId: 42, userId: 7, rating: 5, content: '좋아요' };

describe('reviewApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('리뷰를 작성한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: review });

    const result = await reviewApi.createReview({ productId: 42, rating: 5, content: '좋아요' } as never);

    expect(api.post).toHaveBeenCalledWith('/reviews', { productId: 42, rating: 5, content: '좋아요' });
    expect(result.rating).toBe(5);
  });

  it('상품별 리뷰를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [review] });

    const result = await reviewApi.getProductReviews(42);

    expect(api.get).toHaveBeenCalledWith('/reviews/product/42');
    expect(result).toHaveLength(1);
  });

  it('사용자별 리뷰를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [review] });

    const result = await reviewApi.getUserReviews(7);

    expect(api.get).toHaveBeenCalledWith('/reviews/user/7');
    expect(result[0].userId).toBe(7);
  });

  it('리뷰를 수정한다', async () => {
    vi.mocked(api.put).mockResolvedValueOnce({ data: { ...review, content: '수정함' } });

    const result = await reviewApi.updateReview(1, { rating: 4, content: '수정함' } as never);

    expect(api.put).toHaveBeenCalledWith('/reviews/1', { rating: 4, content: '수정함' });
    expect(result.content).toBe('수정함');
  });

  it('리뷰 삭제는 userId 를 쿼리로 넘긴다 (소유자 확인)', async () => {
    vi.mocked(api.delete).mockResolvedValueOnce({ data: undefined });

    await reviewApi.deleteReview(1, 7);

    expect(api.delete).toHaveBeenCalledWith('/reviews/1', { params: { userId: 7 } });
  });

  it('남의 리뷰 삭제는 403 이 전파된다', async () => {
    vi.mocked(api.delete).mockRejectedValueOnce({ response: { status: 403 } });

    await expect(reviewApi.deleteReview(1, 999)).rejects.toMatchObject({
      response: { status: 403 },
    });
  });
});
