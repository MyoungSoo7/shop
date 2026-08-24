import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OperationConsolePage from '@/pages/operation/OperationConsolePage';
import { operationApi } from '@/api/operation';

const showToast = vi.fn();

// PG 라우터 상태 카드가 이 화면 안에 있다 — 막지 않으면 실제 axios 를 부르고, 그 결과에 따라
// 버튼 문구('조회 중…' ↔ 'PG 상태 새로고침')가 바뀌어 이 파일의 버튼 조회가 불안정해진다.
// 카드 자체의 규율은 PgRoutingHealthCard.test.tsx 가 따로 못박는다.
vi.mock('@/api/pgRouting', () => ({
  pgRoutingApi: { health: vi.fn().mockResolvedValue({ providers: {}, healthy: true }) },
}));

vi.mock('@/contexts/useToast', () => ({
  useToast: () => ({ showToast }),
}));

vi.mock('@/api/operation', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/operation')>();
  return {
    ...actual,
    operationApi: {
      search: vi.fn(),
      get: vi.fn(),
      summary: vi.fn(),
      acknowledge: vi.fn(),
      resolve: vi.fn(),
      markFalsePositive: vi.fn(),
      comment: vi.fn(),
    },
  };
});

const mocked = vi.mocked(operationApi);

const incident = (over: Record<string, unknown> = {}) =>
  ({
    id: 7,
    correlationKey: 'kafka-lag',
    source: 'ALERTMANAGER',
    category: 'KAFKA_BACKLOG',
    severity: 'CRITICAL',
    status: 'OPEN',
    title: 'settlement 컨슈머 lag 급증',
    service: 'settlement-service',
    firstSeenAt: '2026-08-14T01:00:00Z',
    lastSeenAt: '2026-08-14T01:10:00Z',
    occurrenceCount: 4,
    acknowledgedBy: null,
    acknowledgedAt: null,
    resolvedBy: null,
    resolvedAt: null,
    ...over,
  }) as never;

const detail = (over: Record<string, unknown> = {}) =>
  ({
    incident: incident(),
    description: '컨슈머 그룹 지연 3만건',
    labels: {},
    annotations: {},
    timeline: [{ eventType: 'OPENED', actor: 'alertmanager', note: null, createdAt: '2026-08-14T01:00:00Z' }],
    ...over,
  }) as never;

const page = (content = [incident()]) =>
  ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }) as never;

const summary = (over: Record<string, unknown> = {}) =>
  ({
    window: '24h',
    openTotal: 3,
    byStatus: { OPEN: 2, ACKNOWLEDGED: 1, RESOLVED: 5, FALSE_POSITIVE: 1 },
    byCategory: { KAFKA_BACKLOG: 2 },
    bySeverity: { CRITICAL: 1 },
    mttrSeconds: 3720,
    ...over,
  }) as never;

let promptSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mocked.search.mockResolvedValue(page());
  mocked.summary.mockResolvedValue(summary());
  mocked.get.mockResolvedValue(detail());
  promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('메모');
});

afterEach(() => promptSpy.mockRestore());

const renderAndWait = async () => {
  render(<OperationConsolePage />);
  await screen.findByText('settlement 컨슈머 lag 급증');
};

