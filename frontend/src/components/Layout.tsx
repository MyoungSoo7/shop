import React, { useEffect, useRef } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useCart } from '@/contexts/useCart';
import { useMenus } from '@/contexts/useMenus';
import { findActiveTrail } from '@/lib/menuTree';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const user = authApi.getCurrentUser();
  const { totalCount } = useCart();

  const isAdminOrManager = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  const handleLogout = () => {
    authApi.logout();
    // ADMIN / MANAGER → 관리자 로그인, USER → 일반 로그인
    navigate(isAdminOrManager ? '/admin/login' : '/login');
  };

  const isActive = (path: string) =>
    location.pathname === path || location.pathname.startsWith(path + '/');

  /**
   * 내비 항목 속성. `shrink-0` 은 가로 스크롤 스트립 안에서 항목이 찌그러지지 않게 하고,
   * `aria-current` 는 스크린리더에 현재 위치를 알리는 동시에 아래 자동 스크롤의 선택자가 된다.
   */
  const navItemProps = (active: boolean, variant: 'admin' | 'user') => ({
    'aria-current': active ? ('page' as const) : undefined,
    className: [
      // `tap-target`: 내비 항목은 실측 높이 36px 로 Apple 권장 44pt 미만이다. 항목을 키우면 헤더
      // 높이(h-16)와 데스크톱 배치가 흔들리므로, 터치 환경에서만 히트 영역을 44×44 로 넓힌다.
      // 폭은 88px 안팎이라 44px 오버레이가 이웃 항목을 가리지 않는다.
      'tap-target px-4 py-2 rounded-lg font-medium transition-colors text-sm shrink-0',
      active
        ? (variant === 'admin' ? 'bg-gray-800 text-white' : 'bg-blue-600 text-white')
        : (variant === 'admin' ? 'text-gray-600 hover:bg-gray-100' : 'text-gray-700 hover:bg-blue-50'),
    ].join(' '),
  });

  /**
   * 상단 네비 항목은 메뉴 트리의 최상위 노드다(정본은 `menus` 테이블). 활성 판정은
   * "현재 경로의 가장 긴 접두사인 메뉴"를 고르는 한 가지 규칙으로 통일했다 —
   * 하드코딩 시절 `/admin`(대시보드)이 모든 `/admin/**` 에서 함께 켜지던 문제와,
   * 그걸 피하려고 손으로 나열한 정산 그룹 판정에서 `/admin/payouts` 가 빠져 있던
   * 버그를 규칙 하나로 함께 없앤다.
   */
  const { menus } = useMenus();
  const activeRootId = findActiveTrail(menus, location.pathname)[0]?.id ?? null;

  /**
   * 좁은 화면에서 내비는 가로 스크롤 스트립이라 뒤쪽 항목이 화면 밖에 있다. 그대로 두면 사용자가
   * 지금 어느 섹션에 있는지 볼 수 없으므로, 경로가 바뀔 때마다 활성 항목을 스트립 안으로 끌어온다.
   * `block: 'nearest'` 라 세로 스크롤은 건드리지 않고, 넓은 화면에서는 이미 다 보이므로 무동작이다.
   */
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    navRef.current
      ?.querySelector('[aria-current="page"]')
      ?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }, [location.pathname]);

  return (
    /* 좌우 safe-area 는 `#root`(index.css)가 앱 전체에 한 번 건다 — 셸을 거치지 않는 화면까지
       덮기 위해서다. 여기서는 세로 인셋만 헤더·푸터가 각각 처리한다. */
    <div className="min-h-screen bg-gray-50">
      {/* Header — 설치형(standalone)에서는 상태바가 문서 위에 겹쳐 뜨므로(black-translucent)
          헤더가 그만큼 아래에서 시작해야 로고·내비가 시계·배터리에 가리지 않는다. */}
      <header className={`bg-white shadow pt-safe ${isAdminOrManager ? 'border-b-2 border-gray-800' : ''}`}>
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center h-16">

            {/* 왼쪽: 로고 + 내비게이션.
                `min-w-0` 이 없으면 flex 아이템의 기본 최소 폭(min-content) 때문에 좁은 화면에서
                이 블록이 줄어들지 못하고 문서 전체를 밀어 가로 스크롤을 만든다(390px 에서 문서
                634px 로 실측). 로고는 줄이지 않고, 남는 폭이 모자라면 내비를 가로 스크롤시킨다. */}
            <div className="flex items-center space-x-6 min-w-0">
              <Link to={isAdminOrManager ? '/admin' : '/'} className="text-2xl font-bold text-blue-600 shrink-0">
                Lemuel
              </Link>

              {/* ── 내비게이션 ── 항목·순서·노출 역할은 전부 menus 테이블이 정한다.
                  역할별 분기는 서버가 이미 끝냈으므로 여기서는 색만 고른다. */}
              {user && menus.length > 0 && (
                <nav ref={navRef} className="flex space-x-1 min-w-0 overflow-x-auto whitespace-nowrap [scrollbar-width:thin]">
                  {menus.map((item) => (
                    <Link
                      key={item.id}
                      to={item.path ?? '#'}
                      {...navItemProps(item.id === activeRootId, isAdminOrManager ? 'admin' : 'user')}
                    >
                      {item.label}
                    </Link>
                  ))}
                </nav>
              )}
            </div>

            {/* 오른쪽: 사용자 정보 + 액션. 내비와 달리 이쪽은 줄이지 않는다(`shrink-0`) —
                로그아웃은 어느 폭에서도 눌러야 한다. 대신 좁은 화면에서는 이메일을 숨기고
                역할 뱃지만 남긴다(이메일 253px 중 129px). 숨긴 값은 뱃지 title 로 확인할 수 있다. */}
            {user && (
              <div className="flex items-center space-x-3 shrink-0">
                <div className="text-sm text-gray-700 flex items-center gap-2">
                  <span className="font-medium hidden sm:inline">{user.email}</span>
                  <span
                    title={user.email}
                    className={`px-2 py-0.5 rounded text-xs font-semibold ${
                      user.role === 'ADMIN'   ? 'bg-red-100 text-red-800'    :
                      user.role === 'MANAGER' ? 'bg-purple-100 text-purple-800' :
                                                'bg-blue-100 text-blue-800'
                    }`}
                  >
                    {user.role}
                  </span>
                </div>

                {/* 장바구니 — USER 전용 */}
                {user.role === 'USER' && (
                  <Link
                    to="/cart"
                    /* tap-target: 실측 40×40 — 터치 환경에서만 눌리는 영역을 44×44 로 넓힌다. */
                    className="tap-target relative p-2 text-gray-600 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                    title="장바구니"
                  >
                    <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8"
                        d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z" />
                    </svg>
                    {totalCount > 0 && (
                      <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center px-0.5">
                        {totalCount > 99 ? '99+' : totalCount}
                      </span>
                    )}
                  </Link>
                )}

                {/* 마이페이지 — USER 전용 */}
                {user.role === 'USER' && (
                  <Link
                    to="/mypage"
                    /* tap-target: 실측 68×32 — 마이페이지는 자주 눌리는 진입점이라 히트 영역을 넓힌다. */
                    className={`tap-target flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold transition-colors ${
                      isActive('/mypage')
                        ? 'bg-blue-600 text-white'
                        : 'text-gray-600 hover:text-blue-600 hover:bg-blue-50'
                    }`}
                    title="마이페이지"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                        d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                    </svg>
                    MY
                  </Link>
                )}

                <button
                  onClick={handleLogout}
                  className="tap-target px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 transition-colors text-sm"
                >
                  로그아웃
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main>{children}</main>

      {/* Footer */}
      {/* 홈 인디케이터(하단 바)가 문서 위에 겹치는 기기에서 푸터 문구가 가리지 않게 띄운다. */}
      <footer className="bg-white border-t mt-12 pb-safe">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
          <div className="text-center text-gray-600">
            <p className="text-sm">© 2024 Lemuel Settlement System. All rights reserved.</p>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default Layout;
