import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OperatorAdminPage from '@/pages/system/OperatorAdminPage';
import { operatorAdminApi, type OperatorSummary } from '@/api/operatorAdmin';

vi.mock('@/api/operatorAdmin', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/operatorAdmin')>();
  return { ...actual, operatorAdminApi: { search: vi.fn(), export: vi.fn(), unlock: vi.fn() } };
});
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(operatorAdminApi);

const operator = (over: Partial<OperatorSummary> = {}): OperatorSummary => ({
  id: 1, email: 'ops@example.com', name: '운영자', role: 'ADMIN', active: true,
  lastLoginAt: '2026-08-01T09:00:00', failedLoginAttempts: 0,
  lockedUntil: null, locked: false,
  passwordChangedAt: '2026-01-01T00:00:00', createdAt: '2026-01-01T00:00:00', ...over,
});

const page = (content: OperatorSummary[], over = {}) => ({
  content, page: 0, size: 50, totalElements: content.length, totalPages: 1, ...over,
});

beforeEach(() => vi.clearAllMocks());

/**
 * 이 화면의 존재 이유는 <b>회수할 계정을 고르는 것</b>이다.
 *
 * <p>방치된 일반 회원은 아무 일도 일으키지 않지만 방치된 ADMIN 계정은 남의 손에 들어가는 순간
 * 전 권한이다. 그래서 검증의 초점은 "목록이 뜨는가"가 아니라 <b>회수 판단에 필요한 구분이
 * 살아 있는가</b>다 — 쓴 적 없음 vs 오래 안 씀, 해제함 vs 이미 안 잠겨 있었음.
 */
describe('OperatorAdminPage — 회수 판단에 필요한 구분', () => {
  it('로그인한 적 없는 계정을 "-" 로 뭉개지 않는다', async () => {
    mocked.search.mockResolvedValue(page([
      operator({ id: 1, lastLoginAt: null }),
      operator({ id: 2, email: 'old@example.com', lastLoginAt: '2026-01-02T09:00:00' }),
    ]));
    render(<OperatorAdminPage />);

    await waitFor(() => expect(screen.getByTestId('operator-row-1')).toBeInTheDocument());

    // 발급만 하고 아무도 받아가지 않은 계정과, 사람이 있는데 안 쓰는 계정은 회수 판단이 다르다.
    expect(screen.getByTestId('last-login-1')).toHaveTextContent('로그인한 적 없음');
    expect(screen.getByTestId('last-login-2')).not.toHaveTextContent('로그인한 적 없음');
  });

  it('"로그인한 적 없음"은 미사용 일수와 별도 필터다', async () => {
    mocked.search.mockResolvedValue(page([]));
    render(<OperatorAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    fireEvent.click(await screen.findByLabelText('로그인한 적 없음'));

    await waitFor(() =>
      expect(mocked.search).toHaveBeenLastCalledWith(
        expect.objectContaining({ neverLoggedIn: true, idleDays: undefined })));
  });
});

describe('OperatorAdminPage — 잠금 해제', () => {
  const locked = operator({ id: 7, email: 'locked@example.com', locked: true,
    lockedUntil: '2026-08-23T10:00:00', failedLoginAttempts: 5 });

  it('사유가 비면 해제 버튼을 누를 수 없다 — 서버 400 을 기다리게 하지 않는다', async () => {
    mocked.search.mockResolvedValue(page([locked]));
    render(<OperatorAdminPage />);
    await waitFor(() => expect(screen.getByTestId('locked-7')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '잠금 해제' }));

    expect(screen.getByRole('button', { name: '해제' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('잠금 해제 사유'), { target: { value: '본인 확인함' } });
    expect(screen.getByRole('button', { name: '해제' })).toBeEnabled();
  });

  it('사유를 서버로 보내고 목록을 다시 읽는다', async () => {
    mocked.search.mockResolvedValue(page([locked]));
    mocked.unlock.mockResolvedValue({
      userId: 7, email: 'locked@example.com', wasLocked: true,
      previousLockedUntil: '2026-08-23T10:00:00', previousFailedAttempts: 5,
    });
    render(<OperatorAdminPage />);
    await waitFor(() => expect(screen.getByTestId('locked-7')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '잠금 해제' }));
    fireEvent.change(screen.getByLabelText('잠금 해제 사유'), { target: { value: '본인 확인함' } });

    fireEvent.click(screen.getByRole('button', { name: '해제' }));

    await waitFor(() => expect(mocked.unlock).toHaveBeenCalledWith(7, '본인 확인함'));
    expect(await screen.findByTestId('operator-notice')).toHaveTextContent('잠금을 해제했습니다');
  });

  it('이미 풀려 있었으면 "해제했다"고 보고하지 않는다', async () => {
    mocked.search.mockResolvedValue(page([locked]));
    mocked.unlock.mockResolvedValue({
      userId: 7, email: 'locked@example.com', wasLocked: false,
      previousLockedUntil: null, previousFailedAttempts: 0,
    });
    render(<OperatorAdminPage />);
    await waitFor(() => expect(screen.getByTestId('locked-7')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '잠금 해제' }));
    fireEvent.change(screen.getByLabelText('잠금 해제 사유'), { target: { value: '확인' } });

    fireEvent.click(screen.getByRole('button', { name: '해제' }));

    // 목록을 열어 둔 사이 만료됐을 수 있다 — 하지도 않은 일을 했다고 적으면 감사 기록이 틀어진다.
    await waitFor(() =>
      expect(screen.getByTestId('operator-notice')).toHaveTextContent('이미 잠겨 있지 않았습니다'));
  });

  it('잠기지 않은 계정에는 해제 버튼이 없다', async () => {
    mocked.search.mockResolvedValue(page([operator()]));
    render(<OperatorAdminPage />);
    await waitFor(() => expect(screen.getByTestId('operator-row-1')).toBeInTheDocument());

    expect(screen.queryByRole('button', { name: '잠금 해제' })).not.toBeInTheDocument();
  });
});

describe('OperatorAdminPage — 조회 실패와 0건', () => {
  it('조회가 실패하면 빈 표가 아니라 오류를 보여 준다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<OperatorAdminPage />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    // 빈 표를 그리면 조회 실패가 "권한 계정 0건"으로 위장한다.
    expect(screen.queryByTestId('operator-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('operator-empty')).not.toBeInTheDocument();
  });

  it('0건이면 없다고 말한다', async () => {
    mocked.search.mockResolvedValue(page([]));
    render(<OperatorAdminPage />);

    await waitFor(() => expect(screen.getByTestId('operator-empty')).toBeInTheDocument());
  });
});

describe('OperatorAdminPage — CSV', () => {
  it('서버 상한에 잘렸으면 그 사실을 함께 말한다', async () => {
    mocked.search.mockResolvedValue(page([operator()]));
    mocked.export.mockResolvedValue({
      blob: new Blob(['a']), fileName: 'operators.csv', truncated: true, total: 1200,
    });
    render(<OperatorAdminPage />);
    await waitFor(() => expect(screen.getByTestId('operator-row-1')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'CSV' }));

    // 잘린 파일을 전체 명부로 읽으면 회수 대상이 통째로 빠진다.
    expect(await screen.findByTestId('operator-notice')).toHaveTextContent('일부만 담겼습니다');
  });
});