describe('OperationConsolePage — 요약·목록', () => {
  it('요약 카드와 목록을 함께 읽는다', async () => {
    await renderAndWait();

    expect(screen.getByText('활성 인시던트')).toBeInTheDocument();
    expect(screen.getByText('2 / 1')).toBeInTheDocument();
    expect(mocked.summary).toHaveBeenCalledWith('24h');
    expect(mocked.search).toHaveBeenCalledWith({ page: 0, size: 20 });
  });

  it('MTTR 을 시·분으로 읽기 좋게 바꾼다', async () => {
    await renderAndWait();

    expect(screen.getByText('1시간 2분')).toBeInTheDocument();
  });

  it('MTTR 이 없으면 - 로 표시한다', async () => {
    mocked.summary.mockResolvedValue(summary({ mttrSeconds: null }));
    await renderAndWait();

    expect(screen.getAllByText('-').length).toBeGreaterThan(0);
  });

  it('윈도를 바꾸면 그 기간으로 요약을 다시 읽는다', async () => {
    await renderAndWait();

    fireEvent.click(screen.getByRole('button', { name: '7d' }));

    await waitFor(() => expect(mocked.summary).toHaveBeenLastCalledWith('7d'));
  });

  it('목록이 비면 그 사실을 알린다', async () => {
    mocked.search.mockResolvedValue(page([]));
    render(<OperationConsolePage />);

    expect(await screen.findByText('조건에 맞는 인시던트가 없습니다.')).toBeInTheDocument();
  });

  it('목록 조회 실패는 토스트로 알린다', async () => {
    mocked.search.mockRejectedValue(new Error('down'));
    render(<OperationConsolePage />);

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('인시던트 목록을 불러오지 못했습니다.', 'error'),
    );
  });

  it('요약 조회 실패도 토스트로 알린다', async () => {
    mocked.summary.mockRejectedValue(new Error('down'));
    render(<OperationConsolePage />);

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('요약 정보를 불러오지 못했습니다.', 'error'),
    );
  });

  it('새로고침은 요약과 목록을 모두 다시 읽는다', async () => {
    await renderAndWait();

    fireEvent.click(screen.getByRole('button', { name: '새로고침' }));

    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));
    expect(mocked.summary).toHaveBeenCalledTimes(2);
  });
});

