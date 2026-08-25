import api from './axios';
import { OrderResponse } from '@/types';

/**
 * 취소·환불 <b>신청 → 승인</b> 워크플로 — order-service {@code OrderController}.
 *
 * <p>사용자는 신청만 하고, 상태를 종단(CANCELED·REFUNDED)까지 끌고 가는 것은 운영자다.
 * 이 분리가 곧 권한 모델이다 — 신청 경로는 {@code /orders/{id}/...}, 승인 경로는
 * {@code /orders/admin/{id}/...} 이고 후자는 SecurityConfig 가 ADMIN/MANAGER 로 게이트한다.
 *
 * <p>기존 {@code PATCH /orders/{id}/cancel}(즉시 취소)은 {@code orderApi.cancelOrder} 에 남아 있다.
 * 결제 전(CREATED) 주문처럼 승인이 필요 없는 경우를 위한 별개 경로다.
 */

/** 주문 상태 — 서버 {@code OrderStatus} enum. */
export type OrderStatusValue =
  | 'CREATED'
  | 'PAID'
  | 'SHIPPING_PENDING'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'CANCELLATION_REQUESTED'
  | 'CANCELLATION_APPROVED'
  | 'REFUND_REQUESTED'
  | 'REFUND_COMPLETED'
  | 'CANCELED'
  | 'REFUNDED';

export const ORDER_STATUS_LABEL: Record<OrderStatusValue, string> = {
  CREATED: '주문 완료',
  PAID: '결제 완료',
  SHIPPING_PENDING: '배송 준비',
  IN_TRANSIT: '배송 중',
  DELIVERED: '배송 완료',
  CANCELLATION_REQUESTED: '취소 신청됨',
  CANCELLATION_APPROVED: '취소 승인됨',
  REFUND_REQUESTED: '환불 신청됨',
  REFUND_COMPLETED: '환불 완료',
  CANCELED: '취소됨',
  REFUNDED: '환불됨',
};

/**
 * 사용자가 취소를 <b>신청</b>할 수 있는 상태.
 * 서버 전이표(CREATED·PAID → CANCELLATION_REQUESTED)를 그대로 옮긴 것이다.
 */
export const canRequestCancellation = (status: string): boolean =>
  status === 'CREATED' || status === 'PAID';

/**
 * 사용자가 환불을 <b>신청</b>할 수 있는 상태.
 * 서버 전이표에서 REFUND_REQUESTED 로 갈 수 있는 출발 상태들이다.
 */
export const canRequestRefund = (status: string): boolean =>
  ['PAID', 'SHIPPING_PENDING', 'IN_TRANSIT', 'DELIVERED', 'CANCELLATION_APPROVED'].includes(status);

/**
 * 운영자 승인 대기 상태 — 이 두 개가 곧 승인 큐다.
 *
 * 서버 조회의 status 파라미터로 그대로 넘긴다. 예전에는 전 주문을 받아 아래
 * {@link isAwaitingApproval} 로 걸러냈는데, 그 방식은 목록에 페이징이 붙는 순간
 * <b>첫 페이지 밖의 대기 건을 조용히 빠뜨린다</b> — 큐가 비어 보이는데 실제로는 밀려 있다.
 */
export const AWAITING_APPROVAL_STATUSES = ['CANCELLATION_REQUESTED', 'REFUND_REQUESTED'] as const;

/** 운영자 승인 대기 — 이 두 상태가 곧 승인 큐다. */
export const isAwaitingApproval = (status: string): boolean =>
  (AWAITING_APPROVAL_STATUSES as readonly string[]).includes(status);

export const orderWorkflowApi = {
  /** POST /orders/{id}/cancellation-request — 사용자 취소 신청. */
  requestCancellation: async (orderId: number, reason: string): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>(`/orders/${orderId}/cancellation-request`, { reason });
    return response.data;
  },

  /** POST /orders/{id}/refund-request — 사용자 환불 신청. */
  requestRefund: async (orderId: number, reason: string): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>(`/orders/${orderId}/refund-request`, { reason });
    return response.data;
  },

  /**
   * POST /orders/{id}/request-withdraw — 사용자가 낸 취소·환불 신청을 철회한다.
   *
   * 되돌아갈 상태는 클라이언트가 고르지 않는다. 서버가 상태 이력에서 신청 직전 상태를 읽어
   * 복귀시키므로, 배송 중이던 주문의 환불 신청을 철회하면 배송 중으로 돌아간다.
   */
  withdrawRequest: async (orderId: number, reason?: string): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>(
      `/orders/${orderId}/request-withdraw`, { reason: reason ?? null });
    return response.data;
  },

  /** POST /orders/admin/{id}/cancellation-approve — 운영자 취소 승인 (ADMIN/MANAGER). */
  approveCancellation: async (orderId: number, reason: string): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>(
      `/orders/admin/${orderId}/cancellation-approve`, { reason });
    return response.data;
  },

  /** POST /orders/admin/{id}/refund-approve — 운영자 환불 승인 (ADMIN/MANAGER). */
  approveRefund: async (orderId: number, reason: string): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>(
      `/orders/admin/${orderId}/refund-approve`, { reason });
    return response.data;
  },
};
