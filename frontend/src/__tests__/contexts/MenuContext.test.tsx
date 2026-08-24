import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { MenuProvider } from '@/contexts/MenuContext';
import { useMenus } from '@/contexts/useMenus';
import { navMenuApi, type NavMenuNode } from '@/api/menu';
import { authApi } from '@/api/auth';

/**
 * 메뉴 공급자 — <b>로그인 시점에 메뉴가 다시 조회되는가</b>.
 *
 * <p>막는 것: "로그인했는데 네비게이션이 통째로 비어 있고, 새로고침해야 나타나는" 상태.
 * 2026-08-23 실측 — 관리자로 로그인하면 `/admin` 이 로고와 로그아웃 버튼만 있는 화면으로
 * 떴다. 서버는 정상이었다(`menus` 54행, ADMIN 토큰으로 `/api/menus/me` 8,218바이트).
 *
 * <p>원인은 공급자가 역할을 <b>렌더 시점 localStorage 읽기</b>로 가져온 것이다. 앱은 로그아웃
 * 상태로 마운트되어 익명으로 한 번 조회하고 `[]` 를 정상값으로 저장하는데, SPA 로그인은
 * 리마운트가 아니라서 그 효과가 다시 돌지 않았다. 하드 리로드만이 복구 경로였다.
 *
 * <p>그래서 이 테스트는 <b>리마운트 없이</b> 로그인시킨다 — 컴포넌트를 다시 렌더하면
 * 결함이 재현되지 않아 게이트가 공회전한다.
 */
vi.mock('@/api/menu', () => ({
  navMenuApi: { getMine: vi.fn() },
}));

const node = (id: number, name: string): NavMenuNode => ({
  id, name, label: name, path: `/p${id}`, icon: null,
  description: null, area: 'BACKOFFICE', type: 'ITEM', children: [],
});

const Probe = () => {
  const { menus, loading, degraded } = useMenus();
  return (
    <div>
      <span data-testid="count">{menus.length}</span>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="degraded">{String(degraded)}</span>
    </div>
  );
};

/** 서버는 토큰이 있을 때만 트리를 준다 — 실제 `/api/menus/me` 의 동작(무인증은 `[]`)과 같다. */
const serveByToken = () => {
  vi.mocked(navMenuApi.getMine).mockImplementation(async () =>
    (authApi.isAuthenticated() ? [node(1, '대시보드'), node(2, '정산')] : []));
};

describe('MenuProvider — 로그인 시점 재조회', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    serveByToken();
  });

  it('로그아웃 상태로 마운트하면 메뉴가 비어 있다', async () => {
    render(<MenuProvider><Probe /></MenuProvider>);
    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    expect(screen.getByTestId('count')).toHaveTextContent('0');
  });

  it('리마운트 없이 로그인해도 메뉴를 다시 받아온다 (새로고침 없이 네비게이션 복구)', async () => {
    render(<MenuProvider><Probe /></MenuProvider>);
    await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
    expect(screen.getByTestId('count')).toHaveTextContent('0');

    // SPA 로그인과 같은 경로 — 토큰만 저장되고 리마운트는 일어나지 않는다.
    await act(async () => {
      authApi.saveToken({ token: 't.t.t', email: 'admin@lemuel.local', role: 'ADMIN' });
      await Promise.resolve();
    });

    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('2'));
    expect(vi.mocked(navMenuApi.getMine).mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('로그아웃하면 메뉴를 비운다 — 남은 트리로 남의 화면을 가리키지 않는다', async () => {
    authApi.saveToken({ token: 't.t.t', email: 'admin@lemuel.local', role: 'ADMIN' });
    render(<MenuProvider><Probe /></MenuProvider>);
    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('2'));

    await act(async () => {
      authApi.logout();
      await Promise.resolve();
    });

    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('0'));
  });

  it('로그인 상태인데 서버가 빈 트리를 주면 폴백 스냅샷으로 버틴다', async () => {
    authApi.saveToken({ token: 't.t.t', email: 'admin@lemuel.local', role: 'ADMIN' });
    vi.mocked(navMenuApi.getMine).mockResolvedValue([]);

    render(<MenuProvider><Probe /></MenuProvider>);

    await waitFor(() => expect(screen.getByTestId('degraded')).toHaveTextContent('true'));
    expect(Number(screen.getByTestId('count').textContent)).toBeGreaterThan(0);
  });
});
