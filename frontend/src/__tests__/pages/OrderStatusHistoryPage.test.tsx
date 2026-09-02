import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OrderStatusHistoryPage from '@/pages/system/OrderStatusHistoryPage';
import { orderStatusHistoryApi, type OrderStatusTimeline, type StatusStep } from '@/api/orderStatusHistory';

vi.mock('@/api/orderStatusHistory', () => ({
  orderStatusHistoryApi: { of: vi.fn() },
}));

const mocked = vi.mocked(orderStatusHistoryApi);

/** {@code LONG_DWELL_SECONDS} 와 같은 값. 상수를 import 하면 임계치가 바뀌어도 테스트가 따라 움직여 아무것도 못 잡는다. */
const LONG = 24 * 3_600;

const step = (over: Partial<StatusStep> = {}): StatusStep => ({
  id: 1,
  previousStatus: null,
  newStatus: 'CREATED',
  changedBy: 'system',
  reason: null,
  changedAt: '2026-09-01T01:00:00Z',
  dwellSeconds: 30,
  ...over,
});

const timeline = (over: Partial<OrderStatusTimeline> = {}): OrderStatusTimeline => ({
  orderId: 4242,
  currentStatus: 'PAID',
  lastRecordedStatus: 'PAID',
  historyMatchesOrder: true,
  steps: [step()],
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  mocked.of.mockResolvedValue(timeline());
});

const lookup = async (id: string) => {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('주문번호'), id);
  await user.click(screen.getByRole('button', { name: '조회' }));
};

/**
 * 주문 상태 이력 화면.
 *
 * <p>이 화면이 표 덤프와 다른 지점만 검증한다 — 체류 시간, 이력·주문 상태 불일치 배너,
 * 그리고 <b>없는 주문과 이력 0건의 구분</b>. 표가 그려지는지는 이 화면의 존재 이유가 아니다.
 */
describe('OrderStatusHistoryPage — 입력 검증', () => {
  it('양의 정수가 아니면 서버를 부르지 않는다 — 400 을 왕복해서 받아올 이유가 없다', async () => {
    render(<OrderStatusHistoryPage />);

    await lookup('0');

    expect(await screen.findByRole('alert')).toHaveTextContent('주문번호는 양의 정수입니다.');
    expect(mocked.of).not.toHaveBeenCalled();
  });

  it('소수도 거절한다', async () => {
    render(<OrderStatusHistoryPage />);

    await lookup('1.5');

    expect(await screen.findByRole('alert')).toHaveTextContent('주문번호는 양의 정수입니다.');
    expect(mocked.of).not.toHaveBeenCalled();
  });

  it('빈 입력이면 조회 버튼이 비활성이다', () => {
    render(<OrderStatusHistoryPage />);

    expect(screen.getByRole('button', { name: '조회' })).toBeDisabled();
  });
});

describe('OrderStatusHistoryPage — 없는 주문 vs 이력 0건', () => {
  it('404 는 "없는 주문" 으로 따로 보여 준다 — 오타와 조사할 신호를 뭉개지 않는다', async () => {
    mocked.of.mockRejectedValue({ response: { status: 404 } });
    render(<OrderStatusHistoryPage />);

    await lookup('999');

    expect(await screen.findByTestId('order-not-found')).toBeInTheDocument();
    expect(screen.queryByTestId('timeline-empty')).not.toBeInTheDocument();
  });

  it('이력 0건은 표가 아니라 조사 신호다 — 없는 주문과 다른 화면이다', async () => {
    mocked.of.mockResolvedValue(timeline({ lastRecordedStatus: null, historyMatchesOrder: false, steps: [] }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('timeline-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('order-not-found')).not.toBeInTheDocument();
    expect(screen.getByTestId('match-summary')).toHaveTextContent('이력이 한 건도 없습니다.');
  });

  it('404 가 아닌 오류는 문구로 보여 준다', async () => {
    mocked.of.mockRejectedValue({ response: { status: 500, data: { message: 'DB 연결 끊김' } } });
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByRole('alert')).toHaveTextContent('DB 연결 끊김');
    expect(screen.queryByTestId('order-not-found')).not.toBeInTheDocument();
  });
});

describe('OrderStatusHistoryPage — 불일치 배너', () => {
  it('일치하면 일치한다고 말한다', async () => {
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('match-summary'))
      .toHaveTextContent('이력의 마지막 도착 상태가 주문의 현재 상태와 일치합니다.');
    expect(screen.getByTestId('current-status')).toHaveTextContent('PAID');
    expect(screen.getByTestId('order-id')).toHaveTextContent('4242');
  });

  it('어긋나면 이력의 마지막 도착 상태를 짚어 준다 — 표만 보면 안 보이는 사실이다', async () => {
    mocked.of.mockResolvedValue(timeline({
      currentStatus: 'DELIVERED',
      lastRecordedStatus: 'SHIPPED',
      historyMatchesOrder: false,
    }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('match-summary')).toHaveTextContent('이력과 주문 상태가 어긋납니다.');
    expect(screen.getByTestId('last-recorded')).toHaveTextContent('SHIPPED');
  });

  it('현재 상태가 null 이어도 화면이 비지 않는다', async () => {
    mocked.of.mockResolvedValue(timeline({ currentStatus: null, lastRecordedStatus: null, historyMatchesOrder: false, steps: [] }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('current-status')).toHaveTextContent('없음');
  });
});

describe('OrderStatusHistoryPage — 체류 시간', () => {
  it('오래 머문 칸을 눈에 띄게 표시한다', async () => {
    mocked.of.mockResolvedValue(timeline({
      steps: [
        step({ id: 1, newStatus: 'CREATED', dwellSeconds: LONG }),
        step({ id: 2, previousStatus: 'CREATED', newStatus: 'PAID', dwellSeconds: 30 }),
      ],
    }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('step-1')).toHaveClass('bg-amber-50');
    expect(screen.getByTestId('step-2')).not.toHaveClass('bg-amber-50');
    expect(screen.getByTestId('dwell-1')).toHaveTextContent('1일');
  });

  it('마지막 칸은 "지금 몇 초째" 라 성격이 다르다고 밝힌다', async () => {
    mocked.of.mockResolvedValue(timeline({
      steps: [
        step({ id: 1, newStatus: 'CREATED', dwellSeconds: 30 }),
        step({ id: 2, previousStatus: 'CREATED', newStatus: 'PAID', dwellSeconds: 90 }),
      ],
    }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('dwell-2')).toHaveTextContent('(현재 진행 중)');
    expect(screen.getByTestId('dwell-1')).not.toHaveTextContent('(현재 진행 중)');
  });

  it('상태값을 번역하지 않는다 — 지금 코드가 모르는 옛 값도 원문 그대로 나온다', async () => {
    mocked.of.mockResolvedValue(timeline({
      currentStatus: 'LEGACY_HOLD',
      lastRecordedStatus: 'LEGACY_HOLD',
      steps: [step({ id: 9, newStatus: 'LEGACY_HOLD' })],
    }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByTestId('step-9')).toHaveTextContent('LEGACY_HOLD');
  });

  it('조회 중에는 버튼 문구가 바뀐다', async () => {
    let release: (v: OrderStatusTimeline) => void = () => {};
    mocked.of.mockReturnValue(new Promise<OrderStatusTimeline>((res) => { release = res; }));
    render(<OrderStatusHistoryPage />);

    await lookup('4242');

    expect(await screen.findByRole('button', { name: '조회 중…' })).toBeDisabled();
    release(timeline());
    await waitFor(() => expect(screen.getByRole('button', { name: '조회' })).toBeEnabled());
  });
});
