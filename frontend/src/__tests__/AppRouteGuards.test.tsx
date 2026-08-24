import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '@/App';

/**
 * App.tsx 의 라우트 가드 3종 — ProtectedRoute · AdminManagerRoute · AdminOnlyRoute.
 *
 * 셋은 서로 *다른 곳으로* 튕긴다. 미인증일 때 ProtectedRoute 는 사용자 로그인,
 * 나머지 둘은 관리자 로그인으로 보내고, 권한 부족일 때 AdminOnlyRoute 만 로그인이
 * 아니라 `/admin` 으로 되돌린다. 눈으로는 다 '막혔다'로 보여서 뒤바뀌어도 안 들킨다.
 * 목적지까지 확인하는 게 이 파일의 일이다.
 */

// 무거운 실제 화면 대신 표식만 그린다 — 여기서 볼 것은 화면 내용이 아니라 도달 여부다.
vi.mock('@/pages/OrderPage', () => ({ default: () => <div>주문 화면</div> }));
vi.mock('@/pages/system/MenuManagementPage', () => ({
  default: () => <div>메뉴 관리 화면</div>,
}));
vi.mock('@/pages/AdminDashboardPage', () => ({
  default: () => <div>관리자 대시보드</div>,
}));

// AppWorkforceAccess.test.tsx 와 같은 이유 — `<App/>` 는 AuthProvider 가 `/users/me` 를
// 부르는데, 개발 PC 에 프론트가 떠 있으면 그 요청이 실제로 나가 401 을 받고 인터셉터가
// 토큰을 지운다. 그러면 가드 판정이 환경에 따라 흔들린다.
vi.mock('@/api/user', () => ({
  userApi: { me: () => Promise.reject(new Error('network disabled in test')) },
}));

// Layout 은 현재 메뉴(`[aria-current="page"]`)를 가로 스크롤로 끌어온다. jsdom 에는
// scrollIntoView 자체가 없어서, 활성 메뉴가 *잡히는* 경로에서만 터진다 — 그래서
// /workforce 처럼 매칭되는 메뉴가 없는 기존 테스트는 우연히 통과했다.
// (AiChatPage.test.tsx 도 같은 이유로 같은 스텁을 둔다.)
Element.prototype.scrollIntoView = vi.fn();

const visit = (path: string) => {
  window.history.pushState({}, '', path);
  render(<App />);
};

const signIn = (role: string) => {
  localStorage.setItem('access_token', `${role.toLowerCase()}-token`);
  localStorage.setItem('user_email', `${role.toLowerCase()}@example.com`);
  localStorage.setItem('user_role', role);
};

describe('라우트 가드', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('ProtectedRoute (/order — 역할 무관, 인증만 요구)', () => {
    it('미인증이면 사용자 로그인으로 보낸다', async () => {
      visit('/order');

      expect(
        await screen.findByRole('heading', { name: '사용자 로그인' }),
      ).toBeInTheDocument();
      expect(screen.queryByText('주문 화면')).not.toBeInTheDocument();
    });

    it('로그인한 일반 사용자에게 열어 준다', async () => {
      signIn('USER');

      visit('/order');

      expect(await screen.findByText('주문 화면')).toBeInTheDocument();
    });
  });

  describe('AdminManagerRoute (/admin — ADMIN·MANAGER 공용)', () => {
    it('미인증이면 관리자 로그인으로 보낸다', async () => {
      visit('/admin');

      expect(await screen.findByText('관리자 시스템')).toBeInTheDocument();
      expect(screen.queryByText('관리자 대시보드')).not.toBeInTheDocument();
    });

    it('권한이 없는 일반 사용자는 사용자 로그인으로 보낸다', async () => {
      signIn('USER');

      visit('/admin');

      expect(
        await screen.findByRole('heading', { name: '사용자 로그인' }),
      ).toBeInTheDocument();
      expect(screen.queryByText('관리자 대시보드')).not.toBeInTheDocument();
    });

    it.each(['ADMIN', 'MANAGER'])('%s 에게 열어 준다', async (role) => {
      signIn(role);

      visit('/admin');

      expect(await screen.findByText('관리자 대시보드')).toBeInTheDocument();
    });
  });

  describe('AdminOnlyRoute (/admin/system/menus — 최고 관리자 전용)', () => {
    it('미인증이면 관리자 로그인으로 보낸다', async () => {
      visit('/admin/system/menus');

      expect(await screen.findByText('관리자 시스템')).toBeInTheDocument();
      expect(screen.queryByText('메뉴 관리 화면')).not.toBeInTheDocument();
    });

    it('MANAGER 는 로그인이 아니라 관리자 대시보드로 되돌린다', async () => {
      // 여기가 다른 가드와 갈리는 지점이다 — 이미 관리자 콘솔에 들어와 있는 사람을
      // 로그인 화면으로 내쫓지 않고, 자기 권한으로 볼 수 있는 곳에 남겨 둔다.
      signIn('MANAGER');

      visit('/admin/system/menus');

      expect(await screen.findByText('관리자 대시보드')).toBeInTheDocument();
      expect(screen.queryByText('메뉴 관리 화면')).not.toBeInTheDocument();
    });

    it('ADMIN 에게 시스템 관리 화면을 열어 준다', async () => {
      signIn('ADMIN');

      visit('/admin/system/menus');

      expect(await screen.findByText('메뉴 관리 화면')).toBeInTheDocument();
    });
  });
});
