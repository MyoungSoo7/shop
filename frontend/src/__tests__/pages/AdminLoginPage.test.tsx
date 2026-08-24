import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AdminLoginPage from '@/pages/AdminLoginPage';
import { authApi } from '@/api/auth';
import { LoginResponse, UserResponse } from '@/types';

vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    saveToken: vi.fn(),
    logout: vi.fn(),
    getCurrentUser: vi.fn(),
    isAuthenticated: vi.fn(),
  },
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminLoginPage />
    </MemoryRouter>
  );

/** 로그인 폼의 submit 버튼 (type="submit") 반환 */
const getLoginSubmitBtn = () => {
  const form = screen.getByPlaceholderText('비밀번호').closest('form')!;
  return within(form).getByRole('button');
};

/** 회원가입 탭 버튼 (tab switcher) 클릭 */
const clickRegisterTab = async (user: ReturnType<typeof userEvent.setup>) => {
  // 탭 영역의 "회원가입" 버튼 — 탭 컨테이너 내부에 두 개의 탭 버튼이 있음
  const tabBtns = screen.getAllByRole('button', { name: '회원가입' });
  // 탭 버튼은 type="button", register form의 submit은 "MANAGER 계정 생성" / "ADMIN 계정 생성"
  await user.click(tabBtns[0]);
};

describe('AdminLoginPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  // ─── 렌더링 ──────────────────────────────────────────────
  describe('렌더링', () => {
    it('Lemuel 로고와 관리자 시스템 텍스트가 표시된다', () => {
      renderPage();

      expect(screen.getByText('Lemuel')).toBeInTheDocument();
      expect(screen.getByText('관리자 시스템')).toBeInTheDocument();
    });

    it('로그인/회원가입 탭 버튼이 2개 렌더링된다', () => {
      renderPage();

      // 탭 영역: type="button" 인 로그인·회원가입 탭 + submit 버튼
      const loginBtns = screen.getAllByRole('button', { name: '로그인' });
      expect(loginBtns.length).toBeGreaterThanOrEqual(1);
      expect(screen.getByRole('button', { name: '회원가입' })).toBeInTheDocument();
    });

    it('초기에는 로그인 폼이 표시된다', () => {
      renderPage();

      expect(screen.getByPlaceholderText('이메일')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('비밀번호')).toBeInTheDocument();
      expect(screen.queryByText('역할 선택')).not.toBeInTheDocument();
    });
  });

  // ─── 탭 전환 ─────────────────────────────────────────────
  describe('탭 전환', () => {
    it('회원가입 탭 클릭 시 역할 선택 버튼이 나타난다', async () => {
      const user = userEvent.setup();
      renderPage();

      await clickRegisterTab(user);

      expect(screen.getByText('역할 선택')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'MANAGER' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'ADMIN' })).toBeInTheDocument();
    });

    it('회원가입 탭 클릭 시 비밀번호 확인 입력 필드가 나타난다', async () => {
      const user = userEvent.setup();
      renderPage();

      await clickRegisterTab(user);

      expect(screen.getByPlaceholderText('비밀번호 확인')).toBeInTheDocument();
    });
  });

  // ─── 로그인 ───────────────────────────────────────────────
  describe('로그인', () => {
    it('ADMIN 계정으로 로그인하면 /admin으로 이동한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'admin@test.com', role: 'ADMIN' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'admin@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(getLoginSubmitBtn());

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin'));
      expect(authApi.saveToken).toHaveBeenCalled();
    });

    it('MANAGER 계정으로 로그인하면 /admin으로 이동한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'mgr@test.com', role: 'MANAGER' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'mgr@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(getLoginSubmitBtn());

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/admin'));
    });

    it('USER 역할로 로그인 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'user@test.com', role: 'USER' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'user@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(getLoginSubmitBtn());

      await waitFor(() =>
        expect(screen.getByText('관리자(ADMIN) 또는 매니저(MANAGER) 계정으로만 로그인할 수 있습니다.')).toBeInTheDocument()
      );
      expect(mockNavigate).not.toHaveBeenCalled();
    });

    it('로그인 실패 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.login).mockRejectedValueOnce({
        response: { data: { message: '이메일 또는 비밀번호가 올바르지 않습니다.' } },
      });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'bad@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'wrong');
      await user.click(getLoginSubmitBtn());

      await waitFor(() =>
        expect(screen.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).toBeInTheDocument()
      );
    });

    it('로그인 중에는 submit 버튼이 비활성화된다', async () => {
      const user = userEvent.setup();
      let resolveLogin!: (value: LoginResponse) => void;
      vi.mocked(authApi.login).mockImplementationOnce(() => new Promise(r => { resolveLogin = r; }));
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'admin@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password');
      await user.click(getLoginSubmitBtn());

      await waitFor(() => {
        const btn = getLoginSubmitBtn();
        expect(btn).toBeDisabled();
        expect(btn).toHaveTextContent('로그인 중...');
      });

      resolveLogin({ token: 'jwt', email: 'admin@test.com', role: 'ADMIN' });
    });
  });

  // ─── 회원가입 ─────────────────────────────────────────────
  describe('회원가입', () => {
    it('비밀번호 불일치 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      renderPage();
      await clickRegisterTab(user);

      await user.type(screen.getByPlaceholderText('이메일'), 'mgr@test.com');
      const pwInput = screen.getByPlaceholderText('비밀번호');
      await user.type(pwInput, 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'differentpass');

      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      await waitFor(() =>
        expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toBeInTheDocument()
      );
      expect(authApi.register).not.toHaveBeenCalled();
    });

    it('MANAGER 계정 생성 성공 시 성공 메시지가 표시된다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockResolvedValueOnce({ id: 1, email: 'mgr@test.com', createdAt: '', updatedAt: '' });
      renderPage();
      await clickRegisterTab(user);

      await user.type(screen.getByPlaceholderText('이메일'), 'mgr@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      await waitFor(() =>
        expect(screen.getByText('MANAGER 계정이 생성되었습니다. 로그인해주세요.')).toBeInTheDocument()
      );
    });

    it('ADMIN 역할 선택 후 계정 생성 버튼 텍스트가 변경된다', async () => {
      const user = userEvent.setup();
      renderPage();
      await clickRegisterTab(user);

      await user.click(screen.getByRole('button', { name: 'ADMIN' }));

      expect(screen.getByRole('button', { name: 'ADMIN 계정 생성' })).toBeInTheDocument();
    });

    it('이메일 중복 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockRejectedValueOnce({ response: { status: 409 } });
      renderPage();
      await clickRegisterTab(user);

      await user.type(screen.getByPlaceholderText('이메일'), 'dup@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      await waitFor(() => expect(screen.getByText('이미 사용 중인 이메일입니다.')).toBeInTheDocument());
    });

    it('가입 중에는 submit 버튼이 비활성화된다', async () => {
      const user = userEvent.setup();
      let resolveRegister!: (value: UserResponse) => void;
      vi.mocked(authApi.register).mockImplementationOnce(
        () => new Promise(r => { resolveRegister = r; })
      );
      renderPage();
      await clickRegisterTab(user);

      await user.type(screen.getByPlaceholderText('이메일'), 'mgr@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: 'MANAGER 계정 생성' }));

      await waitFor(() => expect(screen.getByRole('button', { name: '가입 중...' })).toBeDisabled());

      resolveRegister({ id: 1, email: 'mgr@test.com', createdAt: '', updatedAt: '' });
    });
  });
});