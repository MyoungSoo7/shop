import { describe, it, expect, vi, beforeEach } from 'vitest';
import { orderStatusHistoryApi } from '@/api/orderStatusHistory';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 주문 상태 이력 API 클라이언트.
 *
 * <p>경로가 {@code /orders/admin/{id}/status-history} 인 것은 우연이 아니다. SecurityConfig 의
 * {@code /orders/admin/**} 와 게이트웨이의 {@code /orders/**} 라우트가 이미 이 접두사를 덮는다 —
 * {@code /admin/orders/...} 로 옮기면 두 곳에 줄을 더 넣어야 하고, 빠뜨린 쪽이 조용히 샌다.
 * 그래서 경로 모양 자체를 테스트가 잡아 둔다.
 */
describe('orderStatusHistoryApi', () => {
  it('주문번호를 경로에 넣어 조회한다 — 접두사가 /orders/admin 이다', async () => {
    mocked.get.mockResolvedValue({ data: { orderId: 42, currentStatus: 'PAID', lastRecordedStatus: 'PAID', historyMatchesOrder: true, steps: [] } } as never);

    await orderStatusHistoryApi.of(42);

    expect(mocked.get).toHaveBeenCalledWith('/orders/admin/42/status-history');
  });

  it('타임라인을 그대로 돌려준다 — 폐기된 상태값도 손대지 않는다', async () => {
    const timeline = {
      orderId: 7,
      currentStatus: 'DELIVERED',
      lastRecordedStatus: 'LEGACY_HOLD',
      historyMatchesOrder: false,
      steps: [
        { id: 1, previousStatus: null, newStatus: 'LEGACY_HOLD', changedBy: null, reason: null, changedAt: '2026-09-01T00:00:00Z', dwellSeconds: 30 },
      ],
    };
    mocked.get.mockResolvedValue({ data: timeline } as never);

    await expect(orderStatusHistoryApi.of(7)).resolves.toBe(timeline);
  });

  it('오류는 삼키지 않고 호출부로 올린다 — 404 와 이력 0건을 화면이 구분해야 한다', async () => {
    mocked.get.mockRejectedValue({ response: { status: 404 } });

    await expect(orderStatusHistoryApi.of(999)).rejects.toMatchObject({ response: { status: 404 } });
  });
});
