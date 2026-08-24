import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useMenus } from '@/contexts/useMenus';
import { findActiveTrail, isOnActiveTrail } from '@/lib/menuTree';

interface SideNavLayoutProps {
  children: React.ReactNode;
}

/**
 * 좌측 사이드바 셸 — 정산·CEO·시스템 세 벌로 나뉘어 있던 레이아웃을 하나로 합친 것.
 *
 * <p>세 컴포넌트는 마크업이 완전히 같고 항목 배열과 머리글만 달랐다. 항목이 메뉴 트리에서
 * 오게 된 이상 남는 차이가 없으므로, 현재 경로가 속한 최상위 묶음을 찾아 그 하위를 그린다.
 * 화면을 새로 붙일 때 더 이상 레이아웃 파일을 건드리지 않아도 된다 — 메뉴 행만 추가하면 된다.
 */
const SideNavLayout: React.FC<SideNavLayoutProps> = ({ children }) => {
  const location = useLocation();
  const { menus } = useMenus();

  const trail = findActiveTrail(menus, location.pathname);
  const group = trail[0] ?? null;
  const items = group?.children ?? [];

  // 사이드바로 그릴 하위가 없으면(권한으로 전부 걸러졌거나 트리 미로딩) 본문만 그린다.
  // 빈 사이드바 껍데기를 남기면 콘텐츠 폭만 좁아지고 얻는 게 없다.
  if (items.length === 0) {
    return (
      <div className="min-h-screen bg-gray-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
          <main className="min-w-0">{children}</main>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="flex flex-col lg:flex-row gap-6">

          {/* ── 좌측 사이드바 ─────────────────────────────────────────── */}
          <aside className="w-full lg:w-64 flex-shrink-0">
            <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden lg:sticky lg:top-8">
              <div className="px-5 py-4 border-b border-gray-100 bg-gray-900">
                <p className="text-white font-bold flex items-center gap-2">
                  <span>{group?.icon}</span> {group?.name}
                </p>
                {group?.description && (
                  <p className="text-gray-400 text-xs mt-0.5">{group.description}</p>
                )}
              </div>
              <nav className="p-2 space-y-1">
                {items.map((item) => {
                  const active = isOnActiveTrail(item, trail);
                  return (
                    <Link
                      key={item.id}
                      to={item.path ?? '#'}
                      aria-current={active ? 'page' : undefined}
                      className={`flex items-start gap-3 px-3 py-2.5 rounded-lg transition-colors ${
                        active
                          ? 'bg-gray-900 text-white'
                          : 'text-gray-700 hover:bg-gray-100'
                      }`}
                    >
                      <span className="text-lg leading-none mt-0.5">{item.icon}</span>
                      <span className="flex-1 min-w-0">
                        <span className="block text-sm font-semibold">{item.name}</span>
                        {item.description && (
                          <span className={`block text-xs mt-0.5 ${active ? 'text-gray-300' : 'text-gray-400'}`}>
                            {item.description}
                          </span>
                        )}
                      </span>
                    </Link>
                  );
                })}
              </nav>
            </div>
          </aside>

          {/* ── 우측 콘텐츠 ──────────────────────────────────────────── */}
          <main className="flex-1 min-w-0">{children}</main>
        </div>
      </div>
    </div>
  );
};

export default SideNavLayout;
