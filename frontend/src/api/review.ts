import api from './axios';
import { ReviewCreateRequest, ReviewUpdateRequest, ReviewResponse } from '@/types';

export const reviewApi = {
  /** POST /reviews */
  createReview: async (request: ReviewCreateRequest): Promise<ReviewResponse> => {
    const response = await api.post<ReviewResponse>('/reviews', request);
    return response.data;
  },

  /**
   * GET /reviews/product/{productId}
   *
   * <p>부가 정보라 호출부(주문 화면·추천 화면)가 실패를 그대로 삼킨다. 전역 토스트도 함께 끈다.
   */
  getProductReviews: async (productId: number): Promise<ReviewResponse[]> => {
    const response = await api.get<ReviewResponse[]>(`/reviews/product/${productId}`, {
      skipAuthToast: true,
    });
    return response.data;
  },

  /** GET /reviews/user/{userId} */
  getUserReviews: async (userId: number): Promise<ReviewResponse[]> => {
    const response = await api.get<ReviewResponse[]>(`/reviews/user/${userId}`);
    return response.data;
  },

  /** PUT /reviews/{id} */
  updateReview: async (id: number, request: ReviewUpdateRequest): Promise<ReviewResponse> => {
    const response = await api.put<ReviewResponse>(`/reviews/${id}`, request);
    return response.data;
  },

  /** DELETE /reviews/{id}?userId= */
  deleteReview: async (id: number, userId: number): Promise<void> => {
    await api.delete(`/reviews/${id}`, { params: { userId } });
  },
};