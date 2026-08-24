/**
 * 알림 푸시 SSE 클라이언트 — operation-service 의 notification 슬라이스(8092)가 서빙한다.
 * (폴리글랏 Kotlin 8130 에서 흡수됐다 — ADR 0041. 아래 경로 계약은 그대로다.)
 *
 * 경로 계약: 프론트는 항상 `/api/notifications/stream` 으로 구독한다.
 *  - dev: vite proxy 가 `/api` 를 gateway 로 넘긴다
 *  - docker/k8s: nginx → gateway 가 그대로 전달(서비스 실경로가 같아 RewritePath 가 없다)
 *
 * 인증: `EventSource` 는 요청 헤더를 못 붙이므로 JWT 를 쿼리 파라미터로 보낸다
 * (서버는 Authorization 헤더도 받는다). URL 에 실린 토큰은 접근 로그에 남을 수 있어
 * 프록시 로깅 정책과 함께 봐야 한다 — docs/sse.md 참조.
 *
 * 재연결: EventSource 가 스스로 재접속하며 마지막 `id` 를 `Last-Event-ID` 로 되돌려
 * 보내므로, 끊긴 동안 발생한 알림은 서버 보관 창(기본 100건/수신자) 안에서 재생된다.
 */

/** 서버 푸시 페이로드 (operation notification 슬라이스의 StreamEventDto 와 계약) */
export interface PushedNotification {
  /** 재개 지점(SSE event id) */
  id: number;
  type: 'SETTLEMENT_CONFIRMED' | 'PAYMENT_CONFIRMED' | 'INVESTMENT_EXECUTED' | 'GENERIC';
  recipient: string;
  subject: string;
  body: string;
  eventId: string | null;
  /** ISO-8601 (UTC) */
  occurredAt: string;
}

export type NotificationStreamState = 'connecting' | 'open' | 'error';

export interface NotificationStreamHandle {
  close: () => void;
}

const STREAM_PATH = '/api/notifications/stream';

/** 토큰을 쿼리로 실은 구독 URL. 토큰이 없으면 서버가 401 로 끊는다. */
export const notificationStreamUrl = (token: string | null): string =>
  token ? `${STREAM_PATH}?token=${encodeURIComponent(token)}` : STREAM_PATH;

/**
 * 로그인 사용자의 알림 스트림 구독. 반환된 handle.close() 로 해제한다
 * (컴포넌트 unmount 시 필수 — 안 하면 서버 커넥션이 하트비트마다 살아남는다).
 *
 * @param onNotification 알림 1건 수신 콜백
 * @param onStateChange  연결 상태 변화 콜백(재연결 중에는 error 가 일시적으로 뜬다)
 * @param token          기본값은 localStorage 의 access_token
 */
export const subscribeNotifications = (
  onNotification: (notification: PushedNotification) => void,
  onStateChange?: (state: NotificationStreamState) => void,
  token: string | null = localStorage.getItem('access_token'),
): NotificationStreamHandle => {
  const es = new EventSource(notificationStreamUrl(token));
  onStateChange?.('connecting');
  es.onopen = () => onStateChange?.('open');
  es.onerror = () => onStateChange?.('error');
  es.addEventListener('notification', (e) => {
    try {
      onNotification(JSON.parse((e as MessageEvent).data) as PushedNotification);
    } catch {
      // 계약 밖 프레임은 무시 — 스트림은 유지한다.
    }
  });
  return { close: () => es.close() };
};
