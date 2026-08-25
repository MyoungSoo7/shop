import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import MetricTrendPage from '@/pages/system/MetricTrendPage';
import { metricTrendApi, type MetricTrendResponse } from '@/api/metricTrend';

vi.mock('@/api/metricTrend', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/metricTrend')>();
  return { ...actual, metricTrendApi: { trend: vi.fn() } };
});

const mocked = vi.mocked(metricTrendApi);

const response = (over: Partial<MetricTrendResponse> = {}): MetricTrendResponse => ({
  from: '2026-07-25', to: '2026-08-23', zone: 'Asia/Seoul',
  asOf: '2026-08-23T09:00:00',
  metrics: ['ORDER'],
  series: [
    { date: '2026-08-22', metric: 'ORDER', count: 3, amount: 30000, amountComplete: true, amountUnknownCount: 0 },
    { date: '2026-08-23', metric: 'ORDER', count: 5, amount: 50000, amountComplete: true, amountUnknownCount: 0 },
  ],
  totals: [
    { metric: 'ORDER', label: '주문', count: 8, amount: 80000, hasAmount: true, amountComplete: true, amountUnknownCount: 0 },
  ],
  ...over,
});

beforeEach(() => vi.clearAllMocks());

/**
 * 이 화면의 존재 이유는 대시보드 카드에 <b>비교 대상</b>을 붙이는 것이다.
 *
 * <p>그래서 검증의 초점은 막대가 그려지는지가 아니라, <b>모르는 값을 아는 값처럼 그리지
 * 않는가</b>다 — 집계가 없는 기간의 {@code asOf} 와, 합계에서 빠진 금액.
 */
describe('MetricTrendPage — 모르는 값을 지어내지 않는다', () => {
  it('기간 안에 한 건도 없으면 조회 시각을 대신 찍지 않는다', async () => {
    mocked.trend.mockResolvedValue(response({ asOf: null, series: [], totals: [] }));
    render(<MetricTrendPage />);

    await waitFor(() => expect(screen.getByTestId('trend-as-of')).toHaveTextContent('아직 없음'));
    // 다른 시각을 찍으면 멈춘 집계가 "방금 갱신됨"으로 위장한다.
    expect(screen.getByTestId('trend-empty')).toBeInTheDocument();
  });

  it('금액 합계가 불완전하면 하한값이라고 못 박는다', async () => {
    mocked.trend.mockResolvedValue(response({
      totals: [{ metric: 'ORDER', label: '주문', count: 8, amount: 80000,
        hasAmount: true, amountComplete: false, amountUnknownCount: 2 }],
    }));
    render(<MetricTrendPage />);

    await waitFor(() => expect(screen.getByTestId('incomplete-ORDER')).toHaveTextContent('하한값'));
    expect(screen.getByTestId('incomplete-ORDER')).toHaveTextContent('2건');
  });

  it('합계가 완전하면 경고를 달지 않는다 — 늘 켜진 경고는 아무도 읽지 않는다', async () => {
    mocked.trend.mockResolvedValue(response());
    render(<MetricTrendPage />);

    await waitFor(() => expect(screen.getByTestId('trend-ORDER')).toBeInTheDocument());
    expect(screen.queryByTestId('incomplete-ORDER')).not.toBeInTheDocument();
  });
});

describe('MetricTrendPage — 지표 선택', () => {
  it('처음에는 지표를 지정하지 않는다 — 서버 기본값이 전 지표다', async () => {
    mocked.trend.mockResolvedValue(response());
    render(<MetricTrendPage />);

    await waitFor(() =>
      expect(mocked.trend).toHaveBeenCalledWith(expect.objectContaining({ metrics: [] })));
  });

  it('칩을 누르면 그 지표만 다시 조회한다', async () => {
    mocked.trend.mockResolvedValue(response());
    render(<MetricTrendPage />);
    await waitFor(() => expect(screen.getByTestId('metric-filters')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '주문' }));

    await waitFor(() =>
      expect(mocked.trend).toHaveBeenLastCalledWith(expect.objectContaining({ metrics: ['ORDER'] })));
  });

  it('걸러낸 뒤에도 칩이 남는다 — 응답에서만 목록을 만들면 되돌아갈 길이 사라진다', async () => {
    mocked.trend.mockResolvedValue(response({
      totals: [
        { metric: 'ORDER', label: '주문', count: 8, amount: 80000, hasAmount: true, amountComplete: true, amountUnknownCount: 0 },
        { metric: 'SIGNUP', label: '가입', count: 2, amount: null, hasAmount: false, amountComplete: true, amountUnknownCount: 0 },
      ],
    }));
    render(<MetricTrendPage />);
    await waitFor(() => expect(screen.getByRole('button', { name: '가입' })).toBeInTheDocument());

    mocked.trend.mockResolvedValue(response());   // 주문만 담긴 응답
    fireEvent.click(screen.getByRole('button', { name: '주문' }));

    await waitFor(() =>
      expect(mocked.trend).toHaveBeenLastCalledWith(expect.objectContaining({ metrics: ['ORDER'] })));
    expect(screen.getByRole('button', { name: '가입' })).toBeInTheDocument();
  });
});

describe('MetricTrendPage — 조회 실패', () => {
  it('실패하면 빈 그래프가 아니라 오류를 보여 준다', async () => {
    mocked.trend.mockRejectedValue(new Error('boom'));
    render(<MetricTrendPage />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // 빈 그래프는 실패를 "그 기간에 아무 일도 없었다"로 위장시킨다.
    expect(screen.queryByTestId('trend-as-of')).not.toBeInTheDocument();
    expect(screen.queryByTestId('trend-empty')).not.toBeInTheDocument();
  });
});
