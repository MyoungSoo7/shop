import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BatchRunConsolePage from '@/pages/system/BatchRunConsolePage';
import {
  batchRunApi,
  type BatchRunView,
  type RerunnableBatchView,
} from '@/api/batchRun';

vi.mock('@/api/batchRun', () => ({
  batchRunApi: {
    search: vi.fn(),
    latest: vi.fn(),
    rerunnable: vi.fn(),
    rerun: vi.fn(),
  },
}));

const mocked = vi.mocked(batchRunApi);

/** 상대 시각으로 만든다 — 고정 문자열은 달력이 지나가면 스스로 stale 이 되어 테스트를 흔든다. */
const hoursAgo = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString();

const run = (over: Partial<BatchRunView> = {}): BatchRunView => ({
  id: 1,
  batchName: 'point-lot-expiry',
  runId: 'r-1',
  targetDate: '2026-09-01',
  status: 'SUCCEEDED',
  startedAt: hoursAgo(1),
  completedAt: hoursAgo(1),
  processedCount: 12,
  errorMessage: null,
  triggeredBy: 'scheduler',
  ...over,
});

const rerunnable = (over: Partial<RerunnableBatchView> = {}): RerunnableBatchView => ({
  batchName: 'point-lot-expiry',
  description: '포인트 로트 만료',
  supportsDryRun: true,
  ...over,
});

const page = (content: BatchRunView[]) => ({
  content,
  number: 0,
  size: 50,
  totalElements: content.length,
  totalPages: 1,
});

beforeEach(() => {
  vi.clearAllMocks();
  mocked.latest.mockResolvedValue([run()]);
  mocked.search.mockResolvedValue(page([run()]));
  mocked.rerunnable.mockResolvedValue([rerunnable()]);
});

/**
 * 배치 실행 원장 콘솔.
 *
 * <p>이 화면이 ShedLock 테이블 덤프와 다른 지점만 검증한다 — <b>끝을 못 본 실행</b>의 구분,
 * 마지막 성공이 오래된 배치의 집계, 미리보기 기본값, 그리고 조회 실패를 빈 표로 위장하지 않는 것.
 */
describe('BatchRunConsolePage — 배치별 최근 실행', () => {
  it('RUNNING 은 성공도 실패도 아닌 "끝을 못 봄" 으로 그린다 — "-" 는 종료 시각 없는 성공으로 읽힌다', async () => {
    mocked.latest.mockResolvedValue([run({ status: 'RUNNING', completedAt: null, processedCount: null })]);
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('unfinished-point-lot-expiry')).toHaveTextContent('끝을 못 봄');
  });

  it('오래된 마지막 실행을 센다 — 26시간이 기준선이다', async () => {
    mocked.latest.mockResolvedValue([
      run({ batchName: 'fresh', startedAt: hoursAgo(2) }),
      run({ batchName: 'stale', startedAt: hoursAgo(27) }),
    ]);
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('stale-summary')).toHaveTextContent('1개');
    expect(screen.getByTestId('latest-row-stale')).toHaveClass('bg-red-50');
    expect(screen.getByTestId('latest-row-fresh')).not.toHaveClass('bg-red-50');
  });

  it('최근에 돌았어도 성공이 아니면 오래된 것과 같이 센다 — 실패는 시간이 지나서 문제인 게 아니다', async () => {
    mocked.latest.mockResolvedValue([run({ status: 'FAILED', startedAt: hoursAgo(1) })]);
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('stale-summary')).toHaveTextContent('1개');
  });

  it('전부 최근에 성공했으면 그렇다고 말한다', async () => {
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('stale-summary')).toHaveTextContent('모든 배치가 최근 26시간 안에 성공했습니다.');
  });

  it('조회 실패를 빈 표로 위장하지 않는다', async () => {
    mocked.latest.mockRejectedValue({ response: { data: { message: '원장 조회 실패' } } });
    render(<BatchRunConsolePage />);

    const alerts = await screen.findAllByRole('alert');
    expect(alerts.some((a) => a.textContent?.includes('원장 조회 실패'))).toBe(true);
    expect(screen.queryByTestId('latest-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('latest-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stale-summary')).not.toBeInTheDocument();
  });

  it('기록이 0건인 것과 조회 실패는 다른 화면이다', async () => {
    mocked.latest.mockResolvedValue([]);
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('latest-empty')).toBeInTheDocument();
  });
});

