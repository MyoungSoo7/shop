import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Register from '@/pages/Register';
import { authApi } from '@/api/auth';
import { UserResponse } from '@/types';

vi.mock('@/api/auth', () => ({
  authApi: {
    register: vi.fn(),
    login: vi.fn(),
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
      <Register />
    </MemoryRouter>
  );

describe('Register', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  // ─── 렌더링 ──────────────────────────────────────────────
  describe('렌더링', () => {
    it('이메일, 비밀번호, 비밀번호 확인 입력 필드가 렌더링된다', () => {
      renderPage();

      expect(screen.getByPlaceholderText('이메일')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('비밀번호')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('비밀번호 확인')).toBeInTheDocument();
    });

    it('회원가입 버튼이 렌더링된다', () => {
      renderPage();
      expect(screen.getByRole('button', { name: '회원가입' })).toBeInTheDocument();
    });

    it('로그인 링크 버튼이 렌더링된다', () => {
      renderPage();
      expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument();
    });
  });

  // ─── 유효성 검사 ─────────────────────────────────────────
  describe('유효성 검사', () => {
    it('비밀번호가 일치하지 않으면 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'user@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'differentpass');
      await user.click(screen.getByRole('button', { name: '회원가입' }));

      expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toBeInTheDocument();
      expect(authApi.register).not.toHaveBeenCalled();
    });
  });

  // ─── 회원가입 성공 ────────────────────────────────────────
  describe('회원가입 성공', () => {
    it('회원가입 성공 후 자동 로그인하여 /order로 이동한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockResolvedValueOnce({ id: 1, email: 'new@test.com', createdAt: '', updatedAt: '' });
      vi.mocked(authApi.login).mockResolvedValueOnce({ token: 'jwt', email: 'new@test.com', role: 'USER' });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'new@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: '회원가입' }));

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/order'));
      expect(authApi.saveToken).toHaveBeenCalled();
    });

    it('자동 로그인 실패 시 /login으로 이동한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockResolvedValueOnce({ id: 1, email: 'new@test.com', createdAt: '', updatedAt: '' });
      vi.mocked(authApi.login).mockRejectedValueOnce(new Error('login failed'));
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'new@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: '회원가입' }));

      await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/login'));
    });
  });

  // ─── 회원가입 실패 ────────────────────────────────────────
  describe('회원가입 실패', () => {
    it('이메일 중복(409) 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockRejectedValueOnce({ response: { status: 409 } });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'dup@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: '회원가입' }));

      await waitFor(() => expect(screen.getByText('이미 사용 중인 이메일입니다.')).toBeInTheDocument());
    });

    it('서버 오류 시 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.register).mockRejectedValueOnce({
        response: { status: 500, data: { message: '서버 오류' } },
      });
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'user@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: '회원가입' }));

      await waitFor(() => expect(screen.getByText('서버 오류')).toBeInTheDocument());
    });

    it('제출 중에는 버튼이 비활성화된다', async () => {
      const user = userEvent.setup();
      let resolveRegister!: (value: UserResponse) => void;
      vi.mocked(authApi.register).mockImplementationOnce(
        () => new Promise(r => { resolveRegister = r; })
      );
      renderPage();

      await user.type(screen.getByPlaceholderText('이메일'), 'user@test.com');
      await user.type(screen.getByPlaceholderText('비밀번호'), 'password123');
      await user.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
      await user.click(screen.getByRole('button', { name: '회원가입' }));

      await waitFor(() => expect(screen.getByRole('button', { name: '가입 중...' })).toBeDisabled());

      resolveRegister({ id: 1, email: 'user@test.com', createdAt: '', updatedAt: '' });
    });
  });

  // ─── 내비게이션 ──────────────────────────────────────────
  describe('내비게이션', () => {
    it('로그인 버튼 클릭 시 /login으로 이동한다', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByRole('button', { name: '로그인' }));

      expect(mockNavigate).toHaveBeenCalledWith('/login');
    });
  });
});