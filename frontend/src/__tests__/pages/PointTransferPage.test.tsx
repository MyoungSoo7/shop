import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>보내기 전에 확인 단계가 있다.</b> 되돌리는 API 가 없다 — 받은 사람이 이미 썼을 수 있다.
 * 확인 문구에는 받는 분과 금액을 다시 적는다. 버튼만 두 번 누르게 하면 두 번째 클릭은 첫 번째의
 * 연장이 되어 아무것도 막지 못한다.
 *
 * <p>② <b>실패해도 멱등 키를 바꾸지 않는다.</b> 응답을 못 받은 요청은 서버에서 이미 처리됐을 수
 * 있다. 재시도에 키를 새로 뽑으면 그게 곧 두 번 보내기다. 성공한 뒤에만 새로 뽑는다.
 *
 * <p>③ <b>보내는 사람 칸이 본문에 없다.</b> 주체를 요청이 지정할 수 있으면 남의 포인트를 보내는
 * 요청을 만들 수 있다.
 *
 * <p>④ <b>거절 사유를 화면이 지어내지 않는다.</b> 서버는 "이메일이 없다"와 "이름이 다르다"를
 * 구분하지 않는다 — 구분해 주면 응답만 보고 가입 여부를 캐낼 수 있다.
 */

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

vi.mock('@/api/point', () => ({ pointApi: { myBalance: vi.fn() } }));
vi.mock('@/api/pointTransfer', async () => {
  const actual = await vi.importActual<typeof import('@/api/pointTransfer')>('@/api/pointTransfer');
  return {
    ...actual,
    pointTransferApi: { send: vi.fn(), history: vi.fn() },
  };
});

const { pointApi } = await import('@/api/point');
const { pointTransferApi } = await import('@/api/pointTransfer');
const { default: PointTransferPage } = await import('@/pages/PointTransferPage');

const balance = vi.mocked(pointApi.myBalance);
const transfer = vi.mocked(pointTransferApi);

const result = (over: Record<string, unknown> = {}) => ({
  transferNo: 'PT20260828-00000001',
  recipientEmail: 'fr****@example.com',
  recipientName: '김받는',
  amount: 1000,
  remainingBalance: 4000,
  transferredAt: '2026-08-28T10:00:00+09:00',
  alreadyProcessed: false,
  ...over,
});

const entry = (over: Record<string, unknown> = {}) => ({
  transferNo: 'PT20260828-00000001',
  outgoing: true,
  counterpartName: '김받는',
  amount: 1000,
  message: '고마워',
  transferredAt: '2026-08-28T10:00:00+09:00',
  ...over,
});

const renderPage = () => render(<MemoryRouter><PointTransferPage /></MemoryRouter>);

const fill = async (amount = '1000') => {
  await userEvent.type(screen.getByTestId('transfer-recipientEmail'), 'friend@example.com');
  await userEvent.type(screen.getByTestId('transfer-recipientName'), '김받는');
  await userEvent.type(screen.getByTestId('transfer-amount'), amount);
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth.userId = 7;
  mockAuth.loading = false;
  balance.mockResolvedValue({ userId: 7, available: 5000 });
  transfer.history.mockResolvedValue([]);
  transfer.send.mockResolvedValue(result());
});

describe('PointTransferPage — 진입', () => {
  it('인증 확인 중에는 로그인 안내를 띄우지 않는다 — 새로고침마다 번쩍인다', () => {
    mockAuth.loading = true;

    renderPage();

    expect(screen.getByText('불러오는 중…')).toBeInTheDocument();
    expect(screen.queryByText(/로그인/)).not.toBeInTheDocument();
  });

  it('로그인하지 않았으면 로그인 링크만 보인다', () => {
    mockAuth.userId = null;

    renderPage();

    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument();
    expect(screen.queryByTestId('transfer-recipientEmail')).not.toBeInTheDocument();
  });

  it('보낼 수 있는 포인트를 먼저 보여 준다', async () => {
    renderPage();

    expect(await screen.findByTestId('transfer-balance')).toHaveTextContent('5,000P');
  });
});

describe('PointTransferPage — 보내기', () => {
  it('확인 단계를 거치지 않으면 전송하지 않는다', async () => {
    renderPage();
    await fill();

    await userEvent.click(screen.getByTestId('transfer-review'));

    expect(screen.getByTestId('transfer-confirm')).toBeInTheDocument();
    expect(transfer.send).not.toHaveBeenCalled();
  });

  /** 확인 문구가 "정말 보낼까요?" 뿐이면 두 번째 클릭이 첫 번째의 연장이 되어 아무것도 막지 못한다. */
  it('확인 문구에 받는 분과 금액을 다시 적는다', async () => {
    renderPage();
    await fill();

    await userEvent.click(screen.getByTestId('transfer-review'));

    const confirm = screen.getByTestId('transfer-confirm');
    expect(confirm).toHaveTextContent('김받는');
    expect(confirm).toHaveTextContent('friend@example.com');
    expect(confirm).toHaveTextContent('1,000P');
    expect(confirm).toHaveTextContent('되돌릴 수 없습니다');
  });

  it('보내기를 누르면 토큰 주체를 담지 않은 본문이 나간다', async () => {
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    await waitFor(() => expect(transfer.send).toHaveBeenCalledTimes(1));
    const body = transfer.send.mock.calls[0][0];
    expect(body).toMatchObject({
      recipientEmail: 'friend@example.com',
      recipientName: '김받는',
      amount: 1000,
    });
    expect(body).not.toHaveProperty('senderUserId');
    expect(body.requestId).toBeTruthy();
  });

  it('한마디를 비워 두면 null 로 보낸다 — 빈 문자열과 없음을 서버가 갈라 보지 않게', async () => {
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    await waitFor(() => expect(transfer.send).toHaveBeenCalled());
    expect(transfer.send.mock.calls[0][0].message).toBeNull();
  });

  it('보낸 뒤 서버가 준 잔액과 가려진 이메일을 그대로 그린다', async () => {
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(await screen.findByTestId('transfer-result')).toHaveTextContent('fr****@example.com');
    expect(screen.getByTestId('transfer-balance')).toHaveTextContent('4,000P');
  });

  it('이미 처리된 요청이면 다시 보내지 않았다고 알린다', async () => {
    transfer.send.mockResolvedValue(result({ alreadyProcessed: true }));
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    expect(await screen.findByTestId('transfer-replayed')).toBeInTheDocument();
  });
});

