import { describe, it, expect, vi, beforeEach } from 'vitest';
import { cartApi, type CartResponse } from '@/api/cart';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

const mockCart: CartResponse = {
  cart: { id: 10, userId: 7, totalQuantity: 3, itemCount: 2, lastActiveAt: '2026-08-09T10:00:00' },
  items: [
    { id: 1, productId: 100, variantId: null, quantity: 2, addedAt: '2026-08-09T10:00:00' },
    { id: 2, productId: 200, variantId: 5, quantity: 1, addedAt: '2026-08-09T10:01:00' },
  ],
};

describe('cartApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('조회는 사용자별 경로를 쓴다 — 장바구니는 소유자에 매인 자원이다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: mockCart });

    const result = await cartApi.get(7);

    expect(api.get).toHaveBeenCalledWith('/users/7/cart');
    expect(result.items).toHaveLength(2);
    expect(result.cart.totalQuantity).toBe(3);
  });

  it('항목 추가는 variantId 를 생략해도 명시적 null 로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: mockCart });

    await cartApi.addItem(7, 100, 2);

    expect(api.post).toHaveBeenCalledWith('/users/7/cart/items', {
      productId: 100,
      variantId: null,
      quantity: 2,
    });
  });

  it('수량 변경은 PATCH 로 보낸다 — 0 이면 서버가 삭제로 처리한다', async () => {
    vi.mocked(api.patch).mockResolvedValue({ data: mockCart });

    await cartApi.changeQuantity(7, 100, 0);

    expect(api.patch).toHaveBeenCalledWith('/users/7/cart/items', {
      productId: 100,
      variantId: null,
      quantity: 0,
    });
  });

  /** variantId 가 없을 때 params 에 null 을 실으면 쿼리스트링에 variantId= 가 붙어 서버 해석이 달라진다. */
  it('항목 삭제는 variantId 가 없으면 쿼리에 아예 싣지 않는다', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: mockCart });

    await cartApi.removeItem(7, 100);

    expect(api.delete).toHaveBeenCalledWith('/users/7/cart/items', {
      params: { productId: 100 },
    });
  });

  it('항목 삭제는 variantId 가 있으면 함께 싣는다', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: mockCart });

    await cartApi.removeItem(7, 200, 5);

    expect(api.delete).toHaveBeenCalledWith('/users/7/cart/items', {
      params: { productId: 200, variantId: 5 },
    });
  });

  it('비우기는 항목 경로가 아니라 장바구니 루트를 지운다', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: { ...mockCart, items: [] } });

    const result = await cartApi.clear(7);

    expect(api.delete).toHaveBeenCalledWith('/users/7/cart');
    expect(result.items).toEqual([]);
  });

  it('체크아웃은 주문 요약을 돌려준다', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { orderId: 55, amount: 30000, itemCount: 2, status: 'CREATED' },
    });

    const result = await cartApi.checkout(7);

    expect(api.post).toHaveBeenCalledWith('/users/7/cart/checkout');
    expect(result.orderId).toBe(55);
    expect(result.status).toBe('CREATED');
  });
});
