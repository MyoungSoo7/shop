import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ForgotPassword from '@/pages/ForgotPassword';
import ResetPassword from '@/pages/ResetPassword';
import api from '@/api/axios';

const navigate = vi.fn();

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigate };
});

vi.mock('@/api/axios', () => ({
  default: { post: vi.fn() },
}));

const mockedApi = vi.mocked(api);

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

describe('ForgotPassword', () => {
  const renderPage = () => render(<MemoryRouter><ForgotPassword /></MemoryRouter>);

  it('이메일을 보내면 안내 화면으로 바뀐다 (유효기간까지 알려 준다)', async () => {
    mockedApi.post.mockResolvedValue({ data: undefined } as never);
    renderPage();

    await userEvent.type(screen.getByPlaceholderText('이메일 주소'), 'user@example.com');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 재설정 링크 보내기' }));

    expect(await screen.findByText('이메일 발송 완료!')).toBeInTheDocument();
    expect(screen.getByText(/30분간 유효/)).toBeInTheDocument();
    expect(mockedApi.post).toHaveBeenCalledWith('/users/password-reset/request', {
      email: 'user@example.com',
    });
  });

  it('발송 후 로그인 페이지로 돌아갈 수 있다', async () => {
    mockedApi.post.mockResolvedValue({ data: undefined } as never);
    renderPage();
    await userEvent.type(screen.getByPlaceholderText('이메일 주소'), 'u@e.com');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 재설정 링크 보내기' }));
    await screen.findByText('이메일 발송 완료!');

    await userEvent.click(screen.getByRole('button', { name: '로그인 페이지로 돌아가기 →' }));

    expect(navigate).toHaveBeenCalledWith('/login');
  });

  it('실패하면 서버 사유를 보여 주고 폼을 유지한다', async () => {
    mockedApi.post.mockRejectedValue({ response: { data: { message: '가입되지 않은 이메일' } } });
    renderPage();

    await userEvent.type(screen.getByPlaceholderText('이메일 주소'), 'nobody@example.com');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 재설정 링크 보내기' }));

    expect(await screen.findByText('가입되지 않은 이메일')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('이메일 주소')).toBeInTheDocument();
  });

  it('로그인·회원가입으로 이동할 수 있다', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '← 로그인으로 돌아가기' }));
    expect(navigate).toHaveBeenCalledWith('/login');

    await userEvent.click(screen.getByRole('button', { name: '회원가입' }));
    expect(navigate).toHaveBeenCalledWith('/register');
  });
});

describe('ResetPassword', () => {
  const renderWithToken = (token?: string) =>
    render(
      <MemoryRouter initialEntries={[token ? `/reset?token=${token}` : '/reset']}>
        <ResetPassword />
      </MemoryRouter>,
    );

  it('토큰이 없으면 폼 대신 안내와 이동 버튼을 보여 준다', async () => {
    renderWithToken();

    expect(screen.getByText('유효하지 않은 링크입니다.')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/새 비밀번호/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '비밀번호 찾기로 이동 →' }));
    expect(navigate).toHaveBeenCalledWith('/forgot-password');
  });

  it('두 입력이 다르면 서버를 부르지 않고 막는다', async () => {
    renderWithToken('t-1');

    await userEvent.type(screen.getByPlaceholderText('새 비밀번호 (최소 8자)'), 'password123');
    await userEvent.type(screen.getByPlaceholderText('비밀번호 확인'), 'password124');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));

    expect(await screen.findByText('비밀번호가 일치하지 않습니다.')).toBeInTheDocument();
    expect(mockedApi.post).not.toHaveBeenCalled();
  });

  it('8자 미만도 서버를 부르지 않고 막는다', async () => {
    renderWithToken('t-1');

    await userEvent.type(screen.getByPlaceholderText('새 비밀번호 (최소 8자)'), 'short');
    await userEvent.type(screen.getByPlaceholderText('비밀번호 확인'), 'short');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));

    expect(await screen.findByText('비밀번호는 최소 8자 이상이어야 합니다.')).toBeInTheDocument();
    expect(mockedApi.post).not.toHaveBeenCalled();
  });

  it('토큰과 새 비밀번호를 보내고 완료 화면으로 바뀐다', async () => {
    mockedApi.post.mockResolvedValue({ data: undefined } as never);
    renderWithToken('t-1');

    await userEvent.type(screen.getByPlaceholderText('새 비밀번호 (최소 8자)'), 'password123');
    await userEvent.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));

    await waitFor(() =>
      expect(mockedApi.post).toHaveBeenCalledWith('/users/password-reset/confirm', {
        token: 't-1',
        newPassword: 'password123',
      }),
    );
    expect(await screen.findByText('비밀번호 변경 완료!')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '로그인 페이지로 이동 →' }));
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  it('만료된 토큰 등 서버 실패는 사유를 보여 준다', async () => {
    mockedApi.post.mockRejectedValue({ response: { data: { message: '만료된 토큰' } } });
    renderWithToken('t-1');

    await userEvent.type(screen.getByPlaceholderText('새 비밀번호 (최소 8자)'), 'password123');
    await userEvent.type(screen.getByPlaceholderText('비밀번호 확인'), 'password123');
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));

    expect(await screen.findByText('만료된 토큰')).toBeInTheDocument();
  });
});