describe('PointTransferPage — 멱등 키', () => {
  /**
   * 이 화면에서 가장 조용히 틀릴 수 있는 자리다. 실패한 요청은 서버에서 이미 처리됐을 수 있어,
   * 재시도에 키를 새로 뽑으면 그게 곧 두 번 보내기다.
   */
  it('전송이 실패하면 같은 키로 다시 보낸다', async () => {
    transfer.send.mockRejectedValueOnce(new Error('timeout'));
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));
    await userEvent.click(screen.getByRole('button', { name: '보내기' }));
    await screen.findByRole('alert');

    await userEvent.click(screen.getByTestId('transfer-review'));
    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    await waitFor(() => expect(transfer.send).toHaveBeenCalledTimes(2));
    expect(transfer.send.mock.calls[1][0].requestId)
      .toBe(transfer.send.mock.calls[0][0].requestId);
  });

  it('전송이 성공하면 다음 전송은 새 키를 쓴다', async () => {
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));
    await userEvent.click(screen.getByRole('button', { name: '보내기' }));
    await screen.findByTestId('transfer-result');

    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));
    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    await waitFor(() => expect(transfer.send).toHaveBeenCalledTimes(2));
    expect(transfer.send.mock.calls[1][0].requestId)
      .not.toBe(transfer.send.mock.calls[0][0].requestId);
  });
});

describe('PointTransferPage — 거절과 검증', () => {
  it('보유 포인트보다 많으면 서버까지 가지 않는다', async () => {
    renderPage();
    await screen.findByTestId('transfer-balance');
    await fill('9000');

    expect(await screen.findByTestId('transfer-not-enough')).toBeInTheDocument();
    expect(screen.getByTestId('transfer-review')).toBeDisabled();
  });

  it('소수점 포인트는 보낼 수 없다', async () => {
    renderPage();
    await fill('10.5');

    expect(await screen.findByTestId('transfer-amount-invalid')).toBeInTheDocument();
    expect(screen.getByTestId('transfer-review')).toBeDisabled();
  });

  it('받는 분 칸이 비면 확인 단계로 갈 수 없다', async () => {
    renderPage();
    await userEvent.type(screen.getByTestId('transfer-recipientEmail'), 'friend@example.com');
    await userEvent.type(screen.getByTestId('transfer-amount'), '1000');

    expect(screen.getByTestId('transfer-review')).toBeDisabled();
  });

  /** 서버가 사유를 구분하지 않으므로 화면도 추측 문구를 붙이지 않는다. */
  it('거절 문구는 서버가 준 것을 그대로 쓴다', async () => {
    transfer.send.mockRejectedValue({
      response: { data: { message: '받는 분을 확인할 수 없습니다. 이메일과 이름을 다시 확인해 주세요.' } },
    });
    renderPage();
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('받는 분을 확인할 수 없습니다');
    expect(alert.textContent).not.toContain('이름이');
  });
});

describe('PointTransferPage — 이력', () => {
  it('보낸 것과 받은 것을 부호까지 갈라 그린다', async () => {
    transfer.history.mockResolvedValue([
      entry(),
      entry({ transferNo: 'PT-2', outgoing: false, counterpartName: '이보낸', amount: 500, message: null }),
    ]);

    renderPage();

    const outgoing = await screen.findByTestId('transfer-item-PT20260828-00000001');
    expect(outgoing).toHaveTextContent('보냄');
    expect(outgoing).toHaveTextContent('-1,000P');
    expect(outgoing).toHaveTextContent('고마워');

    const incoming = screen.getByTestId('transfer-item-PT-2');
    expect(incoming).toHaveTextContent('받음');
    expect(incoming).toHaveTextContent('+500P');
  });

  it('주고받은 것이 없으면 빈 안내를 띄운다', async () => {
    renderPage();

    expect(await screen.findByTestId('transfer-history-empty')).toBeInTheDocument();
  });

  it('보낸 뒤에는 이력을 다시 읽는다', async () => {
    renderPage();
    await screen.findByTestId('transfer-history-empty');
    await fill();
    await userEvent.click(screen.getByTestId('transfer-review'));

    await userEvent.click(screen.getByRole('button', { name: '보내기' }));

    await waitFor(() => expect(transfer.history).toHaveBeenCalledTimes(2));
  });
});
