import api from './axios';

/**
 * 주문 상태 이력 — order-service {@code AdminOrderStatusHistoryController}.
 *
 * <p>URL 이 {@code /admin/orders/...} 가 아니라 {@code /orders/admin/...} 인 것은 서버 쪽
 * 의도다. 주문 <i>한 건</i>에 대한 운영자 조작이 이미 그 접두사 아래 모여 있고, SecurityConfig
 * 의 {@code /orders/admin/**}(ADMIN·MANAGER)와 게이트웨이의 {@code /orders/**} 라우트가 이미
 * 덮는다 — 새 접두사를 만들면 두 곳에 줄을 더 넣어야 하고 빠뜨리면 각각 조용히 샌다.
 *
 * <p>상태가 <b>문자열</b>이고 유니온 타입이 아니다. 이력은 "지금 코드가 아는 값" 이 아니라
 * "그때 적힌 값" 이라서다. 운영 DB 에는 이미 폐기된 상태값의 행이 남아 있고, 타입으로 좁히면
 * 그 행이 화면에서 사라지거나 파싱에서 터진다.
 */

export interface StatusStep {
  id: number;
  previousStatus: string | null;
  newStatus: string;
  changedBy: string | null;
  reason: string | null;
  changedAt: string;
  /** 이 상태에 머문 시간(초). 마지막 칸은 "지금 몇 초째 여기 있는가" 다. */
  dwellSeconds: number;
}

export interface OrderStatusTimeline {
  orderId: number;
  /** 주문 테이블의 현재 상태. */
  currentStatus: string | null;
  /** 이력의 마지막 도착 상태. 이력이 0건이면 null. */
  lastRecordedStatus: string | null;
  /**
   * 위 둘이 같은가. <b>false 면 이력을 남기지 않은 전이 경로가 있다는 뜻</b>이고,
   * 그게 이 화면이 표 덤프와 다른 이유다. 이력 0건도 false 다(그 자체가 조사할 신호다).
   */
  historyMatchesOrder: boolean;
  steps: StatusStep[];
}

export const orderStatusHistoryApi = {
  /** 주문 한 건의 상태 전이 전부. 페이징이 없다 — 잘린 이력은 찾는 그 한 줄이 없을 때 무용하다. */
  of: async (orderId: number): Promise<OrderStatusTimeline> =>
    (await api.get<OrderStatusTimeline>(`/orders/admin/${orderId}/status-history`)).data,
};
