import api from './axios';
import {
  CouponResponse,
  CouponValidateResponse,
  CouponCreateRequest,
  CouponPreviewLine,
  CouponPreviewResponse,
} from '@/types';

export const couponApi = {
  /**
   * 쿠폰 미리보기 (장바구니 기준)
   * POST /orders/coupon-preview
   *
   * 할인액이 장바구니에 무엇이 담겼는지에 달려 있어서(상품·카테고리 전용 쿠폰) 금액 하나로는
   * 계산할 수 없다. 서버가 주문 생성과 같은 경로로 단가·카테고리를 해석하므로, 여기서 나온
   * 값이 결제에 그대로 적용된다.
   */
  preview: async (
    userId: number,
    couponCode: string | null,
    lines: CouponPreviewLine[],
  ): Promise<CouponPreviewResponse> => {
    const response = await api.post<CouponPreviewResponse>('/orders/coupon-preview', {
      userId,
      lines,
      couponCode,
    });
    return response.data;
  },

  /**
   * 쿠폰 유효성 검증 — 금액만 아는 경로(전체 적용 쿠폰 전용).
   * GET /coupons/{code}/validate?userId=&amount=
   *
   * 상품을 특정하지 않으므로 상품·카테고리 전용 쿠폰은 "쓸 수 있는 상품이 없다"로 응답한다.
   * 장바구니가 있으면 {@link preview} 를 쓴다.
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