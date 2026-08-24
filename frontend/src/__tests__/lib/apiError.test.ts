import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { apiErrorData, apiErrorMessage, apiErrorStatus, errorDetail } from '@/lib/apiError';

/**
 * apiError — catch 절의 unknown 을 화면 문구로 좁히는 단일 지점.
 *
 * 기존 관용구(`err.response?.data?.message || '기본 문구'`)의 동작을 그대로 보존한다:
 * 서버가 준 message 가 없으면 예외 자체의 message 가 아니라 화면용 기본 문구로 떨어진다
 * (네트워크 오류에 "Network Error" 를 노출하지 않기 위함).
 */
describe('apiError', () => {
  function axiosError(status: number, data: unknown): AxiosError {
    const err = new AxiosError('Request failed', 'ERR_BAD_REQUEST');
    err.response = {
      status,
      statusText: '',
      data,
      headers: new AxiosHeaders(),
      config: { headers: new AxiosHeaders() },
    };
    return err;
  }

  describe('apiErrorMessage', () => {
    it('서버가 준 message 를 그대로 쓴다', () => {
      expect(apiErrorMessage(axiosError(422, { message: '재원이 부족합니다.' }), '기본')).toBe(
        '재원이 부족합니다.',
      );
    });

    it('서버 message 가 없으면 기본 문구 — 예외 message 를 노출하지 않는다', () => {
      expect(apiErrorMessage(axiosError(500, {}), '조회에 실패했습니다.')).toBe('조회에 실패했습니다.');
      expect(apiErrorMessage(new Error('Network Error'), '조회에 실패했습니다.')).toBe(
        '조회에 실패했습니다.',
      );
    });

    it('빈 문자열 message 는 없는 것으로 본다', () => {
      expect(apiErrorMessage(axiosError(500, { message: '' }), '기본')).toBe('기본');
    });

    it('message 가 문자열이 아니면 기본 문구', () => {
      expect(apiErrorMessage(axiosError(500, { message: { nested: true } }), '기본')).toBe('기본');
    });

    it('axios 가 아닌 값(문자열·null·undefined)도 안전하게 처리한다', () => {
      expect(apiErrorMessage('boom', '기본')).toBe('기본');
      expect(apiErrorMessage(null, '기본')).toBe('기본');
      expect(apiErrorMessage(undefined, '기본')).toBe('기본');
    });

    it('응답 없이 요청만 실패한 경우(response undefined)도 기본 문구', () => {
      expect(apiErrorMessage(new AxiosError('timeout'), '기본')).toBe('기본');
    });

    it('body 가 JSON 이 아니라 문자열이면 그 문자열을 쓴다', () => {
      expect(apiErrorMessage(axiosError(400, '이미 사용 중인 이메일입니다.'), '기본')).toBe(
        '이미 사용 중인 이메일입니다.',
      );
    });

    it('빈 문자열 body 는 없는 것으로 본다', () => {
      expect(apiErrorMessage(axiosError(400, ''), '기본')).toBe('기본');
    });
  });

  describe('구조적 판별 — AxiosError 인스턴스가 아니어도 response 모양이면 인정한다', () => {
    // 기존 코드는 `err.response?.data?.message` 로 구조만 봤다. 인스턴스 판별(axios.isAxiosError)로
    // 바꾸면 테스트 목이나 다른 클라이언트가 던진 동일 모양 오류가 조용히 기본 문구로 떨어진다.
    const plain = { response: { status: 409, data: { message: '이미 사용 중인 이메일입니다.' } } };

    it('평범한 객체에서도 message 를 꺼낸다', () => {
      expect(apiErrorMessage(plain, '기본')).toBe('이미 사용 중인 이메일입니다.');
    });

    it('평범한 객체에서도 status 를 꺼낸다', () => {
      expect(apiErrorStatus(plain)).toBe(409);
    });

    it('status 가 숫자가 아니면 undefined', () => {
      expect(apiErrorStatus({ response: { status: '409' } })).toBeUndefined();
    });

    it('response 가 객체가 아니면 무시한다', () => {
      expect(apiErrorStatus({ response: 'nope' })).toBeUndefined();
      expect(apiErrorMessage({ response: 'nope' }, '기본')).toBe('기본');
    });
  });

  describe('apiErrorData', () => {
    it('서버 응답 body 를 그대로 돌려준다 — 디버그 로깅용', () => {
      expect(apiErrorData(axiosError(500, { trace: 'x' }))).toEqual({ trace: 'x' });
    });

    it('axios 오류가 아니면 undefined', () => {
      expect(apiErrorData(new Error('boom'))).toBeUndefined();
    });
  });

  describe('errorDetail', () => {
    it('서버 message 를 최우선으로 쓴다', () => {
      expect(errorDetail(axiosError(400, { message: '서버 사유' }), '기본')).toBe('서버 사유');
    });

    it('서버 message 가 없으면 예외 자체의 message 로 떨어진다', () => {
      expect(errorDetail(new Error('결제창을 열 수 없습니다'), '기본')).toBe('결제창을 열 수 없습니다');
    });

    it('둘 다 없으면 기본 문구', () => {
      expect(errorDetail({}, '알 수 없는 오류')).toBe('알 수 없는 오류');
      expect(errorDetail(null, '알 수 없는 오류')).toBe('알 수 없는 오류');
    });
  });

  describe('apiErrorStatus', () => {
    it('HTTP 상태코드를 돌려준다', () => {
      expect(apiErrorStatus(axiosError(409, {}))).toBe(409);
    });

    it('axios 오류가 아니거나 응답이 없으면 undefined', () => {
      expect(apiErrorStatus(new Error('boom'))).toBeUndefined();
      expect(apiErrorStatus(null)).toBeUndefined();
    });
  });
});
