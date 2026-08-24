import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Login from '@/pages/Login';
import { authApi } from '@/api/auth';

vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    autoLogin: vi.fn(),
    guestLogin: vi.fn(),
    saveToken: vi.fn(),
  },
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

const mocked = vi.mocked(authApi);

const sessionOf = (role: string) => ({ token: 't', role, email: 'u@lemuel.dev', userId: 1 });

/** axios 오류 모양 — apiErrorStatus 가 이 형태에서 status 를 읽는다. */
const axiosError = (status: number, message?: string) => ({
  isAxiosError: true,
  response: { status, data: message ? { message } : undefined },
});

const renderPage = () => render(<MemoryRouter><Login /></MemoryRouter>);

const submitLogin = () => {
  fireEvent.change(screen.getByPlaceholderText('이메일'), { target: { value: 'u@lemuel.dev' } });
  fireEvent.change(screen.getByPlaceholderText('비밀번호'), { target: { value: 'pw' } });
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
};

beforeEach(() => vi.clearAllMocks());

/**
 * 사용자 로그인 화면 — <b>어디로 보내는가</b>가 계약이다.
 *
 * <p>같은 폼으로 들어와도 역할에 따라 도착지가 갈린다(운영자는 관리자 대시보드). 잘못 보내면
 * 권한은 맞는데 화면이 비어 보이는, 진단하기 나쁜 형태의 버그가 된다.
 */
describe('Login — 자격 증명 로그인', () => {
  it('일반 사용자는 주문 화면으로 보낸다', async () => {
    mocked.login.mockResolvedValue(sessionOf('USER') as never);
    renderPage();

    submitLogin();

    await waitFor(() => expect(mocked.saveToken).toHaveBeenCalled());
    expect(mocked.login).toHaveBeenCalledWith({ email: 'u@lemuel.dev', password: 'pw' });
    expect(mockNavigate).toHaveBeenCalledWith('/order');
  });

  it('ADMIN·MANAGER 가 이 화면으로 들어와도 관리자 대시보드로 보낸다', async () => {
    for (const role of ['ADMIN', 'MANAGER']) {
      vi.clearAllMocks();
      mocked.login.mockResolvedValue(sessionOf(role) as never);
      const view = renderPage();

      submitLogin();

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin'));
      view.unmount();
    }
  });

  it('실패하면 사유를 화면에 남기고 이동하지 않는다', async () => {
    mocked.login.mockRejectedValue(axiosError(401));
    renderPage();

    submitLogin();

    await waitFor(() =>
      expect(screen.getByText(/이메일 또는 비밀번호가 올바르지 않습니다/)).toBeInTheDocument());
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(mocked.saveToken).not.toHaveBeenCalled();
  });

  it('서버가 사유를 주면 그 문구를 그대로 보여 준다', async () => {
    mocked.login.mockRejectedValue(axiosError(423, '잠긴 계정입니다'));
    renderPage();

    submitLogin();

    await waitFor(() => expect(screen.getByText(/잠긴 계정입니다/)).toBeInTheDocument());
  });
});

describe('Login — 데모 자동로그인', () => {
  it('일반 사용자 데모는 주문 화면, 운영자 데모는 관리자 화면으로 간다', async () => {
    mocked.autoLogin.mockResolvedValue(sessionOf('USER') as never);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /일반 사용자/ }));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/order'));
    expect(mocked.autoLogin).toHaveBeenCalledWith('USER');

    fireEvent.click(await screen.findByRole('button', { name: /관리자$/ }));
    await waitFor(() => expect(mocked.autoLogin).toHaveBeenCalledWith('ADMIN'));
    expect(mockNavigate).toHaveBeenCalledWith('/admin');
  });

  it('매니저 데모도 관리자 화면으로 간다', async () => {
    mocked.autoLogin.mockResolvedValue(sessionOf('MANAGER') as never);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /매니저/ }));

    await waitFor(() => expect(mocked.autoLogin).toHaveBeenCalledWith('MANAGER'));
    expect(mockNavigate).toHaveBeenCalledWith('/admin');
  });

  it('404 는 장애가 아니라 데모 모드가 꺼진 것 — 켜는 방법을 알려 준다', async () => {
    mocked.autoLogin.mockRejectedValue(axiosError(404));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /일반 사용자/ }));

    await waitFor(() => expect(screen.getByText(/데모 모드가 비활성 상태입니다/)).toBeInTheDocument());
    expect(screen.getByText(/lemuel.demo.enabled=true/)).toBeInTheDocument();
  });

  it('404 가 아닌 실패는 일반 오류로 알린다', async () => {
    mocked.autoLogin.mockRejectedValue(axiosError(500));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /일반 사용자/ }));

    await waitFor(() => expect(screen.getByText(/자동 로그인에 실패했습니다/)).toBeInTheDocument());
  });
});

describe('Login — 게스트 둘러보기', () => {
  it('게스트는 토큰을 받고 메인으로 간다', async () => {
    mocked.guestLogin.mockResolvedValue(sessionOf('GUEST') as never);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /게스트 둘러보기/ }));

    await waitFor(() => expect(mocked.saveToken).toHaveBeenCalled());
    expect(mockNavigate).toHaveBeenCalledWith('/');
  });

  it('404 는 게스트 모드가 꺼진 것으로 안내한다', async () => {
    mocked.guestLogin.mockRejectedValue(axiosError(404));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /게스트 둘러보기/ }));

    await waitFor(() => expect(screen.getByText(/게스트 모드가 비활성 상태입니다/)).toBeInTheDocument());
  });

  it('그 외 실패는 일반 오류로 알린다', async () => {
    mocked.guestLogin.mockRejectedValue(axiosError(503));
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /게스트 둘러보기/ }));

    await waitFor(() => expect(screen.getByText(/게스트 진입에 실패했습니다/)).toBeInTheDocument());
  });
});

describe('Login — 이동 링크', () => {
  it('관리자 로그인·회원가입·비밀번호 찾기로 각각 보낸다', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /관리자 페이지로 가기/ }));
    expect(mockNavigate).toHaveBeenCalledWith('/admin/login');

    fireEvent.click(screen.getByRole('button', { name: '비밀번호를 잊으셨나요?' }));
    expect(mockNavigate).toHaveBeenCalledWith('/forgot-password');

    fireEvent.click(screen.getByRole('button', { name: /회원가입 →/ }));
    expect(mockNavigate).toHaveBeenCalledWith('/register');
  });
});
