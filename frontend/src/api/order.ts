import api from './axios';
import { OrderCreateRequest, OrderResponse } from '@/types';

export const orderApi = {
  /**
   * 주문 생성
   * POST /orders
   */
  createOrder: async (request: OrderCreateRequest): Promise<OrderResponse> => {
    const response = await api.post<OrderResponse>('/orders', request);
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
