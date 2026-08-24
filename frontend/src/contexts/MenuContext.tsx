import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { navMenuApi, type NavMenuNode } from '@/api/menu';
import { authApi, AUTH_CHANGED_EVENT } from '@/api/auth';
import { resolveFallbackMenus } from '@/data/menuFallback';
import { MenuContext } from './useMenus';

/**
 * 네비게이션 메뉴 공급자.
 *
 * <p>역할이 바뀔 때(로그인/로그아웃/계정 전환)만 다시 조회한다. 메뉴는 화면마다 바뀌는 값이
 * 아니라 세션 단위 값이므로, 라우트 이동마다 호출하면 순수한 낭비다.
 *
 * <p><b>역할은 상태로 들고 세션 이벤트로 갱신한다.</b> 렌더 시점에 localStorage 를 읽으면
 * 로그인해도 값이 바뀌지 않는다 — SPA 로그인은 리마운트가 아니고, 이 공급자를 다시 렌더시킬
 * 상태 변화도 없기 때문이다. 2026-08-23 에 그래서 관리자 로그인 직후 네비게이션이 통째로
 * 비어 있었다(서버는 정상: `menus` 54행, ADMIN 토큰 조회 8,218바이트). 앱이 로그아웃 상태로
 * 마운트되며 익명 조회의 `[]` 를 정상값으로 굳혔고, 새로고침만이 복구 경로였다.
 */
export const MenuProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [role, setRole] = useState<string | null>(() => authApi.getCurrentUser()?.role ?? null);

  // 세션 변경(로그인·교체·로그아웃) 구독. `storage` 는 다른 탭에서 로그아웃한 경우다 —
  // 같은 탭에서는 발생하지 않으므로 자체 이벤트와 함께 걸어야 양쪽이 덮인다.
  useEffect(() => {
    const sync = () => setRole(authApi.getCurrentUser()?.role ?? null);
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  const [menus, setMenus] = useState<NavMenuNode[]>(() => resolveFallbackMenus(role));
  const [loading, setLoading] = useState(true);
  const [degraded, setDegraded] = useState(false);

  const load = useCallback(async (): Promise<void> => {
    setLoading(true);
    try {
      const data = await navMenuApi.getMine();
      // 로그인했는데 트리가 비어 있으면 서버 데이터가 아직 없는 상태(미마이그레이션 등)다.
      // 그대로 그리면 로그인은 됐는데 아무 데도 못 가는 화면이 되므로 스냅샷으로 버틴다.
      if (role !== null && data.length === 0) {
        setMenus(resolveFallbackMenus(role));
        setDegraded(true);
      } else {
        setMenus(data);
        setDegraded(false);
      }
    } catch {
      setMenus(resolveFallbackMenus(role));
      setDegraded(true);
    } finally {
      setLoading(false);
    }
  }, [role]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      await load();
      if (cancelled) return;
    })();
    return () => { cancelled = true; };
  }, [load]);

  const value = useMemo(
    () => ({ menus, loading, degraded, refresh: load }),
    [menus, loading, degraded, load],
  );

  return <MenuContext.Provider value={value}>{children}</MenuContext.Provider>;
};
