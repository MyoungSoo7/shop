import { describe, it, expect, vi, beforeEach } from 'vitest';
import { opsDashboardApi, type TodayOverview } from '@/api/opsDashboard';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

const overview: TodayOverview = {
  date: '2026-08-25',
  zone: 'Asia/Seoul',
  asOf: '2026-08-25T01:20:00Z',
  metrics: [
    {
      key: 'ORDER_CREATED',
      label: '오늘 주문',
      count: 12,
      amount: '540000',
      hasAmount: true,
      amountComplete: true,
      amountUnknownCount: 0,
    },
    {
      key: 'USER_REGISTERED',
      label: '신규 가입',
      count: 3,
      amount: null,
      hasAmount: false,
      amountComplete: true,
      amountUnknownCount: 0,
    },
  ],
  openIncidents: 1,
  failedDispatches: 0,
};

describe('opsDashboardApi', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
  });

  it('오늘은 파라미터 없이 부른다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: overview });

    await expect(opsDashboardApi.today()).resolves.toEqual(overview);
    expect(api.get).toHaveBeenCalledWith('/api/ops/dashboard/today', { params: undefined });
  });

  it('날짜를 주면 그 날짜로 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: overview });

    await opsDashboardApi.today('2026-08-24');

    expect(api.get).toHaveBeenCalledWith('/api/ops/dashboard/today', {
      params: { date: '2026-08-24' },
    });
  });

  /**
   * 금액은 문자열로 온다(서버가 BigDecimal 을 그대로 직렬화). 숫자로 받으면 큰 값에서
   * 정밀도가 새기 때문인데, 화면이 그걸 잊고 문자열을 더하면 "540000300" 같은 값이 나온다.
   */
  it('금액은 문자열이고 금액 없는 지표는 null 이다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: overview });

    const result = await opsDashboardApi.today();

    expect(typeof result.metrics[0].amount).toBe('string');
    expect(result.metrics[1].amount).toBeNull();
    expect(result.metrics[1].hasAmount).toBe(false);
  });
});
