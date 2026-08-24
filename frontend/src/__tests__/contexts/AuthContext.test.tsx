import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { AuthProvider } from '@/contexts/AuthContext';
import { useAuth } from '@/contexts/useAuth';
import { userApi } from '@/api/user';
import { authApi } from '@/api/auth';

/**
 * 로그인 주체 공급자 — <b>로그인 시점에 주체를 다시 읽는가</b>.
 *
 * <p>막는 것: 로그인했는데 `userId` 가 null 로 남아, 그것에 매달린 기능(장바구니·내 정보)이
 * 새로고침 전까지 조용히 비활성이 되는 상태. MenuProvider 가 같은 이유로 네비게이션을 비운
 * 채 떴던 것과 한 뿌리다(2026-08-23) — 마운트는 로그아웃 상태에서 한 번뿐이고 SPA 로그인은
 * 리마운트가 아니다.
 *
 * <p>여기서도 <b>리마운트 없이</b> 로그인시킨다. 다시 렌더하면 결함이 재현되지 않는다.
 */
vi.mock('@/api/user', () => ({
  userApi: { me: vi.fn() },
}));

const me = {
  id: 7, email: 'admin@lemuel.local', role: 'ADMIN',
  name: null, phoneNumber: null, active: true, createdAt: '2026-08-01T00:00:00',
};

const Probe = () => {
  const { userId, loading } = useAuth();
  return (
    <div>
      <span data-testid="uid">{String(userId)}</span>
      <span data-testid="loading">{String(loading)}</span>
    </div>
  );
};

describe('AuthProvider — 로그인 시점 재조회', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    vi.mocked(userApi.me).mockResolvedValue(me);
  });

  it('토큰이 없으면 조회하지 않는다 — 로그인 화면에서 스스로를 쫓아내지 않는다', async () => {
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    expect(screen.getByTestId('uid')).toHaveTextContent('null');
    expect(userApi.me).not.toHaveBeenCalled();
  });

  it('리마운트 없이 로그인하면 주체를 읽어 온다', async () => {
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('uid')).toHaveTextContent('null'));

    await act(async () => {
      authApi.saveToken({ token: 't.t.t', email: me.email, role: me.role });
      await Promise.resolve();
    });

    await waitFor(() => expect(screen.getByTestId('uid')).toHaveTextContent('7'));
  });

  it('로그아웃하면 주체를 비운다', async () => {
    authApi.saveToken({ token: 't.t.t', email: me.email, role: me.role });
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('uid')).toHaveTextContent('7'));

    await act(async () => {
      authApi.logout();
      await Promise.resolve();
    });

    await waitFor(() => expect(screen.getByTestId('uid')).toHaveTextContent('null'));
  });
});
