import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MemberAdminPage from '@/pages/system/MemberAdminPage';
import { memberApi, type MemberPage, type MemberSummary } from '@/api/member';
import { saveBlob } from '@/api/auditLog';

vi.mock('@/api/member', () => ({
  memberApi: {
    search: vi.fn(),
    statusCounts: vi.fn(),
    enums: vi.fn(),
    export: vi.fn(),
    changeRole: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    suspend: vi.fn(),
    reinstate: vi.fn(),
  },
}));
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(memberApi);
const mockedSave = vi.mocked(saveBlob);

const member = (overrides: Partial<MemberSummary> = {}): MemberSummary => ({
  id: 42,
  email: 'hong@lemuel.io',
  name: '홍길동',
  phoneNumber: '010-1111-2222',
  role: 'USER',
  membershipStatus: 'APPROVED',
  active: true,
  createdAt: '2026-03-01T12:00:00',
  updatedAt: '2026-03-01T12:00:00',
  ...overrides,
});

const pageOf = (rows: MemberSummary[]): MemberPage => ({
  content: rows,
  page: 0,
  size: 50,
  totalElements: rows.length,
  totalPages: rows.length === 0 ? 0 : 1,
});

describe('MemberAdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.enums.mockResolvedValue({
      roles: ['USER', 'MANAGER', 'ADMIN'],
      membershipStatuses: ['PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'],
    });
    mocked.statusCounts.mockResolvedValue([]);
    mocked.search.mockResolvedValue(pageOf([]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('가입일 조건 없이 첫 조회한다 — 언제 가입했는지 모르는 회원을 찾는 화면이다', async () => {
    render(<MemberAdminPage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalled());
    const [query] = mocked.search.mock.calls[0];
    expect(query.joinedFrom).toBeUndefined();
    expect(query.joinedTo).toBeUndefined();
    expect(query.size).toBe(50);
  });

  it('필터 목록은 서버 enum 으로 그린다', async () => {
    render(<MemberAdminPage />);

    expect(await screen.findByRole('option', { name: 'MANAGER' })).toBeInTheDocument();
    // 승인 상태는 한국어 라벨로 보여 준다.
    expect(screen.getByRole('option', { name: '승인 대기' })).toBeInTheDocument();
  });

  it('계정 상태 "전체"는 조건을 걸지 않는다 — 탈퇴 회원도 찾을 수 있어야 한다', async () => {
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    expect(mocked.search.mock.calls[0][0].active).toBeUndefined();
  });

  it('검색어를 넣고 조회하면 질의에 실린다', async () => {
    const user = userEvent.setup();
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.type(await screen.findByLabelText('검색어'), '홍길동');
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.keyword === '홍길동')).toBe(true));
  });

  it('PENDING 회원에게는 승인·반려만 보인다 — 아직 정지할 것이 없다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'PENDING' })]));
    render(<MemberAdminPage />);

    expect(await screen.findByRole('button', { name: '승인' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '반려' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '정지' })).not.toBeInTheDocument();
  });

  it('APPROVED 회원에게는 정지만 보인다 — 이미 승인된 회원을 다시 승인하지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'APPROVED' })]));
    render(<MemberAdminPage />);

    expect(await screen.findByRole('button', { name: '정지' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument();
  });

  it('SUSPENDED 회원에게는 정지 해제가 보인다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'SUSPENDED' })]));
    render(<MemberAdminPage />);

    expect(await screen.findByRole('button', { name: '정지 해제' })).toBeInTheDocument();
  });

  it('승인하면 승인 API 를 부르고 목록을 다시 읽는다 — 낙관적 갱신을 하지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'PENDING' })]));
    mocked.approve.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    const before = mocked.search.mock.calls.length;
    await user.click(await screen.findByRole('button', { name: '승인' }));

    await waitFor(() => expect(mocked.approve).toHaveBeenCalledWith(42));
    await waitFor(() => expect(mocked.search.mock.calls.length).toBeGreaterThan(before));
  });

  it('정지 사유를 비우면 아무것도 부르지 않는다 — 사유 없는 조작을 만들지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'APPROVED' })]));
    vi.stubGlobal('prompt', vi.fn().mockReturnValue('   '));
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '정지' }));

    expect(mocked.suspend).not.toHaveBeenCalled();
  });

  it('역할 변경은 새 역할과 사유를 함께 보낸다', async () => {
    mocked.search.mockResolvedValue(pageOf([member()]));
    mocked.changeRole.mockResolvedValue(member({ role: 'MANAGER' }));
    vi.stubGlobal('prompt', vi.fn()
      .mockReturnValueOnce('manager')
      .mockReturnValueOnce('CS 팀 배치'));
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '역할 변경' }));

    await waitFor(() =>
      expect(mocked.changeRole).toHaveBeenCalledWith(42, 'MANAGER', 'CS 팀 배치'));
  });

  it('역할 변경 사유를 비우면 호출하지 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([member()]));
    vi.stubGlobal('prompt', vi.fn()
      .mockReturnValueOnce('MANAGER')
      .mockReturnValueOnce(''));
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '역할 변경' }));

    expect(mocked.changeRole).not.toHaveBeenCalled();
  });

  it('CSV 를 받으면 감사에 남는다는 사실을 알린다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']),
      fileName: 'members_2026-03-02.csv',
      truncated: false,
      total: 12,
    });
    const user = userEvent.setup();
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    const notice = await screen.findByRole('status');
    expect(notice).toHaveTextContent('감사 로그에 남았습니다');
    expect(mockedSave).toHaveBeenCalledWith(expect.any(Blob), 'members_2026-03-02.csv');
  });

  it('잘린 CSV 는 몇 명 중 몇 명인지 말한다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']),
      fileName: 'members.csv',
      truncated: true,
      total: 12345,
    });
    const user = userEvent.setup();
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('12,345명 중 앞 5,000명');
  });

  it('결과가 없으면 빈 표를 남기지 않고 그렇게 말한다', async () => {
    render(<MemberAdminPage />);

    expect(await screen.findByText('조건에 맞는 회원이 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사용자에게 드러낸다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<MemberAdminPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('상태별 인원 칩을 누르면 그 상태로 좁혀 조회한다', async () => {
    mocked.statusCounts.mockResolvedValue([{ membershipStatus: 'PENDING', count: 4 }]);
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: /승인 대기/ }));

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.status === 'PENDING')).toBe(true));
  });

  it('상태를 골라도 집계 질의에는 상태를 싣지 않는다 — 고른 상태 하나만 남으면 집계가 무의미해진다', async () => {
    const user = userEvent.setup();
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.statusCounts).toHaveBeenCalled());

    await user.selectOptions(await screen.findByLabelText('승인 상태'), 'SUSPENDED');

    await waitFor(() =>
      // 목록 질의에는 실리고
      expect(mocked.search.mock.calls.some(([q]) => q.status === 'SUSPENDED')).toBe(true));
    // 집계 질의에는 어느 호출에서도 실리지 않는다.
    expect(mocked.statusCounts.mock.calls.every(([q]) => q.status === undefined)).toBe(true);
  });

  it('역할·상태 목록을 못 받아도 화면은 뜬다 — 필터 하나 때문에 조회 자체를 막지 않는다', async () => {
    mocked.enums.mockRejectedValue(new Error('boom'));
    render(<MemberAdminPage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalled());
    expect(within(await screen.findByLabelText('역할')).getAllByRole('option')).toHaveLength(1);
  });

  it('CSV 실패도 드러낸다', async () => {
    mocked.export.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('CSV');
  });

  it('승인 실패는 사용자에게 드러난다 — 실패한 전이가 성공처럼 보이면 안 된다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'PENDING' })]));
    mocked.approve.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '승인' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('가입일·역할·계정 상태 필터는 그대로 질의에 실린다', async () => {
    const user = userEvent.setup();
    render(<MemberAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.selectOptions(await screen.findByLabelText('역할'), 'MANAGER');
    await user.selectOptions(screen.getByLabelText('계정 상태'), 'false');
    await user.type(screen.getByLabelText('가입 시작일'), '2026-01-01');
    await user.type(screen.getByLabelText('가입 종료일'), '2026-03-31');
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.search.mock.calls.some(([q]) =>
      q.role === 'MANAGER' && q.active === false
      && q.joinedFrom === '2026-01-01' && q.joinedTo === '2026-03-31',
    )).toBe(true));
  });

  it('여러 페이지면 이동이 나오고, 조회를 다시 누르면 1페이지로 돌아온다', async () => {
    mocked.search.mockResolvedValue({
      ...pageOf([member()]), totalElements: 120, totalPages: 3,
    });
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '이전' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '조회' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());
  });

  it('반려·정지 사유를 입력하면 그대로 보낸다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'PENDING' })]));
    mocked.reject.mockResolvedValue(undefined);
    vi.stubGlobal('prompt', vi.fn().mockReturnValue('서류 미비'));
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '반려' }));

    await waitFor(() => expect(mocked.reject).toHaveBeenCalledWith(42, '서류 미비'));
  });

  it('정지 해제는 사유 없이 부른다', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ membershipStatus: 'SUSPENDED' })]));
    mocked.reinstate.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '정지 해제' }));

    await waitFor(() => expect(mocked.reinstate).toHaveBeenCalledWith(42));
  });

  it('역할 변경 프롬프트를 취소하면 사유를 묻지도 않는다', async () => {
    mocked.search.mockResolvedValue(pageOf([member()]));
    const prompt = vi.fn().mockReturnValue(null);
    vi.stubGlobal('prompt', prompt);
    const user = userEvent.setup();
    render(<MemberAdminPage />);

    await user.click(await screen.findByRole('button', { name: '역할 변경' }));

    expect(prompt).toHaveBeenCalledTimes(1);
    expect(mocked.changeRole).not.toHaveBeenCalled();
  });

  it('연락처 없는 회원은 —— 로 그린다 (빈칸이 아니라 없음임을 밝힌다)', async () => {
    mocked.search.mockResolvedValue(pageOf([member({ phoneNumber: null, name: null })]));
    render(<MemberAdminPage />);

    const table = await screen.findByRole('table');
    expect(within(table).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });
});