describe('OperationConsolePage — 필터·페이지', () => {
  it('상태 필터를 걸면 첫 페이지부터 다시 조회한다', async () => {
    await renderAndWait();
    const selects = screen.getAllByRole('combobox');

    fireEvent.change(selects[0], { target: { value: 'OPEN' } });

    await waitFor(() =>
      expect(mocked.search).toHaveBeenLastCalledWith({ status: 'OPEN', page: 0, size: 20 }),
    );
  });

  it('필터를 전체로 되돌리면 그 조건을 뺀다', async () => {
    await renderAndWait();
    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[0], { target: { value: 'OPEN' } });
    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));

    fireEvent.change(selects[0], { target: { value: '' } });

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({ page: 0, size: 20 }));
  });

  it('초기화 버튼은 모든 필터를 지운다', async () => {
    await renderAndWait();
    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[1], { target: { value: 'CRITICAL' } });
    await waitFor(() => expect(mocked.search).toHaveBeenCalledTimes(2));

    fireEvent.click(await screen.findByRole('button', { name: '필터 초기화' }));

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({ page: 0, size: 20 }));
  });

  it('페이지가 하나면 페이지네이션을 아예 그리지 않는다', async () => {
    await renderAndWait();

    expect(screen.queryByRole('button', { name: '이전' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument();
  });

  it('여러 페이지면 첫 페이지에서 이전이 잠긴다', async () => {
    mocked.search.mockResolvedValue({
      content: [incident()],
      page: 0,
      size: 20,
      totalElements: 40,
      totalPages: 2,
    } as never);
    await renderAndWait();

    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
  });

  it('다음 페이지를 요청하면 page 를 올려 조회한다', async () => {
    mocked.search.mockResolvedValue({
      content: [incident()],
      page: 0,
      size: 20,
      totalElements: 40,
      totalPages: 2,
    } as never);
    await renderAndWait();

    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    await waitFor(() => expect(mocked.search).toHaveBeenLastCalledWith({ page: 1, size: 20 }));
  });
});

describe('OperationConsolePage — 상세·조치', () => {
  const openDetail = async () => {
    await renderAndWait();
    fireEvent.click(screen.getByText('settlement 컨슈머 lag 급증'));
    await screen.findByText('타임라인');
  };

  it('행을 누르면 상세를 읽어 드로어에 그린다', async () => {
    await openDetail();

    expect(mocked.get).toHaveBeenCalledWith(7);
    expect(screen.getByText('컨슈머 그룹 지연 3만건')).toBeInTheDocument();
    expect(screen.getByText('생성')).toBeInTheDocument();
  });

  it('상세 조회 실패는 토스트로 알린다', async () => {
    mocked.get.mockRejectedValue(new Error('down'));
    await renderAndWait();

    fireEvent.click(screen.getByText('settlement 컨슈머 lag 급증'));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('상세 정보를 불러오지 못했습니다.', 'error'),
    );
  });

  it('OPEN 이면 확인(ACK)이 가능하고 메모를 물어 전달한다', async () => {
    mocked.acknowledge.mockResolvedValue(detail({ incident: incident({ status: 'ACKNOWLEDGED' }) }));
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '확인(ACK)' }));

    await waitFor(() => expect(mocked.acknowledge).toHaveBeenCalledWith(7, '메모'));
    expect(showToast).toHaveBeenCalledWith('확인 처리했습니다.', 'success');
  });

  it('메모를 비우면 note 없이 보낸다', async () => {
    promptSpy.mockReturnValue('   ');
    mocked.resolve.mockResolvedValue(detail({ incident: incident({ status: 'RESOLVED' }) }));
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '해결' }));

    await waitFor(() => expect(mocked.resolve).toHaveBeenCalledWith(7, undefined));
  });

  it('오탐 처리도 같은 경로를 탄다', async () => {
    mocked.markFalsePositive.mockResolvedValue(
      detail({ incident: incident({ status: 'FALSE_POSITIVE' }) }),
    );
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '오탐' }));

    await waitFor(() => expect(mocked.markFalsePositive).toHaveBeenCalledWith(7, '메모'));
  });

  it('상태 충돌(409)은 그 사실을 구분해 알린다', async () => {
    mocked.acknowledge.mockRejectedValue({ response: { status: 409 } });
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '확인(ACK)' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('현재 상태에서 처리할 수 없습니다 (상태 충돌).', 'error'),
    );
  });

  it('그 밖의 실패는 일반 문구로 알린다', async () => {
    mocked.acknowledge.mockRejectedValue(new Error('down'));
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '확인(ACK)' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('처리에 실패했습니다.', 'error'));
  });

  it('이미 해결된 인시던트는 확인·해결·오탐이 잠긴다', async () => {
    mocked.get.mockResolvedValue(detail({ incident: incident({ status: 'RESOLVED' }) }));
    await openDetail();

    expect(screen.getByRole('button', { name: '확인(ACK)' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '해결' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '오탐' })).toBeDisabled();
  });

  it('코멘트는 입력을 받아 추가한다', async () => {
    promptSpy.mockReturnValue('재발 감시 중');
    mocked.comment.mockResolvedValue(detail());
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '코멘트' }));

    await waitFor(() => expect(mocked.comment).toHaveBeenCalledWith(7, '재발 감시 중'));
    expect(showToast).toHaveBeenCalledWith('코멘트를 추가했습니다.', 'success');
  });

  it('코멘트 입력을 취소하면 아무것도 하지 않는다', async () => {
    promptSpy.mockReturnValue(null as never);
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '코멘트' }));

    expect(mocked.comment).not.toHaveBeenCalled();
  });

  it('빈 코멘트도 보내지 않는다', async () => {
    promptSpy.mockReturnValue('   ');
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '코멘트' }));

    expect(mocked.comment).not.toHaveBeenCalled();
  });

  it('코멘트 실패는 토스트로 알린다', async () => {
    promptSpy.mockReturnValue('노트');
    mocked.comment.mockRejectedValue(new Error('down'));
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '코멘트' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('코멘트 추가에 실패했습니다.', 'error'),
    );
  });

  it('런북 주석이 있으면 링크를 띄운다', async () => {
    mocked.get.mockResolvedValue(
      detail({ annotations: { runbook_url: 'https://runbook.example.com' } }),
    );
    await openDetail();

    expect(screen.getByRole('link', { name: /런북 열기/ })).toHaveAttribute(
      'href',
      'https://runbook.example.com',
    );
  });

  it('타임라인이 비면 그 사실을 알린다', async () => {
    mocked.get.mockResolvedValue(detail({ timeline: [] }));
    await openDetail();

    expect(screen.getByText('타임라인 없음')).toBeInTheDocument();
  });

  it('닫기 버튼으로 드로어를 닫는다', async () => {
    await openDetail();

    fireEvent.click(screen.getByRole('button', { name: '✕' }));

    await waitFor(() => expect(screen.queryByText('타임라인')).not.toBeInTheDocument());
  });
});
