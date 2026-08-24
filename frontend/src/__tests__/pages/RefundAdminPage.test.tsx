import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import RefundAdminPage from '@/pages/RefundAdminPage';
import { refundAdminApi, type AdminRefundItem } from '@/api/refundAdmin';

vi.mock('@/api/refundAdmin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/refundAdmin')>();
  return { ...actual, refundAdminApi: { byStatus: vi.fn(), historyOf: vi.fn() } };
});

const mocked = vi.mocked(refundAdminApi);

const refund = (over: Partial<AdminRefundItem> = {}): AdminRefundItem => ({
  id: 1, paymentId: 500, amount: 12000, status: 'FAILED',
  retryCount: 2, retryExhausted: false, nextRetryAt: '2026-08-22T10:00:00',
  idempotencyKey: 'k-1', reason: 'PG timeout',
  requestedAt: '2026-08-22T09:00:00', completedAt: null, ...over,
});

const exhausted = refund({ id: 2, paymentId: 501, retryCount: 5, retryExhausted: true, nextRetryAt: null });

beforeEach(() => vi.clearAllMocks());

/**
 * 이 화면의 존재 이유는 <b>FAILED 를 둘로 가르는 것</b>이다.
 *
 * <p>{@code retryExhausted=false} 는 스케줄러가 곧 다시 시도할 건이고, {@code true} 는 상한 5회를
 * 다 써서 <b>아무도 다시 시도하지 않는</b> 건이다. 한 덩어리로 보여 주면 운영자가 "곧 알아서 될 것"과
 * "지금 내가 안 하면 영영 안 될 것"을 구분할 수 없다.
 *
 * <p>두 번째 규율: <b>조회 실패와 0건을 뭉개지 않는다</b>. 빈 표는 실패를 "환불 실패 없음"으로
 * 위장시키는데, 이 화면에서 그 오해는 밀린 건을 못 본 채 넘어가게 만든다.
 */
describe('RefundAdminPage — 재시도 소진 구분', () => {
  it('소진된 건에 배지를 달고 "자동 재시도 안 함"을 명시한다', async () => {
    mocked.byStatus.mockResolvedValue([refund(), exhausted]);
    render(<RefundAdminPage />);

    await waitFor(() => expect(screen.getByTestId('refund-row-2')).toBeInTheDocument());

    expect(screen.getByTestId('exhausted-2')).toHaveTextContent('재시도 끝');
    expect(screen.getByTestId('next-retry-2')).toHaveTextContent('자동 재시도 안 함');
    // 아직 재시도가 남은 건에는 배지가 없고 다음 시각이 보인다.
    expect(screen.queryByTestId('exhausted-1')).not.toBeInTheDocument();
    expect(screen.getByTestId('next-retry-1')).not.toHaveTextContent('자동 재시도 안 함');
  });

  it('사람이 필요한 건수를 머리에 요약한다', async () => {
    mocked.byStatus.mockResolvedValue([refund(), exhausted]);
    render(<RefundAdminPage />);

    await waitFor(() => expect(screen.getByTestId('needs-human')).toHaveTextContent('1건'));
  });

  it('소진된 건이 없으면 그렇다고 말한다 — 침묵하면 확인했는지 알 수 없다', async () => {
    mocked.byStatus.mockResolvedValue([refund()]);
    render(<RefundAdminPage />);

    await waitFor(() =>
      expect(screen.getByTestId('needs-human')).toHaveTextContent('재시도가 끝난 건이 없습니다'));
  });

  it('실패 탭이 아니면 소진 요약을 띄우지 않는다', async () => {
    mocked.byStatus.mockResolvedValue([]);
    render(<RefundAdminPage />);
    await waitFor(() => expect(screen.getByTestId('refund-empty')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('tab', { name: '완료' }));

    await waitFor(() => expect(mocked.byStatus).toHaveBeenCalledWith('COMPLETED'));
    expect(screen.queryByTestId('needs-human')).not.toBeInTheDocument();
  });
});

describe('RefundAdminPage — 조회 실패와 0건', () => {
  it('조회가 실패하면 빈 표가 아니라 오류를 보여 준다', async () => {
    mocked.byStatus.mockRejectedValue(new Error('boom'));
    render(<RefundAdminPage />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByTestId('refund-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('refund-empty')).not.toBeInTheDocument();
  });

  it('0건이면 없다고 말한다', async () => {
    mocked.byStatus.mockResolvedValue([]);
    render(<RefundAdminPage />);

    await waitFor(() => expect(screen.getByTestId('refund-empty')).toBeInTheDocument());
  });

  it('기본 탭은 실패다 — 열자마자 밀린 건을 본다', async () => {
    mocked.byStatus.mockResolvedValue([]);
    render(<RefundAdminPage />);

    await waitFor(() => expect(mocked.byStatus).toHaveBeenCalledWith('FAILED'));
  });
});

describe('RefundAdminPage — 결제별 이력', () => {
  it('이력을 펼치면 실제 환불 완료액을 먼저 보여 준다', async () => {
    mocked.byStatus.mockResolvedValue([refund()]);
    mocked.historyOf.mockResolvedValue({
      paymentId: 500, totalRefunded: 12000,
      refunds: [
        { id: 1, amount: 12000, status: 'FAILED', idempotencyKey: 'k-1', reason: 'PG timeout', requestedAt: '2026-08-22T09:00:00', completedAt: null },
        { id: 3, amount: 12000, status: 'COMPLETED', idempotencyKey: 'k-1', reason: null, requestedAt: '2026-08-22T09:05:00', completedAt: '2026-08-22T09:06:00' },
      ],
    });
    render(<RefundAdminPage />);
    await waitFor(() => expect(screen.getByTestId('refund-row-1')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '이력' }));

    // 시도 횟수가 아니라 "실제로 나간 돈"이 이중 환불 판단의 기준이다.
    expect(await screen.findByTestId('total-refunded')).toHaveTextContent('12,000');
    expect(mocked.historyOf).toHaveBeenCalledWith(500);
  });

  it('탭을 바꾸면 펼친 이력을 닫는다 — 다른 목록 위에 남으면 어느 건인지 모른다', async () => {
    mocked.byStatus.mockResolvedValue([refund()]);
    mocked.historyOf.mockResolvedValue({ paymentId: 500, totalRefunded: 0, refunds: [] });
    render(<RefundAdminPage />);
    await waitFor(() => expect(screen.getByTestId('refund-row-1')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '이력' }));
    await waitFor(() => expect(screen.getByTestId('history-500')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('tab', { name: '요청됨' }));

    await waitFor(() => expect(screen.queryByTestId('history-500')).not.toBeInTheDocument());
  });
});

describe('RefundAdminPage — 조작 버튼', () => {
  it('재시도·집행 버튼이 없다 — 서버에 운영자용 재시도 API 가 없다', async () => {
    mocked.byStatus.mockResolvedValue([exhausted]);
    render(<RefundAdminPage />);
    await waitFor(() => expect(screen.getByTestId('refund-row-2')).toBeInTheDocument());

    // 고객용 환불 요청을 버튼으로 재활용하면 멱등 키 의미가 어긋나 이중 환불이 될 수 있다.
    expect(screen.queryByRole('button', { name: /재시도|환불 실행|집행/ })).not.toBeInTheDocument();
  });
});
