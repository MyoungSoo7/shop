import api from './axios';
import { CouponResponse, CouponValidateResponse, CouponCreateRequest } from '@/types';

export const couponApi = {
  /**
   * 쿠폰 유효성 검증
   * GET /coupons/{code}/validate?userId=&amount=
   */
  validate: async (code: string, userId: number, amount: number): Promise<CouponValidateResponse> => {
    const response = await api.get<CouponValidateResponse>(`/coupons/${code}/validate`, {
      params: { userId, amount },
    });
    return response.data;
  },

  /**
   * 쿠폰 사용 처리
   * POST /coupons/{code}/use
   */
  use: async (code: string, userId: number, orderId: number): Promise<void> => {
    await api.post(`/coupons/${code}/use`, { userId, orderId });
  },

  /**
   * 전체 쿠폰 목록 (관리자)
   * GET /coupons
   */
  getAll: async (): Promise<CouponResponse[]> => {
    const response = await api.get<CouponResponse[]>('/coupons');
    return response.data;
  },

  /**
   * 쿠폰 생성 (관리자)
   * POST /coupons
   */
  create: async (data: CouponCreateRequest): Promise<CouponResponse> => {
    const response = await api.post<CouponResponse>('/coupons', data);
    return response.data;
  },
};