import api from './axios';
import {
  MultiItemOrderResponse,
  OrderCreateRequest,
  OrderLineRequest,
  OrderResponse,
} from '@/types';

export const orderApi = {
  /**
   * 주문 생성 (단건, 레거시)
   * POST /orders
   *
   * 수량 개념이 없어 상품 정가 1개로만 주문된다. 서버가 요청 금액과 상품 가격이 다르면 400 으로
   * 거절하므로, 수량·옵션·쿠폰이 붙는 주문은 {@link createMultiItemOrder} 를 써야 한다.
   */
  createOrder: async (request: OrderCreateRequest): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>('/orders', request);
    return response.data;
  },

  /**
   * 주문 생성 (다건/SKU)
   * POST /orders/multi
   *
   * 결제 금액을 클라이언트가 계산하지 않는다. 라인(무엇을 몇 개)만 보내면 서버가 단가·쿠폰 할인·
   * 배송비를 확정하고, 재고 차감과 쿠폰 사용 기록까지 같은 트랜잭션에서 처리한다. 따라서 호출한
   * 쪽은 쿠폰 사용을 따로 기록하면 안 된다 — 두 번 소진된다.
   *
   * @param idempotencyKey 같은 키의 재요청은 새 주문을 만들지 않고 기존 주문을 돌려준다.
   */
  createMultiItemOrder: async (
    userId: number,
    lines: OrderLineRequest[],
    couponCode?: string | null,
    idempotencyKey?: string,
  ): Promise<MultiItemOrderResponse> => {
    const response = await api.post<MultiItemOrderResponse>(
      '/orders/multi',
      { userId, lines, couponCode: couponCode ?? null },
      idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
    );
    return response.data;
  },

  /**
   * 주문 조회
   * GET /orders/{id}
   */
  getOrder: async (id: number): Promise<OrderResponse> => {
    const response = await api.get<OrderResponse>(`/orders/${id}`);
    return response.data;
  },

  /**
   * 사용자별 주문 목록 조회
   * GET /orders/user/{userId}
   */
  getUserOrders: async (userId: number): Promise<OrderResponse[]> => {
    const response = await api.get<OrderResponse[]>(`/orders/user/${userId}`);
    return response.data;
  },

  /**
   * 주문 취소
   * PATCH /orders/{id}/cancel
   */
  cancelOrder: async (id: number): Promise<OrderResponse> => {
    const response = await api.patch<OrderResponse>(`/orders/${id}/cancel`);
    return response.data;
  },
};
