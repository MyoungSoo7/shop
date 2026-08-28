import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { getAuthStorage } from '@/lib/authStorage';

/**
 * 실패를 호출부가 직접 삼키는 요청에 붙이는 표시.
 *
 * <p>화면이 "실패해도 그만" 으로 처리한 호출까지 인터셉터가 "접근 권한이 없습니다" 를 띄우면,
 * 정상 동작하는 화면 위에 경고만 뜨는 상태가 된다. 그런 호출은 이 플래그로 전역 토스트를 끈다.
 * 콘솔 로그는 그대로 남기므로 개발자는 여전히 403 을 본다.
 */
declare module 'axios' {
  // 타입 파라미터는 axios 원본 선언(<D = any, P = any>)과 이름·기본값까지 같아야 병합된다.
  // 여기서는 안 쓰지만 지우거나 `_D` 로 바꾸면 TS2428 로 병합이 깨진다.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/no-unused-vars
  export interface AxiosRequestConfig<D = any, P = any> {
    skipAuthToast?: boolean;
  }
}

// Toast 관리를 위한 전역 참조
let globalShowToast: ((message: string, type: 'success' | 'error' | 'warning' | 'info') => void) | null = null;

export const setGlobalToast = (showToast: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void) => {
  globalShowToast = showToast;
};

// Axios 인스턴스 생성
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json;charset=UTF-8',
  },
});

// Request Interceptor: JWT 토큰 자동 추가
api.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getAuthStorage().get('access_token');

    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    return config;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  }
);

// Response Interceptor: 에러 처리 및 자동 로그아웃
api.interceptors.response.use(
  (response) => {
    return response;
  },
  (error: AxiosError) => {
    // 401 Unauthorized: 토큰 만료 또는 인증 실패
    if (error.response?.status === 401) {
      if (globalShowToast) {
        globalShowToast('세션이 만료되었습니다. 다시 로그인해주세요.', 'warning');
      }

      void getAuthStorage().clear();

      // 로그인 페이지로 리다이렉트
      if (window.location.pathname !== '/login' && window.location.pathname !== '/register') {
        setTimeout(() => {
          window.location.href = '/login';
        }, 1000); // Toast 메시지를 보여주기 위한 딜레이
      }
    }

    // 403 Forbidden: 권한 없음
    if (error.response?.status === 403) {
      // 비밀번호 재설정 API는 인증 없이 호출 가능해야 함
      const isPasswordResetEndpoint = error.config?.url?.includes('/password-reset');

      if (isPasswordResetEndpoint) {
        console.error('[개발자] 비밀번호 재설정 API가 Spring Security에서 차단됨. SecurityConfig 확인 필요:', error.response.data);
      }

      if (globalShowToast && !error.config?.skipAuthToast) {
        globalShowToast('접근 권한이 없습니다.', 'error');
      }
      console.error('Access denied:', error.response.data);
    }

    // 500 Internal Server Error
    if (error.response?.status === 500) {
      if (globalShowToast) {
        globalShowToast('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.', 'error');
      }
    }

    // Network Error
    if (error.message === 'Network Error') {
      if (globalShowToast) {
        globalShowToast('네트워크 오류가 발생했습니다. 인터넷 연결을 확인해주세요.', 'error');
      }
    }

    return Promise.reject(error);
  }
);

export default api;
