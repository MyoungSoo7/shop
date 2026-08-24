import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SideNavLayout from '@/components/SideNavLayout';
import { MenuContext } from '@/contexts/useMenus';
import { resolveFallbackMenus } from '@/data/menuFallback';
import type { NavMenuNode } from '@/api/menu';

const renderAt = (pathname: string, menus: NavMenuNode[]) =>
  render(
    <MemoryRouter initialEntries={[pathname]}>
      <MenuContext.Provider value={{ menus, loading: false, degraded: false, refresh: async () => {} }}>
        <SideNavLayout><div>본문</div></SideNavLayout>
      </MenuContext.Provider>
    </MemoryRouter>,
  );

describe('SideNavLayout — 세 벌이던 사이드바 셸을 트리 하나로', () => {
  it('배송 경로에서는 배송 하위 2개를 그린다 (ADMIN)', () => {
    renderAt('/admin/shipping', resolveFallbackMenus('ADMIN'));

    ['배송 관리', '배송비 정책'].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument();
    });
  });

  it('MANAGER 에게는 배송비 정책이 보이지 않는다 — 서버가 ADMIN 으로 막는 표면이다', () => {
    renderAt('/admin/shipping', resolveFallbackMenus('MANAGER'));

    expect(screen.getByText('배송 관리')).toBeInTheDocument();
    expect(screen.queryByText('배송비 정책')).not.toBeInTheDocument();
  });

  it('시스템 경로에서는 사이드바 제목이 "시스템 관리" 다', () => {
    renderAt('/admin/system/rbac', resolveFallbackMenus('ADMIN'));

    expect(screen.getByText('시스템 관리')).toBeInTheDocument();
    expect(screen.getByText('System Administration')).toBeInTheDocument();
  });

  it('현재 항목에 aria-current=page 가 하나만 붙는다', () => {
    const { container } = renderAt('/admin/system/refunds', resolveFallbackMenus('ADMIN'));

    const current = container.querySelectorAll('[aria-current="page"]');
    expect(current).toHaveLength(1);
    expect(current[0].textContent).toContain('환불 운영');
  });

  it('트리가 비어 있으면 사이드바 없이 본문만 그린다', () => {
    renderAt('/admin/shipping', []);

    expect(screen.getByText('본문')).toBeInTheDocument();
    expect(screen.queryByText('배송 관리')).not.toBeInTheDocument();
  });
});
