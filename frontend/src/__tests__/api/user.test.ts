import { describe, it, expect, vi, beforeEach } from 'vitest';
import { userApi } from '@/api/user';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

describe('userApi.me', () => {
  beforeEach(() => vi.resetAllMocks());

  it('경로에 식별자를 싣지 않는다 — 주체는 서버가 JWT 에서 정한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        id: 7,
        email: 'seller@example.com',
        role: 'USER',
        name: '홍길동',
        phoneNumber: null,
        active: true,
        createdAt: '2026-01-01T00:00:00Z',
      },
    });

    const result = await userApi.me();

    expect(api.get).toHaveBeenCalledWith('/users/me');
    expect(result.id).toBe(7);
  });

  it('미인증이면 401 이 전파된다', async () => {
    vi.mocked(api.get).mockRejectedValueOnce({ response: { status: 401 } });

    await expect(userApi.me()).rejects.toMatchObject({ response: { status: 401 } });
  });
});
