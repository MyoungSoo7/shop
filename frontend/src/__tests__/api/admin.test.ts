import { describe, it, expect, vi, beforeEach } from 'vitest';
import { adminApi } from '@/api/admin';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

describe('adminApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it('주문 한 페이지를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        content: [{ id: 1, status: 'CREATED' }, { id: 2, status: 'PAID' }],
        page: 0,
        size: 50,
        totalElements: 137,
        totalPages: 3,
      },
    });

    const result = await adminApi.getOrders({ page: 0, size: 50 });

    expect(api.get).toHaveBeenCalledWith('/orders/admin', { params: { page: 0, size: 50 } });
    expect(result.content).toHaveLength(2);
    // 배열 길이(2)와 전체 건수(137)는 다르다. 이 둘을 섞어 쓰는 게 이번 작업의 원인이었다.
    expect(result.totalElements).toBe(137);
  });

  it('빈 조건은 파라미터로 나가지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
    });

    await adminApi.getOrders({ status: [], from: '', to: undefined });

    expect(api.get).toHaveBeenCalledWith('/orders/admin', { params: {} });
  });

  it('상태 여러 개는 반복 파라미터로 나간다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
    });

    await adminApi.getOrders({ status: ['CANCELLATION_REQUESTED', 'REFUND_REQUESTED'] });

    expect(api.get).toHaveBeenCalledWith('/orders/admin', {
      params: { status: ['CANCELLATION_REQUESTED', 'REFUND_REQUESTED'] },
    });
  });

  it('집계는 page/size 를 떼고 부른다 — 붙으면 전 범위 집계가 아니게 된다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { totalCount: 12, totalAmount: '1200.00', statuses: [] },
    });

    const summary = await adminApi.getOrderSummary({ status: ['PAID'], page: 3, size: 10 });

    expect(api.get).toHaveBeenCalledWith('/orders/admin/summary', {
      params: { status: ['PAID'] },
    });
    expect(summary.totalCount).toBe(12);
  });

  it('전체 사용자를 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [{ id: 1, email: 'a@b.com', role: 'ADMIN', createdAt: '2026-01-01T00:00:00Z' }],
    });

    const result = await adminApi.getAllUsers();

    expect(api.get).toHaveBeenCalledWith('/users/admin/all');
    expect(result[0].role).toBe('ADMIN');
  });

  it('권한이 없으면 403 이 전파된다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 403 } });

    await expect(adminApi.getAllUsers()).rejects.toMatchObject({ response: { status: 403 } });
  });
});
