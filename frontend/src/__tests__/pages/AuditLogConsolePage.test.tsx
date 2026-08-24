import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuditLogConsolePage from '@/pages/system/AuditLogConsolePage';
import { auditLogApi, saveBlob, type AuditLogPage } from '@/api/auditLog';

vi.mock('@/api/auditLog', () => ({
  auditLogApi: {
    search: vi.fn(),
    actionCounts: vi.fn(),
    actions: vi.fn(),
    export: vi.fn(),
  },
  saveBlob: vi.fn(),
}));

const mocked = vi.mocked(auditLogApi);
const mockedSave = vi.mocked(saveBlob);

const emptyPage: AuditLogPage = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 };

const pageWith = (overrides: Partial<AuditLogPage> = {}): AuditLogPage => ({
  ...emptyPage,
  content: [
    {
      id: 11,
      actorId: 7,
      actorEmail: 'admin@lemuel.io',
      action: 'USER_ROLE_CHANGED',
      resourceType: 'USER',
      resourceId: '42',
      detailJson: '{"before":"USER","after":"MANAGER"}',
      ipAddress: '10.0.0.1',
      userAgent: 'curl',
      createdAt: '2026-03-02T09:30:00',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  ...overrides,
});

describe('AuditLogConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.actions.mockResolvedValue(['LOGIN_FAILED', 'USER_ROLE_CHANGED']);
    mocked.actionCounts.mockResolvedValue([]);
    mocked.search.mockResolvedValue(emptyPage);
  });

  it('첫 진입에 커머스 감사를 최근 30일로 조회한다 — 기간은 화면이 늘 채워 보낸다', async () => {
    render(<AuditLogConsolePage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalled());
    const [scope, query] = mocked.search.mock.calls[0];
    expect(scope).toBe('COMMERCE');
    expect(query.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(query.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(query.size).toBe(50);
  });

  it('필터 드롭다운은 서버가 준 액션 목록으로 그린다 — 화면에 하드코딩하지 않는다', async () => {
    render(<AuditLogConsolePage />);

    expect(await screen.findByRole('option', { name: 'USER_ROLE_CHANGED' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'LOGIN_FAILED' })).toBeInTheDocument();
  });

  it('정산 탭을 고르면 정산 감사 표면을 조회한다', async () => {
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('tab', { name: /정산/ }));

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([scope]) => scope === 'SETTLEMENT')).toBe(true));
  });

  it('행 상세는 접혀 있고, 눌러야 detail_json 이 펼쳐진다', async () => {
    mocked.search.mockResolvedValue(pageWith());
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);

    // 액션 이름은 필터 <option> 에도 있으므로 표 안으로 좁혀 찾는다.
    const table = await screen.findByRole('table');
    expect(within(table).getByText('USER_ROLE_CHANGED')).toBeInTheDocument();
    expect(screen.queryByText(/"after":"MANAGER"/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '보기' }));

    expect(screen.getByText(/"after":"MANAGER"/)).toBeInTheDocument();
  });

  it('결과가 없으면 "기록된 조작이 없습니다"라고 말한다 — 빈 표를 남기지 않는다', async () => {
    render(<AuditLogConsolePage />);

    expect(await screen.findByText('이 기간에 기록된 조작이 없습니다.')).toBeInTheDocument();
  });

  it('CSV 가 잘리면 몇 건 중 몇 건인지 경고한다 — 잘린 줄 모르는 감사 자료가 나가면 안 된다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']),
      fileName: 'audit-logs_2026-03-02.csv',
      truncated: true,
      total: 12345,
    });
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    const notice = await screen.findByRole('status');
    expect(notice).toHaveTextContent('12,345건 중 앞 5,000건');
    expect(mockedSave).toHaveBeenCalledWith(expect.any(Blob), 'audit-logs_2026-03-02.csv');
  });

  it('CSV 가 온전하면 전부 받았다고 알린다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']),
      fileName: 'audit-logs.csv',
      truncated: false,
      total: 12,
    });
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('12건을 모두 내려받았습니다');
  });

  it('액션별 건수 칩을 누르면 그 액션으로 좁혀 다시 조회한다', async () => {
    mocked.actionCounts.mockResolvedValue([{ action: 'LOGIN_FAILED', count: 9 }]);
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);

    await user.click(await screen.findByRole('button', { name: /LOGIN_FAILED/ }));

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([, query]) => query.action === 'LOGIN_FAILED')).toBe(true));
  });

  it('조회 실패는 사용자에게 드러낸다 — 빈 표를 성공처럼 보여 주지 않는다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<AuditLogConsolePage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('액션 목록을 못 받아도 화면은 뜬다 — 필터 하나 때문에 조회 자체를 막지 않는다', async () => {
    mocked.actions.mockRejectedValue(new Error('boom'));
    render(<AuditLogConsolePage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalled());
    // 드롭다운은 "전체" 하나만 남는다.
    expect(within(await screen.findByLabelText('액션')).getAllByRole('option')).toHaveLength(1);
  });

  it('CSV 실패도 드러낸다', async () => {
    mocked.export.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('CSV');
  });

  it('필터 입력은 그대로 질의에 실린다 — 기간·행위자·리소스', async () => {
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.clear(await screen.findByLabelText('시작일'));
    await user.type(screen.getByLabelText('시작일'), '2026-03-01');
    await user.clear(screen.getByLabelText('종료일'));
    await user.type(screen.getByLabelText('종료일'), '2026-03-31');
    await user.type(screen.getByLabelText('행위자 이메일'), 'ops@lemuel.io');
    await user.type(screen.getByLabelText('리소스 유형'), 'PAYOUT');
    await user.type(screen.getByLabelText('리소스 ID'), 'P-1');
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.search.mock.calls.some(([, q]) =>
      q.from === '2026-03-01' && q.to === '2026-03-31'
      && q.actorEmail === 'ops@lemuel.io' && q.resourceType === 'PAYOUT' && q.resourceId === 'P-1',
    )).toBe(true));
  });

  it('여러 페이지면 페이지 이동이 나오고, 조회를 다시 누르면 1페이지로 돌아온다', async () => {
    mocked.search.mockResolvedValue(pageWith({ totalElements: 120, totalPages: 3 }));
    const user = userEvent.setup();
    render(<AuditLogConsolePage />);

    await user.click(await screen.findByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '이전' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());

    // 2페이지에서 조회를 누르면 1페이지로 — 새 조건인데 옛 페이지에 머무르면 빈 결과처럼 보인다.
    await user.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '조회' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());
  });
});
