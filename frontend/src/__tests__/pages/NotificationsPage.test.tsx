import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import NotificationsPage from '@/pages/NotificationsPage';
import { notificationKey, formatOccurredAt } from '@/lib/notificationDisplay';
import type {
  NotificationStreamState,
  PushedNotification,
} from '@/api/notificationStream';

/**
 * 이 화면이 지켜야 하는 규율 넷.
 *
 * <p>① <b>연결 상태를 숨기지 않는다</b> — 조용히 끊긴 스트림과 "알림이 없다"가 화면에서
 * 같아 보이면 사용자는 아무 일도 없다고 믿는다.
 *
 * <p>② <b>빈 상태 문구가 서버 상태를 단정하지 않는다</b> — 서버는 알림을 저장하지 않으므로
 * "알림이 없습니다"는 이 화면이 알 수 없는 사실이다.
 *
 * <p>③ <b>중복 키가 `id` 가 아니다</b> — 서버 시퀀스는 재시작하면 1부터 다시 시작한다.
 * `id` 로 중복을 걸면 재시작 직후의 새 알림이 조용히 버려진다. 이게 이 파일의 핵심 회귀 가드다.
 *
 * <p>④ <b>unmount 시 구독을 닫는다</b> — 안 닫으면 서버 커넥션이 하트비트마다 살아남는다.
 */

vi.mock('@/api/notificationStream', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/notificationStream')>();
  return { ...actual, subscribeNotifications: vi.fn() };
});

const { subscribeNotifications } = await import('@/api/notificationStream');
const mockedSubscribe = vi.mocked(subscribeNotifications);

/** 마지막 구독의 콜백을 붙잡아 테스트에서 직접 이벤트를 밀어 넣는다. */
let pushNotification: (n: PushedNotification) => void;
let pushState: (s: NotificationStreamState) => void;
/**
 * `vi.fn()` 를 그냥 두면 `Mock<Procedure | Constructable>` 로 추론돼 `NotificationStreamHandle.close`
 * (`() => void`)에 대입되지 않는다 — vitest 는 타입을 보지 않아 초록이고, CI 의 `typecheck:tests`
 * 에서만 드러난다(실제로 그렇게 새어 나갔다). 시그니처를 명시해 핸들 계약에 맞춘다.
 */
let close: ReturnType<typeof vi.fn<() => void>>;