describe('BatchRunConsolePage — 재실행', () => {
  it('미리보기가 기본값이다 — 서버 기본값과 반대다', async () => {
    render(<BatchRunConsolePage />);

    expect(await screen.findByLabelText('미리보기만 실행')).toBeChecked();
    expect(screen.getByRole('button', { name: '미리보기' })).toBeInTheDocument();
  });

  it('미리보기를 지원하지 않는 배치를 고르면 체크박스를 잠근다', async () => {
    mocked.rerunnable.mockResolvedValue([rerunnable({ batchName: 'gift-claim-expiry', supportsDryRun: false, description: '선물 수령권 만료' })]);
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);

    await user.selectOptions(await screen.findByLabelText('재실행할 배치'), 'gift-claim-expiry');

    expect(screen.getByLabelText('미리보기만 실행')).toBeDisabled();
    expect(screen.getByTestId('rerun-description')).toHaveTextContent('이 배치는 미리보기를 지원하지 않습니다.');
  });

  it('미리보기 결과는 "아무것도 바뀌지 않았습니다" 로 말하고 원장을 다시 읽지 않는다', async () => {
    mocked.rerun.mockResolvedValue({ batchName: 'point-lot-expiry', targetDate: '2026-09-01', dryRun: true, processedCount: 3 });
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);

    await user.selectOptions(await screen.findByLabelText('재실행할 배치'), 'point-lot-expiry');
    const before = mocked.latest.mock.calls.length;
    await user.click(screen.getByRole('button', { name: '미리보기' }));

    expect(await screen.findByTestId('rerun-result')).toHaveTextContent('아무것도 바뀌지 않았습니다');
    expect(mocked.latest.mock.calls.length).toBe(before);
  });

  it('실제 재실행 뒤에는 원장을 다시 읽는다 — 방금 늘어난 줄이 화면에 없으면 안 된다', async () => {
    mocked.rerun.mockResolvedValue({ batchName: 'point-lot-expiry', targetDate: '2026-09-01', dryRun: false, processedCount: 9 });
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);

    await user.selectOptions(await screen.findByLabelText('재실행할 배치'), 'point-lot-expiry');
    await user.click(screen.getByLabelText('미리보기만 실행'));
    const before = mocked.latest.mock.calls.length;
    await user.click(screen.getByRole('button', { name: '실제 재실행' }));

    expect(await screen.findByTestId('rerun-result')).toHaveTextContent('재실행 완료');
    await waitFor(() => expect(mocked.latest.mock.calls.length).toBeGreaterThan(before));
  });

  it('요청은 고른 배치·날짜·미리보기 여부 그대로 나간다', async () => {
    mocked.rerun.mockResolvedValue({ batchName: 'point-lot-expiry', targetDate: '2026-08-30', dryRun: true, processedCount: 0 });
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);

    await user.selectOptions(await screen.findByLabelText('재실행할 배치'), 'point-lot-expiry');
    await user.clear(screen.getByLabelText('재실행 대상 날짜'));
    await user.type(screen.getByLabelText('재실행 대상 날짜'), '2026-08-30');
    await user.click(screen.getByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(mocked.rerun).toHaveBeenCalledWith('point-lot-expiry', '2026-08-30', true));
  });

  it('재실행 실패는 문구로 보여 준다', async () => {
    mocked.rerun.mockRejectedValue({ response: { data: { message: '이 배치는 dry-run 을 지원하지 않는다' } } });
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);

    await user.selectOptions(await screen.findByLabelText('재실행할 배치'), 'point-lot-expiry');
    await user.click(screen.getByRole('button', { name: '미리보기' }));

    const alerts = await screen.findAllByRole('alert');
    expect(alerts.some((a) => a.textContent?.includes('dry-run'))).toBe(true);
    expect(screen.queryByTestId('rerun-result')).not.toBeInTheDocument();
  });

  it('배치를 고르지 않으면 실행 버튼이 잠겨 있다', async () => {
    render(<BatchRunConsolePage />);

    await screen.findByLabelText('재실행할 배치');
    expect(screen.getByRole('button', { name: '미리보기' })).toBeDisabled();
  });

  it('재실행 가능 목록 조회가 실패해도 화면은 성립한다 — 조회는 그대로 된다', async () => {
    mocked.rerunnable.mockRejectedValue(new Error('boom'));
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('latest-table')).toBeInTheDocument();
    expect(screen.getByTestId('history-table')).toBeInTheDocument();
  });
});

