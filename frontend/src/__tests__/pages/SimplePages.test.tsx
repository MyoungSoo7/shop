import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import StartPage from '@/pages/StartPage';
import GamesPage from '@/pages/GamesPage';
import TossPaymentFail from '@/pages/TossPaymentFail';
import ViewerPage from '@/pages/ViewerPage';
import { authApi } from '@/api/auth';

const navigate = vi.fn();

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigate };
});

vi.mock('@/api/auth', () => ({
  authApi: { login: vi.fn(), saveToken: vi.fn() },
}));

const mockedAuth = vi.mocked(authApi);

beforeEach(() => {
  vi.clearAllMocks();
});

describe('StartPage — 역할 선택 진입', () => {
  const renderPage = () => render(<MemoryRouter><StartPage /></MemoryRouter>);

  it('USER 는 로그인 페이지로 보낸다 (빠른 로그인 없음)', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: /쇼핑하기 \(USER\)/ }));

    expect(navigate).toHaveBeenCalledWith('/login');
    expect(mockedAuth.login).not.toHaveBeenCalled();
  });

  it('MANAGER 빠른 로그인은 시드 계정으로 로그인하고 토큰을 저장한다', async () => {
    mockedAuth.login.mockResolvedValue({ token: 't', email: 'm@t', role: 'MANAGER' } as never);
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: /매니저 모드/ }));

    await waitFor(() =>
      expect(mockedAuth.login).toHaveBeenCalledWith({
        email: 'seed_manager@test.com',
        password: 'password123',
      }),
    );
    expect(mockedAuth.saveToken).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/admin');
  });

  it('ADMIN 빠른 로그인도 관리자 대시보드로 보낸다', async () => {
    mockedAuth.login.mockResolvedValue({ token: 't', email: 'a@t', role: 'ADMIN' } as never);
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: /관리자 대시보드 \(ADMIN\)/ }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/admin'));
    expect(mockedAuth.login).toHaveBeenCalledWith({
      email: 'seed_admin@test.com',
      password: 'password123',
    });
  });

  it('응답 역할이 USER 면 주문 페이지로 보낸다', async () => {
    mockedAuth.login.mockResolvedValue({ token: 't', email: 'u@t', role: 'USER' } as never);
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: /관리자 대시보드 \(ADMIN\)/ }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/order'));
  });

  it('로그인 실패는 서버 확인 안내로 알린다', async () => {
    mockedAuth.login.mockRejectedValue(new Error('down'));
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: /매니저 모드/ }));

    expect(
      await screen.findByText('MANAGER 계정 로그인에 실패했습니다. 서버가 실행 중인지 확인하세요.'),
    ).toBeInTheDocument();
  });
});

describe('GamesPage', () => {
  it('게임 카드를 그리고 누르면 그 게임으로 이동한다', async () => {
    render(<MemoryRouter><GamesPage /></MemoryRouter>);

    expect(screen.getByText('오목 게임')).toBeInTheDocument();
    expect(screen.getByText('바둑 게임')).toBeInTheDocument();

    await userEvent.click(screen.getByText('오목 게임'));
    expect(navigate).toHaveBeenCalledWith('/games/gomoku');

    await userEvent.click(screen.getByText('바둑 게임'));
    expect(navigate).toHaveBeenCalledWith('/games/baduk');
  });
});

describe('TossPaymentFail', () => {
  const renderWith = (query: string) =>
    render(
      <MemoryRouter initialEntries={[`/order/toss/fail${query}`]}>
        <TossPaymentFail />
      </MemoryRouter>,
    );

  it('쿼리로 온 사유와 코드를 그대로 보여 준다', () => {
    renderWith('?code=PAY_PROCESS_CANCELED&message=사용자가 결제를 취소했습니다');

    expect(screen.getByText('사용자가 결제를 취소했습니다')).toBeInTheDocument();
    expect(screen.getByText('오류 코드: PAY_PROCESS_CANCELED')).toBeInTheDocument();
  });

  it('쿼리가 없으면 기본 문구로 대체한다', () => {
    renderWith('');

    expect(screen.getByText('결제가 취소되었습니다.')).toBeInTheDocument();
    expect(screen.getByText('오류 코드: 알 수 없음')).toBeInTheDocument();
  });

  it('다시 시도하면 주문 페이지로 돌아간다', async () => {
    renderWith('');

    await userEvent.click(screen.getByRole('button', { name: '다시 시도하기' }));

    expect(navigate).toHaveBeenCalledWith('/order');
  });
});

describe('ViewerPage', () => {
  it('뷰어 목록과 지원 형식을 보여 준다', () => {
    render(<ViewerPage />);

    expect(screen.getByText('Markdown Viewer')).toBeInTheDocument();
    expect(screen.getByText('Text Viewer')).toBeInTheDocument();
    expect(screen.getByText('.markdown')).toBeInTheDocument();
  });

  it('뷰어를 고르면 iframe 으로 그 뷰어를 연다', async () => {
    const { container } = render(<ViewerPage />);

    await userEvent.click(screen.getByText('Markdown Viewer'));

    const iframe = container.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe).toHaveAttribute('src', '/viewers/md-viewer.html');
  });

  it('텍스트 뷰어도 같은 방식으로 열린다', async () => {
    const { container } = render(<ViewerPage />);

    await userEvent.click(screen.getByText('Text Viewer'));

    expect(container.querySelector('iframe')).toHaveAttribute('src', '/viewers/txt-viewer.html');
  });

  it('뒤로를 누르면 선택 화면으로 돌아온다', async () => {
    render(<ViewerPage />);
    await userEvent.click(screen.getByText('Markdown Viewer'));

    await userEvent.click(screen.getByRole('button', { name: '← 뒤로' }));

    expect(screen.getByText('📖 문서 뷰어')).toBeInTheDocument();
  });
});
