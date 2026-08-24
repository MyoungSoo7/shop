import React, { useCallback, useEffect, useState } from 'react';
import { AuthContext } from '@/contexts/useAuth';
import { authApi, AUTH_CHANGED_EVENT } from '@/api/auth';
import { userApi, MeResponse } from '@/api/user';

/**
 * 로그인 주체를 한 번 읽어 앱 전체에 공급한다.
 *
 * <p>토큰이 없으면 아예 요청하지 않는다 — 비로그인 상태에서 /users/me 를 때리면
 * axios 인터셉터의 401 처리가 "세션이 만료되었습니다" 토스트 + 로그인 리다이렉트를
 * 발동시켜, 로그인 화면에서 스스로를 쫓아내는 루프가 된다.
 *
 * <p>조회 실패는 삼킨다. 사용자 식별에 실패한 것은 화면 전체를 막을 사유가 아니고,
 * userId 를 요구하는 개별 기능이 각자 비활성화되면 된다(null 로 노출).
 */
export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(() => authApi.isAuthenticated());

  const load = useCallback(async () => {
    if (!authApi.isAuthenticated()) {
      setUser(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      setUser(await userApi.me());
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 세션이 바뀌면 다시 읽는다.
   *
   * <p>이것이 없으면 SPA 로그인 후에도 `user` 가 null 로 남는다 — 마운트는 로그아웃
   * 상태에서 한 번뿐이고 로그인은 리마운트가 아니기 때문이다. 그러면 `userId` 에 매달린
   * 기능(장바구니·내 정보 등)이 새로고침 전까지 조용히 비활성이 된다. MenuProvider 가
   * 같은 이유로 네비게이션을 비운 채 뜬 것과 한 뿌리다(2026-08-23).
   */
  useEffect(() => {
    const sync = () => { void load(); };
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, [load]);

  return (
    <AuthContext.Provider
      value={{ user, userId: user?.id ?? null, loading, refresh: load }}
    >
      {children}
    </AuthContext.Provider>
  );
};
