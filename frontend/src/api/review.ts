import api from './axios';
import { ReviewCreateRequest, ReviewUpdateRequest, ReviewResponse } from '@/types';

export const reviewApi = {
  /** POST /reviews */
  createReview: async (request: ReviewCreateRequest): Promise<ReviewResponse> => {
    const response = await api.post<ReviewResponse>('/reviews', request);
    return response.data;
  },

  /** GET /reviews/product/{productId} */
  getProductReviews: async (productId: number): Promise<ReviewResponse[]> => {
    const response = await api.get<ReviewResponse[]>(`/reviews/product/${productId}`);
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