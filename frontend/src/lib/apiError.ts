/**
 * catch 절의 `unknown` 을 화면 문구·상태코드로 좁히는 단일 지점.
 *
 * <p>`catch (err: any)` 로 타입을 지우면 `err.response.data.message` 오타가 런타임까지 살아남는다.
 * 좁히기를 여기 한 곳에 모아 두면 호출부는 `unknown` 그대로 두고도 안전하게 쓸 수 있다.
 *
 * <p>판별은 <b>구조로</b> 한다(`axios.isAxiosError` 인스턴스 판별이 아니라). 오류는 axios 말고도
 * 온다 — SSE 스트리밍 래퍼, 테스트 목 등 같은 모양의 객체를 그대로 받아들여야 기존 동작이 보존된다.
 *
 * <p>문구 규칙은 기존 관용구(`err.response?.data?.message || '기본 문구'`)를 그대로 따른다 —
 * 서버가 준 message 가 없으면 예외 자체의 message 가 아니라 <b>화면용 기본 문구</b>로 떨어진다.
 * 네트워크 오류에 "Network Error" 같은 원문을 노출하지 않기 위한 의도된 동작이다.
 */

/** 오류에서 HTTP 응답 부분만 꺼낸다 — 객체가 아니거나 응답 전 실패면 undefined. */
function responseOf(err: unknown): { status?: unknown; data?: unknown } | undefined {
  if (typeof err !== 'object' || err === null) {
    return undefined;
  }
  const response = (err as { response?: unknown }).response;
  return typeof response === 'object' && response !== null
    ? (response as { status?: unknown; data?: unknown })
    : undefined;
}

/**
 * 서버 응답 body 에서 사용자에게 보일 문구를 꺼낸다.
 *
 * <p>JSON body 는 `{ message }`, 평문 body 는 문자열 자체를 쓴다(일부 엔드포인트가 text/plain 로 준다).
 * 문자열이 아니거나 비어 있으면 없는 것으로 본다.
 */
function serverMessage(err: unknown): string | undefined {
  const data = responseOf(err)?.data;
  if (typeof data === 'string') {
    return data.length > 0 ? data : undefined;
  }
  if (typeof data !== 'object' || data === null) {
    return undefined;
  }
  const message = (data as { message?: unknown }).message;
  return typeof message === 'string' && message.length > 0 ? message : undefined;
}

/** 서버가 준 message, 없으면 화면용 기본 문구. */
export function apiErrorMessage(err: unknown, fallback: string): string {
  return serverMessage(err) ?? fallback;
}

/**
 * 서버 message → 예외 자체의 message → 기본 문구.
 *
 * <p>원인 문구까지 보여 주는 게 맞는 자리에서만 쓴다(결제창 SDK 오류 등 서버 응답이 아예 없는 경로).
 */
export function errorDetail(err: unknown, fallback: string): string {
  const fromServer = serverMessage(err);
  if (fromServer) {
    return fromServer;
  }
  if (err instanceof Error && err.message.length > 0) {
    return err.message;
  }
  return fallback;
}

/** HTTP 상태코드 — 응답이 없거나 숫자가 아니면 undefined. */
export function apiErrorStatus(err: unknown): number | undefined {
  const status = responseOf(err)?.status;
  return typeof status === 'number' ? status : undefined;
}

/** 서버 응답 body 원문 — 디버그 로깅 전용(화면 노출은 {@link apiErrorMessage} 를 쓴다). */
export function apiErrorData(err: unknown): unknown {
  return responseOf(err)?.data;
}
