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

  it('전체 주문을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: [{ id: 1, status: 'CREATED' }, { id: 2, status: 'PAID' }],
    });

    const result = await adminApi.getAllOrders();

    expect(api.get).toHaveBeenCalledWith('/orders/admin/all');
    expect(result).toHaveLength(2);
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
