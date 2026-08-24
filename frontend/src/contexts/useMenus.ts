import { createContext, useContext } from 'react';
import type { NavMenuNode } from '@/api/menu';

/**
 * 메뉴 컨텍스트와 소비 훅 — 컴포넌트(MenuProvider)와 파일을 분리한다
 * (컴포넌트 파일에서 값을 export 하면 Fast Refresh 가 상태를 잃는다).
 */
export interface MenuContextType {
  /** 서버가 내 권한으로 걸러 준 메뉴 트리 */
  menus: NavMenuNode[];
  /** 첫 로딩 중 — 이 동안의 빈 트리는 "메뉴가 없다"가 아니라 "아직 모른다"이다. */
  loading: boolean;
  /**
   * 폴백 스냅샷으로 그리고 있는 중인지. 서버 조회가 실패했거나 로그인 상태인데 빈 트리를
   * 받은 경우다 — 네비게이션이 통째로 사라지는 것보다 마지막으로 알려진 트리가 낫다.
   */
  degraded: boolean;
  /** 강제 재조회 (메뉴 관리 화면에서 저장 후 즉시 반영용) */
  refresh: () => Promise<void>;
}

export const MenuContext = createContext<MenuContextType | null>(null);

export const useMenus = (): MenuContextType => {
  const ctx = useContext(MenuContext);
  if (!ctx) throw new Error('useMenus must be used within MenuProvider');
  return ctx;
};
