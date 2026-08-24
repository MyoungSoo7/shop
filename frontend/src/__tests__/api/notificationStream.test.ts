import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  notificationStreamUrl,
  subscribeNotifications,
  type PushedNotification,
} from '@/api/notificationStream';

/** jsdom 에는 EventSource 가 없으므로 리스너를 노출하는 페이크로 대체한다. */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners: Record<string, Array<(e: MessageEvent) => void>> = {};

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    (this.listeners[type] ??= []).push(cb);
  }

  emit(type: string, data: string) {
    for (const cb of this.listeners[type] ?? []) {
      cb({ data } as MessageEvent);
    }
  }

  close() {
    this.closed = true;
  }
}

const sample = {
  id: 7,
  type: 'SETTLEMENT_CONFIRMED',
  recipient: '42',
  subject: '정산 확정: 1001',
  body: 'lemuel.settlement.confirmed 이벤트가 처리되었습니다.',
  eventId: 'evt-1',
  occurredAt: '2026-08-11T00:00:01Z',
};

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource);
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('notificationStream api', () => {
  it('구독 URL 은 게이트웨이 경로 계약을 따르고 토큰을 쿼리로 싣는다', () => {
    expect(notificationStreamUrl('abc.def.ghi')).toBe(
      '/api/notifications/stream?token=abc.def.ghi',
    );
    // 토큰이 없으면 붙이지 않는다(서버가 401 로 끊는다).
    expect(notificationStreamUrl(null)).toBe('/api/notifications/stream');
  });

  it('토큰 특수문자는 URL 인코딩된다', () => {
    expect(notificationStreamUrl('a b&c')).toBe('/api/notifications/stream?token=a%20b%26c');
  });

  it('토큰을 넘기지 않으면 localStorage 의 access_token 을 쓴다', () => {
    localStorage.setItem('access_token', 'stored-token');
    subscribeNotifications(() => undefined);

    expect(FakeEventSource.instances[0].url).toBe(
      '/api/notifications/stream?token=stored-token',
    );
  });

  it('notification 이벤트를 파싱해 콜백에 전달한다', () => {
    const received: PushedNotification[] = [];
    subscribeNotifications((n) => received.push(n), undefined, 't');

    FakeEventSource.instances[0].emit('notification', JSON.stringify(sample));

    expect(received).toHaveLength(1);
    expect(received[0].id).toBe(7);
    expect(received[0].subject).toBe('정산 확정: 1001');
  });

  it('계약 밖(비 JSON) 프레임은 무시하고 스트림을 유지한다', () => {
    const received: PushedNotification[] = [];
    subscribeNotifications((n) => received.push(n), undefined, 't');

    const es = FakeEventSource.instances[0];
    es.emit('notification', 'not-json');
    es.emit('notification', JSON.stringify(sample));

    expect(received).toHaveLength(1);
  });

  it('상태 콜백은 connecting → open 순으로 통지되고 close() 가 연결을 닫는다', () => {
    const states: string[] = [];
    const handle = subscribeNotifications(() => undefined, (s) => states.push(s), 't');

    const es = FakeEventSource.instances[0];
    expect(states).toEqual(['connecting']);
    es.onopen?.();
    es.onerror?.();
    expect(states).toEqual(['connecting', 'open', 'error']);

    handle.close();
    expect(es.closed).toBe(true);
  });
});