describe('BatchRunConsolePage — 실행 이력', () => {
  it('빈 필터는 서버로 보내지 않는다 — 빈 문자열은 "전체" 가 아니라 값이다', async () => {
    render(<BatchRunConsolePage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalledWith({
      batchName: undefined, status: undefined, targetDate: undefined, size: 50,
    }));
  });

  it('상태 필터가 그대로 실린다', async () => {
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);
    await screen.findByTestId('history-table');

    await user.selectOptions(screen.getByLabelText('상태로 거르기'), 'FAILED');

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({
      batchName: undefined, status: 'FAILED', targetDate: undefined, size: 50,
    }));
  });

  it('전체 건수와 표시 건수를 함께 보여 준다 — 50건 상한이 잘렸는지 보이게', async () => {
    mocked.search.mockResolvedValue({ ...page([run({ id: 1 }), run({ id: 2 })]), totalElements: 137 });
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('history-count')).toHaveTextContent('총 137건 중 2건');
    expect(screen.getByTestId('history-row-1')).toBeInTheDocument();
  });

  it('이력 조회 실패도 빈 표가 아니다', async () => {
    mocked.search.mockRejectedValue({ response: { data: { message: '이력 조회 실패' } } });
    render(<BatchRunConsolePage />);

    const alerts = await screen.findAllByRole('alert');
    expect(alerts.some((a) => a.textContent?.includes('이력 조회 실패'))).toBe(true);
    expect(screen.queryByTestId('history-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('history-empty')).not.toBeInTheDocument();
  });

  it('조건에 맞는 게 없으면 그렇다고 말한다', async () => {
    mocked.search.mockResolvedValue(page([]));
    render(<BatchRunConsolePage />);

    expect(await screen.findByTestId('history-empty')).toBeInTheDocument();
  });

  it('새로고침은 두 조회를 다시 부른다', async () => {
    const user = userEvent.setup();
    render(<BatchRunConsolePage />);
    await screen.findByTestId('latest-table');
    const latestBefore = mocked.latest.mock.calls.length;
    const searchBefore = mocked.search.mock.calls.length;

    await user.click(screen.getByRole('button', { name: '새로고침' }));

    await waitFor(() => {
      expect(mocked.latest.mock.calls.length).toBeGreaterThan(latestBefore);
      expect(mocked.search.mock.calls.length).toBeGreaterThan(searchBefore);
    });
  });

  it('처리 건수가 null 이면 0 이 아니라 모른다는 뜻이다', async () => {
    mocked.search.mockResolvedValue(page([run({ id: 5, processedCount: null, status: 'FAILED', errorMessage: 'DB 끊김' })]));
    render(<BatchRunConsolePage />);

    const row = await screen.findByTestId('history-row-5');
    expect(row).toHaveTextContent('-');
    expect(row).toHaveTextContent('DB 끊김');
  });
});
