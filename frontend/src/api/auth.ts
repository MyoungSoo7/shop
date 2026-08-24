import api from './axios';
import { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from '@/types';
import { getAuthStorage } from '@/lib/authStorage';

/**
 * 세션이 바뀌었다는 신호 (로그인·세션교체·로그아웃).
 *
 * <p>왜 필요한가 — 토큰은 localStorage 에 있고 그것은 **리액티브가 아니다**. 로그인은
 * SPA 안에서 일어나 리마운트가 없으므로, 토큰을 렌더 시점에 읽는 컨텍스트는 로그인 후에도
 * 로그아웃 상태의 값을 그대로 들고 있는다. 2026-08-23 에 그 결과로 관리자 로그인 직후
 * 네비게이션이 통째로 비어 있었고(서버는 정상), 새로고침이 유일한 복구 경로였다.
 *
 * <p>구독 측은 이 이벤트를 받아 자기 상태를 다시 읽으면 된다.
 */
export const AUTH_CHANGED_EVENT = 'lemuel:auth-changed';

/** 세션 변경 통지. 저장소 쓰기가 끝난 뒤에 부른다 — 먼저 부르면 구독자가 옛 값을 읽는다. */
const notifyAuthChanged = (): void => {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
  }
};

export const authApi = {
  /**
   * 로그인
   * POST /auth/login
   */
  login: async (credentials: LoginRequest): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/auth/login', credentials);
    return response.data;
  },

  /**
   * 회원가입
   * POST /users
   */
  register: async (userData: RegisterRequest): Promise<UserResponse> => {
    const response = await api.post<UserResponse>('/users', userData);
    return response.data;
  },

  /**
   * 데모 자동로그인 (USER / MANAGER / ADMIN)
   * POST /auth/dev/auto-login?role=USER
   * 백엔드의 lemuel.demo.enabled=true 일 때만 200, 아니면 404.
   */
  autoLogin: async (role: 'USER' | 'MANAGER' | 'ADMIN'): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>(
      `/auth/dev/auto-login?role=${role}`
    );
    return response.data;
  },

  /**
   * 게스트 둘러보기 토큰 (DB 사용자 없음, 읽기 전용 화면용)
   * POST /auth/dev/guest
   */
  guestLogin: async (): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/auth/dev/guest');
    return response.data;
  },

  /**
   * 로그아웃 (클라이언트 측)
   */
  logout: (): void => {
    void getAuthStorage().clear();
    notifyAuthChanged();
  },

  /**
   * 토큰 저장
   */
  saveToken: (loginResponse: LoginResponse): void => {
    // 기존 로그인 세션이 있는지 확인
    const storage = getAuthStorage();
    const existingEmail = storage.get('user_email');

    if (existingEmail && existingEmail !== loginResponse.email) {
      console.warn(`세션 교체: ${existingEmail} -> ${loginResponse.email}`);
      // 기존 세션 정보 제거
      authApi.logout();
    }

    // 새 세션 저장. 통지는 쓰기가 끝난 뒤라야 구독자가 새 역할을 읽는다
    // (`logout()` 이 이미 한 번 통지했더라도 그때는 아직 새 토큰이 없다).
    void Promise.all([
      storage.set('access_token', loginResponse.token),
      storage.set('user_email', loginResponse.email),
      storage.set('user_role', loginResponse.role),
      storage.set('login_timestamp', new Date().toISOString()),
    ]).then(notifyAuthChanged, notifyAuthChanged);
  },

  /**
   * 현재 사용자 정보 가져오기
   */
  getCurrentUser: (): { email: string; role: string } | null => {
    const storage = getAuthStorage();
    const email = storage.get('user_email');
    const role = storage.get('user_role');

    if (email && role) {
      return { email, role };
    }
    return null;
  },

  /**
   * 인증 여부 확인
   */
  isAuthenticated: (): boolean => {
    return !!getAuthStorage().get('access_token');
  },
};
