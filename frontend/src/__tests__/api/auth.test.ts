import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { authApi } from '@/api/auth';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
  },
}));

describe('authApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  // ─── login ────────────────────────────────────────────────
  describe('login', () => {
    it('자격증명을 POST하고 LoginResponse를 반환한다', async () => {
      const mockResponse = { token: 'jwt-token', email: 'user@test.com', role: 'USER' };
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockResponse });

      const result = await authApi.login({ email: 'user@test.com', password: 'pass' });

      expect(api.post).toHaveBeenCalledWith('/auth/login', { email: 'user@test.com', password: 'pass' });
      expect(result).toEqual(mockResponse);
    });

    it('로그인 실패 시 에러를 throw한다', async () => {
      const error = { response: { status: 401, data: { message: '인증 실패' } } };
      vi.mocked(api.post).mockRejectedValueOnce(error);

      await expect(authApi.login({ email: 'bad@test.com', password: 'wrong' }))
        .rejects.toMatchObject({ response: { status: 401 } });
    });
  });

  // ─── register ─────────────────────────────────────────────
  describe('register', () => {
    it('사용자 정보를 POST하고 UserResponse를 반환한다', async () => {
      const mockUser = { id: 1, email: 'new@test.com', role: 'USER' };
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockUser });

      const result = await authApi.register({ email: 'new@test.com', password: 'pass', role: 'USER' });

      expect(api.post).toHaveBeenCalledWith('/users', { email: 'new@test.com', password: 'pass', role: 'USER' });
      expect(result).toEqual(mockUser);
    });

    it('이메일 중복 시 409 에러를 throw한다', async () => {
      const error = { response: { status: 409, data: { message: '이미 사용 중인 이메일' } } };
      vi.mocked(api.post).mockRejectedValueOnce(error);

      await expect(authApi.register({ email: 'dup@test.com', password: 'pass', role: 'USER' }))
        .rejects.toMatchObject({ response: { status: 409 } });
    });
  });

  // ─── demo login ───────────────────────────────────────────
  describe('demo login', () => {
    it('역할 기반 자동로그인 API를 호출한다', async () => {
      const mockResponse = { token: 'manager-token', email: 'manager@test.com', role: 'MANAGER' };
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockResponse });

      const result = await authApi.autoLogin('MANAGER');

      expect(api.post).toHaveBeenCalledWith('/auth/dev/auto-login?role=MANAGER');
      expect(result).toEqual(mockResponse);
    });

    it('게스트 로그인 API를 호출한다', async () => {
      const mockResponse = { token: 'guest-token', email: 'guest@test.com', role: 'USER' };
      vi.mocked(api.post).mockResolvedValueOnce({ data: mockResponse });

      const result = await authApi.guestLogin();

      expect(api.post).toHaveBeenCalledWith('/auth/dev/guest');
      expect(result).toEqual(mockResponse);
    });
  });

  // ─── saveToken ────────────────────────────────────────────
  describe('saveToken', () => {
    const loginResp = { token: 'my-jwt', email: 'user@test.com', role: 'USER' };

    it('token, email, role, login_timestamp를 localStorage에 저장한다', () => {
      authApi.saveToken(loginResp);

      expect(localStorage.getItem('access_token')).toBe('my-jwt');
      expect(localStorage.getItem('user_email')).toBe('user@test.com');
      expect(localStorage.getItem('user_role')).toBe('USER');
      expect(localStorage.getItem('login_timestamp')).not.toBeNull();
    });

    it('다른 이메일의 기존 세션이 있으면 교체한다', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      localStorage.setItem('access_token', 'old-token');
      localStorage.setItem('user_email', 'old@test.com');
      localStorage.setItem('user_role', 'USER');

      authApi.saveToken(loginResp);

      expect(localStorage.getItem('user_email')).toBe('user@test.com');
      expect(localStorage.getItem('access_token')).toBe('my-jwt');
      expect(warnSpy).toHaveBeenCalledWith('세션 교체: old@test.com -> user@test.com');
    });

    it('같은 이메일의 기존 세션은 덮어쓴다', () => {
      localStorage.setItem('access_token', 'old-token');
      localStorage.setItem('user_email', 'user@test.com');
      localStorage.setItem('user_role', 'USER');

      authApi.saveToken({ ...loginResp, token: 'new-token' });

      expect(localStorage.getItem('access_token')).toBe('new-token');
    });
  });

  // ─── logout ───────────────────────────────────────────────
  describe('logout', () => {
    it('localStorage에서 인증 정보를 모두 제거한다', () => {
      localStorage.setItem('access_token', 'token');
      localStorage.setItem('user_email', 'user@test.com');
      localStorage.setItem('user_role', 'USER');
      localStorage.setItem('login_timestamp', new Date().toISOString());

      authApi.logout();

      expect(localStorage.getItem('access_token')).toBeNull();
      expect(localStorage.getItem('user_email')).toBeNull();
      expect(localStorage.getItem('user_role')).toBeNull();
      expect(localStorage.getItem('login_timestamp')).toBeNull();
    });
  });

  // ─── getCurrentUser ───────────────────────────────────────
  describe('getCurrentUser', () => {
    it('로그인된 경우 email과 role을 반환한다', () => {
      localStorage.setItem('user_email', 'admin@test.com');
      localStorage.setItem('user_role', 'ADMIN');

      const user = authApi.getCurrentUser();

      expect(user).toEqual({ email: 'admin@test.com', role: 'ADMIN' });
    });

    it('비로그인 상태에서 null을 반환한다', () => {
      expect(authApi.getCurrentUser()).toBeNull();
    });

    it('email만 있고 role이 없으면 null을 반환한다', () => {
      localStorage.setItem('user_email', 'user@test.com');

      expect(authApi.getCurrentUser()).toBeNull();
    });
  });

  // ─── isAuthenticated ──────────────────────────────────────
  describe('isAuthenticated', () => {
    it('access_token이 있으면 true를 반환한다', () => {
      localStorage.setItem('access_token', 'valid-token');

      expect(authApi.isAuthenticated()).toBe(true);
    });

    it('access_token이 없으면 false를 반환한다', () => {
      expect(authApi.isAuthenticated()).toBe(false);
    });

    it('logout 후 false를 반환한다', () => {
      localStorage.setItem('access_token', 'token');
      authApi.logout();

      expect(authApi.isAuthenticated()).toBe(false);
    });
  });
});
