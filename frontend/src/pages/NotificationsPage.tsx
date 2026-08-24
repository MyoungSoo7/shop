import { useEffect, useRef, useState } from 'react';
import {
  subscribeNotifications,
  type NotificationStreamState,
  type PushedNotification,
} from '@/api/notificationStream';
import { formatOccurredAt, notificationKey } from '@/lib/notificationDisplay';

/**
 * 내 알림 — 실시간 푸시 스트림(`/api/notifications/stream`)을 구독해 도착하는 대로 쌓는다.
 *
 * <p><b>이 화면은 수신함이 아니라 스트림이다.</b> 서버(operation-service 의 notification 슬라이스)는
 * 알림을 저장하지 않는다 — 보관은 프로세스 메모리의 재생 창(수신자당 100건)뿐이고 재시작하면 사라진다
 * (ADR 0041 · docs/sse.md). 그래서 "지난 알림 전부"를 보여 준다고 약속하지 않는다. 빈 화면 문구도
 * "알림이 없습니다"가 아니라 <b>"연결된 뒤로 도착한 알림이 없습니다"</b> 다 — 전자는 서버에 없다는
 * 뜻으로 읽히는데 그건 이 화면이 알 수 없는 사실이다.
 *
 * <p><b>연결 상태를 숨기지 않는다.</b> 가치가 전부 실시간성에 있으므로, 조용히 끊긴 스트림은
 * "알림이 안 온다"와 화면상 구별되지 않는다. 그 둘이 같아 보이면 사용자는 아무 일도 없다고 믿는다.
 * 그래서 연결 배지를 항상 그린다.
 *
 * <p><b>실패 사유를 지어내지 않는다.</b> `EventSource` 는 401·503·네트워크 단절을 구분해 주지 않고
 * 전부 `onerror` 로만 알린다. 화면이 "로그인이 만료됐습니다" 같은 추측을 쓰면 틀린 안내가 된다 —
 * 재연결 중이라는 사실만 말하고, 원인은 서버 로그의 몫으로 남긴다.
 *
 * <p><b>중복 제거 키는 `id` 가 아니다</b> — 서버 재시작 시 시퀀스가 1부터 다시 시작하므로
 * `id` 로 걸면 새 알림이 조용히 버려진다. 근거와 규칙은 `@/lib/notificationDisplay` 에 있다.
 */

/** 화면이 들고 있는 최대 건수 — 서버 재생 창(100건)과 같은 성질의 상한. 무한히 자라지 않게 한다. */
const MAX_RETAINED = 200;

const TYPE_LABEL: Record<PushedNotification['type'], string> = {
  SETTLEMENT_CONFIRMED: '정산 확정',
  PAYMENT_CONFIRMED: '결제',
  INVESTMENT_EXECUTED: '투자 체결',
  GENERIC: '알림',
};

const STATE_LABEL: Record<NotificationStreamState, string> = {
  connecting: '연결 중',
  open: '실시간 수신 중',
  error: '재연결 중',
};

/** 연결 배지 색 — 초록(수신 중)·노랑(연결 중)·주황(재연결). 상태를 색으로도 구분해 둔다. */
const STATE_STYLE: Record<NotificationStreamState, string> = {
  connecting: 'bg-amber-50 text-amber-700 ring-amber-200',
  open: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  error: 'bg-orange-50 text-orange-700 ring-orange-200',
};

/** 유형 배지 색 — 정산·결제·투자를 한눈에 가른다. */
const TYPE_STYLE: Record<PushedNotification['type'], string> = {
  SETTLEMENT_CONFIRMED: 'bg-blue-50 text-blue-700 ring-blue-200',
  PAYMENT_CONFIRMED: 'bg-violet-50 text-violet-700 ring-violet-200',
  INVESTMENT_EXECUTED: 'bg-teal-50 text-teal-700 ring-teal-200',
  GENERIC: 'bg-gray-50 text-gray-600 ring-gray-200',
};

export default function NotificationsPage() {
  const [notifications, setNotifications] = useState<PushedNotification[]>([]);
  const [state, setState] = useState<NotificationStreamState>('connecting');
  /** 이미 그린 알림의 키 — 재연결 재생이 화면에 중복을 만들지 않게 한다. */
  const seen = useRef<Set<string>>(new Set());

  useEffect(() => {
    const handle = subscribeNotifications(
      (incoming) => {
        const key = notificationKey(incoming);
        if (seen.current.has(key)) {
          return;
        }
        seen.current.add(key);
        // 도착 순서대로 앞에 쌓는다 — 재생 백로그는 오래된 것부터 오므로 결과가 최신순이 된다.
        setNotifications((prev) => [incoming, ...prev].slice(0, MAX_RETAINED));
      },
      setState,
    );
    return () => handle.close();
  }, []);

  return (
    <main className="mx-auto max-w-2xl p-6 space-y-6">
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-2xl font-bold">내 알림</h1>
        <span
          className={`rounded-full px-3 py-1 text-xs font-medium ring-1 ring-inset ${STATE_STYLE[state]}`}
          data-testid="stream-state"
          role="status"
        >
          {STATE_LABEL[state]}
        </span>
      </header>

      <p className="text-sm text-gray-500">
        정산 확정·결제·투자 체결이 일어나는 즉시 도착합니다. 지난 알림은 보관하지 않으므로,
        이 목록은 <strong className="font-medium text-gray-700">연결된 뒤로 받은 것</strong>입니다.
      </p>

      {notifications.length === 0 ? (
        <p
          className="rounded border border-dashed p-8 text-center text-sm text-gray-500"
          data-testid="notifications-empty"
        >
          연결된 뒤로 도착한 알림이 없습니다.
        </p>
      ) : (
        <ul className="space-y-3" data-testid="notifications-list">
          {notifications.map((n) => (
            <li
              key={notificationKey(n)}
              className="flex items-start gap-3 rounded border p-4 hover:bg-gray-50"
            >
              <span
                className={`shrink-0 rounded px-2 py-1 text-xs font-medium ring-1 ring-inset ${TYPE_STYLE[n.type] ?? TYPE_STYLE.GENERIC}`}
              >
                {TYPE_LABEL[n.type] ?? TYPE_LABEL.GENERIC}
              </span>
              <div className="min-w-0 flex-1">
                <p className="font-medium break-words">{n.subject}</p>
                <p className="mt-1 text-sm text-gray-500 break-words">{n.body}</p>
              </div>
              <time
                className="shrink-0 text-xs text-gray-400 tabular-nums"
                dateTime={n.occurredAt}
              >
                {formatOccurredAt(n.occurredAt)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
