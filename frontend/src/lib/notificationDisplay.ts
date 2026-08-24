import type { PushedNotification } from '@/api/notificationStream';

/**
 * 알림 표시용 순수 함수 — 컴포넌트에서 분리해 둔다.
 *
 * <p>react-refresh 규칙이 "컴포넌트 파일은 컴포넌트만 export" 를 강제하기도 하지만, 더 중요한 이유는
 * 이 둘이 <b>화면 없이도 단독으로 검증돼야 하는 규칙</b>이라는 점이다 — 특히 중복 판정 키는
 * 잘못 잡으면 알림이 조용히 사라지는 종류의 결함이다.
 */

/**
 * 중복 판정 키.
 *
 * <p><b>`id`(SSE 시퀀스)를 키로 쓰면 안 된다.</b> 서버의 스트림은 프로세스 메모리라 재시작하면
 * 시퀀스가 1부터 다시 시작한다(ADR 0041 · 무영속). `id` 로 중복을 걸면 재시작 직후에 도착한
 * <b>새 알림</b>이 이미 본 번호와 겹쳐 화면에서 조용히 버려진다.
 *
 * <p>그래서 발행 서비스가 부여한 도메인 이벤트 UUID(`eventId`)를 우선 키로 쓴다 — 재시작·재생에도
 * 안정적이다. `eventId` 가 없는 경우는 이벤트에서 온 것이 아닌 임시 발송뿐이라
 * (`/internal/notifications/send`), 그때만 시퀀스+발생시각 조합으로 떨어진다.
 */
export const notificationKey = (n: PushedNotification): string =>
  n.eventId ?? `seq:${n.id}@${n.occurredAt}`;

/**
 * UTC ISO → 로컬 시각 문자열.
 *
 * <p>파싱 불가한 값은 <b>원문을 그대로</b> 돌려준다. "Invalid Date" 를 그리거나 현재 시각으로
 * 대체하면 화면이 없는 사실을 지어내는 셈이다 — 서버가 보낸 것을 그대로 보여 주는 편이 정직하다.
 */
export const formatOccurredAt = (iso: string): string => {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString('ko-KR');
};
