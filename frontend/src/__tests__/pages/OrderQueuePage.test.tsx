import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OrderQueuePage from '@/pages/system/OrderQueuePage';
import { orderQueueApi, type QueueBucket } from '@/api/orderQueue';

vi.mock('@/api/orderQueue', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/orderQueue')>();
  return { ...actual, orderQueueApi: { list: vi.fn(), export: vi.fn() } };
});
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(orderQueueApi);

const bucket = (over: Partial<QueueBucket> = {}): QueueBucket => ({
  key: 'REFUND_REQUESTED', label: '환불 신청', statuses: ['REFUND_REQUESTED'],
  count: 3, oldestWaitingSince: '2026-08-20T09:00:00', oldestWaitingHours: 50,
  slaHours: 24, overdueCount: 1, ageFromOrderDateCount: 0, ...over,
});

const queues = (buckets: QueueBucket[], asOf = '2026-08-23T11:00:00') => ({ asOf, buckets });

beforeEach(() => vi.clearAllMocks());

/**
 * 이 화면의 존재 이유는 <b>건수만으로는 급한지 알 수 없다</b>는 것이다. 환불 신청 3건이 오늘
 * 들어온 것과 나흘 묵은 것은 전혀 다른 상황인데 상태별 카운트에서는 똑같이 "3"이다.
 *
 * <p>그래서 검증의 초점은 기한 초과와 대기 시간이 살아 있는가, 그리고 <b>서버가 정한 순서</b>가
 * 화면에서 뒤집히지 않는가다 — 순서가 곧 우선순위다.
 */
describe('OrderQueuePage — 우선순위는 서버가 정한다', () => {
  it('건수가 적어도 서버 순서를 그대로 유지한다', async () => {
    mocked.list.mockResolvedValue(queues([
      bucket({ key: 'CANCELLATION_APPROVED', label: '취소 승인 후 미완료', count: 1, overdueCount: 1 }),
      bucket({ key: 'AWAITING_SHIPMENT', label: '발송 대기', count: 90, overdueCount: 0, slaHours: 48 }),
    ]));
    render(<OrderQueuePage />);
    await waitFor(() => expect(screen.getByTestId('queue-table')).toBeInTheDocument());

    // 건수 내림차순으로 다시 정렬하면 건수는 적지만 급한 큐가 아래로 밀린다.
    const keys = screen.getAllByTestId(/^queue-row-/).map((row) => row.getAttribute('data-testid'));
    expect(keys).toEqual(['queue-row-CANCELLATION_APPROVED', 'queue-row-AWAITING_SHIPMENT']);
  });

  it('기한 초과 총합을 머리에 먼저 요약한다', async () => {
    mocked.list.mockResolvedValue(queues([
      bucket({ key: 'A', count: 3, overdueCount: 1 }),
      bucket({ key: 'B', count: 5, overdueCount: 2 }),
    ]));
    render(<OrderQueuePage />);

    await waitFor(() => expect(screen.getByTestId('queue-summary')).toHaveTextContent('3건'));
    expect(screen.getByTestId('queue-summary')).toHaveTextContent('8건');
  });

  it('기한 초과가 없으면 없다고 말한다 — 침묵하면 확인했는지 알 수 없다', async () => {
    mocked.list.mockResolvedValue(queues([bucket({ overdueCount: 0 })]));
    render(<OrderQueuePage />);

    await waitFor(() =>
      expect(screen.getByTestId('queue-summary')).toHaveTextContent('기한을 넘긴 건이 없습니다'));
  });

  it('대기 시간을 일·시간으로 풀어 쓴다', async () => {
    mocked.list.mockResolvedValue(queues([bucket({ key: 'A', oldestWaitingHours: 50 })]));
    render(<OrderQueuePage />);

    await waitFor(() => expect(screen.getByTestId('waiting-A')).toHaveTextContent('2일 2시간'));
  });

  it('0건인 큐는 최장 대기가 없다 — 0시간으로 꾸미지 않는다', async () => {
    mocked.list.mockResolvedValue(queues([
      bucket({ key: 'A', count: 0, overdueCount: 0, oldestWaitingSince: null, oldestWaitingHours: null }),
    ]));
    render(<OrderQueuePage />);

    await waitFor(() => expect(screen.getByTestId('waiting-A')).toHaveTextContent('-'));
  });
});

describe('OrderQueuePage — 추정된 대기 시간', () => {
  it('주문 일시로 대신 잰 건이 있으면 과대평가라고 표시한다', async () => {
    mocked.list.mockResolvedValue(queues([
      bucket({ key: 'A', ageFromOrderDateCount: 2 }),
      bucket({ key: 'UNPAID', ageFromOrderDateCount: 0 }),
    ]));
    render(<OrderQueuePage />);
    await waitFor(() => expect(screen.getByTestId('queue-table')).toBeInTheDocument());

    expect(screen.getByTestId('estimated-A')).toHaveTextContent('과대평가');
    // 대체가 정확한 큐에는 배지를 달지 않는다 — 늘 켜진 경고는 아무도 읽지 않는다.
    expect(screen.queryByTestId('estimated-UNPAID')).not.toBeInTheDocument();
  });
});

describe('OrderQueuePage — 조회 실패와 CSV', () => {
  it('조회가 실패하면 빈 표가 아니라 오류를 보여 준다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));
    render(<OrderQueuePage />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // 빈 표를 그리면 조회 실패가 "밀린 일 0건"으로 위장한다.
    expect(screen.queryByTestId('queue-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('queue-summary')).not.toBeInTheDocument();
  });

  it('내려받은 파일의 기준 시각을 함께 말한다 — 큐는 계속 움직인다', async () => {
    mocked.list.mockResolvedValue(queues([bucket()]));
    mocked.export.mockResolvedValue({
      blob: new Blob(['a']), fileName: 'order_queues.csv', asOf: '2026-08-23T11:00:00',
    });
    render(<OrderQueuePage />);
    await waitFor(() => expect(screen.getByTestId('queue-table')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'CSV' }));

    expect(await screen.findByTestId('queue-notice')).toHaveTextContent('기준 시각');
  });

  it('기준 시각 헤더가 없으면 아는 척하지 않는다', async () => {
    mocked.list.mockResolvedValue(queues([bucket()]));
    mocked.export.mockResolvedValue({
      blob: new Blob(['a']), fileName: 'order_queues.csv', asOf: null,
    });
    render(<OrderQueuePage />);
    await waitFor(() => expect(screen.getByTestId('queue-table')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'CSV' }));

    expect(await screen.findByTestId('queue-notice')).toHaveTextContent('알 수 없음');
  });
});