const sample = (over: Partial<PushedNotification> = {}): PushedNotification => ({
  id: 1,
  type: 'SETTLEMENT_CONFIRMED',
  recipient: '42',
  subject: '정산 확정: STL-1',
  body: 'lemuel.settlement.confirmed 이벤트가 처리되었습니다.',
  eventId: 'evt-1',
  occurredAt: '2026-08-25T01:00:00Z',
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  close = vi.fn<() => void>();
  mockedSubscribe.mockImplementation((onNotification, onStateChange) => {
    pushNotification = onNotification;
    pushState = onStateChange ?? (() => undefined);
    return { close };
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('NotificationsPage — 연결 상태', () => {
  it('연결 상태를 항상 그린다 — 끊긴 스트림이 "알림 없음"으로 위장되면 안 된다', () => {
    render(<NotificationsPage />);

    expect(screen.getByTestId('stream-state')).toHaveTextContent('연결 중');

    act(() => pushState('open'));
    expect(screen.getByTestId('stream-state')).toHaveTextContent('실시간 수신 중');

    act(() => pushState('error'));
    expect(screen.getByTestId('stream-state')).toHaveTextContent('재연결 중');
  });

  it('오류 상태에서 원인을 지어내지 않는다 — EventSource 는 401·503 을 구분해 주지 않는다', () => {
    render(<NotificationsPage />);

    act(() => pushState('error'));

    const badge = screen.getByTestId('stream-state');
    expect(badge).toHaveTextContent('재연결 중');
    expect(badge.textContent).not.toMatch(/로그인|401|503|권한/);
  });
});

describe('NotificationsPage — 빈 상태', () => {
  it('빈 문구가 서버 상태를 단정하지 않는다', () => {
    render(<NotificationsPage />);

    const empty = screen.getByTestId('notifications-empty');
    expect(empty).toHaveTextContent('연결된 뒤로 도착한 알림이 없습니다.');
    // "알림이 없습니다" 로 끝나면 서버에 없다는 뜻으로 읽힌다 — 이 화면이 알 수 없는 사실이다.
    expect(empty.textContent).not.toBe('알림이 없습니다.');
  });
});

describe('NotificationsPage — 수신·정렬·중복', () => {
  it('도착한 알림을 최신이 위로 오도록 쌓는다', () => {
    render(<NotificationsPage />);

    act(() => pushNotification(sample({ id: 1, eventId: 'evt-1', subject: '먼저' })));
    act(() => pushNotification(sample({ id: 2, eventId: 'evt-2', subject: '나중' })));

    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('나중');
    expect(items[1]).toHaveTextContent('먼저');
  });

  it('같은 eventId 재전송은 화면에 중복으로 쌓이지 않는다 (재연결 재생)', () => {
    render(<NotificationsPage />);

    act(() => pushNotification(sample({ id: 5, eventId: 'evt-dup' })));
    act(() => pushNotification(sample({ id: 5, eventId: 'evt-dup' })));

    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('★ 서버 재시작으로 id 가 1 부터 다시 시작해도 새 알림을 버리지 않는다', () => {
    render(<NotificationsPage />);

    act(() => pushNotification(sample({ id: 1, eventId: 'evt-before-restart', subject: '재시작 전' })));
    // 서버 재시작 — 시퀀스가 1 로 되돌아가지만 도메인 이벤트는 다른 것이다.
    act(() => pushNotification(sample({ id: 1, eventId: 'evt-after-restart', subject: '재시작 후' })));

    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('재시작 후');
  });

  it('eventId 가 없는 임시 발송은 시퀀스+시각으로 구분된다', () => {
    render(<NotificationsPage />);

    act(() => pushNotification(sample({ id: 1, eventId: null, occurredAt: '2026-08-25T01:00:00Z' })));
    act(() => pushNotification(sample({ id: 1, eventId: null, occurredAt: '2026-08-25T02:00:00Z' })));
    // 같은 시퀀스 + 같은 시각이면 같은 것으로 본다.
    act(() => pushNotification(sample({ id: 1, eventId: null, occurredAt: '2026-08-25T02:00:00Z' })));

    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('유형 배지와 본문·시각을 함께 보여 준다', () => {
    render(<NotificationsPage />);

    act(() => pushNotification(sample({ type: 'INVESTMENT_EXECUTED', subject: '투자 체결: ORD-9' })));

    expect(screen.getByText('투자 체결')).toBeInTheDocument();
    expect(screen.getByText('투자 체결: ORD-9')).toBeInTheDocument();
    expect(screen.getByRole('listitem').querySelector('time')).toHaveAttribute(
      'dateTime',
      '2026-08-25T01:00:00Z',
    );
  });
});

describe('NotificationsPage — 구독 수명', () => {
  it('unmount 하면 구독을 닫는다', () => {
    const { unmount } = render(<NotificationsPage />);
    expect(mockedSubscribe).toHaveBeenCalledTimes(1);

    unmount();

    expect(close).toHaveBeenCalledTimes(1);
  });
});

describe('순수 헬퍼', () => {
  it('notificationKey 는 eventId 를 우선한다', () => {
    expect(notificationKey(sample({ eventId: 'evt-9', id: 3 }))).toBe('evt-9');
    expect(notificationKey(sample({ eventId: null, id: 3, occurredAt: 'T' }))).toBe('seq:3@T');
  });

  it('formatOccurredAt 은 파싱 불가 값을 지어내지 않고 원문을 돌려준다', () => {
    expect(formatOccurredAt('아무것도 아님')).toBe('아무것도 아님');
    expect(formatOccurredAt('2026-08-25T01:00:00Z')).not.toBe('2026-08-25T01:00:00Z');
  });
});
