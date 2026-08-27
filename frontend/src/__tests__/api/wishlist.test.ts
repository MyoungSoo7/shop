import { describe, it, expect, vi, beforeEach } from 'vitest';
import { wishlistApi, GONE_AVAILABILITIES, type Wishlist } from '@/api/wishlist';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const mockWishlist: Wishlist = {
  items: [
    {
      productId: 10, name: '살 수 있는 것', price: 1000, primaryImageUrl: 'https://img/10',
      availability: 'AVAILABLE', reason: '구매 가능', available: true, gone: false,
      addedAt: '2026-08-01T10:00:00',
    },
    {
      productId: 11, name: '품절된 것', price: 2000, primaryImageUrl: null,
      availability: 'OUT_OF_STOCK', reason: '품절', available: false, gone: false,
      addedAt: '2026-08-02T10:00:00',
    },
  ],
  totalCount: 2,
  goneCount: 0,
  maxItems: 300,
};

describe('wishlistApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('조회는 사용자별 경로를 쓴다 — 찜은 소유자에 매인 자원이다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: mockWishlist });

    const result = await wishlistApi.list(7);

    expect(api.get).toHaveBeenCalledWith('/users/7/wishlist');
    expect(result.items).toHaveLength(2);
    expect(result.maxItems).toBe(300);
  });

  /** 거르는 쪽은 화면이지 API 가 아니다 — 여기서 걸러 버리면 사유를 보여 줄 재료가 사라진다. */
  it('살 수 없는 항목도 사유를 달고 그대로 온다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: mockWishlist });

    const result = await wishlistApi.list(7);

    expect(result.items[1].available).toBe(false);
    expect(result.items[1].reason).toBe('품절');
  });

  it('담기는 PUT 이다 — 같은 요청을 두 번 보내도 결과가 같아야 한다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { wished: true, changed: true, count: 3 } });

    const result = await wishlistApi.add(7, 10);

    expect(api.put).toHaveBeenCalledWith('/users/7/wishlist/products/10');
    expect(result.wished).toBe(true);
    expect(result.count).toBe(3);
  });

  it('이미 담긴 것을 또 담으면 changed 만 false 로 온다 — 오류가 아니다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { wished: true, changed: false, count: 3 } });

    const result = await wishlistApi.add(7, 10);

    expect(result.wished).toBe(true);
    expect(result.changed).toBe(false);
  });

  it('빼기는 상품 경로를 지운다', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: { wished: false, changed: true, count: 2 } });

    const result = await wishlistApi.remove(7, 10);

    expect(api.delete).toHaveBeenCalledWith('/users/7/wishlist/products/10');
    expect(result.wished).toBe(false);
  });

  it('단건 조회는 목록 경로를 부르지 않는다 — 하트 하나에 수백 줄을 끌어오지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { productId: 10, wished: true } });

    const result = await wishlistApi.contains(7, 10);

    expect(api.get).toHaveBeenCalledWith('/users/7/wishlist/products/10');
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(result.wished).toBe(true);
  });

  it('일괄 정리는 /gone 을 지우고 지워진 목록을 돌려받는다', async () => {
    vi.mocked(api.delete).mockResolvedValue({
      data: {
        removed: [{ ...mockWishlist.items[1], availability: 'DISCONTINUED', gone: true, reason: '단종' }],
        wishlist: { ...mockWishlist, totalCount: 1, goneCount: 0, items: [mockWishlist.items[0]] },
      },
    });

    const result = await wishlistApi.purgeGone(7);

    expect(api.delete).toHaveBeenCalledWith('/users/7/wishlist/gone');
    // 개수만 오면 화면이 무엇을 지웠는지 말할 수 없다.
    expect(result.removed[0].name).toBe('품절된 것');
    expect(result.wishlist.totalCount).toBe(1);
  });

  it('정리 대상은 단종·삭제뿐이다 — 품절은 들어 있지 않다', () => {
    expect(GONE_AVAILABILITIES).toEqual(['DISCONTINUED', 'REMOVED']);
    expect(GONE_AVAILABILITIES).not.toContain('OUT_OF_STOCK');
  });
});
