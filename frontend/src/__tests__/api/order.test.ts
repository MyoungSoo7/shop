import { describe, it, expect, vi, beforeEach } from 'vitest';
import { orderApi } from '@/api/order';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

const order = { id: 100, userId: 7, status: 'CREATED', totalAmount: 30000 };

describe('orderApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('주문을 생성한다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: order });

    const result = await orderApi.createOrder({ userId: 7, items: [] } as never);

    expect(api.post).toHaveBeenCalledWith('/orders', { userId: 7, items: [] });
    expect(result.id).toBe(100);
  });

  it('주문 단건을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: order });

    const result = await orderApi.getOrder(100);

    expect(api.get).toHaveBeenCalledWith('/orders/100');
    expect(result.status).toBe('CREATED');
  });

  it('사용자별 주문 목록을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [order] });

    const result = await orderApi.getUserOrders(7);

    expect(api.get).toHaveBeenCalledWith('/orders/user/7');
    expect(result).toHaveLength(1);
  });

  it('주문을 취소한다', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { ...order, status: 'CANCELLED' } });

    const result = await orderApi.cancelOrder(100);

    expect(api.patch).toHaveBeenCalledWith('/orders/100/cancel');
    expect(result.status).toBe('CANCELLED');
  });

  it('취소 불가 상태면 오류가 전파된다 (상태머신은 서버가 강제)', async () => {
    vi.mocked(api.patch).mockRejectedValueOnce({ response: { status: 400 } });

    await expect(orderApi.cancelOrder(100)).rejects.toMatchObject({ response: { status: 400 } });
  });
});
