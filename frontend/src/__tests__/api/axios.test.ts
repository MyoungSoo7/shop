import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import api, { setGlobalToast } from '@/api/axios';

/**
 * 인터셉터를 직접 꺼내 호출하기 위한 최소 타입.
 *
 * <p>`handlers` 는 axios 의 내부 필드라 공개 타입(InterceptorManager)에 없다. `as any` 로 지우면
 * 호출 인자·반환 형태까지 검증이 사라지므로, 이 테스트가 실제로 쓰는 표면만 좁혀서 선언한다.
 */
interface InterceptorHandlers<TValue> {
  handlers: Array<{
    fulfilled: (value: TValue) => TValue;
    rejected: (error: unknown) => Promise<never>;
  }>;
}

type RequestConfigLike = { headers: Record<string, string> };
type ResponseLike = { data: unknown };

const requestHandler = () =>
  (api.interceptors.request as unknown as InterceptorHandlers<RequestConfigLike>).handlers[0];
const responseHandler = () =>
  (api.interceptors.response as unknown as InterceptorHandlers<ResponseLike>).handlers[0];

const requestFulfilled = () => requestHandler().fulfilled;
const requestRejected = () => requestHandler().rejected;
const responseFulfilled = () => responseHandler().fulfilled;
const responseRejected = () => responseHandler().rejected;

describe('axios interceptors', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    window.history.pushState({}, '', '/order');
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('요청에 access token이 있으면 Authorization 헤더를 추가한다', () => {
    localStorage.setItem('access_token', 'jwt');

    const config = requestFulfilled()({ headers: {} });

    expect(config.headers.Authorization).toBe('Bearer jwt');
  });

  it('요청 에러는 그대로 reject한다', async () => {
    const error = new Error('request');

    await expect(requestRejected()(error)).rejects.toBe(error);
  });

  it('정상 응답은 그대로 반환한다', () => {
    const response = { data: { ok: true } };

    expect(responseFulfilled()(response)).toBe(response);
  });

  it('401 응답은 세션을 제거하고 로그인으로 이동시킨다', async () => {
    const showToast = vi.fn();
    setGlobalToast(showToast);
    localStorage.setItem('access_token', 'jwt');
    localStorage.setItem('user_email', 'user@test.com');
    localStorage.setItem('user_role', 'USER');

    await expect(responseRejected()({ response: { status: 401 } })).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(showToast).toHaveBeenCalledWith('세션이 만료되었습니다. 다시 로그인해주세요.', 'warning');
    expect(localStorage.getItem('access_token')).toBeNull();

    vi.advanceTimersByTime(1000);
  });

  it('403, 500, network error 토스트를 처리한다', async () => {
    const showToast = vi.fn();
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    setGlobalToast(showToast);

    await expect(responseRejected()({
      response: { status: 403, data: 'denied' },
      config: { url: '/password-reset/request' },
    })).rejects.toMatchObject({ response: { status: 403 } });
    await expect(responseRejected()({ response: { status: 500 } })).rejects.toMatchObject({
      response: { status: 500 },
    });
    await expect(responseRejected()({ message: 'Network Error' })).rejects.toMatchObject({
      message: 'Network Error',
    });

    expect(showToast).toHaveBeenCalledWith('접근 권한이 없습니다.', 'error');
    expect(showToast).toHaveBeenCalledWith('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.', 'error');
    expect(showToast).toHaveBeenCalledWith('네트워크 오류가 발생했습니다. 인터넷 연결을 확인해주세요.', 'error');
    expect(consoleSpy).toHaveBeenCalled();
  });
});
