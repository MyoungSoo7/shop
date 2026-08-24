import { createContext, useContext } from 'react';
import { MeResponse } from '@/api/user';

/**
 * 인증 주체 컨텍스트와 소비 훅 — 컴포넌트(AuthProvider)와 <b>파일을 분리</b>한다
 * (react-refresh/only-export-components. useCart·useToast 와 같은 규칙).
 *
 * <p>이 컨텍스트가 생긴 이유: 로그인 응답에 userId 가 없어 화면들이
 * {@code const USER_ID = 1} 을 하드코딩하고 있었다. 서버가 소유권을 대조하는 API
 * (장바구니·주문·리뷰)에 남의 id 를 보내면 403 이고, 1번 사용자로 로그인했을 때만
 * 우연히 동작한다. userId 의 단일 출처를 {@code GET /users/me} 로 못박는다.
 */
export interface AuthContextType {
  /** 로그인 주체. 미인증이거나 조회 실패면 null. */
  user: MeResponse | null;
  /** 편의 접근자 — user?.id. 서버 소유권 대조가 걸린 호출은 이 값이 없으면 시도하지 않는다. */
  userId: number | null;
  /** /users/me 조회 진행 중 여부. true 동안은 "비로그인"으로 단정하면 안 된다. */
  loading: boolean;
  /** 로그인 직후처럼 주체가 바뀌었을 때 다시 읽는다. */
  refresh: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextType | null>(null);

export const useAuth = (): AuthContextType => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
};
